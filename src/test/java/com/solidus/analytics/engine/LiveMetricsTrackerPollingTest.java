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
        insertTransactionWithTarget(economyDb, "AUCTION_SOLD", "uuid-s", "Seller", "uuid-a", "Alice", 250.50, "DIAMOND", 2);

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

    /**
     * THE "insane chart inflation" regression (2.1.3). A bidding war writes
     * escrow-churn rows (BID_PLACED / BID_REFUNDED), a settlement pair
     * (AUCTION_SOLD + AUCTION_WON with target) AND an /ah collect note
     * (AUCTION_WON with null target carrying the summed win prices). The old
     * accounting counted 6800 S$ of volume for a 1200 S$ economy - the daily
     * chart exploded with every bid. Volume must be the real movement only.
     */
    @Test
    void bidWarVolumeCountsTheRealSaleExactlyOnce() throws Exception {
        Path economyDb = createEconomyDbWithEmptyLog();
        LiveMetricsTracker tracker = new LiveMetricsTracker(null, economyDb.toString());
        tracker.pollNewTransactions(); // seed -> cursor 0

        // Auction listed at 1000, Bob bids 1200 after Alice's 1000:
        insertTransactionWithTarget(economyDb, "BID_PLACED", "uuid-a", "Alice", "uuid-s", "Seller", 1000.00, "DIAMOND", 1);
        insertTransactionWithTarget(economyDb, "BID_PLACED", "uuid-b", "Bob", "uuid-s", "Seller", 1200.00, "DIAMOND", 1);
        insertTransaction(economyDb, "BID_REFUNDED", "uuid-a", "Alice", 1000.00, null, 0);

        // Settlement: escrow released to seller -> seller row + winner row.
        insertTransactionWithTarget(economyDb, "AUCTION_SOLD", "uuid-s", "Seller", "uuid-b", "Bob", 1200.00, "DIAMOND", 1);
        insertTransactionWithTarget(economyDb, "AUCTION_WON", "uuid-b", "Bob", "uuid-s", "Seller", 1200.00, "DIAMOND", 1);

        // Bob later collects the item: /ah collect logs AUCTION_WON with the
        // summed win price and NO target - money already moved at settlement.
        insertTransaction(economyDb, "AUCTION_WON", "uuid-b", "Bob", 1200.00, "DIAMOND", 1);

        // A /trade money leg of 50 -> sender row + receiver mirror.
        insertTransactionWithTarget(economyDb, "TRADE_SEND", "uuid-b", "Bob", "uuid-a", "Alice", 50.00, null, 0);
        insertTransactionWithTarget(economyDb, "TRADE_RECEIVE", "uuid-a", "Alice", "uuid-b", "Bob", 50.00, null, 0);

        tracker.pollNewTransactions();

        // Volume = settlement 1200.00 + trade leg 50.00 = 1250.00 S$
        // (old accounting: 6800 + 50 = 6850 S$ - 5.5x inflated).
        assertEquals(125000L, tracker.getDailyVolumeCents());
        // Activity metrics still count every ledger row (8 records).
        assertEquals(8L, tracker.getDailyTransactionCount());
        assertEquals(2L, tracker.getTransactionsByType().get("BID_PLACED"));
        assertEquals(2L, tracker.getTransactionsByType().get("AUCTION_WON"));
        assertEquals(1L, tracker.getTransactionsByType().get("TRADE_RECEIVE"));
    }

    /**
     * Lifecycle regression (2.1.3): start() used check-then-act on a volatile
     * flag - two overlapping callers could spawn TWO polling loops feeding
     * the same counters (every transaction counted twice). The serialized
     * lifecycle must keep exactly one poller thread.
     */
    @Test
    void startIsIdempotentEvenWhenOverlapping() throws Exception {
        Path economyDb = createEconomyDbWithEmptyLog();
        LiveMetricsTracker tracker = new LiveMetricsTracker(null, economyDb.toString());
        try {
            tracker.start();
            tracker.start(); // must be a no-op, not a second loop
            long pollers = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "Solidus-Analytics-LivePoller".equals(t.getName()))
                .count();
            assertEquals(1L, pollers);
        }
        finally {
            tracker.stop(); // interrupt + join - must not hang or throw
        }
        long pollers = Thread.getAllStackTraces().keySet().stream()
            .filter(t -> "Solidus-Analytics-LivePoller".equals(t.getName()) && t.isAlive())
            .count();
        assertEquals(0L, pollers);
    }

    private static void insertTransactionWithTarget(Path db, String type, String uuid, String name,
                                                    String targetUuid, String targetName,
                                                    double amount, String material, int quantity) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, target_uuid, target_name, amount, item_material, item_quantity) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, type);
            ps.setString(3, uuid);
            ps.setString(4, name);
            ps.setString(5, targetUuid);
            ps.setString(6, targetName);
            ps.setDouble(7, amount);
            ps.setString(8, material);
            ps.setInt(9, quantity);
            ps.executeUpdate();
        }
    }
}
