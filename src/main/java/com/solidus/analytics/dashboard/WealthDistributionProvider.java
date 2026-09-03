package com.solidus.analytics.dashboard;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.DirectDb;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only wealth-distribution view over the Core economy database.
 *
 * <p>Feeds the dashboard's wealth-distribution donut and richest-players
 * table. Follows the established read-only convention: every connection is
 * opened through {@link DirectDb#openReadOnly} (PRAGMA query_only), so this
 * class can never mutate Core data even if a SQL statement were changed by
 * mistake.</p>
 *
 * <p>Cost model: one indexed full scan of {@code player_balances} ordered by
 * balance DESC - the same shape {@code SnapshotScheduler} already performs,
 * measured at 0.013ms @10K players / ~3ms @1M. The result is cached with a
 * TTL slightly under the dashboard publish interval, and {@link #get()} is
 * only ever called from the database executor thread inside
 * {@code DashboardManager.publishData()}, so the request path never touches
 * SQLite. Fail-open: any error keeps the last good cache (up to a hard
 * staleness limit) instead of breaking the whole payload.</p>
 *
 * <p>UNIT CONVENTION (same fix as SnapshotScheduler): economy.db stores
 * DECIMAL S$ units, not cents - converted on read so every consumer can
 * keep dividing by 100 for display.</p>
 */
public final class WealthDistributionProvider {
    public record TopPlayer(int rank, String name, long balanceCents, double share) {
    }

    public record WealthDistribution(long computedAt, long totalWealthCents, int playerCount,
            double top1Share, double top10Share, List<TopPlayer> topPlayers) {
    }

    static final int TOP_PLAYERS_LIMIT = 10;
    private static final long STALE_DROP_MS = 300_000L;

    private final String economyDbPath;
    private final long refreshIntervalMs;
    private volatile WealthDistribution cached;

    public WealthDistributionProvider(String economyDbPath) {
        this(economyDbPath, 55_000L);
    }

    WealthDistributionProvider(String economyDbPath, long refreshIntervalMs) {
        this.economyDbPath = economyDbPath;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /**
     * Returns the cached distribution, refreshing it first when older than
     * the TTL. Never throws: on failure the last good value is kept (and
     * dropped once it is unreasonably stale).
     */
    public WealthDistribution get() {
        long now = System.currentTimeMillis();
        WealthDistribution current = this.cached;
        if (current != null && now - current.computedAt() < this.refreshIntervalMs) {
            return current;
        }
        try {
            this.cached = this.compute(now);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to refresh wealth distribution (keeping last good cache): {}",
                (Object)String.valueOf(e.getMessage()));
            if (current != null && now - current.computedAt() > WealthDistributionProvider.STALE_DROP_MS) {
                this.cached = null;
            }
        }
        return this.cached;
    }

    // package-private: exercised directly by unit tests
    WealthDistribution compute(long now) throws SQLException {
        ArrayList<Long> balances = new ArrayList<Long>();
        ArrayList<String> topNames = new ArrayList<String>();
        ArrayList<Long> topBalances = new ArrayList<Long>();
        long totalWealth = 0L;
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath)) {
            String sql = "SELECT player_name, balance FROM player_balances ORDER BY balance DESC";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    long cents = Math.round(rs.getDouble("balance") * 100.0);
                    balances.add(cents);
                    totalWealth += cents;
                    if (topNames.size() < WealthDistributionProvider.TOP_PLAYERS_LIMIT) {
                        String name = rs.getString("player_name");
                        topNames.add(name == null || name.isBlank() ? "unknown" : name);
                        topBalances.add(cents);
                    }
                }
            }
        }
        if (balances.isEmpty()) {
            return null;
        }
        int playerCount = balances.size();
        // Percentile membership needs the final count, so the shares are
        // prefix sums over the DESC-sorted balances collected above.
        int top1Count = Math.max(1, (int)Math.ceil((double)playerCount * 0.01));
        int top10Count = Math.max(1, (int)Math.ceil((double)playerCount * 0.10));
        long top1Wealth = 0L;
        long top10Wealth = 0L;
        for (int i = 0; i < top10Count && i < playerCount; ++i) {
            long b = balances.get(i);
            top10Wealth += b;
            if (i < top1Count) {
                top1Wealth += b;
            }
        }
        double top1Share = totalWealth > 0L ? (double)top1Wealth / (double)totalWealth : 0.0;
        double top10Share = totalWealth > 0L ? (double)top10Wealth / (double)totalWealth : 0.0;
        ArrayList<TopPlayer> players = new ArrayList<TopPlayer>(topNames.size());
        for (int i = 0; i < topNames.size(); ++i) {
            long cents = topBalances.get(i);
            players.add(new TopPlayer(i + 1, topNames.get(i), cents,
                totalWealth > 0L ? (double)cents / (double)totalWealth : 0.0));
        }
        return new WealthDistribution(now, totalWealth, playerCount, top1Share, top10Share,
            List.copyOf(players));
    }
}
