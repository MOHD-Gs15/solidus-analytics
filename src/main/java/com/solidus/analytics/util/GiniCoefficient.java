package com.solidus.analytics.util;

import java.util.Arrays;

public final class GiniCoefficient {
    private GiniCoefficient() {
    }

    public static double calculate(long[] balances) {
        if (balances == null || balances.length <= 1) {
            return 0.0;
        }
        long[] sorted = new long[balances.length];
        System.arraycopy(balances, 0, sorted, 0, balances.length);
        Arrays.sort(sorted);
        long sum = 0L;
        for (long b : sorted) {
            sum += b;
        }
        if (sum == 0L) {
            return 0.0;
        }
        long sumOfAbsoluteDifferences = 0L;
        for (int i = 0; i < sorted.length; ++i) {
            for (int j = 0; j < sorted.length; ++j) {
                sumOfAbsoluteDifferences += Math.abs(sorted[i] - sorted[j]);
            }
        }
        double n = sorted.length;
        return (double)sumOfAbsoluteDifferences / (2.0 * n * (double)sum);
    }

    public static double calculateOptimized(long[] balances) {
        if (balances == null || balances.length <= 1) {
            return 0.0;
        }
        long[] sorted = new long[balances.length];
        System.arraycopy(balances, 0, sorted, 0, balances.length);
        Arrays.sort(sorted);
        long sum = 0L;
        for (long b : sorted) {
            sum += b;
        }
        if (sum == 0L) {
            return 0.0;
        }
        long weightedSum = 0L;
        for (int i = 0; i < sorted.length; ++i) {
            weightedSum += (long)(i + 1) * sorted[i];
        }
        double n = sorted.length;
        double gini = 2.0 * (double)weightedSum / (n * (double)sum) - (n + 1.0) / n;
        return Math.max(0.0, Math.min(1.0, gini));
    }

    public static String interpret(double gini) {
        if (gini < 0.0) {
            return "Invalid";
        }
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

    public static String format(double gini) {
        return String.format("%.4f (%s)", gini, GiniCoefficient.interpret(gini));
    }
}
