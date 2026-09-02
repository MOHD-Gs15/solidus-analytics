package com.solidus.analytics.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.engine.AnalyticsEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;

/**
 * CloudAgent - the in-process Solidus Cloud agent (PROTOCOL.md).
 *
 * <p>Owns the full Cloud-tier pipeline inside solidus-analytics:</p>
 * <ul>
 *   <li>the outbound WSS connection (via {@link CloudRelayClient}),</li>
 *   <li>three telemetry cadences: fast (10 s: tps/ram/cpu/players), economy
 *       (60 s: balances/supply/flow/market/agent.state), slow (300 s:
 *       inflation/disk/world/entities/meta),</li>
 *   <li>the allow-list command router with idempotency + audit mirror,</li>
 *   <li>the veto hook (durable circuit breaker + account freezes) and the
 *       movement anchor freeze,</li>
 *   <li>local backups (timestamped copies - never routed through the relay).</li>
 * </ul>
 *
 * <p>Disabled by default ({@code cloud.enabled=false}) - flipping it on is the
 * operator's explicit pairing decision. When Core is absent the agent still
 * streams read-only telemetry (DB fallbacks) and answers write commands with
 * E_CORE_MISSING.</p>
 */
public final class CloudAgent implements CloudCommandRouter.Sink, TelemetryCollector.EventSink {
    private final AnalyticsEngine engine;
    private final Path configDir;
    private final Path gameDir;
    private final String economyDbPath;
    private final String auctionsDbPath;
    private final String analyticsDbPath;

    private CloudAgentConfig config;
    private CloudAgentStore store;
    private CloudVetoHook veto;
    private AnchorFreezeManager anchor;
    private TelemetryCollector telemetry;
    private EconomyCollector economy;
    private CloudCommandRouter router;
    private CloudRelayClient client;
    private ScheduledExecutorService cloudExec;
    private volatile MinecraftServer server;
    private volatile boolean started = false;
    private final AtomicLong seq = new AtomicLong(0L);

    public CloudAgent(AnalyticsEngine engine, Path configDir, Path gameDir,
                      String economyDbPath, String auctionsDbPath, String analyticsDbPath) {
        this.engine = engine;
        this.configDir = configDir;
        this.gameDir = gameDir;
        this.economyDbPath = economyDbPath;
        this.auctionsDbPath = auctionsDbPath;
        this.analyticsDbPath = analyticsDbPath;
    }

    public void attachServer(MinecraftServer server) {
        this.server = server;
    }

    public synchronized void start() {
        if (this.started) {
            return;
        }
        this.config = new CloudAgentConfig(this.configDir);
        this.config.load();
        if (!this.config.isEnabled()) {
            SolidusAnalyticsMod.LOGGER.info("[Cloud] Cloud agent disabled (cloud.enabled=false). Pair first, then flip the flag.");
            return;
        }
        if (this.config.isInsecureRelayRejected()) {
            // B-3: stay off rather than ship the pairing secret in cleartext.
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Agent stays off: plaintext ws:// relay refused (PROTOCOL.md §1). Switch cloud.relayUrl to wss:// or set cloud.allowInsecureRelay=true for local testing.");
            return;
        }
        try {
            this.store = new CloudAgentStore(this.configDir);
            this.store.initialize();
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] cloud.db failed to initialize - cloud agent stays off", (Throwable)e);
            return;
        }
        this.veto = new CloudVetoHook(this.store);
        this.veto.register();
        this.anchor = new AnchorFreezeManager();
        this.telemetry = new TelemetryCollector(this);
        this.economy = new EconomyCollector(this.economyDbPath, this.auctionsDbPath, this.analyticsDbPath,
            this.engine.getLiveMetrics(), this.engine.getInflationCalculator());
        this.router = new CloudCommandRouter(this, this);
        this.cloudExec = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "solidus-cloud-worker");
            t.setDaemon(true);
            return t;
        });
        this.client = new CloudRelayClient(this.config, this::onFrame, this::onReady, this::helloJson);
        int fast = this.config.getFastIntervalSeconds();
        int econ = this.config.getEconomyIntervalSeconds();
        int slow = this.config.getSlowIntervalSeconds();
        this.cloudExec.scheduleAtFixedRate(this::fastCycle, fast, fast, TimeUnit.SECONDS);
        this.cloudExec.scheduleAtFixedRate(this::economyCycle, econ, econ, TimeUnit.SECONDS);
        this.cloudExec.scheduleAtFixedRate(this::slowCycle, slow, slow, TimeUnit.SECONDS);
        this.cloudExec.scheduleAtFixedRate(() -> this.store.prune(48L * 3600000L, this.engine.getConfig().getDataRetentionDays()),
            1L, 24L, TimeUnit.HOURS);
        this.client.start(null);
        this.started = true;
        SolidusAnalyticsMod.LOGGER.info("[Cloud] Agent started: serverId={} relay={} (commands: {}, core: {})",
            (Object)this.config.getServerId(), (Object)this.config.getRelayUrl(),
            (Object)this.router.supportedCommands().size(),
            (Object)(this.veto.isHookActive() ? "hook active" : "read-only"));
    }

    public synchronized void shutdown() {
        if (!this.started) {
            return;
        }
        this.started = false;
        try {
            if (this.client != null) {
                this.client.stop();
            }
            if (this.cloudExec != null) {
                this.cloudExec.shutdown();
                this.cloudExec.awaitTermination(3L, TimeUnit.SECONDS);
            }
            if (this.veto != null) {
                this.veto.unregister();
            }
            if (this.store != null) {
                this.store.shutdown();
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] shutdown hiccup", (Throwable)e);
        }
        SolidusAnalyticsMod.LOGGER.info("[Cloud] Agent stopped.");
    }

    /** Server-thread tick from the engine. */
    public void onServerTick() {
        if (!this.started) {
            return;
        }
        this.telemetry.onServerTick();
        MinecraftServer s = this.server;
        if (s != null) {
            this.anchor.onTick(s);
        }
    }

    // ---- accessors used by the router -------------------------------------

    CloudAgentStore store() {
        return this.store;
    }

    ScheduledExecutorService cloudExecutor() {
        return this.cloudExec;
    }

    MinecraftServer server() {
        return this.server;
    }

    TelemetryCollector telemetry() {
        return this.telemetry;
    }

    EconomyCollector economy() {
        return this.economy;
    }

    CloudVetoHook veto() {
        return this.veto;
    }

    AnchorFreezeManager anchor() {
        return this.anchor;
    }

    CloudAgentConfig config() {
        return this.config;
    }

    public boolean isStarted() {
        return this.started;
    }

    // ---- handshake + telemetry cycles --------------------------------------

    private String helloJson() {
        JsonObject hello = new JsonObject();
        hello.addProperty("sv", 1);
        hello.addProperty("id", CloudRelayClient.newId());
        hello.addProperty("t", "evt");
        hello.addProperty("type", "hello");
        hello.addProperty("serverId", this.config.getServerId());
        hello.addProperty("secret", this.config.getPairingSecret());
        hello.addProperty("name", this.config.getDisplayName());
        hello.addProperty("agent", this.agentVersion());
        MinecraftServer s = this.server;
        if (s != null) {
            hello.addProperty("mc", safe(() -> s.getServerVersion().toString()));
            hello.addProperty("playersMax", s.getPlayerList().getMaxPlayers());
        }
        hello.addProperty("java", System.getProperty("java.version", "?"));
        hello.addProperty("modsHash", this.telemetry().modsHash());
        hello.addProperty("restartCapable", this.config.isRestartCapable());
        JsonArray caps = new JsonArray();
        this.router.supportedCommands().forEach(caps::add);
        hello.add("caps", caps);
        return hello.toString();
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        }
        catch (Throwable t) {
            return null;
        }
    }

    public String agentVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("solidus-analytics")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        }
        catch (Throwable t) {
            return "?";
        }
    }

    private void fastCycle() {
        if (!this.client.isReady()) {
            return;
        }
        MinecraftServer s = this.server;
        if (s == null) {
            return;
        }
        try {
            s.execute(() -> {
                try {
                    for (JsonObject e : this.telemetry.collectFast(s)) {
                        this.emit(e.get("type").getAsString(), e.getAsJsonObject("d"));
                    }
                }
                catch (Throwable t) {
                    SolidusAnalyticsMod.LOGGER.debug("[Cloud] fast cycle failed", (Throwable)t);
                }
            });
        }
        catch (Throwable t) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] fast schedule failed", (Throwable)t);
        }
    }

    private void economyCycle() {
        if (!this.client.isReady()) {
            return;
        }
        try {
            this.emit("econ.top", this.economy.econTop());
            this.emit("econ.supply", this.economy.econSupply());
            this.emit("econ.distribution", this.economy.econDistribution());
            this.emit("econ.flow", this.economy.econFlow());
            this.emit("econ.notifications", this.economy.econNotifications());
            this.emit("market.auctions.active", this.economy.marketAuctionsActive());
            this.emit("agent.state", this.agentState());
        }
        catch (Throwable t) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] economy cycle failed", (Throwable)t);
        }
    }

    private void slowCycle() {
        if (!this.client.isReady()) {
            return;
        }
        try {
            this.emit("econ.inflation", this.economy.econInflation());
            this.emit("market.auctions.sold", this.economy.marketAuctionsSold());
            this.emit("market.shop.volume", this.economy.marketShopVolume());
            this.emit("health.disk", this.diskStats());
            MinecraftServer s = this.server;
            if (s != null) {
                s.execute(() -> {
                    try {
                        this.emit("health.world", this.telemetry.world(s));
                        this.emit("health.entities", this.telemetry.entitiesTop(s));
                    }
                    catch (Throwable t) {
                        SolidusAnalyticsMod.LOGGER.debug("[Cloud] slow server cycle failed", (Throwable)t);
                    }
                });
            }
            this.emit("health.meta", this.telemetry.meta(s, this.agentVersion(), (int)this.client.droppedEvents()));
        }
        catch (Throwable t) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] slow cycle failed", (Throwable)t);
        }
    }

    public JsonObject agentState() {
        JsonObject d = new JsonObject();
        CloudVetoHook.PauseInfo pause = this.veto.getGlobalPause();
        JsonObject p = new JsonObject();
        p.addProperty("active", pause != null);
        if (pause != null) {
            p.addProperty("reason", pause.reason());
            p.addProperty("by", pause.by());
            p.addProperty("at", pause.at());
        }
        d.add("pause", p);
        CloudVetoHook.PauseInfo auc = this.veto.getAuctionsPaused();
        JsonObject a = new JsonObject();
        a.addProperty("active", auc != null);
        if (auc != null) {
            a.addProperty("reason", auc.reason());
            a.addProperty("by", auc.by());
        }
        d.add("auctionsPaused", a);
        CloudVetoHook.PauseInfo shop = this.veto.getShopPaused();
        JsonObject sh = new JsonObject();
        sh.addProperty("active", shop != null);
        if (shop != null) {
            sh.addProperty("reason", shop.reason());
            sh.addProperty("by", shop.by());
        }
        d.add("shopPaused", sh);
        JsonArray frozen = new JsonArray();
        this.veto.frozenView().forEach((uuid, info) -> {
            JsonObject f = new JsonObject();
            f.addProperty("uuid", String.valueOf(uuid));
            f.addProperty("n", info.name());
            f.addProperty("reason", info.reason());
            f.addProperty("by", info.by());
            f.addProperty("at", info.at());
            frozen.add(f);
        });
        d.add("frozen", frozen);
        d.add("movementFrozen", this.anchor.stateJson().get("movementFrozen"));
        MinecraftServer s = this.server;
        if (s != null) {
            d.addProperty("online", s.getPlayerList().getPlayerCount());
        }
        d.addProperty("hookActive", this.veto.isHookActive());
        return d;
    }

    // ---- events ---------------------------------------------------------------

    @Override
    public void accept(String type, JsonObject payload) {
        this.emit(type, payload);
    }

    public void emit(String type, JsonObject d) {
        if (this.client == null) {
            return;
        }
        JsonObject env = new JsonObject();
        env.addProperty("sv", 1);
        env.addProperty("id", CloudRelayClient.newId());
        env.addProperty("t", "evt");
        env.addProperty("seq", this.seq.incrementAndGet());
        env.addProperty("ts", System.currentTimeMillis());
        env.addProperty("type", type);
        env.add("d", d);
        this.client.send(env.toString());
    }

    private void onReady() {
        this.emit("agent.state", this.agentState());
        MinecraftServer s = this.server;
        if (s != null) {
            s.execute(() -> {
                try {
                    for (JsonObject e : this.telemetry.collectFast(s)) {
                        this.emit(e.get("type").getAsString(), e.getAsJsonObject("d"));
                    }
                    this.emit("health.meta", this.telemetry.meta(s, this.agentVersion(), (int)this.client.droppedEvents()));
                }
                catch (Throwable t) {
                    SolidusAnalyticsMod.LOGGER.debug("[Cloud] onReady snapshot failed", (Throwable)t);
                }
            });
        }
    }

    // ---- inbound frames ---------------------------------------------------------

    private void onFrame(String frame) {
        try {
            JsonObject msg = com.google.gson.JsonParser.parseString(frame).getAsJsonObject();
            if ("cmd".equals(str(msg, "t"))) {
                // immediate transport ack, then route (result follows)
                JsonObject ack = new JsonObject();
                ack.addProperty("sv", 1);
                ack.addProperty("id", str(msg, "id"));
                ack.addProperty("t", "ack");
                ack.addProperty("ok", true);
                this.client.send(ack.toString());
                this.router.route(str(msg, "id"), msg);
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] bad inbound frame: {}", (Object)e.toString());
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    // ---- router sink: results + state notifications ------------------------------

    @Override
    public void sendResult(String msgId, String rid, String cmd, String target, CloudCommandRouter.Actor actor,
                           String status, String code, JsonObject data, String error, long tookMs, boolean duplicate) {
        JsonObject d = new JsonObject();
        d.addProperty("rid", rid);
        d.addProperty("cmd", cmd);
        d.addProperty("target", target);
        if (actor != null) {
            JsonObject a = new JsonObject();
            a.addProperty("uid", actor.uid());
            a.addProperty("name", actor.name());
            a.addProperty("role", actor.role());
            d.add("actor", a);
        }
        d.addProperty("status", status);
        if (code != null) {
            d.addProperty("code", code);
        }
        if (data != null) {
            d.add("data", data);
        }
        if (error != null) {
            d.addProperty("error", error);
        }
        d.addProperty("tookMs", tookMs);
        if (duplicate) {
            d.addProperty("duplicate", true);
        }
        JsonObject env = new JsonObject();
        env.addProperty("sv", 1);
        env.addProperty("id", msgId == null ? CloudRelayClient.newId() : "r-" + msgId);
        env.addProperty("t", "evt");
        env.addProperty("seq", this.seq.incrementAndGet());
        env.addProperty("ts", System.currentTimeMillis());
        env.addProperty("type", "cmd.result");
        env.add("d", d);
        if (this.client != null) {
            this.client.send(env.toString());
        }
    }

    @Override
    public void onVetoChanged() {
        this.emit("agent.state", this.agentState());
    }

    // ---- disk, backups, pairing rotation -----------------------------------------

    public JsonObject diskStats() {
        JsonObject d = new JsonObject();
        d.addProperty("economyDbB", fileSize(this.economyDbPath));
        d.addProperty("auctionsDbB", fileSize(this.auctionsDbPath));
        d.addProperty("analyticsDbB", fileSize(this.analyticsDbPath));
        d.addProperty("walB", fileSize(this.analyticsDbPath + "-wal"));
        d.addProperty("worldsB", dirSize(this.gameDir.resolve("world")));
        d.addProperty("logsB", dirSize(this.gameDir.resolve("logs")));
        try {
            d.addProperty("freeB", Files.getFileStore(this.gameDir).getUsableSpace() / 1048576L);
        }
        catch (IOException e) {
            d.addProperty("freeB", -1L);
        }
        return d;
    }

    private static long fileSize(String path) {
        try {
            return Files.size(Path.of(path, new String[0])) / 1048576L;
        }
        catch (IOException e) {
            return -1L;
        }
    }

    private static long dirSize(Path dir) {
        try (var stream = Files.walk(dir, 2)) {
            long bytes = stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                }
                catch (IOException e) {
                    return 0L;
                }
            }).sum();
            return bytes / 1048576L;
        }
        catch (IOException | java.io.UncheckedIOException e) {
            return -1L;
        }
    }

    private Path backupsDir() {
        return this.configDir.resolve("backups");
    }

    public JsonObject backupNow(boolean worlds, boolean dbs) throws IOException {
        String stamp = Instant.now().toString().replace(":", "").replace("-", "").substring(0, 15);
        Path target = this.backupsDir().resolve(stamp);
        Files.createDirectories(target);
        int files = 0;
        if (dbs) {
            files += this.copyIfExists(Path.of(this.economyDbPath, new String[0]), target.resolve("economy.db"));
            files += this.copyIfExists(Path.of(this.economyDbPath + "-wal", new String[0]), target.resolve("economy.db-wal"));
            files += this.copyIfExists(Path.of(this.auctionsDbPath, new String[0]), target.resolve("auctions.db"));
            files += this.copyIfExists(Path.of(this.analyticsDbPath, new String[0]), target.resolve("analytics.db"));
            files += this.copyIfExists(this.configDir.resolve("cloud.db"), target.resolve("cloud.db"));
        }
        if (worlds) {
            Path world = this.gameDir.resolve("world");
            files += this.copyIfExists(world.resolve("level.dat"), target.resolve("level.dat"));
        }
        JsonObject d = new JsonObject();
        d.addProperty("ok", true);
        d.addProperty("name", stamp);
        d.addProperty("files", files);
        d.addProperty("note", "best-effort local copy; use host snapshots for consistent world backups");
        return d;
    }

    private int copyIfExists(Path src, Path dst) {
        try {
            if (Files.exists(src)) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                return 1;
            }
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] backup copy failed: {}", (Object)src, (Object)e);
        }
        return 0;
    }

    public JsonObject backupList() {
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        Path dir = this.backupsDir();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).forEach(p -> {
                    JsonObject b = new JsonObject();
                    b.addProperty("name", p.getFileName().toString());
                    b.addProperty("at", p.toFile().lastModified());
                    arr.add(b);
                });
            }
            catch (IOException ignored) {
            }
        }
        d.add("backups", arr);
        return d;
    }

    public JsonObject backupPrune(long keepDays) {
        long cutoff = System.currentTimeMillis() - keepDays * 86400000L;
        int removed = 0;
        Path dir = this.backupsDir();
        if (Files.isDirectory(dir)) {
            List<Path> victims = new ArrayList<Path>();
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                    .filter(p -> p.toFile().lastModified() < cutoff)
                    .forEach(victims::add);
            }
            catch (IOException ignored) {
            }
            for (Path v : victims) {
                try {
                    deleteRecursively(v);
                    ++removed;
                }
                catch (IOException ignored) {
                }
            }
        }
        JsonObject d = new JsonObject();
        d.addProperty("ok", true);
        d.addProperty("removed", removed);
        return d;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                }
                catch (IOException ignored) {
                }
            });
        }
    }

    public String rotatePairingSecret() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; ++i) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        String fresh = sb.toString();
        this.config.setPairingSecret(fresh);
        // B-1 fix: the secret must NEVER travel in the command result - that
        // path is persisted to cloud_command_log, the relay's audit ledger and
        // broadcast as cmd.audit to every connected client (§12/§6.7), i.e.
        // readable by roles below owner. The operator reads it from the local
        // 0600 config file (or this single log line on the server they own).
        SolidusAnalyticsMod.LOGGER.warn("[Cloud] Pairing secret rotated. New secret (also in {}): {}",
            (Object)this.configDir.resolve("cloud.properties").toAbsolutePath(), (Object)fresh);
        this.client.forceReconnect();  // B-5: re-hellos with the fresh secret via the hello supplier
        return fresh;
    }
}
