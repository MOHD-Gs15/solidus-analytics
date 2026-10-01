package com.solidus.analytics.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.integration.SolidusIntegration;
import com.solidus.api.SolidusTransactionHook;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CloudVetoHook - the agent's transaction veto hook (PROTOCOL.md &sect;6.3 path
 * "Hook").
 *
 * <p>Implements the <b>solidus-api</b> {@link SolidusTransactionHook}
 * contract DIRECTLY since 2.3.2 (audit W-5 closure) — the old build
 * registered a reflection {@code Proxy} with a hand-written
 * {@code InvocationHandler} and even resolved {@code Decision.ALLOW} /
 * {@code Decision.deny(...)} reflectively. One typo in any of those names
 * and the hook silently failed open. The hook name is
 * {@code "solidus-cloud-agent"}.</p>
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
public final class CloudVetoHook implements SolidusTransactionHook {
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
    private volatile boolean registered;

    public CloudVetoHook(CloudAgentStore store) {
        this.store = store;
        this.loadPersisted();
    }

    // ---- SolidusTransactionHook (typed, compile-checked since 2.3.2) ----

    @Override
    public String name() {
        return HOOK_NAME;
    }

    @Override
    public SolidusTransactionHook.Decision allowTransfer(UUID senderUuid, String senderName,
                                                           UUID receiverUuid, String receiverName,
                                                           double amount) {
        return this.decideTransfer(senderUuid, senderName);
    }

    @Override
    public SolidusTransactionHook.Decision allowAuctionListing(UUID sellerUuid, String sellerName, double price) {
        return this.decideMarket(true, sellerUuid);
    }

    @Override
    public SolidusTransactionHook.Decision allowAuctionPurchase(UUID buyerUuid, String buyerName, double price) {
        return this.decideMarket(true, buyerUuid);
    }

    @Override
    public SolidusTransactionHook.Decision allowShopPurchase(UUID playerUuid, String playerName, double cost) {
        return this.decideMarket(false, playerUuid);
    }

    @Override
    public SolidusTransactionHook.Decision allowShopSell(UUID playerUuid, String playerName) {
        return this.decideMarket(false, playerUuid);
    }

    // ---- lifecycle ----------------------------------------------------

    /**
     * Registers the hook into Core through the typed contract. Returns false
     * when Core is absent (standalone mode) - every pause/freeze command
     * will then answer E_CORE_MISSING.
     */
    public boolean register() {
        if (!SolidusIntegration.isAvailable()) {
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] Solidus Core not loaded - veto hook NOT registered. Pause/freeze commands disabled.");
            return false;
        }
        if (this.registered) {
            return true;
        }
        try {
            if (SolidusIntegration.getInstance().registerTransactionHook(this)) {
                this.registered = true;
                SolidusAnalyticsMod.LOGGER.info("[Cloud] Veto hook '{}' registered into Solidus Core.", HOOK_NAME);
                return true;
            }
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] Core rejected hook registration (duplicate name?).");
            return false;
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to register veto hook through the contract", e);
            return false;
        }
    }

    public void unregister() {
        if (this.registered && SolidusIntegration.isAvailable()) {
            SolidusIntegration.getInstance().unregisterTransactionHook(this);
            this.registered = false;
        }
    }

    public boolean isHookActive() {
        return this.registered;
    }

    // ---- veto decisions (must be fast, in-memory only) ----------------

    private SolidusTransactionHook.Decision decideTransfer(UUID senderUuid, String senderName) {
        PauseInfo pause = this.globalPause;
        if (pause != null) {
            return SolidusTransactionHook.Decision.deny("Economy paused by operator: " + pause.reason());
        }
        FreezeInfo f = this.lookup(senderUuid, senderName);
        if (f != null) {
            return SolidusTransactionHook.Decision.deny("Your account is frozen: " + f.reason());
        }
        return SolidusTransactionHook.Decision.ALLOW;
    }

    private SolidusTransactionHook.Decision decideMarket(boolean auctions, UUID actorUuid) {
        PauseInfo pause = this.globalPause;
        if (pause != null) {
            return SolidusTransactionHook.Decision.deny("Economy paused by operator: " + pause.reason());
        }
        PauseInfo marketPause = auctions ? this.auctionsPaused : this.shopPaused;
        if (marketPause != null) {
            return SolidusTransactionHook.Decision.deny((auctions ? "Auctions" : "Shop") + " paused by operator: " + marketPause.reason());
        }
        if (this.frozen.containsKey(actorUuid)) {
            return SolidusTransactionHook.Decision.deny("Your account is frozen: " + this.frozen.get(actorUuid).reason());
        }
        return SolidusTransactionHook.Decision.ALLOW;
    }

    private FreezeInfo lookup(UUID uuid, String name) {
        FreezeInfo f = uuid != null ? this.frozen.get(uuid) : null;
        if (f != null) {
            return f;
        }
        UUID byName = name != null ? this.nameIndex.get(name) : null;
        return byName != null ? this.frozen.get(byName) : null;
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
