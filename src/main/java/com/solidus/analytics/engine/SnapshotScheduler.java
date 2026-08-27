/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.analytics.engine;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.storage.AnalyticsDatabase;
import com.solidus.analytics.util.GiniCoefficient;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;

public class SnapshotScheduler {
    private int snapshotIntervalTicks = 36000;
    private final AnalyticsDatabase analyticsDb;
    private final String economyDbPath;
    private final String auctionsDbPath;
    private volatile String lastDailySnapshotDate = "";
    private int tickCounter = 0;
    private volatile AnalyticsEngine engineRef;

    public SnapshotScheduler(AnalyticsDatabase analyticsDb, String economyDbPath, String auctionsDbPath) {
        this.analyticsDb = analyticsDb;
        this.economyDbPath = economyDbPath;
        this.auctionsDbPath = auctionsDbPath;
    }

    public void setEngineRef(AnalyticsEngine engine) {
        this.engineRef = engine;
    }

    public void setSnapshotIntervalMinutes(int minutes) {
        minutes = Math.max(1, minutes);
        this.snapshotIntervalTicks = minutes * 1200;
        SolidusAnalyticsMod.LOGGER.info("Snapshot interval set to {} minutes ({} ticks)", (Object)minutes, (Object)this.snapshotIntervalTicks);
    }

    public void onTick(int currentTick) {
        ++this.tickCounter;
        if (this.tickCounter >= this.snapshotIntervalTicks) {
            this.tickCounter = 0;
            this.takeSnapshotAsync("HOURLY");
        }
    }

    public void forceSnapshot(String snapshotType) {
        this.takeSnapshotAsync(snapshotType);
    }

    private void takeSnapshotAsync(String snapshotType) {
        this.analyticsDb.getExecutor().submit(() -> {
            try {
                SnapshotData data = this.computeSnapshot();
                if (data != null) {
                    AnalyticsDatabase.Snapshot snapshot = new AnalyticsDatabase.Snapshot(System.currentTimeMillis(), snapshotType, data.totalWealth, data.playerCount, data.giniCoefficient, data.avgBalance, data.medianBalance, data.top1PercentShare, data.totalWealth, data.auctionActiveListings, data.auctionTotalValue);
                    this.analyticsDb.insertSnapshot(snapshot);
                    SolidusAnalyticsMod.LOGGER.info("Snapshot taken [{}]: {} players, total wealth={} cents, Gini={}, top1%={}%", new Object[]{snapshotType, data.playerCount, data.totalWealth, String.format("%.4f", data.giniCoefficient), String.format("%.1f", data.top1PercentShare * 100.0)});
                    String today = LocalDate.now(ZoneOffset.UTC).toString();
                    if (!today.equals(this.lastDailySnapshotDate)) {
                        this.lastDailySnapshotDate = today;
                        if (!"DAILY".equals(snapshotType)) {
                            this.takeSnapshotAsync("DAILY");
                        }
                        if (this.engineRef != null && this.engineRef.getWeeklyReportGenerator() != null) {
                            this.engineRef.getWeeklyReportGenerator().checkAndGenerate();
                        }
                    }
                }
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to take snapshot", (Throwable)e);
            }
        });
    }

    // package-private: exercised directly by unit tests
    SnapshotData computeSnapshot() {
        SnapshotData data = new SnapshotData();
        String dbUrl = "jdbc:sqlite:" + this.economyDbPath;
        ArrayList<Long> balances = new ArrayList<Long>();
        try (Connection conn = DriverManager.getConnection(dbUrl);){
            try (Statement stmt2 = conn.createStatement();){
                stmt2.execute("PRAGMA query_only = ON");
            }
            String sql = "SELECT balance FROM player_balances ORDER BY balance ASC";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    // UNIT FIX: economy.db stores DECIMAL S$ units (REAL), not cents.
                    // Convert to cents so every downstream /100 display is correct.
                    balances.add(Math.round(rs.getDouble("balance") * 100.0));
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to read balances for snapshot", (Throwable)e);
            return null;
        }
        if (balances.isEmpty()) {
            SolidusAnalyticsMod.LOGGER.warn("No player balances found. Skipping snapshot.");
            return null;
        }
        data.playerCount = balances.size();
        long totalWealth = 0L;
        for (long b : balances) {
            totalWealth += b;
        }
        data.totalWealth = totalWealth;
        data.avgBalance = totalWealth / (long)data.playerCount;
        int mid = data.playerCount / 2;
        data.medianBalance = data.playerCount % 2 == 0 ? ((Long)balances.get(mid - 1) + (Long)balances.get(mid)) / 2L : (Long)balances.get(mid);
        long[] balanceArray = balances.stream().mapToLong(Long::longValue).toArray();
        data.giniCoefficient = data.playerCount > 1000 ? GiniCoefficient.calculateOptimized(balanceArray) : GiniCoefficient.calculate(balanceArray);
        int top1Count = Math.max(1, (int)Math.ceil((double)data.playerCount * 0.01));
        long top1Wealth = 0L;
        for (int i = data.playerCount - top1Count; i < data.playerCount; ++i) {
            top1Wealth += ((Long)balances.get(i)).longValue();
        }
        data.top1PercentShare = totalWealth > 0L ? (double)top1Wealth / (double)totalWealth : 0.0;
        data.auctionActiveListings = 0;
        data.auctionTotalValue = 0L;
        if (this.auctionsDbPath != null) {
            String auctionUrl = "jdbc:sqlite:" + this.auctionsDbPath;
            try (Connection conn = DriverManager.getConnection(auctionUrl);){
                try (Statement stmt3 = conn.createStatement();){
                    stmt3.execute("PRAGMA query_only = ON");
                }
                String sql = "SELECT COUNT(*) as cnt, COALESCE(SUM(price), 0) as total_val FROM auction_listings WHERE status = 0";
                try (Statement stmt4 = conn.createStatement();
                     ResultSet rs = stmt4.executeQuery(sql);){
                    if (rs.next()) {
                        data.auctionActiveListings = rs.getInt("cnt");
                        // UNIT FIX: auction_listings.price is a DECIMAL S$ figure
                        // (REAL), exactly like the monetary columns in economy.db.
                        // getLong() rounded 1250.50 S$ down to 1250 and stored it
                        // AS-IF it were cents, so auctionTotalValue - and every
                        // consumer reading it (dashboard tiles, weekly report,
                        // goods-value inflation input) - was ~100x too small and
                        // lost fractions. Convert explicitly like the other reads.
                        data.auctionTotalValue = Math.round(rs.getDouble("total_val") * 100.0);
                    }
                }
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.warn("Failed to read auction data for snapshot. Auction metrics will be zero.", (Throwable)e);
            }
        }
        return data;
    }

    // package-private: read directly by unit tests
    static class SnapshotData {
        long totalWealth;
        int playerCount;
        double giniCoefficient;
        long avgBalance;
        long medianBalance;
        double top1PercentShare;
        int auctionActiveListings;
        long auctionTotalValue;

        private SnapshotData() {
        }
    }
}
