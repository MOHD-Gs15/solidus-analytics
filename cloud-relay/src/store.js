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
    sess.lastSeen = Date.now();
    const u = this.users.users.find((x) => x.id === sess.userId);
    this.saveUsers();
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

  auditQuery({ fromMs, toMs, actor, cmd, target, limit = 100 }) {
    const rows = [];
    const text = fs.readFileSync(this.auditPath, 'utf8');
    for (const line of text.split('\n').reverse()) {
      if (!line.trim()) continue;
      let r;
      try { r = JSON.parse(line); } catch { continue; }
      if (fromMs && r.ts < fromMs) continue;
      if (toMs && r.ts > toMs) continue;
      if (actor && r.actorName !== actor) continue;
      if (cmd && r.cmd !== cmd) continue;
      if (target && r.target !== target) continue;
      rows.push(r);
      if (rows.length >= Math.min(limit, 200)) break;
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
