package com.solidus.analytics.integration;

import com.solidus.api.BalanceEntry;
import com.solidus.api.EconomyStats;
import com.solidus.api.SolidusApi;
import com.solidus.api.SolidusApiAccess;
import com.solidus.api.SolidusTransactionHook;
import com.solidus.api.TransactionRecord;
import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.DirectDb;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;

/**
 * SolidusIntegration — direct bridge to Solidus Core through the
 * <b>solidus-api</b> contract (family 2.3.2, audit W-5 closure).
 *
 * <p><b>What changed from 2.3.0:</b> this class used to cache ~15 reflective
 * {@code Method} handles against Core's legacy facade AND reach into Core
 * internals — {@code EconomyEngine.isMysqlMode()},
 * {@code EconomyEngine.getStorage()}, {@code SQLiteStorage.getCachedPlayerCount()},
 * and a hand-built {@code Proxy} over {@code TransactionLog$SqlWork} — none
 * of which the contract guaranteed. A Core refactor renaming any of those
 * would have flipped Analytics to DB-only mode silently.</p>
 *
 * <p>Now every call is a plain, compile-checked invocation against the
 * versioned contract jar ({@code libs/solidus-api-2.3.2.jar}) — including
 * the 2.3.2 members that made the last three internal reach-ins
 * contract-grade: {@link SolidusApi#isMysqlMode()} (the backend signal),
 * {@link SolidusApi#withLedgerConnection} (the ledger read seam) and
 * {@link SolidusApi#getEconomyStats()} (the cached player count).
 * {@code fabric.mod.json} declares {@code "solidus": ">=2.3.2 <3.0.0"} so
 * Fabric's loader rejects incompatible combinations before either mod
 * runs.</p>
 *
 * <p>The public surface of this class (method names and signatures) is
 * unchanged from the reflective build, so the rest of Analytics needs no
 * changes — except the return types, which are now the typed contract
 * records instead of {@code Object} / raw lists.</p>
 */
public final class SolidusIntegration {
    private static volatile SolidusIntegration instance;
    private final SolidusApi api;

    private SolidusIntegration(SolidusApi api) {
        this.api = api;
    }

    public static synchronized boolean initialize() {
        if (instance != null) {
            SolidusAnalyticsMod.LOGGER.warn("SolidusIntegration already initialized.");
            return true;
        }
        if (!FabricLoader.getInstance().isModLoaded("solidus")) {
            SolidusAnalyticsMod.LOGGER.warn("Solidus is NOT loaded. Solidus Analytics will operate in standalone mode (reads databases directly, no API access).");
            return false;
        }
        try {
            SolidusApi api = SolidusApiAccess.get();
            if (api == null) {
                SolidusAnalyticsMod.LOGGER.warn("Solidus is loaded but the solidus-api contract is not yet installed. Solidus may not be fully initialized yet.");
                return false;
            }
            instance = new SolidusIntegration(api);
            SolidusAnalyticsMod.LOGGER.info("SolidusIntegration initialized successfully. Connected to Solidus Core {} through the solidus-api contract.", api.getCoreVersion());
            return true;
        }
        catch (Throwable e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to initialize SolidusIntegration. Analytics will use DB-only mode.", e);
            return false;
        }
    }

    public static boolean isAvailable() {
        return instance != null && instance.api != null;
    }

    public static SolidusIntegration getInstance() {
        return instance;
    }

    /**
     * The raw contract instance (null when Core is absent or still
     * initializing). Exposed for the engine wiring (CoreLedgerAccess) and
     * the veto hook registration — both of which are contract-typed since
     * 2.3.2.
     */
    public static SolidusApi getApi() {
        SolidusIntegration integration = instance;
        return integration != null ? integration.api : null;
    }

    // ---- read paths -----------------------------------------------------

    /** econ.top — top balances, typed through the contract. */
    public CompletableFuture<List<BalanceEntry>> getTopBalances(int limit) {
        if (!SolidusIntegration.isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }
        return this.api.getTopBalances(limit);
    }

    /** Player transaction history, typed through the contract. */
    public CompletableFuture<List<TransactionRecord>> getTransactions(UUID playerUuid, int limit) {
        if (!SolidusIntegration.isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }
        return this.api.getTransactions(playerUuid, limit);
    }

    /**
     * Cached player count through Core's one-query aggregate — works in
     * BOTH storage modes now (the old reflective SQLiteStorage path was
     * dead weight in MySQL mode). Falls back to the direct DB read when
     * Core is unavailable, exactly like before.
     */
    public int getCachedPlayerCount() {
        if (SolidusIntegration.isAvailable()) {
            try {
                EconomyStats stats = this.api.getEconomyStats()
                    .get(API_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (stats != null) {
                    return stats.playerCount();
                }
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.debug("getEconomyStats failed, trying DB fallback", e);
            }
        }
        return this.getPlayerCountFromDB();
    }

    /** Economy aggregates, typed through the contract (blocking, bounded). */
    public EconomyStats getEconomyStats(int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable()) {
            return null;
        }
        try {
            return this.api.getEconomyStats().get(timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] getEconomyStats failed", e);
            return null;
        }
    }

    /**
     * True when Core runs its MySQL/MariaDB network mode — the decisive
     * signal that the direct SQLite file readers are dead and every
     * collector must read through CoreLedgerAccess instead. Contract
     * member since Core 2.3.2 (was a reflective EconomyEngine internal).
     */
    public boolean isCoreMysqlMode() {
        if (!SolidusIntegration.isAvailable()) {
            return false;
        }
        try {
            return this.api.isMysqlMode();
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("isMysqlMode failed - assuming SQLite mode", e);
            return false;
        }
    }

    // ---- Cloud Agent write bridge (PROTOCOL.md "API" path) ------------

    /**
     * Grants balance to a (possibly offline) player through Core's own
     * balance pipeline. Returns the new balance, or null on failure/timeout.
     */
    public Double addBalanceOffline(UUID uuid, String playerName, double amount, int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable()) {
            return null;
        }
        try {
            return this.api.addBalance(uuid, playerName, amount)
                .get(timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] addBalanceOffline failed", e);
            return null;
        }
    }

    /** Deducts balance offline-style; null on failure/timeout. */
    public Double subtractBalanceOffline(UUID uuid, String playerName, double amount, int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable()) {
            return null;
        }
        try {
            return this.api.subtractBalance(uuid, playerName, amount)
                .get(timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] subtractBalanceOffline failed", e);
            return null;
        }
    }

    /** Reads a player balance through Core (works offline). */
    public Double getBalanceOffline(UUID uuid, String playerName, int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable()) {
            return null;
        }
        try {
            return this.api.getBalance(uuid, playerName)
                .get(timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] getBalanceOffline failed", e);
            return null;
        }
    }

    /** Offline transfer between two players; reflects TransferResult.success().
     *  D-4 fix: an unreadable result is UNKNOWN (null), never success - the
     *  cloud router maps null to E_EXEC so money is never acked as delivered
     *  when the Core write outcome could not be confirmed. */
    public Boolean transferOffline(UUID fromUuid, String fromName, UUID toUuid, String toName,
                                   double amount, int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable()) {
            return null;
        }
        try {
            return this.api.transfer(fromUuid, fromName, toUuid, toName, amount)
                .get(timeoutSeconds, TimeUnit.SECONDS)
                .success();
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] transferOffline failed", e);
            return null;
        }
    }

    // ---- enforcement hook registration --------------------------------

    /** Registers the cloud veto hook (typed contract call since 2.3.2). */
    public boolean registerTransactionHook(SolidusTransactionHook hook) {
        if (!SolidusIntegration.isAvailable()) {
            return false;
        }
        try {
            return this.api.registerTransactionHook(hook);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] hook registration failed", e);
            return false;
        }
    }

    /** Removes a previously registered hook. */
    public boolean unregisterTransactionHook(SolidusTransactionHook hook) {
        if (!SolidusIntegration.isAvailable()) {
            return false;
        }
        try {
            return this.api.unregisterTransactionHook(hook);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] hook unregistration failed", e);
            return false;
        }
    }

    /** Bounded wait for read-path futures (kept from the reflective build). */
    private static final int API_QUERY_TIMEOUT_SECONDS = 5;

    private volatile String economyDbPath;

    public void setEconomyDbPath(String economyDbPath) {
        this.economyDbPath = economyDbPath;
    }

    private int getPlayerCountFromDB() {
        if (this.economyDbPath == null) {
            return -1;
        }
        String sql = "SELECT COUNT(*) as player_count FROM player_balances";
        try (Connection conn = DirectDb.openReadOnly(this.economyDbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) return -1;
            return rs.getInt("player_count");
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.debug("Failed to query player count from economy.db", e);
        }
        return -1;
    }
}
