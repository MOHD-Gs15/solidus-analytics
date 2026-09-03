package com.solidus.analytics.engine;

import com.solidus.analytics.AnalyticsConfig;
import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.cloud.CloudAgent;
import com.solidus.analytics.dashboard.DashboardManager;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.engine.SnapshotScheduler;
import com.solidus.analytics.integration.SolidusIntegration;
import com.solidus.analytics.license.LicenseVerifier;
import com.solidus.analytics.premium.DiscordWebhookNotifier;
import com.solidus.analytics.premium.EconomyHealthScore;
import com.solidus.analytics.premium.FraudDetector;
import com.solidus.analytics.premium.WeeklyReportGenerator;
import com.solidus.analytics.storage.AnalyticsDatabase;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public class AnalyticsEngine {
    private AnalyticsDatabase database;
    private LiveMetricsTracker liveMetrics;
    private SnapshotScheduler snapshotScheduler;
    private InflationCalculator inflationCalculator;
    private AnalyticsConfig config;
    private LicenseVerifier licenseVerifier;
    private EconomyHealthScore healthScore;
    private FraudDetector fraudDetector;
    private DiscordWebhookNotifier discordNotifier;
    private WeeklyReportGenerator weeklyReportGenerator;
    private DashboardManager dashboardManager;
    private CloudAgent cloudAgent;
    private com.solidus.analytics.dashboard.WealthDistributionProvider wealthDistributionProvider;
    private volatile net.minecraft.server.MinecraftServer attachedServer;
    private volatile boolean initialized = false;
    private volatile boolean premiumEnabled = false;
    private volatile boolean apiIntegrationAvailable = false;
    private String economyDbPath;
    private String auctionsDbPath;
    private Path configDirPath;
    private int cleanupTickCounter = 0;
    private static final int CLEANUP_INTERVAL_TICKS = 720000;

    public void initialize(String configDir) {
        SolidusAnalyticsMod.LOGGER.info("Initializing Solidus Analytics Engine...");
        this.configDirPath = Path.of(configDir, new String[0]);
        this.config = new AnalyticsConfig(this.configDirPath);
        this.config.load();
        this.apiIntegrationAvailable = SolidusIntegration.initialize();
        Path solidusConfigDir = FabricLoader.getInstance().getGameDir().resolve("config").resolve("solidus");
        this.economyDbPath = solidusConfigDir.resolve("economy.db").toAbsolutePath().toString();
        this.auctionsDbPath = solidusConfigDir.resolve("auctions.db").toAbsolutePath().toString();
        SolidusAnalyticsMod.LOGGER.info("Economy DB path: {}", (Object)this.economyDbPath);
        SolidusAnalyticsMod.LOGGER.info("Auctions DB path: {}", (Object)this.auctionsDbPath);
        if (SolidusIntegration.getInstance() != null) {
            SolidusIntegration.getInstance().setEconomyDbPath(this.economyDbPath);
        }
        this.database = new AnalyticsDatabase(configDir);
        this.database.initialize();
        if (!this.database.isInitialized()) {
            SolidusAnalyticsMod.LOGGER.error("Analytics database failed to initialize. Engine will not start.");
            return;
        }
        this.liveMetrics = new LiveMetricsTracker(this.database, this.economyDbPath);
        this.liveMetrics.setPollingIntervalSeconds(this.config.getPollingIntervalSeconds());
        this.liveMetrics.start();
        this.snapshotScheduler = new SnapshotScheduler(this.database, this.economyDbPath, this.auctionsDbPath);
        this.snapshotScheduler.setEngineRef(this);
        this.snapshotScheduler.setSnapshotIntervalMinutes(this.config.getSnapshotIntervalMinutes());
        this.wealthDistributionProvider = new com.solidus.analytics.dashboard.WealthDistributionProvider(this.economyDbPath);
        this.inflationCalculator = new InflationCalculator(this.database, this.economyDbPath, this.auctionsDbPath);
        // D-3 fix: WeeklyReportGenerator is a PREMIUM feature (ARCHITECTURE §10/§10.4) -
        // it is constructed only inside initializePremium(), exactly like healthScore
        // and fraudDetector. Without a license it stays null and every caller
        // (command + scheduler) already null-checks, so no code path can reach it.
        this.initializePremium(this.configDirPath);
        this.dashboardManager = new DashboardManager(this, this.configDirPath);
        this.dashboardManager.initialize();
        this.cloudAgent = new CloudAgent(this, this.configDirPath, FabricLoader.getInstance().getGameDir(),
            this.economyDbPath, this.auctionsDbPath, this.configDirPath.resolve("analytics.db").toAbsolutePath().toString());
        if (this.attachedServer != null) {
            this.cloudAgent.attachServer(this.attachedServer);
        }
        this.cloudAgent.start();
        this.initialized = true;
        SolidusAnalyticsMod.LOGGER.info("Solidus Analytics Engine initialized successfully.");
        SolidusAnalyticsMod.LOGGER.info("API Integration: {} | Mode: {}", (Object)(this.apiIntegrationAvailable ? "ACTIVE" : "UNAVAILABLE"), (Object)(this.apiIntegrationAvailable ? "Full Integration" : "Standalone (DB-only)"));
        SolidusAnalyticsMod.LOGGER.info("Premium Features: {}", (Object)(this.premiumEnabled ? "ENABLED" : "DISABLED"));
    }

    private void initializePremium(Path configDir) {
        this.licenseVerifier = new LicenseVerifier(configDir);
        LicenseVerifier.VerificationState state = this.licenseVerifier.initialize();
        this.premiumEnabled = this.licenseVerifier.isPremiumEnabled();
        if (this.premiumEnabled) {
            SolidusAnalyticsMod.LOGGER.info("Premium license verified. Activating premium features...");
            this.healthScore = new EconomyHealthScore(this);
            this.fraudDetector = new FraudDetector(this, this.economyDbPath);
            this.discordNotifier = new DiscordWebhookNotifier();
            this.weeklyReportGenerator = new WeeklyReportGenerator(this, this.configDirPath);
            if (this.config.isDiscordEnabled()) {
                this.discordNotifier.configure(this.config.getDiscordWebhookUrl(), true);
                this.discordNotifier.setNotifyFraud(this.config.isNotifyFraud());
                this.discordNotifier.setFraudMinSeverity(this.config.getFraudMinSeverity());
                this.discordNotifier.setNotifyInflationWarnings(this.config.isNotifyInflation());
                this.discordNotifier.setNotifyDailySummary(this.config.isNotifyDailySummary());
                this.discordNotifier.setNotifyHealthScore(this.config.isNotifyHealthScore());
                this.discordNotifier.setHealthScoreThreshold(this.config.getHealthScoreAlertThreshold());
            }
            this.database.getExecutor().submit(() -> {
                try {
                    this.fraudDetector.runAllChecks();
                }
                catch (Exception e) {
                    SolidusAnalyticsMod.LOGGER.error("Initial fraud scan failed", (Throwable)e);
                }
            });
        } else {
            SolidusAnalyticsMod.LOGGER.info("No valid premium license. Premium features disabled. State: {}", (Object)state);
        }
    }

    public void shutdown() {
        if (!this.initialized) {
            return;
        }
        SolidusAnalyticsMod.LOGGER.info("Shutting down Solidus Analytics Engine...");
        if (this.cloudAgent != null) {
            this.cloudAgent.shutdown();
        }
        if (this.dashboardManager != null) {
            this.dashboardManager.shutdown();
        }
        if (this.liveMetrics != null) {
            this.liveMetrics.stop();
        }
        if (this.licenseVerifier != null) {
            this.licenseVerifier.shutdown();
        }
        if (this.discordNotifier != null) {
            this.discordNotifier.shutdown();
        }
        if (this.database != null) {
            this.database.shutdown();
        }
        this.initialized = false;
        SolidusAnalyticsMod.LOGGER.info("Solidus Analytics Engine shut down complete.");
    }

    public void onServerTick(int currentTick) {
        if (!this.initialized) {
            return;
        }
        if (this.snapshotScheduler != null) {
            this.snapshotScheduler.onTick(currentTick);
        }
        if (this.dashboardManager != null) {
            this.dashboardManager.onTick(currentTick);
        }
        if (this.cloudAgent != null) {
            this.cloudAgent.onServerTick();
        }
        ++this.cleanupTickCounter;
        if (this.cleanupTickCounter >= 720000) {
            this.cleanupTickCounter = 0;
            if (this.database != null && this.config != null) {
                this.database.getExecutor().submit(() -> this.database.runCleanup(this.config.getDataRetentionDays()));
            }
        }
    }

    public AnalyticsDatabase getDatabase() {
        return this.database;
    }

    public LiveMetricsTracker getLiveMetrics() {
        return this.liveMetrics;
    }

    public SnapshotScheduler getSnapshotScheduler() {
        return this.snapshotScheduler;
    }

    public InflationCalculator getInflationCalculator() {
        return this.inflationCalculator;
    }

    public AnalyticsConfig getConfig() {
        return this.config;
    }

    public boolean isPremiumEnabled() {
        // D-8 fix: delegate to the verifier so a license expiring mid-session
        // (or a re-verified key file) is honored without a server restart.
        return this.licenseVerifier != null && this.licenseVerifier.isPremiumEnabled();
    }

    public LicenseVerifier getLicenseVerifier() {
        return this.licenseVerifier;
    }

    public EconomyHealthScore getHealthScore() {
        return this.healthScore;
    }

    public FraudDetector getFraudDetector() {
        return this.fraudDetector;
    }

    /**
     * Read-only wealth-distribution view (donut + richest players) for the
     * dashboard payload. Null until initialize() runs or in unit-test stubs.
     */
    public com.solidus.analytics.dashboard.WealthDistributionProvider getWealthDistributionProvider() {
        return this.wealthDistributionProvider;
    }

    public DiscordWebhookNotifier getDiscordNotifier() {
        return this.discordNotifier;
    }

    public WeeklyReportGenerator getWeeklyReportGenerator() {
        return this.weeklyReportGenerator;
    }

    public boolean isApiIntegrationAvailable() {
        return this.apiIntegrationAvailable;
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public Path getConfigDirPath() {
        return this.configDirPath;
    }

    public DashboardManager getDashboardManager() {
        return this.dashboardManager;
    }

    public CloudAgent getCloudAgent() {
        return this.cloudAgent;
    }

    /** Called by the mod entrypoint before initialize() so the cloud agent and
     *  console command path can reach the live server object. */
    public void attachServer(net.minecraft.server.MinecraftServer server) {
        this.attachedServer = server;
        if (this.cloudAgent != null) {
            this.cloudAgent.attachServer(server);
        }
    }

    private void ensureInitialized() {
        if (!this.initialized) {
            throw new IllegalStateException("AnalyticsEngine accessed before initialization!");
        }
    }
}
