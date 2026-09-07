package com.solidus.analytics.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * DB_SCALING_PLAN §8.5 acceptance test - "Analytics should be re-verified
 * against a MySQL-backed ledger".
 *
 * <p>Runs the EXACT CoreLedgerAccess statements against a real
 * MariaDB/MySQL server: the SQL that production executes through Core's
 * TransactionLog connection when Core is in its 2.2.x MySQL network mode.
 * Before 2.1.3 this path did not exist at all - in MySQL mode every
 * Analytics collector read a missing/stale SQLite file and the dashboard
 * pipeline silently died.</p>
 *
 * <p>CI-gated like Core's own MySQL contract tests: skipped unless
 * {@code SOLIDUS_TEST_MYSQL_HOST} is set (the workflow provides a
 * mariadb:11 service container and the SOLIDUS_TEST_* environment).</p>
 *
 * <p>The database used is throwaway ({@code SOLIDUS_TEST_MYSQL_DATABASE}
 * plus this test's suffix) and is dropped after the run; the test never
 * touches any other database on the server.</p>
 */
class CoreLedgerAccessMySqlTest {

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final String PORT = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");

    private static final String DDL = """
        CREATE TABLE transaction_log (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            timestamp BIGINT NOT NULL,
            type VARCHAR(24) NOT NULL,
            player_uuid CHAR(36) NOT NULL,
            player_name VARCHAR(64) NOT NULL,
            target_uuid CHAR(36),
            target_name VARCHAR(64),
            amount DECIMAL(18,2) NOT NULL,
            item_material VARCHAR(128),
            item_quantity INTEGER,
            description VARCHAR(255)
        );
        CREATE TABLE player_balances (
            player_uuid CHAR(36) PRIMARY KEY,
            player_name VARCHAR(64) NOT NULL,
            balance DECIMAL(18,2) NOT NULL DEFAULT 0.00
        );
        CREATE TABLE auction_listings (
            listing_id CHAR(36) PRIMARY KEY,
            seller_uuid CHAR(36),
            material_name VARCHAR(128),
            quantity INTEGER,
            price DECIMAL(18,2) NOT NULL,
            status INTEGER NOT NULL DEFAULT 0
        );
        """;

    private static String jdbcUrl(String database) {
        return "jdbc:mariadb://" + HOST + ":" + PORT + "/" + database + "?user=" + USER
            + "&password=" + PASSWORD + "&allowPublicKeyRetrieval=true&connectTimeout=5000";
    }

    private static Connection openAdmin() throws SQLException {
        return DriverManager.getConnection("jdbc:mariadb://" + HOST + ":" + PORT + "/?user=" + USER
            + "&password=" + PASSWORD + "&allowPublicKeyRetrieval=true&connectTimeout=5000");
    }

    private static String insertRow(Connection conn, String type, String player, String target,
                                    double amount, String material, int qty) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, target_uuid, target_name, amount, item_material, item_quantity) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, type);
            ps.setString(3, player);
            ps.setString(4, player);
            ps.setString(5, target);
            ps.setString(6, target);
            ps.setDouble(7, amount);
            ps.setString(8, material);
            ps.setInt(9, qty);
            ps.executeUpdate();
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void ledgerSqlIsPortableToRealMariaDb() throws Exception {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set - MySQL ledger verification skipped (local/dev run)");

        String testDb = DATABASE + "_ledger_access";
        try (Connection admin = openAdmin()) {
            try (Statement stmt = admin.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS " + testDb);
                stmt.execute("CREATE DATABASE " + testDb);
                stmt.execute("USE " + testDb);
                for (String part : DDL.split(";")) {
                    if (!part.isBlank()) {
                        stmt.execute(part.trim());
                    }
                }
            }
            try {
                // Seed: a bid war + settlement + collect artifact + trade pair,
                // balances, and active/expired listings - the §8.5 scenario set.
                try (Connection conn = DriverManager.getConnection(jdbcUrl(testDb))) {
                    insertRow(conn, "BID_PLACED", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1000.00, "DIAMOND", 1);
                    insertRow(conn, "BID_PLACED", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1200.00, "DIAMOND", 1);
                    insertRow(conn, "BID_REFUNDED", UUID.randomUUID().toString(), null, 1000.00, null, 0);
                    insertRow(conn, "AUCTION_SOLD", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1200.00, "DIAMOND", 1);
                    insertRow(conn, "AUCTION_WON", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1200.00, "DIAMOND", 1);
                    insertRow(conn, "AUCTION_WON", UUID.randomUUID().toString(), null, 1200.00, "DIAMOND", 1);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("INSERT INTO player_balances (player_uuid, player_name, balance) VALUES ('" + UUID.randomUUID() + "', 'Alice', 1000.00)");
                        stmt.execute("INSERT INTO player_balances (player_uuid, player_name, balance) VALUES ('" + UUID.randomUUID() + "', 'Bob', 250.50)");
                        stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, material_name, quantity, price, status) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 'DIAMOND', 1, 99.99, 0)");
                        stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, material_name, quantity, price, status) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 'GOLD', 2, 40.00, 1)");
                    }
                }

                CoreLedgerAccess access = CoreLedgerAccess.forConnectionProvider(work -> {
                    try (Connection conn = DriverManager.getConnection(jdbcUrl(testDb))) {
                        return work.run(conn);
                    }
                });

                // 1. Id-cursor seeding + bounded poll on the MySQL dialect.
                long maxId = access.seedMaxId();
                assertEquals(6L, maxId, "MAX(id) must see all 6 seeded rows");
                List<CoreLedgerAccess.LedgerRow> batch = access.pollSinceId(0L, 100);
                assertEquals(6, batch.size());
                assertTrue(access.pollSinceId(maxId, 100).isEmpty(), "id cursor must exclude processed rows");

                // 2. DECIMAL(18,2) -> cents conversion at exact precision
                //    (row 4 of 6 = the AUCTION_SOLD settlement at 1200.00).
                assertEquals(120000L, batch.get(3).amountCents());

                // 3. Aggregates: money supply 1000.00 + 250.50 = 1250.50 S$.
                assertEquals(125050L, access.moneySupplyCents());
                // 4. Only the ACTIVE listing counts: 99.99 S$.
                CoreLedgerAccess.AuctionStats stats = access.auctionStats();
                assertEquals(1L, stats.activeListings());
                assertEquals(9999L, stats.totalValueCents());
                // 5. Wealth scan arrives DESC with cents.
                List<CoreLedgerAccess.BalanceRow> balances = access.balanceScanDesc();
                assertEquals(2, balances.size());
                assertEquals(100000L, balances.get(0).balanceCents());
                assertEquals("Alice", balances.get(0).playerName());
            }
            finally {
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("DROP DATABASE IF EXISTS " + testDb);
                }
            }
        }
    }
}
