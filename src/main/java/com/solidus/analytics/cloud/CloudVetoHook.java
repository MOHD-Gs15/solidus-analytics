package com.solidus.analytics.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.integration.SolidusIntegration;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CloudVetoHook - the agent's transaction veto hook (PROTOCOL.md &sect;6.3 path
 * "Hook").
 *
 * <p>Registered into Solidus Core through the SAME reflection-proxy pattern the
 * Governance module uses (SolidusTransactionHook is interface-only for us - we
 * never link against Core). The hook name is {@code "solidus-cloud-agent"}.</p>
 *
 * <p>State (all volatile/concurrent, consulted in-memory only - veto hooks must
 * be fast per the Core threading contract):</p>
 * <ul>
 *   <li>{@code globalPause} - the econ.pause.global circuit breaker: deny every
 *       money movement with the operator's reason shown to players.</li>
 *   <li>{@code auctionsPaused} / {@code shopPaused} - market.auction.pause /
 *       market.shop.pause (deny listing+purchase / purchase+sell).</li>
 *   <li>{@code frozen} - econ.freeze per-account blocks: any movement where the
 *       frozen account is sender/buyer/seller is denied.</li>
 * </ul>
 *
 * <p>Every mutation is persisted to cloud.db and re-loaded at boot, so a
 * restart cannot silently unfreeze a containment action (&sect;5.4). Fail-open
 * is inherited from Core's EconomyHooks: if our proxy throws, the transaction
 * proceeds - the agent can never wedge the economy by itself.</p>
 */
public final class CloudVetoHook {
    public static final String HOOK_NAME = "solidus-cloud-agent";
    private static final String STATE_KEY = "veto_state";

    /** Pause flag: who set it, why, when. Immutable snapshot; null = off. */
    public record PauseInfo(String reason, String by, long at) {}

    /** Frozen account entry (money-freeze, not movement freeze). */
    public record FreezeInfo(String name, String reason, String by, long at) {}

    private volatile PauseInfo globalPause = null;
    private volatile PauseInfo auctionsPaused = null;
    private volatile PauseInfo shopPaused = null;
    private final ConcurrentHashMap<UUID, FreezeInfo> frozen = new ConcurrentHashMap<UUID, FreezeInfo>();
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<String, UUID>();

    private final CloudAgentStore store;
    private Object registeredProxy;

    public CloudVetoHook(CloudAgentStore store) {
        this.store = store;
        this.loadPersisted();
    }

    // ---- lifecycle ----------------------------------------------------

    /**
     * Registers the reflection proxy into Core. Returns false when Core is
     * absent (standalone mode) - every pause/freeze command will then answer
     * E_CORE_MISSING.
     */
    public boolean register() {
        if (!SolidusIntegration.isAvailable()) {
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] Solidus Core not loaded - veto hook NOT registered. Pause/freeze commands disabled.");
            return false;
        }
        if (this.registeredProxy != null) {
            return true;
        }
        try {
            Class<?> hookItf = Class.forName("com.solidus.api.SolidusTransactionHook");
            InvocationHandler handler = new InvocationHandler(){
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name2 = method.getName();
                    if ("name".equals(name2)) {
                        return HOOK_NAME;
                    }
                    if ("hashCode".equals(name2)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name2)) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(name2)) {
                        return "solidus-cloud-agent-proxy";
                    }
                    switch (name2) {
                        case "allowTransfer": {
                            return CloudVetoHook.this.decideTransfer((UUID)args[0], (String)args[1], (UUID)args[2], (String)args[3]);
                        }
                        case "allowAuctionListing":
                        case "allowAuctionPurchase": {
                            UUID actor = (UUID)args[0];
                            return CloudVetoHook.this.decideMarket(true, actor);
                        }
                        case "allowShopPurchase":
                        case "allowShopSell": {
                            UUID actor = (UUID)args[0];
                            return CloudVetoHook.this.decideMarket(false, actor);
                        }
                    }
                    return null;
                }
            };
            Object proxy = Proxy.newProxyInstance(hookItf.getClassLoader(), new Class<?>[]{hookItf}, handler);
            if (SolidusIntegration.getInstance().registerTransactionHook(proxy)) {
                this.registeredProxy = proxy;
                SolidusAnalyticsMod.LOGGER.info("[Cloud] Veto hook '{}' registered into Solidus Core.", (Object)HOOK_NAME);
                return true;
            }
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] Core rejected hook registration (duplicate name?).");
            return false;
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to register veto hook via reflection", (Throwable)e);
            return false;
        }
    }

    public void unregister() {
        if (this.registeredProxy != null && SolidusIntegration.isAvailable()) {
            SolidusIntegration.getInstance().unregisterTransactionHook(this.registeredProxy);
            this.registeredProxy = null;
        }
    }

    public boolean isHookActive() {
        return this.registeredProxy != null;
    }

    // ---- veto decisions (must be fast, in-memory only) ----------------

    private Object decideTransfer(UUID senderUuid, String senderName, UUID receiverUuid, String receiverName) {
        PauseInfo pause = this.globalPause;
        if (pause != null) {
            return denyDecision("Economy paused by operator: " + pause.reason());
        }
        FreezeInfo f = this.lookup(senderUuid, senderName);
        if (f != null) {
            return denyDecision("Your account is frozen: " + f.reason());
        }
        return allowDecision();
    }

    private Object decideMarket(boolean auctions, UUID actorUuid) {
        PauseInfo pause = this.globalPause;
        if (pause != null) {
            return denyDecision("Economy paused by operator: " + pause.reason());
        }
        PauseInfo marketPause = auctions ? this.auctionsPaused : this.shopPaused;
        if (marketPause != null) {
            return denyDecision((auctions ? "Auctions" : "Shop") + " paused by operator: " + marketPause.reason());
        }
        if (this.frozen.containsKey(actorUuid)) {
            return denyDecision("Your account is frozen: " + this.frozen.get(actorUuid).reason());
        }
        return allowDecision();
    }

    private FreezeInfo lookup(UUID uuid, String name) {
        FreezeInfo f = uuid != null ? this.frozen.get(uuid) : null;
        if (f != null) {
            return f;
        }
        UUID byName = name != null ? this.nameIndex.get(name) : null;
        return byName != null ? this.frozen.get(byName) : null;
    }

    // reflective access to Core's Decision record
    private static volatile Object cachedAllowDecision;

    private static Object allowDecision() {
        if (cachedAllowDecision != null) {
            return cachedAllowDecision;
        }
        try {
            Class<?> decision = Class.forName("com.solidus.api.SolidusTransactionHook$Decision");
            cachedAllowDecision = decision.getField("ALLOW").get(null);
            return cachedAllowDecision;
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Cannot resolve Decision.ALLOW", (Throwable)e);
            return null;
        }
    }

    private static Object denyDecision(String reason) {
        try {
            Class<?> decision = Class.forName("com.solidus.api.SolidusTransactionHook$Decision");
            return decision.getMethod("deny", String.class).invoke(null, reason);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Cannot build denial decision", (Throwable)e);
            return null;
        }
    }

    // ---- state mutations (command side, may be any thread) -------------

    public void setGlobalPause(PauseInfo info) {
        this.globalPause = info;
        this.persist();
    }

    public void setAuctionsPaused(PauseInfo info) {
        this.auctionsPaused = info;
        this.persist();
    }

    public void setShopPaused(PauseInfo info) {
        this.shopPaused = info;
        this.persist();
    }

    public void freeze(UUID uuid, String name, FreezeInfo info) {
        this.frozen.put(uuid, info);
        if (name != null) {
            this.nameIndex.put(name, uuid);
        }
        this.persist();
    }

    public boolean unfreeze(UUID uuid) {
        FreezeInfo removed = this.frozen.remove(uuid);
        if (removed != null) {
            this.nameIndex.remove(removed.name());
            this.persist();
            return true;
        }
        return false;
    }

    public boolean isFrozen(UUID uuid) {
        return this.frozen.containsKey(uuid);
    }

    public Map<UUID, FreezeInfo> frozenView() {
        return Map.copyOf(this.frozen);
    }

    public PauseInfo getGlobalPause() {
        return this.globalPause;
    }

    public PauseInfo getAuctionsPaused() {
        return this.auctionsPaused;
    }

    public PauseInfo getShopPaused() {
        return this.shopPaused;
    }

    // ---- persistence ---------------------------------------------------

    private synchronized void persist() {
        JsonObject root = new JsonObject();
        this.putPause(root, "global", this.globalPause);
        this.putPause(root, "auctions", this.auctionsPaused);
        this.putPause(root, "shop", this.shopPaused);
        JsonObject frozenJson = new JsonObject();
        this.frozen.forEach((uuid, info) -> {
            JsonObject f = new JsonObject();
            f.addProperty("name", info.name());
            f.addProperty("reason", info.reason());
            f.addProperty("by", info.by());
            f.addProperty("at", info.at());
            frozenJson.add(uuid.toString(), f);
        });
        root.add("frozen", frozenJson);
        this.store.saveState(STATE_KEY, root.toString());
    }

    private void putPause(JsonObject root, String key, PauseInfo info) {
        if (info != null) {
            JsonObject p = new JsonObject();
            p.addProperty("reason", info.reason());
            p.addProperty("by", info.by());
            p.addProperty("at", info.at());
            root.add(key, p);
        }
    }

    private void loadPersisted() {
        String raw = this.store.loadState(STATE_KEY);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            this.globalPause = this.readPause(root, "global");
            this.auctionsPaused = this.readPause(root, "auctions");
            this.shopPaused = this.readPause(root, "shop");
            if (root.has("frozen")) {
                JsonObject frozenJson = root.getAsJsonObject("frozen");
                for (Map.Entry<String, com.google.gson.JsonElement> entry : frozenJson.entrySet()) {
                    String uuidRaw = entry.getKey();
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject f = entry.getValue().getAsJsonObject();
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidRaw);
                    }
                    catch (IllegalArgumentException e) {
                        continue;
                    }
                    this.frozen.put(uuid, new FreezeInfo(
                        f.has("name") ? f.get("name").getAsString() : "",
                        f.has("reason") ? f.get("reason").getAsString() : "frozen",
                        f.has("by") ? f.get("by").getAsString() : "unknown",
                        f.has("at") ? f.get("at").getAsLong() : 0L));
                }
            }
            if (this.globalPause != null || this.auctionsPaused != null || this.shopPaused != null || !this.frozen.isEmpty()) {
                SolidusAnalyticsMod.LOGGER.info("[Cloud] Restored durable veto state: pause={} auctions={} shop={} frozen={}",
                    (Object)(this.globalPause != null), (Object)(this.auctionsPaused != null),
                    (Object)(this.shopPaused != null), (Object)this.frozen.size());
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] Failed to restore veto state - starting clean", (Throwable)e);
        }
    }

    private PauseInfo readPause(JsonObject root, String key) {
        if (!root.has(key)) {
            return null;
        }
        JsonObject p = root.getAsJsonObject(key);
        return new PauseInfo(
            p.has("reason") ? p.get("reason").getAsString() : "paused",
            p.has("by") ? p.get("by").getAsString() : "unknown",
            p.has("at") ? p.get("at").getAsLong() : 0L);
    }
}
