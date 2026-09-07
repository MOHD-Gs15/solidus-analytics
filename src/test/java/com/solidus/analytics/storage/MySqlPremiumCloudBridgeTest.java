package com.solidus.analytics.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.solidus.analytics.cloud.EconomyCollector;
import com.solidus.analytics.premium.FraudDetector;

/**
 * The 2.1.4 completion of the DB_SCALING_PLAN &sect;8.5 flow: AFTER
 * {@code /solidus-admin storage migrate} moves Core to MySQL and the server
 * restarts, EVERY Analytics reader - not just the engine collectors shipped
 * in 2.1.3, but also the premium FraudDetector and the cloud
 * EconomyCollector - must read the MIGRATED ledger through Core's own
 * connection.
 *
 * <p>This test reproduces the post-migration state against a REAL
 * MariaDB/MySQL server: the tables below are the actual Core schema
 * (docs/sql/mysql/001_init.sql - CHAR(36) uuid column, DECIMAL(18,2) money,
 * expire_timestamp / seller_name on listings, pending_notifications) and the
 * collectors run on ONE shared connection provider, exactly like Core's
 * pooled connection in production.</p>
 *
 * <p>CI-gated like the &sect;8.5 acceptance test: skipped unless
 * {@code SOLIDUS_TEST_MYSQL_HOST} is set (the workflow provides a
 * mariadb:11 service container and the SOLIDUS_TEST_* environment). The
 * database used is throwaway and dropped after the run.</p>
 */
class MySqlPremiumCloudBridgeTest {

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final String PORT = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");

    /** Verbatim subset of Core's docs/sql/mysql/001_init.sql (real column names/types). */
    private static final String DDL = """
        CREATE TABLE transaction_log (
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            timestamp BIGINT NOT NULL,
            type VARCHAR(32) NOT NULL,
            player_uuid CHAR(36) NOT NULL,
            player_name VARCHAR(64) NOT NULL,
            target_uuid CHAR(36),
            target_name VARCHAR(64),
            amount DECIMAL(18,2) NOT NULL,
            item_material VARCHAR(128),
            item_quantity INTEGER,
            description TEXT,
            KEY idx_transaction_player (player_uuid, timestamp DESC)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        CREATE TABLE player_balances (
            uuid CHAR(36) PRIMARY KEY NOT NULL,
            player_name VARCHAR(64) NOT NULL,
            balance DECIMAL(18,2) NOT NULL DEFAULT 0.00,
            last_updated BIGINT NOT NULL,
            KEY idx_balance_rank (balance DESC)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        CREATE TABLE pending_notifications (
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            timestamp BIGINT NOT NULL,
            player_uuid CHAR(36) NOT NULL,
            message TEXT NOT NULL,
            KEY idx_notifications_player (player_uuid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        CREATE TABLE auction_listings (
            listing_id CHAR(36) PRIMARY KEY NOT NULL,
            seller_uuid CHAR(36) NOT NULL,
            seller_name VARCHAR(64) NOT NULL,
            material_name VARCHAR(128) NOT NULL,
            quantity INTEGER NOT NULL,
            item_nbt MEDIUMTEXT,
            price DECIMAL(18,2) NOT NULL,
            listed_timestamp BIGINT NOT NULL,
            expire_timestamp BIGINT NOT NULL,
            status INTEGER NOT NULL DEFAULT 0,
            KEY idx_active_listings (status, expire_timestamp)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;

    private static String jdbcUrl(String database) {
        return "jdbc:mariadb://" + HOST + ":" + PORT + "/" + database + "?user=" + USER
            + "&password=" + PASSWORD + "&allowPublicKeyRetrieval=true&connectTimeout=5000";
    }

    private static Connection openAdmin() throws SQLException {
        return DriverManager.getConnection("jdbc:mariadb://" + HOST + ":" + PORT + "/?user=" + USER
            + "&password=" + PASSWORD + "&allowPublicKeyRetrieval=true&connectTimeout=5000");
    }

    private static void insertLedger(Connection conn, long ts, String type, String playerUuid,
                                     String playerName, String targetUuid, String targetName,
                                     double amount, String material, Integer qty) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, target_uuid, target_name, amount, item_material, item_quantity) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, ts);
            ps.setString(2, type);
            ps.setString(3, playerUuid);
            ps.setString(4, playerName);
            ps.setString(5, targetUuid);
            ps.setString(6, targetName);
            ps.setDouble(7, amount);
            ps.setString(8, material);
            if (qty == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setInt(9, qty);
            }
            ps.executeUpdate();
        }
    }

    @Test
    void migratedMysqlLedgerServesThePremiumAndCloudCollectors() throws Exception {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set - premium/cloud bridge verification skipped (local/dev run)");

        String testDb = DATABASE + "_premium_cloud";
        long now = System.currentTimeMillis();
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
                String alice = UUID.randomUUID().toString();
                String bob = UUID.randomUUID().toString();
                // The migrated ledger: whale income + small earners (rapid
                // wealth), an Alice<->Bob ping-pong (circular), a zero-value
                // bypass row, an auction settlement, balances, one live and
                // one expired listing, and queued notifications.
                try (Connection conn = DriverManager.getConnection(jdbcUrl(testDb))) {
                    // id order MUST follow chronological order (Core assigns
                    // ids monotonically): distinct rising timestamps keep the
                    // priceTrend chronological-ordering contract checkable.
                    insertLedger(conn, now - 2000, "SHOP_SELL", alice, "Alice", null, null, 100.10, "DIAMOND", 1);
                    insertLedger(conn, now - 1000, "SHOP_SELL", alice, "Alice", null, null, 100.10, "DIAMOND", 1);
                    insertLedger(conn, now, "SHOP_SELL", alice, "Alice", null, null, 100.05, "DIAMOND", 1);
                    insertLedger(conn, now, "SHOP_SELL", bob, "Bob", null, null, 0.50, "WHEAT", 64);
                    for (int i = 1; i <= 6; i++) {
                        insertLedger(conn, now, "SHOP_SELL", UUID.randomUUID().toString(), "P" + i, null, null, 0.50, "WHEAT", 1);
                    }
                    insertLedger(conn, now, "PAY_SEND", alice, "Alice", bob, "Bob", 0.50, null, null);
                    insertLedger(conn, now, "PAY_SEND", bob, "Bob", alice, "Alice", 0.50, null, null);
                    insertLedger(conn, now, "PAY_SEND", UUID.randomUUID().toString(), "Eve", null, null, 0.00, null, null);
                    insertLedger(conn, now - 60_000, "AUCTION_SOLD", bob, "Bob", alice, "Alice", 75.25, "GOLD", 3);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("INSERT INTO player_balances (uuid, player_name, balance, last_updated) "
                            + "VALUES ('" + alice + "', 'Alice', 1000.00, " + now + ")");
                        stmt.execute("INSERT INTO player_balances (uuid, player_name, balance, last_updated) "
                            + "VALUES ('" + bob + "', 'Bob', 250.50, " + (now - 1000) + ")");
                        stmt.execute("INSERT INTO pending_notifications (timestamp, player_uuid, message) "
                            + "VALUES (" + now + ", '" + bob + "', 'you were outbid')");
                        stmt.execute("INSERT INTO pending_notifications (timestamp, player_uuid, message) "
                            + "VALUES (" + now + ", '" + bob + "', 'payment received')");
                        stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, seller_name, material_name, quantity, price, listed_timestamp, expire_timestamp, status) "
                            + "VALUES ('" + UUID.randomUUID() + "', '" + bob + "', 'Bob', 'DIAMOND', 1, 99.99, " + now + ", " + (now + 3600_000) + ", 0)");
                        stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, seller_name, material_name, quantity, price, listed_timestamp, expire_timestamp, status) "
                            + "VALUES ('" + UUID.randomUUID() + "', '" + bob + "', 'Bob', 'GOLD', 2, 40.00, " + (now - 7200_000) + ", " + (now - 3600_000) + ", 0)");
                    }
                }

                // The single shared provider mirrors Core's pooled connection
                // handing out one live backend connection per SqlWork.
                CoreLedgerAccess access = CoreLedgerAccess.forConnectionProvider(work -> {
                    try (Connection conn = DriverManager.getConnection(jdbcUrl(testDb))) {
                        return work.run(conn);
                    }
                });

                // ---- PREMIUM: FraudDetector reads the migrated ledger ----
                FraudDetector detector = new FraudDetector(null, "/nonexistent/economy.db");
                detector.setLedgerAccess(access);
                List<FraudDetector.FraudAlert> alerts = detector.runAllChecks();

                assertTrue(alerts.stream().anyMatch(a -> a.type == FraudDetector.FraudAlert.Type.RAPID_WEALTH_GAIN
                        && "Alice".equals(a.playerName) && a.description.contains("300.75")),
                    "rapid-wealth check must run on the MySQL dialect with decimal precision intact "
                        + "(Alice's income: 3 shop rows 300.25 + 0.50 PAY_SEND = 300.75); got: " + alerts);
                assertTrue(alerts.stream().anyMatch(a -> a.type == FraudDetector.FraudAlert.Type.CIRCULAR_TRADING),
                    "circular 2-loop must be detected on the migrated ledger");
                assertTrue(alerts.stream().anyMatch(a -> a.type == FraudDetector.FraudAlert.Type.ZERO_VALUE_TRANSFER),
                    "the zero-value bypass row must be flagged");
                assertTrue(alerts.stream().noneMatch(a -> a.type == FraudDetector.FraudAlert.Type.HIGH_FREQUENCY),
                    "no high-frequency false positive at ~12 rows/minute");

                // ---- CLOUD: EconomyCollector reads the migrated ledger ----
                EconomyCollector collector = new EconomyCollector("/nonexistent/economy.db",
                    "/nonexistent/auctions.db", "/nonexistent/analytics.db", null, null);
                collector.setLedgerAccess(access);

                var search = collector.txSearch("SHOP_SELL", null, null, null, null, null, 10);
                assertEquals(10, search.getAsJsonArray("rows").size(),
                    "all 10 SHOP_SELL rows (3 Alice + 1 Bob + 6 filler) match the type filter within LIMIT 10");
                assertEquals(50L, search.getAsJsonArray("rows").get(0).getAsJsonObject().get("amountC").getAsLong(),
                    "newest row first (id DESC): the last filler row's 0.50 -> 50 cents");
                assertEquals(10010L, search.getAsJsonArray("rows").get(9).getAsJsonObject().get("amountC").getAsLong(),
                    "DECIMAL(18,2) 100.10 -> 10010 cents, no float drift (Alice's oldest SHOP_SELL row, last in DESC order)");

                String resolved = collector.uuidForName("Alice");
                assertEquals(alice, resolved, "player_balances.uuid must resolve through the bridge");
                assertTrue(collector.isKnownPlayer("Alice"));
                assertFalse(collector.isKnownPlayer("Zed"));
                assertTrue(collector.knownPlayerNames(10).contains("Bob"));

                var minFiltered = collector.txSearch(null, null, null, 6000L, null, null, 10);
                assertEquals(4, minFiltered.getAsJsonArray("rows").size(),
                    "minC=60 S$ keeps Alice's three shop rows (2x100.10 + 100.05) and the 75.25 AUCTION_SOLD");

                var profile = collector.playerProfile("Alice", null);
                assertEquals(100000L, profile.get("balC").getAsLong(), "balance 1000.00 -> 100000 cents");
                assertEquals(now, profile.get("lastSeen").getAsLong());
                assertEquals(4, profile.getAsJsonArray("tx").size(),
                    "Alice's 4 ledger rows (3 SHOP_SELL + 1 PAY_SEND, DESC LIMIT 20)");
                assertFalse(profile.get("frozen").getAsBoolean());

                var sold = collector.marketAuctionsSold();
                assertEquals(1, sold.getAsJsonArray("recent").size());
                assertEquals(7525L, sold.getAsJsonArray("recent").get(0).getAsJsonObject().get("priceC").getAsLong());

                var active = collector.marketAuctionsActive();
                assertEquals(1, active.get("count").getAsInt(), "expired listing must be excluded by expire_timestamp");
                assertEquals(9999L, active.get("totalValueC").getAsLong());
                assertEquals("DIAMOND", active.getAsJsonArray("listings").get(0).getAsJsonObject().get("material").getAsString());

                var trend = collector.priceTrend("DIAMOND", 50);
                assertEquals(3, trend.getAsJsonArray("series").size(),
                    "all three DIAMOND shop rows form the series");
                long firstAt = trend.getAsJsonArray("series").get(0).getAsJsonObject().get("at").getAsLong();
                long secondAt = trend.getAsJsonArray("series").get(1).getAsJsonObject().get("at").getAsLong();
                assertTrue(firstAt < secondAt, "series must be chronological");

                assertEquals(2, collector.econNotifications().get("pending").getAsInt(),
                    "pending_notifications must be visible through the same Core connection");
            }
            finally {
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("DROP DATABASE IF EXISTS " + testDb);
                }
            }
        }
    }
}
