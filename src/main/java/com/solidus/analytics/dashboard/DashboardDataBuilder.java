/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.analytics.dashboard;

import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.license.LicenseVerifier;
import com.solidus.analytics.premium.EconomyHealthScore;
import com.solidus.analytics.premium.FraudDetector;
import com.solidus.analytics.storage.AnalyticsDatabase;
import java.util.List;
import java.util.Map;

public class DashboardDataBuilder {
    public static String buildJson(AnalyticsEngine engine) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
        json.append("\"server\":{");
        json.append("\"name\":").append(DashboardDataBuilder.escapeJson(DashboardDataBuilder.getServerName(engine))).append(",");
        json.append("\"fingerprint\":").append(DashboardDataBuilder.escapeJson(LicenseVerifier.computeServerFingerprint()));
        json.append("},");
        DashboardDataBuilder.buildLiveMetrics(json, engine);
        json.append(",");
        DashboardDataBuilder.buildLatestSnapshot(json, engine);
        json.append(",");
        DashboardDataBuilder.buildInflationData(json, engine);
        json.append(",");
        DashboardDataBuilder.buildHealthScore(json, engine);
        json.append(",");
        DashboardDataBuilder.buildFraudAlerts(json, engine);
        json.append(",");
        DashboardDataBuilder.buildDailyHistory(json, engine);
        json.append(",");
        DashboardDataBuilder.buildTopItems(json, engine);
        json.append("}");
        return json.toString();
    }

    private static void buildLiveMetrics(StringBuilder json, AnalyticsEngine engine) {
        LiveMetricsTracker metrics = engine.getLiveMetrics();
        json.append("\"liveMetrics\":{");
        json.append("\"dailyVolume\":").append(metrics.getDailyVolumeCents()).append(",");
        json.append("\"dailyTransactionCount\":").append(metrics.getDailyTransactionCount()).append(",");
        json.append("\"activePlayerCount\":").append(metrics.getActivePlayerCount()).append(",");
        Map<String, Long> byType = metrics.getTransactionsByType();
        json.append("\"transactionsByType\":{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : byType.entrySet()) {
            if (!first) {
                json.append(",");
            }
            json.append(DashboardDataBuilder.escapeJson(entry.getKey())).append(":").append(entry.getValue());
            first = false;
        }
        json.append("}");
        json.append("}");
    }

    private static void buildLatestSnapshot(StringBuilder json, AnalyticsEngine engine) {
        AnalyticsDatabase.Snapshot snapshot = engine.getDatabase().getLatestSnapshot();
        json.append("\"latestSnapshot\":");
        if (snapshot == null) {
            json.append("null");
        } else {
            json.append("{");
            json.append("\"timestamp\":").append(snapshot.timestamp()).append(",");
            json.append("\"type\":").append(DashboardDataBuilder.escapeJson(snapshot.snapshotType())).append(",");
            json.append("\"totalWealth\":").append(snapshot.totalWealth()).append(",");
            json.append("\"playerCount\":").append(snapshot.playerCount()).append(",");
            json.append("\"giniCoefficient\":").append(snapshot.giniCoefficient()).append(",");
            json.append("\"avgBalance\":").append(snapshot.avgBalance()).append(",");
            json.append("\"medianBalance\":").append(snapshot.medianBalance()).append(",");
            json.append("\"top1PercentShare\":").append(snapshot.top1PercentShare()).append(",");
            json.append("\"moneySupply\":").append(snapshot.moneySupply()).append(",");
            json.append("\"auctionActiveListings\":").append(snapshot.auctionActiveListings()).append(",");
            json.append("\"auctionTotalValue\":").append(snapshot.auctionTotalValue());
            json.append("}");
        }
    }

    private static void buildInflationData(StringBuilder json, AnalyticsEngine engine) {
        InflationCalculator.InflationReport report = null;
        try {
            report = engine.getInflationCalculator().getCachedOrCalculate();
        }
        catch (Exception exception) {
            // empty catch block
        }
        json.append("\"inflation\":");
        if (report == null) {
            json.append("null");
        } else {
            json.append("{");
            json.append("\"moneySupplyCents\":").append(report.moneySupplyCents).append(",");
            json.append("\"goodsValueCents\":").append(report.goodsValueCents).append(",");
            json.append("\"moneyToGoodsRatio\":").append(report.moneyToGoodsRatio).append(",");
            json.append("\"status\":").append(DashboardDataBuilder.escapeJson(report.status)).append(",");
            json.append("\"inflationRate24h\":").append(report.inflationRate24h != null ? report.inflationRate24h : "null").append(",");
            json.append("\"inflationRate7d\":").append(report.inflationRate7d != null ? report.inflationRate7d : "null").append(",");
            json.append("\"inflationRate30d\":").append(report.inflationRate30d != null ? report.inflationRate30d : "null");
            json.append("}");
        }
    }

    private static void buildHealthScore(StringBuilder json, AnalyticsEngine engine) {
        json.append("\"healthScore\":");
        if (!engine.isPremiumEnabled() || engine.getHealthScore() == null) {
            json.append("null");
        } else {
            try {
                EconomyHealthScore.HealthReport report = engine.getHealthScore().compute();
                json.append("{");
                json.append("\"overallScore\":").append(report.overallScore).append(",");
                json.append("\"grade\":").append(DashboardDataBuilder.escapeJson(report.getGrade())).append(",");
                json.append("\"summary\":").append(DashboardDataBuilder.escapeJson(report.summary)).append(",");
                json.append("\"giniScore\":").append(report.giniScore).append(",");
                json.append("\"inflationScore\":").append(report.inflationScore).append(",");
                json.append("\"moneyGrowthScore\":").append(report.moneyGrowthScore).append(",");
                json.append("\"activityScore\":").append(report.activityScore).append(",");
                json.append("\"liquidityScore\":").append(report.liquidityScore);
                json.append("}");
            }
            catch (Exception e) {
                json.append("null");
            }
        }
    }

    private static void buildFraudAlerts(StringBuilder json, AnalyticsEngine engine) {
        json.append("\"fraudAlerts\":");
        if (!engine.isPremiumEnabled() || engine.getFraudDetector() == null) {
            json.append("null");
        } else {
            List<FraudDetector.FraudAlert> alerts = engine.getFraudDetector().getRecentAlerts(20);
            json.append("[");
            for (int i = 0; i < alerts.size(); ++i) {
                if (i > 0) {
                    json.append(",");
                }
                FraudDetector.FraudAlert alert = alerts.get(i);
                json.append("{");
                json.append("\"timestamp\":").append(alert.timestamp).append(",");
                json.append("\"type\":").append(DashboardDataBuilder.escapeJson(alert.type.name())).append(",");
                json.append("\"playerName\":").append(DashboardDataBuilder.escapeJson(alert.playerName)).append(",");
                json.append("\"severity\":").append(DashboardDataBuilder.escapeJson(alert.severity.name())).append(",");
                json.append("\"description\":").append(DashboardDataBuilder.escapeJson(alert.description));
                json.append("}");
            }
            json.append("]");
        }
    }

    private static void buildDailyHistory(StringBuilder json, AnalyticsEngine engine) {
        List<AnalyticsDatabase.DailyMetrics> history = engine.getDatabase().getRecentDailyMetrics(30);
        json.append("\"dailyHistory\":[");
        for (int i = 0; i < history.size(); ++i) {
            if (i > 0) {
                json.append(",");
            }
            AnalyticsDatabase.DailyMetrics day = history.get(i);
            json.append("{");
            json.append("\"date\":").append(DashboardDataBuilder.escapeJson(day.date())).append(",");
            json.append("\"transactionCount\":").append(day.transactionCount()).append(",");
            json.append("\"transactionVolume\":").append(day.transactionVolume()).append(",");
            json.append("\"activePlayers\":").append(day.activePlayers()).append(",");
            json.append("\"inflationRate\":").append(day.inflationRate() != null ? day.inflationRate() : "null");
            json.append("}");
        }
        json.append("]");
    }

    private static void buildTopItems(StringBuilder json, AnalyticsEngine engine) {
        LiveMetricsTracker metrics = engine.getLiveMetrics();
        json.append("\"topItems\":{");
        Map<String, Long> topBought = metrics.getTopBoughtItems(10);
        json.append("\"bought\":[");
        int i = 0;
        for (Map.Entry<String, Long> entry : topBought.entrySet()) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"item\":").append(DashboardDataBuilder.escapeJson(entry.getKey())).append(",\"quantity\":").append(entry.getValue()).append("}");
            ++i;
        }
        json.append("],");
        Map<String, Long> topSold = metrics.getTopSoldItems(10);
        json.append("\"sold\":[");
        i = 0;
        for (Map.Entry<String, Long> entry : topSold.entrySet()) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"item\":").append(DashboardDataBuilder.escapeJson(entry.getKey())).append(",\"quantity\":").append(entry.getValue()).append("}");
            ++i;
        }
        json.append("]");
        json.append("}");
    }

    private static String getServerName(AnalyticsEngine engine) {
        try {
            return "Solidus Server";
        }
        catch (Exception e) {
            return "Unknown Server";
        }
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\"");
        block7: for (char c : value.toCharArray()) {
            switch (c) {
                case '\"': {
                    sb.append("\\\"");
                    continue block7;
                }
                case '\\': {
                    sb.append("\\\\");
                    continue block7;
                }
                case '\n': {
                    sb.append("\\n");
                    continue block7;
                }
                case '\r': {
                    sb.append("\\r");
                    continue block7;
                }
                case '\t': {
                    sb.append("\\t");
                    continue block7;
                }
                default: {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", c));
                        continue block7;
                    }
                    sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
