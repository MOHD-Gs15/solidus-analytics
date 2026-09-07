package com.solidus.analytics.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the CoreLedgerAccess SQL surface through the plain connection
 * provider seam - the exact statements that run through Core's
 * TransactionLog connection in production. Backend portability of these
 * statements is proven against real MariaDB in
 * {@code CoreLedgerAccessMySqlTest} (CI-gated).
 */
class CoreLedgerAccessTest {

    private Path dbFile;
    private CoreLedgerAccess access;

    private static final String DDL = """
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
        );
        CREATE TABLE player_balances (
            player_uuid TEXT PRIMARY KEY,
            player_name TEXT NOT NULL,
            balance REAL NOT NULL
        );
        CREATE TABLE auction_listings (
            listing_id TEXT PRIMARY KEY,
            seller_uuid TEXT,
            material_name TEXT,
            quantity INTEGER,
            price REAL,
            status INTEGER NOT NULL DEFAULT 0
        );
        """;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempDirectory("core-ledger").resolve("economy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            // xerial's execute() only runs the FIRST statement of a batch -
            // apply each DDL block separately.
            for (String part : DDL.split(";")) {
                if (!part.isBlank()) {
                    stmt.execute(part.trim());
                }
            }
        }
        access = CoreLedgerAccess.forConnectionProvider(work -> {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
                return work.run(conn);
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    private static void insertRow(Connection conn, String type, String player, String target,
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
    }

    @Test
    void seedMaxIdReturnsZeroOnEmptyLog() throws Exception {
        assertEquals(0L, access.seedMaxId());
    }

    @Test
    void genericReadSeamRunsCallerSqlOnTheProvidedConnection() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            insertRow(conn, "PAY_SEND", "uuid-a", "uuid-b", 12.34, null, 0);
        }
        // The 2.1.4 seam used by FraudDetector / EconomyCollector: arbitrary
        // (SELECT-only, bounded) caller SQL on the same provider connection.
        Long count = access.read(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM transaction_log")) {
                rs.next();
                return Long.valueOf(rs.getLong(1));
            }
        });
        assertEquals(1L, count.longValue());
    }

    @Test
    void pollSinceIdAppliesIdCursorAndCentsContract() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            insertRow(conn, "PAY_SEND", "uuid-a", "uuid-b", 12.34, null, 0);
            insertRow(conn, "PAY_RECEIVE", "uuid-b", "uuid-a", 12.34, null, 0);
        }
        long maxId = access.seedMaxId();
        assertEquals(2L, maxId);

        List<CoreLedgerAccess.LedgerRow> batch = access.pollSinceId(0L, 100);
        assertEquals(2, batch.size());
        assertEquals(1L, batch.get(0).id());
        assertEquals("PAY_SEND", batch.get(0).type());
        // 12.34 S$ -> 1234 cents, exactly (no float drift at 2 decimals)
        assertEquals(1234L, batch.get(0).amountCents());
        assertEquals("uuid-b", batch.get(0).targetUuid());

        // The id cursor excludes already-polled rows.
        assertTrue(access.pollSinceId(maxId, 100).isEmpty());
    }

    @Test
    void aggregateReadsFollowTheCentsContract() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            insertRow(conn, "SHOP_BUY", "uuid-a", null, 10.00, "DIRT", 3);
            insertRow(conn, "SHOP_SELL", "uuid-b", null, 5.55, "WHEAT", 9);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO player_balances (player_uuid, player_name, balance) VALUES ('u1', 'Alice', 1000.00)");
                stmt.execute("INSERT INTO player_balances (player_uuid, player_name, balance) VALUES ('u2', 'Bob', 250.50)");
                stmt.execute("INSERT INTO player_balances (player_uuid, player_name, balance) VALUES ('u3', '  ', 5.00)");
                stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, material_name, quantity, price, status) VALUES ('l1', 'u1', 'DIAMOND', 1, 99.99, 0)");
                stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, material_name, quantity, price, status) VALUES ('l2', 'u2', 'GOLD', 2, 40.00, 0)");
                stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, material_name, quantity, price, status) VALUES ('l3', 'u3', 'IRON', 1, 10.00, 1)");
            }
        }

        assertEquals(125550L, access.moneySupplyCents()); // 1000.00 + 250.50 + 5.00
        assertEquals(1555L, access.shopThroughputCentsSince(0L)); // 10.00 + 5.55 = 15.55 S$
        CoreLedgerAccess.AuctionStats stats = access.auctionStats();
        assertEquals(2L, stats.activeListings()); // status=0 only
        assertEquals(13999L, stats.totalValueCents()); // 99.99 + 40.00 = 139.99 S$

        List<CoreLedgerAccess.BalanceRow> balances = access.balanceScanDesc();
        assertEquals(3, balances.size());
        assertEquals(100000L, balances.get(0).balanceCents()); // DESC order
        assertEquals("Alice", balances.get(0).playerName());
        assertEquals("unknown", balances.get(2).playerName()); // blank name normalized
    }
}
