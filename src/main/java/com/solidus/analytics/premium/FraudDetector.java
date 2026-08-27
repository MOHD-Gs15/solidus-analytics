/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.analytics.premium;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class FraudDetector {
    private static final double RAPID_WEALTH_THRESHOLD = 5.0;
    private static final int HIGH_FREQUENCY_THRESHOLD = 30;
    private static final int CIRCULAR_TRADE_THRESHOLD = 5;
    private static final double UNUSUAL_SIZE_THRESHOLD = 10.0;
    private final AnalyticsEngine engine;
    private final String economyDbPath;
    private final List<FraudAlert> recentAlerts = new ArrayList<FraudAlert>();
    private static final int MAX_RECENT_ALERTS = 100;

    public FraudDetector(AnalyticsEngine engine, String economyDbPath) {
        this.engine = engine;
        this.economyDbPath = economyDbPath;
    }

    public List<FraudAlert> runAllChecks() {
        ArrayList<FraudAlert> newAlerts = new ArrayList<FraudAlert>();
        newAlerts.addAll(this.checkRapidWealthGain());
        newAlerts.addAll(this.checkHighFrequencyTrading());
        newAlerts.addAll(this.checkUnusualTransactionSize());
        for (FraudAlert alert : newAlerts) {
            this.addAlert(alert);
        }
        if (!newAlerts.isEmpty()) {
            SolidusAnalyticsMod.LOGGER.warn("Fraud detection found {} suspicious pattern(s).", (Object)newAlerts.size());
        }
        return newAlerts;
    }

    private List<FraudAlert> checkRapidWealthGain() {
        ArrayList<FraudAlert> alerts = new ArrayList<FraudAlert>();
        String dbUrl = "jdbc:sqlite:" + this.economyDbPath;
        long oneHourAgo = System.currentTimeMillis() - 3600000L;
        String sql = "    SELECT player_uuid, player_name,\n           SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) as income,\n           SUM(CASE WHEN amount < 0 THEN ABS(amount) ELSE 0 END) as spending\n    FROM transaction_log\n    WHERE timestamp > ?\n    GROUP BY player_uuid, player_name\n    HAVING income > 0\n    ORDER BY income DESC\n";
        try (Connection conn = DriverManager.getConnection(dbUrl);){
            try (Statement stmt = conn.createStatement();){
                stmt.execute("PRAGMA query_only = ON");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);){
                ps.setLong(1, oneHourAgo);
                try (ResultSet rs = ps.executeQuery();){
                    double totalIncome = 0.0;
                    int playerCount = 0;
                    ArrayList<String[]> results = new ArrayList<String[]>();
                    while (rs.next()) {
                        String uuid = rs.getString("player_uuid");
                        String name = rs.getString("player_name");
                        long income = rs.getLong("income");
                        totalIncome += (double)income;
                        ++playerCount;
                        results.add(new String[]{uuid, name, String.valueOf(income)});
                    }
                    if (playerCount > 0) {
                        double avgIncome = totalIncome / (double)playerCount;
                        for (String[] result : results) {
                            long income = Long.parseLong(result[2]);
                            if (!(avgIncome > 0.0) || !((double)income > avgIncome * 5.0)) continue;
                            alerts.add(new FraudAlert(Instant.now().toEpochMilli(), FraudAlert.Type.RAPID_WEALTH_GAIN, result[1], result[0], String.format("Player earned %,.0f S$ in 1h (server avg: %,.0f S$, %.1fx above average)", (double)income / 100.0, avgIncome / 100.0, (double)income / avgIncome), (double)income > avgIncome * 5.0 * 2.0 ? FraudAlert.Severity.HIGH : FraudAlert.Severity.MEDIUM));
                        }
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check rapid wealth gain", (Throwable)e);
        }
        return alerts;
    }

    private List<FraudAlert> checkHighFrequencyTrading() {
        ArrayList<FraudAlert> alerts = new ArrayList<FraudAlert>();
        String dbUrl = "jdbc:sqlite:" + this.economyDbPath;
        long oneMinuteAgo = System.currentTimeMillis() - 60000L;
        String sql = "    SELECT player_uuid, player_name, COUNT(*) as tx_count\n    FROM transaction_log\n    WHERE timestamp > ?\n    GROUP BY player_uuid, player_name\n    HAVING tx_count > ?\n";
        try (Connection conn = DriverManager.getConnection(dbUrl);){
            try (Statement stmt = conn.createStatement();){
                stmt.execute("PRAGMA query_only = ON");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);){
                ps.setLong(1, oneMinuteAgo);
                ps.setInt(2, 30);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        int txCount = rs.getInt("tx_count");
                        alerts.add(new FraudAlert(Instant.now().toEpochMilli(), FraudAlert.Type.HIGH_FREQUENCY, rs.getString("player_name"), rs.getString("player_uuid"), String.format("Player made %d transactions in 1 minute (threshold: %d)", txCount, 30), txCount > 90 ? FraudAlert.Severity.HIGH : FraudAlert.Severity.LOW));
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check high-frequency trading", (Throwable)e);
        }
        return alerts;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private List<FraudAlert> checkUnusualTransactionSize() {
        ArrayList<FraudAlert> alerts = new ArrayList<FraudAlert>();
        String dbUrl = "jdbc:sqlite:" + this.economyDbPath;
        long oneHourAgo = System.currentTimeMillis() - 3600000L;
        String avgSql = "SELECT AVG(ABS(amount)) as avg_amount FROM transaction_log WHERE timestamp > ?";
        String outlierSql = "    SELECT player_uuid, player_name, ABS(amount) as amount, type\n    FROM transaction_log\n    WHERE timestamp > ? AND ABS(amount) > ?\n    ORDER BY amount DESC LIMIT 10\n";
        try (Connection conn = DriverManager.getConnection(dbUrl);){
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON");
            }
            double avgAmount = 0.0;
            try (PreparedStatement ps = conn.prepareStatement(avgSql)) {
                ps.setLong(1, oneHourAgo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        avgAmount = rs.getDouble("avg_amount");
                    }
                }
            }
            if (!(avgAmount > 0.0) || Double.isNaN(avgAmount) || Double.isInfinite(avgAmount)) {
                return alerts;
            }
            try (PreparedStatement ps = conn.prepareStatement(outlierSql)) {
                ps.setLong(1, oneHourAgo);
                ps.setLong(2, (long)(avgAmount * 10.0));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long amount = rs.getLong("amount");
                        alerts.add(new FraudAlert(Instant.now().toEpochMilli(), FraudAlert.Type.UNUSUAL_SIZE, rs.getString("player_name"), rs.getString("player_uuid"), String.format("Transaction of %,.2f S$ (server avg: %,.2f S$, %.1fx above average) type: %s", (double)amount / 100.0, avgAmount / 100.0, (double)amount / avgAmount, rs.getString("type")), (double)amount > avgAmount * 20.0 ? FraudAlert.Severity.HIGH : FraudAlert.Severity.MEDIUM));
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check unusual transaction sizes", (Throwable)e);
        }
        return alerts;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void addAlert(FraudAlert alert) {
        List<FraudAlert> list = this.recentAlerts;
        synchronized (list) {
            this.recentAlerts.add(alert);
            if (this.recentAlerts.size() > 100) {
                this.recentAlerts.remove(0);
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public List<FraudAlert> getRecentAlerts(int limit) {
        List<FraudAlert> list = this.recentAlerts;
        synchronized (list) {
            int start = Math.max(0, this.recentAlerts.size() - limit);
            return new ArrayList<FraudAlert>(this.recentAlerts.subList(start, this.recentAlerts.size()));
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public int getHighSeverityCount() {
        List<FraudAlert> list = this.recentAlerts;
        synchronized (list) {
            return (int)this.recentAlerts.stream().filter(a -> a.severity == FraudAlert.Severity.HIGH).count();
        }
    }

    public static class FraudAlert {
        public final long timestamp;
        public final Type type;
        public final String playerName;
        public final String playerUuid;
        public final String description;
        public final Severity severity;

        public FraudAlert(long timestamp, Type type, String playerName, String playerUuid, String description, Severity severity) {
            this.timestamp = timestamp;
            this.type = type;
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.description = description;
            this.severity = severity;
        }

        public String format() {
            return String.format("[%s] %s \u2014 %s: %s", new Object[]{this.severity, this.type, this.playerName, this.description});
        }

        public static enum Type {
            RAPID_WEALTH_GAIN,
            HIGH_FREQUENCY,
            CIRCULAR_TRADING,
            UNUSUAL_SIZE,
            ZERO_VALUE_TRANSFER;

        }

        public static enum Severity {
            LOW,
            MEDIUM,
            HIGH;

        }
    }
}
