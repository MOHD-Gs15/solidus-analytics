package com.solidus.analytics.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solidus.analytics.SolidusAnalyticsMod;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * CloudRelayClient - the outbound-only WSS connection to the Solidus Cloud
 * Relay (PROTOCOL.md &sect;1 and &sect;4).
 *
 * <p>Built on the JDK's built-in {@code java.net.http.WebSocket} - zero extra
 * dependencies to shade, works on any host that allows egress to 443.</p>
 *
 * <ul>
 *   <li><b>Handshake</b>: connects, sends {@code hello} (serverId + secret +
 *       capabilities), and only flips to "ready" on {@code hello.ok}.</li>
 *   <li><b>Heartbeat</b>: {@code hb} every 15 s while connected.</li>
 *   <li><b>Reconnect</b>: exponential 1→60 s with &plusmn;20 % jitter; while
 *       disconnected up to 2 000 events are buffered (oldest dropped, counter
 *       surfaced in health.meta).</li>
 *   <li><b>Send discipline</b>: all frames funnel through one queue drained by
 *       one completion chain - WebSocket forbids concurrent sends.</li>
 *   <li><b>Optional pinning</b>: {@code cloud.pinSha256} pins the relay leaf
 *       certificate fingerprint (defense against MITM on shared hosts).</li>
 * </ul>
 */
public final class CloudRelayClient {
    private static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 15, 30, 60};
    private static final int MAX_BUFFERED = 2000;
    private static final long HB_INTERVAL_MS = 15000L;

    private final CloudAgentConfig config;
    private final Consumer<String> incoming;      // full frames from relay
    private final Runnable onReady;
    private final Supplier<String> helloSupplier;  // fresh hello per (re)connect (B-5)
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean broken = new AtomicBoolean(false);
    private final AtomicLong droppedEvents = new AtomicLong(0L);
    private final AtomicLong seq = new AtomicLong(0L);
    private final ConcurrentLinkedQueue<String> outbox = new ConcurrentLinkedQueue<String>();
    private final ConcurrentLinkedQueue<String> offlineBuffer = new ConcurrentLinkedQueue<String>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile WebSocket ws;
    private volatile HttpClient httpClient;
    private Thread controlThread;
    private long lastHbMs = 0L;

    public CloudRelayClient(CloudAgentConfig config, Consumer<String> incoming, Runnable onReady) {
        this(config, incoming, onReady, () -> null);
    }

    /** Full constructor: {@code helloSupplier} is consulted on EVERY (re)connect
     *  so rotations (pairing.rotate) re-hello with the CURRENT secret (audit B-5). */
    public CloudRelayClient(CloudAgentConfig config, Consumer<String> incoming, Runnable onReady,
                            Supplier<String> helloSupplier) {
        this.config = config;
        this.incoming = incoming;
        this.onReady = onReady;
        this.helloSupplier = helloSupplier;
    }

    public void start(String helloJson) {
        if (this.running.getAndSet(true)) {
            return;
        }
        this.controlThread = new Thread(() -> this.loop(), "solidus-cloud-relay");
        this.controlThread.setDaemon(true);
        this.controlThread.start();
    }

    public void stop() {
        this.running.set(false);
        this.ready.set(false);
        try {
            if (this.ws != null) {
                this.ws.sendClose(WebSocket.NORMAL_CLOSURE, "agent shutdown").get(2L, TimeUnit.SECONDS);
            }
        }
        catch (Exception ignored) {
        }
        if (this.controlThread != null) {
            this.controlThread.interrupt();
        }
    }

    public boolean isReady() {
        return this.ready.get();
    }

    public long droppedEvents() {
        return this.droppedEvents.get();
    }

    /** Force a reconnect (used after pairing secret rotation). */
    public void forceReconnect() {
        this.broken.set(true);
        this.ready.set(false);
        try {
            if (this.ws != null) {
                this.ws.abort();
            }
        }
        catch (Exception ignored) {
        }
    }

    // ---- send path ------------------------------------------------------

    /** Sends a pre-serialized frame; buffers while the relay is unreachable. */
    public void send(String json) {
        if (!this.running.get()) {
            return;
        }
        if (this.ready.get()) {
            this.outbox.add(json);
            this.drain();
        }
        else {
            this.offlineBuffer.add(json);
            while (this.offlineBuffer.size() > MAX_BUFFERED) {
                this.offlineBuffer.poll();
                this.droppedEvents.incrementAndGet();
            }
        }
    }

    private synchronized void drain() {
        if (!this.draining.compareAndSet(false, true)) {
            return;
        }
        try {
            WebSocket socket = this.ws;
            while (socket != null && this.ready.get()) {
                String next = this.outbox.poll();
                if (next == null) {
                    break;
                }
                socket.sendText(next, true).whenComplete((v, err) -> {
                    if (err != null) {
                        this.broken.set(true);
                    }
                    this.drain();
                });
            }
        }
        finally {
            this.draining.set(false);
        }
    }

    // ---- control loop ----------------------------------------------------

    private void loop() {
        int backoffIdx = 0;
        while (this.running.get()) {
            this.broken.set(false);
            try {
                this.connect(this.hello());
                backoffIdx = 0;
                while (this.running.get() && !this.broken.get() && this.ready.get()) {
                    long now = System.currentTimeMillis();
                    if (now - this.lastHbMs > HB_INTERVAL_MS) {
                        this.lastHbMs = now;
                        this.sendRaw("{\"sv\":1,\"id\":\"m-hb" + this.seq.incrementAndGet()
                            + "\",\"t\":\"evt\",\"type\":\"hb\",\"ts\":" + now + ",\"d\":{}}");
                    }
                    TimeUnit.MILLISECONDS.sleep(500L);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception e) {
                SolidusAnalyticsMod.LOGGER.debug("[Cloud] relay connection error: {}", (Object)e.toString());
            }
            this.ready.set(false);
            if (!this.running.get()) {
                return;
            }
            long waitMs = (long)(BACKOFF_SECONDS[Math.min(backoffIdx, BACKOFF_SECONDS.length - 1)] * 1000L
                * (0.8 + Math.random() * 0.4));
            ++backoffIdx;
            SolidusAnalyticsMod.LOGGER.info("[Cloud] relay disconnected - reconnecting in {} ms", (Object)waitMs);
            try {
                TimeUnit.MILLISECONDS.sleep(waitMs);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String hello() {
        String fresh = this.helloSupplier != null ? this.helloSupplier.get() : null;
        return fresh;
    }

    private void connect(String helloJson) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L));
        String pin = this.config.getPinSha256();
        if (pin != null && !pin.isBlank()) {
            builder.sslContext(this.pinnedSslContext(pin));
        }
        this.httpClient = builder.build();
        java.util.concurrent.CompletableFuture<WebSocket> future = this.httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10L))
            .buildAsync(URI.create(this.config.getRelayUrl()), new RelayListener());
        WebSocket socket = future.get(20L, TimeUnit.SECONDS);
        this.ws = socket;
        socket.request(1L);
        this.sendRaw(helloJson);
        // handshake: the listener flips `ready` when hello.ok arrives and
        // flushes the offline buffer; if the relay rejects, it closes us.
        long deadline = System.currentTimeMillis() + 15000L;
        while (!this.ready.get() && System.currentTimeMillis() < deadline && !this.broken.get()) {
            TimeUnit.MILLISECONDS.sleep(100L);
        }
        if (!this.ready.get()) {
            throw new IllegalStateException("handshake timeout (no hello.ok within 15s)");
        }
        SolidusAnalyticsMod.LOGGER.info("[Cloud] Connected to relay {} as serverId={}",
            (Object)this.config.getRelayUrl(), (Object)this.config.getServerId());
    }

    private void sendRaw(String json) {
        WebSocket socket = this.ws;
        if (socket == null) {
            return;
        }
        try {
            socket.sendText(json, true).get(5L, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            this.broken.set(true);
        }
    }

    private SSLContext pinnedSslContext(String expectedHex) throws Exception {
        String normalized = expectedHex.replace(":", "").toLowerCase();
        X509TrustManager pinning = new X509TrustManager(){
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("client auth not expected");
            }
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("empty chain");
                }
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    String hex = java.util.HexFormat.of().formatHex(digest);
                    if (!hex.equals(normalized)) {
                        throw new CertificateException("relay certificate pin mismatch");
                    }
                }
                catch (Exception e) {
                    throw new CertificateException("pin check failed: " + e);
                }
            }
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{pinning}, new java.security.SecureRandom());
        return ctx;
    }

    // ---- listener ---------------------------------------------------------

    private final class RelayListener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();
        /** B-11 fix: relay frames are small JSON envelopes - 1 MiB is ~1000x the
         *  largest legitimate message. A hostile relay streaming a giant frame
         *  must not be able to OOM the game server. */
        private static final int MAX_FRAME_CHARS = 1024 * 1024;

        @Override
        public void onOpen(WebSocket webSocket) {
            CloudRelayClient.this.ws = webSocket;
            webSocket.request(1L);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (this.partial.length() + data.length() > MAX_FRAME_CHARS) {
                SolidusAnalyticsMod.LOGGER.warn("[Cloud] relay frame exceeds 1 MiB - dropping connection");
                this.partial.setLength(0);
                CloudRelayClient.this.broken.set(true);
                try {
                    webSocket.abort();
                }
                catch (Throwable ignored) {
                }
                return null;
            }
            this.partial.append(data);
            if (last) {
                String frame = this.partial.toString();
                this.partial.setLength(0);
                CloudRelayClient.this.handleFrame(frame);
            }
            webSocket.request(1L);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            CloudRelayClient.this.broken.set(true);
            CloudRelayClient.this.ready.set(false);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            CloudRelayClient.this.broken.set(true);
            CloudRelayClient.this.ready.set(false);
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] websocket error: {}", (Object)String.valueOf(error));
        }
    }

    private void handleFrame(String frame) {
        try {
            JsonObject msg = JsonParser.parseString(frame).getAsJsonObject();
            String type = msg.has("type") && msg.get("type").isJsonPrimitive() ? msg.get("type").getAsString() : "";
            if ("hello.ok".equals(type)) {
                this.ready.set(true);
                this.lastHbMs = System.currentTimeMillis();
                // flush everything buffered while offline
                while (!this.offlineBuffer.isEmpty()) {
                    String buffered = this.offlineBuffer.poll();
                    if (buffered != null) {
                        this.outbox.add(buffered);
                    }
                }
                this.drain();
                if (this.onReady != null) {
                    this.onReady.run();
                }
                return;
            }
            if ("hello.err".equals(type)) {
                SolidusAnalyticsMod.LOGGER.warn("[Cloud] relay rejected hello: {}", (Object)frame);
                this.broken.set(true);
                return;
            }
            // B-3 fix: the relay only earns the right to send us commands AFTER
            // it authenticated itself against our pairing secret in hello.
            // Frames before hello.ok are dropped (a spoofed/MITM peer must not
            // be able to push owner-level commands onto an unauthenticated socket).
            if (!this.ready.get()) {
                SolidusAnalyticsMod.LOGGER.warn("[Cloud] dropping frame received before hello.ok");
                return;
            }
            this.incoming.accept(frame);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] bad frame from relay: {}", (Object)e.toString());
        }
        catch (StackOverflowError so) {
            // B-11: deeply nested JSON can blow the stack before the size cap bites.
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] oversized/malformed frame from relay dropped");
        }
    }

    public static String newId() {
        return "m-" + UUID.randomUUID().toString().substring(0, 13);
    }
}
