package com.solidus.analytics.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the per-IP authentication rate limiter that protects the
 * dashboard's PBKDF2 verification from CPU exhaustion and online guessing.
 */
@DisplayName("AuthRateLimiter")
class AuthRateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.9";

    @Test
    @DisplayName("blocks an IP after five failures inside the window")
    void blocksAfterFiveFailures() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AuthRateLimiter limiter = new AuthRateLimiter(clock::get);

        for (int i = 0; i < AuthRateLimiter.MAX_FAILED_ATTEMPTS - 1; i++) {
            limiter.recordFailure(IP);
            assertFalse(limiter.isBlocked(IP), "must not block before the limit");
        }
        limiter.recordFailure(IP);
        assertTrue(limiter.isBlocked(IP), "must block once the failure limit is reached");
        assertTrue(limiter.getRemainingLockMs(IP) > 0);
    }

    @Test
    @DisplayName("a successful login clears the failure counter")
    void successClearsFailures() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AuthRateLimiter limiter = new AuthRateLimiter(clock::get);

        for (int i = 0; i < AuthRateLimiter.MAX_FAILED_ATTEMPTS - 1; i++) {
            limiter.recordFailure(IP);
        }
        limiter.recordSuccess(IP);
        limiter.recordFailure(IP);

        assertFalse(limiter.isBlocked(IP), "success must reset the counter");
    }

    @Test
    @DisplayName("old failures outside the window do not count")
    void staleFailuresStartNewWindow() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AuthRateLimiter limiter = new AuthRateLimiter(clock::get);

        for (int i = 0; i < AuthRateLimiter.MAX_FAILED_ATTEMPTS - 1; i++) {
            limiter.recordFailure(IP);
        }
        // Move past the window: the old failures go stale.
        clock.addAndGet(AuthRateLimiter.WINDOW_MS + 1);
        limiter.recordFailure(IP);

        assertFalse(limiter.isBlocked(IP), "only one fresh failure is in the new window");
    }

    @Test
    @DisplayName("lockout expires after the lockout duration")
    void lockoutExpires() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AuthRateLimiter limiter = new AuthRateLimiter(clock::get);

        for (int i = 0; i < AuthRateLimiter.MAX_FAILED_ATTEMPTS; i++) {
            limiter.recordFailure(IP);
        }
        assertTrue(limiter.isBlocked(IP));

        clock.addAndGet(AuthRateLimiter.LOCKOUT_MS + 1);
        assertFalse(limiter.isBlocked(IP), "lockout must lift after the duration");
    }

    @Test
    @DisplayName("failure counts are isolated per IP")
    void countsAreIsolatedPerIp() {
        AuthRateLimiter limiter = new AuthRateLimiter(() -> 1_000_000L);

        for (int i = 0; i < AuthRateLimiter.MAX_FAILED_ATTEMPTS; i++) {
            limiter.recordFailure(IP);
        }
        assertTrue(limiter.isBlocked(IP));
        assertFalse(limiter.isBlocked(OTHER_IP), "another IP must be unaffected");
        assertFalse(limiter.isBlocked(null), "unknown IP must not be blocked");
        assertFalse(limiter.isBlocked(""), "blank IP must not be blocked");
    }

    @Test
    @DisplayName("null and blank IPs are tolerated (no crash, no tracking)")
    void nullAndBlankIpTolerated() {
        AuthRateLimiter limiter = new AuthRateLimiter(() -> 1_000_000L);

        for (int i = 0; i < AuthRateLimiter.MAX_FAILED_ATTEMPTS + 3; i++) {
            limiter.recordFailure(null);
            limiter.recordFailure("");
        }
        assertEquals(0, limiter.trackedCount());
        assertFalse(limiter.isBlocked(null));
    }

    @Test
    @DisplayName("failures spread across windows cannot accumulate into a block")
    void spreadingFailuresNeverBlocks() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AuthRateLimiter limiter = new AuthRateLimiter(clock::get);

        // One failure every (window / 2) - never two failures in the same window.
        for (int i = 0; i < 40; i++) {
            limiter.recordFailure(IP);
            clock.addAndGet(AuthRateLimiter.WINDOW_MS / 2 + 1);
            assertFalse(limiter.isBlocked(IP), "slow attempts must never trigger lockout, iteration " + i);
        }
    }
}
