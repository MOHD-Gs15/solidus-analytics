package com.solidus.analytics.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solidus.analytics.SolidusAnalyticsMod;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * AnchorFreezeManager - movement freeze for {@code player.freeze}
 * (catalog "players" domain, approved decision: ships in v1).
 *
 * <p>Deliberately NO Mixin and no event-system surgery: when frozen, the
 * player's position is recorded as an anchor; every server tick the manager
 * checks the distance and teleports the player back to the anchor the moment
 * they drift beyond a 2-block radius. Cheap, version-stable, works on any
 * Fabric version, and cannot desync the movement code because it only ever
 * uses the same vanilla teleport the console would use.</p>
 */
public final class AnchorFreezeManager {
    private static final double RADIUS = 2.0;
    private static final long MESSAGE_INTERVAL_MS = 5000L;

    /** B-12 fix: the anchor now remembers the LEVEL OBJECT it was taken in.
     * Teleports always target that level, so a player escaping to another
     * dimension is pulled back to the correct dimension instead of being
     * teleported to overworld coordinates inside the wrong dimension (which
     * can bury them in blocks or drop them into the void, every tick). */
    private record Anchor(net.minecraft.server.level.ServerLevel level, String levelName,
                          double x, double y, double z, String reason, String by, long at, long lastMsgMs) {}

    private final ConcurrentHashMap<String, Anchor> anchors = new ConcurrentHashMap<String, Anchor>();

    public void freeze(ServerPlayer player, boolean anchorAtSpawn, String reason, String by) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        net.minecraft.server.level.ServerLevel level = player.level();
        if (anchorAtSpawn) {
            try {
                net.minecraft.core.BlockPos spawn = player.level().getLevelData().getRespawnData().pos();
                x = spawn.getX() + 0.5;
                y = spawn.getY();
                z = spawn.getZ() + 0.5;
            }
            catch (Throwable t) {
                // keep the current position as anchor
            }
        }
        this.anchors.put(player.getGameProfile().name(),
            new Anchor(level, this.levelName(player), x, y, z, reason == null ? "frozen" : reason, by == null ? "unknown" : by,
                System.currentTimeMillis(), 0L));
        player.sendSystemMessage(Component.literal("[Solidus] You have been frozen in place: " + (reason == null ? "" : reason)));
    }

    public boolean unfreeze(String name) {
        Anchor removed = this.anchors.remove(name);
        return removed != null;
    }

    public boolean isFrozen(String name) {
        return this.anchors.containsKey(name);
    }

    /** Called from the server tick thread. */
    public void onTick(MinecraftServer server) {
        if (this.anchors.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String playerName = player.getGameProfile().name();
            Anchor anchor = this.anchors.get(playerName);
            if (anchor == null) {
                continue;
            }
            String currentLevel = this.levelName(player);
            double dx = player.getX() - anchor.x();
            double dy = player.getY() - anchor.y();
            double dz = player.getZ() - anchor.z();
            boolean drifted = currentLevel == null || !currentLevel.equals(anchor.levelName())
                || dx * dx + dy * dy + dz * dz > RADIUS * RADIUS;
            if (!drifted) {
                continue;
            }
            try {
                // Teleport to the ANCHOR's level (correct dimension), never the
                // player's current (wrong) one - audit B-12.
                player.teleportTo(anchor.level(), anchor.x(), anchor.y(), anchor.z(),
                    java.util.Set.of(), player.getYRot(), player.getXRot(), false);
            }
            catch (Throwable t) {
                SolidusAnalyticsMod.LOGGER.debug("[Cloud] anchor teleport failed", (Throwable)t);
            }
            long now = System.currentTimeMillis();
            if (now - anchor.lastMsgMs() > MESSAGE_INTERVAL_MS) {
                this.anchors.put(playerName,
                    new Anchor(anchor.level(), anchor.levelName(), anchor.x(), anchor.y(), anchor.z(), anchor.reason(),
                        anchor.by(), anchor.at(), now));
                player.sendSystemMessage(Component.literal("[Solidus] You are frozen: " + anchor.reason()));
            }
        }
    }

    public JsonObject stateJson() {
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        this.anchors.forEach((name, anchor) -> {
            JsonObject f = new JsonObject();
            f.addProperty("n", name);
            f.addProperty("level", anchor.levelName());
            f.addProperty("x", anchor.x());
            f.addProperty("y", anchor.y());
            f.addProperty("z", anchor.z());
            f.addProperty("reason", anchor.reason());
            f.addProperty("by", anchor.by());
            f.addProperty("at", anchor.at());
            arr.add(f);
        });
        d.add("movementFrozen", arr);
        d.addProperty("count", arr.size());
        return d;
    }

    private String levelName(ServerPlayer player) {
        try {
            return player.level().dimension().identifier().toString();
        }
        catch (Throwable t) {
            return null;
        }
    }
}
