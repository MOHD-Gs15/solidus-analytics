package com.solidus.analytics.engine;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.CoreLedgerAccess;
import com.solidus.analytics.storage.DirectDb;
import com.solidus.analytics.storage.AnalyticsDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LiveMetricsTracker {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30000L;
    /**
     * Upper bound per poll cycle. A busy network server cannot produce more
     * rows between two 30s polls than this; if it ever does, the remainder
     * is picked up on the next cycle (the cursor advances to the last
     * applied row, so nothing is lost or double-counted).
     */
    private static final int POLL_ROW_CAP = 20000;
    private volatile long pollIntervalMs = 30000L;
    // SECURITY/CORRECTNESS FIX: cursor moved from timestamp to autoincrement id.
    // The old "WHERE timestamp > ?" cursor permanently skipped every transaction
    // written in the same millisecond as the cursor advanced while a poll was
    // running - bursts within one ms are common, so daily metrics silently lost
    // rows forever. transaction_log.id is INTEGER PRIMARY KEY AUTOINCREMENT
    // (SQLite) / BIGINT AUTO_INCREMENT (MySQL), making id-based incremental
    // polling exact on BOTH backends.
    private final AtomicLong lastPolledId = new AtomicLong(0L);
    // EMPTY-LOG FIX: distinguishes "cursor not seeded yet" from "cursor is
    // legitimately 0 because the transaction log was EMPTY when seeded".
    // The old code treated a 0 cursor as "nothing to poll" and returned early
    // on every cycle, so a server started against an empty transaction_log
    // permanently stopped recording metrics until the mod was restarted.
    private volatile boolean cursorInitialized = false;
    private volatile String currentDate = LocalDate.now(ZoneOffset.UTC).toString();
    private final AtomicLong dailyVolumeCents = new AtomicLong(0L);
    private final AtomicLong dailyTransactionCount = new AtomicLong(0L);
    private final ConcurrentHashMap<String, AtomicLong> transactionsByType = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, AtomicLong> topItemsBought = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, AtomicLong> topItemsSold = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, Boolean> activePlayers = new ConcurrentHashMap();
    private final AnalyticsDatabase analyticsDb;
    private final String economyDbPath;
    // DB_SCALING_PLAN §8.5 (2.1.3): when Core runs MySQL network mode the
    // SQLite economy.db file no longer exists/is stale, so the poller reads
    // the ledger through Core's own TransactionLog connection instead
    // (backend-agnostic, zero extra dependencies). Null = classic SQLite
    // direct-file mode.
    private volatile CoreLedgerAccess ledgerAccess;
    private volatile boolean running = false;
    // LIFECYCLE FIX (2.1.3): start() used check-then-act on a volatile flag;
    // two overlapping callers could both pass the guard and submit TWO
    // polling loops feeding the SAME counters - every transaction counted
    // N times ("insane chart inflation"). All start/stop transitions are now
    // serialized, and the loop runs on its OWN dedicated thread.
    private final Object lifecycleLock = new Object();
    private volatile Thread pollThread;

    public LiveMetricsTracker(AnalyticsDatabase analyticsDb, String economyDbPath) {
        this.analyticsDb = analyticsDb;
        this.economyDbPath = economyDbPath;
    }

    /** Wires the Core-connection ledger path (MySQL network mode). Nullable. */
    public void setLedgerAccess(CoreLedgerAccess ledgerAccess) {
        this.ledgerAccess = ledgerAccess;
    }

    public void start() {
        synchronized (this.lifecycleLock) {
            if (this.running) {
                return;
            }
            this.running = true;
            this.tryInitializeCursor();
            // EXECUTOR-STARVATION FIX (2.1.3): the polling loop used to be
            // submitted to AnalyticsDatabase's SINGLE-THREAD worker - the
            // same executor that must run snapshot inserts, daily-metrics
            // upserts, dashboard publishes and cleanup. An infinite loop
            // permanently occupied that thread, so every queued task waited
            // forever (unbounded queue growth = genuine memory leak) and
            // nothing was ever persisted. The loop now owns a dedicated
            // daemon thread and the worker executor stays free.
            Thread thread = new Thread(this::pollingLoop, "Solidus-Analytics-LivePoller");
            thread.setDaemon(true);
            this.pollThread = thread;
            thread.start();
            SolidusAnalyticsMod.LOGGER.info("LiveMetricsTracker started. Polling interval: {}ms, ledger path: {}",
                new Object[]{this.pollIntervalMs, this.ledgerAccess != null ? "Core connection (backend-agnostic)" : "SQLite economy.db"});
        }
    }

    public void setPollingIntervalSeconds(int seconds) {
        seconds = Math.max(5, seconds);
        this.pollIntervalMs = (long)seconds * 1000L;
        SolidusAnalyticsMod.LOGGER.info("Polling interval set to {} seconds ({}ms)", (Object)seconds, (Object)this.pollIntervalMs);
    }

    public void stop() {
        synchronized (this.lifecycleLock) {
            this.running = false;
            Thread thread = this.pollThread;
            if (thread != null) {
                thread.interrupt();
                try {
                    // Guarantees no dying poller can touch counters/persist
                    // after stop() returns (the old fire-and-forget loop
                    // could run one more cycle concurrently with shutdown).
                    thread.join(5000L);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                this.pollThread = null;
            }
        }
        try {
            this.forcePersist();
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to persist live metrics on shutdown", (Throwable)e);
        }
        SolidusAnalyticsMod.LOGGER.info("LiveMetricsTracker stopped. Final metrics persisted.");
    }

    public double getDailyVolume() {
        return (double)this.dailyVolumeCents.get() / 100.0;
    }

    public long getDailyVolumeCents() {
        return this.dailyVolumeCents.get();
    }

    public long getDailyTransactionCount() {
        return this.dailyTransactionCount.get();
    }

    public Map<String, Long> getTransactionsByType() {
        HashMap<String, Long> result = new HashMap<String, Long>();
        this.transactionsByType.forEach((type, count) -> result.put((String)type, count.get()));
        return result;
    }

    public Map<String, Long> getTopBoughtItems(int limit) {
        return this.getTopEntries(this.topItemsBought, limit);
    }

    public Map<String, Long> getTopSoldItems(int limit) {
        return this.getTopEntries(this.topItemsSold, limit);
    }

    public int getActivePlayerCount() {
        return this.activePlayers.size();
    }

    private void pollingLoop() {
        while (this.running) {
            try {
                this.pollNewTransactions();
                this.checkDailyReset();
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Error during transaction poll", (Throwable)e);
            }
            try {
                Thread.sleep(this.pollIntervalMs);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // package-private: exercised directly by unit tests
    void pollNewTransactions() {
        // Cursor seeding retries on every cycle until the ledger is
        // readable; once seeded, a 0 cursor is a VALID position meaning
        // "log was empty at seed time" - every future row (id >= 1) is new.
        if (!this.cursorInitialized && !this.tryInitializeCursor()) {
            return; // ledger not readable yet (schema missing) - retry next cycle
        }
        long since = this.lastPolledId.get();
        // PHASE 1 - buffer the full batch BEFORE any counter is touched.
        // The old single-pass loop applied counters while iterating and
        // advanced the cursor only afterwards; an SQLException mid-iteration
        // left rows counted with the cursor stuck, so the next cycle
        // re-counted them. A failed read now applies nothing.
        List<CoreLedgerAccess.LedgerRow> batch;
        try {
            batch = this.readNewRows(since);
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to poll transactions from the ledger", (Throwable)e);
            return;
        }
        if (batch.isEmpty()) {
            return;
        }
        // PHASE 2 - apply counters, then advance the cursor past the batch.
        long maxId = since;
        for (CoreLedgerAccess.LedgerRow row : batch) {
            this.applyRow(row);
            maxId = Math.max(maxId, row.id());
        }
        this.lastPolledId.set(maxId);
        SolidusAnalyticsMod.LOGGER.debug("Processed {} new transactions. Daily total: {} tx, S${}", new Object[]{batch.size(), this.dailyTransactionCount.get(), String.format("%,.2f", (double)this.dailyVolumeCents.get() / 100.0)});
    }

    private List<CoreLedgerAccess.LedgerRow> readNewRows(long since) throws SQLException {
        if (this.ledgerAccess != null) {
            return this.ledgerAccess.pollSinceId(since, POLL_ROW_CAP);
        }
        String sql = "SELECT id, type, player_uuid, target_uuid, amount, item_material, item_quantity "
            + "FROM transaction_log WHERE id > ? ORDER BY id ASC LIMIT " + POLL_ROW_CAP;
        List<CoreLedgerAccess.LedgerRow> rows = new ArrayList<>();
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CoreLedgerAccess.LedgerRow(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("player_uuid"),
                        rs.getString("target_uuid"),
                        // UNIT FIX: Solidus Core stores monetary amounts as DECIMAL S$
                        // units (REAL in SQLite / DECIMAL(18,2) in MySQL, e.g. 500.0 =
                        // 500 S$), NOT cents. This module's contract treats every
                        // internal figure as cents and divides by 100 at display time;
                        // without this explicit conversion ALL money metrics were
                        // reported 100x too small.
                        Math.round(rs.getDouble("amount") * 100.0),
                        rs.getString("item_material"),
                        rs.getInt("item_quantity")));
                }
            }
        }
        return rows;
    }

    private void applyRow(CoreLedgerAccess.LedgerRow row) {
        if (countsTowardVolume(row.type(), row.targetUuid())) {
            this.dailyVolumeCents.addAndGet(Math.abs(row.amountCents()));
        }
        this.dailyTransactionCount.incrementAndGet();
        this.transactionsByType.computeIfAbsent(row.type(), k -> new AtomicLong(0L)).incrementAndGet();
        if (row.itemMaterial() != null && row.itemQuantity() > 0) {
            if ("SHOP_BUY".equals(row.type()) || "AUCTION_BOUGHT".equals(row.type())) {
                this.topItemsBought.computeIfAbsent(row.itemMaterial(), k -> new AtomicLong(0L)).addAndGet(row.itemQuantity());
            } else if ("SHOP_SELL".equals(row.type())) {
                this.topItemsSold.computeIfAbsent(row.itemMaterial(), k -> new AtomicLong(0L)).addAndGet(row.itemQuantity());
            }
        }
        if (row.playerUuid() != null) {
            this.activePlayers.put(row.playerUuid(), Boolean.TRUE);
        }
    }

    /**
     * VOLUME ACCOUNTING (2.1.3 - the "insane chart inflation" fix).
     *
     * <p>Volume must count money that actually moved between accounts,
     * exactly once. Core's ledger writes PARTICIPANT ROWS and ESCROW
     * CHURN rows, and pre-bidding Analytics counted them all:</p>
     * <ul>
     *   <li>{@code PAY_RECEIVE} / {@code TRADE_RECEIVE} / {@code AUCTION_SOLD} /
     *       {@code DEATH_REWARD} - receiver-side mirrors of one movement already
     *       counted from the initiator's row.</li>
     *   <li>{@code BID_PLACED} - escrow-IN (bidder to holding): the money never
     *       left the bidder's own economic position, and outbid placements are
     *       refunded via {@code BID_REFUNDED}. A 50-bid auction war counted
     *       50 placements + 49 refunds as "volume".</li>
     *   <li>{@code BID_REFUNDED} - escrow-OUT (holding to bidder).</li>
     *   <li>{@code AUCTION_WON} - TWO distinct core rows share this type:
     *       (a) the bid settlement (escrow released to seller, target =
     *       seller) - the ONE real movement of a bid-settled sale, counted;
     *       (b) the {@code /ah collect} item-delivery note (target = null,
     *       carrying the summed win prices even though that money already
     *       moved at settlement) - NOT volume.</li>
     * </ul>
     *
     * <p>Worked example, one 1200 S$ auction won after 2 bids: old volume =
     * 1000(BID_PLACED) + 1000(BID_REFUNDED) + 1200(BID_PLACED) + 1200(SOLD)
     * + 1200(WON settle) + 1200(WON collect) = 6800 S$. Correct volume =
     * 1200 S$. Instant-buy path keeps its existing exact-once behavior
     * ({@code AUCTION_BOUGHT} counted, {@code AUCTION_SOLD} mirror excluded).</p>
     */
    static boolean countsTowardVolume(String type, String targetUuid) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case "PAY_RECEIVE":
            case "TRADE_RECEIVE":
            case "AUCTION_SOLD":
            case "DEATH_REWARD":
            case "BID_PLACED":
            case "BID_REFUNDED":
                return false;
            case "AUCTION_WON":
                return targetUuid != null && !targetUuid.isBlank();
            default:
                return true;
        }
    }

    private void checkDailyReset() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        if (!today.equals(this.currentDate)) {
            SolidusAnalyticsMod.LOGGER.info("Date changed from {} to {}. Persisting daily metrics and resetting counters.", (Object)this.currentDate, (Object)today);
            this.persistDailyMetrics(this.currentDate);
            this.dailyVolumeCents.set(0L);
            this.dailyTransactionCount.set(0L);
            this.transactionsByType.clear();
            this.topItemsBought.clear();
            this.topItemsSold.clear();
            this.activePlayers.clear();
            this.currentDate = today;
        }
    }

    private void persistDailyMetrics(String date) {
        Map<String, Long> typeCounts = this.getTransactionsByType();
        int shopBuyCount = typeCounts.getOrDefault("SHOP_BUY", 0L).intValue();
        int shopSellCount = typeCounts.getOrDefault("SHOP_SELL", 0L).intValue();
        int auctionCount = (int)(typeCounts.getOrDefault("AUCTION_LIST", 0L) + typeCounts.getOrDefault("AUCTION_SOLD", 0L) + typeCounts.getOrDefault("AUCTION_BOUGHT", 0L) + typeCounts.getOrDefault("AUCTION_EXPIRED", 0L));
        int payTransferCount = typeCounts.getOrDefault("PAY_SEND", 0L).intValue();
        String topBought = this.getTopBoughtItems(1).entrySet().stream().findFirst().map(Map.Entry::getKey).orElse(null);
        String topSold = this.getTopSoldItems(1).entrySet().stream().findFirst().map(Map.Entry::getKey).orElse(null);
        Double inflationRate = this.calculateInflationRate();
        AnalyticsDatabase.DailyMetrics metrics = new AnalyticsDatabase.DailyMetrics(date, (int)this.dailyTransactionCount.get(), this.dailyVolumeCents.get(), shopBuyCount, shopSellCount, auctionCount, payTransferCount, 0, this.activePlayers.size(), inflationRate, topBought, topSold);
        this.analyticsDb.upsertDailyMetricsAsync(metrics);
        SolidusAnalyticsMod.LOGGER.info("Persisted daily metrics for date: {}", (Object)date);
    }

    private Double calculateInflationRate() {
        AnalyticsDatabase.Snapshot latest = this.analyticsDb.getLatestSnapshot();
        if (latest == null) {
            return null;
        }
        long twentyFourHoursAgo = latest.timestamp() - 86400000L;
        AnalyticsDatabase.Snapshot previous = this.analyticsDb.getSnapshotBefore(twentyFourHoursAgo);
        if (previous == null || previous.totalWealth() == 0L) {
            return null;
        }
        return (double)(latest.totalWealth() - previous.totalWealth()) / (double)previous.totalWealth() * 100.0;
    }

    // Seeds the polling cursor from MAX(id). Returns false ONLY when the
    // ledger cannot be read yet (e.g. schema not created, Core not
    // initialized) so the polling loop retries instead of silently leaving
    // metrics dead forever. A successful seed with an empty log leaves the
    // cursor at 0, which pollNewTransactions now treats as a valid position.
    private boolean tryInitializeCursor() {
        if (this.ledgerAccess != null) {
            try {
                long maxId = this.ledgerAccess.seedMaxId();
                this.lastPolledId.set(maxId);
                SolidusAnalyticsMod.LOGGER.info("Last known transaction row id (via Core connection): {}", (Object)maxId);
            }
            catch (SQLException e) {
                SolidusAnalyticsMod.LOGGER.warn("Core ledger not ready for cursor seed; will retry on next poll", (Throwable)e);
                return false;
            }
            this.cursorInitialized = true;
            return true;
        }
        String sql = "SELECT MAX(id) as max_id FROM transaction_log";
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath)){
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                if (rs.next()) {
                    long maxId = rs.getLong("max_id");
                    if (!rs.wasNull()) {
                        this.lastPolledId.set(maxId);
                        SolidusAnalyticsMod.LOGGER.info("Last known transaction row id: {}", (Object)maxId);
                    } else {
                        this.lastPolledId.set(0L);
                        SolidusAnalyticsMod.LOGGER.info("Transaction log empty. Cursor starts at 0; new transactions will be picked up.");
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.warn("Economy db not ready for cursor seed; will retry on next poll", (Throwable)e);
            return false;
        }
        this.cursorInitialized = true;
        return true;
    }

    private Map<String, Long> getTopEntries(ConcurrentHashMap<String, AtomicLong> map, int limit) {
        HashMap<String, Long> result = new HashMap<String, Long>();
        map.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, AtomicLong> e) -> e.getValue().get()).reversed()).limit(limit).forEach(e -> result.put(e.getKey(), e.getValue().get()));
        return result;
    }

    public void forcePersist() {
        this.persistDailyMetrics(this.currentDate);
    }
}
