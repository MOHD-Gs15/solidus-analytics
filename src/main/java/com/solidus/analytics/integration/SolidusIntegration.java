package com.solidus.analytics.integration;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.storage.DirectDb;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;

public final class SolidusIntegration {
    private static SolidusIntegration instance;
    private final Object apiInstance;
    private final Class<?> apiClass;
    private Method getTopBalancesMethod;
    private Method getTransactionLogMethod;
    private Method getTransactionsMethod;
    private Method getEconomyEngineMethod;
    private Method getStorageMethod;
    private Method getCachedPlayerCountMethod;
    private Method addBalanceOfflineMethod;
    private Method subtractBalanceOfflineMethod;
    private Method getBalanceOfflineMethod;
    private Method transferOfflineMethod;
    private Method getEconomyStatsMethod;
    private Method registerHookMethod;
    private Method unregisterHookMethod;
    private volatile String economyDbPath;

    private SolidusIntegration(Object apiInstance, Class<?> apiClass) {
        this.apiInstance = apiInstance;
        this.apiClass = apiClass;
        this.cacheMethodHandles();
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
            Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
            Method getInstanceMethod = apiClass.getMethod("getInstance", new Class[0]);
            Object apiInstance = getInstanceMethod.invoke(null, new Object[0]);
            if (apiInstance == null) {
                SolidusAnalyticsMod.LOGGER.warn("Solidus is loaded but SolidusAPI.getInstance() returned null. Solidus may not be fully initialized yet.");
                return false;
            }
            instance = new SolidusIntegration(apiInstance, apiClass);
            SolidusAnalyticsMod.LOGGER.info("SolidusIntegration initialized successfully. Connected to Solidus API.");
            return true;
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to initialize SolidusIntegration. Analytics will use DB-only mode.", (Throwable)e);
            return false;
        }
    }

    public static boolean isAvailable() {
        return instance != null && SolidusIntegration.instance.apiInstance != null;
    }

    public static SolidusIntegration getInstance() {
        return instance;
    }

    public CompletableFuture<List<?>> getTopBalances(int limit) {
        if (!SolidusIntegration.isAvailable() || this.getTopBalancesMethod == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return (CompletableFuture)this.getTopBalancesMethod.invoke(this.apiInstance, limit);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to call getTopBalances via reflection", (Throwable)e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public Object getTransactionLog() {
        if (!SolidusIntegration.isAvailable()) {
            return null;
        }
        try {
            return this.getTransactionLogMethod.invoke(this.apiInstance, new Object[0]);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to call getTransactionLog via reflection", (Throwable)e);
            return null;
        }
    }

    public CompletableFuture<List<?>> getTransactions(UUID playerUuid, int limit) {
        if (!SolidusIntegration.isAvailable() || this.getTransactionsMethod == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Object transactionLog = this.getTransactionLog();
            if (transactionLog == null) {
                return CompletableFuture.completedFuture(null);
            }
            return (CompletableFuture)this.getTransactionsMethod.invoke(transactionLog, playerUuid, limit);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to call getTransactions via reflection", (Throwable)e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public int getCachedPlayerCount() {
        if (SolidusIntegration.isAvailable() && this.getCachedPlayerCountMethod != null) {
            try {
                Object engine = this.getEconomyEngineMethod.invoke(this.apiInstance, new Object[0]);
                if (engine == null) {
                    return -1;
                }
                Object storage = this.getStorageMethod.invoke(engine, new Object[0]);
                if (storage == null) {
                    return -1;
                }
                return (Integer)this.getCachedPlayerCountMethod.invoke(storage, new Object[0]);
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.debug("Reflected getCachedPlayerCount failed, trying DB fallback", (Throwable)e);
            }
        }
        return this.getPlayerCountFromDB();
    }

    public void setEconomyDbPath(String economyDbPath) {
        this.economyDbPath = economyDbPath;
    }

    // ---- Cloud Agent write bridge (PROTOCOL.md "API" path) ------------

    /**
     * Grants balance to a (possibly offline) player through Core's own
     * balance pipeline. Returns the new balance, or null on failure/timeout.
     */
    public Double addBalanceOffline(UUID uuid, String playerName, double amount, int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable() || this.addBalanceOfflineMethod == null) {
            return null;
        }
        try {
            CompletableFuture<?> future = (CompletableFuture<?>)this.addBalanceOfflineMethod
                .invoke(this.apiInstance, uuid, playerName, amount);
            Object result = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return result instanceof Number n ? Double.valueOf(n.doubleValue()) : null;
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] addBalanceOffline failed", (Throwable)e);
            return null;
        }
    }

    /** Deducts balance offline-style; null on failure/timeout. */
    public Double subtractBalanceOffline(UUID uuid, String playerName, double amount, int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable() || this.subtractBalanceOfflineMethod == null) {
            return null;
        }
        try {
            CompletableFuture<?> future = (CompletableFuture<?>)this.subtractBalanceOfflineMethod
                .invoke(this.apiInstance, uuid, playerName, amount);
            Object result = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return result instanceof Number n ? Double.valueOf(n.doubleValue()) : null;
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] subtractBalanceOffline failed", (Throwable)e);
            return null;
        }
    }

    /** Reads a player balance through Core (works offline). */
    public Double getBalanceOffline(UUID uuid, String playerName, int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable() || this.getBalanceOfflineMethod == null) {
            return null;
        }
        try {
            CompletableFuture<?> future = (CompletableFuture<?>)this.getBalanceOfflineMethod
                .invoke(this.apiInstance, uuid, playerName);
            Object result = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return result instanceof Number n ? Double.valueOf(n.doubleValue()) : null;
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] getBalanceOffline failed", (Throwable)e);
            return null;
        }
    }

    /** Offline transfer between two players; reflects TransferResult.success(). */
    public Boolean transferOffline(UUID fromUuid, String fromName, UUID toUuid, String toName,
                                   double amount, int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable() || this.transferOfflineMethod == null) {
            return null;
        }
        try {
            CompletableFuture<?> future = (CompletableFuture<?>)this.transferOfflineMethod
                .invoke(this.apiInstance, fromUuid, fromName, toUuid, toName, amount);
            Object result = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (result == null) {
                return Boolean.FALSE;
            }
            try {
                Object success = result.getClass().getMethod("success").invoke(result);
                return success instanceof Boolean b ? b : Boolean.TRUE;
            }
            catch (Exception ignored) {
                return Boolean.TRUE;
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] transferOffline failed", (Throwable)e);
            return null;
        }
    }

    /** Core EconomyStats record (playerCount/avgBalance/totalSupply/gini), or null. */
    public Object getEconomyStats(int timeoutSeconds) {
        if (!SolidusIntegration.isAvailable() || this.getEconomyStatsMethod == null) {
            return null;
        }
        try {
            CompletableFuture<?> future = (CompletableFuture<?>)this.getEconomyStatsMethod.invoke(this.apiInstance, new Object[0]);
            return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] getEconomyStats failed", (Throwable)e);
            return null;
        }
    }

    /** Registers a reflection proxy as a SolidusTransactionHook (Cloud veto hook). */
    public boolean registerTransactionHook(Object proxy) {
        if (!SolidusIntegration.isAvailable() || this.registerHookMethod == null) {
            return false;
        }
        try {
            Object ok = this.registerHookMethod.invoke(this.apiInstance, proxy);
            return !(ok instanceof Boolean b) || b.booleanValue();
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] hook registration failed", (Throwable)e);
            return false;
        }
    }

    /** Removes a previously registered hook proxy. */
    public boolean unregisterTransactionHook(Object proxy) {
        if (!SolidusIntegration.isAvailable() || this.unregisterHookMethod == null) {
            return false;
        }
        try {
            Object ok = this.unregisterHookMethod.invoke(this.apiInstance, proxy);
            return !(ok instanceof Boolean b) || b.booleanValue();
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] hook unregistration failed", (Throwable)e);
            return false;
        }
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
            SolidusAnalyticsMod.LOGGER.debug("Failed to query player count from economy.db", (Throwable)e);
        }
        return -1;
    }

    private void cacheMethodHandles() {
        try {
            this.getTopBalancesMethod = this.apiClass.getMethod("getTopBalances", Integer.TYPE);
            this.getTransactionLogMethod = this.apiClass.getMethod("getTransactionLog", new Class[0]);
            this.getEconomyEngineMethod = this.apiClass.getMethod("getEconomyEngine", new Class[0]);
            Class<?> engineClass = Class.forName("com.solidus.economy.EconomyEngine");
            this.getStorageMethod = engineClass.getMethod("getStorage", new Class[0]);
            Class<?> storageClass = Class.forName("com.solidus.economy.SQLiteStorage");
            try {
                this.getCachedPlayerCountMethod = storageClass.getMethod("getCachedPlayerCount", new Class[0]);
            }
            catch (NoSuchMethodException e) {
                SolidusAnalyticsMod.LOGGER.info("SQLiteStorage.getCachedPlayerCount() not found in Solidus Core. Will use direct DB query fallback for player counts.");
            }
            Class<?> transactionLogClass = Class.forName("com.solidus.economy.TransactionLog");
            this.getTransactionsMethod = transactionLogClass.getMethod("getTransactions", UUID.class, Integer.TYPE);
            this.addBalanceOfflineMethod = this.apiClass.getMethod("addBalanceOffline", UUID.class, String.class, Double.TYPE);
            this.subtractBalanceOfflineMethod = this.apiClass.getMethod("subtractBalanceOffline", UUID.class, String.class, Double.TYPE);
            this.getBalanceOfflineMethod = this.apiClass.getMethod("getBalanceOffline", UUID.class, String.class);
            this.transferOfflineMethod = this.apiClass.getMethod("transferOffline",
                UUID.class, String.class, UUID.class, String.class, Double.TYPE);
            this.getEconomyStatsMethod = this.apiClass.getMethod("getEconomyStats", new Class[0]);
            try {
                Class<?> hookItf = Class.forName("com.solidus.api.SolidusTransactionHook");
                this.registerHookMethod = this.apiClass.getMethod("registerTransactionHook", hookItf);
                this.unregisterHookMethod = this.apiClass.getMethod("unregisterTransactionHook", hookItf);
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.info("SolidusTransactionHook not found in this Core build - veto hooks unavailable.");
            }
            SolidusAnalyticsMod.LOGGER.info("All Solidus API method handles cached successfully.");
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to cache Solidus API method handles. Some features may be unavailable.", (Throwable)e);
        }
    }
}
