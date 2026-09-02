'use strict';
// Solidus Cloud Relay - login rate limiter (audit P0-2).
//
// Mirrors the Java-side AuthRateLimiter policy exactly (5 failures in a
// sliding 60 s window -> 5 minute lockout, success clears the counter), so the
// cloud login edge matches the hardening the local dashboard already has.
//
// Keys are tracked BOTH per-IP and per-account-name:
//   - per-IP stops distributed guessing against many accounts,
//   - per-name stops one targeted account being hammered from rotating IPs.
//
// isBlocked() is checked BEFORE scrypt runs, so a locked source is rejected
// cheaply and cannot pin the relay CPU on password derivations. State is
// in-memory by design: a relay restart gives a clean slate (same rationale as
// the Java limiter - never permanently lock out an owner).
//
// The tracked-key set is bounded (oldest idle entries evicted) so an attacker
// cannot grow memory unboundedly with spoofed addresses or name floods.

const MAX_FAILED_ATTEMPTS = 5;
const WINDOW_MS = 60_000;
const LOCKOUT_MS = 300_000;
const MAX_TRACKED = 10_000;

class LoginLimiter {
  /** @param {() => number} [clock] injectable millis clock for tests */
  constructor(clock = Date.now) {
    this.clock = clock;
    /** @type {Map<string, {windowStart:number, failures:number, blockedUntil:number}>} */
    this.entries = new Map();
  }

  _check(key) {
    const e = this.entries.get(key);
    if (!e) return false;
    return e.blockedUntil > 0 && this.clock() < e.blockedUntil;
  }

  /** True while the IP or account name is locked out. */
  isBlocked(ip, name) {
    if (ip && this._check(this._ipKey(ip))) return true;
    if (name) return this._check(this._nameKey(String(name).toLowerCase()));
    return false;
  }

  /** Milliseconds until the lock lifts (max over ip+name); 0 when open. */
  getRemainingLockMs(ip, name) {
    let ms = 0;
    for (const key of [ip && this._ipKey(ip), name && this._nameKey(String(name).toLowerCase())]) {
      if (!key) continue;
      const e = this.entries.get(key);
      if (e && e.blockedUntil > 0) ms = Math.max(ms, e.blockedUntil - this.clock());
    }
    return Math.max(0, ms);
  }

  /** Records one failed login against both the IP and the account name. */
  recordFailure(ip, name) {
    const now = this.clock();
    for (const key of [ip && this._ipKey(ip), name && this._nameKey(String(name).toLowerCase())]) {
      if (!key) continue;
      let e = this.entries.get(key);
      if (!e) {
        e = { windowStart: now, failures: 0, blockedUntil: 0 };
        this.entries.set(key, e);
      }
      if (now - e.windowStart > WINDOW_MS) {
        // New window: previous failures are stale.
        e.windowStart = now;
        e.failures = 0;
      }
      e.failures++;
      if (e.failures >= MAX_FAILED_ATTEMPTS) e.blockedUntil = now + LOCKOUT_MS;
    }
    this._evict(now);
  }

  /** Records a successful login - clears both counters. */
  recordSuccess(ip, name) {
    this.entries.delete(this._ipKey(ip));
    this.entries.delete(this._nameKey(String(name || '').toLowerCase()));
  }

  /** Visible for tests. */
  size() { return this.entries.size; }

  _ipKey(ip) { return 'ip:' + String(ip || ''); }
  _nameKey(name) { return 'nm:' + name; }

  _evict(now) {
    if (this.entries.size <= MAX_TRACKED) return;
    this.entries.forEach((e, key) => {
      if (e.blockedUntil <= now && now - e.windowStart > WINDOW_MS) this.entries.delete(key);
    });
  }
}

module.exports = { LoginLimiter, MAX_FAILED_ATTEMPTS, WINDOW_MS, LOCKOUT_MS };
