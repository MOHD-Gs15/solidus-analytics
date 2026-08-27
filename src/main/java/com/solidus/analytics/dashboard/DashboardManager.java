/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.analytics.dashboard;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.dashboard.AnalyticsWebServer;
import com.solidus.analytics.dashboard.DashboardDataBuilder;
import com.solidus.analytics.dashboard.DashboardEncryption;
import com.solidus.analytics.dashboard.GitHubDataPublisher;
import com.solidus.analytics.engine.AnalyticsEngine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Properties;

public class DashboardManager {
    private final DashboardEncryption encryption;
    private final GitHubDataPublisher githubPublisher;
    private AnalyticsWebServer webServer;
    private volatile boolean githubPublishEnabled = false;
    private volatile boolean webServerEnabled = false;
    private volatile int publishIntervalSeconds = 60;
    private final AnalyticsEngine engine;
    private final Path configDir;
    private final Path dashboardConfigPath;
    private int tickCounter = 0;
    private int publishIntervalTicks = 1200;
    private final Properties dashboardProps;

    public DashboardManager(AnalyticsEngine engine, Path configDir) {
        this.engine = engine;
        this.configDir = configDir;
        this.dashboardConfigPath = configDir.resolve("dashboard.properties");
        this.encryption = new DashboardEncryption();
        this.githubPublisher = new GitHubDataPublisher();
        this.dashboardProps = new Properties();
    }

    public void initialize() {
        this.loadConfig();
        this.applyConfig();
        SolidusAnalyticsMod.LOGGER.info("Dashboard Manager initialized. GitHub: {} | Web Server: {} | Encryption: {}", new Object[]{this.githubPublishEnabled ? "ON" : "OFF", this.webServerEnabled ? "ON" : "OFF", this.encryption.isUnlocked() ? "UNLOCKED" : "LOCKED"});
    }

    public void onTick(int currentTick) {
        if (!this.githubPublishEnabled && !this.webServerEnabled) {
            return;
        }
        ++this.tickCounter;
        if (this.tickCounter >= this.publishIntervalTicks) {
            this.tickCounter = 0;
            this.engine.getDatabase().getExecutor().submit(this::publishData);
        }
    }

    public void publishData() {
        try {
            String jsonData = DashboardDataBuilder.buildJson(this.engine);
            if (this.githubPublishEnabled && this.githubPublisher.isEnabled()) {
                if (!this.encryption.isUnlocked()) {
                    SolidusAnalyticsMod.LOGGER.warn("Refusing to publish dashboard data: encryption is locked.");
                } else {
                    String encrypted = this.encryption.encrypt(jsonData);
                    if (encrypted != null) {
                        this.githubPublisher.publishAsync(encrypted);
                    }
                }
            }
            if (this.webServerEnabled && this.webServer != null) {
                this.webServer.updateData(jsonData);
            }
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to publish dashboard data", (Throwable)e);
        }
    }

    public void shutdown() {
        this.encryption.lock();
        this.githubPublisher.shutdown();
        if (this.webServer != null) {
            this.webServer.stop();
            this.webServer = null;
        }
        SolidusAnalyticsMod.LOGGER.info("Dashboard Manager shut down.");
    }

    private void loadConfig() {
        if (Files.exists(this.dashboardConfigPath, new LinkOption[0])) {
            try (InputStream is = Files.newInputStream(this.dashboardConfigPath, new OpenOption[0]);){
                this.dashboardProps.load(is);
            }
            catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to load dashboard config", (Throwable)e);
            }
        }
    }

    private void applyConfig() {
        this.githubPublishEnabled = this.getBool("github.enabled", false);
        String token = System.getenv().getOrDefault("SOLIDUS_GITHUB_TOKEN", "");
        if (this.dashboardProps.containsKey("github.token")) {
            SolidusAnalyticsMod.LOGGER.warn("Ignoring legacy github.token from dashboard.properties; use SOLIDUS_GITHUB_TOKEN instead.");
        }
        String owner = this.dashboardProps.getProperty("github.owner", "");
        String repo = this.dashboardProps.getProperty("github.repo", "");
        String branch = this.dashboardProps.getProperty("github.branch", "main");
        this.githubPublisher.configure(token, owner, repo, branch, this.githubPublishEnabled);
        this.webServerEnabled = this.getBool("webserver.enabled", false);
        int webPort = this.getInt("webserver.port", 9090);
        String webPassword = this.dashboardProps.getProperty("webserver.password_hash", "");
        if (this.webServerEnabled && webPassword.isBlank()) {
            SolidusAnalyticsMod.LOGGER.error("Refusing to start dashboard web server without a password hash.");
            this.webServerEnabled = false;
        }
        if (this.webServerEnabled) {
            try {
                this.webServer = new AnalyticsWebServer(this.engine, webPort, webPassword);
                this.webServer.start();
            }
            catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to start embedded web server", (Throwable)e);
                this.webServerEnabled = false;
            }
        }
        this.publishIntervalSeconds = Math.max(30, this.getInt("publish.interval.seconds", 60));
        this.publishIntervalTicks = Math.max(600, this.publishIntervalSeconds * 20);
        String passwordHash = this.dashboardProps.getProperty("encryption.password_hash", "");
        if (passwordHash.isBlank()) {
            SolidusAnalyticsMod.LOGGER.info("Dashboard encryption not set up. Use /analytics dashboard setup <password> to enable encryption.");
        } else {
            boolean autoUnlocked = this.tryAutoUnlock(passwordHash);
            if (!autoUnlocked) {
                SolidusAnalyticsMod.LOGGER.info("Dashboard encryption hash found. Use /analytics dashboard unlock <password> to unlock, or set SOLIDUS_DASHBOARD_PASSWORD env var for automatic unlock on restart.");
            }
        }
    }

    public void saveConfig() {
        try {
            Files.createDirectories(this.dashboardConfigPath.getParent(), new FileAttribute[0]);
            try (OutputStream os = Files.newOutputStream(this.dashboardConfigPath, new OpenOption[0]);){
                this.dashboardProps.store(os, "Solidus Analytics Dashboard Configuration\n=========================================\nGitHub Pages Mode (no VPS needed):\n  github.enabled \u2014 Enable GitHub Pages publishing (default: false)\n  github.owner   \u2014 Your GitHub username or organization\n  github.repo    \u2014 Repository name for the dashboard\n  github.token   — legacy setting; ignored. Set SOLIDUS_GITHUB_TOKEN in the server environment\n  github.branch  \u2014 Branch to publish to (default: main)\n\nEmbedded Web Server (VPS/dedicated server only):\n  webserver.enabled     \u2014 Enable embedded web server (default: false)\n  webserver.port        — Localhost port for the web server (default: 9090)\n  webserver.password_hash \u2014 Hashed password for web access\n\nEncryption:\n  encryption.password_hash — PBKDF2 password record (set via command, not manually)\n\nPublishing:\n  publish.interval.seconds \u2014 How often to update data (default: 60)\n");
            }
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to save dashboard config", (Throwable)e);
        }
    }

    public String setupEncryption(String password) {
        String hash = this.encryption.setupPassword(password.toCharArray());
        this.dashboardProps.setProperty("encryption.password_hash", hash);
        this.saveConfig();
        return "Dashboard encryption set up successfully. Your data will be encrypted before publishing.";
    }

    public String unlockEncryption(String password) {
        String storedHash = this.dashboardProps.getProperty("encryption.password_hash", "");
        if (storedHash.isBlank()) {
            return "Encryption is not set up yet. Use /analytics dashboard setup <password> first.";
        }
        if (this.encryption.unlock(password.toCharArray(), storedHash)) {
            return "Dashboard encryption unlocked. Data will be published encrypted.";
        }
        return "Incorrect password. Dashboard encryption remains locked.";
    }

    public String setupGitHub(String owner, String repo) {
        String token = System.getenv().getOrDefault("SOLIDUS_GITHUB_TOKEN", "");
        if (token.isBlank()) {
            return "GitHub publishing is not configured: set SOLIDUS_GITHUB_TOKEN in the server environment.";
        }
        if (!owner.matches("[A-Za-z0-9_.-]{1,39}") || !repo.matches("[A-Za-z0-9_.-]{1,100}")) {
            return "Invalid GitHub owner or repository name.";
        }
        this.dashboardProps.setProperty("github.enabled", "true");
        this.dashboardProps.remove("github.token");
        this.dashboardProps.setProperty("github.owner", owner);
        this.dashboardProps.setProperty("github.repo", repo);
        this.dashboardProps.setProperty("github.branch", "main");
        this.saveConfig();
        this.githubPublishEnabled = true;
        this.githubPublisher.configure(token, owner, repo, "main", true);
        return "GitHub Pages publishing configured for " + owner + "/" + repo + ". The token remains in the environment only.";
    }

    public String getEncryptionStatus() {
        if (!this.dashboardProps.containsKey("encryption.password_hash") || this.dashboardProps.getProperty("encryption.password_hash").isBlank()) {
            return "NOT SET UP \u2014 Use /analytics dashboard setup <password>";
        }
        return this.encryption.isUnlocked() ? "UNLOCKED" : "LOCKED \u2014 Use /analytics dashboard unlock <password>";
    }

    public String getGitHubStatus() {
        if (!this.githubPublishEnabled) {
            return "DISABLED";
        }
        return this.githubPublisher.isEnabled() ? "ACTIVE" : "MISCONFIGURED";
    }

    public String getWebServerStatus() {
        if (!this.webServerEnabled) {
            return "DISABLED";
        }
        if (this.webServer != null && this.webServer.isRunning()) {
            return "RUNNING on port " + this.webServer.getPort();
        }
        return "STOPPED";
    }

    public DashboardEncryption getEncryption() {
        return this.encryption;
    }

    public GitHubDataPublisher getGithubPublisher() {
        return this.githubPublisher;
    }

    public boolean isGithubPublishEnabled() {
        return this.githubPublishEnabled;
    }

    public boolean isWebServerEnabled() {
        return this.webServerEnabled;
    }

    private boolean tryAutoUnlock(String storedHash) {
        String envPassword = System.getenv("SOLIDUS_DASHBOARD_PASSWORD");
        if (envPassword != null && !envPassword.isBlank()) {
            if (this.encryption.unlock(envPassword.toCharArray(), storedHash)) {
                SolidusAnalyticsMod.LOGGER.info("Dashboard auto-unlocked via SOLIDUS_DASHBOARD_PASSWORD env var.");
                return true;
            }
            SolidusAnalyticsMod.LOGGER.warn("SOLIDUS_DASHBOARD_PASSWORD env var set but password incorrect. Falling back to manual unlock.");
            return false;
        }
        Path keyFile = this.configDir.resolve(".dashboard-key");
        if (Files.exists(keyFile, new LinkOption[0])) {
            try {
                String keyPassword = Files.readString(keyFile).trim();
                if (!keyPassword.isBlank()) {
                    if (this.encryption.unlock(keyPassword.toCharArray(), storedHash)) {
                        SolidusAnalyticsMod.LOGGER.info("Dashboard auto-unlocked via .dashboard-key file. Ensure this file has restricted permissions (chmod 600).");
                        return true;
                    }
                    SolidusAnalyticsMod.LOGGER.warn(".dashboard-key file found but password incorrect. Falling back to manual unlock.");
                }
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.warn("Failed to read .dashboard-key file", (Throwable)e);
            }
        }
        return false;
    }

    private boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(this.dashboardProps.getProperty(key, String.valueOf(defaultValue)));
    }

    private int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(this.dashboardProps.getProperty(key, String.valueOf(defaultValue)));
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
