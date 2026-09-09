'use strict';
// Solidus Cloud Relay - args schema validation (SA2-003, PROTOCOL §6.1 step 2
// + §6.4 + §15). The relay is the FIRST enforcement point: every forwarded
// frame's args must match the catalog schema below - unknown keys -> E_ARGS,
// closed enums, bounded integers, length-capped strings. The agent 2.1.4
// re-validates everything again (defense in depth), but an old/weak agent on
// the other end is no longer the only line of defense.

// Schema value spec:
//   s(len)          string, trimmed, max length len (default 256)
//   name            player-name string: ^[A-Za-z0-9_]{1,16}$ after trim
//   ident(len)      material/id string: ^[a-z0-9_.:\-]{1,len}$ (blocks NBT brackets)
//   i(min,max)      safe integer, inclusive bounds
//   b               boolean
//   e(...vals)      closed enum
//   obj({...})      nested plain object (depth 1), own spec
//   any             generic: string<=512 / finite number / boolean (gov.tax.config)
//
// A key is OPTIONAL when wrapped in opt(). Everything not listed -> E_ARGS.

const s = (len = 256) => ({ t: 's', len });
const name = () => ({ t: 'name' });
const ident = (len = 64) => ({ t: 'ident', len });
const i = (min, max) => ({ t: 'i', min, max });
const b = () => ({ t: 'b' });
const e = (...vals) => ({ t: 'e', vals });
const obj = (spec) => ({ t: 'obj', spec });
const any = () => ({ t: 'any' });
const opt = (spec) => ({ ...spec, optional: true });

const MONEY = () => i(1, 10_000_000);          // §15: amountC 1..10000000
const AGG_MONEY = () => i(1, 100_000_000);     // §15: capC 1..100000000
const MS = () => i(0, 4_000_000_000_000);      // epoch-millis bounds

// ---- catalog schemas (mirrors PROTOCOL.md §15; relay-side commands listed
// ---- here too so alert-rule args get the same treatment) --------------------
const SCHEMA = {
  // health (queries only)
  'health.tps': {}, 'health.ram': {}, 'health.cpu': {}, 'health.disk': {},
  'health.world': {}, 'health.entities': {}, 'health.meta': {},
  // players
  'players.list': {},
  'player.profile': {},
  'player.inspect': { reveal: opt(b()) },
  'player.kick': {}, 'player.heal': {}, 'player.feed': {},
  'player.ban': {}, 'player.ban.ip': {}, 'player.unban': {},
  'player.freeze': { anchor: opt(e('current', 'spawn')) },
  'player.unfreeze': {},
  'player.gamemode': { mode: e('survival', 'creative', 'adventure', 'spectator') },
  'player.tp': { to: obj({ kind: e('spawn', 'coords', 'player'), x: opt(i(-30_000_000, 30_000_000)), y: opt(i(-2048, 2047)), z: opt(i(-30_000_000, 30_000_000)), player: opt(name()) }) },
  'player.give': { item: ident(64), qty: i(1, 64) },
  'player.msg': { message: s(512) },
  'server.broadcast': { message: s(512), tier: opt(e('all', 'ops')) },
  'server.save': {},
  'server.broadcast.restart': { delayS: i(30, 300) },
  'server.backup.local': { worlds: opt(b()), dbs: opt(b()) },
  'server.backup.list': {},
  'server.backup.prune': { keepDays: i(1, 365) },
  'whitelist.manage': { action: e('add', 'remove', 'on', 'off'), target: opt(name()) },
  // econ
  'econ.top': { limit: opt(i(1, 50)) },
  'econ.supply': {}, 'econ.distribution': {}, 'econ.inflation': {},
  'econ.flow': {}, 'econ.notifications': {},
  'econ.tx.search': {
    type: opt(s(32)), player: opt(s(64)), material: opt(ident(64)),
    minC: opt(i(0, 1_000_000_000_000)), maxC: opt(i(0, 1_000_000_000_000)),
    sinceMs: opt(MS()), limit: opt(i(1, 100)),
  },
  'econ.grant': { amountC: MONEY() },
  'econ.deduct': { amountC: MONEY() },
  'econ.transfer': { to: name(), amountC: MONEY() },
  'econ.grant.all': { scope: e('online', 'known'), amountC: MONEY(), capC: AGG_MONEY() },
  'econ.freeze': {}, 'econ.unfreeze': {},
  'econ.pause.global': {}, 'econ.resume.global': {},
  'econ.rollback.tx': { txId: i(1, 9_000_000_000_000_000) },
  // market
  'market.auctions.active': {}, 'market.auctions.sold': {}, 'market.shop.volume': {},
  'market.price.trend': { material: ident(64), points: i(10, 500) },
  'territory.stats': {},
  'market.auction.pause': {}, 'market.auction.resume': {},
  'market.shop.pause': {}, 'market.shop.resume': {},
  'market.auction.cancel': {}, 'market.auctions.cancel.bulk': {},
  'market.shop.price.set': {},
  'market.item.cap': { material: ident(64), priceC: i(0, 10_000_000_000) },
  'market.item.ban': { material: ident(64) },
  // lifecycle / governance
  'solidus.reload': {},
  'pairing.rotate': {},
  'gov.tax.run': {},
  'gov.tax.config': { config: opt(any()) },
  'gov.freeze.global': {},
  'gov.rollback.window': { sinceMs: MS(), untilMs: MS() },
  // lifecycle D-class
  'server.restart': {},
  'server.stop': {},
  // relay-side (audit.query/export limits are enforced separately)
  'audit.query': { limit: opt(i(1, 200)), fromMs: opt(MS()), toMs: opt(MS()), actor: opt(s(64)), cmd: opt(s(64)), target: opt(s(64)) },
  'audit.export': { format: opt(e('json', 'csv')), fromMs: opt(MS()), toMs: opt(MS()), actor: opt(s(64)), cmd: opt(s(64)), target: opt(s(64)) },
  'session.list': {},
  'session.revoke': { dev: opt(s(64)) },
  'alert.silence': { minutes: opt(i(5, 1440)) },
  'alert.rule.templates': { template: e('tps', 'ram', 'cpu') },
  'alert.rule.manage': { action: e('create', 'delete', 'update'), rule: opt(obj({ metric: opt(e('tps', 'ram', 'cpu')), op: opt(e('<', '>')), threshold: opt(i(-1_000_000, 1_000_000)), forMs: opt(i(0, 86_400_000)), silenceMin: opt(i(0, 1440)), enabled: opt(b()), id: opt(s(64)), serverId: opt(s(64)), channels: opt(any()) })) },
  'alert.channel.test': {},
};

const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;
const IDENT_RE = /^[a-z0-9_.:\-]{1,64}$/;

function checkValue(key, spec, v, path) {
  const at = path ? `${path}.${key}` : key;
  switch (spec.t) {
    case 's':
      if (typeof v !== 'string') return `must be a string`;
      if (v.trim().length > spec.len) return `exceeds ${spec.len} chars`;
      return null;
    case 'name':
      if (typeof v !== 'string' || !NAME_RE.test(v.trim())) return `must match ^[A-Za-z0-9_]{1,16}$`;
      return null;
    case 'ident':
      if (typeof v !== 'string' || !IDENT_RE.test(v)) return `must match ^[a-z0-9_.:-]{1,64}$`;
      return null;
    case 'i':
      if (typeof v !== 'number' || !Number.isInteger(v)) return `must be an integer`;
      if (v < spec.min || v > spec.max) return `out of range [${spec.min}..${spec.max}]`;
      return null;
    case 'b':
      if (typeof v !== 'boolean') return `must be a boolean`;
      return null;
    case 'e':
      if (!spec.vals.includes(v)) return `must be one of: ${spec.vals.join('|')}`;
      return null;
    case 'obj': {
      if (typeof v !== 'object' || v === null || Array.isArray(v)) return `must be an object`;
      return checkArgs(v, spec.spec, at);
    }
    case 'any':
      if (typeof v === 'string') return v.length > 512 ? `exceeds 512 chars` : null;
      if (typeof v === 'number') return Number.isFinite(v) ? null : `must be finite`;
      if (typeof v === 'boolean' || v === null) return null;
      return `unsupported type`;
    default:
      return `unsupported spec`;
  }
}

function checkArgs(args, spec, path = '') {
  if (args === undefined || args === null) args = {};
  if (typeof args !== 'object' || Array.isArray(args)) return 'args must be an object';
  for (const [k, v] of Object.entries(args)) {
    const spec2 = spec[k];
    if (!spec2) return `unknown key "${k}" (E_ARGS, §6.4)`;
    if (typeof v === 'object' && v !== null && spec2.t !== 'obj') return `"${k}" must not be a nested object`;
    const err = checkValue(k, spec2, v, path);
    if (err) return `"${k}" ${err}`;
  }
  for (const [k, spec2] of Object.entries(spec)) {
    if (!spec2.optional && !(k in args)) return `missing required key "${k}"`;
  }
  return null;
}

/**
 * Validates a client command frame's args against the catalog schema.
 * @returns {null} when args are acceptable, or a short E_ARGS reason string.
 */
function validateArgs(cmd, frame) {
  const spec = SCHEMA[cmd];
  // Fail-closed: every allow-listed command MUST have a schema here. A new
  // catalog entry without a schema is a relay bug, not a pass-through.
  if (!spec) return 'no relay schema for this command (fail-closed)';
  const args = frame.args;
  if (args === undefined || args === null) {
    return Object.keys(spec).some((k) => !spec[k].optional) ? 'args required' : null;
  }
  if (typeof args !== 'object' || Array.isArray(args)) return 'args must be an object';
  if (JSON.stringify(args).length > 8192) return 'args exceed 8 KiB';
  if (Object.keys(args).length > 24) return 'too many args keys';
  return checkArgs(args, spec);
}

/** Validates the alert-rule payload posted via alert.rule.manage (SA2-006). */
function sanitizeAlertRule(input) {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return null;
  const out = {};
  if (input.metric !== undefined) {
    if (!['tps', 'ram', 'cpu'].includes(input.metric)) return null;
    out.metric = input.metric;
  }
  if (input.op !== undefined) {
    if (!['<', '>'].includes(input.op)) return null;
    out.op = input.op;
  }
  if (input.threshold !== undefined) {
    if (typeof input.threshold !== 'number' || !Number.isFinite(input.threshold)) return null;
    out.threshold = input.threshold;
  }
  if (input.forMs !== undefined) {
    if (!Number.isInteger(input.forMs) || input.forMs < 0 || input.forMs > 86_400_000) return null;
    out.forMs = input.forMs;
  }
  if (input.silenceMin !== undefined) {
    if (!Number.isInteger(input.silenceMin) || input.silenceMin < 0 || input.silenceMin > 1440) return null;
    out.silenceMin = input.silenceMin;
  }
  if (input.enabled !== undefined) {
    if (typeof input.enabled !== 'boolean') return null;
    out.enabled = input.enabled;
  }
  if (input.channels !== undefined) {
    if (!Array.isArray(input.channels) || input.channels.some((c) => c !== 'push')) return null;
    out.channels = input.channels;
  }
  // serverId / id / builtin are NEVER client-settable: ownership fields are
  // stamped by the relay itself (SA2-006).
  return out;
}

module.exports = { validateArgs, sanitizeAlertRule, SCHEMA };
