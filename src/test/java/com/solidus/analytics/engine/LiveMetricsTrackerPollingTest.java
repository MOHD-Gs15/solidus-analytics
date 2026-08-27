package com.solidus.analytics.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the P0 polling defect: LiveMetricsTracker permanently
 * stopped polling when the economy transaction_log was EMPTY at startup,
 * because a 0 cursor was misread as "nothing to poll". A server started
 * against a fresh (empty) transaction log therefore never recorded any daily
 * metrics until the mod was restarted.
 */
class LiveMetricsTrackerPollingTest {

    private static final String TX_LOG_DDL = """
        CREATE TABLE transaction_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            type TEXT NOT NULL,
            player_uuid TEXT NOT NULL,
            player_name TEXT NOT NULL,
            target_uuid TEXT,
            target_name TEXT,
            amount REAL NOT NULL,
            item_material TEXT,
            item_quantity INTEGER,
            description TEXT
        )
        """;

    private static Path createEconomyDbWithEmptyLog() throws Exception {
        Path db = Files.createTempDirectory("solidus-economy").resolve("economy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute(TX_LOG_DDL);
        }
        return db;
    }

    private static void insertTransaction(Path db, String type, String uuid, String name,
                                          double amount, String material, int quantity) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, amount, item_material, item_quantity) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, type);
            ps.setString(3, uuid);
            ps.setString(4, name);
            ps.setDouble(5, amount);
            ps.setString(6, material);
            ps.setInt(7, quantity);
            ps.executeUpdate();
        }
    }

    @Test
    void pollsTransactionsWrittenAfterAnEmptyLogStartup() throws Exception {
        Path economyDb = createEconomyDbWithEmptyLog();
        LiveMetricsTracker tracker = new LiveMetricsTracker(null, economyDb.toString());

        // Cycle 1: log still empty -> cursor seeds at 0, nothing to process yet.
        tracker.pollNewTransactions();
        assertEquals(0L, tracker.getDailyTransactionCount());

        // Rows written AFTER startup (fresh-install path) must be picked up.
        insertTransaction(economyDb, "SHOP_BUY", "uuid-1", "Alice", 12.75, "DIAMOND", 1);
        insertTransaction(economyDb, "PAY_SEND", "uuid-2", "Bob", 500.00, null, 0);

        tracker.pollNewTransactions();

        assertEquals(2L, tracker.getDailyTransactionCount());
        // cents: round(12.75 * 100) + round(500.00 * 100) = 1275 + 50000
        assertEquals(51275L, tracker.getDailyVolumeCents());
    }

    @Test
    void mirrorRecordsCountMoneyMovementExactlyOnce() throws Exception {
        Path economyDb = createEconomyDbWithEmptyLog();
        LiveMetricsTracker tracker = new LiveMetricsTracker(null, economyDb.toString());
        tracker.pollNewTransactions(); // seed on an empty log -> cursor 0 (valid)

        // One /pay of 100 S$ -> core writes BOTH participant rows:
        insertTransaction(economyDb, "PAY_SEND", "uuid-a", "Alice", 100.00, null, 0);
        insertTransaction(economyDb, "PAY_RECEIVE", "uuid-b", "Bob", 100.00, null, 0);

        // One auction sale of 250.50 S$ -> core writes BOTH participant rows:
        insertTransaction(economyDb, "AUCTION_BOUGHT", "uuid-a", "Alice", 250.50, "DIAMOND", 2);
        insertTransaction(economyDb, "AUCTION_SOLD", "uuid-s", "Seller", 250.50, "DIAMOND", 2);

        tracker.pollNewTransactions();

        // Volume counts each money movement exactly once: 100.00 + 250.50 = 350.50 S$
        assertEquals(35050L, tracker.getDailyVolumeCents());
        // Activity metrics still count every audit row (4 records).
        assertEquals(4L, tracker.getDailyTransactionCount());
        assertEquals(1L, tracker.getTransactionsByType().get("PAY_RECEIVE"));
        assertEquals(1L, tracker.getTransactionsByType().get("AUCTION_BOUGHT"));
        assertEquals(1L, tracker.getTransactionsByType().get("PAY_SEND"));
        assertEquals(1L, tracker.getTransactionsByType().get("AUCTION_SOLD"));
    }
}
