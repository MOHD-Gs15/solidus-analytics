package com.solidus.analytics.premium;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.premium.DiscordWebhookNotifier;
import com.solidus.analytics.premium.EconomyHealthScore;
import com.solidus.analytics.premium.FraudDetector;
import com.solidus.analytics.storage.AnalyticsDatabase;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WeeklyReportGenerator {
    private final AnalyticsEngine engine;
    private final Path reportsDir;
    private final AnalyticsDatabase database;
    private volatile int lastReportWeek = -1;

    public WeeklyReportGenerator(AnalyticsEngine engine, Path configDir) {
        this.engine = engine;
        this.database = engine.getDatabase();
        this.reportsDir = configDir.resolve("reports");
        this.lastReportWeek = this.loadLastReportWeekFromDB();
    }

    public void checkAndGenerate() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        // FIX: week identity must use the ISO WEEK-BASED year, not the calendar
        // year. Around New Year the calendar year and the ISO week-year diverge,
        // which produced colliding week keys and skipped/duplicated reports.
        int currentWeek = now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int weekBasedYear = now.get(IsoFields.WEEK_BASED_YEAR);
        if (now.getDayOfWeek().getValue() != 1) {
            return;
        }
        int weekKey = weekBasedYear * 100 + currentWeek;
        if (weekKey == this.lastReportWeek) {
            return;
        }
        this.lastReportWeek = weekKey;
        this.persistLastReportWeek(weekKey);
        this.generateReport();
    }

    private int loadLastReportWeekFromDB() {
        try {
            String value = this.database.getMetadataValue("last_weekly_report_week");
            if (value != null) {
                int weekKey = Integer.parseInt(value);
                SolidusAnalyticsMod.LOGGER.info("Weekly report: loaded last report week from DB: {}", (Object)weekKey);
                return weekKey;
            }
        }
        catch (NumberFormatException e) {
            SolidusAnalyticsMod.LOGGER.warn("Invalid last report week value in DB, starting fresh", (Throwable)e);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("Could not load last report week from DB, starting fresh", (Throwable)e);
        }
        return -1;
    }

    private void persistLastReportWeek(int weekKey) {
        try {
            this.database.setMetadataValue("last_weekly_report_week", String.valueOf(weekKey));
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to persist last report week to DB", (Throwable)e);
        }
    }

    public Path forceGenerate() {
        return this.generateReport();
    }

    private Path generateReport() {
        try {
            Files.createDirectories(this.reportsDir, new FileAttribute[0]);
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to create reports directory", (Throwable)e);
            return null;
        }
        String dateStr = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path reportFile = this.reportsDir.resolve("weekly-report-" + dateStr + ".txt");
        try {
            String content = this.buildReport();
            try (BufferedWriter writer = Files.newBufferedWriter(reportFile, new OpenOption[0]);){
                writer.write(content);
            }
            SolidusAnalyticsMod.LOGGER.info("Weekly report generated: {}", (Object)reportFile);
            DiscordWebhookNotifier discord = this.engine.getDiscordNotifier();
            if (discord != null && discord.isEnabled()) {
                this.sendDiscordSummary(discord, content);
            }
            return reportFile;
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to write weekly report", (Throwable)e);
            return null;
        }
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        String separator = "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550";
        LocalDate reportDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate weekStart = reportDate.minusDays(6L);
        sb.append(separator).append("\n");
        sb.append("  SOLIDUS ANALYTICS \u2014 WEEKLY ECONOMY REPORT\n");
        sb.append("  Period: ").append(weekStart).append(" to ").append(reportDate).append("\n");
        sb.append("  Generated: ").append(Instant.now()).append("\n");
        sb.append(separator).append("\n\n");
        sb.append("EXECUTIVE SUMMARY\n");
        sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
        EconomyHealthScore.HealthReport healthReport = null;
        if (this.engine.isPremiumEnabled() && this.engine.getHealthScore() != null) {
            healthReport = this.engine.getHealthScore().compute();
            sb.append(String.format("  Economy Health Score: %.1f / 100 (Grade: %s)\n", healthReport.overallScore, healthReport.getGrade()));
            sb.append("  Assessment: ").append(healthReport.summary).append("\n\n");
        } else {
            sb.append("  (Health score requires premium license)\n\n");
        }
        sb.append("KEY METRICS (Last 7 Days)\n");
        sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
        AnalyticsDatabase db = this.engine.getDatabase();
        List<AnalyticsDatabase.DailyMetrics> weekData = db.getRecentDailyMetrics(7);
        if (weekData.isEmpty()) {
            sb.append("  No daily metrics data available yet.\n\n");
        } else {
            long totalVolume = 0L;
            int totalTransactions = 0;
            int totalActivePlayers = 0;
            int daysWithData = weekData.size();
            for (AnalyticsDatabase.DailyMetrics dailyMetrics : weekData) {
                totalVolume += dailyMetrics.transactionVolume();
                totalTransactions += dailyMetrics.transactionCount();
                totalActivePlayers = Math.max(totalActivePlayers, dailyMetrics.activePlayers());
            }
            sb.append(String.format("  Total Volume:         %,.2f S$\n", (double)totalVolume / 100.0));
            sb.append(String.format("  Total Transactions:   %,d\n", totalTransactions));
            sb.append(String.format("  Avg Daily Volume:     %,.2f S$\n", (double)totalVolume / 100.0 / (double)daysWithData));
            sb.append(String.format("  Avg Daily Tx Count:   %,d\n", totalTransactions / daysWithData));
            sb.append(String.format("  Peak Active Players:  %d\n", totalActivePlayers));
            sb.append("\n");
            sb.append("  Daily Breakdown:\n");
            sb.append("  \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510\n");
            sb.append("  \u2502 Date       \u2502 Tx Count \u2502 Volume          \u2502 Active  \u2502\n");
            sb.append("  \u251c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u253c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u253c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u253c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2524\n");
            for (AnalyticsDatabase.DailyMetrics dailyMetrics : weekData) {
                sb.append(String.format("  \u2502 %s \u2502 %,8d \u2502 %,14.2f S$ \u2502 %,7d \u2502\n", dailyMetrics.date(), dailyMetrics.transactionCount(), (double)dailyMetrics.transactionVolume() / 100.0, dailyMetrics.activePlayers()));
            }
            sb.append("  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518\n\n");
        }
        sb.append("INFLATION ANALYSIS\n");
        sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
        InflationCalculator.InflationReport inflation = null;
        try {
            inflation = this.engine.getInflationCalculator().getCachedOrCalculate();
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("Could not get inflation report for weekly summary", (Throwable)e);
        }
        if (inflation != null) {
            sb.append(String.format("  Money Supply:       %s\n", inflation.formatMoneySupply()));
            sb.append(String.format("  Goods Value:        %s\n", inflation.formatGoodsValue()));
            sb.append(String.format("  Money:Goods Ratio:  %s\n", inflation.formatRatio()));
            sb.append(String.format("  Status:             %s\n", inflation.status));
            sb.append("\n");
            sb.append("  Inflation Rates:\n");
            sb.append(String.format("    24h:  %s\n", inflation.formatRate(inflation.inflationRate24h)));
            sb.append(String.format("    7d:   %s\n", inflation.formatRate(inflation.inflationRate7d)));
            sb.append(String.format("    30d:  %s\n", inflation.formatRate(inflation.inflationRate30d)));
            sb.append("\n  Reference:\n");
            sb.append("    Ratio < 2:1   = Deflation\n");
            sb.append("    Ratio 2-5:1   = Healthy\n");
            sb.append("    Ratio 5-10:1  = Moderate Inflation\n");
            sb.append("    Ratio > 10:1  = Inflation Warning\n\n");
        } else {
            sb.append("  Insufficient data for inflation analysis.\n\n");
        }
        sb.append("WEALTH DISTRIBUTION\n");
        sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
        AnalyticsDatabase.Snapshot latest = db.getLatestSnapshot();
        if (latest != null) {
            sb.append(String.format("  Total Wealth:       %,.2f S$\n", (double)latest.totalWealth() / 100.0));
            sb.append(String.format("  Player Count:       %d\n", latest.playerCount()));
            sb.append(String.format("  Average Balance:    %,.2f S$\n", (double)latest.avgBalance() / 100.0));
            sb.append(String.format("  Median Balance:     %,.2f S$\n", (double)latest.medianBalance() / 100.0));
            sb.append(String.format("  Gini Coefficient:   %.4f (%s)\n", latest.giniCoefficient(), this.interpretGini(latest.giniCoefficient())));
            sb.append(String.format("  Top 1%% Share:       %.1f%%\n", latest.top1PercentShare() * 100.0));
            sb.append(String.format("  Active Auctions:    %d (value: %,.2f S$)\n", latest.auctionActiveListings(), (double)latest.auctionTotalValue() / 100.0));
            sb.append("\n");
        } else {
            sb.append("  No snapshot data available yet.\n\n");
        }
        sb.append("TOP TRADED ITEMS (Today)\n");
        sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
        LiveMetricsTracker metrics = this.engine.getLiveMetrics();
        Map<String, Long> topBought = metrics.getTopBoughtItems(5);
        Map<String, Long> topSold = metrics.getTopSoldItems(5);
        if (!topBought.isEmpty()) {
            sb.append("  Most Bought:\n");
            int rank = 1;
            for (Map.Entry<String, Long> entry : topBought.entrySet()) {
                sb.append(String.format("    #%d  %s: %d units\n", rank++, entry.getKey(), entry.getValue()));
            }
        }
        if (!topSold.isEmpty()) {
            sb.append("  Most Sold:\n");
            int rank = 1;
            for (Map.Entry<String, Long> entry : topSold.entrySet()) {
                sb.append(String.format("    #%d  %s: %d units\n", rank++, entry.getKey(), entry.getValue()));
            }
        }
        if (topBought.isEmpty() && topSold.isEmpty()) {
            sb.append("  No item data available.\n");
        }
        sb.append("\n");
        if (this.engine.isPremiumEnabled() && this.engine.getFraudDetector() != null) {
            sb.append("FRAUD ALERTS (This Week)\n");
            sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
            List<FraudDetector.FraudAlert> alerts = this.engine.getFraudDetector().getRecentAlerts(20);
            if (alerts.isEmpty()) {
                sb.append("  No suspicious activity detected. Economy looks clean.\n\n");
            } else {
                long l = alerts.stream().filter(a -> a.severity == FraudDetector.FraudAlert.Severity.HIGH).count();
                long medCount = alerts.stream().filter(a -> a.severity == FraudDetector.FraudAlert.Severity.MEDIUM).count();
                sb.append(String.format("  Total alerts: %d (HIGH: %d, MEDIUM: %d, LOW: %d)\n\n", alerts.size(), l, medCount, (long)alerts.size() - l - medCount));
                for (FraudDetector.FraudAlert alert : alerts) {
                    sb.append(String.format("  [%s] %s \u2014 %s\n", new Object[]{alert.severity, alert.type, alert.playerName}));
                    sb.append(String.format("         %s\n", alert.description));
                }
                sb.append("\n");
            }
        }
        sb.append("RECOMMENDATIONS\n");
        sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
        List<String> recommendations = this.generateRecommendations(latest, inflation, healthReport);
        if (recommendations.isEmpty()) {
            sb.append("  Economy is healthy. No immediate actions needed.\n");
        } else {
            for (int i = 0; i < recommendations.size(); i++) {
                sb.append(String.format("  %d. %s\n", i + 1, recommendations.get(i)));
            }
        }
        sb.append("\n").append(separator).append("\n");
        sb.append("  Report generated by Solidus Analytics\n");
        sb.append(separator).append("\n");
        return sb.toString();
    }

    private List<String> generateRecommendations(AnalyticsDatabase.Snapshot snapshot, InflationCalculator.InflationReport inflation, EconomyHealthScore.HealthReport health) {
        ArrayList<String> recs = new ArrayList<String>();
        if (inflation != null) {
            if (inflation.moneyToGoodsRatio > 10.0) {
                recs.add("High inflation detected. Consider increasing shop prices or adding money sinks (taxes, fees, luxury items) to reduce the money supply.");
            } else if (inflation.moneyToGoodsRatio < 2.0 && inflation.moneyToGoodsRatio > 0.0) {
                recs.add("Deflation detected. Consider reducing shop prices or adding ways for players to earn money to stimulate spending.");
            }
            if (inflation.inflationRate7d != null && inflation.inflationRate7d > 15.0) {
                recs.add("Weekly inflation rate exceeds 15%. This is concerning \u2014 investigate potential money duplication exploits or overly generous reward systems.");
            }
        }
        if (snapshot != null) {
            if (snapshot.giniCoefficient() > 0.6) {
                recs.add("High wealth inequality (Gini > 0.6). Consider implementing progressive taxes, welfare systems, or new player bonuses to distribute wealth more evenly.");
            }
            if (snapshot.top1PercentShare() > 0.4) {
                recs.add(String.format("Top 1%% of players hold %.1f%% of all wealth. This may discourage new players. Consider wealth redistribution mechanisms.", snapshot.top1PercentShare() * 100.0));
            }
            if (snapshot.auctionActiveListings() < 5 && snapshot.playerCount() > 20) {
                recs.add("Very few active auction listings relative to player count. The market may be illiquid. Consider encouraging auctions through incentives or events.");
            }
        }
        if (health != null) {
            if (health.activityScore < 40.0) {
                recs.add("Low transaction activity. Players may be hoarding money instead of trading. Consider adding limited-time shop deals or events to stimulate the economy.");
            }
            if (health.liquidityScore < 40.0) {
                recs.add("Low market liquidity. There are not enough goods available for purchase. Consider adding more items to the server shop or encouraging player trading.");
            }
        }
        if (recs.isEmpty()) {
            recs.add("Economy is healthy. No immediate actions needed.");
        }
        return recs;
    }

    private void sendDiscordSummary(DiscordWebhookNotifier discord, String fullReport) {
        StringBuilder summary = new StringBuilder();
        String[] lines = fullReport.split("\n");
        boolean inSection = false;
        int sectionCount = 0;
        for (String line : lines) {
            if (line.startsWith("EXECUTIVE") || line.startsWith("KEY METRICS") || line.startsWith("RECOMMENDATIONS")) {
                inSection = true;
                ++sectionCount;
            } else if (line.startsWith("INFLATION") || line.startsWith("WEALTH") || line.startsWith("TOP TRADED") || line.startsWith("FRAUD")) {
                inSection = false;
            }
            if (inSection || line.contains("SOLIDUS ANALYTICS") || line.contains("\u2550\u2550\u2550")) {
                summary.append(line).append("\n");
            }
            if (summary.length() <= 1800) continue;
            summary.append("... (full report saved to file)\n");
            break;
        }
        String color = "5763719";
        discord.notifyCustomEmbed("Weekly Economy Report", summary.toString(), color);
    }

    private String interpretGini(double gini) {
        if (gini < 0.2) {
            return "Very Low Inequality";
        }
        if (gini < 0.3) {
            return "Low Inequality";
        }
        if (gini < 0.4) {
            return "Moderate Inequality";
        }
        if (gini < 0.5) {
            return "High Inequality";
        }
        if (gini < 0.7) {
            return "Very High Inequality";
        }
        return "Extreme Inequality";
    }
}
