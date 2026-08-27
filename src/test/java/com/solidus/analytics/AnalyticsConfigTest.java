package com.solidus.analytics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnalyticsConfigTest {
    @Test
    void normalizesUnsafeRuntimeSettings() throws Exception {
        Path dir = Files.createTempDirectory("solidus-analytics-config");
        Path config = dir.resolve("analytics.properties");
        Files.writeString(config, String.join(System.lineSeparator(),
            "snapshot.interval.minutes=-10",
            "polling.interval.seconds=0",
            "data.retention.days=-1",
            "cleanup.interval.hours=0",
            "discord.enabled=true",
            "discord.webhook.url=http://example.invalid/webhook",
            "discord.health_score.threshold=NaN",
            "discord.fraud.min_severity=unknown"));

        AnalyticsConfig analyticsConfig = new AnalyticsConfig(dir);
        analyticsConfig.load();

        assertEquals(1, analyticsConfig.getSnapshotIntervalMinutes());
        assertEquals(5, analyticsConfig.getPollingIntervalSeconds());
        assertEquals(1, analyticsConfig.getDataRetentionDays());
        assertEquals(1, analyticsConfig.getCleanupIntervalHours());
        assertFalse(analyticsConfig.isDiscordEnabled());
        assertEquals(50.0, analyticsConfig.getHealthScoreAlertThreshold());
        assertEquals("HIGH", analyticsConfig.getFraudMinSeverity());
    }
}
