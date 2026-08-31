package com.solidus.analytics.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.integration.SolidusIntegration;
import com.solidus.analytics.storage.DirectDb;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * EconomyCollector - read-only economy & market telemetry plus the parameterized
 * queries behind econ.tx.search / player.profile / market.price.trend
 * (PROTOCOL.md &sect;5.2 and &sect;15).
 *
 * <p>Read path discipline: everything that touches Core databases goes through
 * {@link DirectDb#openReadOnly} (query_only=ON, short busy timeout) or through
 * the existing reflection bridge. Money leaves this class in integer cents.</p>
 */
public final class EconomyCollector {
    private static final int API_TIMEOUT_SECONDS = 5;

    private final String economyDbPath;
    private final String auctionsDbPath;
    private final String analyticsDbPath;
    private final LiveMetricsTracker liveMetrics;
    private final InflationCalculator inflationCalculator;
    private long lastSupplyC = -1L;
    private long lastSupplyAt = 0L;

    public EconomyCollector(String economyDbPath, String auctionsDbPath, String analyticsDbPath,
                            LiveMetricsTracker liveMetrics, InflationCalculator inflationCalculator) {
        this.economyDbPath = economyDbPath;
        this.auctionsDbPath = auctionsDbPath;
        this.analyticsDbPath = analyticsDbPath;
        this.liveMetrics = liveMetrics;
        this.inflationCalculator = inflationCalculator;
    }

    // ---- periodic readings ----------------------------------------------

    /** econ.top - top 10 balances via Core API (reflection). */
    public JsonObject econTop() {
        JsonObject d = new JsonObject();
        JsonArray entries = new JsonArray();
        if (SolidusIntegration.isAvailable()) {
            try {
                CompletableFuture<List> future = (CompletableFuture<List>)(Object)SolidusIntegration.getInstance().getTopBalances(10);
                if (future != null) {
                    for (Object entry : future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        if (entry == null) {
                            continue;
                        }
                        JsonObject e = new JsonObject();
                        e.addProperty("n", str(entry, "playerName"));
                        e.addProperty("uuid", str(entry, "uuid"));
                        e.addProperty("balC", Math.round(dbl(entry, "balance") * 100.0));
                        entries.add(e);
                    }
                }
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.debug("[Cloud] econ.top unavailable", (Throwable)e);
            }
        }
        d.add("entries", entries);
        return d;
    }

    /** econ.supply + econ.distribution aggregates from Core's EconomyStats. */
    public JsonObject econSupply() {
        JsonObject d = new JsonObject();
        d.addProperty("supplyC", -1L);
        d.addProperty("delta24hC", 0L);
        d.addProperty("players", -1);
        if (!SolidusIntegration.isAvailable()) {
            return d;
        }
        try {
            Object stats = SolidusIntegration.getInstance().getEconomyStats(API_TIMEOUT_SECONDS);
            if (stats != null) {
                long supplyC = Math.round(dbl(stats, "totalSupply") * 100.0);
                long now = System.currentTimeMillis();
                long delta = 0L;
                if (this.lastSupplyC >= 0L && this.lastSupplyAt > 0L) {
                    double scale = 86400000.0 / (double)Math.max(1L, now - this.lastSupplyAt);
                    delta = Math.round((double)(supplyC - this.lastSupplyC) * scale);
                }
                this.lastSupplyC = supplyC;
                this.lastSupplyAt = now;
                d.addProperty("supplyC", supplyC);
                d.addProperty("delta24hC", delta);
                d.addProperty("players", (int)dbl(stats, "playerCount"));
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] econ.supply unavailable", (Throwable)e);
        }
        return d;
    }

    /** econ.distribution - latest persisted snapshot row (gini / top1 / median). */
    public JsonObject econDistribution() {
        JsonObject d = new JsonObject();
        d.addProperty("gini", -1.0);
        d.addProperty("top1Pct", -1.0);
        d.addProperty("medianC", -1L);
        d.addProperty("source", "snapshot");
        try (Connection conn = DirectDb.openReadOnly(this.analyticsDbPath);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT gini_coefficient, top1_percent_share, median_balance, timestamp FROM analytics_snapshots ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery();) {
            if (rs.next()) {
                d.addProperty("gini", rs.getDouble("gini_coefficient"));
                d.addProperty("top1Pct", rs.getDouble("top1_percent_share"));
                d.addProperty("medianC", rs.getLong("median_balance"));
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] econ.distribution unavailable", (Throwable)e);
        }
        return d;
    }

    /** econ.flow - today's live transaction flow from LiveMetricsTracker. */
    public JsonObject econFlow() {
        JsonObject d = new JsonObject();
        d.addProperty("dayVolC", this.liveMetrics == null ? -1L : this.liveMetrics.getDailyVolumeCents());
        d.addProperty("dayCount", this.liveMetrics == null ? -1L : this.liveMetrics.getDailyTransactionCount());
        JsonObject byType = new JsonObject();
        if (this.liveMetrics != null) {
            this.liveMetrics.getTransactionsByType().forEach(byType::addProperty);
        }
        d.add("byType", byType);
        d.addProperty("activePlayers", this.liveMetrics == null ? -1 : this.liveMetrics.getActivePlayerCount());
        return d;
    }

    /** econ.inflation - cached or freshly computed InflationReport. */
    public JsonObject econInflation() {
        JsonObject d = new JsonObject();
        d.addProperty("rate", -1.0);
        d.addProperty("band", "UNKNOWN");
        if (this.inflationCalculator == null) {
            return d;
        }
        try {
            InflationCalculator.InflationReport report = this.inflationCalculator.getCachedOrCalculate();
            if (report != null) {
                d.addProperty("rate", report.inflationRate == null ? -1.0 : Math.round(report.inflationRate * 100.0) / 100.0);
                d.addProperty("band", report.status == null ? "UNKNOWN" : report.status);
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] econ.inflation unavailable", (Throwable)e);
        }
        return d;
    }

    /** econ.notifications - pending player notifications (delivery health). */
    public JsonObject econNotifications() {
        JsonObject d = new JsonObject();
        d.addProperty("pending", -1);
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM pending_notifications");
             ResultSet rs = ps.executeQuery();) {
            if (rs.next()) {
                d.addProperty("pending", rs.getInt(1));
            }
        }
        catch (Exception e) {
            // Core schema may not have this table in older versions - degrade quietly
        }
        return d;
    }

    /** market.auctions.active - live listings from auctions.db (read-only). */
    public JsonObject marketAuctionsActive() {
        JsonObject d = new JsonObject();
        d.addProperty("count", 0);
        d.addProperty("totalValueC", 0L);
        JsonArray listings = new JsonArray();
        try (Connection conn = DirectDb.openReadOnly(this.auctionsDbPath)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) AS c, COALESCE(SUM(price),0) AS v FROM auction_listings WHERE status = 0 AND expire_timestamp > ?")) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery();) {
                    if (rs.next()) {
                        d.addProperty("count", rs.getInt("c"));
                        d.addProperty("totalValueC", Math.round(rs.getDouble("v") * 100.0));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT listing_id, seller_name, material_name, quantity, price, expire_timestamp FROM auction_listings"
                    + "        WHERE status = 0 AND expire_timestamp > ? ORDER BY price DESC LIMIT 10")) {
                ps.setLong(1, System.currentTimeMillis());
                long now = System.currentTimeMillis();
                try (ResultSet rs = ps.executeQuery();) {
                    while (rs.next()) {
                        JsonObject l = new JsonObject();
                        l.addProperty("id", rs.getString("listing_id"));
                        l.addProperty("seller", rs.getString("seller_name"));
                        l.addProperty("material", rs.getString("material_name"));
                        l.addProperty("qty", rs.getInt("quantity"));
                        l.addProperty("priceC", Math.round(rs.getDouble("price") * 100.0));
                        l.addProperty("endsInS", Math.max(0L, (rs.getLong("expire_timestamp") - now) / 1000L));
                        listings.add(l);
                    }
                }
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] market.auctions.active unavailable", (Throwable)e);
        }
        d.add("listings", listings);
        return d;
    }

    /** market.auctions.sold - recent AUCTION_SOLD transactions. */
    public JsonObject marketAuctionsSold() {
        JsonObject d = new JsonObject();
        JsonArray recent = new JsonArray();
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, player_name, amount, item_material, item_quantity, timestamp FROM transaction_log"
                + "        WHERE type = 'AUCTION_SOLD' ORDER BY id DESC LIMIT 20")) {
            try (ResultSet rs = ps.executeQuery();) {
                while (rs.next()) {
                    JsonObject t = new JsonObject();
                    t.addProperty("id", rs.getLong("id"));
                    t.addProperty("buyer", rs.getString("player_name"));
                    t.addProperty("material", rs.getString("item_material"));
                    t.addProperty("qty", rs.getInt("item_quantity"));
                    t.addProperty("priceC", Math.round(rs.getDouble("amount") * 100.0));
                    t.addProperty("at", rs.getLong("timestamp"));
                    recent.add(t);
                }
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] market.auctions.sold unavailable", (Throwable)e);
        }
        d.add("recent", recent);
        return d;
    }

    /** market.shop.volume - top bought/sold materials today. */
    public JsonObject marketShopVolume() {
        JsonObject d = new JsonObject();
        if (this.liveMetrics == null) {
            d.add("topBought", new JsonArray());
            d.add("topSold", new JsonArray());
            return d;
        }
        d.add("topBought", this.mapToJson(this.liveMetrics.getTopBoughtItems(10)));
        d.add("topSold", this.mapToJson(this.liveMetrics.getTopSoldItems(10)));
        return d;
    }

    private JsonArray mapToJson(java.util.Map<String, Long> map) {
        JsonArray arr = new JsonArray();
        map.forEach((material, count) -> {
            JsonArray pair = new JsonArray();
            pair.add(material);
            pair.add(count);
            arr.add(pair);
        });
        return arr;
    }

    // ---- parameterized queries (command results) -------------------------

    /** econ.tx.search - filtered transaction log query. */
    public JsonObject txSearch(String type, String player, String material, Long minC, Long maxC,
                               Long sinceMs, int limit) {
        JsonObject d = new JsonObject();
        JsonArray rows = new JsonArray();
        StringBuilder sql = new StringBuilder("SELECT id, type, player_name, amount, item_material, item_quantity, timestamp FROM transaction_log WHERE 1=1");
        ArrayList params = new ArrayList();
        if (type != null) {
            sql.append(" AND type = ?");
            params.add(type);
        }
        if (player != null) {
            sql.append(" AND player_name = ?");
            params.add(player);
        }
        if (material != null) {
            sql.append(" AND item_material = ?");
            params.add(material);
        }
        if (minC != null) {
            sql.append(" AND amount >= ?");
            params.add((double)minC / 100.0);
        }
        if (maxC != null) {
            sql.append(" AND amount <= ?");
            params.add((double)maxC / 100.0);
        }
        if (sinceMs != null) {
            sql.append(" AND timestamp >= ?");
            params.add(sinceMs);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(Math.max(1, Math.min(100, limit)));
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement(sql.toString());) {
            for (int i = 0; i < params.size(); ++i) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery();) {
                while (rs.next()) {
                    JsonObject t = new JsonObject();
                    t.addProperty("id", rs.getLong("id"));
                    t.addProperty("type", rs.getString("type"));
                    t.addProperty("player", rs.getString("player_name"));
                    t.addProperty("amountC", Math.round(rs.getDouble("amount") * 100.0));
                    t.addProperty("material", rs.getString("item_material"));
                    t.addProperty("qty", rs.getInt("item_quantity"));
                    t.addProperty("at", rs.getLong("timestamp"));
                    rows.add(t);
                }
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] tx search failed", (Throwable)e);
        }
        d.add("rows", rows);
        return d;
    }

    /** player.profile - balance + last seen + recent transactions + frozen flag. */
    public JsonObject playerProfile(String name, CloudVetoHook veto) {
        JsonObject d = new JsonObject();
        d.addProperty("name", name);
        d.addProperty("balC", -1L);
        d.addProperty("lastSeen", 0L);
        d.addProperty("frozen", false);
        JsonArray tx = new JsonArray();
        String uuid = null;
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, balance, last_updated FROM player_balances WHERE player_name = ?");) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery();) {
                if (rs.next()) {
                    uuid = rs.getString("uuid");
                    d.addProperty("balC", Math.round(rs.getDouble("balance") * 100.0));
                    d.addProperty("lastSeen", rs.getLong("last_updated"));
                }
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] profile balance lookup failed", (Throwable)e);
        }
        if (uuid != null && SolidusIntegration.isAvailable()) {
            try {
                CompletableFuture<Double> future = (CompletableFuture<Double>)(Object)SolidusIntegration.getInstance().getBalanceOffline(java.util.UUID.fromString(uuid), name, API_TIMEOUT_SECONDS);
                if (future != null) {
                    Double live = future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (live != null) {
                        d.addProperty("balC", Math.round(live.doubleValue() * 100.0));
                    }
                }
            }
            catch (Exception e) {
                // keep the DB-read balance
            }
        }
        if (uuid != null && veto != null) {
            d.addProperty("frozen", veto.isFrozen(java.util.UUID.fromString(uuid)));
        }
        d.add("tx", this.recentTransactions(name, 20));
        return d;
    }

    private JsonArray recentTransactions(String playerName, int limit) {
        JsonArray rows = new JsonArray();
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, type, amount, item_material, item_quantity, timestamp FROM transaction_log"
                + "        WHERE player_name = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, playerName);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery();) {
                while (rs.next()) {
                    JsonObject t = new JsonObject();
                    t.addProperty("id", rs.getLong("id"));
                    t.addProperty("type", rs.getString("type"));
                    t.addProperty("amountC", Math.round(rs.getDouble("amount") * 100.0));
                    t.addProperty("material", rs.getString("item_material"));
                    t.addProperty("qty", rs.getInt("item_quantity"));
                    t.addProperty("at", rs.getLong("timestamp"));
                    rows.add(t);
                }
            }
        }
        catch (Exception e) {
            // degrade to empty
        }
        return rows;
    }

    /** market.price.trend - price series for one material (auction sales + shop flows). */
    public JsonObject priceTrend(String material, int points) {
        JsonObject d = new JsonObject();
        JsonArray series = new JsonArray();
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement(
                "SELECT amount, timestamp FROM transaction_log WHERE item_material = ? AND amount > 0"
                + "        ORDER BY id DESC LIMIT ?");
             ResultSet rs = ps.executeQuery();) {
            ps.setString(1, material);
            ps.setInt(2, Math.max(10, Math.min(500, points)));
            ArrayList<double[]> tmp = new ArrayList<double[]>();
            while (rs.next()) {
                tmp.add(new double[]{rs.getDouble("amount") * 100.0, (double)rs.getLong("timestamp")});
            }
            for (int i = tmp.size() - 1; i >= 0; --i) {
                JsonObject point = new JsonObject();
                point.addProperty("pC", Math.round(tmp.get(i)[0]));
                point.addProperty("at", (long)tmp.get(i)[1]);
                series.add(point);
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] price trend failed", (Throwable)e);
        }
        d.add("series", series);
        return d;
    }

    /** Resolves a player name to uuid from the known-player table (null if unknown). */
    public String uuidForName(String name) {
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM player_balances WHERE player_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery();) {
                if (rs.next()) {
                    return rs.getString("uuid");
                }
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] uuid lookup failed for {}", (Object)name, (Object)e);
        }
        return null;
    }

    /** Known-player check used by the router (E_NO_SUCH_PLAYER guard). */
    public boolean isKnownPlayer(String name) {
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM player_balances WHERE player_name = ?");) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery();) {
                return rs.next();
            }
        }
        catch (SQLException e) {
            return false;
        }
    }

    /** Names of all known players (capped) for econ.grant.all scope=known. */
    public java.util.List<String> knownPlayerNames(int limit) {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement("SELECT player_name FROM player_balances ORDER BY last_updated DESC LIMIT ?");) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery();) {
                while (rs.next()) {
                    names.add(rs.getString("player_name"));
                }
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] knownPlayerNames failed", (Throwable)e);
        }
        return names;
    }

    // ---- reflective record accessors -------------------------------------

    private static String str(Object record, String accessor) {
        try {
            Method m = record.getClass().getMethod(accessor);
            Object v = m.invoke(record);
            return v == null ? null : v.toString();
        }
        catch (Exception e) {
            return null;
        }
    }

    private static double dbl(Object record, String accessor) {
        try {
            Method m = record.getClass().getMethod(accessor);
            Object v = m.invoke(record);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            return 0.0;
        }
        catch (Exception e) {
            return 0.0;
        }
    }
}
