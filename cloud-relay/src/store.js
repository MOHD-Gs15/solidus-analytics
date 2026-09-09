'use strict';
// Solidus Cloud Relay - durable state store.
// MVP persistence: JSON files with atomic writes + an append-only audit JSONL.
// The storage surface is deliberately tiny (load/save/append) so a later
// migration to SQLite changes nothing above this module (PROTOCOL.md §12).
//
// SA2 round (2.1.5): strict-load for users/servers (SA2-024), hot re-read of
// externally edited files (SA2-008), scrypt N=32768 with transparent rehash
// (SA2-017), 0600 file modes (SA2-018), atomic streaming pruneAudit
// (SA2-019), per-account session cap + bounded lastSeen writes (SA2-009),
// and a global row clip on every audit append (SA2-004).

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { config } = require('./config');

// scrypt parameters (SA2-017): new hashes use the current OWASP guidance
// (N=32768). Hashes without a scryptN field verify with the legacy N=16384
// and are transparently re-upgraded after a successful verification.
const SCRYPT_N_LEGACY = 16384;
const SCRYPT_N = Number(process.env.RELAY_SCRYPT_N ?? 32768);

class Store {
  constructor() {
    this.dataDir = config.dataDir;
    fs.mkdirSync(this.dataDir, { recursive: true });
    // SECURITY (SA2-024): users.json/servers.json load STRICTLY. A corrupted
    // file used to be silently swallowed into an EMPTY store, after which
    // the next `npm run user` would mint a full-power owner who knows only
    // that "the file was gone". Fail-closed with an actionable message.
    this.users = this.loadJsonStrict('users.json', { users: [], sessions: [] });
    this.servers = this.loadJsonStrict('servers.json', { servers: [] });
    // alerts.json is relay-owned and self-healing; keep the lenient loader.
    this.alerts = this.loadJson('alerts.json', { rules: [], silenceUntil: 0, silence: {} });
    this.auditPath = path.join(this.dataDir, 'audit.jsonl');
    if (!fs.existsSync(this.auditPath)) fs.writeFileSync(this.auditPath, '', { mode: 0o600 });
    // SA2-018: best-effort 0600 on pre-existing data files (created by older
    // versions with umask 022 = world-readable session tokens).
    for (const f of ['users.json', 'servers.json', 'alerts.json', 'audit.jsonl']) {
      try { fs.chmodSync(path.join(this.dataDir, f), 0o600); } catch { /* not fatal */ }
    }
    this.mtimes = new Map();   // name -> mtimeMs at load/save (SA2-008)
    this.mtimes.set('users.json', this.statMtime('users.json'));
    this.mtimes.set('servers.json', this.statMtime('servers.json'));
  }

  statMtime(name) {
    try { return fs.statSync(path.join(this.dataDir, name)).mtimeMs; } catch { return 0; }
  }

  loadJson(name, fallback) {
    const p = path.join(this.dataDir, name);
    try {
      return { ...fallback, ...JSON.parse(fs.readFileSync(p, 'utf8')) };
    } catch {
      return { ...fallback };
    }
  }

  loadJsonStrict(name, fallback) {
    const p = path.join(this.dataDir, name);
    if (!fs.existsSync(p)) return { ...fallback };
    let parsed;
    try {
      parsed = JSON.parse(fs.readFileSync(p, 'utf8'));
    } catch (e) {
      throw new Error(
        `${name} is CORRUPT (${e.message}). Refusing to start with an empty account store - ` +
        `restore the file from backup, or move it away and consciously recreate the accounts with 'npm run user'.`);
    }
    return { ...fallback, ...parsed };
  }

  /**
   * SA2-008: re-read users/servers when an external edit (CLI, incident
   * response, PM2 sibling) changed the file on disk. The relay used to keep
   * a boot-time copy forever AND clobber external edits on the next save -
   * making file-based revocation a fiction. Read paths now reload first.
   */
  reloadIfChanged(name, target) {
    const m = this.statMtime(name);
    if (m === this.mtimes.get(name)) return false;
    const fresh = this.loadJsonStrict(name, target === 'users'
      ? { users: [], sessions: [] } : { servers: [] });
    if (target === 'users') this.users = fresh; else this.servers = fresh;
    this.mtimes.set(name, m);
    return true;
  }

  maybeReloadUsers() { this.reloadIfChanged('users.json', 'users'); }
  maybeReloadServers() { this.reloadIfChanged('servers.json', 'servers'); }

  saveJson(name, obj) {
    const p = path.join(this.dataDir, name);
    // SA2-008: warn loudly instead of silently clobbering an external edit
    // that landed between our last sync and this write.
    if (this.mtimes.has(name) && this.statMtime(name) !== this.mtimes.get(name)) {
      console.error(`[store] ${name} changed on disk since the last relay write - overwriting now (external edits may be lost)`);
    }
    const tmp = p + '.tmp';
    // SA2-018: 0600 - users.json carries live Bearer tokens + scrypt hashes,
    // servers.json carries pairing-secret hashes.
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, p);
    this.mtimes.set(name, fs.statSync(p).mtimeMs);
  }

  saveUsers() { this.saveJson('users.json', this.users); }
  saveServers() { this.saveJson('servers.json', this.servers); }
  saveAlerts() { this.saveJson('alerts.json', this.alerts); }

  // ---- users / auth ------------------------------------------------------

  hashPassword(password, salt) {
    const s = salt || crypto.randomBytes(16).toString('hex');
    // maxmem: 128*N*r needs 32 MiB exactly at N=32768 - the OpenSSL default
    // cap is 32 MiB inclusive of overhead, so raise it explicitly.
    const hash = crypto.scryptSync(String(password), s, 32,
      { N: SCRYPT_N, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }).toString('hex');
    return { salt: s, hash, scryptN: SCRYPT_N };
  }

  verifyPassword(user, password) {
    try {
      const N = user.scryptN || SCRYPT_N_LEGACY;
      const hash = crypto.scryptSync(String(password), user.salt, 32,
        { N, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }).toString('hex');
      return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(user.hash, 'hex'));
    } catch {
      return false;
    }
  }

  /**
   * SA2-017: transparently upgrades a legacy N=16384 hash to the current
   * parameters after a SUCCESSFUL verification. Called on login and on the
   * D-class password re-entry path.
   */
  maybeRehash(user, password) {
    if (user.scryptN === SCRYPT_N) return;
    try {
      const { hash, scryptN } = this.hashPassword(password, user.salt);
      user.hash = hash;
      user.scryptN = scryptN;
      this.maybeReloadUsers();
      this.saveUsers();
    } catch { /* best effort */ }
  }

  findUser(name) {
    this.maybeReloadUsers();
    return this.users.users.find((u) => u.name === name) || null;
  }

  findUserByToken(token) {
    if (!token) return null;
    this.maybeReloadUsers();
    const sess = this.users.sessions.find((s) => s.token === token);
    if (!sess) return null;
    if (Date.now() > sess.expiresAt) {
      this.users.sessions = this.users.sessions.filter((x) => x !== sess);
      this.saveUsers();
      return null;
    }
    // audit C-13: lastSeen used to trigger a full-file rewrite on EVERY token
    // lookup (every authenticated request). Throttle to one write per minute.
    const now = Date.now();
    if (now - sess.lastSeen > 60000) {
      sess.lastSeen = now;
      this.saveUsers();
    }
    const u = this.users.users.find((x) => x.id === sess.userId);
    return u ? { user: u, session: sess } : null;
  }

  createSession(user, dev) {
    this.maybeReloadUsers();
    const sess = {
      token: crypto.randomBytes(32).toString('hex'),
      userId: user.id,
      dev: dev || 'unknown-device',
      created: Date.now(),
      lastSeen: Date.now(),
      expiresAt: Date.now() + config.tokenTtlDays * 86400000,
    };
    this.users.sessions.push(sess);
    // SA2-009: sessions per account are bounded - the oldest session is
    // evicted past the cap, so users.json growth (one full rewrite per
    // login) stays O(1) per account instead of creeping forever.
    const mine = this.users.sessions
      .filter((s) => s.userId === user.id)
      .sort((a, b) => a.created - b.created);
    if (mine.length > config.limits.maxSessionsPerUser) {
      const evict = new Set(mine.slice(0, mine.length - config.limits.maxSessionsPerUser));
      this.users.sessions = this.users.sessions.filter((s) => !evict.has(s));
    }
    this.saveUsers();
    return sess;
  }

  revokeSession(dev, userId) {
    this.maybeReloadUsers();
    const before = this.users.sessions.length;
    this.users.sessions = this.users.sessions.filter((s) => !(s.dev === dev && (!userId || s.userId === userId)));
    this.saveUsers();
    return before - this.users.sessions.length;
  }

  // ---- servers / pairing ---------------------------------------------------

  findServer(serverId) {
    this.maybeReloadServers();
    return this.servers.servers.find((s) => s.serverId === serverId) || null;
  }

  /**
   * Pairs (or re-pairs) a server: stores ONLY sha256(secret) per PROTOCOL.md §11.
   * Returns the server record.
   */
  pairServer({ serverId, name, secret, userId }) {
    this.maybeReloadServers();
    let rec = this.findServer(serverId);
    if (!rec) {
      rec = { serverId, name: name || serverId, addedAt: Date.now() };
      this.servers.servers.push(rec);
    }
    rec.name = name || rec.name;
    rec.secretHash = crypto.createHash('sha256').update(String(secret)).digest('hex');
    rec.userId = userId;
    rec.subscription = rec.subscription || { status: 'active', renewsAt: 0, plan: 'cloud' };
    this.saveServers();
    return rec;
  }

  verifyPairing(serverId, secret) {
    const rec = this.findServer(serverId);
    if (!rec || !rec.secretHash) return null;
    const hash = crypto.createHash('sha256').update(String(secret)).digest('hex');
    return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(rec.secretHash, 'hex')) ? rec : null;
  }

  entitled(serverId) {
    const rec = this.findServer(serverId);
    if (!rec) return false;
    if (!rec.subscription || rec.subscription.status !== 'active') {
      // 14-day read-only grace after expiry (approved tier table)
      return false;
    }
    return true;
  }

  /**
   * SA2-020 (§10): the subscription's 14-day read-only window. True while
   * events/audit remain readable after an expiry (status != active but the
   * grace timestamp is still in the future).
   */
  readableAfterExpiry(serverId) {
    const rec = this.findServer(serverId);
    if (!rec) return false;
    if (rec.subscription?.status === 'active') return true;
    const until = rec.subscription?.readOnlyUntil || 0;
    return Date.now() < until;
  }

  // ---- audit ledger (append-only) -----------------------------------------

  audit(row) {
    // SECURITY (SA2-004): the ledger is the forensic record - attacker- or
    // error-controlled free text is clipped to the §6.4 caps at the single
    // choke point so no row can balloon (a rejection frame used to carry up
    // to the 256 KiB WS payload verbatim into audit.jsonl).
    const clip = (v, n) => v == null ? null : String(v).slice(0, n);
    const safe = { ...row };
    for (const k of ['rid', 'cmd', 'target', 'reason', 'error', 'idemKey', 'actorName', 'code', 'status', 'kind', 'serverId']) {
      if (safe[k] !== undefined) safe[k] = clip(safe[k], 256);
    }
    if (safe.detail !== undefined) safe.detail = clip(safe.detail, 512);
    const line = JSON.stringify({ ts: Date.now(), ...safe });
    fs.appendFileSync(this.auditPath, line + '\n', { mode: 0o600 });
  }

  /**
   * Backward tail scan (audit P1-6): rows are needed newest-first and the
   * ledger is append-only in chronological order, so we read 64 KiB chunks
   * from the END of the file instead of loading the whole ledger. Once a row
   * is older than fromMs, every earlier row is older too -> stop scanning.
   * Splitting on 0x0A operates on raw Buffers, so multi-byte UTF-8 sequences
   * split across chunk boundaries stay intact.
   */
  auditQuery({ fromMs, toMs, actor, cmd, target, limit = 100, serverIds }) {
    const max = Math.min(limit, 2000);
    const rows = [];
    let fd;
    try { fd = fs.openSync(this.auditPath, 'r'); } catch { return rows; }
    const CHUNK = 64 * 1024;
    // audit C-1: tenant scoping - null/undefined serverIds means "no filter"
    // (owner); an array restricts rows to the caller's own servers. Rows with
    // no serverId (e.g. cross-cutting auth rows) are owner-only.
    const serverFilter = Array.isArray(serverIds) ? new Set(serverIds) : null;
    const matches = (r) => (!toMs || r.ts <= toMs)
      && (!serverFilter || (r.serverId != null && serverFilter.has(r.serverId)))
      && (!actor || r.actorName === actor)
      && (!cmd || r.cmd === cmd)
      && (!target || r.target === target);
    let stop = false;
    const consider = (buf) => {
      let r;
      try { r = JSON.parse(buf.toString('utf8')); } catch { return; }
      if (fromMs && r.ts < fromMs) { stop = true; return; }
      if (matches(r)) rows.push(r);
    };
    try {
      const size = fs.fstatSync(fd).size;
      let pos = size;
      let pendingHead = Buffer.alloc(0); // earliest incomplete line fragment
      while (pos > 0 && !stop && rows.length < max) {
        const read = Math.min(CHUNK, pos);
        pos -= read;
        const chunk = Buffer.allocUnsafe(read);
        fs.readSync(fd, chunk, 0, read, pos);
        const combined = pendingHead.length ? Buffer.concat([chunk, pendingHead]) : chunk;
        const nl = combined.indexOf(0x0a);
        if (nl === -1) {
          // a single line larger than the chunk: keep accumulating backward
          pendingHead = combined;
          continue;
        }
        pendingHead = combined.subarray(0, nl);
        // everything after the first newline is complete lines; the trailing
        // split element is '' when the window ends at a line boundary.
        const lines = combined.subarray(nl + 1).toString('utf8').split('\n');
        for (let i = lines.length - 1; i >= 0 && !stop && rows.length < max; i--) {
          if (!lines[i]) continue;
          let r;
          try { r = JSON.parse(lines[i]); } catch { continue; }
          if (fromMs && r.ts < fromMs) { stop = true; break; }
          if (matches(r)) rows.push(r);
        }
      }
      // pos == 0: the pending head IS the file's first line.
      if (!stop && rows.length < max && pendingHead.length) consider(pendingHead);
    } finally {
      fs.closeSync(fd);
    }
    return rows;
  }

  /**
   * SA2-019: prune is now ATOMIC and STREAMING. The old version read the
   * whole ledger into memory and wrote it back IN PLACE - a crash mid-write
   * truncated/destroyed "the official sequential ledger" (§12), and the
   * memory spike grew with the file. Now lines stream through a bounded
   * buffer into a 0600 temp file that atomically replaces the original;
   * any failure leaves the original untouched.
   */
  pruneAudit() {
    const cutoff = Date.now() - config.auditRetentionDays * 86400000;
    const tmp = this.auditPath + '.prune.tmp';
    let src;
    let dst;
    try {
      src = fs.createReadStream(this.auditPath, { encoding: 'utf8' });
      dst = fs.createWriteStream(tmp, { encoding: 'utf8', mode: 0o600 });
    } catch {
      try { dst?.close(); } catch {}
      try { fs.unlinkSync(tmp); } catch {}
      return;
    }
    let leftover = '';
    let failed = false;
    src.on('error', () => { failed = true; try { dst.destroy(); } catch {} });
    dst.on('error', () => { failed = true; src.destroy(); });
    src.on('data', (chunk) => {
      if (failed) return;
      const text = leftover + chunk;
      const lines = text.split('\n');
      leftover = lines.pop(); // last element is an incomplete line (or '')
      const kept = lines.filter((l) => {
        if (!l.trim()) return false;
        try { return JSON.parse(l).ts >= cutoff; } catch { return false; }
      });
      if (kept.length) dst.write(kept.join('\n') + '\n');
    });
    src.on('end', () => {
      if (failed) return;
      if (leftover.trim()) {
        try { if (JSON.parse(leftover).ts >= cutoff) dst.write(leftover + '\n'); } catch {}
      }
      dst.end(() => {
        if (failed) { try { fs.unlinkSync(tmp); } catch {} return; }
        try { fs.renameSync(tmp, this.auditPath); } catch { try { fs.unlinkSync(tmp); } catch {} }
      });
    });
  }
}

module.exports = { Store, SCRYPT_N, SCRYPT_N_LEGACY };
