package com.solidus.analytics.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solidus.analytics.SolidusAnalyticsMod;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * TelemetryCollector - health + players telemetry (PROTOCOL.md &sect;5.2).
 *
 * <p>All numbers are gathered inside the server process:</p>
 * <ul>
 *   <li><b>TPS/MSPT</b> - measured from the mod's existing END_SERVER_TICK hook:
 *       a 60 s ring of tick durations gives the exact 1-minute average + p95 +
 *       spike count; exponentially-weighted averages cover 5/15 minutes.</li>
 *   <li><b>RAM/GC</b> - platform JMX beans; <b>CPU</b> - com.sun.management bean.</li>
 *   <li><b>Players</b> - diffed every cycle: join/leave are derived events, so no
 *       extra Fabric event registration is needed (version-stable).</li>
 *   <li><b>modsHash</b> - SHA-256 of the sorted modId@version list; any change
 *       fires the agent.security.change alert on the relay (G5 compensating control).</li>
 * </ul>
 *
 * <p>Ping/IP/game-mode go through reflection because their accessor names have
 * drifted across Minecraft versions; a failure degrades the single field to null
 * instead of breaking the stream.</p>
 */
public final class TelemetryCollector {
    private static final int RING_TICKS = 1200; // 60 s at 20 tps
    private static final double SPIKE_MS = 75.0;
    private static final int SPIKE_RUN_TRIGGER = 3;

    private final long[] tickNanos = new long[RING_TICKS];
    private int ringHead = 0;
    private int ringCount = 0;
    private long lastTickNano = 0L;
    private double msptEma5 = 50.0;
    private double msptEma15 = 50.0;
    private int spikeRun = 0;
    private long bootMs = System.currentTimeMillis();
    private String cachedModsHash;
    private String cachedMcVersion;

    private final Map<String, JsonObject> lastPlayers = new HashMap<String, JsonObject>();
    private final Map<String, Long> firstSeenMs = new HashMap<String, Long>();

    /** Consumer for on-change events (spikes, join/leave). */
    public interface EventSink {
        void accept(String type, JsonObject payload);
    }

    private final EventSink sink;

    public TelemetryCollector(EventSink sink) {
        this.sink = sink;
    }

    // ---- tick measurement (server thread) -----------------------------

    public void onServerTick() {
        long now = System.nanoTime();
        double ms = this.lastTickNano == 0L ? 50.0 : Math.max(1.0, (double)(now - this.lastTickNano) / 1000000.0);
        this.lastTickNano = now;
        this.tickNanos[this.ringHead] = (long)ms;
        this.ringHead = (this.ringHead + 1) % RING_TICKS;
        this.ringCount = Math.min(this.ringCount + 1, RING_TICKS);
        double alpha5 = 1.0 / (20.0 * 60.0 * 5.0);
        double alpha15 = 1.0 / (20.0 * 60.0 * 15.0);
        this.msptEma5 += alpha5 * (ms - this.msptEma5);
        this.msptEma15 += alpha15 * (ms - this.msptEma15);
        if (ms > SPIKE_MS) {
            ++this.spikeRun;
            if (this.spikeRun == SPIKE_RUN_TRIGGER) {
                JsonObject spike = new JsonObject();
                spike.addProperty("run", this.spikeRun);
                spike.addProperty("ms", (long)ms);
                spike.addProperty("tps1", round1(this.tpsFrom(this.msptAvg60())));
                spike.addProperty("at", System.currentTimeMillis());
                this.sink.accept("health.lag_spike", spike);
            }
        }
        else if (this.spikeRun > 0) {
            this.spikeRun = 0;
        }
    }

    private double msptAvg60() {
        if (this.ringCount == 0) {
            return 50.0;
        }
        long sum = 0L;
        for (int i = 0; i < this.ringCount; ++i) {
            sum += this.tickNanos[i];
        }
        return (double)sum / (double)this.ringCount;
    }

    private double tpsFrom(double mspt) {
        return Math.min(20.0, 1000.0 / Math.max(1.0, mspt));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // ---- fast collectors (10 s, server thread) -------------------------

    public List<JsonObject> collectFast(MinecraftServer server) {
        ArrayList<JsonObject> events = new ArrayList<JsonObject>(4);
        events.add(this.tps());
        events.add(this.ram());
        events.add(this.cpu());
        this.players(server, events);
        return events;
    }

    private JsonObject tps() {
        double avg = this.msptAvg60();
        double[] sorted = new double[this.ringCount];
        for (int i = 0; i < this.ringCount; ++i) {
            sorted[i] = (double)this.tickNanos[i];
        }
        java.util.Arrays.sort(sorted);
        double p95 = this.ringCount == 0 ? 50.0 : sorted[(int)Math.min(sorted.length - 1L, (long)Math.floor(sorted.length * 0.95))];
        int spikes = 0;
        for (int i = 0; i < this.ringCount; ++i) {
            if ((double)this.tickNanos[i] > SPIKE_MS) {
                ++spikes;
            }
        }
        JsonObject d = new JsonObject();
        d.addProperty("tps1", round1(this.tpsFrom(avg)));
        d.addProperty("tps5", round1(this.tpsFrom(this.msptEma5)));
        d.addProperty("tps15", round1(this.tpsFrom(this.msptEma15)));
        d.addProperty("msptAvg", round1(avg));
        d.addProperty("msptP95", round1(p95));
        d.addProperty("spikes", spikes);
        return d;
    }

    private JsonObject ram() {
        java.lang.management.MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        java.lang.management.MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        JsonObject d = new JsonObject();
        d.addProperty("heapUsedB", heap.getUsed() / 1048576L);
        d.addProperty("heapMaxB", heap.getMax() < 0L ? -1 : heap.getMax() / 1048576L);
        d.addProperty("heapCommittedB", heap.getCommitted() / 1048576L);
        d.addProperty("nonHeapB", nonHeap.getUsed() / 1048576L);
        JsonObject gc = new JsonObject();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            JsonObject g = new JsonObject();
            g.addProperty("count", bean.getCollectionCount());
            g.addProperty("ms", bean.getCollectionTime());
            gc.add(bean.getName(), g);
        }
        d.add("gc", gc);
        return d;
    }

    private JsonObject cpu() {
        JsonObject d = new JsonObject();
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
            double proc = os.getProcessCpuLoad();
            double sys = os.getCpuLoad();
            d.addProperty("procPct", proc < 0.0 ? -1.0 : round1(proc * 100.0));
            d.addProperty("sysPct", sys < 0.0 ? -1.0 : round1(sys * 100.0));
            d.addProperty("load1", round1(os.getSystemLoadAverage()));
        }
        catch (Throwable t) {
            d.addProperty("procPct", -1.0);
            d.addProperty("sysPct", -1.0);
            d.addProperty("load1", -1.0);
        }
        return d;
    }

    /** Query-command variants (same payloads as the periodic events). */
    public JsonObject tpsQuery() {
        return this.tps();
    }

    public JsonObject ramQuery() {
        return this.ram();
    }

    public JsonObject cpuQuery() {
        return this.cpu();
    }

    /** Player game mode name ("survival", ...) or null. */
    public String gameMode(ServerPlayer player) {
        try {
            return player.gameMode().getName();
        }
        catch (Throwable t) {
            return null;
        }
    }

    private void players(MinecraftServer server, List<JsonObject> events) {
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        HashMap<String, JsonObject> current = new HashMap<String, JsonObject>();
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String name = player.getGameProfile().name();
            JsonObject p = new JsonObject();
            p.addProperty("n", name);
            p.addProperty("uuid", String.valueOf(player.getGameProfile().id()));
            p.addProperty("ping", (Integer)null);
            p.addProperty("level", this.levelName(player));
            p.addProperty("mode", this.gameMode(player));
            p.addProperty("ip", this.maskedIp(player));
            Long firstSeen = this.firstSeenMs.get(name);
            if (firstSeen == null) {
                firstSeen = now;
                this.firstSeenMs.put(name, firstSeen);
                JsonObject join = new JsonObject();
                join.addProperty("n", name);
                join.addProperty("uuid", String.valueOf(player.getGameProfile().id()));
                join.addProperty("ip", this.maskedIp(player));
                join.addProperty("first", false);
                events.add(this.wrap("player.join", join));
            }
            p.addProperty("sessS", (int)((now - firstSeen) / 1000L));
            p.add("balC", null);
            arr.add(p);
            current.put(name, p);
        }
        for (String previous : this.lastPlayers.keySet()) {
            if (!current.containsKey(previous)) {
                JsonObject leave = new JsonObject();
                leave.addProperty("n", previous);
                leave.addProperty("uuid", this.lastPlayers.get(previous).has("uuid") ? this.lastPlayers.get(previous).get("uuid").getAsString() : "");
                this.firstSeenMs.remove(previous);
                events.add(this.wrap("player.leave", leave));
            }
        }
        this.lastPlayers.clear();
        this.lastPlayers.putAll(current);
        d.addProperty("full", true);
        d.addProperty("max", server.getPlayerList().getMaxPlayers());
        d.add("players", arr);
        events.add(this.wrap("players.list", d));
    }

    private JsonObject wrap(String type, JsonObject payload) {
        JsonObject ev = new JsonObject();
        ev.addProperty("type", type);
        ev.add("d", payload);
        return ev;
    }

    /** Full IP - only reachable through player.inspect with reveal:true (audited, G6). */
    public String fullIp(ServerPlayer player) {
        try {
            java.net.SocketAddress addr = player.connection.getRemoteAddress();
            if (addr instanceof java.net.InetSocketAddress inet) {
                return inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
            }
        }
        catch (Throwable t) {
            // ignore
        }
        return null;
    }

    private String levelName(ServerPlayer player) {
        try {
            return player.level().dimension().identifier().toString();
        }
        catch (Throwable t) {
            return null;
        }
    }

    public String maskedIp(ServerPlayer player) {
        try {
            java.net.SocketAddress addr = player.connection.getRemoteAddress();
            if (addr instanceof java.net.InetSocketAddress inet) {
                String host = inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
                String[] parts = host.split("\\.");
                if (parts.length == 4) {
                    return parts[0] + "." + parts[1] + "." + parts[2] + ".*";
                }
                return host;
            }
        }
        catch (Throwable t) {
            // ignore
        }
        return null;
    }

    // ---- slow collectors (60-300 s) ------------------------------------

    public JsonObject world(MinecraftServer server) {
        JsonObject d = new JsonObject();
        JsonArray levels = new JsonArray();
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            JsonObject l = new JsonObject();
            try {
                l.addProperty("name", level.dimension().identifier().toString());
            }
            catch (Throwable t) {
                l.addProperty("name", "?");
            }
            try {
                l.addProperty("chunks", level.getChunkSource().getLoadedChunksCount());
            }
            catch (Throwable t) {
                l.addProperty("chunks", -1);
            }
            l.addProperty("entities", this.countEntities(level));
            l.addProperty("diskB", -1);
            levels.add(l);
        }
        d.add("levels", levels);
        return d;
    }

    private int countEntities(net.minecraft.server.level.ServerLevel level) {
        try {
            return level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forExactClass(net.minecraft.world.entity.Entity.class),
                e -> true).size();
        }
        catch (Throwable t) {
            return -1;
        }
    }

    public JsonObject entitiesTop(MinecraftServer server) {
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            try {
                for (net.minecraft.world.entity.Entity entity : level.getEntities(
                        net.minecraft.world.level.entity.EntityTypeTest.forExactClass(net.minecraft.world.entity.Entity.class),
                        e -> true)) {
                    String type = entity.getType().toString();
                    counts.merge(type, 1, Integer::sum);
                }
            }
            catch (Throwable t) {
                // degrade quietly
            }
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(10, top.size()); ++i) {
            JsonObject e = new JsonObject();
            e.addProperty("type", top.get(i).getKey());
            e.addProperty("count", top.get(i).getValue());
            arr.add(e);
        }
        d.add("top", arr);
        return d;
    }

    public JsonObject meta(MinecraftServer server, String agentVersion, int droppedEvts) {
        JsonObject d = new JsonObject();
        d.addProperty("agent", agentVersion);
        d.addProperty("mc", this.mcVersion(server));
        d.addProperty("loader", this.loaderVersion());
        d.addProperty("java", System.getProperty("java.version", "?"));
        d.addProperty("core", this.coreVersion());
        d.addProperty("governance", net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("solidus-governance"));
        d.addProperty("uptimeS", (int)((System.currentTimeMillis() - this.bootMs) / 1000L));
        d.addProperty("modsHash", this.modsHash());
        d.addProperty("droppedEvts", droppedEvts);
        d.addProperty("playersMax", server.getPlayerList().getMaxPlayers());
        return d;
    }

    private String loaderVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        }
        catch (Throwable t) {
            return "?";
        }
    }

    private String mcVersion(MinecraftServer server) {
        try {
            return server.getServerVersion();
        }
        catch (Throwable t) {
            return "?";
        }
    }

    private String coreVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("solidus")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
        }
        catch (Throwable t) {
            return null;
        }
    }

    public String modsHash() {
        if (this.cachedModsHash != null) {
            return this.cachedModsHash;
        }
        try {
            List<String> mods = new ArrayList<String>();
            net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()
                .forEach(m -> mods.add(m.getMetadata().getId() + "@" + m.getMetadata().getVersion().getFriendlyString()));
            mods.sort(String::compareTo);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(String.join("\n", mods).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.cachedModsHash = "sha256:" + HexFormat.of().formatHex(hash);
        }
        catch (Throwable t) {
            this.cachedModsHash = "sha256:error";
        }
        return this.cachedModsHash;
    }

    /** True when the mod fingerprint changed since the last call site check. */
    public boolean modsHashChanged() {
        String previous = this.cachedModsHash;
        this.cachedModsHash = null;
        String fresh = this.modsHash();
        return previous != null && !previous.equals(fresh);
    }

    public UUID[] onlineUuids(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
            .map(p -> p.getGameProfile().id()).toArray(UUID[]::new);
    }
}
