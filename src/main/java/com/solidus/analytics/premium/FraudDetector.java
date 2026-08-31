package com.solidus.analytics.premium;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.DirectDb;
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
        newAlerts.addAll(this.checkCircularTrading());
        newAlerts.addAll(this.checkZeroValueTransfers());
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
                long oneHourAgo = System.currentTimeMillis() - 3600000L;
        String sql = "    SELECT player_uuid, player_name,\n           SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) as income,\n           SUM(CASE WHEN amount < 0 THEN ABS(amount) ELSE 0 END) as spending\n    FROM transaction_log\n    WHERE timestamp > ?\n    GROUP BY player_uuid, player_name\n    HAVING income > 0\n    ORDER BY income DESC\n";
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath)) {
            try (PreparedStatement ps = conn.prepareStatement(sql);){
                ps.setLong(1, oneHourAgo);
                try (ResultSet rs = ps.executeQuery();){
                    // PRECISION FIX: SUM(amount) over a REAL column yields DECIMAL
                    // S$ (e.g. 12.75). getLong() silently truncated every player's
                    // income to whole S$ BEFORE the 5x-average comparison ran, so
                    // alerts were computed on rounded data. Use doubles end-to-end;
                    // alert text keeps raw S$ display units (NOT cents).
                    double totalIncome = 0.0;
                    int playerCount = 0;
                    ArrayList<PlayerIncome> results = new ArrayList<PlayerIncome>();
                    while (rs.next()) {
                        String uuid = rs.getString("player_uuid");
                        String name = rs.getString("player_name");
                        double income = rs.getDouble("income");
                        totalIncome += income;
                        ++playerCount;
                        results.add(new PlayerIncome(uuid, name, income));
                    }
                    if (playerCount > 0) {
                        double avgIncome = totalIncome / (double)playerCount;
                        for (PlayerIncome result : results) {
                            if (!(avgIncome > 0.0) || !(result.income > avgIncome * 5.0)) continue;
                            alerts.add(new FraudAlert(Instant.now().toEpochMilli(), FraudAlert.Type.RAPID_WEALTH_GAIN, result.playerName, result.playerUuid, String.format("Player earned %,.2f S$ in 1h (server avg: %,.2f S$, %.1fx above average)", result.income, avgIncome, result.income / avgIncome), result.income > avgIncome * 5.0 * 2.0 ? FraudAlert.Severity.HIGH : FraudAlert.Severity.MEDIUM));
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
                long oneMinuteAgo = System.currentTimeMillis() - 60000L;
        String sql = "    SELECT player_uuid, player_name, COUNT(*) as tx_count\n    FROM transaction_log\n    WHERE timestamp > ?\n    GROUP BY player_uuid, player_name\n    HAVING tx_count > ?\n";
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath)) {
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

    private List<FraudAlert> checkUnusualTransactionSize() {
        ArrayList<FraudAlert> alerts = new ArrayList<FraudAlert>();
                long oneHourAgo = System.currentTimeMillis() - 3600000L;
        String avgSql = "SELECT AVG(ABS(amount)) as avg_amount FROM transaction_log WHERE timestamp > ?";
        String outlierSql = "    SELECT player_uuid, player_name, ABS(amount) as amount, type\n    FROM transaction_log\n    WHERE timestamp > ? AND ABS(amount) > ?\n    ORDER BY amount DESC LIMIT 10\n";
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath)){
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
                ps.setDouble(2, avgAmount * 10.0);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // PRECISION FIX: ABS(amount) of a REAL column keeps
                        // fractions (12.75 S$); getLong() read 12. Amounts stay
                        // in raw S$ display units (NOT cents) in alert text.
                        double amount = rs.getDouble("amount");
                        alerts.add(new FraudAlert(Instant.now().toEpochMilli(), FraudAlert.Type.UNUSUAL_SIZE, rs.getString("player_name"), rs.getString("player_uuid"), String.format("Transaction of %,.2f S$ (server avg: %,.2f S$, %.1fx above average) type: %s", amount, avgAmount, amount / avgAmount, rs.getString("type")), amount > avgAmount * 20.0 ? FraudAlert.Severity.HIGH : FraudAlert.Severity.MEDIUM));
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check unusual transaction sizes", (Throwable)e);
        }
        return alerts;
    }

    /**
     * R13: implements the previously-declared-but-never-executed
     * CIRCULAR_TRADING check. Fetches the last 24h of peer-to-peer payments
     * (PAY_SEND rows: sender -> target) and detects closed loops - wash-trading
     * patterns where money keeps returning to its origin (A->B & B->A ping-pong,
     * or A->B->C->A round trips). Each loop is one alert; a player participating
     * in {@value #CIRCULAR_TRADE_THRESHOLD}+ loops is escalated to HIGH.
     */
    private List<FraudAlert> checkCircularTrading() {
        ArrayList<FraudAlert> alerts = new ArrayList<FraudAlert>();
        long oneDayAgo = System.currentTimeMillis() - 86_400_000L;
        // PAY_SEND only: every transfer also writes a mirrored PAY_RECEIVE row,
        // so scanning both would double-count every edge.
        String sql = "SELECT player_uuid, player_name, target_uuid, target_name, ABS(amount) as amount\n"
            + "FROM transaction_log\n"
            + "WHERE timestamp > ? AND type = 'PAY_SEND'\n"
            + "LIMIT 50000\n"; // bounded scan: fraud checks must never pin memory
        // Graph: sender -> list of (receiver, amount)
        java.util.Map<String, java.util.List<Edge>> graph = new java.util.HashMap<>();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, oneDayAgo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String from = rs.getString("player_uuid");
                    String to = rs.getString("target_uuid");
                    if (from == null || to == null || from.equals(to)) continue;
                    graph.computeIfAbsent(from, k -> new ArrayList<>())
                        .add(new Edge(to, rs.getDouble("amount")));
                    names.putIfAbsent(from, rs.getString("player_name"));
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check circular trading", (Throwable)e);
            return alerts;
        }
        if (graph.isEmpty()) {
            return alerts;
        }
        // 2-loops (ping-pong): A->B and B->A
        java.util.Map<String, Integer> loopsPerPlayer = new java.util.HashMap<>();
        java.util.Set<String> reportedPairs = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, java.util.List<Edge>> entry : graph.entrySet()) {
            String a = entry.getKey();
            for (Edge e1 : entry.getValue()) {
                String b = e1.to();
                if (a.compareTo(b) < 0 && graph.containsKey(b)
                        && graph.get(b).stream().anyMatch(e2 -> e2.to().equals(a))) {
                    String pairKey = a + "|" + b;
                    if (reportedPairs.add(pairKey)) {
                        loopsPerPlayer.merge(a, 1, Integer::sum);
                        loopsPerPlayer.merge(b, 1, Integer::sum);
                        alerts.add(new FraudAlert(Instant.now().toEpochMilli(),
                            FraudAlert.Type.CIRCULAR_TRADING, names.get(a), a,
                            String.format("Two-way payment loop detected between %s and %s (mutual transfers within 24h)",
                                names.get(a), names.get(b)),
                            FraudAlert.Severity.MEDIUM));
                    }
                }
            }
        }
        // 3-loops (round trips): A->B->C->A
        for (java.util.Map.Entry<String, java.util.List<Edge>> entry : graph.entrySet()) {
            String a = entry.getKey();
            for (Edge ab : entry.getValue()) {
                java.util.List<Edge> fromB = graph.get(ab.to());
                if (fromB == null) continue;
                for (Edge bc : fromB) {
                    if (bc.to().equals(a)) continue; // that is a 2-loop
                    java.util.List<Edge> fromC = graph.get(bc.to());
                    if (fromC == null) continue;
                    boolean closes = fromC.stream().anyMatch(ca -> ca.to().equals(a));
                    if (closes && reportedPairs.add("3|" + a + "|" + ab.to() + "|" + bc.to())) {
                        loopsPerPlayer.merge(a, 1, Integer::sum);
                        alerts.add(new FraudAlert(Instant.now().toEpochMilli(),
                            FraudAlert.Type.CIRCULAR_TRADING, names.get(a), a,
                            String.format("Circular payment route %s -> %s -> %s -> %s within 24h",
                                names.get(a), names.get(ab.to()), names.get(bc.to()), names.get(a)),
                            FraudAlert.Severity.MEDIUM));
                    }
                }
            }
        }
        // Escalate repeat participants
        for (FraudAlert alert : alerts) {
            if (loopsPerPlayer.getOrDefault(alert.playerUuid, 0) >= CIRCULAR_TRADE_THRESHOLD) {
                alerts.set(alerts.indexOf(alert), new FraudAlert(alert.timestamp, alert.type,
                    alert.playerName, alert.playerUuid,
                    alert.description + String.format(" - player involved in %d loops (threshold: %d)",
                        loopsPerPlayer.get(alert.playerUuid), CIRCULAR_TRADE_THRESHOLD),
                    FraudAlert.Severity.HIGH));
            }
        }
        return alerts;
    }

    /**
     * R13: implements the previously-declared-but-never-executed
     * ZERO_VALUE_TRANSFER check. Core rejects amount <= 0 on every payment
     * path, so a zero/near-zero transfer row can only appear through a mod-side
     * bypass or direct database writes - a data-integrity anomaly worth flagging
     * (fake activity volume, notification spam, or a broken integration).
     */
    private List<FraudAlert> checkZeroValueTransfers() {
        ArrayList<FraudAlert> alerts = new ArrayList<FraudAlert>();
        long oneDayAgo = System.currentTimeMillis() - 86_400_000L;
        String sql = "SELECT player_uuid, player_name, COUNT(*) as zero_count\n"
            + "FROM transaction_log\n"
            + "WHERE timestamp > ? AND type = 'PAY_SEND' AND ABS(amount) < 0.01\n"
            + "GROUP BY player_uuid, player_name\n";
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, oneDayAgo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int zeroCount = rs.getInt("zero_count");
                    alerts.add(new FraudAlert(Instant.now().toEpochMilli(),
                        FraudAlert.Type.ZERO_VALUE_TRANSFER, rs.getString("player_name"),
                        rs.getString("player_uuid"),
                        String.format("%d zero-value payment(s) in 24h - Core rejects amount<=0, this suggests a bypass or DB write", zeroCount),
                        zeroCount > 10 ? FraudAlert.Severity.HIGH : FraudAlert.Severity.LOW));
                }
            }
        } catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to check zero-value transfers", (Throwable)e);
        }
        return alerts;
    }

    private void addAlert(FraudAlert alert) {
        List<FraudAlert> list = this.recentAlerts;
        synchronized (list) {
            this.recentAlerts.add(alert);
            if (this.recentAlerts.size() > 100) {
                this.recentAlerts.remove(0);
            }
        }
    }

    public List<FraudAlert> getRecentAlerts(int limit) {
        List<FraudAlert> list = this.recentAlerts;
        synchronized (list) {
            int start = Math.max(0, this.recentAlerts.size() - limit);
            return new ArrayList<FraudAlert>(this.recentAlerts.subList(start, this.recentAlerts.size()));
        }
    }

    public int getHighSeverityCount() {
        List<FraudAlert> list = this.recentAlerts;
        synchronized (list) {
            return (int)this.recentAlerts.stream().filter(a -> a.severity == FraudAlert.Severity.HIGH).count();
        }
    }

    // PRECISION FIX: typed carrier replacing the old String[] + Long.parseLong
    // round-trip that froze incomes to whole S$ values.
    private static final class PlayerIncome {
        final String playerUuid;
        final String playerName;
        final double income;

        PlayerIncome(String playerUuid, String playerName, double income) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.income = income;
        }
    }

    /** Directed transfer edge used by the circular-trading graph scan. */
    private record Edge(String to, double amount) {
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
