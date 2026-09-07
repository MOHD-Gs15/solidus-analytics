package com.solidus.analytics.storage;

import com.solidus.analytics.SolidusAnalyticsMod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * CoreLedgerAccess - MySQL-era (DB_SCALING_PLAN §8.5) read path to the
 * Solidus Core ledger.
 *
 * <p>Until 2.1.2 every Analytics collector read Core's SQLite files directly
 * (economy.db / auctions.db through {@link DirectDb}). That breaks silently
 * the moment Core runs its 2.2.x MySQL network mode: the ledger, the balance
 * table and the auction tables live in MariaDB/MySQL, the local
 * {@code economy.db} file no longer exists (or is a frozen pre-cutover
 * copy), and the whole dashboard pipeline died with it.</p>
 *
 * <p>The fix runs THROUGH Core instead of around it: Core's
 * {@code TransactionLog.withConnection(SqlWork)} (public since core 2.2.4)
 * hands out a live JDBC connection to whatever backend Core is configured
 * for - the shared SQLite connection in single-server mode, a pooled MySQL
 * connection in network mode. One code path, both dialects, zero new
 * Analytics dependencies (Core's own driver and pool do the driving).</p>
 *
 * <p>Accessibility contract: every query here is SELECT-only and bounded
 * (LIMIT or aggregate). Analytics must never write to Core's database
 * through this bridge - the queries are the same portability-audited
 * statements the direct-file path already used (both dialects share the
 * column layout; money columns are DECIMAL S$ in MySQL and REAL in SQLite,
 * converted to cents on read exactly like the file path does).</p>
 *
 * <p>The production seam is a {@link Proxy} over Core's public
 * {@code TransactionLog$SqlWork} interface, so tests can drive the exact
 * same SQL through a plain {@link Connection} provider without Core on the
 * classpath ({@link #forConnectionProvider(ConnectionProvider)}).</p>
 */
public final class CoreLedgerAccess {

    /** One ledger row, already converted to the Analytics cents contract. */
    public record LedgerRow(long id, String type, String playerUuid, String targetUuid,
                            long amountCents, String itemMaterial, int itemQuantity) {
    }

    /** One balance row (player_balances), DESC order, cents contract. */
    public record BalanceRow(String playerName, long balanceCents) {
    }

    /** COUNT + SUM pair for auction_listings (status = 0), cents contract. */
    public record AuctionStats(long activeListings, long totalValueCents) {
    }

    /**
     * SQL execution seam. Production wires this to Core's
     * {@code TransactionLog.withConnection(SqlWork)}; tests hand a plain
     * connection provider (SQLite in-memory, real MariaDB, ...).
     */
    public interface ConnectionProvider {
        Object run(SqlWorkAdapter work) throws SQLException;
    }

    /** Functional mirror of Core's SqlWork for the provider seam. */
    public interface SqlWorkAdapter {
        Object run(Connection conn) throws SQLException;
    }

    private final ConnectionProvider provider;

    private CoreLedgerAccess(ConnectionProvider provider) {
        this.provider = provider;
    }

    /**
     * Builds the production access path over a live Core TransactionLog
     * instance (fetched reflectively by the caller). Returns null when the
     * instance is missing - callers fall back to the direct-file path.
     */
    public static CoreLedgerAccess create(Object coreTransactionLog) {
        if (coreTransactionLog == null) {
            return null;
        }
        try {
            final Class<?> sqlWorkClass = Class.forName("com.solidus.economy.TransactionLog$SqlWork");
            final Object log = coreTransactionLog;
            final Method withConnection = log.getClass().getMethod("withConnection", sqlWorkClass);
            ConnectionProvider provider = work -> {
                InvocationHandler handler = (proxy, method, args) -> {
                    if ("run".equals(method.getName())) {
                        return work.run((Connection) args[0]);
                    }
                    throw new IllegalStateException("Unexpected SqlWork method: " + method);
                };
                Object sqlWorkProxy = Proxy.newProxyInstance(
                    CoreLedgerAccess.class.getClassLoader(),
                    new Class<?>[]{sqlWorkClass},
                    handler);
                try {
                    return withConnection.invoke(log, sqlWorkProxy);
                }
                catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof SQLException sqlException) {
                        throw sqlException;
                    }
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new SQLException("Core withConnection failed", cause);
                }
                catch (IllegalAccessException e) {
                    throw new SQLException("Core TransactionLog.withConnection is not accessible", e);
                }
            };
            return new CoreLedgerAccess(provider);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to wire CoreLedgerAccess to the Core transaction log", (Throwable) e);
            return null;
        }
    }

    /**
     * Verification seam: plain connection provider (SQLite file, in-memory
     * DB, real MariaDB...). Public since 2.1.4 so the premium/cloud collector
     * bridge tests can drive the exact production SQL without Core on the
     * classpath. Production code must use {@link #create(Object)} instead.
     */
    public static CoreLedgerAccess forConnectionProvider(ConnectionProvider provider) {
        return new CoreLedgerAccess(provider);
    }

    private <T> T query(final SqlWorkAdapter work) throws SQLException {
        Object result = provider.run(work);
        if (result == null) {
            throw new SQLException("Core ledger query returned null");
        }
        @SuppressWarnings("unchecked")
        T typed = (T) result;
        return typed;
    }

    /**
     * Generic read seam for the remaining premium/cloud collectors
     * (FraudDetector, EconomyCollector - 2.1.4). The work object runs on the
     * SAME live Core connection that the fixed query methods use: a shared
     * SQLite connection in single-server mode, a pooled MySQL connection in
     * network mode.
     *
     * <p>Contract (extends the accessibility contract in the class javadoc):
     * the work object may only issue SELECT statements and must bound itself
     * (LIMIT / aggregates). It must never close the handed-out connection -
     * its lifecycle belongs to Core's pool. Analytics must never write to
     * Core's database through this seam.</p>
     */
    public <T> T read(SqlWorkAdapter work) throws SQLException {
        return query(work);
    }

    // ---------------------------------------------------------------
    // Ledger polling (id-cursor, identical semantics to the file path)
    // ---------------------------------------------------------------

    /** Seeds the poll cursor: MAX(id) of the shared transaction_log. */
    public long seedMaxId() throws SQLException {
        return query(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MAX(id) AS max_id FROM transaction_log")) {
                if (rs.next()) {
                    // getLong BEFORE wasNull: wasNull() reports the LAST column
                    // read, so reading first is the only correct order.
                    long maxId = rs.getLong("max_id");
                    if (!rs.wasNull()) {
                        return maxId;
                    }
                }
                return 0L;
            }
        });
    }

    /**
     * Reads ledger rows with id &gt; sinceId in id order (bounded). The row
     * set is buffered BEFORE any counter is applied, so a failure here can
     * never double-count (two-phase poll).
     */
    public List<LedgerRow> pollSinceId(final long sinceId, final int cap) throws SQLException {
        return query(conn -> {
            String sql = "SELECT id, type, player_uuid, target_uuid, amount, item_material, item_quantity "
                + "FROM transaction_log WHERE id > ? ORDER BY id ASC LIMIT ?";
            List<LedgerRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, sinceId);
                ps.setInt(2, Math.max(1, cap));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String type = rs.getString("type");
                        String playerUuid = rs.getString("player_uuid");
                        String targetUuid = rs.getString("target_uuid");
                        // Money columns are DECIMAL S$ (MySQL) / REAL (SQLite):
                        // convert to the Analytics cents contract on read.
                        long amountCents = Math.round(rs.getDouble("amount") * 100.0);
                        String material = rs.getString("item_material");
                        int quantity = rs.getInt("item_quantity");
                        rows.add(new LedgerRow(id, type, playerUuid, targetUuid, amountCents, material, quantity));
                    }
                }
            }
            return rows;
        });
    }

    // ---------------------------------------------------------------
    // Aggregate reads (money supply, shop throughput, auctions, balances)
    // ---------------------------------------------------------------

    /** SUM(player_balances.balance) in cents. */
    public long moneySupplyCents() throws SQLException {
        return query(conn -> {
            String sql = "SELECT COALESCE(SUM(balance), 0) AS total_wealth FROM player_balances";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (!rs.next()) {
                    return 0L;
                }
                return Math.round(rs.getDouble("total_wealth") * 100.0);
            }
        });
    }

    /** SUM(ABS(amount)) over shop rows in the last 24h window, cents. */
    public long shopThroughputCentsSince(final long sinceEpochMs) throws SQLException {
        return query(conn -> {
            String sql = "SELECT COALESCE(SUM(ABS(amount)), 0) AS shop_volume FROM transaction_log "
                + "WHERE type IN ('SHOP_BUY', 'SHOP_SELL') AND timestamp > ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, sinceEpochMs);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return 0L;
                    }
                    return Math.round(rs.getDouble("shop_volume") * 100.0);
                }
            }
        });
    }

    /** COUNT(*) + SUM(price) for active auction listings (status = 0), cents. */
    public AuctionStats auctionStats() throws SQLException {
        return query(conn -> {
            String sql = "SELECT COUNT(*) AS cnt, COALESCE(SUM(price), 0) AS total_val "
                + "FROM auction_listings WHERE status = 0";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (!rs.next()) {
                    return new AuctionStats(0L, 0L);
                }
                return new AuctionStats(rs.getLong("cnt"), Math.round(rs.getDouble("total_val") * 100.0));
            }
        });
    }

    /** All balances DESC by amount (wealth distribution + snapshots), cents. */
    public List<BalanceRow> balanceScanDesc() throws SQLException {
        return query(conn -> {
            String sql = "SELECT player_name, balance FROM player_balances ORDER BY balance DESC";
            List<BalanceRow> rows = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String name = rs.getString("player_name");
                    long cents = Math.round(rs.getDouble("balance") * 100.0);
                    rows.add(new BalanceRow(name == null || name.isBlank() ? "unknown" : name, cents));
                }
            }
            return rows;
        });
    }
}
