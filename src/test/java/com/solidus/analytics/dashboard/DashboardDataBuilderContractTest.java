package com.solidus.analytics.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.storage.AnalyticsDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden JSON contract test for DashboardDataBuilder (audit P1).
 *
 * <p>The /api/data payload is the most important contract in the feature: it
 * is consumed by the local web app, the encrypted GitHub Pages channel and
 * potentially future external readers. This test pins the exact top-level
 * section set, the field names of every section, the cents-only money
 * convention, the schemaVersion commitment (audit 12.1 exit criterion #3)
 * and the documented null-tolerance rules (Table 8: empty snapshot -&gt;
 * null, no inflation basis -&gt; null, premium off -&gt; null, NaN/Infinity
 * -&gt; null). Renaming or removing a field without bumping
 * SCHEMA_VERSION must fail here.</p>
 *
 * <p>Stubs are hand-rolled subclasses instead of Mockito: the byte-buddy
 * inline mock maker does not support the Java 25 class file version used by
 * the Gradle toolchain, and plain subclasses exercise the same public
 * surface the builder touches.</p>
 */
class DashboardDataBuilderContractTest {

    private static final Set<String> TOP_LEVEL_SECTIONS = Set.of(
        "schemaVersion", "timestamp", "server", "liveMetrics", "latestSnapshot",
        "inflation", "healthScore", "fraudAlerts", "dailyHistory", "topItems");

    // ---- hand-rolled stubs (no Mockito: Java 25 + bytebuddy mismatch) ----

    private static final class StubEngine extends AnalyticsEngine {
        LiveMetricsTracker metrics;
        AnalyticsDatabase database;
        InflationCalculator inflation;
        boolean premium;

        @Override
        public LiveMetricsTracker getLiveMetrics() {
            return this.metrics;
        }

        @Override
        public AnalyticsDatabase getDatabase() {
            return this.database;
        }

        @Override
        public InflationCalculator getInflationCalculator() {
            return this.inflation;
        }

        @Override
        public boolean isPremiumEnabled() {
            return this.premium;
        }
    }

    private static final class StubMetrics extends LiveMetricsTracker {
        long volume;
        long count;
        int active;
        Map<String, Long> byType = Map.of();
        Map<String, Long> bought = Map.of();
        Map<String, Long> sold = Map.of();

        StubMetrics(AnalyticsDatabase db) {
            super(db, "unused-economy.db");
        }

        @Override
        public long getDailyVolumeCents() {
            return this.volume;
        }

        @Override
        public long getDailyTransactionCount() {
            return this.count;
        }

        @Override
        public int getActivePlayerCount() {
            return this.active;
        }

        @Override
        public Map<String, Long> getTransactionsByType() {
            return this.byType;
        }

        @Override
        public Map<String, Long> getTopBoughtItems(int limit) {
            return this.bought;
        }

        @Override
        public Map<String, Long> getTopSoldItems(int limit) {
            return this.sold;
        }
    }

    private static final class StubDatabase extends AnalyticsDatabase {
        Snapshot latest;
        List<DailyMetrics> daily = List.of();

        StubDatabase(Path dir) {
            super(dir.toString());
        }

        @Override
        public Snapshot getLatestSnapshot() {
            return this.latest;
        }

        @Override
        public List<DailyMetrics> getRecentDailyMetrics(int limit) {
            return this.daily;
        }
    }

    private static final class StubInflation extends InflationCalculator {
        InflationReport report;

        StubInflation(AnalyticsDatabase db) {
            super(db, "unused-economy.db", "unused-auctions.db");
        }

        @Override
        public InflationReport getCachedOrCalculate() {
            return this.report;
        }
    }

    // ---- fixtures -----------------------------------------------------------

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("solidus-contract");
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubEngine fullEngine(Path dir) {
        StubEngine engine = new StubEngine();
        StubMetrics metrics = new StubMetrics(null);
        metrics.volume = 123_456L;
        metrics.count = 7L;
        metrics.active = 3;
        Map<String, Long> byType = new LinkedHashMap<>();
        byType.put("shop_sell", 2L);
        byType.put("auction_buy", 5L);
        metrics.byType = byType;
        metrics.bought = new LinkedHashMap<>(Map.of("diamond", 5L));
        metrics.sold = new LinkedHashMap<>(Map.of("cobblestone", 9L));
        engine.metrics = metrics;

        StubDatabase database = new StubDatabase(dir);
        database.latest = new AnalyticsDatabase.Snapshot(
            1_700_000_000_000L, "SCHEDULED", 500_000L, 5, 0.42, 100_000L, 80_000L, 33.3, 500_000L, 2, 75_000L);
        database.daily = List.of(new AnalyticsDatabase.DailyMetrics(
            "2026-08-30", 12, 240_000L, 5, 4, 3, 2, 1, 9, 1.5, "diamond", "cobblestone"));
        engine.database = database;

        StubInflation inflation = new StubInflation(null);
        InflationCalculator.InflationReport report = new InflationCalculator.InflationReport();
        report.moneySupplyCents = 500_000L;
        report.goodsValueCents = 250_000L;
        report.moneyToGoodsRatio = 2.0;
        report.status = "STABLE";
        report.inflationRate24h = 0.5;
        report.inflationRate7d = 1.2;
        report.inflationRate30d = 2.5;
        inflation.report = report;
        engine.inflation = inflation;
        engine.premium = false;
        return engine;
    }

    // ---- tests --------------------------------------------------------------

    @Test
    void fullContractShapeIsGolden() {
        StubEngine engine = DashboardDataBuilderContractTest.fullEngine(DashboardDataBuilderContractTest.tempDir());
        String json = DashboardDataBuilder.buildJson(engine);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // exact top-level contract (additive-only changes keep version 1)
        assertEquals(DashboardDataBuilderContractTest.TOP_LEVEL_SECTIONS, root.keySet());
        assertEquals(1L, root.get("schemaVersion").getAsLong());
        assertTrue(root.get("timestamp").getAsLong() > 0);

        JsonObject server = root.getAsJsonObject("server");
        assertEquals("Solidus Server", server.get("name").getAsString());
        assertFalse(server.get("fingerprint").getAsString().isBlank());

        JsonObject liveMetrics = root.getAsJsonObject("liveMetrics");
        assertEquals(123_456L, liveMetrics.get("dailyVolume").getAsLong());
        assertEquals(7L, liveMetrics.get("dailyTransactionCount").getAsLong());
        assertEquals(3, liveMetrics.get("activePlayerCount").getAsInt());
        JsonObject byType = liveMetrics.getAsJsonObject("transactionsByType");
        assertEquals(2L, byType.get("shop_sell").getAsLong());
        assertEquals(5L, byType.get("auction_buy").getAsLong());

        JsonObject snapshot = root.getAsJsonObject("latestSnapshot");
        assertEquals(1_700_000_000_000L, snapshot.get("timestamp").getAsLong());
        assertEquals("SCHEDULED", snapshot.get("type").getAsString());
        assertEquals(500_000L, snapshot.get("totalWealth").getAsLong());
        assertEquals(5, snapshot.get("playerCount").getAsInt());
        assertEquals(0.42, snapshot.get("giniCoefficient").getAsDouble(), 1e-9);
        assertEquals(100_000L, snapshot.get("avgBalance").getAsLong());
        assertEquals(80_000L, snapshot.get("medianBalance").getAsLong());
        assertEquals(33.3, snapshot.get("top1PercentShare").getAsDouble(), 1e-9);
        assertEquals(500_000L, snapshot.get("moneySupply").getAsLong());
        assertEquals(2, snapshot.get("auctionActiveListings").getAsInt());
        assertEquals(75_000L, snapshot.get("auctionTotalValue").getAsLong());

        JsonObject inflation = root.getAsJsonObject("inflation");
        assertEquals(500_000L, inflation.get("moneySupplyCents").getAsLong());
        assertEquals(250_000L, inflation.get("goodsValueCents").getAsLong());
        assertEquals(2.0, inflation.get("moneyToGoodsRatio").getAsDouble(), 1e-9);
        assertEquals("STABLE", inflation.get("status").getAsString());
        assertEquals(0.5, inflation.get("inflationRate24h").getAsDouble(), 1e-9);
        assertEquals(1.2, inflation.get("inflationRate7d").getAsDouble(), 1e-9);
        assertEquals(2.5, inflation.get("inflationRate30d").getAsDouble(), 1e-9);

        // premium sections are nulls when the license is inactive
        assertTrue(root.get("healthScore").isJsonNull());
        assertTrue(root.get("fraudAlerts").isJsonNull());

        JsonArray dailyHistory = root.getAsJsonArray("dailyHistory");
        assertEquals(1, dailyHistory.size());
        JsonObject day = dailyHistory.get(0).getAsJsonObject();
        assertEquals(Set.of("date", "transactionCount", "transactionVolume", "activePlayers", "inflationRate"),
            day.keySet());
        assertEquals("2026-08-30", day.get("date").getAsString());
        assertEquals(12, day.get("transactionCount").getAsInt());
        assertEquals(240_000L, day.get("transactionVolume").getAsLong());
        assertEquals(9, day.get("activePlayers").getAsInt());
        assertEquals(1.5, day.get("inflationRate").getAsDouble(), 1e-9);

        JsonObject topItems = root.getAsJsonObject("topItems");
        assertEquals(2, topItems.keySet().size());
        JsonArray bought = topItems.getAsJsonArray("bought");
        assertEquals("diamond", bought.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(5L, bought.get(0).getAsJsonObject().get("quantity").getAsLong());
        assertEquals("cobblestone", topItems.getAsJsonArray("sold").get(0).getAsJsonObject().get("item").getAsString());
    }

    @Test
    void emptyEngineKeepsContractValidWithNulls() {
        // the "fresh install" shape: zero counters, empty maps, no snapshot,
        // no inflation basis, premium off (Table 8 acceptance rules)
        StubEngine engine = new StubEngine();
        StubMetrics metrics = new StubMetrics(null);
        engine.metrics = metrics;
        engine.database = new StubDatabase(DashboardDataBuilderContractTest.tempDir());
        engine.inflation = new StubInflation(null);
        engine.premium = false;

        String json = DashboardDataBuilder.buildJson(engine);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(DashboardDataBuilderContractTest.TOP_LEVEL_SECTIONS, root.keySet());
        assertTrue(root.get("latestSnapshot").isJsonNull(), "no snapshot yet -> null, not zeros");
        assertTrue(root.get("inflation").isJsonNull(), "no inflation basis -> null");
        assertTrue(root.get("healthScore").isJsonNull());
        assertTrue(root.get("fraudAlerts").isJsonNull());
        assertEquals(0, root.getAsJsonArray("dailyHistory").size());
        assertEquals(0, root.getAsJsonObject("topItems").getAsJsonArray("bought").size());
        assertEquals(0, root.getAsJsonObject("topItems").getAsJsonArray("sold").size());
        assertEquals(0L, root.getAsJsonObject("liveMetrics").get("dailyVolume").getAsLong());
    }

    @Test
    void nonFiniteDoublesBecomeNullInsteadOfBreakingJson() {
        StubEngine engine = DashboardDataBuilderContractTest.fullEngine(DashboardDataBuilderContractTest.tempDir());
        StubDatabase database = new StubDatabase(DashboardDataBuilderContractTest.tempDir());
        database.latest = new AnalyticsDatabase.Snapshot(
            1_700_000_000_000L, "SCHEDULED", 500_000L, 5,
            Double.NaN, 100_000L, 80_000L, Double.POSITIVE_INFINITY, 500_000L, 2, 75_000L);
        database.daily = List.of(new AnalyticsDatabase.DailyMetrics(
            "2026-08-30", 12, 240_000L, 5, 4, 3, 2, 1, 9, Double.NaN, "diamond", "cobblestone"));
        engine.database = database;

        String json = DashboardDataBuilder.buildJson(engine);

        assertFalse(json.contains("NaN"), "NaN token breaks JSON.parse client-side");
        assertFalse(json.contains("Infinity"), "Infinity token breaks JSON.parse client-side");
        JsonObject snapshot = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("latestSnapshot");
        assertTrue(snapshot.get("giniCoefficient").isJsonNull());
        assertTrue(snapshot.get("top1PercentShare").isJsonNull());
    }

    @Test
    void stringEscapingCoversQuotesBackslashAndControlChars() {
        // regression: control chars used to throw IllegalFormatConversionException
        assertEquals("null", DashboardDataBuilder.escapeJson(null));
        assertEquals("\"a\\\"b\"", DashboardDataBuilder.escapeJson("a\"b"));
        assertEquals("\"a\\\\b\"", DashboardDataBuilder.escapeJson("a\\b"));
        assertEquals("\"a\\nb\"", DashboardDataBuilder.escapeJson("a\nb"));
        assertEquals("\"a\\tb\"", DashboardDataBuilder.escapeJson("a\tb"));
        assertEquals("\"a\\u0001b\"", DashboardDataBuilder.escapeJson("a\u0001b"));
        assertEquals("\"x\\\"\\ty\\u0001\"", DashboardDataBuilder.escapeJson("x\"\ty\u0001"));
        // every escaped output must itself be valid JSON (round-trip check)
        JsonObject parsed = JsonParser.parseString(
            "{\"k\":" + DashboardDataBuilder.escapeJson("x\"\ty\u0001\u001fz") + "}").getAsJsonObject();
        assertEquals("x\"\ty\u0001\u001fz", parsed.get("k").getAsString());
    }
}
