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
const { AlertEngine, pushReady } = require('./alerts');
const { LoginLimiter } = require('./login-limiter');

const store = new Store();
const alerts = new AlertEngine(store);
const loginLimiter = new LoginLimiter();

// ---- live state -----------------------------------------------------------

const agents = new Map();   // serverId -> { ws, caps, meta, lastSeen, ring:[], queue:[] }
const clients = new Map();  // ws -> { user, role, serverId, dev }
const idemCache = new Map();      // `${userId}:${cmd}:${idemKey}` -> result
const prepareTokens = new Map();  // token -> { userId, cmd, target, preparedAt }
const rate = new Map();           // userId -> { financial:[], w2:[], w1:[], r:[], d:[], broadcast:[] }
const pending = new Map();        // rid -> { ws (originator), userId }
const wsTickets = new Map();      // ticket -> { sessionToken, userId, expiresAt }  (single-use, P1-5)

setInterval(() => store.pruneAudit(), 12 * 3600 * 1000).unref();

// ---------- helpers ---------------------------------------------------------

function nowMs() { return Date.now(); }

function j(obj) { return JSON.stringify(obj); }

function newId(prefix) { return prefix + '-' + crypto.randomUUID().slice(0, 13); }

function agentOf(serverId) {
  const a = agents.get(serverId);
  return a && a.ws && a.ws.readyState === 1 ? a : null;
}

function ringPush(serverId, event) {
  const a = agents.get(serverId);
  if (!a) return;
  a.ring.push(event);
  if (a.ring.length > config.eventRing) a.ring.shift();
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

  // relay-side commands never reach the agent (§15 alerts & audit domain)
  if (COMMAND_META.relaySide.includes(cmd)) return relaySideCommand(ws, client, frame);

  if (!meta) return reject('E_UNKNOWN_CMD', 'command id is not in the catalog allow-list');
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

  // TTL (§3)
  const ttl = meta.risk === 'D' ? 90000 : config.commandTtlMs;
  const expiresAt = frame.expiresAt || (frame.issuedAt || nowMs()) + ttl;
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

  // idempotency (G3)
  if (meta.financial) {
    if (!frame.idemKey) return reject('E_ARGS', 'idemKey is mandatory for financial commands');
    const key = `${client.user.id}:${cmd}:${frame.idemKey}`;
    const prior = idemCache.get(key);
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
  store.audit({ kind: 'cmd', rid, serverId, cmd, target: target ?? '', reason: frame.reason || '',
    actorId: client.user.id, actorName: client.user.name, actorRole: client.role,
    status: agent ? 'sent' : 'queued', receivedAt: nowMs(), idemKey: frame.idemKey || null, args: frame.args || {} });
  pending.set(rid, { ws, userId: client.user.id, serverId, cmd, target: target ?? '', actor: forward.actor, idemKey: frame.idemKey || null });

  if (agent) {
    agent.ws.send(j(forward));
    setTimeout(() => resolvePending(rid, 'timeout', null, 'no agent result within 120s'), 120000).unref();
  } else {
    const q = agents.get(serverId)?.queue || [];
    if (q.length >= config.limits.commandQueue) {
      pending.delete(rid);
      return reject('E_RATE', 'offline command queue is full');
    }
    if (expiresAt - nowMs() < 30000) {
      pending.delete(rid);
      return reject('E_EXPIRED', 'TTL too short to queue while agent offline');
    }
    q.push(forward);
    agents.get(serverId).queue = q;
    ws.send(j({ sv: 1, id: frame.id, t: 'evt', type: 'cmd.queued', d: { rid, cmd, queuePos: q.length } }));
  }
}

function resolvePending(rid, status, code, error, data, tookMs) {
  const p = pending.get(rid);
  if (!p) return;
  pending.delete(rid);
  // relay-side idempotency cache for financial commands (G3)
  if (p.idemKey) {
    idemCache.set(`${p.userId}:${p.cmd}:${p.idemKey}`, { status, code, error, d: data || {}, at: nowMs() });
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
  const done = (status, data, code) => ws.send(j({
    sv: 1, id: frame.id, t: 'evt', type: 'cmd.result', ts: nowMs(),
    d: { rid: frame.rid || frame.id, cmd, target: frame.target ?? null, actor: { name: client.user.name, role: client.role }, status, code: code || null, data: data || null, tookMs: 0 },
  }));
  store.audit({ kind: 'cmd', serverId: client.serverId, cmd, target: frame.target ?? '', actorName: client.user.name, actorRole: client.role, status: 'applied', receivedAt: nowMs() });
  switch (cmd) {
    case 'session.list':
      return done('applied', { sessions: store.users.sessions.filter((s) => s.userId === client.user.id).map((s) => ({ dev: s.dev, lastSeen: s.lastSeen, expiresAt: s.expiresAt })) });
    case 'session.revoke': {
      const n = store.revokeSession(args?.dev, client.user.id);
      return done('applied', { revoked: n });
    }
    case 'audit.query':
      return done('applied', { rows: store.auditQuery({ ...args, limit: args?.limit || 100 }) });
    case 'audit.export':
      return done('applied', { format: args?.format || 'json', rows: store.auditQuery({ ...args, limit: 2000 }) });
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
        if (r) Object.assign(r, args.rule, { id: r.id });
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
    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch { return ws.close(4000, 'bad json'); }
      if (msg.t === 'evt' && msg.type === 'hello') {
        const rec = store.verifyPairing(msg.serverId, msg.secret);
        if (!rec) {
          store.audit({ kind: 'auth', serverId: msg.serverId, status: 'rejected', code: 'E_AUTH' });
          ws.send(j({ sv: 1, id: msg.id, t: 'evt', type: 'hello.err', d: { code: 'E_AUTH' } }));
          return ws.close(4001, 'auth');
        }
        serverId = msg.serverId;
        const prev = agents.get(serverId);
        if (prev && prev.ws !== ws) { try { prev.ws.terminate(); } catch {} }
        agents.set(serverId, {
          ws, caps: msg.caps || [], meta: msg, lastSeen: nowMs(),
          ring: prev?.ring || [], queue: prev?.queue || [],
        });
        ws.send(j({ sv: 1, id: msg.id, t: 'evt', type: 'hello.ok', d: { sessionId: newId('s'), relayTs: nowMs(), protoMin: config.protoMin } }));
        // flush queued commands (§6.6)
        const a = agents.get(serverId);
        for (const q of a.queue.splice(0)) {
          if (q.expiresAt > nowMs()) ws.send(j(q));
          else resolvePending(q.rid, 'rejected', 'E_EXPIRED', 'expired while agent offline');
        }
        store.audit({ kind: 'agent', serverId, status: 'online', agent: msg.agent, mc: msg.mc, modsHash: msg.modsHash });
        broadcastToClients(serverId, { sv: 1, id: newId('m'), t: 'evt', type: 'agent.status', d: { online: true, agent: msg.agent, mc: msg.mc, caps: msg.caps } });
        // replay ring to attached clients
        for (const [cws, c] of clients) {
          if (c.serverId === serverId && cws.readyState === 1) {
            for (const ev of a.ring) cws.send(j(ev));
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
        client.serverId = msg.d?.serverId;
        const a = agents.get(client.serverId);
        if (a && a.ws.readyState === 1) {
          for (const ev of a.ring) ws.send(j(ev));
        }
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
      if (!user || !store.verifyPassword(user, password)) {
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
      rec.pushSubs = (rec.pushSubs || []).filter((s) => s.endpoint !== subscription.endpoint);
      rec.pushSubs.push(subscription);
      store.saveServers();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(j({ subscribed: true }));
    }
    // static PWA
    let p = path.normalize(path.join(config.publicDir, url.pathname === '/' ? 'index.html' : url.pathname));
    if (!p.startsWith(config.publicDir)) { res.writeHead(403); return res.end(); }
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

const agentWss = new WebSocketServer({ noServer: true });
const clientWss = new WebSocketServer({ noServer: true });
httpServer.on('upgrade', (req, socket, head) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  if (pathname === '/agent') agentWss.handleUpgrade(req, socket, head, (ws) => agentWss.emit('connection', ws, req));
  else if (pathname === '/app') clientWss.handleUpgrade(req, socket, head, (ws) => clientWss.emit('connection', ws, req));
  else socket.destroy();
});
setupAgentSocket(agentWss);
setupClientSocket(clientWss);

// prune stale idem cache + expired ws tickets
setInterval(() => {
  const cutoff = nowMs() - config.idemCacheMs;
  for (const [k, v] of idemCache) if (v.at < cutoff) idemCache.delete(k);
  for (const [k, v] of prepareTokens) if (v.validUntil < nowMs()) prepareTokens.delete(k);
  for (const [k, v] of wsTickets) if (v.expiresAt < nowMs()) wsTickets.delete(k);
}, 60000).unref();

httpServer.listen(config.port, config.host, () => {
  const scheme = config.allowInsecure ? 'ws' : 'wss';
  console.log(`Solidus Cloud Relay v0.1.0 listening on ${scheme}://${config.host}:${config.port}`);
  console.log(`  agent endpoint : ${scheme}://<host>:${config.port}/agent`);
  console.log(`  client endpoint: ${scheme}://<host>:${config.port}/app   (PWA at /)`);
  console.log(`  data dir       : ${config.dataDir}`);
  console.log(`  web push       : ${pushReady ? 'READY' : 'disabled (set VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY or run npm run keys)'}`);
  if (store.users.users.length === 0) {
    console.log('  NOTE: no users yet - create the owner with: npm run user -- --name <you> --password <pass>');
  }
});
