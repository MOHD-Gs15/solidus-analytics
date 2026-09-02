'use strict';
// Security edge tests for the Solidus Cloud Relay (audit P0-1, P0-2, P1-5).
// Boots a dedicated relay instance and verifies from the OUTSIDE:
//   1. every HTTP response carries the hardening headers (CSP, XFO,
//      Referrer-Policy, nosniff, CORP) and API responses are no-store,
//   2. /api/login enforces the 5-attempts lockout (429 + Retry-After, and
//      even the CORRECT password is refused while locked),
//   3. WebSocket upgrades require a valid single-use ticket.

const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const PORT = 9301 + Math.floor(Math.random() * 200);
const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'solidus-sec-'));
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

async function main() {
  const env = { ...process.env, RELAY_DATA_DIR: DATA };
  execFileSync('node', [path.join(ROOT, 'src/cli.js'), 'user', '--name', 'owner', '--password', 'sec-pass-123'], { env });
  console.log('setup: owner created');

  const relay = spawn('node', [path.join(ROOT, 'src/server.js')], {
    env: { ...env, RELAY_PORT: String(PORT), RELAY_HOST: '127.0.0.1', RELAY_ALLOW_INSECURE: 'true' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  relay.stdout.on('data', () => {});
  relay.stderr.on('data', (d) => process.stderr.write('  relay! ' + d));
  await waitFor(async () => {
    try { await fetch(`${HTTP}/api/state`); return true; } catch { return false; }
  });
  console.log('setup: relay up');

  try {
    // ---- 1) security headers (P0-1) --------------------------------------
    const home = await fetch(`${HTTP}/`);
    const csp = home.headers.get('content-security-policy') || '';
    assert(home.headers.get('x-content-type-options') === 'nosniff', 'static: nosniff');
    assert(home.headers.get('x-frame-options') === 'DENY', 'static: X-Frame-Options DENY');
    assert(home.headers.get('referrer-policy') === 'no-referrer', 'static: Referrer-Policy no-referrer');
    assert(home.headers.get('cross-origin-resource-policy') === 'same-origin', 'static: CORP same-origin');
    assert(csp.includes("default-src 'self'"), 'static: CSP default-src self');
    assert(csp.includes('frame-ancestors \'none\''), 'static: CSP frame-ancestors none');
    assert(csp.includes('connect-src') && csp.includes(`ws://127.0.0.1:${PORT}`), 'static: CSP connect-src bound to serving host only');

    const st = await fetch(`${HTTP}/api/state`);
    assert(st.headers.get('cache-control') === 'no-store', 'api: Cache-Control no-store');
    assert((st.headers.get('content-security-policy') || '').includes("default-src 'self'"), 'api: CSP present too');

    const loginHeaders = (await post('/api/login', { name: 'owner', password: 'wrong' })).headers;
    assert(loginHeaders.get('cache-control') === 'no-store', 'login: no-store on auth responses');
    assert(loginHeaders.get('x-frame-options') === 'DENY', 'login: X-Frame-Options on 401');

    // ---- 2) login lockout (P0-2) ------------------------------------------
    // 4 more wrong passwords (1 already above) -> 5 failures total.
    for (let i = 0; i < 4; i++) {
      const r = await post('/api/login', { name: 'owner', password: 'wrong' });
      assert(r.status === 401, `wrong password #${i + 2} -> 401`);
    }
    // 6th attempt: even the CORRECT password is refused while locked.
    const locked = await post('/api/login', { name: 'owner', password: 'sec-pass-123' });
    assert(locked.status === 429, 'locked IP gets 429 even with correct password');
    const retryAfter = Number(locked.headers.get('retry-after') || 0);
    assert(retryAfter > 0 && retryAfter <= 300, `429 carries Retry-After (${retryAfter}s)`);
    const body = await locked.json();
    assert(typeof body.error === 'string', '429 body is generic JSON');

    // a DIFFERENT account name from the same IP: also refused (per-IP lock).
    const other = await post('/api/login', { name: 'other-user', password: 'x' });
    assert(other.status === 429, 'per-IP lockout covers other account names');

    // ---- 3) ws ticket flow (P1-5) -----------------------------------------
    // The IP is locked; use a fresh session obtained BEFORE the lockout is
    // impossible now, so verify tickets via direct limiter math instead:
    // unlock by using a different source port? No - spawn a probe with the
    // limiter clock: simplest is to test tickets against the still-valid
    // session from... we cannot login (locked). So: restart relay to clear
    // the in-memory limiter (documented behavior), then test tickets.
    relay.kill('SIGTERM');
    await sleep(300);
    const relay2 = spawn('node', [path.join(ROOT, 'src/server.js')], {
      env: { ...env, RELAY_PORT: String(PORT), RELAY_HOST: '127.0.0.1', RELAY_ALLOW_INSECURE: 'true' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    relay2.stdout.on('data', () => {});
    relay2.stderr.on('data', () => {});
    await waitFor(async () => {
      try { await fetch(`${HTTP}/api/state`); return true; } catch { return false; }
    });
    console.log('setup: relay restarted (in-memory limiter reset, sessions persist)');

    const login = await (await post('/api/login', { name: 'owner', password: 'sec-pass-123' })).json();
    assert(login.token, 'login works again after restart');

    const badTicketReq = await post('/api/ws-ticket', {}, { Authorization: 'Bearer nope' });
    assert(badTicketReq.status === 401, 'ws-ticket requires a valid Bearer token');

    const tkt = await (await post('/api/ws-ticket', {}, { Authorization: 'Bearer ' + login.token })).json();
    assert(tkt.ticket && tkt.expiresAt > Date.now(), 'ws-ticket issued');

    const probe = (url) => new Promise((resolve) => {
      const ws = new WebSocket(url);
      let gotAuth = false;
      ws.addEventListener('message', (ev) => { if (JSON.parse(String(ev.data)).type === 'auth.ok') gotAuth = true; });
      ws.addEventListener('close', () => resolve(gotAuth));
      ws.addEventListener('error', () => resolve(gotAuth));
      setTimeout(() => { try { ws.close(); } catch {} resolve(gotAuth); }, 1500);
    });

    assert(await probe(`${WS_BASE}/app?ticket=${tkt.ticket}`) === true, 'valid ticket authenticates the socket');

    // reuse of the SAME ticket must fail (single-use)
    assert(await probe(`${WS_BASE}/app?ticket=${tkt.ticket}`) === false, 'ticket reuse rejected');

    // garbage ticket
    assert(await probe(`${WS_BASE}/app?ticket=wt-deadbeef`) === false, 'unknown ticket rejected');

    // long-lived token on the URL is refused outright
    assert(await probe(`${WS_BASE}/app?token=${login.token}`) === false, 'token-in-URL rejected');

    relay2.kill('SIGTERM');
    await sleep(200);
  } finally {
    try { relay.kill('SIGTERM'); } catch {}
    fs.rmSync(DATA, { recursive: true, force: true });
  }

  console.log(failures === 0 ? '\nSECURITY TEST PASSED' : `\nSECURITY TEST FAILED (${failures} assertions)`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
