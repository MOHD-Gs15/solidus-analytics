'use strict';
// Solidus Cloud Relay - durable state store.
// MVP persistence: JSON files with atomic writes + an append-only audit JSONL.
// The storage surface is deliberately tiny (load/save/append) so a later
// migration to SQLite changes nothing above this module (PROTOCOL.md §12).

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { config } = require('./config');

class Store {
  constructor() {
    this.dataDir = config.dataDir;
    fs.mkdirSync(this.dataDir, { recursive: true });
    this.users = this.loadJson('users.json', { users: [], sessions: [] });
    this.servers = this.loadJson('servers.json', { servers: [] });
    this.alerts = this.loadJson('alerts.json', { rules: [], silenceUntil: 0 });
    this.auditPath = path.join(this.dataDir, 'audit.jsonl');
    if (!fs.existsSync(this.auditPath)) fs.writeFileSync(this.auditPath, '');
  }

  loadJson(name, fallback) {
    const p = path.join(this.dataDir, name);
    try {
      return { ...fallback, ...JSON.parse(fs.readFileSync(p, 'utf8')) };
    } catch {
      return { ...fallback };
    }
  }

  saveJson(name, obj) {
    const p = path.join(this.dataDir, name);
    const tmp = p + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2));
    fs.renameSync(tmp, p);
  }

  saveUsers() { this.saveJson('users.json', this.users); }
  saveServers() { this.saveJson('servers.json', this.servers); }
  saveAlerts() { this.saveJson('alerts.json', this.alerts); }

  // ---- users / auth ------------------------------------------------------

  hashPassword(password, salt) {
    const s = salt || crypto.randomBytes(16).toString('hex');
    const hash = crypto.scryptSync(String(password), s, 32, { N: 16384, r: 8, p: 1 }).toString('hex');
    return { salt: s, hash };
  }

  verifyPassword(user, password) {
    try {
      const { hash } = this.hashPassword(password, user.salt);
      return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(user.hash, 'hex'));
    } catch {
      return false;
    }
  }

  findUser(name) {
    return this.users.users.find((u) => u.name === name) || null;
  }

  findUserByToken(token) {
    if (!token) return null;
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
    const sess = {
      token: crypto.randomBytes(32).toString('hex'),
      userId: user.id,
      dev: dev || 'unknown-device',
      created: Date.now(),
      lastSeen: Date.now(),
      expiresAt: Date.now() + config.tokenTtlDays * 86400000,
    };
    this.users.sessions.push(sess);
    this.saveUsers();
    return sess;
  }

  revokeSession(dev, userId) {
    const before = this.users.sessions.length;
    this.users.sessions = this.users.sessions.filter((s) => !(s.dev === dev && (!userId || s.userId === userId)));
    this.saveUsers();
    return before - this.users.sessions.length;
  }

  // ---- servers / pairing ---------------------------------------------------

  findServer(serverId) {
    return this.servers.servers.find((s) => s.serverId === serverId) || null;
  }

  /**
   * Pairs (or re-pairs) a server: stores ONLY sha256(secret) per PROTOCOL.md §11.
   * Returns the server record.
   */
  pairServer({ serverId, name, secret, userId }) {
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

  // ---- audit ledger (append-only) -----------------------------------------

  audit(row) {
    const line = JSON.stringify({ ts: Date.now(), ...row });
    fs.appendFileSync(this.auditPath, line + '\n');
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

  pruneAudit() {
    const cutoff = Date.now() - config.auditRetentionDays * 86400000;
    const text = fs.readFileSync(this.auditPath, 'utf8');
    const kept = text.split('\n').filter((l) => {
      if (!l.trim()) return false;
      try { return JSON.parse(l).ts >= cutoff; } catch { return false; }
    });
    fs.writeFileSync(this.auditPath, kept.join('\n') + (kept.length ? '\n' : ''));
  }
}

module.exports = { Store };
