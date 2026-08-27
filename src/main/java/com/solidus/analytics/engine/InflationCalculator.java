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
import java.util.concurrent.CompletableFuture;

public class InflationCalculator {
    private static final double INFLATION_WARNING_THRESHOLD = 10.0;
    private static final double MODERATE_INFLATION_THRESHOLD = 5.0;
    private static final double DEFLATION_THRESHOLD = 2.0;
    private final AnalyticsDatabase analyticsDb;
    private final String economyDbPath;
    private final String auctionsDbPath;
    private volatile InflationReport cachedReport;

    public InflationCalculator(AnalyticsDatabase analyticsDb, String economyDbPath, String auctionsDbPath) {
        this.analyticsDb = analyticsDb;
        this.economyDbPath = economyDbPath;
        this.auctionsDbPath = auctionsDbPath;
    }

    public InflationReport calculate() {
        long goodsValueCents;
        long moneySupplyCents;
        InflationReport report = new InflationReport();
        report.timestamp = System.currentTimeMillis();
        report.moneySupplyCents = moneySupplyCents = this.getMoneySupply();
        report.goodsValueCents = goodsValueCents = this.getGoodsValue();
        report.moneyToGoodsRatio = goodsValueCents > 0L ? (double)moneySupplyCents / (double)goodsValueCents : -1.0;
        report.status = this.interpretRatio(report.moneyToGoodsRatio);
        report.inflationRate = this.calculateInflationRateFromSnapshots();
        report.inflationRate24h = this.calculateInflationRate(24);
        report.inflationRate7d = this.calculateInflationRate(168);
        report.inflationRate30d = this.calculateInflationRate(720);
        this.cachedReport = report;
        return report;
    }

    public InflationReport getCachedOrCalculate() {
        if (this.cachedReport != null && System.currentTimeMillis() - this.cachedReport.timestamp < 300000L) {
            return this.cachedReport;
        }
        return this.calculate();
    }

    public CompletableFuture<InflationReport> calculateAsync() {
        return CompletableFuture.supplyAsync(this::calculate, this.analyticsDb.getExecutor());
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private long getMoneySupply() {
        String dbUrl = "jdbc:sqlite:" + this.economyDbPath;
        String sql = "SELECT COALESCE(SUM(balance), 0) as total_wealth, COUNT(*) as player_count FROM player_balances";
        try (Connection conn = DriverManager.getConnection(dbUrl);){
            try (Statement stmt = conn.createStatement();){
                stmt.execute("PRAGMA query_only = ON");
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (!rs.next()) return 0L;
                // UNIT FIX: balances are DECIMAL S$ units in economy.db, not cents.
                long totalCents = Math.round(rs.getDouble("total_wealth") * 100.0);
                return totalCents;
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to read money supply from economy.db", (Throwable)e);
        }
        return 0L;
    }

    private long getGoodsValue() {
        long auctionValue = this.getActiveAuctionValue();
        long shopThroughput = this.estimateShopThroughput();
        return auctionValue + shopThroughput;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private long getActiveAuctionValue() {
        if (this.auctionsDbPath == null) {
            return 0L;
        }
        String dbUrl = "jdbc:sqlite:" + this.auctionsDbPath;
        String sql = "SELECT COALESCE(SUM(price), 0) as total_value FROM auction_listings WHERE status = 0";
        try (Connection conn = DriverManager.getConnection(dbUrl);){
            try (Statement stmt = conn.createStatement();){
                stmt.execute("PRAGMA query_only = ON");
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (!rs.next()) return 0L;
                // UNIT FIX: auction prices are DECIMAL S$ units, not cents.
                return Math.round(rs.getDouble("total_value") * 100.0);
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to read auction value. Using 0.", (Throwable)e);
        }
        return 0L;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private long estimateShopThroughput() {
        String dbUrl = "jdbc:sqlite:" + this.economyDbPath;
        long twentyFourHoursAgo = System.currentTimeMillis() - 86400000L;
        String sql = "SELECT COALESCE(SUM(ABS(amount)), 0) as shop_volume FROM transaction_log WHERE type IN ('SHOP_BUY', 'SHOP_SELL') AND timestamp > ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);){
            try (Statement stmt = conn.createStatement();){
                stmt.execute("PRAGMA query_only = ON");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);){
                ps.setLong(1, twentyFourHoursAgo);
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return 0L;
                    // UNIT FIX: transaction amounts are DECIMAL S$ units, not cents.
                    long shopVolumeCents = Math.round(rs.getDouble("shop_volume") * 100.0);
                    return shopVolumeCents;
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to estimate shop throughput. Using 0.", (Throwable)e);
        }
        return 0L;
    }

    private Double calculateInflationRate(int hoursAgo) {
        AnalyticsDatabase.Snapshot latest = this.analyticsDb.getLatestSnapshot();
        if (latest == null) {
            return null;
        }
        long targetTimestamp = latest.timestamp() - (long)hoursAgo * 3600000L;
        AnalyticsDatabase.Snapshot previous = this.analyticsDb.getSnapshotBefore(targetTimestamp);
        if (previous == null || previous.totalWealth() == 0L) {
            return null;
        }
        return (double)(latest.totalWealth() - previous.totalWealth()) / (double)previous.totalWealth() * 100.0;
    }

    private Double calculateInflationRateFromSnapshots() {
        return this.calculateInflationRate(24);
    }

    private String interpretRatio(double ratio) {
        if (ratio < 0.0) {
            return "NO GOODS AVAILABLE";
        }
        if (ratio < 2.0) {
            return "DEFLATION";
        }
        if (ratio < 5.0) {
            return "HEALTHY";
        }
        if (ratio < 10.0) {
            return "MODERATE INFLATION";
        }
        return "INFLATION WARNING";
    }

    public static class InflationReport {
        public long timestamp;
        public long moneySupplyCents;
        public long goodsValueCents;
        public double moneyToGoodsRatio;
        public String status;
        public Double inflationRate;
        public Double inflationRate24h;
        public Double inflationRate7d;
        public Double inflationRate30d;

        public String formatMoneySupply() {
            return InflationReport.formatCents(this.moneySupplyCents);
        }

        public String formatGoodsValue() {
            return InflationReport.formatCents(this.goodsValueCents);
        }

        public String formatRatio() {
            if (this.moneyToGoodsRatio < 0.0) {
                return "N/A";
            }
            return String.format("%.1f:1", this.moneyToGoodsRatio);
        }

        public String formatRate(Double rate) {
            if (rate == null) {
                return "N/A";
            }
            return String.format("%+.2f%%", rate);
        }

        private static String formatCents(long cents) {
            double dollars = (double)cents / 100.0;
            if (dollars == (double)((long)dollars)) {
                return String.format("%,d", (long)dollars) + " S$";
            }
            return String.format("%,.2f", dollars) + " S$";
        }
    }
}
