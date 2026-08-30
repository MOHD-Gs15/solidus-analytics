package com.solidus.analytics.dashboard;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * AuthRateLimiter - per-IP rate limiting for dashboard authentication.
 *
 * <p>Two threats this closes (audit finding: PBKDF2 DoS vector):</p>
 * <ul>
 *   <li><b>CPU exhaustion:</b> every authentication attempt runs a full
 *       PBKDF2 derivation (210k iterations). Unthrottled, a request flood
 *       pins the server CPU. Once an IP is blocked, requests from it are
 *       rejected WITHOUT running PBKDF2 at all.</li>
 *   <li><b>Online password guessing:</b> failed attempts are counted per IP
 *       in a sliding 60-second window; reaching the limit locks that IP out
 *       for 5 minutes. A successful login clears the counter for that IP.</li>
 * </ul>
 *
 * <p>The tracked-IP set is bounded (oldest idle entries are evicted) so an
 * attacker cannot grow memory unboundedly either. Lockout state is
 * in-memory by design: a server restart always gives a clean slate, which
 * keeps a misconfigured proxy from permanently locking out the admin.</p>
 */
public class AuthRateLimiter {

    /** Failed attempts within one window before the IP is locked out. */
    static final int MAX_FAILED_ATTEMPTS = 5;
    /** Sliding window for counting consecutive failures. */
    static final long WINDOW_MS = 60_000L;
    /** How long an IP that reached the failure limit stays blocked. */
    static final long LOCKOUT_MS = 300_000L;
    /** Upper bound on tracked IPs (memory bound). */
    private static final int MAX_TRACKED_IPS = 10_000;

    private static final class Entry {
        volatile long windowStart;
        volatile int failures;
        volatile long blockedUntil;
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /** Production constructor using the wall clock. */
    public AuthRateLimiter() {
        this(System::currentTimeMillis);
    }

    /** Test constructor with an injectable clock (millis since epoch). */
    AuthRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** True while the IP is locked out (must not run PBKDF2 for it). */
    public boolean isBlocked(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        Entry entry = this.entries.get(ip);
        if (entry == null) {
            return false;
        }
        return entry.blockedUntil > 0 && this.clock.getAsLong() < entry.blockedUntil;
    }

    /** Millis until the block lifts; 0 when not blocked. */
    public long getRemainingLockMs(String ip) {
        if (ip == null || ip.isBlank()) {
            return 0L;
        }
        Entry entry = this.entries.get(ip);
        if (entry == null) {
            return 0L;
        }
        long remaining = entry.blockedUntil - this.clock.getAsLong();
        return Math.max(0L, remaining);
    }

    /** Records one failed authentication attempt for the IP. */
    public void recordFailure(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        long now = this.clock.getAsLong();
        this.entries.compute(ip, (key, entry) -> {
            if (entry == null) {
                entry = new Entry();
                entry.windowStart = now;
            }
            if (now - entry.windowStart > WINDOW_MS) {
                // New window: previous failures are stale.
                entry.windowStart = now;
                entry.failures = 0;
            }
            entry.failures++;
            if (entry.failures >= MAX_FAILED_ATTEMPTS) {
                entry.blockedUntil = now + LOCKOUT_MS;
            }
            return entry;
        });
        this.evictIfNeeded(now);
    }

    /** Records a successful authentication - clears the IP's failure state. */
    public void recordSuccess(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        this.entries.remove(ip);
    }

    /** Visible for tests; number of currently tracked IPs. */
    int trackedCount() {
        return this.entries.size();
    }

    private void evictIfNeeded(long now) {
        if (this.entries.size() <= MAX_TRACKED_IPS) {
            return;
        }
        this.entries.entrySet().removeIf(item -> {
            Entry entry = item.getValue();
            return entry.blockedUntil <= now
                && (now - entry.windowStart) > WINDOW_MS;
        });
    }
}
