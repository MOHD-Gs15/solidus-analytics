'use strict';
// End-to-end smoke test for the Solidus Cloud Relay.
// Boots the relay on an ephemeral port with a temp data dir, then plays both
// sides: a FAKE AGENT (implements the /agent handshake + canned command
// results) and a FAKE CLIENT (login -> auth.ok -> select -> commands).
//
// Asserts the whole v1 contract path: pairing, hello/hello.ok, event ring,
// W2 typed-name confirmation, financial idempotency (duplicate replay),
// D-class prepare-token + password + hold, audit ledger contents.

const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const PORT = 8901 + Math.floor(Math.random() * 200);
const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'solidus-relay-'));
const ROOT = path.join(__dirname, '..');
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

// tiny promise WS helper (browser-style API in Node >= 22)
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
  }
  on(h) { this.handlers.push(h); }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  once(type, timeout = 8000) {
    return new Promise((res) => {
      const h = (m) => { if (m.type === type || m.t === type) { this.handlers.splice(this.handlers.indexOf(h), 1); res(m); } };
      this.handlers.push(h);
      setTimeout(() => res(null), timeout);
    });
  }
  close() { this.ws.close(); }
}

async function main() {
  // 0) users + pairing via CLI against the temp data dir
  const env = { ...process.env, RELAY_DATA_DIR: DATA };
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'user', '--name', 'owner', '--password', 'test-pass-123'], { env });
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'pair', '--user', 'owner', '--serverId', 'srv-test01', '--secret', 'a'.repeat(64), '--name', 'Test Server'], { env });
  console.log('setup: owner + server paired');

  // 1) boot relay
  const relay = spawn('node', [path.join(ROOT, 'src/server.js')], {
    env: { ...env, RELAY_PORT: String(PORT), RELAY_HOST: '127.0.0.1', RELAY_ALLOW_INSECURE: 'true', RELAY_DESTRUCTIVE_HOLD_MS: '400' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  relay.stdout.on('data', (d) => process.stdout.write('  relay| ' + d));
  relay.stderr.on('data', (d) => process.stderr.write('  relay! ' + d));
  await waitFor(async () => {
    try { await fetch(`http://127.0.0.1:${PORT}/api/state`); return true; } catch { return false; }
  });
  console.log('setup: relay up');

  try {
    // 2) fake agent connects
    const agent = new WS(`${WS_BASE}/agent`);
    await agent.open;
    agent.send({ sv: 1, id: 'm-h1', t: 'evt', type: 'hello', serverId: 'srv-test01', secret: 'a'.repeat(64), agent: '0.0-test', mc: '26.1.2', caps: ['econ.grant', 'econ.pause.global', 'server.stop', 'health.tps'] });
    const helloOk = await agent.once('hello.ok');
    assert(helloOk && helloOk.d.protoMin === 1, 'agent hello.ok received');

    // 3) agent streams a telemetry event
    agent.send({ sv: 1, id: 'm-e1', t: 'evt', seq: 1, ts: Date.now(), type: 'health.tps', d: { tps1: 19.8, tps5: 20, tps15: 20, msptAvg: 8, msptP95: 12, spikes: 0 } });

    // agent answers commands with ack + result
    agent.on((m) => {
      if (m.t !== 'cmd') return;
      agent.send({ sv: 1, id: m.id, t: 'ack', ok: true });
      agent.send({
        sv: 1, id: 'r-' + m.id, t: 'evt', type: 'cmd.result', ts: Date.now(),
        d: { rid: m.rid, cmd: m.cmd, target: m.target, status: 'applied', data: { balanceC: 125000 }, tookMs: 12 },
      });
    });

    // 4) fake client: login -> ws ticket -> connect (audit P1-5)
    const login = await (await fetch(`http://127.0.0.1:${PORT}/api/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'owner', password: 'test-pass-123' }),
    })).json();
    assert(login.token && login.role === 'owner', 'client login ok (owner)');

    const tkt = await (await fetch(`http://127.0.0.1:${PORT}/api/ws-ticket`, {
      method: 'POST', headers: { Authorization: 'Bearer ' + login.token },
    })).json();
    assert(tkt.ticket && tkt.expiresAt > Date.now(), 'ws-ticket issued (30 s single-use)');

    const client = new WS(`${WS_BASE}/app?ticket=${tkt.ticket}`);
    await client.open;
    const authOk = await client.once('auth.ok');
    assert(authOk && authOk.d.servers[0].serverId === 'srv-test01' && authOk.d.servers[0].online, 'auth.ok shows paired server online');

    // 4b) the ticket was single-use and the old token-in-URL path is closed
    const staleTicket = new WS(`${WS_BASE}/app?ticket=${tkt.ticket}`);
    await staleTicket.open;
    const staleAuth = await staleTicket.once('auth.ok', 1200);
    assert(!staleAuth, 'ticket is single-use (second use rejected)');
    staleTicket.close();

    const tokenUrl = new WS(`${WS_BASE}/app?token=${login.token}`);
    await tokenUrl.open;
    const tokenAuth = await tokenUrl.once('auth.ok', 1200);
    assert(!tokenAuth, 'token-in-URL rejected on the socket');
    tokenUrl.close();

    client.send({ sv: 1, id: 'm-c0', t: 'evt', type: 'select', d: { serverId: 'srv-test01' } });
    const evt = await client.once('health.tps');
    assert(evt && evt.d.tps1 === 19.8, 'ring replay delivered health.tps to late client');

    // 5) W2 financial command with typed-name confirmation + idemKey
    const idem = 'id-test-001';
    const w2frame = {
      sv: 1, id: 'm-c1', t: 'cmd', cmd: 'econ.grant', target: 'Notch',
      args: { amountC: 25000 }, reason: 'event prize',
      actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 60000,
      idemKey: idem, confirm: { typed: 'Notch' },
    };
    client.send(w2frame);
    let res = await client.once('cmd.result');
    assert(res && res.d.status === 'applied' && res.d.data.balanceC === 125000, 'W2 econ.grant applied (typed confirm accepted)');

    // 6) duplicate idemKey -> applied + duplicate:true, no double credit
    client.send({ ...w2frame, id: 'm-c2' });
    res = await client.once('cmd.result');
    assert(res && res.d.status === 'applied' && res.d.data.duplicate === true, 'duplicate idemKey replayed original result');

    // 7) wrong typed name -> E_CONFIRM_MISMATCH
    client.send({ ...w2frame, id: 'm-c3', idemKey: 'id-test-002', confirm: { typed: 'SomeoneElse' } });
    res = await client.once('cmd.result');
    assert(res && res.d.code === 'E_CONFIRM_MISMATCH', 'typed-name mismatch rejected');

    // 8) D-class: prepare -> token -> password + hold
    client.send({ sv: 1, id: 'm-p1', t: 'evt', type: 'prepare', cmdTarget: 'econ.pause.global' });
    const prep = await client.once('prepare.ok');
    assert(prep && prep.d.token, 'prepare.ok issued confirmToken');
    await sleep(600); // > RELAY_DESTRUCTIVE_HOLD_MS=400
    client.send({
      sv: 1, id: 'm-c4', t: 'cmd', cmd: 'econ.pause.global', target: '', args: {}, reason: 'exploit containment',
      actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 90000,
      confirm: { token: prep.d.token, password: 'test-pass-123', typed: '' },
    });
    res = await client.once('cmd.result');
    assert(res && res.d.status === 'applied', 'D-class applied after prepare+password+hold');

    // 9) D-class without token -> rejected
    client.send({
      sv: 1, id: 'm-c5', t: 'cmd', cmd: 'server.stop', target: '', args: {}, reason: 'no token',
      actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(), expiresAt: Date.now() + 90000,
      confirm: { password: 'test-pass-123', typed: '' },
    });
    res = await client.once('cmd.result');
    assert(res && res.d.code === 'E_CONFIRM_MISMATCH', 'D-class without token rejected');

    // 10) unknown command id -> E_UNKNOWN_CMD (G1)
    client.send({ sv: 1, id: 'm-c6', t: 'cmd', cmd: 'op.grant', target: 'Notch', args: {}, actor: { name: 'owner', role: 'owner' } });
    res = await client.once('cmd.result');
    assert(res && res.d.code === 'E_UNKNOWN_CMD', 'unknown id rejected (no privilege path exists)');

    // 11) audit ledger contains everything
    client.send({ sv: 1, id: 'm-c7', t: 'cmd', cmd: 'audit.query', args: { limit: 50 }, actor: { name: 'owner', role: 'owner' } });
    res = await client.once('cmd.result');
    const rows = res?.d?.data?.rows || [];
    assert(rows.some((r) => r.cmd === 'econ.grant' && r.status === 'applied'), 'audit contains econ.grant applied');
    assert(rows.some((r) => r.cmd === 'econ.pause.global' && r.status === 'applied'), 'audit contains circuit breaker applied');
    assert(rows.some((r) => r.cmd === 'op.grant' && r.code === 'E_UNKNOWN_CMD'), 'audit records rejected unknown ids');

    client.close();
    agent.close();
  } finally {
    relay.kill('SIGTERM');
    await sleep(200);
    fs.rmSync(DATA, { recursive: true, force: true });
  }

  console.log(failures === 0 ? '\nSMOKE TEST PASSED' : `\nSMOKE TEST FAILED (${failures} assertions)`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
