package com.solidus.analytics.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * Regression test for the P1 unit defect: SnapshotScheduler stored
 * auctionTotalValue by reading SUM(price) through getLong() without
 * converting the REAL S$ column to cents, so the figure (and every consumer:
 * dashboard tiles, weekly report, inflation goods-value input) was ~100x too
 * small and lost fractional parts.
 */
class SnapshotSchedulerMoneyUnitsTest {

    @Test
    void auctionTotalValueAndBalancesAreConvertedToCents() throws Exception {
        Path dir = Files.createTempDirectory("solidus-snapshot");
        Path economyDb = dir.resolve("economy.db");
        Path auctionsDb = dir.resolve("auctions.db");

        // Schemas mirror Solidus Core (player_balances / auction_listings).
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + economyDb);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE player_balances (
                    uuid TEXT PRIMARY KEY NOT NULL,
                    player_name TEXT NOT NULL,
                    balance REAL NOT NULL DEFAULT 0.0,
                    last_updated INTEGER NOT NULL
                )
                """);
            stmt.execute("INSERT INTO player_balances VALUES ('u1', 'Alice', 10.50, 1)");
            stmt.execute("INSERT INTO player_balances VALUES ('u2', 'Bob', 20.75, 2)");
            stmt.execute("INSERT INTO player_balances VALUES ('u3', 'Carol', 5.25, 3)");
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + auctionsDb);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE auction_listings (
                    listing_id TEXT PRIMARY KEY NOT NULL,
                    seller_uuid TEXT NOT NULL,
                    seller_name TEXT NOT NULL,
                    material_name TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    item_nbt TEXT,
                    price REAL NOT NULL,
                    listed_timestamp INTEGER NOT NULL,
                    expire_timestamp INTEGER NOT NULL,
                    status INTEGER NOT NULL DEFAULT 0
                )
                """);
            stmt.execute("INSERT INTO auction_listings VALUES ('l1', 's1', 'Seller', 'DIAMOND', 1, null, 1250.50, 1, 2, 0)");
            stmt.execute("INSERT INTO auction_listings VALUES ('l2', 's2', 'Seller', 'EMERALD', 1, null, 100.25, 1, 2, 0)");
            // status = 2 (expired/collectible) must stay excluded from the sum.
            stmt.execute("INSERT INTO auction_listings VALUES ('l3', 's3', 'Seller', 'GOLD_INGOT', 1, null, 999.99, 1, 2, 2)");
        }

        SnapshotScheduler scheduler = new SnapshotScheduler(null, economyDb.toString(), auctionsDb.toString());
        SnapshotScheduler.SnapshotData data = scheduler.computeSnapshot();

        assertEquals(3, data.playerCount);
        // 10.50 + 20.75 + 5.25 = 36.50 S$ = 3650 cents
        assertEquals(3650L, data.totalWealth);
        // Sorted balances: 5.25, 10.50, 20.75 -> median = 10.50 S$ = 1050 cents
        assertEquals(1050L, data.medianBalance);
        // Only ACTIVE listings count: 1250.50 + 100.25 = 1350.75 S$ = 135075 cents.
        // The pre-fix value was 1350 (raw S$ units stored as cents, fraction lost).
        assertEquals(2, data.auctionActiveListings);
        assertEquals(135075L, data.auctionTotalValue);
    }
}
