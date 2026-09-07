package com.solidus.analytics.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solidus.analytics.storage.CoreLedgerAccess;

/**
 * 2.1.4: EconomyCollector's Core-database reads ride the same
 * CoreLedgerAccess bridge as the engine collectors (PROTOCOL.md &sect;5.2 /
 * &sect;15 command surface).
 *
 * <p>Production shape: when Core runs its 2.2.x MySQL network mode there is
 * ONE backend holding transaction_log + player_balances +
 * pending_notifications + auction_listings - the bridge provider exposes
 * exactly that. The fixture mirrors it with a single SQLite file, which is
 * what the provider seam is for. Parity with the direct file path is
 * asserted, plus the fail-open contract (a broken source degrades to the
 * documented empty/-1 defaults instead of throwing).</p>
 *
 * <p>Dialect portability against REAL MariaDB (real DECIMAL(18,2) columns,
 * real CHAR(36) uuid column names from Core's 001_init.sql) is proven by the
 * CI-gated {@code MySqlPremiumCloudBridgeTest}.</p>
 */
class EconomyCollectorBridgeTest {

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
            uuid TEXT PRIMARY KEY,
            player_name TEXT NOT NULL,
            balance REAL NOT NULL,
            last_updated INTEGER NOT NULL
        );
        CREATE TABLE pending_notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            player_uuid TEXT NOT NULL,
            message TEXT NOT NULL
        );
        CREATE TABLE auction_listings (
            listing_id TEXT PRIMARY KEY,
            seller_uuid TEXT,
            seller_name TEXT,
            material_name TEXT,
            quantity INTEGER,
            price REAL NOT NULL,
            listed_timestamp INTEGER,
            expire_timestamp INTEGER NOT NULL,
            status INTEGER NOT NULL DEFAULT 0
        );
        """;

    private Path dbFile;

    private void seed() throws Exception {
        dbFile = Files.createTempDirectory("solidus-econ-bridge").resolve("economy.db");
        long now = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            for (String part : DDL.split(";")) {
                if (!part.isBlank()) {
                    stmt.execute(part.trim());
                }
            }
            stmt.execute("INSERT INTO player_balances (uuid, player_name, balance, last_updated) "
                + "VALUES ('uuid-alice', 'Alice', 1000.00, " + now + ")");
            stmt.execute("INSERT INTO player_balances (uuid, player_name, balance, last_updated) "
                + "VALUES ('uuid-bob', 'Bob', 250.50, " + (now - 1000) + ")");
            // id order MUST follow chronological order (Core assigns ids
            // monotonically): oldest row first, newest last.
            stmt.execute("INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, target_uuid, target_name, amount, item_material, item_quantity) "
                + "VALUES (" + (now - 2000) + ", 'SHOP_SELL', 'uuid-alice', 'Alice', NULL, NULL, 50.05, 'DIAMOND', 2)");
            stmt.execute("INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, target_uuid, target_name, amount, item_material, item_quantity) "
                + "VALUES (" + now + ", 'SHOP_SELL', 'uuid-alice', 'Alice', NULL, NULL, 100.10, 'DIAMOND', 1)");
            stmt.execute("INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, target_uuid, target_name, amount, item_material, item_quantity) "
                + "VALUES (" + (now - 4000) + ", 'AUCTION_SOLD', 'uuid-bob', 'Bob', 'uuid-alice', 'Alice', 75.25, 'GOLD', 3)");
            stmt.execute("INSERT INTO pending_notifications (timestamp, player_uuid, message) "
                + "VALUES (" + now + ", 'uuid-bob', 'you were outbid')");
            stmt.execute("INSERT INTO pending_notifications (timestamp, player_uuid, message) "
                + "VALUES (" + now + ", 'uuid-bob', 'payment received')");
            stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, seller_name, material_name, quantity, price, listed_timestamp, expire_timestamp, status) "
                + "VALUES ('l-active', 'uuid-bob', 'Bob', 'DIAMOND', 1, 99.99, " + now + ", " + (now + 3600_000) + ", 0)");
            stmt.execute("INSERT INTO auction_listings (listing_id, seller_uuid, seller_name, material_name, quantity, price, listed_timestamp, expire_timestamp, status) "
                + "VALUES ('l-expired', 'uuid-bob', 'Bob', 'GOLD', 2, 40.00, " + (now - 7200_000) + ", " + (now - 3600_000) + ", 0)");
        }
    }

    private EconomyCollector collectorWithBridge(CoreLedgerAccess bridge) {
        EconomyCollector collector = new EconomyCollector(dbFile.toString(), dbFile.toString(),
            dbFile.toString(), null, null);
        if (bridge != null) {
            collector.setLedgerAccess(bridge);
        }
        return collector;
    }

    private CoreLedgerAccess bridgeOver() {
        return CoreLedgerAccess.forConnectionProvider(work -> {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
                return work.run(conn);
            }
        });
    }

    @Test
    void bridgeModeMatchesDirectFileModeAcrossTheCommandSurface() throws Exception {
        seed();
        EconomyCollector direct = collectorWithBridge(null);
        EconomyCollector bridged = collectorWithBridge(bridgeOver());

        // econ.tx.search: type filter + cents contract
        JsonObject directSearch = direct.txSearch("SHOP_SELL", null, null, null, null, null, 10);
        JsonObject bridgeSearch = bridged.txSearch("SHOP_SELL", null, null, null, null, null, 10);
        assertEquals(directSearch, bridgeSearch);
        assertEquals(2, bridgeSearch.getAsJsonArray("rows").size());
        // ORDER BY id DESC = newest first: the 100.10 row (id 2) leads.
        assertEquals(10010L, bridgeSearch.getAsJsonArray("rows").get(0).getAsJsonObject().get("amountC").getAsLong(),
            "newest row first (id DESC): 100.10 S$ -> 10010 cents, no float drift");
        assertEquals(5005L, bridgeSearch.getAsJsonArray("rows").get(1).getAsJsonObject().get("amountC").getAsLong(),
            "50.05 S$ -> 5005 cents");

        // minC filter (cents): >= 60 S$ keeps the 100.10 SHOP_SELL row AND
        // the 75.25 AUCTION_SOLD row (no type filter on this call)
        JsonObject minFiltered = bridged.txSearch(null, null, null, 6000L, null, null, 10);
        assertEquals(2, minFiltered.getAsJsonArray("rows").size());

        // player.profile: balance + lastSeen + tx history
        JsonObject directProfile = direct.playerProfile("Alice", null);
        JsonObject bridgeProfile = bridged.playerProfile("Alice", null);
        assertEquals(directProfile, bridgeProfile);
        assertEquals(100000L, bridgeProfile.get("balC").getAsLong());
        assertTrue(bridgeProfile.get("lastSeen").getAsLong() > 0);
        assertEquals(2, bridgeProfile.getAsJsonArray("tx").size());
        assertFalse(bridgeProfile.get("frozen").getAsBoolean());

        // name resolution + known-player guards
        assertEquals("uuid-alice", bridged.uuidForName("Alice"));
        assertEquals(direct.uuidForName("Alice"), bridged.uuidForName("Alice"));
        assertTrue(bridged.isKnownPlayer("Alice"));
        assertFalse(bridged.isKnownPlayer("Zed"));
        assertEquals(direct.knownPlayerNames(10), bridged.knownPlayerNames(10));
        assertTrue(bridged.knownPlayerNames(10).contains("Bob"));

        // market.auctions.sold
        JsonObject directSold = direct.marketAuctionsSold();
        JsonObject bridgeSold = bridged.marketAuctionsSold();
        assertEquals(directSold, bridgeSold);
        assertEquals(1, bridgeSold.getAsJsonArray("recent").size());
        assertEquals(7525L, bridgeSold.getAsJsonArray("recent").get(0).getAsJsonObject().get("priceC").getAsLong());

        // market.auctions.active: only the un-expired listing counts
        JsonObject directActive = direct.marketAuctionsActive();
        JsonObject bridgeActive = bridged.marketAuctionsActive();
        assertEquals(directActive, bridgeActive);
        assertEquals(1, bridgeActive.get("count").getAsInt());
        assertEquals(9999L, bridgeActive.get("totalValueC").getAsLong());
        assertEquals("DIAMOND", bridgeActive.getAsJsonArray("listings").get(0).getAsJsonObject().get("material").getAsString());

        // market.price.trend: DESC read is reversed into a chronological series
        JsonObject trend = bridged.priceTrend("DIAMOND", 50);
        JsonArray series = trend.getAsJsonArray("series");
        assertEquals(2, series.size());
        long first = series.get(0).getAsJsonObject().get("at").getAsLong();
        long second = series.get(1).getAsJsonObject().get("at").getAsLong();
        assertTrue(first < second, "series must be chronological (oldest first)");
        assertEquals(5005L, series.get(0).getAsJsonObject().get("pC").getAsLong());
        assertEquals(10010L, series.get(1).getAsJsonObject().get("pC").getAsLong());

        // econ.notifications
        assertEquals(direct.econNotifications(), bridged.econNotifications());
        assertEquals(2, bridged.econNotifications().get("pending").getAsInt());
    }

    @Test
    void brokenLedgerSourceFailsOpenAcrossTheCommandSurface() throws Exception {
        seed();
        EconomyCollector collector = collectorWithBridge(CoreLedgerAccess.forConnectionProvider(work -> {
            throw new SQLException("simulated core outage");
        }));

        assertEquals(0, collector.marketAuctionsActive().get("count").getAsInt());
        assertEquals(0, collector.marketAuctionsSold().getAsJsonArray("recent").size());
        assertEquals(0, collector.txSearch("SHOP_SELL", null, null, null, null, null, 10).getAsJsonArray("rows").size());
        assertEquals(-1L, collector.playerProfile("Alice", null).get("balC").getAsLong());
        assertEquals(0, collector.priceTrend("DIAMOND", 10).getAsJsonArray("series").size());
        assertNullValue(collector.uuidForName("Alice"));
        assertFalse(collector.isKnownPlayer("Alice"));
        assertTrue(collector.knownPlayerNames(10).isEmpty());
        assertEquals(-1, collector.econNotifications().get("pending").getAsInt());
    }

    private static void assertNullValue(String actual) {
        org.junit.jupiter.api.Assertions.assertNull(actual,
            "a failed uuid lookup must degrade to null, never throw");
    }
}
