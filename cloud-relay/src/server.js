'use strict';
// Solidus Cloud Relay - main server.
//
//   wss://relay/agent   <- solidus-analytics Cloud Agent (outbound-only, §4.1)
//   wss://relay/app     <- PWA clients (§4.2)
//   http  /api/login /api/pair /api/push-subscribe /api/state
//   http  /            <- PWA static files
//
// Implements the v1 contract: allow-list + roles, risk-tiered confirmations
// (typed-name for W2, prepare-token + password + hold for D), idempotency
// cache, rate ceilings, store&forward with TTL, authoritative audit ledger,
// entitlement gate, alert rules, heartbeat loss detection.

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { WebSocketServer } = require('ws');
const { config, COMMAND_META, RANK } = require('./config');
const { Store } = require('./store');
const { RelayDb } = require('./db');
const { AlertEngine, pushReady } = require('./alerts');
const { LoginLimiter } = require('./login-limiter');

const store = new Store();
const alerts = new AlertEngine(store);
const loginLimiter = new LoginLimiter();
const relayDb = new RelayDb(config.dbPath);

// ---- live state -----------------------------------------------------------

const agents = new Map();   // serverId -> { ws, caps, meta, lastSeen }
const clients = new Map();  // ws -> { user, role, serverId, dev }
const idemCache = new Map();      // `${userId}:${cmd}:${idemKey}` -> result (L1 over relayDb)
const prepareTokens = new Map();  // token -> { userId, cmd, target, preparedAt }
const rate = new Map();           // userId -> { financial:[], w2:[], w1:[], r:[], d:[], broadcast:[] }
const pending = new Map();        // rid -> { ws (originator), userId }
const wsTickets = new Map();      // ticket -> { sessionToken, userId, expiresAt }  (single-use, P1-5)

// P2 durable rings: per-server event replay buffers, seeded from relay.db at
// boot and kept in sync on every push. Independent of agent sockets, so the
// ring now survives BOTH agent disconnects and relay restarts (§6.6).
const rings = new Map();   // serverId -> [frames oldest..newest]
for (const [sid, frames] of relayDb.loadRings(config.eventRing)) rings.set(sid, frames);

// boot closure (P2): commands in flight when the previous process died get an
// honest timeout + audit row instead of vanishing; queued commands that
// expired during the downtime are closed as E_EXPIRED. Queued commands that
// are still alive stay queued and flush when the agent reconnects.
for (const ctx of relayDb.failInFlight()) {
  store.audit({ kind: 'cmd', rid: ctx.rid, serverId: ctx.serverId, cmd: ctx.cmd,
    target: ctx.target ?? '', actorId: ctx.userId ?? null,
    actorName: ctx.actor?.name ?? null, actorRole: ctx.actor?.role ?? null,
    status: 'timeout', code: 'E_RESTART', error: 'relay restarted while command was in flight',
    receivedAt: Date.now(), idemKey: ctx.idemKey ?? null });
}
for (const ctx of relayDb.closeExpiredQueued()) {
  store.audit({ kind: 'cmd', rid: ctx.rid, serverId: ctx.serverId, cmd: ctx.cmd,
    target: ctx.target ?? '', actorId: ctx.userId ?? null,
    actorName: ctx.actor?.name ?? null, actorRole: ctx.actor?.role ?? null,
    status: 'rejected', code: 'E_EXPIRED', error: 'expired while relay was down',
    receivedAt: Date.now(), idemKey: ctx.idemKey ?? null });
}

setInterval(() => store.pruneAudit(), 12 * 3600 * 1000).unref();
setInterval(() => {
  relayDb.pruneCommands(Date.now() - config.commandRetentionDays * 86400000);
}, 12 * 3600 * 1000).unref();

// ---------- helpers ---------------------------------------------------------

function nowMs() { return Date.now(); }

function j(obj) { return JSON.stringify(obj); }

function newId(prefix) { return prefix + '-' + crypto.randomUUID().slice(0, 13); }

function agentOf(serverId) {
  const a = agents.get(serverId);
  return a && a.ws && a.ws.readyState === 1 ? a : null;
}

function ringPush(serverId, event) {
  let ring = rings.get(serverId);
  if (!ring) { ring = []; rings.set(serverId, ring); }
  ring.push(event);
  const overflowed = ring.length > config.eventRing;
  if (overflowed) ring.shift();
  // mirror to the durable store; trim only on overflow so the DELETE runs
  // about once per event past the ring size, not once per event.
  relayDb.appendEvent(serverId, event, config.eventRing, overflowed);
}

function broadcastToClients(serverId, frame, exceptWs) {
  for (const [ws, c] of clients) {
    if (c.serverId === serverId && ws !== exceptWs && ws.readyState === 1) {
      ws.send(j(frame));
    }
  }
}

function pushRate(list, max, windowMs = 60000) {
  const t = nowMs();
  while (list.length && t - list[0] > windowMs) list.shift();
  if (list.length >= max) return false;
  list.push(t);
  return true;
}

function rateOf(userId) {
  if (!rate.has(userId)) rate.set(userId, { financial: [], w2: [], w1: [], r: [], d: [], broadcast: [] });
  return rate.get(userId);
}

/** audit C-1: the servers a user owns (owners read the whole ledger). */
function ownedServerIds(client) {
  if (client.role === 'owner') return null;   // null = no filter
  return store.servers.servers
    .filter((s) => s.userId === client.user.id)
    .map((s) => s.serverId);
}

// ---------- security headers (audit P0-1) ------------------------------------

// Same hardening the local dashboard already ships, applied to every relay
// HTTP response. The PWA loads only same-origin assets and speaks to this
// relay over WebSocket, so connect-src allows ws/wss ONLY for the serving
// host. The Host header is sanitized to a strict charset - a hostile value
// can only ever affect the caller's own response, never another session's
// policy. API responses additionally get no-store.
function applySecurityHeaders(req, res, isApi) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Cross-Origin-Resource-Policy', 'same-origin');
  const host = String(req.headers.host || 'localhost').replace(/[^a-zA-Z0-9.:\-_]/g, '') || 'localhost';
  res.setHeader('Content-Security-Policy',
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
    + `connect-src 'self' ws://${host} wss://${host}; frame-ancestors 'none'; `
    + "base-uri 'none'; form-action 'self'; object-src 'none'");
  if (isApi) res.setHeader('Cache-Control', 'no-store');
}

// ---------- WebSocket upgrade tickets (audit P1-5) ----------------------------

// Long-lived session tokens must not travel in the WS URL (they leak into
// proxy and media logs). The PWA exchanges its Bearer token for a 30-second
// single-use ticket, then opens the socket with ?ticket=. The session token
// itself never crosses the wire again.
function issueWsTicket(sessionToken, userId) {
  const ticket = 'wt-' + crypto.randomBytes(32).toString('hex');
  wsTickets.set(ticket, { sessionToken, userId, expiresAt: nowMs() + config.wsTicketTtlMs });
  return { ticket, expiresAt: nowMs() + config.wsTicketTtlMs };
}

function consumeWsTicket(ticket) {
  if (!ticket) return null;
  const t = wsTickets.get(ticket);
  wsTickets.delete(ticket); // single-use, always
  if (!t || nowMs() > t.expiresAt || !t.sessionToken) return null;
  // re-validate the underlying session (expiry / revocation still apply)
  return store.findUserByToken(t.sessionToken);
}

function limitFor(meta, cmd) {
  if (cmd === 'server.broadcast') return { list: 'broadcast', max: config.limits.broadcastPerMin };
  switch (meta.risk) {
    case 'R': return { list: 'r', max: config.limits.rPerMin };
    case 'W1': return { list: 'w1', max: config.limits.w1PerMin };
    case 'W2': return meta.financial
      ? { list: 'financial', max: config.limits.financialPerMin }
      : { list: 'w2', max: config.limits.w2PerMin };
    case 'D': return { list: 'd', max: config.limits.dPerHour, windowMs: 3600000 };
    default: return { list: 'r', max: config.limits.rPerMin };
  }
}

// relay-side command role floors (audit C-1). PROTOCOL §10/§15: viewer = no
// commands, audit.query = admin, audit.export/session.*/alert.* = owner.
const RELAY_CMD_ROLES = {
  'audit.query': 'admin',
  'audit.export': 'owner',
  'session.list': 'owner',
  'session.revoke': 'owner',
  'alert.silence': 'owner',
  'alert.rule.templates': 'owner',
  'alert.rule.manage': 'owner',
  'alert.channel.test': 'owner',
};

// ---------- command validation + dispatch (§6) ------------------------------

function rejectResult(frame, status, code, error, data) {
  return {
    sv: 1, id: frame.id, t: 'evt', type: 'cmd.result', ts: nowMs(),
    d: { rid: frame.rid || frame.id, cmd: frame.cmd, target: frame.target ?? null,
      actor: frame.actor, status, code: code || null, data: data || null, error: error || null, tookMs: 0 },
  };
}

function handleClientCommand(ws, client, frame) {
  const meta = COMMAND_META[frame.cmd];
  const { cmd, target } = frame;
  const serverId = client.serverId;
  const rec = store.findServer(serverId);
  const agent = agentOf(serverId);
  const caps = agent ? agent.caps : null;

  /** Every rejection is ALSO an audit row (§12: accepted or rejected). */
  const reject = (code, error, data) => {
    store.audit({
      kind: 'cmd', rid: frame.rid || frame.id, serverId: serverId || null, cmd: cmd || 'unknown',
      target: target ?? '', reason: frame.reason || '', actorId: client.user.id,
      actorName: client.user.name, actorRole: client.role, status: 'rejected', code,
      error: error || null, receivedAt: nowMs(), idemKey: frame.idemKey || null,
    });
    ws.send(j(rejectResult(frame, 'rejected', code, error, data)));
  };

  // relay-side commands never reach the agent (§15 alerts & audit domain),
  // but they are still role-gated (audit C-1: this used to dispatch BEFORE the
  // only RANK check in the file, handing viewer accounts owner-grade
  // audit.export / alert-rule powers) and rate-limited like every command (G4).
  if (COMMAND_META.relaySide.includes(cmd)) {
    const minRole = RELAY_CMD_ROLES[cmd] || 'owner';
    if (RANK[client.role] === undefined || RANK[client.role] < RANK[minRole]) {
      return reject('E_ROLE', `relay-side command requires ${minRole}`);
    }
    const lim = limitFor({ risk: 'R' }, cmd);
    if (!pushRate(rateOf(client.user.id)[lim.list], lim.max)) {
      return reject('E_RATE', 'rate ceiling exceeded', { retryAfterMs: 30000 });
    }
    return relaySideCommand(ws, client, frame);
  }

  if (!meta) return reject('E_UNKNOWN_CMD', 'command id is not in the catalog allow-list');
  // C-4: fail CLOSED on non-canonical role strings - RANK['Admin'] is
  // undefined and `undefined < x` is always false, so a hand-created user
  // with a mangled role used to pass EVERY gate including D-class.
  if (RANK[client.role] === undefined) return reject('E_ROLE', 'unknown role for this account');
  if (RANK[client.role] < RANK[meta.role]) return reject('E_ROLE', 'role below minRole');
  if (!rec || rec.userId !== client.user.id) return reject('E_UNKNOWN_SERVER', 'server not paired to your account');
  if (!store.entitled(serverId)) return reject('E_ENTITLEMENT', 'subscription inactive - command channel closed');
  if (caps && !caps.includes(cmd) && meta.risk !== 'R') {
    return reject('E_CORE_MISSING', 'agent did not advertise this capability');
  }

  // rate ceilings (G4)
  const lim = limitFor(meta, cmd);
  const bucket = rateOf(client.user.id);
  if (!pushRate(bucket[lim.list], lim.max, lim.windowMs || 60000)) {
    return reject('E_RATE', 'rate ceiling exceeded', { retryAfterMs: 30000 });
  }

  // clock skew (§11)
  if (Math.abs(nowMs() - (frame.issuedAt || nowMs())) > 300000) {
    return reject('E_ARGS', 'issuedAt skews more than 300s from relay time');
  }

  // TTL (§3) - audit C-5: expiresAt is CLAMPED to issuedAt + class TTL, not
  // merely checked as a floor. A forged far-future expiresAt used to let a
  // queued command survive indefinitely and execute long after issuance.
  const ttl = meta.risk === 'D' ? 90000 : config.commandTtlMs;
  const issuedBase = frame.issuedAt || nowMs();
  const expiresAt = Math.min(frame.expiresAt || issuedBase + ttl, issuedBase + ttl);
  if (expiresAt < nowMs()) return reject('E_EXPIRED', 'command TTL exceeded');

  // reason + typed-name confirmation for W2/D (G2)
  if (meta.risk === 'W2' || meta.risk === 'D') {
    if (!frame.reason || !String(frame.reason).trim()) {
      return reject('E_CONFIRM_MISSING', 'reason is mandatory for W2/D');
    }
    if (frame.confirm?.typed !== target) {
      return reject('E_CONFIRM_MISMATCH', 'confirm.typed must equal target exactly');
    }
  }

  // destructive two-phase (§7): prepare token + password re-entry + hold
  if (meta.risk === 'D') {
    const tok = prepareTokens.get(frame.confirm?.token);
    const okToken = tok && tok.userId === client.user.id && tok.cmd === cmd
      && String(tok.target || '') === String(target || '') && nowMs() < tok.validUntil && !tok.used;
    if (!okToken) return reject('E_CONFIRM_MISMATCH', 'valid confirmToken required for destructive commands');
    if (!frame.confirm?.password || !store.verifyPassword(client.user, frame.confirm.password)) {
      return reject('E_AUTH', 'password re-entry failed');
    }
    if (nowMs() - tok.preparedAt < config.destructiveHoldMs) {
      return reject('E_HOLD', `destructive hold not elapsed (${config.destructiveHoldMs}ms)`);
    }
    tok.used = true;
    prepareTokens.delete(frame.confirm.token);
  }

  // idempotency (G3) - L1 memory over the durable L2 relay.db rows, so a
  // relay restart inside the 10 min window still replays the first result
  // instead of re-forwarding money commands to the agent.
  if (meta.financial) {
    if (!frame.idemKey) return reject('E_ARGS', 'idemKey is mandatory for financial commands');
    const key = `${client.user.id}:${cmd}:${frame.idemKey}`;
    let prior = idemCache.get(key);
    if (!prior) {
      prior = relayDb.idemGet(key);
      if (prior) idemCache.set(key, prior);
    }
    if (prior) {
      store.audit({
        kind: 'cmd', rid: frame.rid || frame.id, serverId, cmd, target: target ?? '',
        reason: frame.reason || '', actorId: client.user.id, actorName: client.user.name,
        actorRole: client.role, status: 'applied', code: 'E_IDEM_DUP', receivedAt: nowMs(),
        idemKey: frame.idemKey, duplicate: true,
      });
      const dup = { ...prior.d, duplicate: true };
      const res = rejectResult(frame, prior.status, prior.code, prior.error, dup);
      return ws.send(j(res));
    }
  }

  // forward to the agent (or queue while offline, §6.6)
  const rid = newId('r');
  const forward = {
    sv: 1, id: newId('m'), t: 'cmd', rid, cmd,
    target: target ?? '',
    args: frame.args || {},
    reason: frame.reason || '',
    actor: { uid: client.user.id, name: client.user.name, role: client.role, dev: client.dev },
    issuedAt: frame.issuedAt || nowMs(),
    expiresAt,
    idemKey: frame.idemKey || undefined,
    ts: nowMs(),
  };
  // store&forward preconditions (§6.6), checked BEFORE any state is written
  // so a rejection can never leave a zombie queued row in relay.db.
  if (!agent) {
    if (relayDb.queueCount(serverId) >= config.limits.commandQueue) {
      return reject('E_RATE', 'offline command queue is full');
    }
    if (expiresAt - nowMs() < 30000) {
      return reject('E_EXPIRED', 'TTL too short to queue while agent offline');
    }
  }

  store.audit({ kind: 'cmd', rid, serverId, cmd, target: target ?? '', reason: frame.reason || '',
    actorId: client.user.id, actorName: client.user.name, actorRole: client.role,
    status: agent ? 'sent' : 'queued', receivedAt: nowMs(), idemKey: frame.idemKey || null, args: frame.args || {} });
  // P2 durable lifecycle: the row moves queued -> sent -> done and survives
  // relay restarts; pending keeps the live originator socket only.
  relayDb.insertCommand({ rid, serverId, userId: client.user.id, cmd, target: target ?? '',
    actor: forward.actor, idemKey: frame.idemKey || null,
    issuedAt: forward.issuedAt, expiresAt, state: agent ? 'sent' : 'queued', frame: forward });
  pending.set(rid, { ws, userId: client.user.id, serverId, cmd, target: target ?? '', actor: forward.actor, idemKey: frame.idemKey || null });

  if (agent) {
    agent.ws.send(j(forward));
    setTimeout(() => resolvePending(rid, 'timeout', null, 'no agent result within 120s'), 120000).unref();
  } else {
    // store&forward while offline (§6.6) - the queue IS relay.db now, so a
    // relay crash between accept and flush no longer drops commands.
    ws.send(j({ sv: 1, id: frame.id, t: 'evt', type: 'cmd.queued', d: { rid, cmd, queuePos: relayDb.queueCount(serverId) } }));
  }
}

function resolvePending(rid, status, code, error, data, tookMs) {
  // live pending first; after a relay restart the originator socket is gone
  // but the durable row still carries the context needed for audit + cmd.audit.
  const p = pending.get(rid) || relayDb.commandContext(rid);
  if (!p) return;
  pending.delete(rid);
  relayDb.finishCommand(rid, { status, code: code || null, error: error || null, data: data || null });
  // relay-side idempotency cache for financial commands (G3). Written AFTER
  // the terminal result: a crash between the two re-forwards on retry, and
  // the agent's 48 h cloud_idempotency table remains the last line of defense
  // (§8) - never a false "applied" for a command that never executed.
  if (p.idemKey) {
    const key = `${p.userId}:${p.cmd}:${p.idemKey}`;
    const rec = { status, code, error, d: data || {}, at: nowMs() };
    idemCache.set(key, rec);
    relayDb.idemSet(key, rec);
  }
  const result = {
    sv: 1, id: 'r-' + rid, t: 'evt', type: 'cmd.result', ts: nowMs(),
    d: { rid, cmd: p.cmd, target: p.target, actor: p.actor, status, code: code || null, data: data || null, error: error || null, tookMs: tookMs || 0 },
  };
  if (p.ws && p.ws.readyState === 1) p.ws.send(j(result));
  const auditFrame = { ...result.d, kind: 'cmd', serverId: p.serverId };
  store.audit(auditFrame);
  broadcastToClients(p.serverId, { ...result, type: 'cmd.audit', id: newId('m') }, p.ws);
}

// ---------- relay-side commands (§15) ----------------------------------------

function relaySideCommand(ws, client, frame) {
  const { cmd, args } = frame;
  // audit C-1: the audit row now records the REAL outcome and actor identity
  // (it used to claim status 'applied' before the switch even ran, including
  // for rejected commands).
  const done = (status, data, code) => {
    ws.send(j({
      sv: 1, id: frame.id, t: 'evt', type: 'cmd.result', ts: nowMs(),
      d: { rid: frame.rid || frame.id, cmd, target: frame.target ?? null, actor: { name: client.user.name, role: client.role }, status, code: code || null, data: data || null, tookMs: 0 },
    }));
    store.audit({ kind: 'cmd', serverId: client.serverId ?? null, cmd, target: String(frame.target ?? '').slice(0, 64),
      actorId: client.user.id, actorName: client.user.name, actorRole: client.role,
      status, code: code || null, receivedAt: nowMs(), idemKey: frame.idemKey || null });
  };
  switch (cmd) {
    case 'session.list':
      return done('applied', { sessions: store.users.sessions.filter((s) => s.userId === client.user.id).map((s) => ({ dev: s.dev, lastSeen: s.lastSeen, expiresAt: s.expiresAt })) });
    case 'session.revoke': {
      const n = store.revokeSession(args?.dev, client.user.id);
      return done('applied', { revoked: n });
    }
    case 'audit.query':
      // audit C-1: rows are scoped to the caller's own servers (owners see
      // everything; the ledger previously leaked cross-tenant command history
      // to any admin, and - pre-C-1 - to ANY viewer).
      return done('applied', { rows: store.auditQuery({ ...args, limit: args?.limit || 100, serverIds: ownedServerIds(client) }) });
    case 'audit.export':
      return done('applied', { format: args?.format || 'json', rows: store.auditQuery({ ...args, limit: 2000, serverIds: ownedServerIds(client) }) });
    case 'alert.silence': {
      store.alerts.silenceUntil = nowMs() + Math.min(1440, Math.max(5, args?.minutes || 30)) * 60000;
      store.saveAlerts();
      return done('applied', { silenceUntil: store.alerts.silenceUntil });
    }
    case 'alert.rule.templates': {
      const tpl = {
        tps: { metric: 'tps', op: '<', threshold: 15, forMs: 300000 },
        ram: { metric: 'ram', op: '>', threshold: 90, forMs: 300000 },
        cpu: { metric: 'cpu', op: '>', threshold: 95, forMs: 300000 },
      }[args?.template];
      if (!tpl) return done('rejected', null, 'E_ARGS');
      store.alerts.rules.push({ id: newId('rule'), serverId: client.serverId, ...tpl, channels: ['push'], silenceMin: 15, enabled: true });
      store.saveAlerts();
      return done('applied', { installed: tpl });
    }
    case 'alert.rule.manage': {
      if (args?.action === 'delete') {
        store.alerts.rules = store.alerts.rules.filter((r) => r.id !== args.rule?.id || r.builtin);
        store.saveAlerts();
        return done('applied', { rules: store.alerts.rules.length });
      }
      if (args?.action === 'update' && args.rule?.id) {
        const r = store.alerts.rules.find((x) => x.id === args.rule.id);
        if (!r) return done('rejected', null, 'E_ARGS');
        // audit C-1: §13 says the heartbeat rule is "built-in, non-deletable";
        // the delete branch protected it, the update branch did not - any user
        // could muzzle the one alert that watches agent liveness.
        if (r.builtin) return done('rejected', null, 'E_ARGS');
        Object.assign(r, args.rule, { id: r.id });
        store.saveAlerts();
        return done('applied', { rule: r });
      }
      const rule = { id: newId('rule'), serverId: client.serverId, ...(args?.rule || {}), enabled: args?.rule?.enabled !== false };
      store.alerts.rules.push(rule);
      store.saveAlerts();
      return done('applied', { rule });
    }
    case 'alert.channel.test':
      alerts.fire(client.serverId, { id: 'test', channels: ['push'], silenceMin: 0 }, { firedAt: 0 }, 'channel test from Solidus Cloud');
      return done('applied', { pushReady, sent: pushReady });
    default:
      return done('rejected', null, 'E_UNKNOWN_CMD');
  }
}

// ---------- agent socket (/agent) --------------------------------------------

function setupAgentSocket(wss) {
  wss.on('connection', (ws) => {
    let serverId = null;
    // audit C-6: an unauthenticated /agent socket that never sends hello must
    // not pin resources - close it after 10 s.
    const helloTimer = setTimeout(() => { if (!serverId) { try { ws.close(4001, 'no hello'); } catch {} } }, 10000);
    helloTimer.unref?.();
    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch { return ws.close(4000, 'bad json'); }
      if (msg.t === 'evt' && msg.type === 'hello') {
        const rec = store.verifyPairing(String(msg.serverId || '').slice(0, 64), String(msg.secret || '').slice(0, 256));
        if (!rec) {
          // audit C-7: failed hellos are attacker-controlled and were written
          // to the 90-day ledger verbatim (disk exhaustion + forensic noise).
          // Truncate the row and throttle per source IP.
          const peer = ws._socket ? ws._socket.remoteAddress || 'unknown' : 'unknown';
          const key = 'hello:' + peer;
          const bucket = (rate.set(key, rate.get(key) || []));
          if (!pushRate(bucket, 10, 60000)) { try { ws.close(4001, 'throttled'); } catch {} return; }
          store.audit({ kind: 'auth', serverId: String(msg.serverId || '').slice(0, 64), status: 'rejected', code: 'E_AUTH' });
          ws.send(j({ sv: 1, id: msg.id, t: 'evt', type: 'hello.err', d: { code: 'E_AUTH' } }));
          return ws.close(4001, 'auth');
        }
        serverId = msg.serverId;
        const prev = agents.get(serverId);
        if (prev && prev.ws !== ws) { try { prev.ws.terminate(); } catch {} }
        agents.set(serverId, { ws, caps: msg.caps || [], meta: msg, lastSeen: nowMs() });
        ws.send(j({ sv: 1, id: msg.id, t: 'evt', type: 'hello.ok', d: { sessionId: newId('s'), relayTs: nowMs(), protoMin: config.protoMin } }));
        // flush queued commands (§6.6) - the durable relay.db queue, so the
        // flush also delivers commands queued before a relay restart.
        for (const q of relayDb.queuedCommands(serverId)) {
          if (q.expiresAt > nowMs()) {
            relayDb.markSent(q.rid);
            ws.send(j(q.frame));
            setTimeout(() => resolvePending(q.rid, 'timeout', null, 'no agent result within 120s'), 120000).unref();
          } else {
            resolvePending(q.rid, 'rejected', 'E_EXPIRED', 'expired while agent offline');
          }
        }
        store.audit({ kind: 'agent', serverId, status: 'online', agent: msg.agent, mc: msg.mc, modsHash: msg.modsHash });
        broadcastToClients(serverId, { sv: 1, id: newId('m'), t: 'evt', type: 'agent.status', d: { online: true, agent: msg.agent, mc: msg.mc, caps: msg.caps } });
        // replay ring to attached clients
        for (const [cws, c] of clients) {
          if (c.serverId === serverId && cws.readyState === 1) {
            for (const ev of rings.get(serverId) || []) cws.send(j(ev));
          }
        }
        return;
      }
      if (!serverId) return;
      const a = agents.get(serverId);
      if (!a) return;
      a.lastSeen = nowMs();
      if (msg.t === 'evt') {
        // command results close the pending loop ONLY: the originator gets the
        // synthesized result, others get cmd.audit (§6.7) - never both raw.
        if (msg.type === 'cmd.result') {
          const d = msg.d || {};
          if (d.rid) resolvePending(d.rid, d.status, d.code, d.error, d.data, d.tookMs);
          return;
        }
        ringPush(serverId, msg);
        broadcastToClients(serverId, msg);
        if (msg.type && msg.type.startsWith('health.')) alerts.onEvent(serverId, msg.type, msg.d || {});
        if (msg.type === 'agent.security.change') alerts.onSecurityChange(serverId, msg.d?.detail || 'change');
      }
    });
    ws.on('close', () => {
      if (!serverId) return;
      const a = agents.get(serverId);
      if (a && a.ws === ws) {
        agents.delete(serverId);
        store.audit({ kind: 'agent', serverId, status: 'offline' });
        broadcastToClients(serverId, { sv: 1, id: newId('m'), t: 'evt', type: 'agent.status', d: { online: false } });
      }
    });
    ws.on('error', () => {});
  });

  // heartbeat watch (§4.1): silence > 120s = agent.heartbeat.lost
  setInterval(() => {
    for (const [serverId, a] of agents) {
      if (nowMs() - a.lastSeen > config.heartbeatTimeoutMs) {
        try { a.ws.terminate(); } catch {}
        agents.delete(serverId);
        alerts.onHeartbeatLost(serverId);
        broadcastToClients(serverId, { sv: 1, id: newId('m'), t: 'evt', type: 'agent.status', d: { online: false, heartbeatLost: true } });
      }
    }
  }, 15000).unref();
}

// ---------- client socket (/app) ----------------------------------------------

function setupClientSocket(wss) {
  wss.on('connection', (ws, req) => {
    const url = new URL(req.url, 'http://localhost');
    // audit P1-5: the long-lived token is no longer accepted on the socket;
    // only short-lived single-use tickets are (see /api/ws-ticket).
    if (url.searchParams.get('token')) { ws.close(4001, 'token-in-url rejected'); return; }
    const found = consumeWsTicket(url.searchParams.get('ticket'));
    if (!found) { ws.close(4001, 'auth'); return; }
    const client = {
      user: found.user, role: found.user.role, serverId: null, dev: found.session.dev,
      sessionToken: found.session.token,  // audit C-3: live sockets are re-validated
    };
    clients.set(ws, client);
    const servers = store.servers.servers
      .filter((s) => s.userId === client.user.id)
      .map((s) => ({ serverId: s.serverId, name: s.name, online: !!agentOf(s.serverId), entitled: store.entitled(s.serverId) }));
    ws.send(j({
      sv: 1, id: newId('m'), t: 'evt', type: 'auth.ok', ts: nowMs(),
      d: { user: client.user.name, role: client.role, servers },
    }));

    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch { return; }
      if (msg.t === 'evt' && msg.type === 'select') {
        // audit C-2: validate OWNERSHIP before subscribing. The command path
        // already checked rec.userId === client.user.id; the read path did
        // not, leaking another tenant's ring replay, live telemetry and
        // cmd.audit frames to any authenticated user.
        const wantId = msg.d?.serverId;
        const rec = store.findServer(String(wantId || ''));
        if (!wantId || !rec || rec.userId !== client.user.id) {
          return ws.send(j({ sv: 1, id: msg.id, t: 'evt', type: 'select.err', d: { code: 'E_UNKNOWN_SERVER' } }));
        }
        client.serverId = wantId;
        for (const ev of rings.get(client.serverId) || []) ws.send(j(ev));
        return;
      }
      if (msg.t === 'evt' && msg.type === 'prepare') {
        const meta = COMMAND_META[msg.cmdTarget];
        if (!meta || meta.risk !== 'D') return ws.send(j({ sv: 1, id: msg.id, t: 'evt', type: 'prepare.err', d: { code: 'E_ARGS' } }));
        const token2 = newId('cf');
        prepareTokens.set(token2, { userId: client.user.id, cmd: msg.cmdTarget, target: msg.target || '', preparedAt: nowMs(), validUntil: nowMs() + 120000 });
        return ws.send(j({ sv: 1, id: msg.id, t: 'evt', type: 'prepare.ok', d: { token: token2, validUntil: nowMs() + 120000, holdMs: config.destructiveHoldMs } }));
      }
      if (msg.t === 'cmd') {
        try { handleClientCommand(ws, client, msg); } catch (e) { console.error('[cmd]', e); }
      }
    });
    ws.on('close', () => clients.delete(ws));
    ws.on('error', () => clients.delete(ws));
  });
}

// ---------- HTTP API + static PWA ----------------------------------------------

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json', '.ico': 'image/x-icon' };

function readBody(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 100000) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(body || '{}')); } catch { resolve({}); } });
  });
}

function authUser(req) {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  return store.findUserByToken(token);
}

const httpServer = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  applySecurityHeaders(req, res, url.pathname.startsWith('/api/'));
  try {
    if (req.method === 'POST' && url.pathname === '/api/login') {
      const { name, password } = await readBody(req);
      const ip = req.socket.remoteAddress || '';
      // audit P0-2: lockout check BEFORE scrypt so a blocked source cannot
      // pin the relay CPU on password derivations, and guessing is capped.
      if (loginLimiter.isBlocked(ip, name)) {
        store.audit({ kind: 'auth', actorName: name || null, status: 'rejected', code: 'E_LOCKOUT' });
        res.writeHead(429, { 'Content-Type': 'application/json', 'Retry-After': String(Math.max(1, Math.ceil(loginLimiter.getRemainingLockMs(ip, name) / 1000))) });
        return res.end(j({ error: 'too many failed attempts' }));
      }
      const user = store.findUser(String(name || ''));
      // audit C-10: unknown usernames skip scrypt entirely, answering ~30-60ms
      // faster than existing ones - a username-enumeration oracle despite the
      // identical 401 bodies. Run a DUMMY scrypt against a fixed record so the
      // unknown-user path costs the same as a real verification.
      const DUMMY_VERIFY = { salt: 'deadbeefdeadbeefdeadbeefdeadbeef', hash: '00'.repeat(32) };
      const ok = user ? store.verifyPassword(user, password) : (store.verifyPassword(DUMMY_VERIFY, password), false);
      if (!ok) {
        loginLimiter.recordFailure(ip, name);
        store.audit({ kind: 'auth', actorName: name, status: 'rejected' });
        res.writeHead(401, { 'Content-Type': 'application/json' });
        return res.end(j({ error: 'bad credentials' }));
      }
      loginLimiter.recordSuccess(ip, user.name);
      const sess = store.createSession(user, req.headers['user-agent']?.slice(0, 40) || 'unknown');
      store.audit({ kind: 'auth', actorName: user.name, status: 'login', dev: sess.dev });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(j({ token: sess.token, user: user.name, role: user.role }));
    }
    // audit P1-5: exchange the Bearer session token for a 30 s single-use
    // WebSocket ticket; the token itself never rides a URL again.
    if (req.method === 'POST' && url.pathname === '/api/ws-ticket') {
      const found = authUser(req);
      if (!found) { res.writeHead(401); return res.end('{}'); }
      const { ticket, expiresAt } = issueWsTicket(found.session.token, found.user.id);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(j({ ticket, expiresAt }));
    }
    if (url.pathname === '/api/state') {
      const found = authUser(req);
      if (!found) { res.writeHead(401); return res.end('{}'); }
      const servers = store.servers.servers
        .filter((s) => s.userId === found.user.id)
        .map((s) => ({ serverId: s.serverId, name: s.name, online: !!agentOf(s.serverId), entitled: store.entitled(s.serverId) }));
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(j({ user: found.user.name, role: found.user.role, servers, pushReady }));
    }
    if (req.method === 'POST' && url.pathname === '/api/pair') {
      const found = authUser(req);
      if (!found || found.user.role !== 'owner') { res.writeHead(403); return res.end(j({ error: 'owner only' })); }
      const { serverId, secret, name } = await readBody(req);
      if (!serverId || !secret) { res.writeHead(400); return res.end(j({ error: 'serverId and secret required' })); }
      const rec = store.pairServer({ serverId: String(serverId), secret: String(secret), name, userId: found.user.id });
      store.audit({ kind: 'pairing', serverId, actorName: found.user.name, status: 'paired' });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(j({ paired: true, serverId: rec.serverId, name: rec.name, entitled: store.entitled(serverId) }));
    }
    if (req.method === 'POST' && url.pathname === '/api/push-subscribe') {
      const found = authUser(req);
      if (!found) { res.writeHead(401); return res.end('{}'); }
      const { serverId, subscription } = await readBody(req);
      const rec = store.findServer(String(serverId || ''));
      if (!rec || rec.userId !== found.user.id || !subscription?.endpoint) { res.writeHead(400); return res.end(j({ error: 'bad subscription' })); }
      // audit C-8: the relay POSTs alert pushes to whatever endpoint is stored
      // here - a wholesale client-controlled URL was a ready-made SSRF vector
      // (http://169.254.169.254/... and friends). Accept only https push
      // endpoints on real hostnames, no userinfo, default port, bounded size.
      let ep;
      try { ep = new URL(String(subscription.endpoint)); } catch { ep = null; }
      const isIpLiteral = ep && (/^\d+\.\d+\.\d+\.\d+$/.test(ep.hostname) || ep.hostname.includes(':'));
      if (!ep || ep.protocol !== 'https:' || !ep.hostname || isIpLiteral || ep.username || ep.password
          || String(subscription.endpoint).length > 512) {
        res.writeHead(400);
        return res.end(j({ error: 'endpoint must be an https push-service URL' }));
      }
      rec.pushSubs = (rec.pushSubs || []).filter((s) => s.endpoint !== subscription.endpoint);
      if (rec.pushSubs.length >= 10) rec.pushSubs.shift();  // bound the list
      rec.pushSubs.push({ endpoint: subscription.endpoint, keys: subscription.keys || {} });
      store.saveServers();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(j({ subscribed: true }));
    }
    // static PWA
    let p = path.normalize(path.join(config.publicDir, url.pathname === '/' ? 'index.html' : url.pathname));
    // audit C-12: the prefix check missed the path-separator boundary - a
    // sibling directory like /public-evil/ also "starts with" the public dir.
    if (p !== config.publicDir && !p.startsWith(config.publicDir + path.sep)) { res.writeHead(403); return res.end(); }
    fs.readFile(p, (err, data) => {
      if (err) { res.writeHead(404); return res.end('not found'); }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(p)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
      res.end(data);
    });
  } catch (e) {
    console.error('[http]', e);
    res.writeHead(500);
    res.end('error');
  }
});

// ---------- boot ----------------------------------------------------------------

// audit C-6: ws defaults allow 100 MiB frames, bufferable PRE-auth on /agent
// (unauthenticated OOM). 256 KiB is ~1000x the largest legitimate envelope.
const agentWss = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });
const clientWss = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });
httpServer.on('upgrade', (req, socket, head) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  if (pathname === '/agent') agentWss.handleUpgrade(req, socket, head, (ws) => agentWss.emit('connection', ws, req));
  else if (pathname === '/app') clientWss.handleUpgrade(req, socket, head, (ws) => clientWss.emit('connection', ws, req));
  else socket.destroy();
});
setupAgentSocket(agentWss);
setupClientSocket(clientWss);

// audit C-3: revocation/expiry must reach LIVE sockets too. session.revoke
// used to only kill future logins - a stolen-token /app connection kept its
// role and could keep issuing econ.grant / server.stop indefinitely.
setInterval(() => {
  for (const [ws, c] of clients) {
    if (ws.readyState !== 1) continue;
    const found = store.findUserByToken(c.sessionToken);
    if (!found || found.user.id !== c.user.id || found.user.role !== c.role) {
      try { ws.close(4001, 'session revoked or expired'); } catch {}
      clients.delete(ws);
    }
  }
}, Number(process.env.RELAY_SESSION_SWEEP_MS ?? 30000)).unref();

// prune stale idem cache + expired ws tickets (+ durable idem rows, P2)
setInterval(() => {
  const cutoff = nowMs() - config.idemCacheMs;
  for (const [k, v] of idemCache) if (v.at < cutoff) idemCache.delete(k);
  relayDb.idemPrune(cutoff);
  for (const [k, v] of prepareTokens) if (v.validUntil < nowMs()) prepareTokens.delete(k);
  for (const [k, v] of wsTickets) if (v.expiresAt < nowMs()) wsTickets.delete(k);
}, 60000).unref();

httpServer.listen(config.port, config.host, () => {
  const s = relayDb.stats();
  // audit C-9: this process NEVER terminates TLS - the scheme in the old log
  // line was fiction (allowInsecure only flipped the printed string). Say the
  // truth: plain ws/http on this port, TLS MUST come from a reverse proxy.
  if (!config.allowInsecure) {
    console.log('SECURITY: this relay speaks PLAIN http/ws. Front it with a TLS-terminating');
    console.log('SECURITY: reverse proxy (Caddy/nginx) before exposing it - see PROTOCOL.md §1.');
    console.log('SECURITY: set RELAY_ALLOW_INSECURE=true to acknowledge and silence this notice.');
  } else {
    console.log('SECURITY: RELAY_ALLOW_INSECURE acknowledged (local reverse proxy assumed).');
  }
  console.log(`Solidus Cloud Relay v0.2.0 listening on ws://${config.host}:${config.port} (TLS offloaded upstream)`);
  console.log(`  agent endpoint : ws://<host>:${config.port}/agent`);
  console.log(`  client endpoint: ws://<host>:${config.port}/app   (PWA at /)`);
  console.log(`  data dir       : ${config.dataDir}`);
  console.log(`  sqlite store   : ${config.dbPath} (events=${s.events}, commands=${s.commands}, idem=${s.idem})`);
  console.log(`  web push       : ${pushReady ? 'READY' : 'disabled (set VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY or run npm run keys)'}`);
  if (store.users.users.length === 0) {
    console.log('  NOTE: no users yet - create the owner with: npm run user -- --name <you> --password <pass>');
  }
});

// durable store is WAL + synchronous=NORMAL, so a crash loses at most the
// last commits - still, close cleanly when we can.
process.once('SIGTERM', () => { try { relayDb.close(); } catch {} process.exit(0); });
process.once('SIGINT', () => { try { relayDb.close(); } catch {} process.exit(0); });
