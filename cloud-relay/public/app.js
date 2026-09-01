/* Solidus Cloud PWA - client application.
 * Speaks the v1 protocol to the relay (see docs/cloud/PROTOCOL.md):
 * login -> token -> wss /app -> select server -> live events + commands
 * with risk-tiered confirmations (W2 typed name, D prepare-token + password + hold). */
'use strict';

const $ = (id) => document.getElementById(id);
const fmtC = (c) => c == null ? '—' : (c / 100).toLocaleString('en-US', { maximumFractionDigits: 2 }) + ' S$';
const fmtMB = (b) => b == null || b < 0 ? '—' : b + ' MB';
const fmtDur = (s) => s == null ? '—' : s < 3600 ? Math.floor(s / 60) + 'm ' + (s % 60) + 's' : Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm';

let TOKEN = localStorage.getItem('sc_token') || null;
let WS = null;
let MY = { user: null, role: 'viewer', servers: [] };
let currentServer = null;
let commandDefs = [];

// ---------- catalog of PWA-facing commands (risk + fields) ----------
const COMMANDS = [
  { id: 'player.kick', risk: 'W1', desc: 'Kick with reason', target: 'player', reason: true },
  { id: 'player.msg', risk: 'W1', desc: 'Private message', target: 'player', fields: [{ k: 'message', label: 'Message' }] },
  { id: 'server.broadcast', risk: 'W1', desc: 'Broadcast to all', fields: [{ k: 'message', label: 'Message' }] },
  { id: 'player.ban', risk: 'W2', desc: 'Ban permanently', target: 'player', reason: true },
  { id: 'player.unban', risk: 'W2', desc: 'Pardon by name', target: 'player', reason: true },
  { id: 'player.freeze', risk: 'W2', desc: 'Movement anchor lock', target: 'player', reason: true },
  { id: 'player.unfreeze', risk: 'W2', desc: 'Release movement lock', target: 'player', reason: true },
  { id: 'player.give', risk: 'W2', desc: 'Give items (creates value!)', target: 'player', reason: true, fields: [{ k: 'item', label: 'Item (minecraft:…)', ph: 'minecraft:diamond' }, { k: 'qty', label: 'Quantity', type: 'number', def: 1 }] },
  { id: 'econ.grant', risk: 'W2', desc: 'Grant balance', target: 'player', reason: true, financial: true, fields: [{ k: 'amountC', label: 'Amount (cents)', type: 'number', ph: '25000 = 250.00' }] },
  { id: 'econ.deduct', risk: 'W2', desc: 'Deduct / fine', target: 'player', reason: true, financial: true, fields: [{ k: 'amountC', label: 'Amount (cents)', type: 'number' }] },
  { id: 'econ.transfer', risk: 'W2', desc: 'Transfer between players', target: 'player', reason: true, financial: true, fields: [{ k: 'to', label: 'To player' }, { k: 'amountC', label: 'Amount (cents)', type: 'number' }] },
  { id: 'econ.freeze', risk: 'W2', desc: 'Freeze account money', target: 'player', reason: true },
  { id: 'econ.unfreeze', risk: 'W2', desc: 'Unfreeze account', target: 'player', reason: true },
  { id: 'econ.resume.global', risk: 'W2', desc: 'Resume economy', reason: true },
  { id: 'market.auction.pause', risk: 'W2', desc: 'Pause auctions', reason: true },
  { id: 'market.shop.pause', risk: 'W2', desc: 'Pause shop', reason: true },
  { id: 'econ.pause.global', risk: 'D', desc: 'CIRCUIT BREAKER - freeze all money movement', reason: true },
  { id: 'server.restart', risk: 'D', desc: 'Restart server (needs restart-capable host)', reason: true },
  { id: 'server.stop', risk: 'D', desc: 'Stop server', reason: true },
  { id: 'server.save', risk: 'W1', desc: 'Save all worlds' },
];

// ---------- login ----------
async function login(name, pass) {
  const r = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, password: pass }) });
  if (!r.ok) throw new Error('bad credentials');
  const d = await r.json();
  TOKEN = d.token;
  localStorage.setItem('sc_token', TOKEN);
  startApp();
}

$('login-form').addEventListener('submit', (e) => {
  e.preventDefault();
  $('login-error').classList.add('hidden');
  login($('login-name').value.trim(), $('login-pass').value).catch((err) => {
    $('login-error').textContent = 'Sign-in failed: ' + err.message;
    $('login-error').classList.remove('hidden');
  });
});

$('logout').addEventListener('click', () => {
  localStorage.removeItem('sc_token');
  location.reload();
});

// ---------- pairing ----------
$('pair-btn').addEventListener('click', async () => {
  const r = await fetch('/api/pair', { method: 'POST', headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json' },
    body: JSON.stringify({ serverId: $('pair-server-id').value.trim(), secret: $('pair-secret').value.trim(), name: $('pair-name').value.trim() }) });
  const d = await r.json();
  $('pair-result').textContent = d.paired ? `Paired ${d.serverId} ✓ - flip cloud.enabled=true on the server, it will appear here within seconds.` : ('Failed: ' + (d.error || 'unknown'));
  if (d.paired) setTimeout(() => location.reload(), 1500);
});

// ---------- app boot ----------
async function startApp() {
  const r = await fetch('/api/state', { headers: { Authorization: 'Bearer ' + TOKEN } });
  if (!r.ok) { localStorage.removeItem('sc_token'); location.reload(); return; }
  const st = await r.json();
  MY = st;
  $('login').classList.add('hidden');
  $('app').classList.remove('hidden');
  if (st.servers.length === 0) $('pair-panel').classList.remove('hidden');
  const sel = $('server-select');
  sel.innerHTML = '';
  for (const s of st.servers) {
    const o = document.createElement('option');
    o.value = s.serverId; o.textContent = `${s.name} ${s.entitled ? '' : '($ expired)'}`;
    sel.appendChild(o);
  }
  connectWs();
}

// ---------- websocket ----------
function connectWs() {
  WS = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/app?token=${TOKEN}`);
  WS.onopen = () => { $('conn-badge').textContent = 'connected'; $('conn-badge').className = 'badge on'; };
  WS.onclose = () => {
    $('conn-badge').textContent = 'offline';
    $('conn-badge').className = 'badge off';
    setTimeout(connectWs, 3000);
  };
  WS.onmessage = (ev) => handle(JSON.parse(ev.data));
}

$('server-select').addEventListener('change', () => {
  currentServer = $('server-select').value;
  WS.send(JSON.stringify({ sv: 1, id: 'sel', t: 'evt', type: 'select', d: { serverId: currentServer } }));
  renderCommands();
});

function handle(m) {
  switch (m.type) {
    case 'auth.ok': {
      MY.role = m.d.role;
      currentServer = m.d.servers[0]?.serverId || null;
      $('server-select').value = currentServer;
      if (currentServer) WS.send(JSON.stringify({ sv: 1, id: 'sel0', t: 'evt', type: 'select', d: { serverId: currentServer } }));
      renderCommands();
      break;
    }
    case 'health.tps': set('t-tps', `${m.d.tps1} / ${m.d.tps5} / ${m.d.tps15}`); set('t-mspt', `MSPT ${m.d.msptAvg} p95 ${m.d.msptP95} · spikes ${m.d.spikes}`); break;
    case 'health.ram':
      set('t-ram', `${m.d.heapUsedB} / ${m.d.heapMaxB} MB`);
      $('t-ram-bar').firstElementChild.style.width = m.d.heapMaxB > 0 ? Math.min(100, (m.d.heapUsedB / m.d.heapMaxB) * 100) + '%' : '0';
      break;
    case 'health.cpu': set('t-cpu', (m.d.procPct ?? '—') + '%'); set('t-load', `sys ${m.d.sysPct}% · load1 ${m.d.load1}`); break;
    case 'players.list': renderPlayers(m.d); break;
    case 'econ.supply': set('t-supply', fmtC(m.d.supplyC)); set('t-supply-d', `24h Δ ${fmtC(m.d.delta24hC)} · ${m.d.players} players`); break;
    case 'econ.distribution': set('t-gini', m.d.gini); set('t-top1', `top 1%: ${m.d.top1Pct}% · median ${fmtC(m.d.medianC)}`); break;
    case 'econ.flow': set('t-vol', fmtC(m.d.dayVolC)); set('t-tx', `${m.d.dayCount} tx today · ${m.d.activePlayers} active`); break;
    case 'market.auctions.active': set('t-auct', m.d.count); set('t-auct-v', 'value ' + fmtC(m.d.totalValueC)); break;
    case 'agent.state': renderState(m.d); break;
    case 'agent.status': {
      const b = $('conn-badge');
      b.textContent = m.d.online ? 'server online' : 'server offline';
      b.className = 'badge ' + (m.d.online ? 'on' : 'off');
      commandDefs = m.d.caps || [];
      renderCommands();
      break;
    }
    case 'cmd.audit': auditRow(m.d, true); break;
    case 'cmd.result': cmdResult(m.d); break;
    case 'cmd.queued': toast(`queued: ${m.d.cmd} (agent offline)`); break;
    case 'prepare.ok': modalPrepareOk(m.d); break;
    case 'player.join': toast(`${m.d.n} joined`); break;
    case 'player.leave': toast(`${m.d.n} left`); break;
    case 'health.lag_spike': toast(`⚠ lag spike: ${m.d.run} ticks / ${m.d.ms}ms`); auditRow({ cmd: 'health.lag_spike', status: 'spike', error: `${m.d.run} ticks > 75ms` }, true); break;
  }
}

function set(id, v) { $(id).textContent = v == null ? '—' : v; }

// ---------- rendering ----------
function renderPlayers(d) {
  const tb = $('players-table').tBodies[0];
  tb.innerHTML = '';
  $('players-empty').classList.toggle('hidden', (d.players || []).length > 0);
  set('t-players', `${d.players?.length ?? 0}/${d.max ?? '?'}`);
  for (const p of d.players || []) {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${esc(p.n)}</td><td>${esc(p.mode) || '—'}</td><td>${esc((p.level || '').split(':').pop())}</td><td>${fmtDur(p.sessS)}</td>`;
    const td = document.createElement('td');
    const btn = document.createElement('button');
    btn.className = 'ghost'; btn.textContent = 'profile';
    btn.onclick = () => sendCommand('player.profile', { target: p.n }, p.n);
    td.appendChild(btn); tr.appendChild(td); tb.appendChild(tr);
  }
}

function renderState(d) {
  const b = $('state-banners');
  b.innerHTML = '';
  if (d.pause?.active) banner(`⛔ ECONOMY PAUSED — ${esc(d.pause.reason)} (by ${esc(d.pause.by)})`, 'bad');
  if (d.auctionsPaused?.active) banner('Auctions paused — ' + esc(d.auctionsPaused.reason), 'warn');
  if (d.shopPaused?.active) banner('Shop paused — ' + esc(d.shopPaused.reason), 'warn');
  if ((d.frozen || []).length) banner(`Frozen accounts: ${d.frozen.map((f) => esc(f.n)).join(', ')}`, 'warn');
}

function banner(text, cls) {
  const div = document.createElement('div');
  div.className = 'banner ' + cls; div.textContent = text;
  $('state-banners').appendChild(div);
}

// ---------- commands ----------
const ROLE_OK = { R: true, W1: ['mod', 'admin', 'owner'], W2: ['admin', 'owner'], D: ['owner'] };

function renderCommands() {
  const grid = $('cmd-grid');
  grid.innerHTML = '';
  const caps = commandDefs.length ? commandDefs : COMMANDS.map((c) => c.id);
  for (const c of COMMANDS) {
    const btn = document.createElement('button');
    btn.className = 'cmd-btn ' + c.risk;
    btn.innerHTML = `<b>${c.id}</b><small>${c.risk} · ${c.desc}</small>`;
    const roleOk = ROLE_OK[c.risk] === true || ROLE_OK[c.risk].includes(MY.role);
    const capOk = c.risk === 'R' || caps.includes(c.id);
    btn.disabled = !(roleOk && capOk);
    btn.onclick = () => openModal(c);
    grid.appendChild(btn);
  }
}

let modal = { c: null, target: '', token: null, validUntil: 0, sending: false };

function openModal(c) {
  modal = { c, target: '', token: null, validUntil: 0, sending: false };
  $('modal-title').textContent = c.id + '  [' + c.risk + ']';
  $('modal-desc').textContent = c.desc + (c.risk === 'D' ? ' — destructive: password re-entry + 30s hold.' : c.risk === 'W2' ? ' — type the target name exactly + a reason.' : '');
  const f = $('modal-fields');
  f.innerHTML = '';
  if (c.target) field(f, 'target', 'Target player name');
  for (const x of c.fields || []) field(f, x.k, x.label, x.type, x.ph || '', x.def);
  if (c.reason) field(f, 'reason', 'Reason (required)', null, 'why are you doing this?');
  if (c.risk === 'W2') field(f, 'typed', `Type "${c.target ? 'the target name' : 'CONFIRM'}" exactly`);
  if (c.risk === 'D') field(f, 'password', 'Your password (re-entry)', 'password');
  $('modal-status').textContent = '';
  $('modal').classList.remove('hidden');
  $('modal-send').textContent = 'Execute';
  $('modal-send').disabled = false;
}

function field(parent, k, label, type, ph, def) {
  const l = document.createElement('div');
  l.className = 'field-label'; l.textContent = label;
  const i = document.createElement('input');
  i.id = 'mf-' + k; if (type) i.type = type; if (ph) i.placeholder = ph; if (def != null) i.value = def;
  parent.appendChild(l); parent.appendChild(i);
}

$('modal-cancel').addEventListener('click', () => $('modal').classList.add('hidden'));

$('modal-send').addEventListener('click', async () => {
  const c = modal.c;
  if (!c || modal.sending) return;
  const val = (k) => { const el = $('mf-' + k); return el ? el.value.trim() : ''; };
  const target = c.target ? val('target') : '';
  const args = {};
  for (const x of c.fields || []) args[x.k] = x.type === 'number' ? Number(val(x.k)) : val(x.k);
  const frame = {
    sv: 1, id: 'm-' + crypto.randomUUID().slice(0, 10), t: 'cmd', cmd: c.id, target,
    args, reason: val('reason') || undefined, actor: { name: MY.user, role: MY.role },
    issuedAt: Date.now(), expiresAt: Date.now() + (c.risk === 'D' ? 90000 : 60000),
  };
  if (c.financial) frame.idemKey = 'id-' + crypto.randomUUID().slice(0, 12);
  if (c.risk === 'W2') frame.confirm = { typed: val('typed') };
  if (c.risk === 'D') {
    frame.confirm = { typed: val('typed') || target || 'CONFIRM', password: val('password') };
    if (!modal.token) {
      modal.sending = true;
      $('modal-status').textContent = 'preparing destructive confirmation…';
      WS.send(JSON.stringify({ sv: 1, id: 'p-' + crypto.randomUUID().slice(0, 8), t: 'evt', type: 'prepare', cmdTarget: c.id, target }));
      modal.pendingFrame = frame;
      return;
    }
    frame.confirm.token = modal.token;
  }
  modal.sending = true;
  $('modal-status').textContent = 'executing…';
  WS.send(JSON.stringify(frame));
});

function modalPrepareOk(d) {
  modal.token = d.token;
  modal.validUntil = d.validUntil;
  const hold = d.holdMs || 30000;
  $('modal-status').textContent = `destructive hold: ${Math.round(hold / 1000)}s countdown…`;
  const btn = $('modal-send');
  btn.disabled = true;
  let left = Math.ceil(hold / 1000);
  const iv = setInterval(() => {
    left -= 1;
    if (left <= 0) {
      clearInterval(iv);
      btn.disabled = false;
      btn.textContent = 'EXECUTE NOW';
      $('modal-status').textContent = 'hold elapsed - re-checked your password? execute when ready.';
      modal.sending = false;
    } else {
      btn.textContent = `hold ${left}s`;
    }
  }, 1000);
  // stash frame; the next Execute click will attach the token
  const orig = $('modal-send').onclick;
  $('modal-send').onclick = null;
  const send = () => {
    if (!modal.token) return;
    const frame = modal.pendingFrame;
    frame.confirm.token = modal.token;
    frame.confirm.password = $('mf-password') ? $('mf-password').value : '';
    $('modal-status').textContent = 'executing…';
    WS.send(JSON.stringify(frame));
  };
  $('modal-send').addEventListener('click', function once() {
    if (modal.token) { send(); $('modal-send').removeEventListener('click', once); }
  });
}

function cmdResult(d) {
  const st = $('modal-status');
  if (d.status === 'applied') {
    st.textContent = `✓ applied in ${d.tookMs}ms` + (d.data ? ' · ' + JSON.stringify(d.data).slice(0, 140) : '');
    toast(`✓ ${d.cmd} applied`);
  } else if (d.duplicate) {
    st.textContent = '↻ duplicate — original result returned';
  } else {
    st.textContent = `✗ ${d.status}: ${d.code || ''} ${d.error || ''}`;
    toast(`✗ ${d.cmd}: ${d.code || d.status}`);
  }
  modal.sending = false;
  $('modal-send').disabled = false;
  setTimeout(() => $('modal').classList.add('hidden'), 2200);
  auditRow(d, true);
}

function sendCommand(cmd, args, target) {
  const frame = { sv: 1, id: 'q-' + crypto.randomUUID().slice(0, 8), t: 'cmd', cmd, target: target || '', args, actor: { name: MY.user, role: MY.role }, issuedAt: Date.now() };
  if (cmd === 'player.profile') { frame.d = null; }
  WS.send(JSON.stringify(frame));
  toast('querying ' + cmd + '…');
}

// ---------- audit feed ----------
function auditRow(d, prepend) {
  const feed = $('audit-feed');
  const row = document.createElement('div');
  row.className = 'row';
  const t = new Date(d.ts || Date.now()).toLocaleTimeString();
  row.innerHTML = `<span class="t">${t}</span><span class="${d.status || ''}">${d.status || ''}</span><span>${esc(d.cmd || d.kind || '')}</span><span>${esc(d.target || '')}</span><span class="muted">${esc(d.actorName || d.actor?.name || '')}</span><span class="muted">${esc(d.code || d.error || '')}</span>`;
  if (prepend) feed.prepend(row); else feed.appendChild(row);
  while (feed.children.length > 120) feed.removeChild(feed.lastChild);
}

$('audit-more').addEventListener('click', () => {
  WS.send(JSON.stringify({ sv: 1, id: 'aq', t: 'cmd', cmd: 'audit.query', args: { limit: 40 }, actor: { name: MY.user, role: MY.role } }));
});

// ---------- misc ----------
function toast(text) {
  const el = document.createElement('div');
  el.className = 'toast-item'; el.textContent = text;
  $('toast').appendChild(el);
  setTimeout(() => el.remove(), 4200);
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---------- push enrollment ----------
$('push-enroll').addEventListener('click', async () => {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return toast('push not supported on this browser');
  const reg = await navigator.serviceWorker.register('/sw.js');
  const r = await fetch('/api/state', { headers: { Authorization: 'Bearer ' + TOKEN } });
  if (!(await r.json()).pushReady) return toast('relay push not configured (VAPID keys missing)');
  const sub = await reg.pushManager.subscribe({ userVisibleOnly: true });
  await fetch('/api/push-subscribe', { method: 'POST', headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json' },
    body: JSON.stringify({ serverId: currentServer, subscription: sub.toJSON() }) });
  toast('push notifications enabled ✓');
});

// boot
if (TOKEN) startApp();
