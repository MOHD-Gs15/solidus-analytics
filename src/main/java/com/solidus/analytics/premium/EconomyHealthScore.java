package com.solidus.analytics.premium;

import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.storage.AnalyticsDatabase;

public class EconomyHealthScore {
    private static final double WEIGHT_GINI = 0.25;
    private static final double WEIGHT_INFLATION = 0.25;
    private static final double WEIGHT_MONEY_GROWTH = 0.2;
    private static final double WEIGHT_ACTIVITY = 0.15;
    private static final double WEIGHT_LIQUIDITY = 0.15;
    private final AnalyticsEngine engine;

    public EconomyHealthScore(AnalyticsEngine engine) {
        this.engine = engine;
    }

    public HealthReport compute() {
        HealthReport report = new HealthReport();
        report.timestamp = System.currentTimeMillis();
        AnalyticsDatabase db = this.engine.getDatabase();
        AnalyticsDatabase.Snapshot latest = db.getLatestSnapshot();
        if (latest == null) {
            report.overallScore = 50.0;
            report.summary = "Insufficient data \u2014 no snapshots available yet";
            report.giniScore = 50.0;
            report.inflationScore = 50.0;
            report.moneyGrowthScore = 50.0;
            report.activityScore = 50.0;
            report.liquidityScore = 50.0;
            return report;
        }
        report.giniScore = this.computeGiniScore(latest.giniCoefficient());
        InflationCalculator.InflationReport inflationReport = this.engine.getInflationCalculator().getCachedOrCalculate();
        report.inflationScore = this.computeInflationScore(inflationReport);
        report.moneyGrowthScore = this.computeMoneyGrowthScore(latest, db);
        report.activityScore = this.computeActivityScore();
        report.liquidityScore = this.computeLiquidityScore(latest);
        report.overallScore = Math.max(0.0, Math.min(100.0, report.giniScore * 0.25 + report.inflationScore * 0.25 + report.moneyGrowthScore * 0.2 + report.activityScore * 0.15 + report.liquidityScore * 0.15));
        report.summary = this.interpretScore(report.overallScore);
        return report;
    }

    private double computeGiniScore(double gini) {
        if (gini < 0.0 || gini > 1.0) {
            return 50.0;
        }
        if (gini <= 0.1) {
            return 40.0;
        }
        if (gini <= 0.2) {
            return 70.0;
        }
        if (gini <= 0.3) {
            return 95.0;
        }
        if (gini <= 0.4) {
            return 85.0;
        }
        if (gini <= 0.5) {
            return 65.0;
        }
        if (gini <= 0.6) {
            return 45.0;
        }
        if (gini <= 0.7) {
            return 30.0;
        }
        return 15.0;
    }

    private double computeInflationScore(InflationCalculator.InflationReport report) {
        if (report == null || report.inflationRate24h == null) {
            return 50.0;
        }
        double rate = report.inflationRate24h;
        if (rate < -5.0) {
            return 20.0;
        }
        if (rate < -2.0) {
            return 40.0;
        }
        if (rate < 0.0) {
            return 60.0;
        }
        if (rate < 2.0) {
            return 80.0;
        }
        if (rate < 5.0) {
            return 95.0;
        }
        if (rate < 10.0) {
            return 60.0;
        }
        if (rate < 20.0) {
            return 35.0;
        }
        if (rate < 50.0) {
            return 15.0;
        }
        return 5.0;
    }

    private double computeMoneyGrowthScore(AnalyticsDatabase.Snapshot latest, AnalyticsDatabase db) {
        long sevenDaysAgo = latest.timestamp() - 604800000L;
        AnalyticsDatabase.Snapshot weekAgo = db.getSnapshotBefore(sevenDaysAgo);
        if (weekAgo == null || weekAgo.totalWealth() == 0L) {
            return 50.0;
        }
        double growthRate = (double)(latest.totalWealth() - weekAgo.totalWealth()) / (double)weekAgo.totalWealth() * 100.0;
        if (growthRate < -10.0) {
            return 15.0;
        }
        if (growthRate < -5.0) {
            return 35.0;
        }
        if (growthRate < 0.0) {
            return 55.0;
        }
        if (growthRate < 5.0) {
            return 75.0;
        }
        if (growthRate < 15.0) {
            return 95.0;
        }
        if (growthRate < 25.0) {
            return 70.0;
        }
        if (growthRate < 50.0) {
            return 40.0;
        }
        return 20.0;
    }

    private double computeActivityScore() {
        long dailyTx = this.engine.getLiveMetrics().getDailyTransactionCount();
        int activePlayers = this.engine.getLiveMetrics().getActivePlayerCount();
        if (activePlayers == 0) {
            return 30.0;
        }
        double txPerPlayer = (double)dailyTx / (double)activePlayers;
        if (txPerPlayer < 1.0) {
            return 35.0;
        }
        if (txPerPlayer < 3.0) {
            return 55.0;
        }
        if (txPerPlayer < 5.0) {
            return 75.0;
        }
        if (txPerPlayer < 10.0) {
            return 90.0;
        }
        if (txPerPlayer < 20.0) {
            return 95.0;
        }
        return 70.0;
    }

    private double computeLiquidityScore(AnalyticsDatabase.Snapshot latest) {
        int playerCount = latest.playerCount();
        if (playerCount == 0) {
            return 30.0;
        }
        int auctionListings = latest.auctionActiveListings();
        double listingsPerPlayer = (double)auctionListings / (double)playerCount;
        if (listingsPerPlayer < 0.1) {
            return 35.0;
        }
        if (listingsPerPlayer < 0.5) {
            return 55.0;
        }
        if (listingsPerPlayer < 1.0) {
            return 75.0;
        }
        if (listingsPerPlayer < 2.0) {
            return 90.0;
        }
        if (listingsPerPlayer < 5.0) {
            return 95.0;
        }
        return 80.0;
    }

    private String interpretScore(double score) {
        if (score >= 90.0) {
            return "Excellent \u2014 Economy is thriving with healthy balance";
        }
        if (score >= 80.0) {
            return "Good \u2014 Economy is stable with minor concerns";
        }
        if (score >= 70.0) {
            return "Above Average \u2014 Generally healthy, some areas to watch";
        }
        if (score >= 60.0) {
            return "Fair \u2014 Moderate imbalances detected";
        }
        if (score >= 50.0) {
            return "Average \u2014 Mixed signals, consider monitoring";
        }
        if (score >= 40.0) {
            return "Below Average \u2014 Several economic concerns";
        }
        if (score >= 30.0) {
            return "Poor \u2014 Significant economic problems detected";
        }
        if (score >= 20.0) {
            return "Critical \u2014 Economy is in distress";
        }
        return "Emergency \u2014 Immediate intervention needed";
    }

    public static String getScoreColor(double score) {
        if (score >= 80.0) {
            return "GREEN";
        }
        if (score >= 60.0) {
            return "YELLOW";
        }
        if (score >= 40.0) {
            return "GOLD";
        }
        if (score >= 20.0) {
            return "RED";
        }
        return "DARK_RED";
    }

    public static class HealthReport {
        public long timestamp;
        public double overallScore;
        public double giniScore;
        public double inflationScore;
        public double moneyGrowthScore;
        public double activityScore;
        public double liquidityScore;
        public String summary;

        public String getGrade() {
            if (this.overallScore >= 90.0) {
                return "A+";
            }
            if (this.overallScore >= 80.0) {
                return "A";
            }
            if (this.overallScore >= 70.0) {
                return "B+";
            }
            if (this.overallScore >= 60.0) {
                return "B";
            }
            if (this.overallScore >= 50.0) {
                return "C+";
            }
            if (this.overallScore >= 40.0) {
                return "C";
            }
            if (this.overallScore >= 30.0) {
                return "D";
            }
            return "F";
        }

        public String formatReport() {
            return String.format("Economy Health Score: %.1f/100 (%s)\nSummary: %s\n\nFactor Breakdown:\n  Gini Inequality:    %.0f/100 (weight: 25%%)\n  Inflation Rate:     %.0f/100 (weight: 25%%)\n  Money Growth:       %.0f/100 (weight: 20%%)\n  Activity Level:     %.0f/100 (weight: 15%%)\n  Market Liquidity:   %.0f/100 (weight: 15%%)\n", this.overallScore, this.getGrade(), this.summary, this.giniScore, this.inflationScore, this.moneyGrowthScore, this.activityScore, this.liquidityScore);
        }
    }
}
