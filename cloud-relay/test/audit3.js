'use strict';
// Security regression tests for the SA2 audit round (2.1.5):
//   SA2-001  one hostile packet must not crash the relay (HTTP + upgrade)
//   SA2-003  args schema validation (unknown keys / ranges / sizes)
//   SA2-004  invalid-frame reject budget closes abusive sockets
//   SA2-005  push endpoint SSRF guard (subscribe-time)
//   SA2-006  alert rule tenant isolation + per-server silence
//   SA2-010  targetless D-class commands accept typed:'CONFIRM'
//   SA2-020  sv protocol check, pairing material format
//
// Boots a dedicated relay (fake agent for cmd.result flows) and verifies
// from the OUTSIDE, same style as test/security.js.

const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');

const PORT = 9701 + Math.floor(Math.random() * 200);
const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'solidus-sa2-'));
const ROOT = path.join(__dirname, '..');
const HTTP = `http://127.0.0.1:${PORT}`;

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
    body: JSON.stringify(body),
  });
}

/** Sends a raw HTTP/1.1 request line over a socket, reads until close/timeout. */
function rawRequest(rawLine, headers = []) {
  return new Promise((resolve) => {
    const s = net.connect(PORT, '127.0.0.1', () => {
      s.write(`${rawLine}\r\nHost: relay\r\n${headers.join('\r\n')}\r\n\r\n`);
    });
    let buf = '';
    const done = (v) => { try { s.destroy(); } catch {} resolve(v); };
    s.on('data', (d) => { buf += d.toString(); });
    s.on('close', () => done(buf || 'CLOSED'));
    s.on('error', () => done(buf || 'ERROR'));
    setTimeout(() => done(buf || 'TIMEOUT'), 1500);
  });
}

class WS {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.handlers = [];
    this.open = new Promise((res, rej) => {
      this.ws.addEventListener('open', res);
      this.ws.addEventListener('error', rej);
    });
    this.ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(String(ev.data));
      this.handlers.forEach((h) => h(msg));
    });
    this.closed = new Promise((res) => this.ws.addEventListener('close', () => res(true)));
  }
  on(h) { this.handlers.push(h); }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  /** Waits for a frame of `type`, optionally matching d.rid/id === rid.
   *  The listener is removed on MATCH and on TIMEOUT (stale listeners must
   *  never steal later frames). */
  once(type, { timeout = 6000, rid } = {}) {
    return new Promise((res) => {
      const h = (m) => {
        const typeOk = m.type === type || m.t === type;
        const ridOk = rid == null || m.d?.rid === rid || m.id === rid;
        if (typeOk && ridOk) {
          const i = this.handlers.indexOf(h);
          if (i >= 0) this.handlers.splice(i, 1);
          res(m);
        }
      };
      this.handlers.push(h);
      setTimeout(() => {
        const i = this.handlers.indexOf(h);
        if (i >= 0) { this.handlers.splice(i, 1); res(null); }
      }, timeout);
    });
  }
  close() { this.ws.close(); }
}

let relay;
async function main() {
  const env = { ...process.env, RELAY_DATA_DIR: DATA };
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'user', '--name', 'owner', '--password', 'test-pass-123'], { env });
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'user', '--name', 'peon', '--password', 'peon-pass-123', '--role', 'viewer'], { env });
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'pair', '--user', 'owner', '--serverId', 'srv-sa2-01', '--secret', 'a'.repeat(64), '--name', 'SA2 Server'], { env });

  relay = spawn('node', [path.join(ROOT, 'src/server.js')], {
    env: { ...env, RELAY_PORT: String(PORT), RELAY_HOST: '127.0.0.1', RELAY_ALLOW_INSECURE: 'true', RELAY_DESTRUCTIVE_HOLD_MS: '300' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  relay.stderr.on('data', (d) => process.stderr.write('  relay! ' + d));
  const alive = await waitFor(async () => {
    try { await fetch(`${HTTP}/api/state`); return true; } catch { return false; }
  });
  assert(alive, 'relay booted');

  // ---- SA2-001: absolute-form request line must NOT kill the process ------
  const crashRes = await rawRequest('GET http://a:70000/ HTTP/1.1');
  assert(crashRes.startsWith('HTTP/1.1 400') || crashRes === 'CLOSED' || crashRes === 'ERROR',
    `SA2-001: hostile absolute-form request answered without a crash (${JSON.stringify(crashRes.slice(0, 24))})`);
  await sleep(150);
  const stillAlive = await waitFor(async () => {
    try { await fetch(`${HTTP}/api/state`); return true; } catch { return false; }
  });
  assert(stillAlive, 'SA2-001: relay still alive after the hostile request');
  // the upgrade path too: a bad absolute-form upgrade must not kill us
  await rawRequest('GET http://b:70000/agent HTTP/1.1', ['Connection: Upgrade', 'Upgrade: websocket', 'Sec-WebSocket-Version: 13', 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==']);
  await sleep(150);
  const aliveAfterUpgrade = await waitFor(async () => {
    try { await fetch(`${HTTP}/api/state`); return true; } catch { return false; }
  });
  assert(aliveAfterUpgrade, 'SA2-001: relay still alive after a hostile upgrade line');

  // login as owner + viewer
  const loginOwner = await (await post('/api/login', { name: 'owner', password: 'test-pass-123' })).json();
  const loginPeon = await (await post('/api/login', { name: 'peon', password: 'peon-pass-123' })).json();
  assert(loginOwner.token && loginPeon.token, 'both test accounts logged in');
  const ownerH = { Authorization: 'Bearer ' + loginOwner.token };
  const peonH = { Authorization: 'Bearer ' + loginPeon.token };

  // ---- SA2-020: pairing material format ----
  const badPair = await post('/api/pair', { serverId: 'x', secret: 'nothex', name: 'bad' }, ownerH);
  assert(badPair.status === 400, 'SA2-020: malformed pairing material rejected (400)');

  // ---- SA2-005: SSRF guard at subscribe time ----
  const subRes1 = await post('/api/push-subscribe', { serverId: 'srv-sa2-01', subscription: { endpoint: 'https://localhost:9200/leak', keys: {} } }, ownerH);
  assert(subRes1.status === 400, 'SA2-005: https://localhost endpoint rejected');
  const subRes2 = await post('/api/push-subscribe', { serverId: 'srv-sa2-01', subscription: { endpoint: 'https://metadata.google.internal/computeMetadata/v1/', keys: {} } }, ownerH);
  assert(subRes2.status === 400, 'SA2-005: cloud-metadata endpoint rejected');
  const subRes3 = await post('/api/push-subscribe', { serverId: 'srv-sa2-01', subscription: { endpoint: 'https://169.254.169.254/latest/meta-data/', keys: {} } }, ownerH);
  assert(subRes3.status === 400, 'SA2-005: IP-literal endpoint rejected');
  const subRes4 = await post('/api/push-subscribe', { serverId: 'srv-sa2-01', subscription: { endpoint: 'https://fcm.googleapis.com/fcm/send/abc', keys: {} } }, ownerH);
  assert(subRes4.status === 200, 'SA2-005: legitimate public https endpoint accepted');

  // ---- websocket flows (owner) ----
  const tk = await (await post('/api/ws-ticket', {}, ownerH)).json();
  const client = new WS(`ws://127.0.0.1:${PORT}/app?ticket=${tk.ticket}`);
  await client.open;
  const authOk = await client.once('auth.ok');
  assert(authOk, 'owner socket authenticated');
  client.send({ sv: 1, id: 'sel', t: 'evt', type: 'select', d: { serverId: 'srv-sa2-01' } });
  await sleep(150);

  // ---- SA2-003: args schema validation ----
  client.send({ sv: 1, id: 'a1', t: 'cmd', cmd: 'econ.grant', target: 'Notch', args: { amountC: -500 }, reason: 'r', actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000, idemKey: 'sa2-k1', confirm: { typed: 'Notch' } });
  let res = await client.once('cmd.result');
  assert(res?.d?.code === 'E_ARGS', 'SA2-003: out-of-range amountC rejected');
  client.send({ sv: 1, id: 'a2', t: 'cmd', cmd: 'econ.grant', target: 'Notch', args: { amountC: 100, evil: 'x' }, reason: 'r', actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000, idemKey: 'sa2-k2', confirm: { typed: 'Notch' } });
  res = await client.once('cmd.result');
  assert(res?.d?.code === 'E_ARGS' && /unknown key/.test(res.d.error || ''), 'SA2-003: unknown args key rejected (§6.4)');
  client.send({ sv: 1, id: 'a3', t: 'cmd', cmd: 'player.gamemode', target: 'Notch', args: { mode: 'FLY' }, actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000 });
  res = await client.once('cmd.result');
  assert(res?.d?.code === 'E_ARGS', 'SA2-003: non-enum value rejected');
  client.send({ sv: 1, id: 'a4', t: 'cmd', cmd: 'econ.top', target: '', args: { limit: 5000 }, actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000 });
  res = await client.once('cmd.result');
  assert(res?.d?.code === 'E_ARGS', 'SA2-003: oversized int rejected');
  // a valid one passes validation and reaches the (absent) agent -> queued
  client.send({ sv: 1, id: 'a5', t: 'cmd', cmd: 'econ.top', target: '', args: { limit: 10 }, actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000 });
  res = await client.once('cmd.queued', { rid: 'a5' }) || await client.once('cmd.result', { rid: 'a5' });
  assert(res && (res.d?.rid === 'a5' || res.id === 'a5'), 'SA2-003: schema-valid args pass validation and reach the queue');

  // ---- SA2-010: targetless D-class accept typed:'CONFIRM' (no agent -> hold
  //      rejection proves the CONFIRM contract passed) ----
  const prep = await new Promise((resolve) => {
    client.send({ sv: 1, id: 'p1', t: 'evt', type: 'prepare', cmdTarget: 'econ.pause.global' });
    const h = (m) => { if (m.type === 'prepare.ok') { client.handlers.splice(client.handlers.indexOf(h), 1); resolve(m); } if (m.type === 'prepare.err') resolve(null); };
    client.handlers.push(h);
    setTimeout(() => resolve(null), 3000);
  });
  assert(prep?.d?.token, 'SA2-010: prepare.ok issued');
  client.send({ sv: 1, id: 'a6', t: 'cmd', cmd: 'econ.pause.global', target: '', args: {}, reason: 'sa2 confirm contract', actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 90000, confirm: { token: prep?.d?.token, password: 'test-pass-123', typed: 'CONFIRM' } });
  res = await client.once('cmd.result', { rid: 'a6' });
  assert(res && res.d.code !== 'E_CONFIRM_MISMATCH',
    `SA2-010: typed:'CONFIRM' accepted for targetless D-class (got ${res?.d?.code || res?.d?.status || 'timeout'})`);

  // ---- SA2-020: wrong protocol version on the client socket ----
  client.send({ sv: 99, id: 'a7', t: 'cmd', cmd: 'econ.top', target: '', args: {}, actor: { name: 'owner', role: 'owner' } });
  res = await client.once('cmd.result', { rid: 'a7' });
  assert(res?.d?.code === 'E_PROTO', 'SA2-020: frame with wrong sv answered E_PROTO');

  // ---- SA2-006: alert rule tenant stamping (create cannot override serverId) ----
  client.send({ sv: 1, id: 'a8', t: 'cmd', cmd: 'alert.rule.manage', target: '', args: { action: 'create', rule: { metric: 'tps', op: '<', threshold: 10, serverId: 'OTHER-TENANT' } }, actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000 });
  res = await client.once('cmd.result', { rid: 'a8' });
  assert(res?.d?.status === 'applied' && res.d.data.rule.serverId === 'srv-sa2-01',
    'SA2-006: rule creation cannot re-point serverId at another tenant');
  const createdId = res?.d?.data?.rule?.id;
  // delete works within the tenant
  client.send({ sv: 1, id: 'a9', t: 'cmd', cmd: 'alert.rule.manage', target: '', args: { action: 'delete', rule: { id: createdId } }, actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000 });
  res = await client.once('cmd.result', { rid: 'a9' });
  assert(res?.d?.status === 'applied' && res.d.data.deleted === 1, 'SA2-006: own-tenant rule deleted');
  // a foreign rule id cannot be updated (find is tenant-scoped)
  client.send({ sv: 1, id: 'a10', t: 'cmd', cmd: 'alert.rule.manage', target: '', args: { action: 'update', rule: { id: 'builtin-heartbeat', metric: 'tps', threshold: 999 } }, actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000 });
  res = await client.once('cmd.result', { rid: 'a10' });
  assert(res?.d?.code === 'E_ARGS', 'SA2-006: builtin rule stays protected from update');

  // ---- SA2-004: reject budget closes the flood socket ----
  const tk2 = await (await post('/api/ws-ticket', {}, peonH)).json();
  const flooder = new WS(`ws://127.0.0.1:${PORT}/app?ticket=${tk2.ticket}`);
  await flooder.open;
  flooder.send({ sv: 1, id: 's1', t: 'evt', type: 'select', d: { serverId: 'srv-sa2-01' } });
  await sleep(100);
  for (let i = 0; i < 150; i++) {
    flooder.send({ sv: 1, id: 'f' + i, t: 'cmd', cmd: 'x'.repeat(200000), target: 'y'.repeat(200000), args: {}, actor: { name: 'peon', role: 'viewer' }, issuedAt: Date.now() });
  }
  const flooderDied = await Promise.race([flooder.closed, sleep(4000).then(() => false)]);
  assert(flooderDied === true, 'SA2-004: flooding socket is closed by the reject budget');
  client.close();
  flooder.ws.close?.();

  // relay must still be alive after the flood
  await sleep(200);
  const aliveAfterFlood = await waitFor(async () => {
    try { await fetch(`${HTTP}/api/state`); return true; } catch { return false; }
  });
  assert(aliveAfterFlood, 'relay survived the invalid-frame flood');
  client.close();
  flooder.close();
  relay.kill('SIGTERM');
  await sleep(200);
  fs.rmSync(DATA, { recursive: true, force: true });

  console.log(failures === 0 ? '\nALL SA2 TESTS PASSED' : `\n${failures} SA2 TEST(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); relay?.kill('SIGTERM'); process.exit(1); });
