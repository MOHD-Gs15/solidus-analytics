package com.solidus.analytics.storage;

import com.solidus.analytics.SolidusAnalyticsMod;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AnalyticsDatabase {
    private static final String DATABASE_NAME = "analytics.db";
    private static final String CREATE_SNAPSHOTS_TABLE = "    CREATE TABLE IF NOT EXISTS analytics_snapshots (\n        id INTEGER PRIMARY KEY AUTOINCREMENT,\n        timestamp INTEGER NOT NULL,\n        snapshot_type TEXT NOT NULL,\n        total_wealth INTEGER NOT NULL,\n        player_count INTEGER NOT NULL,\n        gini_coefficient REAL NOT NULL,\n        avg_balance INTEGER NOT NULL,\n        median_balance INTEGER NOT NULL,\n        top1_percent_share REAL NOT NULL,\n        money_supply INTEGER NOT NULL,\n        auction_active_listings INTEGER NOT NULL,\n        auction_total_value INTEGER NOT NULL\n    )\n";
    private static final String CREATE_SNAPSHOTS_INDEX = "    CREATE INDEX IF NOT EXISTS idx_snapshots_type_time\n    ON analytics_snapshots (snapshot_type, timestamp DESC)\n";
    private static final String CREATE_DAILY_METRICS_TABLE = "    CREATE TABLE IF NOT EXISTS analytics_daily_metrics (\n        date TEXT PRIMARY KEY,\n        transaction_count INTEGER NOT NULL,\n        transaction_volume INTEGER NOT NULL,\n        shop_buy_count INTEGER NOT NULL,\n        shop_sell_count INTEGER NOT NULL,\n        auction_count INTEGER NOT NULL,\n        pay_transfer_count INTEGER NOT NULL,\n        new_players INTEGER NOT NULL,\n        active_players INTEGER NOT NULL,\n        inflation_rate REAL,\n        top_item_bought TEXT,\n        top_item_sold TEXT\n    )\n";
    private static final String CREATE_ITEM_METRICS_TABLE = "    CREATE TABLE IF NOT EXISTS analytics_item_metrics (\n        date TEXT NOT NULL,\n        material TEXT NOT NULL,\n        buy_count INTEGER NOT NULL,\n        sell_count INTEGER NOT NULL,\n        total_quantity INTEGER NOT NULL,\n        total_value INTEGER NOT NULL,\n        PRIMARY KEY (date, material)\n    )\n";
    private static final String CREATE_METADATA_TABLE = "    CREATE TABLE IF NOT EXISTS analytics_metadata (\n        key TEXT PRIMARY KEY,\n        value TEXT NOT NULL\n    )\n";
    private final ExecutorService asyncExecutor;
    private final String databaseUrl;
    private volatile Connection persistentConnection;
    private volatile boolean initialized = false;
    private final Object connectionLock = new Object();

    public AnalyticsDatabase(String configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir + "/analytics.db";
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Analytics-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void initialize() {
        block18: {
            try {
                this.persistentConnection = DriverManager.getConnection(this.databaseUrl);
                try (Statement stmt = this.persistentConnection.createStatement();){
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA synchronous=NORMAL");
                    stmt.execute("PRAGMA temp_store=MEMORY");
                    stmt.execute("PRAGMA mmap_size=67108864");
                    stmt.execute("PRAGMA cache_size=-2000");
                }
                try (Statement stmt = this.persistentConnection.createStatement()) {
                    stmt.execute(CREATE_SNAPSHOTS_TABLE);
                    stmt.execute(CREATE_SNAPSHOTS_INDEX);
                    stmt.execute(CREATE_DAILY_METRICS_TABLE);
                    stmt.execute(CREATE_ITEM_METRICS_TABLE);
                    stmt.execute(CREATE_METADATA_TABLE);
                }
                this.initialized = true;
                SolidusAnalyticsMod.LOGGER.info("Analytics database initialized successfully.");
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("CRITICAL: Failed to initialize analytics database! Analytics will be disabled.", (Throwable)e);
                if (this.persistentConnection == null) break block18;
                try {
                    this.persistentConnection.close();
                }
                catch (SQLException sQLException) {
                    // empty catch block
                }
                this.persistentConnection = null;
            }
        }
    }

    public void shutdown() {
        // ROBUSTNESS FIX: always stop the worker executor, even when initialize()
        // failed midway - otherwise its daemon thread lingers forever holding the
        // half-open connection state. Ordering preserved: drain queue BEFORE the
        // connection is closed.
        this.asyncExecutor.shutdown();
        try {
            if (!this.asyncExecutor.awaitTermination(30L, TimeUnit.SECONDS)) {
                this.asyncExecutor.shutdownNow();
                SolidusAnalyticsMod.LOGGER.warn("Analytics executor forced shutdown after timeout.");
            }
        }
        catch (InterruptedException e) {
            this.asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (this.persistentConnection != null) {
            try {
                this.persistentConnection.close();
                SolidusAnalyticsMod.LOGGER.info("Analytics database connection closed.");
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to close analytics database connection", (Throwable)e);
            }
        }
        this.initialized = false;
        SolidusAnalyticsMod.LOGGER.info("Analytics database shut down complete.");
    }

    public void insertSnapshot(Snapshot snapshot) {
        this.ensureInitialized();
        String sql = "    INSERT INTO analytics_snapshots\n    (timestamp, snapshot_type, total_wealth, player_count, gini_coefficient,\n     avg_balance, median_balance, top1_percent_share, money_supply,\n     auction_active_listings, auction_total_value)\n    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\n";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql);){
                ps.setLong(1, snapshot.timestamp());
                ps.setString(2, snapshot.snapshotType());
                ps.setLong(3, snapshot.totalWealth());
                ps.setInt(4, snapshot.playerCount());
                ps.setDouble(5, snapshot.giniCoefficient());
                ps.setLong(6, snapshot.avgBalance());
                ps.setLong(7, snapshot.medianBalance());
                ps.setDouble(8, snapshot.top1PercentShare());
                ps.setLong(9, snapshot.moneySupply());
                ps.setInt(10, snapshot.auctionActiveListings());
                ps.setLong(11, snapshot.auctionTotalValue());
                ps.executeUpdate();
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to insert analytics snapshot", (Throwable)e);
            }
        }
    }

    public void insertSnapshotAsync(Snapshot snapshot) {
        this.ensureInitialized();
        this.asyncExecutor.submit(() -> this.insertSnapshot(snapshot));
    }

    public Snapshot getLatestSnapshot() {
        this.ensureInitialized();
        String sql = "SELECT * FROM analytics_snapshots ORDER BY timestamp DESC LIMIT 1";
        Object object = this.connectionLock;
        synchronized (object) {
            try (Statement stmt = this.persistentConnection.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    if (!rs.next()) return null;
                    return this.mapSnapshot(rs);
                }
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get latest snapshot", (Throwable)e);
            }
            return null;
        }
    }

    public Snapshot getSnapshotBefore(long timestamp) {
        this.ensureInitialized();
        String sql = "SELECT * FROM analytics_snapshots WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql)) {
                ps.setLong(1, timestamp);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return this.mapSnapshot(rs);
                }
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get snapshot before timestamp", (Throwable)e);
            }
            return null;
        }
    }

    public List<Snapshot> getSnapshots(String snapshotType, int limit) {
        this.ensureInitialized();
        ArrayList<Snapshot> snapshots = new ArrayList<Snapshot>();
        Object object = this.connectionLock;
        synchronized (object) {
            block32: {
                try {
                    if (snapshotType != null) {
                        String sql = "SELECT * FROM analytics_snapshots WHERE snapshot_type = ? ORDER BY timestamp DESC LIMIT ?";
                        try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql);){
                            ps.setString(1, snapshotType);
                            ps.setInt(2, limit);
                            try (ResultSet rs = ps.executeQuery();){
                                while (rs.next()) {
                                    snapshots.add(this.mapSnapshot(rs));
                                }
                                break block32;
                            }
                        }
                    }
                    String sql = "SELECT * FROM analytics_snapshots ORDER BY timestamp DESC LIMIT ?";
                    try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql);){
                        ps.setInt(1, limit);
                        try (ResultSet rs = ps.executeQuery();){
                            while (rs.next()) {
                                snapshots.add(this.mapSnapshot(rs));
                            }
                        }
                    }
                }
                catch (SQLException e) {
                    SolidusAnalyticsMod.LOGGER.error("Failed to get snapshots", (Throwable)e);
                }
            }
        }
        return snapshots;
    }

    public void upsertDailyMetrics(DailyMetrics metrics) {
        this.ensureInitialized();
        // D-6 fix: additive upsert. The in-memory day counters restart at 0
        // after a crash/restart and the poll cursor re-seeds at MAX(id), so the
        // post-restart partial-day row counts ONLY post-restart transactions.
        // REPLACE used to clobber the pre-crash partial day; ADD merges the two
        // halves into the correct full-day totals (ARCHITECTURE §6.1).
        // non-mergeable fields (rates, top items) keep the newest observation.
        String sql = "    INSERT INTO analytics_daily_metrics\n    (date, transaction_count, transaction_volume, shop_buy_count, shop_sell_count,\n     auction_count, pay_transfer_count, new_players, active_players,\n     inflation_rate, top_item_bought, top_item_sold)\n    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\n    ON CONFLICT(date) DO UPDATE SET\n     transaction_count = transaction_count + excluded.transaction_count,\n     transaction_volume = transaction_volume + excluded.transaction_volume,\n     shop_buy_count = shop_buy_count + excluded.shop_buy_count,\n     shop_sell_count = shop_sell_count + excluded.shop_sell_count,\n     auction_count = auction_count + excluded.auction_count,\n     pay_transfer_count = pay_transfer_count + excluded.pay_transfer_count,\n     new_players = MAX(new_players, excluded.new_players),\n     active_players = MAX(active_players, excluded.active_players),\n     inflation_rate = excluded.inflation_rate,\n     top_item_bought = COALESCE(excluded.top_item_bought, top_item_bought),\n     top_item_sold = COALESCE(excluded.top_item_sold, top_item_sold)\n";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql);){
                ps.setString(1, metrics.date());
                ps.setInt(2, metrics.transactionCount());
                ps.setLong(3, metrics.transactionVolume());
                ps.setInt(4, metrics.shopBuyCount());
                ps.setInt(5, metrics.shopSellCount());
                ps.setInt(6, metrics.auctionCount());
                ps.setInt(7, metrics.payTransferCount());
                ps.setInt(8, metrics.newPlayers());
                ps.setInt(9, metrics.activePlayers());
                if (metrics.inflationRate() != null) {
                    ps.setDouble(10, metrics.inflationRate());
                } else {
                    ps.setNull(10, 7);
                }
                ps.setString(11, metrics.topItemBought());
                ps.setString(12, metrics.topItemSold());
                ps.executeUpdate();
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to upsert daily metrics", (Throwable)e);
            }
        }
    }

    public void upsertDailyMetricsAsync(DailyMetrics metrics) {
        this.ensureInitialized();
        this.asyncExecutor.submit(() -> this.upsertDailyMetrics(metrics));
    }

    public DailyMetrics getDailyMetrics(String date) {
        this.ensureInitialized();
        String sql = "SELECT * FROM analytics_daily_metrics WHERE date = ?";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql)) {
                ps.setString(1, date);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return this.mapDailyMetrics(rs);
                }
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get daily metrics for date: {}", (Object)date, (Object)e);
            }
            return null;
        }
    }

    public List<DailyMetrics> getRecentDailyMetrics(int limit) {
        this.ensureInitialized();
        ArrayList<DailyMetrics> metrics = new ArrayList<DailyMetrics>();
        String sql = "SELECT * FROM analytics_daily_metrics ORDER BY date DESC LIMIT ?";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql);){
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        metrics.add(this.mapDailyMetrics(rs));
                    }
                }
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get recent daily metrics", (Throwable)e);
            }
        }
        return metrics;
    }

    public void upsertItemMetrics(ItemMetrics metrics) {
        this.ensureInitialized();
        String sql = "    INSERT OR REPLACE INTO analytics_item_metrics\n    (date, material, buy_count, sell_count, total_quantity, total_value)\n    VALUES (?, ?, ?, ?, ?, ?)\n";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql);){
                ps.setString(1, metrics.date());
                ps.setString(2, metrics.material());
                ps.setInt(3, metrics.buyCount());
                ps.setInt(4, metrics.sellCount());
                ps.setInt(5, metrics.totalQuantity());
                ps.setLong(6, metrics.totalValue());
                ps.executeUpdate();
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to upsert item metrics", (Throwable)e);
            }
        }
    }

    public void upsertItemMetricsAsync(ItemMetrics metrics) {
        this.ensureInitialized();
        this.asyncExecutor.submit(() -> this.upsertItemMetrics(metrics));
    }

    public List<ItemMetrics> getItemMetrics(String date) {
        this.ensureInitialized();
        ArrayList<ItemMetrics> metrics = new ArrayList<ItemMetrics>();
        String sql = "SELECT * FROM analytics_item_metrics WHERE date = ? ORDER BY total_value DESC";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql);){
                ps.setString(1, date);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        metrics.add(this.mapItemMetrics(rs));
                    }
                }
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get item metrics for date: {}", (Object)date, (Object)e);
            }
        }
        return metrics;
    }

    public int cleanupOldSnapshots(int retentionDays) {
        this.ensureInitialized();
        long cutoffTimestamp = System.currentTimeMillis() - (long)retentionDays * 86400000L;
        String sql = "DELETE FROM analytics_snapshots WHERE timestamp < ?";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql)) {
                ps.setLong(1, cutoffTimestamp);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    SolidusAnalyticsMod.LOGGER.info("Cleaned up {} snapshots older than {} days.", (Object)deleted, (Object)retentionDays);
                }
                return deleted;
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to cleanup old snapshots", (Throwable)e);
                return -1;
            }
        }
    }

    public int cleanupOldDailyMetrics(int retentionDays) {
        this.ensureInitialized();
        String cutoffDate = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays).toString();
        String sql = "DELETE FROM analytics_daily_metrics WHERE date < ?";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql)) {
                ps.setString(1, cutoffDate);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    SolidusAnalyticsMod.LOGGER.info("Cleaned up {} daily metrics older than {} days.", (Object)deleted, (Object)retentionDays);
                }
                return deleted;
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to cleanup old daily metrics", (Throwable)e);
                return -1;
            }
        }
    }

    public int cleanupOldItemMetrics(int retentionDays) {
        this.ensureInitialized();
        String cutoffDate = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays).toString();
        String sql = "DELETE FROM analytics_item_metrics WHERE date < ?";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql)) {
                ps.setString(1, cutoffDate);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    SolidusAnalyticsMod.LOGGER.info("Cleaned up {} item metrics older than {} days.", (Object)deleted, (Object)retentionDays);
                }
                return deleted;
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to cleanup old item metrics", (Throwable)e);
                return -1;
            }
        }
    }

    public void runCleanup(int retentionDays) {
        this.cleanupOldSnapshots(retentionDays);
        this.cleanupOldDailyMetrics(retentionDays);
        this.cleanupOldItemMetrics(retentionDays);
    }

    public ExecutorService getExecutor() {
        return this.asyncExecutor;
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    private Snapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getLong("timestamp"), rs.getString("snapshot_type"), rs.getLong("total_wealth"), rs.getInt("player_count"), rs.getDouble("gini_coefficient"), rs.getLong("avg_balance"), rs.getLong("median_balance"), rs.getDouble("top1_percent_share"), rs.getLong("money_supply"), rs.getInt("auction_active_listings"), rs.getLong("auction_total_value"));
    }

    private DailyMetrics mapDailyMetrics(ResultSet rs) throws SQLException {
        double inflationRate = rs.getDouble("inflation_rate");
        return new DailyMetrics(rs.getString("date"), rs.getInt("transaction_count"), rs.getLong("transaction_volume"), rs.getInt("shop_buy_count"), rs.getInt("shop_sell_count"), rs.getInt("auction_count"), rs.getInt("pay_transfer_count"), rs.getInt("new_players"), rs.getInt("active_players"), rs.wasNull() ? null : Double.valueOf(inflationRate), rs.getString("top_item_bought"), rs.getString("top_item_sold"));
    }

    private ItemMetrics mapItemMetrics(ResultSet rs) throws SQLException {
        return new ItemMetrics(rs.getString("date"), rs.getString("material"), rs.getInt("buy_count"), rs.getInt("sell_count"), rs.getInt("total_quantity"), rs.getLong("total_value"));
    }

    public String getMetadataValue(String key) {
        this.ensureInitialized();
        String sql = "SELECT value FROM analytics_metadata WHERE key = ?";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return rs.getString("value");
                }
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to get metadata value for key: {}", (Object)key, (Object)e);
            }
            return null;
        }
    }

    public void setMetadataValue(String key, String value) {
        this.ensureInitialized();
        String sql = "INSERT OR REPLACE INTO analytics_metadata (key, value) VALUES (?, ?)";
        Object object = this.connectionLock;
        synchronized (object) {
            try (PreparedStatement ps = this.persistentConnection.prepareStatement(sql);){
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to set metadata value for key: {}", (Object)key, (Object)e);
            }
        }
    }

    public void executeRaw(String sql) throws SQLException {
        this.ensureInitialized();
        Object object = this.connectionLock;
        synchronized (object) {
            try (Statement stmt = this.persistentConnection.createStatement();){
                stmt.execute(sql);
            }
        }
    }

    private void ensureInitialized() {
        if (!this.initialized) {
            throw new IllegalStateException("AnalyticsDatabase accessed before initialization!");
        }
    }

    public record Snapshot(long timestamp, String snapshotType, long totalWealth, int playerCount, double giniCoefficient, long avgBalance, long medianBalance, double top1PercentShare, long moneySupply, int auctionActiveListings, long auctionTotalValue) {
    }

    public record DailyMetrics(String date, int transactionCount, long transactionVolume, int shopBuyCount, int shopSellCount, int auctionCount, int payTransferCount, int newPlayers, int activePlayers, Double inflationRate, String topItemBought, String topItemSold) {
    }

    public record ItemMetrics(String date, String material, int buyCount, int sellCount, int totalQuantity, long totalValue) {
    }
}
