package com.solidus.analytics.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solidus.analytics.SolidusAnalyticsMod;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * CloudCommandRouter - the agent-side allow-list command registry
 * (PROTOCOL.md &sect;6, G1..G6).
 *
 * <p>Everything the wire can ask for is a registered {@link Spec} with a fixed
 * id, risk class, role floor, thread affinity and a handler. Unknown ids are
 * rejected with {@code E_UNKNOWN_CMD} - there is no free console, no raw
 * command text ever arrives from outside, and every name embedded into a
 * pre-templated vanilla command is validated against {@code ^[A-Za-z0-9_]{1,16}$}
 * plus the known-player list before it reaches the dispatcher.</p>
 *
 * <p>The router re-validates what the relay already checked (defense in depth):
 * reason mandatory for W2/D, idemKey mandatory for financial commands, expiry,
 * and role floor (D = owner only).</p>
 */
public final class CloudCommandRouter {
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern SAFE_ITEM = Pattern.compile("^[a-z0-9_.:\\-]{1,64}$");
    private static final long MAX_AMOUNT_C = 10000000L;        // 100,000.00 S$
    private static final long MAX_BATCH_CAP_C = 100000000L;    // 1,000,000.00 S$

    public enum Risk { R, W1, W2, D }

    private enum Affinity { SERVER, ASYNC }

    /** Thrown by handlers for protocol-level rejections. */
    public static final class CmdError extends Exception {
        public final String code;
        public CmdError(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public record Actor(String uid, String name, String role) {}

    public interface Handler {
        JsonObject apply(Ctx ctx, JsonObject args) throws Exception;
    }

    public record Ctx(String cmd, String target, String reason, Actor actor, long at) {}

    /** Where to publish results and state-change notifications. */
    public interface Sink {
        void sendResult(String msgId, String rid, String cmd, String target, Actor actor,
                        String status, String code, JsonObject data, String error, long tookMs,
                        boolean duplicate);

        void onVetoChanged();
    }

    record Spec(String id, Risk risk, boolean financial, String minRole, Affinity affinity, Handler handler) {
        int minRank() {
            return rank(minRole);
        }
    }

    static int rank(String role) {
        return switch (role == null ? "viewer" : role) {
            case "owner" -> 3;
            case "admin" -> 2;
            case "mod" -> 1;
            default -> 0;
        };
    }

    private final Map<String, Spec> registry = new LinkedHashMap<String, Spec>();
    private final CloudAgent agent;
    private final Sink sink;

    public CloudCommandRouter(CloudAgent agent, Sink sink) {
        this.agent = agent;
        this.sink = sink;
        this.registerAll();
    }

    public java.util.Set<String> supportedCommands() {
        return java.util.Collections.unmodifiableSet(this.registry.keySet());
    }

    /** Central entry point. Validation -> idempotency -> dispatch -> result + audit. */
    public void route(String msgId, JsonObject msg) {
        String rid = msg.has("rid") && msg.get("rid").isJsonPrimitive() ? msg.get("rid").getAsString() : msgId;
        String cmd = msg.has("cmd") && msg.get("cmd").isJsonPrimitive() ? msg.get("cmd").getAsString() : null;
        String target = msg.has("target") && msg.get("target").isJsonPrimitive() ? msg.get("target").getAsString() : "";
        String reason = msg.has("reason") && msg.get("reason").isJsonPrimitive() ? msg.get("reason").getAsString() : "";
        JsonObject args = msg.has("args") && msg.get("args").isJsonObject() ? msg.get("args").getAsJsonObject() : new JsonObject();
        Actor actor = new Actor(
            str(msg, "uid"), str(msg, "actorName"), str(msg, "actorRole"));
        if (actor.name() == null && msg.has("actor") && msg.get("actor").isJsonObject()) {
            JsonObject a = msg.getAsJsonObject("actor");
            actor = new Actor(str(a, "uid"), str(a, "name"), str(a, "role"));
        }
        long at = System.currentTimeMillis();
        if (msg.has("expiresAt") && msg.get("expiresAt").isJsonPrimitive()) {
            long exp = msg.get("expiresAt").getAsLong();
            if (exp > 0L && exp < at) {
                this.sink.sendResult(msgId, rid, cmd, target, actor, "rejected", "E_EXPIRED", null, "ttl exceeded", 0L, false);
                return;
            }
        }
        Spec spec = cmd == null ? null : this.registry.get(cmd);
        if (spec == null) {
            this.sink.sendResult(msgId, rid, cmd, target, actor, "rejected", "E_UNKNOWN_CMD", null,
                "command id is not in the agent allow-list", 0L, false);
            return;
        }
        if (rank(actor.role()) < spec.minRank()) {
            this.sink.sendResult(msgId, rid, cmd, target, actor, "rejected", "E_ROLE", null, "role below minRole", 0L, false);
            return;
        }
        if ((spec.risk() == Risk.W2 || spec.risk() == Risk.D) && (reason == null || reason.isBlank())) {
            this.sink.sendResult(msgId, rid, cmd, target, actor, "rejected", "E_ARGS", null, "reason is mandatory for this risk class", 0L, false);
            return;
        }
        String idemKey = str(msg, "idemKey");
        if (spec.financial() && (idemKey == null || idemKey.isBlank())) {
            this.sink.sendResult(msgId, rid, cmd, target, actor, "rejected", "E_ARGS", null, "idemKey is mandatory for financial commands", 0L, false);
            return;
        }
        // B-2 fix: atomic claim BEFORE dispatch. INSERT OR IGNORE is the
        // linearization point - two frames racing with the same idemKey (or a
        // relay crash-re-forward, which §8 explicitly relies on the agent to
        // catch) can no longer both pass the old check-then-act gap.
        boolean owned = false;
        if (spec.financial() && idemKey != null) {
            CloudAgentStore.Claim claim = this.agent.store().claimIdempotent(idemKey, spec.id());
            if (claim.storeError()) {
                // Fail CLOSED: never execute money movement without idempotency.
                this.sink.sendResult(msgId, rid, cmd, target, actor, "rejected", "E_EXEC", null,
                    "idempotency store unavailable - refusing to execute financial command", 0L, false);
                return;
            }
            if (!claim.claimed()) {
                if ("pending".equals(claim.existingStatus())) {
                    this.sink.sendResult(msgId, rid, cmd, target, actor, "rejected", "E_IDEM_DUP", null,
                        "a command with this idemKey is already executing", 0L, false);
                    return;
                }
                // Terminal prior outcome: replay it (§8 - duplicates return the
                // first result), marked duplicate:true, never re-executed.
                JsonObject d = null;
                try {
                    d = com.google.gson.JsonParser.parseString(claim.existingResultJson()).getAsJsonObject();
                    d.addProperty("duplicate", true);
                }
                catch (Exception ignored) {
                    d = new JsonObject();
                    d.addProperty("duplicate", true);
                    d.addProperty("priorStatus", claim.existingStatus());
                }
                this.sink.sendResult(msgId, rid, cmd, target, actor, "applied", null, d, null, 0L, true);
                return;
            }
            owned = true;
        }
        final boolean idemOwned = owned;
        final String finalIdemKey = idemKey;
        final Actor cmdActor = actor;
        Runnable task = () -> this.execute(msgId, rid, spec, target, reason, args, cmdActor, at, finalIdemKey, idemOwned);
        MinecraftServer server = this.agent.server();
        if (spec.affinity() == Affinity.SERVER) {
            if (server == null) {
                this.sink.sendResult(msgId, rid, cmd, target, actor, "rejected", "E_STATE", null, "server not ready", 0L, false);
                return;
            }
            server.execute(task);
        }
        else {
            this.agent.cloudExecutor().execute(task);
        }
    }

    private void execute(String msgId, String rid, Spec spec, String target, String reason,
                         JsonObject args, Actor actor, long at, String idemKey, boolean idemOwned) {
        Ctx ctx = new Ctx(spec.id(), target, reason == null ? "" : reason.trim(), actor, at);
        long t0 = System.currentTimeMillis();
        JsonObject data = null;
        String error = null;
        String code = null;
        String status;
        try {
            data = spec.handler().apply(ctx, args);
            status = "applied";
        }
        catch (CmdError e) {
            status = "rejected";
            code = e.code;
            error = e.getMessage();
        }
        catch (Exception e) {
            status = "failed";
            error = e.toString();
            SolidusAnalyticsMod.LOGGER.error("[Cloud] command '{}' failed", (Object)spec.id(), (Object)e);
        }
        long tookMs = System.currentTimeMillis() - t0;
        // B-2 fix: finalize the claim with the FIRST terminal outcome so later
        // duplicates replay this result instead of re-executing (§8). This also
        // covers rejections: a retried frame with the same idemKey gets the same
        // answer deterministically instead of a second Core write attempt.
        if (idemOwned && idemKey != null) {
            JsonObject stored = data == null ? new JsonObject() : data;
            this.agent.store().finalizeIdempotent(idemKey, spec.id(), status, stored.toString());
        }
        this.agent.store().logCommand(System.currentTimeMillis(), rid, spec.id(), ctx.target(),
            actor.uid(), actor.name(), actor.role(), status, code,
            data == null ? null : data.toString(), idemKey);
        this.sink.sendResult(msgId, rid, spec.id(), ctx.target(), actor, status, code, data, error, tookMs, false);
    }

    // ---- shared helpers ---------------------------------------------------

    static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    static String requireName(JsonObject args, String key) throws CmdError {
        String v = str(args, key);
        if (v == null || !SAFE_NAME.matcher(v).matches()) {
            throw new CmdError("E_ARGS", "invalid player name for '" + key + "'");
        }
        return v;
    }

    /**
     * B-4 fix: every name interpolated into a pre-templated vanilla command
     * (G1 Console path) passes through here. The javadoc contract says names
     * are validated against {@code ^[A-Za-z0-9_]{1,16}$} BEFORE reaching the
     * dispatcher - on offline-mode servers a crafted profile name like
     * {@code @a} would otherwise turn {@code kick <t>} into a selector and
     * kick/ban the entire server. Envelope targets are wire-controlled, so
     * they are validated here rather than trusted.
     */
    static String consoleTarget(String target) throws CmdError {
        if (target == null || !SAFE_NAME.matcher(target).matches()) {
            throw new CmdError("E_ARGS", "target is not a safe player name for the console path");
        }
        return target;
    }

    /**
     * B-8 fix: the relay-side W2 typed-name confirmation binds the ENVELOPE
     * {@code target} (PROTOCOL §7: confirm.typed must equal target). The econ
     * handlers historically executed on {@code args.target} - a frame could
     * confirm one name and move another account's money. Enforce that when
     * {@code args.target} is present it matches the envelope target exactly,
     * then use that (validated) name.
     */
    static String confirmedTarget(Ctx ctx, JsonObject args, String key) throws CmdError {
        String argTarget = str(args, key);
        String envelopeTarget = ctx.target() == null ? "" : ctx.target().trim();
        if (argTarget != null && !argTarget.isBlank() && !argTarget.trim().equals(envelopeTarget)) {
            throw new CmdError("E_ARGS", "args.target must equal the envelope target (confirmed name)");
        }
        return requireName(args, key);
    }

    /** B-10 fix: reads the live player list on the server thread only. */
    List<String> snapshotOnlineNames() throws CmdError {
        MinecraftServer server = this.agent.server();
        if (server == null) {
            throw new CmdError("E_STATE", "server not ready");
        }
        try {
            java.util.concurrent.CompletableFuture<List<String>> future = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    java.util.ArrayList<String> names = new java.util.ArrayList<>();
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        names.add(p.getGameProfile().name());
                    }
                    future.complete(names);
                }
                catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(10L, java.util.concurrent.TimeUnit.SECONDS);
        }
        catch (Exception e) {
            throw new CmdError("E_EXEC", "could not snapshot online players: " + e);
        }
    }

    static long requireAmountC(JsonObject args, String key, long max) throws CmdError {
        if (!args.has(key) || !args.get(key).isJsonPrimitive()) {
            throw new CmdError("E_ARGS", "missing integer field '" + key + "'");
        }
        try {
            long v = args.get(key).getAsLong();
            if (v <= 0L || v > max) {
                throw new CmdError("E_ARGS", key + " out of range 1.." + max);
            }
            return v;
        }
        catch (CmdError e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new CmdError("E_ARGS", key + " must be an integer (cents)");
        }
    }

    static String sanitizeFree(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\n', ' ').replace('\r', ' ').trim().substring(0, Math.min(maxLen, raw.length()));
    }

    ServerPlayer onlineExact(MinecraftServer server, String name) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    void requireKnown(String name) throws CmdError {
        ServerPlayer online = this.onlineExact(this.agent.server(), name);
        if (online != null) {
            return;
        }
        if (!this.agent.economy().isKnownPlayer(name)) {
            throw new CmdError("E_NO_SUCH_PLAYER", "target is not a known player on this server");
        }
    }

    String resolveUuid(String name) throws CmdError {
        ServerPlayer online = this.onlineExact(this.agent.server(), name);
        if (online != null) {
            return String.valueOf(online.getGameProfile().id());
        }
        String uuid = this.agent.economy().uuidForName(name);
        if (uuid == null) {
            throw new CmdError("E_NO_SUCH_PLAYER", "cannot resolve uuid for target");
        }
        return uuid;
    }

    /** Pre-templated vanilla command through the server's own command source (G1). */
    int console(MinecraftServer server, String cmd) throws CmdError {
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            return 1;
        }
        catch (Exception e) {
            throw new CmdError("E_EXEC", "vanilla command rejected: " + e.getMessage());
        }
    }

    // ---- registration ------------------------------------------------------

    private void registerAll() {
        CloudAgent a = this.agent;
        // === health & players queries ===
        this.reg("health.tps", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.telemetry().tpsQuery());
        this.reg("health.ram", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.telemetry().ramQuery());
        this.reg("health.cpu", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.telemetry().cpuQuery());
        this.reg("health.meta", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.telemetry().meta(a.server(), a.agentVersion(), 0));
        this.reg("health.world", Risk.R, false, "viewer", Affinity.SERVER, (ctx, args) -> a.telemetry().world(a.server()));
        this.reg("health.entities", Risk.R, false, "viewer", Affinity.SERVER, (ctx, args) -> a.telemetry().entitiesTop(a.server()));
        this.reg("health.disk", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.diskStats());
        this.reg("players.list", Risk.R, false, "viewer", Affinity.SERVER, (ctx, args) -> {
            JsonObject d = new JsonObject();
            JsonArray arr = new JsonArray();
            for (ServerPlayer p : a.server().getPlayerList().getPlayers()) {
                arr.add(p.getGameProfile().name());
            }
            d.add("players", arr);
            d.addProperty("max", a.server().getPlayerList().getMaxPlayers());
            return d;
        });
        this.reg("player.profile", Risk.R, false, "viewer", Affinity.ASYNC,
            (ctx, args) -> a.economy().playerProfile(ctx.target(), a.veto()));
        this.reg("player.inspect", Risk.R, false, "admin", Affinity.SERVER, (ctx, args) -> {
            ServerPlayer p = this.onlineExact(a.server(), ctx.target());
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online");
            }
            boolean reveal = args.has("reveal") && args.get("reveal").getAsBoolean();
            JsonObject d = new JsonObject();
            d.addProperty("name", ctx.target());
            d.addProperty("pos", p.getX() + " " + p.getY() + " " + p.getZ());
            d.addProperty("level", this.safeLevel(p));
            d.addProperty("mode", a.telemetry().gameMode(p));
            d.addProperty("health", (double)p.getHealth());
            d.addProperty("food", p.getFoodData().getFoodLevel());
            d.addProperty("inv", this.inventorySummary(p));
            d.addProperty("ip", reveal ? a.telemetry().fullIp(p) : a.telemetry().maskedIp(p));
            if (reveal) {
                a.store().logCommand(System.currentTimeMillis(), ctx.cmd() + ":ip.reveal", ctx.cmd(), ctx.target(),
                    ctx.actor().uid(), ctx.actor().name(), ctx.actor().role(), "applied", null, null, null);
            }
            return d;
        });
        // === player moderation (Console path, templated) ===
        this.reg("player.kick", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            String target = consoleTarget(ctx.target());
            ServerPlayer p = this.onlineExact(a.server(), target);
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online");
            }
            this.console(a.server(), "kick " + target + " " + sanitizeFree(ctx.reason(), 128));
            return ok();
        });
        this.reg("player.ban", Risk.W2, false, "admin", Affinity.SERVER, (ctx, args) -> {
            String target = consoleTarget(ctx.target());
            this.requireKnown(target);
            this.console(a.server(), "ban " + target + " " + sanitizeFree(ctx.reason(), 128));
            return ok();
        });
        this.reg("player.ban.ip", Risk.W2, false, "admin", Affinity.SERVER, (ctx, args) -> {
            String target = consoleTarget(ctx.target());
            ServerPlayer p = this.onlineExact(a.server(), target);
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online (IP ban needs a session)");
            }
            this.console(a.server(), "ban-ip " + target + " " + sanitizeFree(ctx.reason(), 128));
            return ok();
        });
        this.reg("player.unban", Risk.W2, false, "admin", Affinity.SERVER, (ctx, args) -> {
            String target = consoleTarget(ctx.target());
            this.console(a.server(), "pardon " + target);
            return ok();
        });
        this.reg("player.gamemode", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            String mode = str(args, "mode");
            if (mode == null || !List.of("survival", "creative", "adventure", "spectator").contains(mode)) {
                throw new CmdError("E_ARGS", "mode must be survival|creative|adventure|spectator");
            }
            String target = consoleTarget(ctx.target());
            ServerPlayer p = this.onlineExact(a.server(), target);
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online");
            }
            this.console(a.server(), "gamemode " + mode + " " + target);
            return ok();
        });
        this.reg("player.heal", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            ServerPlayer p = this.onlineExact(a.server(), ctx.target());
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online");
            }
            p.setHealth(p.getMaxHealth());
            p.getFoodData().eat(20, 20.0f);
            return ok();
        });
        this.reg("player.feed", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            ServerPlayer p = this.onlineExact(a.server(), ctx.target());
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online");
            }
            p.getFoodData().eat(20, 20.0f);
            return ok();
        });
        this.reg("player.give", Risk.W2, false, "admin", Affinity.SERVER, (ctx, args) -> {
            String item = str(args, "item");
            long qty = requireAmountC(args, "qty", 64);
            if (item == null || !SAFE_ITEM.matcher(item).matches()) {
                throw new CmdError("E_ARGS", "item must be a namespaced id like minecraft:diamond");
            }
            String target = consoleTarget(ctx.target());
            this.requireKnown(target);
            this.console(a.server(), "give " + target + " " + item + " " + qty);
            return ok();
        });
        this.reg("player.msg", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            String message = sanitizeFree(str(args, "message"), 512);
            if (message.isEmpty()) {
                throw new CmdError("E_ARGS", "message is required");
            }
            ServerPlayer p = this.onlineExact(a.server(), ctx.target());
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online");
            }
            p.sendSystemMessage(Component.literal("[Solidus Cloud] " + message));
            return ok();
        });
        this.reg("server.broadcast", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            String message = sanitizeFree(str(args, "message"), 512);
            String tier = str(args, "tier");
            if (message.isEmpty()) {
                throw new CmdError("E_ARGS", "message is required");
            }
            boolean opsOnly = "ops".equals(tier);
            net.minecraft.server.permissions.Permission opPermission =
                new net.minecraft.server.permissions.Permission.HasCommandLevel(net.minecraft.server.permissions.PermissionLevel.MODERATORS);
            Component text = Component.literal("[Solidus] " + message);
            int n = 0;
            for (ServerPlayer p : a.server().getPlayerList().getPlayers()) {
                if (!opsOnly || p.permissions().hasPermission(opPermission)) {
                    p.sendSystemMessage(text);
                    ++n;
                }
            }
            JsonObject d = ok();
            d.addProperty("reached", n);
            return d;
        });
        this.reg("whitelist.manage", Risk.W2, false, "admin", Affinity.SERVER, (ctx, args) -> {
            String action = str(args, "action");
            if (action == null || !List.of("add", "remove", "on", "off").contains(action)) {
                throw new CmdError("E_ARGS", "action must be add|remove|on|off");
            }
            if ("add".equals(action) || "remove".equals(action)) {
                String target = consoleTarget(ctx.target());
                this.requireKnown(target);
                this.console(a.server(), "whitelist " + action + " " + target);
            }
            else {
                this.console(a.server(), "whitelist " + action);
            }
            return ok();
        });
        this.reg("player.tp", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            String target = consoleTarget(ctx.target());
            ServerPlayer p = this.onlineExact(a.server(), target);
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online");
            }
            JsonObject to = args.has("to") && args.get("to").isJsonObject() ? args.getAsJsonObject("to") : null;
            String kind = to == null ? "spawn" : str(to, "kind");
            if (kind == null) {
                throw new CmdError("E_ARGS", "to.kind required");
            }
            switch (kind) {
                case "spawn" -> {
                    net.minecraft.core.BlockPos spawn = this.spawnPos(p);
                    this.console(a.server(), "tp " + target + " " + spawn.getX() + " " + spawn.getY() + " " + spawn.getZ());
                }
                case "coords" -> {
                    Double x = num(to, "x"), y = num(to, "y"), z = num(to, "z");
                    if (x == null || y == null || z == null) {
                        throw new CmdError("E_ARGS", "coords require x,y,z");
                    }
                    this.console(a.server(), "tp " + target + " " + x + " " + y + " " + z);
                }
                case "player" -> {
                    String other = str(to, "player");
                    if (other == null || !SAFE_NAME.matcher(other).matches()) {
                        throw new CmdError("E_ARGS", "to.player invalid");
                    }
                    this.console(a.server(), "tp " + target + " " + other);
                }
                default -> throw new CmdError("E_ARGS", "to.kind must be spawn|coords|player");
            }
            return ok();
        });
        this.reg("player.freeze", Risk.W2, false, "admin", Affinity.SERVER, (ctx, args) -> {
            ServerPlayer p = this.onlineExact(a.server(), ctx.target());
            if (p == null) {
                throw new CmdError("E_NO_SUCH_PLAYER", "player is not online");
            }
            String anchor = str(args, "anchor");
            a.anchor().freeze(p, "spawn".equals(anchor), ctx.reason(), ctx.actor().name());
            return ok();
        });
        this.reg("player.unfreeze", Risk.W2, false, "admin", Affinity.SERVER, (ctx, args) -> {
            if (!a.anchor().unfreeze(ctx.target())) {
                throw new CmdError("E_STATE", "player is not movement-frozen");
            }
            return ok();
        });
        // === economy: queries ===
        this.reg("econ.top", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().econTop());
        this.reg("econ.supply", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().econSupply());
        this.reg("econ.distribution", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().econDistribution());
        this.reg("econ.flow", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().econFlow());
        this.reg("econ.inflation", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().econInflation());
        this.reg("econ.notifications", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().econNotifications());
        this.reg("econ.tx.search", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().txSearch(
            str(args, "type"), str(args, "player"), str(args, "material"),
            optLong(args, "minC"), optLong(args, "maxC"), optLong(args, "sinceMs"),
            args.has("limit") ? args.get("limit").getAsInt() : 50));
        this.reg("market.auctions.active", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().marketAuctionsActive());
        this.reg("market.auctions.sold", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().marketAuctionsSold());
        this.reg("market.shop.volume", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().marketShopVolume());
        this.reg("market.price.trend", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> a.economy().priceTrend(
            str(args, "material"), args.has("points") ? args.get("points").getAsInt() : 100));
        this.reg("territory.stats", Risk.R, false, "viewer", Affinity.ASYNC, (ctx, args) -> {
            JsonObject d = new JsonObject();
            d.add("claims", null);
            return d;
        });
        // === economy: money (API path, offline-capable, idempotent) ===
        this.reg("econ.grant", Risk.W2, true, "admin", Affinity.ASYNC, (ctx, args) -> {
            long amountC = requireAmountC(args, "amountC", MAX_AMOUNT_C);
            String name = confirmedTarget(ctx, args, "target");
            this.requireKnown(name);
            String uuid = this.resolveUuid(name);
            com.solidus.analytics.integration.SolidusIntegration api = com.solidus.analytics.integration.SolidusIntegration.getInstance();
            if (!com.solidus.analytics.integration.SolidusIntegration.isAvailable() || api == null) {
                throw new CmdError("E_CORE_MISSING", "Solidus Core is not loaded");
            }
            Double newBalance = api.addBalanceOffline(UUID.fromString(uuid), name, (double)amountC / 100.0, 5);
            if (newBalance == null) {
                // B-6 fix: a failed/timed-out Core write is NOT a success - acking
                // it as applied would poison the idempotency row and the audit.
                throw new CmdError("E_EXEC", "Core write failed - balance unchanged");
            }
            JsonObject d = ok();
            d.addProperty("balanceC", Math.round(newBalance * 100.0));
            return d;
        });
        this.reg("econ.deduct", Risk.W2, true, "admin", Affinity.ASYNC, (ctx, args) -> {
            long amountC = requireAmountC(args, "amountC", MAX_AMOUNT_C);
            String name = confirmedTarget(ctx, args, "target");
            this.requireKnown(name);
            String uuid = this.resolveUuid(name);
            com.solidus.analytics.integration.SolidusIntegration api = com.solidus.analytics.integration.SolidusIntegration.getInstance();
            if (!com.solidus.analytics.integration.SolidusIntegration.isAvailable() || api == null) {
                throw new CmdError("E_CORE_MISSING", "Solidus Core is not loaded");
            }
            Double current = api.getBalanceOffline(UUID.fromString(uuid), name, 5);
            if (current != null && current * 100.0 < (double)amountC) {
                throw new CmdError("E_STATE", "insufficient balance for deduction");
            }
            Double newBalance = api.subtractBalanceOffline(UUID.fromString(uuid), name, (double)amountC / 100.0, 5);
            if (newBalance == null) {
                throw new CmdError("E_EXEC", "Core write failed - balance unchanged");
            }
            JsonObject d = ok();
            d.addProperty("balanceC", Math.round(newBalance * 100.0));
            return d;
        });
        this.reg("econ.transfer", Risk.W2, true, "admin", Affinity.ASYNC, (ctx, args) -> {
            long amountC = requireAmountC(args, "amountC", MAX_AMOUNT_C);
            String from = confirmedTarget(ctx, args, "target");
            String to = requireName(args, "to");
            this.requireKnown(from);
            this.requireKnown(to);
            String fromUuid = this.resolveUuid(from);
            String toUuid = this.resolveUuid(to);
            com.solidus.analytics.integration.SolidusIntegration api = com.solidus.analytics.integration.SolidusIntegration.getInstance();
            if (!com.solidus.analytics.integration.SolidusIntegration.isAvailable() || api == null) {
                throw new CmdError("E_CORE_MISSING", "Solidus Core is not loaded");
            }
            Boolean done = api.transferOffline(UUID.fromString(fromUuid), from, UUID.fromString(toUuid), to, (double)amountC / 100.0, 5);
            if (done == null || !done.booleanValue()) {
                throw new CmdError("E_EXEC", "transfer rejected by Core");
            }
            return ok();
        });
        this.reg("econ.grant.all", Risk.W2, true, "admin", Affinity.ASYNC, (ctx, args) -> {
            long amountC = requireAmountC(args, "amountC", MAX_AMOUNT_C);
            long capC = requireAmountC(args, "capC", MAX_BATCH_CAP_C);
            String scope = str(args, "scope");
            if (scope == null || !List.of("online", "known").contains(scope)) {
                throw new CmdError("E_ARGS", "scope must be online|known");
            }
            // B-10 fix: the live player list may ONLY be iterated on the server
            // thread - reading it from a cloud worker risks a mid-grant CME
            // (partial payout, no idem row). Snapshot it via the server executor.
            List<String> names;
            if ("online".equals(scope)) {
                names = this.snapshotOnlineNames();
            }
            else {
                names = a.economy().knownPlayerNames(500);
            }
            if (names.isEmpty()) {
                throw new CmdError("E_STATE", "no players in scope");
            }
            long totalC = amountC * (long)names.size();
            if (totalC > capC) {
                throw new CmdError("E_ARGS", "aggregate " + totalC + "C exceeds batch cap " + capC + "C");
            }
            com.solidus.analytics.integration.SolidusIntegration api = com.solidus.analytics.integration.SolidusIntegration.getInstance();
            if (!com.solidus.analytics.integration.SolidusIntegration.isAvailable() || api == null) {
                throw new CmdError("E_CORE_MISSING", "Solidus Core is not loaded");
            }
            int granted = 0;
            for (String name : names) {
                String uuid = this.resolveUuid(name);
                if (uuid != null && api.addBalanceOffline(UUID.fromString(uuid), name, (double)amountC / 100.0, 5) != null) {
                    ++granted;
                }
            }
            JsonObject d = ok();
            d.addProperty("granted", granted);
            d.addProperty("totalC", amountC * (long)granted);
            return d;
        });
        // === veto state (Hook path) ===
        this.reg("econ.pause.global", Risk.D, false, "owner", Affinity.ASYNC, (ctx, args) -> {
            this.requireVetoHook();
            if (a.veto().getGlobalPause() != null) {
                throw new CmdError("E_STATE", "economy is already paused");
            }
            a.veto().setGlobalPause(new CloudVetoHook.PauseInfo(ctx.reason(), ctx.actor().name(), ctx.at()));
            this.sink.onVetoChanged();
            return ok();
        });
        this.reg("econ.resume.global", Risk.W2, false, "admin", Affinity.ASYNC, (ctx, args) -> {
            this.requireVetoHook();
            if (a.veto().getGlobalPause() == null) {
                throw new CmdError("E_STATE", "economy is not paused");
            }
            a.veto().setGlobalPause(null);
            this.sink.onVetoChanged();
            return ok();
        });
        this.reg("market.auction.pause", Risk.W2, false, "admin", Affinity.ASYNC, (ctx, args) -> {
            this.requireVetoHook();
            a.veto().setAuctionsPaused(new CloudVetoHook.PauseInfo(ctx.reason(), ctx.actor().name(), ctx.at()));
            this.sink.onVetoChanged();
            return ok();
        });
        this.reg("market.auction.resume", Risk.W2, false, "admin", Affinity.ASYNC, (ctx, args) -> {
            this.requireVetoHook();
            if (a.veto().getAuctionsPaused() == null) {
                throw new CmdError("E_STATE", "auctions are not paused");
            }
            a.veto().setAuctionsPaused(null);
            this.sink.onVetoChanged();
            return ok();
        });
        this.reg("market.shop.pause", Risk.W2, false, "admin", Affinity.ASYNC, (ctx, args) -> {
            this.requireVetoHook();
            a.veto().setShopPaused(new CloudVetoHook.PauseInfo(ctx.reason(), ctx.actor().name(), ctx.at()));
            this.sink.onVetoChanged();
            return ok();
        });
        this.reg("market.shop.resume", Risk.W2, false, "admin", Affinity.ASYNC, (ctx, args) -> {
            this.requireVetoHook();
            if (a.veto().getShopPaused() == null) {
                throw new CmdError("E_STATE", "shop is not paused");
            }
            a.veto().setShopPaused(null);
            this.sink.onVetoChanged();
            return ok();
        });
        this.reg("econ.freeze", Risk.W2, false, "admin", Affinity.ASYNC, (ctx, args) -> {
            String name = confirmedTarget(ctx, args, "target");
            this.requireKnown(name);
            this.requireVetoHook();
            String uuid = this.resolveUuid(name);
            a.veto().freeze(UUID.fromString(uuid), name,
                new CloudVetoHook.FreezeInfo(name, ctx.reason(), ctx.actor().name(), ctx.at()));
            this.sink.onVetoChanged();
            return ok();
        });
        this.reg("econ.unfreeze", Risk.W2, false, "admin", Affinity.ASYNC, (ctx, args) -> {
            String name = confirmedTarget(ctx, args, "target");
            this.requireVetoHook();
            String uuid = this.resolveUuid(name);
            if (!a.veto().unfreeze(UUID.fromString(uuid))) {
                throw new CmdError("E_STATE", "account is not frozen");
            }
            this.sink.onVetoChanged();
            return ok();
        });
        // === server lifecycle ===
        this.reg("server.save", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            this.console(a.server(), "save-all");
            return ok();
        });
        this.reg("server.broadcast.restart", Risk.W1, false, "mod", Affinity.SERVER, (ctx, args) -> {
            long delayS = args.has("delayS") ? args.get("delayS").getAsLong() : 60L;
            if (delayS < 30L || delayS > 300L) {
                throw new CmdError("E_ARGS", "delayS must be 30..300");
            }
            this.broadcast(a.server(), "Server restarting in " + delayS + " seconds.");
            JsonObject d = ok();
            d.addProperty("delayS", delayS);
            return d;
        });
        this.reg("server.restart", Risk.D, false, "owner", Affinity.SERVER, (ctx, args) -> this.destructiveStop(ctx, true));
        this.reg("server.stop", Risk.D, false, "owner", Affinity.SERVER, (ctx, args) -> this.destructiveStop(ctx, false));
        this.reg("server.backup.local", Risk.W1, false, "admin", Affinity.ASYNC, (ctx, args) -> a.backupNow(
            !args.has("worlds") || args.get("worlds").getAsBoolean(),
            !args.has("dbs") || args.get("dbs").getAsBoolean()));
        this.reg("server.backup.list", Risk.R, false, "admin", Affinity.ASYNC, (ctx, args) -> a.backupList());
        this.reg("server.backup.prune", Risk.W1, false, "admin", Affinity.ASYNC, (ctx, args) -> {
            long keepDays = args.has("keepDays") ? args.get("keepDays").getAsLong() : 30L;
            if (keepDays < 1L || keepDays > 365L) {
                throw new CmdError("E_ARGS", "keepDays must be 1..365");
            }
            return a.backupPrune(keepDays);
        });
        this.reg("pairing.rotate", Risk.W2, false, "owner", Affinity.ASYNC, (ctx, args) -> {
            // B-1 fix: NEVER put the secret in the result data - results are
            // persisted to cloud_command_log (local mirror), the relay's audit
            // ledger (§12) and broadcast as cmd.audit to every client (§6.7).
            // An admin reading any of those would otherwise walk away with the
            // agent credential. The secret travels only via the local 0600
            // config file and one server-side log line the owner controls.
            a.rotatePairingSecret();
            JsonObject d = ok();
            d.addProperty("serverId", a.config().getServerId());
            d.addProperty("rotated", true);
            d.addProperty("note", "new secret written to cloud.properties and the server log (owner-only); update the relay pairing within 120s - the old secret is dead");
            return d;
        });
        // === documented Core+ gaps: capability-absent answers ===
        for (String gap : new String[]{
                "econ.rollback.tx", "market.auction.cancel", "market.auctions.cancel.bulk",
                "market.shop.price.set", "market.item.cap", "market.item.ban",
                "solidus.reload", "gov.tax.run", "gov.tax.config", "gov.rollback.window"}) {
            this.reg(gap, Risk.W2, false, "admin", Affinity.ASYNC, (ctx, args) -> {
                throw new CmdError("E_CORE_MISSING", "requires Core 2.2 / Governance bridge (see PROTOCOL.md Appendix B)");
            });
        }
        // gov.freeze.global is the SAME breaker as econ.pause.global (one truth)
        this.reg("gov.freeze.global", Risk.D, false, "owner", Affinity.ASYNC, (ctx, args) -> {
            this.requireVetoHook();
            if (a.veto().getGlobalPause() != null) {
                throw new CmdError("E_STATE", "economy is already paused");
            }
            a.veto().setGlobalPause(new CloudVetoHook.PauseInfo(ctx.reason(), ctx.actor().name(), ctx.at()));
            this.sink.onVetoChanged();
            return ok();
        });
    }

    /** B-9 fix: pause/freeze commands must only report applied when the veto
     *  hook is actually registered into Core - otherwise the cloud UI shows a
     *  circuit breaker as active while nothing is enforced on the server. */
    private void requireVetoHook() throws CmdError {
        if (!this.agent.veto().isHookActive()) {
            throw new CmdError("E_CORE_MISSING",
                "veto hook is not registered into Solidus Core - pause/freeze would not be enforced");
        }
    }

    private JsonObject destructiveStop(Ctx ctx, boolean restart) throws CmdError {
        CloudAgent a = this.agent;
        if (restart && !a.config().isRestartCapable()) {
            throw new CmdError("E_STATE", "restart capability not proven on this host (button must stay hidden)");
        }
        this.console(a.server(), "save-all");
        this.broadcast(a.server(), restart
            ? "Server restarting now (" + ctx.reason() + ")"
            : "Server stopping now (" + ctx.reason() + ")");
        JsonObject d = ok();
        d.addProperty("action", restart ? "restart" : "stop");
        a.cloudExecutor().schedule(() -> a.server().halt(false), 3L, java.util.concurrent.TimeUnit.SECONDS);
        return d;
    }

    private void broadcast(MinecraftServer server, String message) {
        Component text = Component.literal("[Solidus] " + message);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(text);
        }
    }

    private String safeLevel(ServerPlayer p) {
        try {
            return p.level().dimension().identifier().toString();
        }
        catch (Throwable t) {
            return null;
        }
    }

    /** World spawn block position (level respawn data, version-safe). */
    private net.minecraft.core.BlockPos spawnPos(ServerPlayer p) {
        try {
            return p.level().getLevelData().getRespawnData().pos();
        }
        catch (Throwable t) {
            return p.blockPosition();
        }
    }

    private String inventorySummary(ServerPlayer p) {
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (net.minecraft.world.item.ItemStack stack : p.getInventory().getNonEquipmentItems()) {
                if (stack == null || stack.getCount() <= 0) {
                    continue;
                }
                String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
                if (n++ > 0) {
                    sb.append(", ");
                }
                sb.append(id).append("x").append(stack.getCount());
                if (n >= 36) {
                    sb.append("…");
                    break;
                }
            }
            return sb.toString();
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static Double num(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? (double)o.get(key).getAsLong() : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static Long optLong(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject ok() {
        JsonObject d = new JsonObject();
        d.addProperty("ok", true);
        return d;
    }

    private void reg(String id, Risk risk, boolean financial, String minRole, Affinity affinity, Handler handler) {
        this.registry.put(id, new Spec(id, risk, financial, minRole, affinity, handler));
    }
}
