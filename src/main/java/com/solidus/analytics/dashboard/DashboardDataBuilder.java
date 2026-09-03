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
    /**
     * Contract version of the /api/data JSON payload (audit 12.1 exit
     * criterion #3): consumers (local web app, encrypted GitHub Pages
     * channel, any future external reader) may branch on this number; any
     * breaking field rename or removal MUST bump it. Additive-only changes
     * keep version 1.
     */
    public static final long SCHEMA_VERSION = 1L;

    public static String buildJson(AnalyticsEngine engine) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"schemaVersion\":").append(DashboardDataBuilder.SCHEMA_VERSION).append(",");
        json.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
        json.append("\"server\":{");
        json.append("\"name\":").append(DashboardDataBuilder.escapeJson(DashboardDataBuilder.getServerName(engine))).append(",");
        json.append("\"fingerprint\":").append(DashboardDataBuilder.escapeJson(LicenseVerifier.computeServerFingerprint()));
        json.append("},");
        DashboardDataBuilder.buildLiveMetrics(json, engine);
        json.append(",");
        DashboardDataBuilder.buildLatestSnapshot(json, engine);
        json.append(",");
        DashboardDataBuilder.buildSnapshotTrend(json, engine);
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
        json.append(",");
        DashboardDataBuilder.buildWealthDistribution(json, engine);
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
            json.append("\"giniCoefficient\":").append(DashboardDataBuilder.num(snapshot.giniCoefficient())).append(",");
            json.append("\"avgBalance\":").append(snapshot.avgBalance()).append(",");
            json.append("\"medianBalance\":").append(snapshot.medianBalance()).append(",");
            json.append("\"top1PercentShare\":").append(DashboardDataBuilder.num(snapshot.top1PercentShare())).append(",");
            json.append("\"moneySupply\":").append(snapshot.moneySupply()).append(",");
            json.append("\"auctionActiveListings\":").append(snapshot.auctionActiveListings()).append(",");
            json.append("\"auctionTotalValue\":").append(snapshot.auctionTotalValue());
            json.append("}");
        }
    }

    /**
     * Delta of the latest snapshot against the snapshot taken before it
     * (dashboard trend arrows). Additive field set - schema version stays 1
     * per the documented policy. Deltas are absolute (latest minus previous);
     * the client formats and colors them. Null when fewer than two snapshots
     * exist (fresh install) - never zeros, mirroring Table 8 rules.
     */
    private static void buildSnapshotTrend(StringBuilder json, AnalyticsEngine engine) {
        json.append("\"snapshotTrend\":");
        AnalyticsDatabase database = engine.getDatabase();
        AnalyticsDatabase.Snapshot latest = database == null ? null : database.getLatestSnapshot();
        AnalyticsDatabase.Snapshot previous = latest == null ? null : database.getSnapshotBefore(latest.timestamp() - 1L);
        if (latest == null || previous == null) {
            json.append("null");
            return;
        }
        json.append("{");
        json.append("\"previousTimestamp\":").append(previous.timestamp()).append(",");
        json.append("\"totalWealthDelta\":").append(latest.totalWealth() - previous.totalWealth()).append(",");
        json.append("\"moneySupplyDelta\":").append(latest.moneySupply() - previous.moneySupply()).append(",");
        json.append("\"playerCountDelta\":").append(latest.playerCount() - previous.playerCount()).append(",");
        json.append("\"giniDelta\":").append(DashboardDataBuilder.num(latest.giniCoefficient() - previous.giniCoefficient())).append(",");
        json.append("\"top1ShareDelta\":").append(DashboardDataBuilder.num(latest.top1PercentShare() - previous.top1PercentShare())).append(",");
        json.append("\"auctionListingsDelta\":").append(latest.auctionActiveListings() - previous.auctionActiveListings());
        json.append("}");
    }

    /**
     * Live wealth distribution (donut + richest players) from the read-only
     * Core economy view. Null when the provider is not wired (unit stubs) or
     * the economy database has no players yet.
     */
    private static void buildWealthDistribution(StringBuilder json, AnalyticsEngine engine) {
        json.append("\"wealthDistribution\":");
        WealthDistributionProvider provider = engine.getWealthDistributionProvider();
        WealthDistributionProvider.WealthDistribution data = provider == null ? null : provider.get();
        if (data == null) {
            json.append("null");
            return;
        }
        json.append("{");
        json.append("\"computedAt\":").append(data.computedAt()).append(",");
        json.append("\"totalWealth\":").append(data.totalWealthCents()).append(",");
        json.append("\"playerCount\":").append(data.playerCount()).append(",");
        json.append("\"top1Share\":").append(DashboardDataBuilder.num(data.top1Share())).append(",");
        json.append("\"top10Share\":").append(DashboardDataBuilder.num(data.top10Share())).append(",");
        json.append("\"topPlayers\":[");
        List<WealthDistributionProvider.TopPlayer> players = data.topPlayers();
        for (int i = 0; i < players.size(); ++i) {
            if (i > 0) {
                json.append(",");
            }
            WealthDistributionProvider.TopPlayer player = players.get(i);
            json.append("{");
            json.append("\"rank\":").append(player.rank()).append(",");
            json.append("\"name\":").append(DashboardDataBuilder.escapeJson(player.name())).append(",");
            json.append("\"balance\":").append(player.balanceCents()).append(",");
            json.append("\"share\":").append(DashboardDataBuilder.num(player.share()));
            json.append("}");
        }
        json.append("]}");
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
            json.append("\"moneyToGoodsRatio\":").append(DashboardDataBuilder.num(report.moneyToGoodsRatio)).append(",");
            json.append("\"status\":").append(DashboardDataBuilder.escapeJson(report.status)).append(",");
            json.append("\"inflationRate24h\":").append(DashboardDataBuilder.num(report.inflationRate24h)).append(",");
            json.append("\"inflationRate7d\":").append(DashboardDataBuilder.num(report.inflationRate7d)).append(",");
            json.append("\"inflationRate30d\":").append(DashboardDataBuilder.num(report.inflationRate30d));
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
                json.append("\"overallScore\":").append(DashboardDataBuilder.num(report.overallScore)).append(",");
                json.append("\"grade\":").append(DashboardDataBuilder.escapeJson(report.getGrade())).append(",");
                json.append("\"summary\":").append(DashboardDataBuilder.escapeJson(report.summary)).append(",");
                json.append("\"giniScore\":").append(DashboardDataBuilder.num(report.giniScore)).append(",");
                json.append("\"inflationScore\":").append(DashboardDataBuilder.num(report.inflationScore)).append(",");
                json.append("\"moneyGrowthScore\":").append(DashboardDataBuilder.num(report.moneyGrowthScore)).append(",");
                json.append("\"activityScore\":").append(DashboardDataBuilder.num(report.activityScore)).append(",");
                json.append("\"liquidityScore\":").append(DashboardDataBuilder.num(report.liquidityScore));
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
            json.append("\"inflationRate\":").append(DashboardDataBuilder.num(day.inflationRate()));
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

    // JSON SAFETY: doubles computed from statistics can become NaN or
    // +/-Infinity on corrupt or foreign data. Appending them literally emits
    // "NaN"/"Infinity" tokens that are INVALID JSON and break the entire
    // dashboard payload (JSON.parse throws client-side). Map them to null.
    private static String num(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        return Double.toString(value);
    }

    private static String num(Double value) {
        return value == null ? "null" : DashboardDataBuilder.num(value.doubleValue());
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
                        // Fix (audit P1, found by the contract test): %04x
                        // rejects a boxed Character - a control char in any
                        // player string used to throw here and kill the whole
                        // dashboard payload. Cast to int first.
                        sb.append(String.format("\\u%04x", (int) c));
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
