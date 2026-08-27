/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.analytics;

import com.solidus.analytics.SolidusAnalyticsMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class AnalyticsConfig {
    private static final int DEFAULT_SNAPSHOT_INTERVAL_MINUTES = 30;
    private static final int DEFAULT_POLLING_INTERVAL_SECONDS = 30;
    private static final int DEFAULT_DATA_RETENTION_DAYS = 90;
    private static final int DEFAULT_CLEANUP_INTERVAL_HOURS = 24;
    private final Path configPath;
    private final Properties properties;
    private int snapshotIntervalMinutes = 30;
    private int pollingIntervalSeconds = 30;
    private int dataRetentionDays = 90;
    private int cleanupIntervalHours = 24;
    private String discordWebhookUrl = "";
    private boolean discordEnabled = false;
    private boolean notifyFraud = true;
    private boolean notifyInflation = true;
    private boolean notifyDailySummary = true;
    private boolean notifyHealthScore = true;
    private double healthScoreAlertThreshold = 50.0;
    private String fraudMinSeverity = "HIGH";

    public AnalyticsConfig(Path configDir) {
        this.configPath = configDir.resolve("analytics.properties");
        this.properties = new Properties();
    }

    public void load() {
        if (Files.exists(this.configPath, new LinkOption[0])) {
            try (InputStream is = Files.newInputStream(this.configPath, new OpenOption[0]);){
                this.properties.load(is);
                SolidusAnalyticsMod.LOGGER.info("Loaded config from {}", (Object)this.configPath);
            }
            catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to load config, using defaults", (Throwable)e);
            }
        } else {
            SolidusAnalyticsMod.LOGGER.info("No config file found. Creating default config at {}", (Object)this.configPath);
            this.createDefaultConfig();
        }
        this.snapshotIntervalMinutes = this.getInt("snapshot.interval.minutes", 30);
        this.pollingIntervalSeconds = this.getInt("polling.interval.seconds", 30);
        this.dataRetentionDays = this.getInt("data.retention.days", 90);
        this.cleanupIntervalHours = this.getInt("cleanup.interval.hours", 24);
        this.discordWebhookUrl = this.properties.getProperty("discord.webhook.url", "");
        this.discordEnabled = this.getBoolean("discord.enabled", false);
        this.notifyFraud = this.getBoolean("discord.notify.fraud", true);
        this.notifyInflation = this.getBoolean("discord.notify.inflation", true);
        this.notifyDailySummary = this.getBoolean("discord.notify.daily_summary", true);
        this.notifyHealthScore = this.getBoolean("discord.notify.health_score", true);
        this.healthScoreAlertThreshold = this.getDouble("discord.health_score.threshold", 50.0);
        this.fraudMinSeverity = this.properties.getProperty("discord.fraud.min_severity", "HIGH");
        this.validateAndNormalize();
    }

    public void save() {
        try (OutputStream os = Files.newOutputStream(this.configPath, new OpenOption[0]);){
            this.properties.store(os, "Solidus Analytics Configuration\nModifying this file while the server is running may not take effect until restart.");
            SolidusAnalyticsMod.LOGGER.info("Saved config to {}", (Object)this.configPath);
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to save config", (Throwable)e);
        }
    }

    private void createDefaultConfig() {
        Properties defaults = new Properties();
        defaults.setProperty("snapshot.interval.minutes", String.valueOf(30));
        defaults.setProperty("polling.interval.seconds", String.valueOf(30));
        defaults.setProperty("data.retention.days", String.valueOf(90));
        defaults.setProperty("cleanup.interval.hours", String.valueOf(24));
        defaults.setProperty("discord.enabled", "false");
        defaults.setProperty("discord.webhook.url", "");
        defaults.setProperty("discord.notify.fraud", "true");
        defaults.setProperty("discord.notify.inflation", "true");
        defaults.setProperty("discord.notify.daily_summary", "true");
        defaults.setProperty("discord.notify.health_score", "true");
        defaults.setProperty("discord.health_score.threshold", "50.0");
        defaults.setProperty("discord.fraud.min_severity", "HIGH");
        try {
            Files.createDirectories(this.configPath.getParent(), new FileAttribute[0]);
            try (OutputStream os = Files.newOutputStream(this.configPath, new OpenOption[0]);){
                defaults.store(os, "Solidus Analytics Configuration\n================================\nThis file controls the behavior of Solidus Analytics.\nChanges take effect after server restart unless noted otherwise.\n\nSnapshot Settings:\n  snapshot.interval.minutes \u2014 How often to take economy snapshots (default: 30)\n\nPolling Settings:\n  polling.interval.seconds \u2014 How often to check for new transactions (default: 30)\n\nData Retention:\n  data.retention.days \u2014 How many days of data to keep (default: 90)\n  cleanup.interval.hours \u2014 How often to run data cleanup (default: 24)\n\nDiscord Integration (Premium):\n  discord.enabled \u2014 Enable/disable Discord notifications (default: false)\n  discord.webhook.url \u2014 Your Discord webhook URL\n  discord.notify.fraud \u2014 Send fraud alerts (default: true)\n  discord.notify.inflation \u2014 Send inflation warnings (default: true)\n  discord.notify.daily_summary \u2014 Send daily economy summaries (default: true)\n  discord.notify.health_score \u2014 Send health score alerts (default: true)\n  discord.health_score.threshold \u2014 Alert when score drops below this (default: 50.0)\n  discord.fraud.min_severity \u2014 Min severity for fraud alerts: LOW, MEDIUM, HIGH (default: HIGH)\n\nPremium Features:\n  Premium is controlled by the license key file, not this config.\n  Place your license key in: config/solidus-analytics/license.key\n");
            }
            this.properties.putAll((Map<?, ?>)defaults);
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to create default config file", (Throwable)e);
        }
    }

    private void validateAndNormalize() {
        this.snapshotIntervalMinutes = Math.max(1, this.snapshotIntervalMinutes);
        this.pollingIntervalSeconds = Math.max(5, this.pollingIntervalSeconds);
        this.dataRetentionDays = Math.max(1, this.dataRetentionDays);
        this.cleanupIntervalHours = Math.max(1, this.cleanupIntervalHours);
        if (Double.isNaN(this.healthScoreAlertThreshold) || Double.isInfinite(this.healthScoreAlertThreshold)) {
            this.healthScoreAlertThreshold = 50.0;
        }
        this.healthScoreAlertThreshold = Math.max(0.0, Math.min(100.0, this.healthScoreAlertThreshold));
        this.fraudMinSeverity = this.fraudMinSeverity == null ? "HIGH" : this.fraudMinSeverity.trim().toUpperCase(Locale.ROOT);
        if (!this.fraudMinSeverity.equals("LOW") && !this.fraudMinSeverity.equals("MEDIUM") && !this.fraudMinSeverity.equals("HIGH")) {
            this.fraudMinSeverity = "HIGH";
        }
        if (this.discordEnabled && !isAllowedDiscordWebhook(this.discordWebhookUrl)) {
            SolidusAnalyticsMod.LOGGER.warn("Discord notifications disabled: webhook URL is not an allowed HTTPS Discord webhook.");
            this.discordEnabled = false;
        }
    }

    private static boolean isAllowedDiscordWebhook(String url) {
        return url != null && (url.startsWith("https://discord.com/api/webhooks/") || url.startsWith("https://discordapp.com/api/webhooks/"));
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

    private double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(this.properties.getProperty(key, String.valueOf(defaultValue)));
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public synchronized int getSnapshotIntervalMinutes() {
        return this.snapshotIntervalMinutes;
    }

    public synchronized int getPollingIntervalSeconds() {
        return this.pollingIntervalSeconds;
    }

    public synchronized int getDataRetentionDays() {
        return this.dataRetentionDays;
    }

    public synchronized int getCleanupIntervalHours() {
        return this.cleanupIntervalHours;
    }

    public synchronized String getDiscordWebhookUrl() {
        return this.discordWebhookUrl;
    }

    public synchronized boolean isDiscordEnabled() {
        return this.discordEnabled;
    }

    public synchronized boolean isNotifyFraud() {
        return this.notifyFraud;
    }

    public synchronized boolean isNotifyInflation() {
        return this.notifyInflation;
    }

    public synchronized boolean isNotifyDailySummary() {
        return this.notifyDailySummary;
    }

    public synchronized boolean isNotifyHealthScore() {
        return this.notifyHealthScore;
    }

    public synchronized double getHealthScoreAlertThreshold() {
        return this.healthScoreAlertThreshold;
    }

    public synchronized String getFraudMinSeverity() {
        return this.fraudMinSeverity;
    }

    public synchronized void setSnapshotIntervalMinutes(int minutes) {
        this.snapshotIntervalMinutes = minutes;
        this.properties.setProperty("snapshot.interval.minutes", String.valueOf(minutes));
    }

    public synchronized void setDiscordWebhookUrl(String url) {
        this.discordWebhookUrl = url;
        this.properties.setProperty("discord.webhook.url", url);
    }

    public synchronized void setDiscordEnabled(boolean enabled) {
        this.discordEnabled = enabled;
        this.properties.setProperty("discord.enabled", String.valueOf(enabled));
    }
}
