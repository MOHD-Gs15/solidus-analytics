'use strict';
// Solidus Cloud Relay - durable relay state (P2: SQLite store, PROTOCOL.md §6.6/§8).
//
// Everything that used to be process-memory and died with a relay restart now
// lives in a single SQLite file (node:sqlite, WAL):
//
//   events    - per-server event ring buffers (last N frames) so PWA clients
//               still get replay after a relay restart (and the ring now also
//               survives agent disconnects, which used to wipe it)
//   commands  - store&forward lifecycle: queued (agent offline, awaiting
//               flush), sent (in flight), done (terminal, kept for retention)
//   idem      - relay-side financial idempotency cache (§8: 10 min window),
//               durable so a restart cannot turn a replay into a re-forward
//
// users/servers/alerts stay atomic JSON saves and the audit ledger stays an
// append-only JSONL (P1-6 tail-scan) on purpose: SQLite is for the hot
// store&forward surfaces, not the cold configuration and the immutable ledger.
//
// The agent keeps its 48 h cloud_idempotency table as the authoritative last
// line of defense; relay-side durability shrinks - but does not eliminate -
// the re-forward window (see resolvePending write order in server.js).

const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const SCHEMA = `
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  server_id TEXT NOT NULL,
  ts INTEGER NOT NULL,
  type TEXT NOT NULL,
  frame TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_server ON events(server_id, id);

CREATE TABLE IF NOT EXISTS commands (
  rid TEXT PRIMARY KEY,
  server_id TEXT NOT NULL,
  user_id TEXT,
  cmd TEXT NOT NULL,
  target TEXT,
  actor TEXT,
  idem_key TEXT,
  issued_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  state TEXT NOT NULL,             -- queued | sent | done
  frame TEXT NOT NULL,             -- exact forward frame for the agent
  result TEXT,                     -- terminal outcome JSON (state = done)
  done_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_commands_server ON commands(server_id, state);
CREATE INDEX IF NOT EXISTS idx_commands_done ON commands(state, done_at);

CREATE TABLE IF NOT EXISTS idem (
  key TEXT PRIMARY KEY,            -- composite userId:cmd:idemKey
  status TEXT,
  code TEXT,
  error TEXT,
  data TEXT,                       -- JSON of the result d payload
  at INTEGER NOT NULL
);
`;

class RelayDb {
  constructor(dbPath) {
    this.dbPath = dbPath;
    fs.mkdirSync(path.dirname(dbPath), { recursive: true });
    this.db = new DatabaseSync(dbPath);
    this.db.exec('PRAGMA journal_mode = WAL');
    this.db.exec('PRAGMA synchronous = NORMAL');
    this.db.exec('PRAGMA foreign_keys = OFF');
    this.db.exec(SCHEMA);
    this.stmts = {
      insEvent: this.db.prepare('INSERT INTO events(server_id, ts, type, frame) VALUES(?,?,?,?)'),
      trimEvents: this.db.prepare(
        `DELETE FROM events WHERE server_id = ? AND id NOT IN
         (SELECT id FROM events WHERE server_id = ? ORDER BY id DESC LIMIT ?)`),
      loadEvents: this.db.prepare(
        'SELECT frame FROM events WHERE server_id = ? ORDER BY id ASC'),
      insCmd: this.db.prepare(
        `INSERT INTO commands(rid, server_id, user_id, cmd, target, actor, idem_key,
          issued_at, expires_at, state, frame) VALUES(?,?,?,?,?,?,?,?,?,?,?)`),
      markSent: this.db.prepare("UPDATE commands SET state = 'sent' WHERE rid = ? AND state = 'queued'"),
      finishCmd: this.db.prepare(
        `UPDATE commands SET state = 'done', result = ?, done_at = ? WHERE rid = ?`),
      queued: this.db.prepare(
        "SELECT rid, expires_at, frame FROM commands WHERE server_id = ? AND state = 'queued' ORDER BY issued_at ASC, rowid ASC"),
      queueCount: this.db.prepare(
        "SELECT COUNT(*) AS n FROM commands WHERE server_id = ? AND state = 'queued'"),
      ctx: this.db.prepare(
        'SELECT server_id, user_id, cmd, target, actor, idem_key FROM commands WHERE rid = ?'),
      inFlight: this.db.prepare("SELECT rid FROM commands WHERE state = 'sent'"),
      expiredQueued: this.db.prepare(
        "SELECT rid FROM commands WHERE state = 'queued' AND expires_at < ?"),
      pruneDone: this.db.prepare(
        "DELETE FROM commands WHERE state = 'done' AND done_at IS NOT NULL AND done_at < ?"),
      idemGet: this.db.prepare('SELECT status, code, error, data, at FROM idem WHERE key = ?'),
      idemSet: this.db.prepare(
        'INSERT OR REPLACE INTO idem(key, status, code, error, data, at) VALUES(?,?,?,?,?,?)'),
      idemPrune: this.db.prepare('DELETE FROM idem WHERE at < ?'),
      counts: this.db.prepare(
        `SELECT (SELECT COUNT(*) FROM events) AS events,
                (SELECT COUNT(*) FROM commands) AS commands,
                (SELECT COUNT(*) FROM idem) AS idem`),
    };
  }

  // ---- event rings ------------------------------------------------------

  /** Append an agent event frame and (when trim=true) keep only the last
   *  ringSize rows for that server. Trim is driven by the caller's in-memory
   *  ring overflow so the DB stays within one ring of the memory view. */
  appendEvent(serverId, frame, ringSize, trim = false) {
    this.stmts.insEvent.run(serverId, Number(frame.ts || Date.now()),
      String(frame.type || 'evt'), JSON.stringify(frame));
    if (trim) this.trimEvents(serverId, ringSize);
  }

  trimEvents(serverId, ringSize) {
    this.stmts.trimEvents.run(serverId, serverId, Number(ringSize));
  }

  /** Map(serverId -> frames oldest..newest), each capped at ringSize. */
  loadRings(ringSize) {
    const rings = new Map();
    const servers = this.db.prepare('SELECT DISTINCT server_id FROM events').all();
    for (const { server_id } of servers) {
      const rows = this.stmts.loadEvents.all(server_id);
      const frames = rows.map((r) => JSON.parse(r.frame));
      if (frames.length > ringSize) frames.splice(0, frames.length - ringSize);
      rings.set(server_id, frames);
    }
    return rings;
  }

  // ---- command lifecycle --------------------------------------------------

  insertCommand({ rid, serverId, userId, cmd, target, actor, idemKey,
    issuedAt, expiresAt, state, frame }) {
    this.stmts.insCmd.run(String(rid), String(serverId),
      userId == null ? null : String(userId), String(cmd),
      target == null ? null : String(target),
      actor == null ? null : JSON.stringify(actor),
      idemKey == null ? null : String(idemKey),
      Number(issuedAt), Number(expiresAt), String(state), JSON.stringify(frame));
  }

  /** queue flush on agent reconnect: pending forward frames in order. */
  queuedCommands(serverId) {
    return this.stmts.queued.all(String(serverId)).map((r) => ({
      rid: r.rid, expiresAt: r.expires_at, frame: JSON.parse(r.frame),
    }));
  }

  queueCount(serverId) {
    return Number(this.stmts.queueCount.get(String(serverId)).n);
  }

  markSent(rid) { this.stmts.markSent.run(String(rid)); }

  /** Record the terminal outcome of a command. Returns true if the row moved
   *  to done (false when it was already closed - e.g. boot closure raced). */
  finishCommand(rid, result) {
    return Number(this.db.prepare(
      `UPDATE commands SET state = 'done', result = ?, done_at = ?
       WHERE rid = ? AND state != 'done'`).run(
      JSON.stringify(result || {}), Date.now(), String(rid)).changes) === 1;
  }

  /** Post-restart context for a command rid: everything resolvePending needs
   *  to write the audit row and broadcast cmd.audit when the live pending
   *  entry (with its originator socket) is gone. */
  commandContext(rid) {
    const r = this.stmts.ctx.get(String(rid));
    if (!r) return null;
    return {
      rid: String(rid), serverId: r.server_id, userId: r.user_id, cmd: r.cmd,
      target: r.target, actor: r.actor ? JSON.parse(r.actor) : null,
      idemKey: r.idem_key, ws: null,
    };
  }

  /** Boot closure: commands that were in flight when the previous process
   *  died. Marks them done with a timeout/E_RESTART result and returns their
   *  full context rows so the caller can write the audit rows. */
  failInFlight() {
    const out = [];
    for (const { rid } of this.stmts.inFlight.all()) {
      const ctx = this.commandContext(rid);
      if (!ctx) continue;
      this.finishCommand(rid, { status: 'timeout', code: 'E_RESTART', error: 'relay restarted while command was in flight' });
      out.push(ctx);
    }
    return out;
  }

  /** Boot closure for queued commands that expired while the relay was down
   *  (no agent ever came back to flush them). */
  closeExpiredQueued() {
    const out = [];
    for (const { rid } of this.stmts.expiredQueued.all(Date.now()).map((x) => ({ rid: x.rid }))) {
      const ctx = this.commandContext(rid);
      if (!ctx) continue;
      this.finishCommand(rid, { status: 'rejected', code: 'E_EXPIRED', error: 'expired while relay was down' });
      out.push(ctx);
    }
    return out;
  }

  pruneCommands(cutoffMs) { this.stmts.pruneDone.run(Number(cutoffMs)); }

  // ---- financial idempotency (§8, relay-side 10 min window) ---------------

  idemGet(key) {
    const r = this.stmts.idemGet.get(String(key));
    if (!r) return null;
    let d = {};
    try { d = r.data ? JSON.parse(r.data) : {}; } catch { d = {}; }
    return { status: r.status, code: r.code, error: r.error, d, at: r.at };
  }

  idemSet(key, { status, code, error, d, at }) {
    this.stmts.idemSet.run(String(key), status ?? null, code ?? null, error ?? null,
      JSON.stringify(d || {}), Number(at || Date.now()));
  }

  idemPrune(cutoffMs) { this.stmts.idemPrune.run(Number(cutoffMs)); }

  // ---- misc ----------------------------------------------------------------

  stats() { return this.stmts.counts.get(); }

  close() {
    try { this.db.close(); } catch { /* already closed */ }
  }
}

module.exports = { RelayDb };
