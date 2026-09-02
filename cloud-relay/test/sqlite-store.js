'use strict';
// P2: durable SQLite store tests for the Solidus Cloud Relay.
//
// Part A - RelayDb unit tests in a temp dir: event ring persistence + trim,
// command lifecycle (queued -> sent -> done), boot closure of in-flight and
// expired-queued commands, idempotency get/set/prune.
//
// Part B - full relay restart E2E: an event streamed before the restart is
// still replayed after it (ring), a command queued while the agent is offline
// survives the restart and flushes on reconnect (store&forward), and a
// financial idemKey replayed after the restart returns duplicate:true instead
// of re-forwarding to the agent (no double credit across restarts).

const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { RelayDb } = require('../src/db');

const PORT = 9201 + Math.floor(Math.random() * 200);
const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'solidus-sqlite-'));
const ROOT = path.join(__dirname, '..');
const WS_BASE = `ws://127.0.0.1:${PORT}`;
const RING = 200;

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

const frame = (type, d) => ({ sv: 1, id: 'm-' + type, t: 'evt', ts: Date.now(), type, d });

// ---------------------------------------------------------------- part A ---

function unitTests() {
  const dbPath = path.join(DATA, 'unit.db');
  const db = new RelayDb(dbPath);

  // ring insert + reload
  for (let i = 1; i <= 3; i++) {
    db.appendEvent('srv-a', frame('health.tps', { tps1: 20 - i, n: i }), RING);
  }
  let rings = db.loadRings(RING);
  assert(rings.get('srv-a')?.length === 3 && rings.get('srv-a')[2].d.n === 3,
    'ring reload returns all frames in order');

  // ring trim on overflow
  for (let i = 4; i <= 250; i++) {
    const f = frame('health.tps', { tps1: 1, n: i });
    db.appendEvent('srv-a', f, RING, i > RING);
  }
  rings = db.loadRings(RING);
  assert(rings.get('srv-a').length === RING && rings.get('srv-a')[0].d.n === 51,
    'ring trimmed to last 200 (oldest dropped)');

  // command lifecycle
  db.insertCommand({ rid: 'r-1', serverId: 'srv-a', userId: 'u-1', cmd: 'econ.grant',
    target: 'Notch', actor: { name: 'owner' }, idemKey: 'k1', issuedAt: Date.now(),
    expiresAt: Date.now() + 600000, state: 'queued',
    frame: { sv: 1, id: 'm-x1', t: 'cmd', rid: 'r-1', cmd: 'econ.grant' } });
  assert(db.queueCount('srv-a') === 1, 'queued command counted');
  const qc = db.queuedCommands('srv-a');
  assert(qc.length === 1 && qc[0].frame.rid === 'r-1', 'queued command frame returned in order');
  db.markSent('r-1');
  assert(db.queueCount('srv-a') === 0 && db.queuedCommands('srv-a').length === 0,
    'markSent drains the queue');
  assert(db.finishCommand('r-1', { status: 'applied', data: { ok: 1 } }),
    'finishCommand moves row to done');
  assert(!db.finishCommand('r-1', { status: 'applied' }), 'second finish is a no-op');
  const ctx = db.commandContext('r-1');
  assert(ctx && ctx.cmd === 'econ.grant' && ctx.actor?.name === 'owner',
    'commandContext carries actor/context for post-restart closure');

  // idem
  db.idemSet('u-1:econ.grant:k1', { status: 'applied', code: null, error: null, d: { balanceC: 99 }, at: Date.now() });
  const got = db.idemGet('u-1:econ.grant:k1');
  assert(got?.status === 'applied' && got.d.balanceC === 99, 'idemGet returns parsed payload');
  assert(db.idemGet('missing') === null, 'idemGet miss is null');
  db.idemPrune(Date.now() + 1);
  assert(db.idemGet('u-1:econ.grant:k1') === null, 'idemPrune removes expired rows');

  // boot closures
  db.insertCommand({ rid: 'r-2', serverId: 'srv-a', userId: 'u-1', cmd: 'server.save',
    target: '', actor: { name: 'owner' }, idemKey: null, issuedAt: Date.now(),
    expiresAt: Date.now() + 600000, state: 'sent', frame: { t: 'cmd', rid: 'r-2' } });
  db.insertCommand({ rid: 'r-3', serverId: 'srv-a', userId: 'u-1', cmd: 'econ.grant',
    target: 'Notch', actor: { name: 'owner' }, idemKey: 'k3', issuedAt: Date.now() - 600000,
    expiresAt: Date.now() - 60000, state: 'queued', frame: { t: 'cmd', rid: 'r-3' } });
  db.close();

  const db2 = new RelayDb(dbPath);
  const inFlight = db2.failInFlight();
  assert(inFlight.length === 1 && inFlight[0].rid === 'r-2' && inFlight[0].cmd === 'server.save',
    'failInFlight closes commands that were in flight');
  const expired = db2.closeExpiredQueued();
  assert(expired.length === 1 && expired[0].rid === 'r-3' && expired[0].idemKey === 'k3',
    'closeExpiredQueued closes dead queued commands');
  assert(db2.queuedCommands('srv-a').length === 0, 'nothing left queued after closures');
  const s = db2.stats();
  assert(s.events === RING && s.commands === 3, `stats() counts rows (events=${s.events}, commands=${s.commands})`);
  db2.close();
}

// ---------------------------------------------------------------- part B ---

async function restartE2E() {
  const env = { ...process.env, RELAY_DATA_DIR: DATA };
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'user', '--name', 'owner', '--password', 'test-pass-123'], { env });
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'pair', '--user', 'owner', '--serverId', 'srv-sq01', '--secret', 'b'.repeat(64), '--name', 'SQ Server'], { env });

  const boot = () => spawn('node', [path.join(ROOT, 'src/server.js')], {
    env: { ...env, RELAY_PORT: String(PORT), RELAY_HOST: '127.0.0.1', RELAY_ALLOW_INSECURE: 'true' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  const login = async () => {
    const r = await (await fetch(`http://127.0.0.1:${PORT}/api/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'owner', password: 'test-pass-123' }),
    })).json();
    const t = await (await fetch(`http://127.0.0.1:${PORT}/api/ws-ticket`, {
      method: 'POST', headers: { Authorization: 'Bearer ' + r.token },
    })).json();
    return t.ticket;
  };

  // --- generation 1 -----------------------------------------------------
  let relay = boot();
  relay.stdout.on('data', (d) => process.stdout.write('  relay| ' + d));
  relay.stderr.on('data', (d) => process.stderr.write('  relay! ' + d));
  await waitFor(async () => {
    try { await fetch(`http://127.0.0.1:${PORT}/api/state`); return true; } catch { return false; }
  });
  console.log('setup: relay generation 1 up');

  // agent connects, streams an event, then DROPS. The ring must survive the
  // agent disconnect itself (it no longer lives inside the agents entry).
  const agent1 = new WS(`${WS_BASE}/agent`);
  await agent1.open;
  agent1.send({ sv: 1, id: 'm-h1', t: 'evt', type: 'hello', serverId: 'srv-sq01', secret: 'b'.repeat(64), agent: '0.0-test', mc: '26.1.2', caps: ['econ.grant'] });
  assert(await agent1.once('hello.ok'), 'gen1: agent hello.ok');
  agent1.send(frame('health.tps', { tps1: 18.4, gen: 1 }));
  await sleep(300);
  agent1.close();
  await sleep(300);

  // client sees the ring even though the agent is offline now
  const ticket1 = await login();
  const client1 = new WS(`${WS_BASE}/app?ticket=${ticket1}`);
  await client1.open;
  await client1.once('auth.ok');
  client1.send({ sv: 1, id: 'm-s1', t: 'evt', type: 'select', d: { serverId: 'srv-sq01' } });
  const replay1 = await client1.once('health.tps');
  assert(replay1 && replay1.d.tps1 === 18.4, 'gen1: ring replayed to client while agent offline');

  // queue a financial command while the agent is offline
  const idem = 'id-restart-001';
  const grant = {
    sv: 1, id: 'm-c1', t: 'cmd', cmd: 'econ.grant', target: 'Notch',
    args: { amountC: 5000 }, reason: 'restart survival test',
    actor: { name: 'owner', role: 'owner' }, issuedAt: Date.now(),
    expiresAt: Date.now() + 300000, idemKey: idem, confirm: { typed: 'Notch' },
  };
  client1.send(grant);
  const queued = await client1.once('cmd.queued');
  assert(queued && queued.d.rid && queued.d.cmd === 'econ.grant', 'gen1: command queued while agent offline');
  client1.close();

  // --- kill + restart ----------------------------------------------------
  relay.kill('SIGTERM');
  await sleep(500);
  relay = boot();
  relay.stdout.on('data', (d) => process.stdout.write('  relay| ' + d));
  relay.stderr.on('data', (d) => process.stderr.write('  relay! ' + d));
  await waitFor(async () => {
    try { await fetch(`http://127.0.0.1:${PORT}/api/state`); return true; } catch { return false; }
  });
  console.log('setup: relay generation 2 up (same data dir)');

  // client FIRST (so it can witness the flush), then the agent
  const ticket2 = await login();
  const client2 = new WS(`${WS_BASE}/app?ticket=${ticket2}`);
  await client2.open;
  await client2.once('auth.ok');
  client2.send({ sv: 1, id: 'm-s2', t: 'evt', type: 'select', d: { serverId: 'srv-sq01' } });
  const replay2 = await client2.once('health.tps');
  assert(replay2 && replay2.d.tps1 === 18.4 && replay2.d.gen === 1,
    'gen2: event ring survived the relay restart (replayed pre-restart event)');

  const agent2 = new WS(`${WS_BASE}/agent`);
  await agent2.open;
  agent2.send({ sv: 1, id: 'm-h2', t: 'evt', type: 'hello', serverId: 'srv-sq01', secret: 'b'.repeat(64), agent: '0.0-test', mc: '26.1.2', caps: ['econ.grant'] });
  assert(await agent2.once('hello.ok'), 'gen2: agent hello.ok');

  // the command queued before the restart must flush to the new agent
  const forwarded = await agent2.once('cmd');
  assert(forwarded && forwarded.rid === queued.d.rid && forwarded.cmd === 'econ.grant',
    'gen2: queued command survived restart and flushed to agent');

  // agent applies it and answers
  agent2.send({
    sv: 1, id: 'r-' + forwarded.id, t: 'evt', type: 'cmd.result', ts: Date.now(),
    d: { rid: forwarded.rid, cmd: 'econ.grant', target: 'Notch', status: 'applied', data: { balanceC: 5000 }, tookMs: 9 },
  });
  const auditEvt = await client2.once('cmd.audit');
  assert(auditEvt && auditEvt.d.rid === forwarded.rid && auditEvt.d.status === 'applied',
    'gen2: post-restart result closed the loop via cmd.audit (durable context)');

  // replay the SAME idemKey after the restart: duplicate, never re-forwarded
  let agentSawGrant = 0;
  agent2.on((m) => { if (m.t === 'cmd' && m.cmd === 'econ.grant') agentSawGrant++; });
  client2.send({ ...grant, id: 'm-c2' });
  const dup = await client2.once('cmd.result');
  assert(dup && dup.d.status === 'applied' && dup.d.data?.duplicate === true,
    'gen2: idemKey replay after restart returns duplicate:true (no double credit)');
  await sleep(600);
  assert(agentSawGrant === 0, 'gen2: agent never received the duplicate (zero re-forwards)');

  client2.close();
  agent2.close();
  relay.kill('SIGTERM');
  await sleep(200);
}

async function main() {
  console.log('part A: RelayDb unit tests');
  unitTests();
  console.log('part B: relay restart E2E');
  await restartE2E();
  fs.rmSync(DATA, { recursive: true, force: true });
  console.log(failures === 0 ? '\nSQLITE STORE TEST PASSED' : `\nSQLITE STORE TEST FAILED (${failures} assertions)`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
