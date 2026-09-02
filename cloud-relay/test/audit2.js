'use strict';
// Audit round-2 security tests for the Solidus Cloud Relay (C-1..C-11).
// Boots a dedicated relay instance and verifies from the OUTSIDE:
//   C-1  relay-side commands are role-gated (viewer cannot audit.query),
//   C-2  cross-tenant `select` is rejected,
//   C-3  session revocation evicts LIVE sockets,
//   C-4  non-canonical role strings fail CLOSED,
//   C-5  expiresAt is clamped to the class TTL,
//   C-6  oversized WS frames are dropped (maxPayload),
//   C-8  push endpoints must be https push-service URLs (SSRF),
//   C-11 alert.silence actually suppresses alert delivery,
//   CLI  user creation rejects unknown roles.
// Plus a direct unit check of the alerts engine suppression window.

const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const WebSocket = require('ws');

const PORT = 9500 + Math.floor(Math.random() * 200);
const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'solidus-audit2-'));
const ROOT = path.join(__dirname, '..');
const HTTP = `http://127.0.0.1:${PORT}`;
const WS_BASE = `ws://127.0.0.1:${PORT}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
function assert(cond, label) {
  if (cond) console.log(`  ok: ${label}`);
  else { failures++; console.error(`  FAIL: ${label}`); }
}

async function waitFor(fn, ms = 8000, step = 100) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const v = await fn();
    if (v) return v;
    await sleep(step);
  }
  return null;
}

async function post(pathname, body, headers = {}) {
  return fetch(HTTP + pathname, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function login(name, password) {
  const r = await post('/api/login', { name, password });
  if (r.status !== 200) throw new Error('login failed for ' + name);
  return (await r.json());
}

/** Opens an authenticated /app socket; resolves { ws, messages, closed } */
function appSocket(token) {
  return post('/api/ws-ticket', {}, { Authorization: 'Bearer ' + token })
    .then((r) => r.json())
    .then(({ ticket }) => new Promise((resolve, reject) => {
      const ws = new WebSocket(`${WS_BASE}/app?ticket=${ticket}`);
      const state = { ws, messages: [], closed: false, closeCode: null };
      ws.on('message', (d) => state.messages.push(JSON.parse(String(d))));
      ws.on('close', (code) => { state.closed = true; state.closeCode = code; });
      ws.on('open', () => resolve(state));
      ws.on('error', reject);
    }));
}

function send(ws, obj) { ws.send(JSON.stringify(obj)); }

async function main() {
  const env = { ...process.env, RELAY_DATA_DIR: DATA };
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'user', '--name', 'owner', '--password', 'own-pass-1'], { env });
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'user', '--name', 'viewer', '--password', 'view-pass-1'], { env });
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'pair', '--user', 'owner', '--serverId', 'srv-a', '--secret', 'a'.repeat(64)], { env });
  console.log('setup: owner + viewer + srv-a');

  // CLI role validation (C-4 root cause)
  let cliRejected = false;
  try {
    execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'user', '--name', 'hacker', '--password', 'x', '--role', 'Admin'], { env });
  } catch (e) {
    cliRejected = true;
  }
  assert(cliRejected, 'C-4/cli: user creation rejects non-canonical roles');

  // Hand-edit a mangled role into users.json (C-4 fail-closed check)
  const usersFile = path.join(DATA, 'users.json');
  const users = JSON.parse(fs.readFileSync(usersFile, 'utf8'));
  users.users.push({
    id: 'u-mangled', name: 'mangled', salt: users.users[0].salt,
    hash: users.users[0].hash, role: 'Admin', created: Date.now(),
  });
  fs.writeFileSync(usersFile, JSON.stringify(users));

  const relay = spawn('node', [path.join(ROOT, 'src/server.js')], {
    env: { ...env, RELAY_PORT: String(PORT), RELAY_HOST: '127.0.0.1',
           RELAY_ALLOW_INSECURE: 'true', RELAY_SESSION_SWEEP_MS: '1000',
           RELAY_DESTRUCTIVE_HOLD_MS: '500' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  relay.stdout.on('data', () => {});
  relay.stderr.on('data', (d) => process.stderr.write('  relay! ' + d));
  await waitFor(async () => {
    try { await fetch(`${HTTP}/api/state`); return true; } catch { return false; }
  });
  console.log('setup: relay up');

  try {
    const own = await login('owner', 'own-pass-1');
    const view = await login('viewer', 'view-pass-1');
    const mangled = await login('mangled', 'own-pass-1');  // same hash as owner

    // ---- C-1: relay-side commands are role-gated --------------------------
    const vs = await appSocket(view.token);
    await sleep(200);
    send(vs.ws, { sv: 1, id: 'q1', t: 'cmd', cmd: 'audit.query', args: { limit: 5 } });
    const q1 = await waitFor(() => vs.messages.find((m) => m.type === 'cmd.result' && m.d?.rid === 'q1'));
    assert(q1 && q1.d.status === 'rejected' && q1.d.code === 'E_ROLE',
      'C-1: viewer cannot audit.query (E_ROLE, was owner-grade powers)');

    send(vs.ws, { sv: 1, id: 'q2', t: 'cmd', cmd: 'alert.rule.manage', args: { action: 'update', rule: { id: 'builtin-heartbeat', enabled: false } } });
    const q2 = await waitFor(() => vs.messages.find((m) => m.type === 'cmd.result' && m.d?.rid === 'q2'));
    assert(q2 && q2.d.status === 'rejected' && q2.d.code === 'E_ROLE',
      'C-1: viewer cannot manage alert rules');

    // owner cannot muzzle the builtin heartbeat rule either
    const os1 = await appSocket(own.token);
    await sleep(200);
    send(os1.ws, { sv: 1, id: 'q3', t: 'cmd', cmd: 'alert.rule.manage', args: { action: 'update', rule: { id: 'builtin-heartbeat', enabled: false } } });
    const q3 = await waitFor(() => os1.messages.find((m) => m.type === 'cmd.result' && m.d?.rid === 'q3'));
    assert(q3 && q3.d.status === 'rejected' && q3.d.code === 'E_ARGS',
      'C-1: builtin heartbeat rule cannot be disabled even by the owner');

    // ---- C-4: non-canonical role fails CLOSED ------------------------------
    const ms1 = await appSocket(mangled.token);
    await sleep(200);
    send(ms1.ws, { sv: 1, id: 'q4', t: 'cmd', cmd: 'server.save', target: '' });
    const q4 = await waitFor(() => ms1.messages.find((m) => m.type === 'cmd.result' && m.d?.rid === 'q4'));
    assert(q4 && q4.d.status === 'rejected' && q4.d.code === 'E_ROLE',
      'C-4: mangled role "Admin" passes NO gate (undefined RANK used to pass all)');

    // ---- C-2: cross-tenant select rejected ---------------------------------
    send(vs.ws, { sv: 1, id: 's1', t: 'evt', type: 'select', d: { serverId: 'srv-a' } });
    const s1 = await waitFor(() => vs.messages.find((m) => m.type === 'select.err' && m.id === 's1'));
    assert(s1 && s1.d.code === 'E_UNKNOWN_SERVER',
      'C-2: cross-tenant select rejected (ring/telemetry/cmd.audit no longer leak)');

    // ---- C-5: expiresAt is clamped -----------------------------------------
    send(os1.ws, {
      sv: 1, id: 'e1', t: 'cmd', cmd: 'econ.grant', target: 'Notch',
      args: { target: 'Notch', amountC: 500 }, reason: 'clamp test',
      actor: { name: 'owner', role: 'owner' },
      issuedAt: Date.now(), expiresAt: Date.now() + 3600_000,   // 1 hour forged!
      idemKey: 'clamp-1',
      confirm: { typed: 'Notch' },
    });
    await waitFor(() => os1.messages.find((m) => m.type === 'cmd.result' && m.d?.rid === 'e1'));
    const db = require(path.join(ROOT, 'src/db.js'));
    const relayDb = new db.RelayDb(path.join(DATA, 'relay.db'));
    const queued = relayDb.queuedCommands('srv-a');
    relayDb.close();
    const clamped = queued.length === 0 || queued.every((q) => q.expiresAt <= Date.now() + 95_000);
    assert(clamped, 'C-5: queued command expiry is clamped to the class TTL (was floor-only)');

    // ---- C-6: oversized frames are dropped ---------------------------------
    const agentWs = new WebSocket(`${WS_BASE}/agent`);
    const oversized = await new Promise((resolve) => {
      agentWs.on('open', () => {
        agentWs.send('x'.repeat(300 * 1024));   // 300 KB > 256 KB maxPayload
      });
      agentWs.on('close', () => resolve(true));
      agentWs.on('error', () => resolve(true));
      setTimeout(() => resolve(false), 3000);
    });
    assert(oversized, 'C-6: oversized (300KB) frame closes the connection (maxPayload 256KB)');

    // ---- C-8: push endpoint validation --------------------------------------
    const badPush = await post('/api/push-subscribe',
      { serverId: 'srv-a', subscription: { endpoint: 'http://169.254.169.254/latest/meta-data' } },
      { Authorization: 'Bearer ' + own.token });
    assert(badPush.status === 400, 'C-8: http:// push endpoint (SSRF) rejected');
    const badPush2 = await post('/api/push-subscribe',
      { serverId: 'srv-a', subscription: { endpoint: 'https://127.0.0.1/x' } },
      { Authorization: 'Bearer ' + own.token });
    assert(badPush2.status === 400, 'C-8: IP-literal https endpoint rejected');
    const goodPush = await post('/api/push-subscribe',
      { serverId: 'srv-a', subscription: { endpoint: 'https://fcm.googleapis.com/fcm/send/abc', keys: {} } },
      { Authorization: 'Bearer ' + own.token });
    assert(goodPush.status === 200, 'C-8: legitimate https push endpoint accepted');

    // ---- C-3: revocation evicts the LIVE socket -----------------------------
    const victim = await appSocket(own.token);
    await sleep(200);
    send(victim.ws, { sv: 1, id: 'r1', t: 'cmd', cmd: 'session.revoke', args: { dev: victim.ws._socket ? undefined : undefined } });
    await sleep(300);
    // revoke ALL sessions of the owner via dev-less args? session.revoke needs
    // args.dev - revoke by the exact dev of the victim session instead:
    // the sweep re-validates the token; simplest deterministic path: revoke
    // from the OTHER socket (os1) with the victim's device id.
    const sessions = await (async () => {
      send(os1.ws, { sv: 1, id: 'sl1', t: 'cmd', cmd: 'session.list' });
      const r = await waitFor(() => os1.messages.find((m) => m.type === 'cmd.result' && m.d?.rid === 'sl1'));
      return r ? r.d.data.sessions : [];
    })();
    assert(Array.isArray(sessions) && sessions.length >= 1, 'C-3: session.list enumerates devices');
    // Sockets minted from the same token share one session record; revoking
    // that dev must evict the victim's LIVE socket (and every socket on it).
    const victimDev = sessions[sessions.length - 1]?.dev;
    send(os1.ws, { sv: 1, id: 'rv1', t: 'cmd', cmd: 'session.revoke', args: { dev: victimDev } });
    await waitFor(() => os1.messages.find((m) => m.type === 'cmd.result' && m.d?.rid === 'rv1'));
    const evicted = await waitFor(() => victim.closed, 6000, 200);
    assert(evicted, 'C-3: revoked session is kicked off its LIVE socket (was: role kept forever)');

    // ---- C-11: alert.silence suppresses delivery (unit-level) ----------------
    const alerts = require(path.join(ROOT, 'src/alerts.js'));
    const auditRows = [];
    const fakeStore = {
      alerts: { rules: [{ id: 'r', metric: 'tps', op: '<', threshold: 15, channels: [], silenceMin: 0, serverId: 'x' }], silenceUntil: 0 },
      audit: (row) => auditRows.push(row),
      findServer: () => null,
      saveAlerts: () => {},
    };
    const engine = new alerts.AlertEngine(fakeStore);
    engine.fire('x', fakeStore.alerts.rules[0], engine.stateOf('r'), 'no silence');
    assert(auditRows.length === 1, 'C-11: alerts fire normally without a silence window');
    fakeStore.alerts.silenceUntil = Date.now() + 60_000;
    auditRows.length = 0;
    engine.fire('x', fakeStore.alerts.rules[0], engine.stateOf('r'), 'silenced');
    assert(auditRows.length === 0, 'C-11: alert.silence actually suppresses (was a silent no-op)');

    relay.kill('SIGTERM');
    await sleep(200);
  } finally {
    try { relay.kill('SIGTERM'); } catch {}
    fs.rmSync(DATA, { recursive: true, force: true });
  }

  console.log(failures === 0 ? '\nAUDIT-2 TEST PASSED' : `\nAUDIT-2 TEST FAILED (${failures} assertions)`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
