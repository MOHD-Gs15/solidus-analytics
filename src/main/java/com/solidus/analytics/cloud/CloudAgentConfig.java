package com.solidus.analytics.cloud;

import com.solidus.analytics.SolidusAnalyticsMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * CloudAgentConfig - configuration for the Solidus Cloud Agent.
 *
 * <p>Lives in {@code config/solidus-analytics/cloud.properties} — deliberately
 * OUTSIDE the jar so that pairing credentials can be rotated by rewriting a
 * file (approved catalog decision: never bake credentials into the artifact).</p>
 *
 * <p>On first boot the agent generates {@code serverId} (8 chars) and the
 * 64-hex {@code pairingSecret}, persists them, and logs the pairing block the
 * owner enters (once) in the cloud PWA. The relay stores only
 * SHA-256(pairingSecret).</p>
 */
public final class CloudAgentConfig {
    private static final int DEFAULT_FAST_INTERVAL_SECONDS = 10;
    private static final int DEFAULT_ECONOMY_INTERVAL_SECONDS = 60;
    private static final int DEFAULT_SLOW_INTERVAL_SECONDS = 300;

    private final Path configPath;
    private final Properties properties = new Properties();

    private boolean enabled = false;
    private String relayUrl = "wss://relay.solidus.example/agent";
    private String serverId = "";
    private String pairingSecret = "";
    private String displayName = "";
    private int fastIntervalSeconds = DEFAULT_FAST_INTERVAL_SECONDS;
    private int economyIntervalSeconds = DEFAULT_ECONOMY_INTERVAL_SECONDS;
    private int slowIntervalSeconds = DEFAULT_SLOW_INTERVAL_SECONDS;
    private boolean restartCapable = false;
    private String pinSha256 = "";

    public CloudAgentConfig(Path configDir) {
        this.configPath = configDir.resolve("cloud.properties");
    }

    public void load() {
        if (Files.exists(this.configPath, new LinkOption[0])) {
            try (InputStream is = Files.newInputStream(this.configPath, new OpenOption[0]);) {
                this.properties.load(is);
                SolidusAnalyticsMod.LOGGER.info("[Cloud] Loaded config from {}", (Object)this.configPath);
            }
            catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to load cloud config, regenerating defaults", (Throwable)e);
            }
        }
        boolean fresh = this.properties.getProperty("cloud.serverId") == null;
        this.enabled = this.getBoolean("cloud.enabled", false);
        this.relayUrl = this.properties.getProperty("cloud.relayUrl", "wss://relay.solidus.example/agent").trim();
        this.serverId = this.properties.getProperty("cloud.serverId", "").trim();
        this.pairingSecret = this.properties.getProperty("cloud.pairingSecret", "").trim();
        this.displayName = this.properties.getProperty("cloud.displayName", "").trim();
        this.fastIntervalSeconds = Math.max(5, this.getInt("cloud.fastIntervalSeconds", DEFAULT_FAST_INTERVAL_SECONDS));
        this.economyIntervalSeconds = Math.max(30, this.getInt("cloud.economyIntervalSeconds", DEFAULT_ECONOMY_INTERVAL_SECONDS));
        this.slowIntervalSeconds = Math.max(60, this.getInt("cloud.slowIntervalSeconds", DEFAULT_SLOW_INTERVAL_SECONDS));
        this.restartCapable = this.getBoolean("cloud.restartCapable", false);
        this.pinSha256 = this.properties.getProperty("cloud.pinSha256", "").trim();
        if (this.serverId.isEmpty()) {
            this.serverId = randomToken(8);
            this.properties.setProperty("cloud.serverId", this.serverId);
        }
        if (this.pairingSecret.isEmpty()) {
            this.pairingSecret = randomToken(64);
            this.properties.setProperty("cloud.pairingSecret", this.pairingSecret);
        }
        if (this.displayName.isEmpty()) {
            this.displayName = this.serverId;
            this.properties.setProperty("cloud.displayName", this.displayName);
        }
        if (fresh) {
            this.save();
            SolidusAnalyticsMod.LOGGER.info("===============================================================");
            SolidusAnalyticsMod.LOGGER.info("[Cloud] First-boot pairing credentials generated");
            SolidusAnalyticsMod.LOGGER.info("[Cloud]   serverId       : {}", (Object)this.serverId);
            SolidusAnalyticsMod.LOGGER.info("[Cloud]   pairingSecret : {}", (Object)this.pairingSecret);
            SolidusAnalyticsMod.LOGGER.info("[Cloud] Enter these once in the Solidus Cloud PWA to pair this server.");
            SolidusAnalyticsMod.LOGGER.info("[Cloud] The relay stores only SHA-256(secret); the plaintext never leaves this file.");
            SolidusAnalyticsMod.LOGGER.info("===============================================================");
        }
        if (!this.relayUrl.startsWith("wss://") && !this.relayUrl.startsWith("ws://")) {
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] relayUrl does not look like a ws:// or wss:// URL: {}", (Object)this.relayUrl);
        }
    }

    public synchronized void save() {
        try (OutputStream os = Files.newOutputStream(this.configPath, new OpenOption[0]);) {
            this.properties.setProperty("cloud.enabled", String.valueOf(this.enabled));
            this.properties.setProperty("cloud.relayUrl", this.relayUrl);
            this.properties.setProperty("cloud.serverId", this.serverId);
            this.properties.setProperty("cloud.pairingSecret", this.pairingSecret);
            this.properties.setProperty("cloud.displayName", this.displayName);
            this.properties.setProperty("cloud.fastIntervalSeconds", String.valueOf(this.fastIntervalSeconds));
            this.properties.setProperty("cloud.economyIntervalSeconds", String.valueOf(this.economyIntervalSeconds));
            this.properties.setProperty("cloud.slowIntervalSeconds", String.valueOf(this.slowIntervalSeconds));
            this.properties.setProperty("cloud.restartCapable", String.valueOf(this.restartCapable));
            this.properties.setProperty("cloud.pinSha256", this.pinSha256);
            this.properties.store(os, "Solidus Cloud Agent configuration\n"
                + "cloud.enabled=false by default - flip to true after pairing.\n"
                + "cloud.relayUrl - the Solidus Cloud Relay agent endpoint (wss://.../agent).\n"
                + "cloud.serverId / cloud.pairingSecret - generated on first boot; rotate with pairing.rotate.\n"
                + "cloud.restartCapable - set true ONLY if your host auto-restarts a crashed/stopped server.\n"
                + "cloud.pinSha256 - optional SHA-256 of the relay certificate (hex, no colons).");
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to save cloud config", (Throwable)e);
        }
    }

    public synchronized void setPairingSecret(String newSecret) {
        this.pairingSecret = newSecret;
        this.properties.setProperty("cloud.pairingSecret", newSecret);
        this.save();
    }

    private static String randomToken(int len) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; ++i) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(this.properties.getProperty(key, String.valueOf(defaultValue)));
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(this.properties.getProperty(key, String.valueOf(defaultValue)));
    }

    public synchronized boolean isEnabled() {
        return this.enabled;
    }

    public synchronized String getRelayUrl() {
        return this.relayUrl;
    }

    public synchronized String getServerId() {
        return this.serverId;
    }

    public synchronized String getPairingSecret() {
        return this.pairingSecret;
    }

    public synchronized String getDisplayName() {
        return this.displayName;
    }

    public synchronized int getFastIntervalSeconds() {
        return this.fastIntervalSeconds;
    }

    public synchronized int getEconomyIntervalSeconds() {
        return this.economyIntervalSeconds;
    }

    public synchronized int getSlowIntervalSeconds() {
        return this.slowIntervalSeconds;
    }

    public synchronized boolean isRestartCapable() {
        return this.restartCapable;
    }

    public synchronized String getPinSha256() {
        return this.pinSha256;
    }
}
