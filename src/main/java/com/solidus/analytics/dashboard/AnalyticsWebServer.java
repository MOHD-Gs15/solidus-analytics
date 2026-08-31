package com.solidus.analytics.dashboard;

import com.solidus.analytics.SolidusAnalyticsMod;
import com.solidus.analytics.dashboard.DashboardEncryption;
import com.solidus.analytics.engine.AnalyticsEngine;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AnalyticsWebServer
extends NanoHTTPD {
    private final AnalyticsEngine engine;
    private final String passwordHash;
    /** Per-IP throttling for Basic auth (PBKDF2 DoS + brute-force mitigation). */
    private final AuthRateLimiter authLimiter = new AuthRateLimiter();
    private volatile String cachedData = "{}";
    private volatile boolean running = false;

    public AnalyticsWebServer(AnalyticsEngine engine, int port, String passwordHash) {
        super("127.0.0.1", port);
        this.engine = engine;
        this.passwordHash = passwordHash;
    }

    public void start() throws IOException {
        this.start(5000, false);
        this.running = true;
        SolidusAnalyticsMod.LOGGER.info("Analytics web server started on port {}", (Object)this.getListeningPort());
    }

    public void stop() {
        super.stop();
        this.running = false;
        SolidusAnalyticsMod.LOGGER.info("Analytics web server stopped.");
    }

    public void updateData(String jsonData) {
        this.cachedData = jsonData;
    }

    public boolean isRunning() {
        return this.running;
    }

    public int getPort() {
        return this.getListeningPort();
    }

    public NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession session) {
        String uri = session.getUri();
        NanoHTTPD.Method method = session.getMethod();
        if (NanoHTTPD.Method.OPTIONS.equals(method)) {
            NanoHTTPD.Response response = AnalyticsWebServer.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "");
            response.addHeader("Allow", "GET, OPTIONS");
            return response;
        }
        if (!NanoHTTPD.Method.GET.equals(method)) {
            return AnalyticsWebServer.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method Not Allowed");
        }
        // Rate limiting runs BEFORE the PBKDF2 verification: a blocked IP is
        // rejected cheaply, so request floods cannot pin the server CPU on
        // password derivations (and online guessing is capped).
        String remoteIp = session.getRemoteIpAddress();
        if (this.authLimiter.isBlocked(remoteIp)) {
            SolidusAnalyticsMod.LOGGER.warn("Analytics dashboard: auth rate limit hit from {}", remoteIp);
            NanoHTTPD.Response tooMany = AnalyticsWebServer.newFixedLengthResponse(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, "text/plain", "Too many failed attempts. Try again later.");
            tooMany.addHeader("Retry-After", "60");
            return tooMany;
        }
        if (!this.isAuthenticated(session)) {
            NanoHTTPD.Response unauthorized = AnalyticsWebServer.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "text/html", "<html><body><h1>401 Unauthorized</h1><p>Valid credentials required.</p></body></html>");
            // SEC FIX: never reveal the setup command to unauthenticated callers.
            // Standard Basic-auth clients need this header to prompt for credentials.
            unauthorized.addHeader("WWW-Authenticate", "Basic realm=\"Solidus Analytics\"");
            return unauthorized;
        }
        NanoHTTPD.Response response = this.routeRequest(uri, session);
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("X-Frame-Options", "DENY");
        response.addHeader("Referrer-Policy", "no-referrer");
        // CSP: the dashboard loads exactly one external script (/js/app.js) and
        // one stylesheet (/css/style.css) and uses no inline handlers or
        // inline styles, so a strict same-origin policy costs nothing and
        // neutralizes any future injection vector in served data.
        response.addHeader("Content-Security-Policy", "default-src 'self'");
        return response;
    }

    private NanoHTTPD.Response routeRequest(String uri, NanoHTTPD.IHTTPSession session) {
        return switch (uri) {
            case "/", "/index.html" -> this.serveDashboardHtml();
            case "/api/data" -> this.serveApiData();
            case "/css/style.css" -> this.serveCss();
            case "/js/app.js" -> this.serveJs();
            default -> AnalyticsWebServer.newFixedLengthResponse((NanoHTTPD.Response.IStatus)NanoHTTPD.Response.Status.NOT_FOUND, (String)"text/plain", (String)"404 Not Found");
        };
    }

    private NanoHTTPD.Response serveDashboardHtml() {
        String html = this.loadResource("/web/index.html");
        if (html != null) {
            return AnalyticsWebServer.newFixedLengthResponse((NanoHTTPD.Response.IStatus)NanoHTTPD.Response.Status.OK, (String)"text/html", (String)html);
        }
        return AnalyticsWebServer.newFixedLengthResponse((NanoHTTPD.Response.IStatus)NanoHTTPD.Response.Status.OK, (String)"text/html", (String)"<html><body><h1>Solidus Analytics Dashboard</h1><p>Dashboard files not found in JAR. Using API-only mode.</p><p>API endpoint: <a href='/api/data'>/api/data</a></p></body></html>");
    }

    private NanoHTTPD.Response serveApiData() {
        return AnalyticsWebServer.newFixedLengthResponse((NanoHTTPD.Response.IStatus)NanoHTTPD.Response.Status.OK, (String)"application/json", (String)this.cachedData);
    }

    private NanoHTTPD.Response serveCss() {
        String css = this.loadResource("/web/css/style.css");
        if (css != null) {
            return AnalyticsWebServer.newFixedLengthResponse((NanoHTTPD.Response.IStatus)NanoHTTPD.Response.Status.OK, (String)"text/css", (String)css);
        }
        return AnalyticsWebServer.newFixedLengthResponse((NanoHTTPD.Response.IStatus)NanoHTTPD.Response.Status.NOT_FOUND, (String)"text/plain", (String)"404");
    }

    private NanoHTTPD.Response serveJs() {
        String js = this.loadResource("/web/js/app.js");
        if (js != null) {
            return AnalyticsWebServer.newFixedLengthResponse((NanoHTTPD.Response.IStatus)NanoHTTPD.Response.Status.OK, (String)"application/javascript", (String)js);
        }
        return AnalyticsWebServer.newFixedLengthResponse((NanoHTTPD.Response.IStatus)NanoHTTPD.Response.Status.NOT_FOUND, (String)"text/plain", (String)"404");
    }

    private boolean isAuthenticated(NanoHTTPD.IHTTPSession session) {
        String remoteIp = session.getRemoteIpAddress();
        if (this.passwordHash == null || this.passwordHash.isBlank()) {
            return false;
        }
        String authHeader = (String)session.getHeaders().get("authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            this.authLimiter.recordFailure(remoteIp);
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
            int colonIndex = decoded.indexOf(':');
            String password = colonIndex >= 0 ? decoded.substring(colonIndex + 1) : decoded;
            if (DashboardEncryption.verifyPassword(password.toCharArray(), this.passwordHash)) {
                this.authLimiter.recordSuccess(remoteIp);
                return true;
            }
            this.authLimiter.recordFailure(remoteIp);
            return false;
        }
        catch (Exception e) {
            this.authLimiter.recordFailure(remoteIp);
            return false;
        }
    }

    private String loadResource(String path) {
        try (InputStream is = this.getClass().getResourceAsStream(path)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.warn("Failed to load resource: {}", (Object)path);
            return null;
        }
    }
}
