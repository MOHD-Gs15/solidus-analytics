package com.solidus.analytics.premium;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.premium.FraudDetector;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DiscordWebhookNotifier {
    private volatile String webhookUrl;
    private volatile boolean enabled = false;
    private volatile boolean notifyFraud = true;
    private volatile FraudDetector.FraudAlert.Severity minFraudSeverity = FraudDetector.FraudAlert.Severity.HIGH;
    private volatile boolean notifyInflationWarnings = true;
    private volatile boolean notifyDailySummary = true;
    private volatile boolean notifyHealthScore = true;
    private volatile double healthScoreThreshold = 50.0;
    private final ExecutorService webhookExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Solidus-Discord-Webhook");
        t.setDaemon(true);
        return t;
    });
    private static final long RATE_LIMIT_MS = 2000L;
    private volatile long lastWebhookSent = 0L;

    public void configure(String webhookUrl, boolean enabled) {
        this.webhookUrl = webhookUrl;
        boolean bl = this.enabled = enabled && webhookUrl != null && !webhookUrl.isBlank();
        if (this.enabled) {
            SolidusAnalyticsMod.LOGGER.info("Discord webhook notifications enabled.");
        }
    }

    public void shutdown() {
        this.webhookExecutor.shutdown();
        try {
            if (!this.webhookExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                this.webhookExecutor.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            this.webhookExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void notifyFraudAlert(FraudDetector.FraudAlert alert) {
        if (!this.enabled) {
            return;
        }
        if (!this.notifyFraud) {
            return;
        }
        if (alert.severity.ordinal() < this.minFraudSeverity.ordinal()) {
            return;
        }
        String color = switch (alert.severity) {
            case FraudDetector.FraudAlert.Severity.HIGH -> "15158332";
            case FraudDetector.FraudAlert.Severity.MEDIUM -> "16776960";
            case FraudDetector.FraudAlert.Severity.LOW -> "3447003";
        };
        String json = this.buildEmbed("Fraud Alert: " + String.valueOf((Object)alert.type), alert.description, alert.playerName, color);
        this.sendWebhookAsync(json);
    }

    public void notifyInflationWarning(double ratio, String status, Double rate24h) {
        if (!this.enabled || !this.notifyInflationWarnings) {
            return;
        }
        String color = "15158332";
        String rateStr = rate24h != null ? String.format("%.2f%%", rate24h) : "N/A";
        String description = String.format("Money:Goods Ratio: %.1f:1\nStatus: %s\n24h Inflation Rate: %s", ratio, status, rateStr);
        String json = this.buildEmbed("Inflation Warning", description, "Economy Monitor", color);
        this.sendWebhookAsync(json);
    }

    public void notifyHealthScore(double score, String grade, String summary) {
        if (!this.enabled || !this.notifyHealthScore) {
            return;
        }
        if (score >= this.healthScoreThreshold) {
            return;
        }
        String color = score < 30.0 ? "15158332" : (score < 50.0 ? "16776960" : "3447003");
        String description = String.format("Health Score: %.1f/100 (Grade: %s)\n%s", score, grade, summary);
        String json = this.buildEmbed("Economy Health Alert", description, "Health Monitor", color);
        this.sendWebhookAsync(json);
    }

    public void notifyDailySummary(long transactionCount, long volumeCents, int activePlayers, double healthScore) {
        if (!this.enabled || !this.notifyDailySummary) {
            return;
        }
        String color = "5763719";
        String description = String.format("Transactions: %,d\nVolume: %,.2f S$\nActive Players: %d\nHealth Score: %.1f/100", transactionCount, (double)volumeCents / 100.0, activePlayers, healthScore);
        String json = this.buildEmbed("Daily Economy Summary", description, "Solidus Analytics", color);
        this.sendWebhookAsync(json);
    }

    private void sendWebhookAsync(String jsonPayload) {
        this.webhookExecutor.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                long timeSinceLast = now - this.lastWebhookSent;
                if (timeSinceLast < 2000L) {
                    Thread.sleep(2000L - timeSinceLast);
                }
                HttpURLConnection conn = (HttpURLConnection)URI.create(this.webhookUrl).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "SolidusAnalytics/1.0");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream();){
                    os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }
                int responseCode = conn.getResponseCode();
                this.lastWebhookSent = System.currentTimeMillis();
                if (responseCode == 204 || responseCode == 200) {
                    SolidusAnalyticsMod.LOGGER.debug("Discord webhook sent successfully.");
                } else if (responseCode == 429) {
                    SolidusAnalyticsMod.LOGGER.warn("Discord webhook rate limited. Backing off.");
                } else {
                    SolidusAnalyticsMod.LOGGER.warn("Discord webhook returned status: {}", (Object)responseCode);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (IOException e) {
                SolidusAnalyticsMod.LOGGER.warn("Failed to send Discord webhook: {}", (Object)e.getMessage());
            }
        });
    }

    private String buildEmbed(String title, String description, String footer, String color) {
        String escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedDesc = description.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String escapedFooter = footer.replace("\\", "\\\\").replace("\"", "\\\"");
        return String.format("{\n  \"embeds\": [{\n    \"title\": \"%s\",\n    \"description\": \"%s\",\n    \"color\": %s,\n    \"footer\": {\n      \"text\": \"%s | Solidus Analytics\"\n    },\n    \"timestamp\": \"%s\"\n  }]\n}", escapedTitle, escapedDesc, color, escapedFooter, Instant.now().toString());
    }

    public void notifyCustomEmbed(String title, String description, String color) {
        if (!this.enabled) {
            return;
        }
        String json = this.buildEmbed(title, description, "Solidus Analytics", color);
        this.sendWebhookAsync(json);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setMinFraudSeverity(FraudDetector.FraudAlert.Severity severity) {
        this.minFraudSeverity = severity;
    }

    public void setNotifyInflationWarnings(boolean enabled) {
        this.notifyInflationWarnings = enabled;
    }

    public void setNotifyDailySummary(boolean enabled) {
        this.notifyDailySummary = enabled;
    }

    public void setNotifyHealthScore(boolean enabled) {
        this.notifyHealthScore = enabled;
    }

    public void setHealthScoreThreshold(double threshold) {
        this.healthScoreThreshold = threshold;
    }

    public void setNotifyFraud(boolean enabled) {
        this.notifyFraud = enabled;
    }

    public void setFraudMinSeverity(String severity) {
        if (severity == null) {
            this.minFraudSeverity = FraudDetector.FraudAlert.Severity.HIGH;
            return;
        }
        this.minFraudSeverity = switch (severity.toUpperCase()) {
            case "LOW" -> FraudDetector.FraudAlert.Severity.LOW;
            case "MEDIUM" -> FraudDetector.FraudAlert.Severity.MEDIUM;
            default -> FraudDetector.FraudAlert.Severity.HIGH;
        };
    }
}
