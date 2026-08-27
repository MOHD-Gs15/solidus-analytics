/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.mojang.brigadier.CommandDispatcher
 *  net.fabricmc.api.DedicatedServerModInitializer
 *  net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
 *  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
 *  net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
 *  net.fabricmc.loader.api.FabricLoader
 *  net.minecraft.commands.CommandSourceStack
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.solidus.analytics;

import com.mojang.brigadier.CommandDispatcher;
import com.solidus.analytics.commands.AnalyticsCommand;
import com.solidus.analytics.commands.InflationCommand;
import com.solidus.analytics.commands.PremiumCommand;
import com.solidus.analytics.engine.AnalyticsEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolidusAnalyticsMod
implements DedicatedServerModInitializer {
    public static final String MOD_ID = "solidus-analytics";
    public static final String MOD_NAME = "Solidus Analytics";
    public static final Logger LOGGER = LoggerFactory.getLogger((String)"Solidus Analytics");
    private static volatile AnalyticsEngine analyticsEngine;

    public void onInitializeServer() {
        LOGGER.info("Solidus Analytics is initializing...");
        analyticsEngine = new AnalyticsEngine();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
            try {
                Files.createDirectories(configDir, new FileAttribute[0]);
            }
            catch (IOException e) {
                LOGGER.error("Failed to create config directory: {}", (Object)configDir, (Object)e);
            }
            analyticsEngine.initialize(configDir.toAbsolutePath().toString());
            LOGGER.info("Solidus Analytics initialized successfully.");
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (analyticsEngine.isInitialized()) {
                analyticsEngine.onServerTick(server.getTickCount());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AnalyticsCommand.register((CommandDispatcher<CommandSourceStack>)dispatcher, analyticsEngine);
            InflationCommand.register((CommandDispatcher<CommandSourceStack>)dispatcher, analyticsEngine);
            PremiumCommand.register((CommandDispatcher<CommandSourceStack>)dispatcher, analyticsEngine);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Solidus Analytics is shutting down...");
            analyticsEngine.shutdown();
            LOGGER.info("Solidus Analytics shutdown complete. All data saved.");
        });
        LOGGER.info("Solidus Analytics mod entry point registered. Engine will initialize after server start.");
    }

    public static AnalyticsEngine getAnalyticsEngine() {
        return analyticsEngine;
    }
}
