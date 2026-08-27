package com.solidus.analytics.dashboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.Gson;
import com.solidus.analytics.SolidusAnalyticsMod;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class GitHubDataPublisher {
    private static final Gson GSON = new Gson();
    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 1000L;
    private static final long MIN_PUBLISH_INTERVAL_MS = 30_000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Solidus-GitHub-Publisher");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> fileShas = new ConcurrentHashMap<>();
    private final AtomicLong lastPublishAttempt = new AtomicLong(0L);
    private volatile String githubToken = "";
    private volatile String repoOwner = "";
    private volatile String repoName = "";
    private volatile String branch = "main";
    private volatile boolean enabled;

    public synchronized void configure(String token, String repoOwner, String repoName, String branch, boolean enabled) {
        this.githubToken = token == null ? "" : token.trim();
        this.repoOwner = repoOwner == null ? "" : repoOwner.trim();
        this.repoName = repoName == null ? "" : repoName.trim();
        this.branch = branch == null || branch.isBlank() ? "main" : branch.trim();
        this.fileShas.clear();
        this.enabled = enabled && !this.githubToken.isBlank()
            && this.repoOwner.matches("[A-Za-z0-9_.-]{1,39}")
            && this.repoName.matches("[A-Za-z0-9_.-]{1,100}")
            && this.branch.matches("[A-Za-z0-9._/-]{1,200}");
        if (this.enabled) {
            SolidusAnalyticsMod.LOGGER.info("GitHub Pages publisher enabled for {}/{}", this.repoOwner, this.repoName);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            githubToken = "";
            fileShas.clear();
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void publishAsync(String jsonData) {
        if (!enabled || jsonData == null || jsonData.isBlank()) return;
        long now = System.currentTimeMillis();
        long previous = lastPublishAttempt.get();
        if (now - previous < MIN_PUBLISH_INTERVAL_MS || !lastPublishAttempt.compareAndSet(previous, now)) return;
        executor.submit(() -> {
            try {
                publishWithRetry("data/analytics-data.json", jsonData, MAX_RETRIES);
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to publish analytics data after retries", e);
            }
        });
    }

    public void publishDashboardFiles(String htmlContent, String cssContent, String jsContent) {
        if (!enabled) return;
        executor.submit(() -> {
            try {
                publishWithRetry("index.html", htmlContent, MAX_RETRIES);
                publishWithRetry("css/style.css", cssContent, MAX_RETRIES);
                publishWithRetry("js/app.js", jsContent, MAX_RETRIES);
                SolidusAnalyticsMod.LOGGER.info("Dashboard files published to GitHub Pages.");
            } catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.error("Failed to publish dashboard files", e);
            }
        });
    }

    private void publishWithRetry(String filePath, String content, int retries) throws Exception {
        if (retries <= 0) throw new IOException("Maximum retries exceeded for " + filePath);
        String sha = getFileSha(filePath);
        HttpResponse<String> response = putFileContent(filePath, content, sha);
        int status = response.statusCode();
        if (status == 200 || status == 201) {
            updateSha(filePath, response.body());
            return;
        }
        if ((status == 409 || status == 422) && retries > 1) {
            fileShas.remove(filePath);
            Thread.sleep(BASE_RETRY_DELAY_MS * (1L << (MAX_RETRIES - retries)));
            publishWithRetry(filePath, content, retries - 1);
            return;
        }
        throw new IOException("GitHub API returned HTTP " + status + " for " + filePath);
    }

    private String getFileSha(String filePath) throws IOException, InterruptedException {
        String cached = fileShas.get(filePath);
        if (cached != null) return cached;
        HttpRequest request = requestBuilder(filePath, false).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode() + " while reading " + filePath);
        }
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String sha = json.has("sha") ? json.get("sha").getAsString() : null;
            if (sha != null && !sha.isBlank()) fileShas.put(filePath, sha);
            return sha;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid GitHub response while reading " + filePath, e);
        }
    }

    private HttpResponse<String> putFileContent(String filePath, String content, String sha) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("message", "Update " + filePath + " - Solidus Analytics");
        body.addProperty("branch", branch);
        body.addProperty("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        if (sha != null && !sha.isBlank()) body.addProperty("sha", sha);
        HttpRequest request = requestBuilder(filePath, true)
            .PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder requestBuilder(String filePath, boolean write) {
        String encodedPath = filePath.replace(" ", "%20");
        String url = "https://api.github.com/repos/" + repoOwner + "/" + repoName + "/contents/" + encodedPath;
        if (!write) url += "?ref=" + branch;
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + githubToken)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "SolidusAnalytics/1.1");
    }

    private void updateSha(String filePath, String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("content") && json.getAsJsonObject("content").has("sha")) {
                fileShas.put(filePath, json.getAsJsonObject("content").get("sha").getAsString());
            } else {
                fileShas.remove(filePath);
            }
        } catch (RuntimeException ignored) {
            fileShas.remove(filePath);
        }
    }
}
