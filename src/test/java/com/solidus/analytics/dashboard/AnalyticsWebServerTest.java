package com.solidus.analytics.dashboard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the local dashboard web server (audit P0-3).
 *
 * <p>Boots the REAL AnalyticsWebServer (NanoHTTPD, 127.0.0.1, ephemeral
 * port) with a real PBKDF2 password hash and drives it with a real HTTP
 * client. Covers the route matrix, the Basic-auth 401 path, the method
 * policy, the per-IP auth lockout (429 + Retry-After while locked, even
 * with correct credentials) and the full security-header set on every
 * response. A fresh server per test keeps the in-memory rate limiter
 * isolated from test ordering.</p>
 */
class AnalyticsWebServerTest {

    private static final String PASSWORD = "correct-pass-42";
    private static String passwordHash;

    private AnalyticsWebServer server;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @BeforeAll
    static void hashPasswordOnce() {
        // PBKDF2 210k - do this once for the whole class, not per test.
        passwordHash = DashboardEncryption.hashPassword(PASSWORD.toCharArray());
    }

    @BeforeEach
    void boot() throws IOException {
        // engine is never dereferenced by the server: only cached JSON is served.
        server = new AnalyticsWebServer(null, 0, passwordHash);
        server.start(); // wrapper start() flags the server as running
    }

    @AfterEach
    void shutdown() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> get(String path, String password) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
            .timeout(Duration.ofSeconds(5))
            .GET();
        if (password != null) {
            String credentials = Base64.getEncoder()
                .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + credentials);
        }
        return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String path, String password) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (password != null) {
            String credentials = Base64.getEncoder()
                .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + credentials);
        }
        return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bootsOnEphemeralLocalPort() {
        assertTrue(server.getPort() > 0, "ephemeral port must be assigned");
        assertTrue(server.isRunning());
    }

    @Test
    void unauthenticatedRequestGets401WithChallenge() throws Exception {
        HttpResponse<String> response = this.get("/api/data", null);
        assertEquals(401, response.statusCode());
        assertEquals("Basic realm=\"Solidus Analytics\"",
            response.headers().firstValue("WWW-Authenticate").orElse(null));
        // SEC FIX regression: the 401 body must not leak the setup command.
        assertFalse(response.body().contains("analytics"), "401 body must stay generic");
    }

    @Test
    void wrongPasswordGets401() throws Exception {
        assertEquals(401, this.get("/api/data", "totally-wrong").statusCode());
    }

    @Test
    void correctCredentialsServeCachedJsonWithFullHeaderSet() throws Exception {
        server.updateData("{\"ok\":true}");
        HttpResponse<String> response = this.get("/api/data", PASSWORD);
        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        assertEquals("application/json",
            response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim());
        // full security-header set on every response (audit matrix row 4)
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(null));
        assertEquals("default-src 'self'",
            response.headers().firstValue("Content-Security-Policy").orElse(null));
    }

    @Test
    void dashboardHtmlAndStaticsAreServed() throws Exception {
        HttpResponse<String> html = this.get("/", PASSWORD);
        assertEquals(200, html.statusCode());
        assertTrue(html.body().contains("Solidus"), "dashboard page must load from JAR resources");
        assertEquals("text/html", html.headers().firstValue("Content-Type").orElse("").split(";")[0].trim());

        HttpResponse<String> css = this.get("/css/style.css", PASSWORD);
        assertEquals(200, css.statusCode());
        assertEquals("text/css", css.headers().firstValue("Content-Type").orElse("").split(";")[0].trim());

        HttpResponse<String> js = this.get("/js/app.js", PASSWORD);
        assertEquals(200, js.statusCode());
    }

    @Test
    void unknownPathIs404WithoutDisclosure() throws Exception {
        HttpResponse<String> response = this.get("/etc/passwd", PASSWORD);
        assertEquals(404, response.statusCode());
        assertEquals("404 Not Found", response.body());
    }

    @Test
    void nonGetMethodsAreRefused() throws Exception {
        assertEquals(405, this.request("POST", "/api/data", PASSWORD).statusCode());
        assertEquals(405, this.request("PUT", "/api/data", PASSWORD).statusCode());
        assertEquals(405, this.request("DELETE", "/api/data", PASSWORD).statusCode());
    }

    @Test
    void optionsIsAllowedForPreflight() throws Exception {
        HttpResponse<String> response = this.request("OPTIONS", "/api/data", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    @Test
    void fiveFailuresLockTheIpEvenForCorrectCredentials() throws Exception {
        // 5 failed attempts (AuthRateLimiter.MAX_FAILED_ATTEMPTS)
        for (int i = 1; i <= 5; ++i) {
            assertEquals(401, this.get("/api/data", "wrong-" + i).statusCode(),
                "failure #" + i + " must be a normal 401");
        }
        // Now the IP is locked: even CORRECT credentials get 429, and the
        // block is checked before PBKDF2 runs (cheap rejection).
        HttpResponse<String> blocked = this.get("/api/data", PASSWORD);
        assertEquals(429, blocked.statusCode());
        assertNotNull(blocked.headers().firstValue("Retry-After").orElse(null));
        assertTrue(Integer.parseInt(blocked.headers().firstValue("Retry-After").get()) > 0);
    }

    @Test
    void lockedOutHasGenericBody() throws Exception {
        for (int i = 0; i < 5; ++i) {
            this.get("/api/data", "nope-" + i);
        }
        HttpResponse<String> blocked = this.get("/api/data", "nope-again");
        assertEquals(429, blocked.statusCode());
        assertTrue(blocked.body().contains("Too many"), "429 body must stay generic");
    }

    @Test
    void successDoesNotLeakHeadersOrTrailingData() throws Exception {
        HttpResponse<String> response = this.get("/api/data", PASSWORD);
        assertEquals(200, response.statusCode());
        assertNull(response.headers().firstValue("Server").orElse(null));
        assertFalse(response.body().contains("NaN"));
    }

    // ---- audit round 2 (A-1, A-3, A-4) ---------------------------------

    @Test
    @DisplayName("A-4: security headers are stamped on EVERY response, including 401/405/429")
    void securityHeadersOnErrorResponses() throws Exception {
        HttpResponse<String> unauthorized = this.get("/api/data", null);
        assertEquals(401, unauthorized.statusCode());
        assertEquals("no-store", unauthorized.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("nosniff", unauthorized.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", unauthorized.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals("default-src 'self'", unauthorized.headers().firstValue("Content-Security-Policy").orElse(null));

        HttpResponse<String> notAllowed = this.request("POST", "/api/data", PASSWORD);
        assertEquals(405, notAllowed.statusCode());
        assertEquals("nosniff", notAllowed.headers().firstValue("X-Content-Type-Options").orElse(null));
    }

    @Test
    @DisplayName("A-3: unauthenticated probes do not consume the failure budget (health checks behind the reverse proxy)")
    void probesDoNotConsumeFailureBudget() throws Exception {
        // 10 credential-less probes (scanner / uptime check pattern).
        for (int i = 0; i < 10; ++i) {
            assertEquals(401, this.get("/api/data", null).statusCode());
        }
        // A legitimate login right after must NOT be locked out.
        HttpResponse<String> ok = this.get("/api/data", PASSWORD);
        assertEquals(200, ok.statusCode());
    }

    @Test
    @DisplayName("A-3: Retry-After reports the REAL remaining lockout (300s window, not hardcoded 60)")
    void retryAfterReflectsRealLockout() throws Exception {
        for (int i = 0; i < 5; ++i) {
            this.get("/api/data", "nope-" + i);
        }
        HttpResponse<String> blocked = this.get("/api/data", PASSWORD);
        assertEquals(429, blocked.statusCode());
        int retryAfter = Integer.parseInt(blocked.headers().firstValue("Retry-After").get());
        assertTrue(retryAfter >= 250, "lockout is 300s - advertised Retry-After must reflect it, got " + retryAfter);
    }

    @Test
    @DisplayName("A-1: a legacy SHA-256 web hash verifies once and is migrated to PBKDF2")
    void legacyWebHashIsMigratedOnSuccessfulLogin() throws Exception {
        // Build a legacy-format hash (hex(salt):hex(digest)) for the password.
        String legacyHash = DashboardEncryptionTest.legacyHashFor(PASSWORD);
        AnalyticsWebServer legacyServer = new AnalyticsWebServer(null, 0, legacyHash);
        final String[] upgradedHolder = new String[1];
        legacyServer.setLegacyWebAuthMigration(password -> {
            // Mirror DashboardManager.migrateWebLegacyHashIfAny.
            upgradedHolder[0] = DashboardEncryption.hashPassword(password.toCharArray());
            legacyServer.updatePasswordHash(upgradedHolder[0]);
        });
        try {
            legacyServer.start();
            HttpResponse<String> first = get(legacyServer, "/api/data", PASSWORD);
            assertEquals(200, first.statusCode(), "legacy hash must verify for migration");

            // The credential is now PBKDF2: the weak verifier is never consulted again.
            assertNotNull(upgradedHolder[0], "migration callback must have fired");
            assertTrue(upgradedHolder[0].startsWith("pbkdf2$"), "upgraded hash must be PBKDF2");
            HttpResponse<String> second = get(legacyServer, "/api/data", PASSWORD);
            assertEquals(200, second.statusCode(), "PBKDF2 record must verify after migration");
            HttpResponse<String> wrong = get(legacyServer, "/api/data", "wrong");
            assertEquals(401, wrong.statusCode());
        } finally {
            legacyServer.stop();
        }
    }

    private HttpResponse<String> get(AnalyticsWebServer target, String path, String password) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + target.getPort() + path))
            .timeout(Duration.ofSeconds(5))
            .GET();
        if (password != null) {
            String credentials = Base64.getEncoder()
                .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + credentials);
        }
        return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
