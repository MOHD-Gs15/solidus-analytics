/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.analytics.engine;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.AnalyticsDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LiveMetricsTracker {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30000L;
    private volatile long pollIntervalMs = 30000L;
    private final AtomicLong lastPolledTimestamp = new AtomicLong(0L);
    private volatile String currentDate = LocalDate.now(ZoneOffset.UTC).toString();
    private final AtomicLong dailyVolumeCents = new AtomicLong(0L);
    private final AtomicLong dailyTransactionCount = new AtomicLong(0L);
    private final ConcurrentHashMap<String, AtomicLong> transactionsByType = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, AtomicLong> topItemsBought = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, AtomicLong> topItemsSold = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, Boolean> activePlayers = new ConcurrentHashMap();
    private final AnalyticsDatabase analyticsDb;
    private final String economyDbPath;
    private volatile boolean running = false;

    public LiveMetricsTracker(AnalyticsDatabase analyticsDb, String economyDbPath) {
        this.analyticsDb = analyticsDb;
        this.economyDbPath = economyDbPath;
    }

    public void start() {
        if (this.running) {
            return;
        }
        this.running = true;
        this.initializeLastTimestamp();
        this.analyticsDb.getExecutor().submit(this::pollingLoop);
        SolidusAnalyticsMod.LOGGER.info("LiveMetricsTracker started. Polling interval: {}ms", (Object)this.pollIntervalMs);
    }

    public void setPollingIntervalSeconds(int seconds) {
        seconds = Math.max(5, seconds);
        this.pollIntervalMs = (long)seconds * 1000L;
        SolidusAnalyticsMod.LOGGER.info("Polling interval set to {} seconds ({}ms)", (Object)seconds, (Object)this.pollIntervalMs);
    }

    public void stop() {
        this.running = false;
        try {
            this.forcePersist();
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to persist live metrics on shutdown", (Throwable)e);
        }
        SolidusAnalyticsMod.LOGGER.info("LiveMetricsTracker stopped. Final metrics persisted.");
    }

    public double getDailyVolume() {
        return (double)this.dailyVolumeCents.get() / 100.0;
    }

    public long getDailyVolumeCents() {
        return this.dailyVolumeCents.get();
    }

    public long getDailyTransactionCount() {
        return this.dailyTransactionCount.get();
    }

    public Map<String, Long> getTransactionsByType() {
        HashMap<String, Long> result = new HashMap<String, Long>();
        this.transactionsByType.forEach((type, count) -> result.put((String)type, count.get()));
        return result;
    }

    public Map<String, Long> getTopBoughtItems(int limit) {
        return this.getTopEntries(this.topItemsBought, limit);
    }

    public Map<String, Long> getTopSoldItems(int limit) {
        return this.getTopEntries(this.topItemsSold, limit);
    }

    public int getActivePlayerCount() {
        return this.activePlayers.size();
    }

    private void pollingLoop() {
        while (this.running) {
            try {
                this.pollNewTransactions();
                this.checkDailyReset();
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Error during transaction poll", (Throwable)e);
            }
            try {
                Thread.sleep(this.pollIntervalMs);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void pollNewTransactions() {
        long since = this.lastPolledTimestamp.get();
        if (since == 0L) {
            this.lastPolledTimestamp.set(System.currentTimeMillis());
            return;
        }
        String dbUrl = "jdbc:sqlite:" + this.economyDbPath;
        String sql = "    SELECT timestamp, type, player_uuid, player_name, amount, item_material, item_quantity\n    FROM transaction_log\n    WHERE timestamp > ?\n    ORDER BY timestamp ASC\n";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql);){
            try (Statement stmt = conn.createStatement();){
                stmt.execute("PRAGMA query_only = ON");
            }
            ps.setLong(1, since);
            long maxTimestamp = since;
            int processed = 0;
            try (ResultSet rs = ps.executeQuery();){
                while (rs.next()) {
                    long timestamp = rs.getLong("timestamp");
                    String type = rs.getString("type");
                    String playerUuid = rs.getString("player_uuid");
                    long amountCents = rs.getLong("amount");
                    String itemMaterial = rs.getString("item_material");
                    int itemQuantity = rs.getInt("item_quantity");
                    this.dailyVolumeCents.addAndGet(Math.abs(amountCents));
                    this.dailyTransactionCount.incrementAndGet();
                    this.transactionsByType.computeIfAbsent(type, k -> new AtomicLong(0L)).incrementAndGet();
                    if (itemMaterial != null && itemQuantity > 0) {
                        if ("SHOP_BUY".equals(type) || "AUCTION_BOUGHT".equals(type)) {
                            this.topItemsBought.computeIfAbsent(itemMaterial, k -> new AtomicLong(0L)).addAndGet(itemQuantity);
                        } else if ("SHOP_SELL".equals(type)) {
                            this.topItemsSold.computeIfAbsent(itemMaterial, k -> new AtomicLong(0L)).addAndGet(itemQuantity);
                        }
                    }
                    if (playerUuid != null) {
                        this.activePlayers.put(playerUuid, Boolean.TRUE);
                    }
                    maxTimestamp = Math.max(maxTimestamp, timestamp);
                    ++processed;
                }
            }
            if (processed > 0) {
                this.lastPolledTimestamp.set(maxTimestamp);
                SolidusAnalyticsMod.LOGGER.debug("Processed {} new transactions. Daily total: {} tx, S${}", new Object[]{processed, this.dailyTransactionCount.get(), String.format("%,.2f", (double)this.dailyVolumeCents.get() / 100.0)});
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to poll transactions from economy.db", (Throwable)e);
        }
    }

    private void checkDailyReset() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        if (!today.equals(this.currentDate)) {
            SolidusAnalyticsMod.LOGGER.info("Date changed from {} to {}. Persisting daily metrics and resetting counters.", (Object)this.currentDate, (Object)today);
            this.persistDailyMetrics(this.currentDate);
            this.dailyVolumeCents.set(0L);
            this.dailyTransactionCount.set(0L);
            this.transactionsByType.clear();
            this.topItemsBought.clear();
            this.topItemsSold.clear();
            this.activePlayers.clear();
            this.currentDate = today;
        }
    }

    private void persistDailyMetrics(String date) {
        Map<String, Long> typeCounts = this.getTransactionsByType();
        int shopBuyCount = typeCounts.getOrDefault("SHOP_BUY", 0L).intValue();
        int shopSellCount = typeCounts.getOrDefault("SHOP_SELL", 0L).intValue();
        int auctionCount = (int)(typeCounts.getOrDefault("AUCTION_LIST", 0L) + typeCounts.getOrDefault("AUCTION_SOLD", 0L) + typeCounts.getOrDefault("AUCTION_BOUGHT", 0L) + typeCounts.getOrDefault("AUCTION_EXPIRED", 0L));
        int payTransferCount = typeCounts.getOrDefault("PAY_SEND", 0L).intValue();
        String topBought = this.getTopBoughtItems(1).entrySet().stream().findFirst().map(Map.Entry::getKey).orElse(null);
        String topSold = this.getTopSoldItems(1).entrySet().stream().findFirst().map(Map.Entry::getKey).orElse(null);
        Double inflationRate = this.calculateInflationRate();
        AnalyticsDatabase.DailyMetrics metrics = new AnalyticsDatabase.DailyMetrics(date, (int)this.dailyTransactionCount.get(), this.dailyVolumeCents.get(), shopBuyCount, shopSellCount, auctionCount, payTransferCount, 0, this.activePlayers.size(), inflationRate, topBought, topSold);
        this.analyticsDb.upsertDailyMetricsAsync(metrics);
        SolidusAnalyticsMod.LOGGER.info("Persisted daily metrics for date: {}", (Object)date);
    }

    private Double calculateInflationRate() {
        AnalyticsDatabase.Snapshot latest = this.analyticsDb.getLatestSnapshot();
        if (latest == null) {
            return null;
        }
        long twentyFourHoursAgo = latest.timestamp() - 86400000L;
        AnalyticsDatabase.Snapshot previous = this.analyticsDb.getSnapshotBefore(twentyFourHoursAgo);
        if (previous == null || previous.totalWealth() == 0L) {
            return null;
        }
        return (double)(latest.totalWealth() - previous.totalWealth()) / (double)previous.totalWealth() * 100.0;
    }

    private void initializeLastTimestamp() {
        String dbUrl = "jdbc:sqlite:" + this.economyDbPath;
        String sql = "SELECT MAX(timestamp) as max_ts FROM transaction_log";
        try (Connection conn = DriverManager.getConnection(dbUrl);){
            try (Statement pragmaStmt = conn.createStatement();){
                pragmaStmt.execute("PRAGMA query_only = ON");
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                if (rs.next()) {
                    long maxTs = rs.getLong("max_ts");
                    if (!rs.wasNull()) {
                        this.lastPolledTimestamp.set(maxTs);
                        SolidusAnalyticsMod.LOGGER.info("Last known transaction timestamp: {}", (Object)maxTs);
                    } else {
                        this.lastPolledTimestamp.set(System.currentTimeMillis());
                        SolidusAnalyticsMod.LOGGER.info("No transactions found. Starting from current time.");
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to initialize last polled timestamp", (Throwable)e);
            this.lastPolledTimestamp.set(System.currentTimeMillis());
        }
    }

    private Map<String, Long> getTopEntries(ConcurrentHashMap<String, AtomicLong> map, int limit) {
        HashMap<String, Long> result = new HashMap<String, Long>();
        map.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, AtomicLong> e) -> e.getValue().get()).reversed()).limit(limit).forEach(e -> result.put(e.getKey(), e.getValue().get()));
        return result;
    }

    public void forcePersist() {
        this.persistDailyMetrics(this.currentDate);
    }
}
