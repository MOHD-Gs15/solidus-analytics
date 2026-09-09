'use strict';
// Solidus Cloud Relay - configuration (env-driven, safe defaults).

const path = require('node:path');

const dataDir = process.env.RELAY_DATA_DIR || path.join(__dirname, '..', 'data');

const config = {
  port: Number(process.env.RELAY_PORT || 8787),
  // SECURITY (SA2-022): bind loopback by default. The documented deployment
  // fronts the relay with a TLS-terminating reverse proxy ON THE SAME HOST,
  // so 127.0.0.1 serves it; binding 0.0.0.0 now requires an explicit
  // RELAY_HOST so a bare `npm start` can never expose plaintext auth frames
  // to the network by accident.
  host: process.env.RELAY_HOST || '127.0.0.1',
  // SECURITY (SA2-007): set RELAY_TRUST_PROXY=true ONLY when the relay runs
  // behind your own reverse proxy that OVERWRITES/appends X-Forwarded-For.
  // When enabled, the login limiter keys on the rightmost XFF entry (the
  // client address as seen by YOUR proxy) instead of the proxy's IP, so one
  // anonymous visitor can no longer lock out every operator behind the
  // shared proxy address. Leave it unset for direct exposure (XFF is then
  // attacker-controlled and MUST NOT be trusted).
  trustProxy: process.env.RELAY_TRUST_PROXY === 'true',
  dataDir,
  // P2 durable store (node:sqlite, WAL): event rings, offline command queue,
  // financial idempotency. Survives relay restarts. Requires node >= 22.5.
  dbPath: process.env.RELAY_DB_PATH || path.join(dataDir, 'relay.db'),
  publicDir: path.join(__dirname, '..', 'public'),
  allowInsecure: process.env.RELAY_ALLOW_INSECURE === 'true', // only behind a TLS-terminating proxy
  protoMin: 1,
  // PROTOCOL.md §7 + §9
  destructiveHoldMs: Number(process.env.RELAY_DESTRUCTIVE_HOLD_MS ?? 30000),
  limits: {
    financialPerMin: Number(process.env.RELAY_FIN_PER_MIN ?? 10),
    w2PerMin: Number(process.env.RELAY_W2_PER_MIN ?? 20),
    w1PerMin: Number(process.env.RELAY_W1_PER_MIN ?? 30),
    rPerMin: Number(process.env.RELAY_R_PER_MIN ?? 120),
    broadcastPerMin: 6,
    dPerHour: 3,
    commandQueue: 64,
    // ---- SA2-009 resource caps (cheap-before-expensive, §9 philosophy) ----
    maxSessionsPerUser: 16,          // bounded users.json growth (O(n²) writes)
    maxPrepareTokensPerUser: 8,      // a viewer cannot flood prepareTokens
    maxPrepareTokensTotal: 512,
    maxWsTickets: 256,               // bounded ticket map
    wsTicketPerMin: 10,              // /api/ws-ticket ceiling per user
    maxAppSockets: 256,              // global concurrent /app upgrades
    maxAgentSockets: 64,             // global concurrent /agent upgrades
    maxSocketsPerIp: 8,              // pre-auth FD/memory exhaustion cap
    rejectBudgetPerMin: 90,          // SA2-004: invalid-frame budget per user
  },
  heartbeatTimeoutMs: 120_000,      // §4.1 - agent.heartbeat.lost
  eventRing: 200,                   // §6.6
  tokenTtlDays: 30,
  wsTicketTtlMs: 30_000,            // audit P1-5 - single-use WS upgrade tickets
  auditRetentionDays: 90,
  idemCacheMs: 10 * 60_000,         // §8
  commandTtlMs: 60_000,             // §3 (D-class 90 s handled per command)
  commandRetentionDays: 7,          // P2: done command rows kept for forensics
  // SA2-003: a forwarded args object may never exceed this size - the agent
  // re-validates semantically, the relay keeps the wire cheap.
  maxArgsBytes: 8192,
  vapid: {
    publicKey: process.env.VAPID_PUBLIC_KEY || '',
    privateKey: process.env.VAPID_PRIVATE_KEY || '',
    subject: process.env.VAPID_SUBJECT || 'mailto:owner@solidus.invalid',
  },
};

// Risk classes per command id (mirrors the catalog / agent registry).
const COMMAND_META = {
  // health / players queries - R
  'health.tps': { risk: 'R', role: 'viewer' }, 'health.ram': { risk: 'R', role: 'viewer' },
  'health.cpu': { risk: 'R', role: 'viewer' }, 'health.disk': { risk: 'R', role: 'viewer' },
  'health.world': { risk: 'R', role: 'viewer' }, 'health.entities': { risk: 'R', role: 'viewer' },
  'health.meta': { risk: 'R', role: 'viewer' }, 'players.list': { risk: 'R', role: 'viewer' },
  'player.profile': { risk: 'R', role: 'viewer' }, 'player.inspect': { risk: 'R', role: 'admin' },
  'econ.top': { risk: 'R', role: 'viewer' }, 'econ.supply': { risk: 'R', role: 'viewer' },
  'econ.tx.search': { risk: 'R', role: 'viewer' }, 'econ.distribution': { risk: 'R', role: 'viewer' },
  'econ.inflation': { risk: 'R', role: 'viewer' }, 'econ.flow': { risk: 'R', role: 'viewer' },
  'econ.notifications': { risk: 'R', role: 'viewer' },
  'market.auctions.active': { risk: 'R', role: 'viewer' }, 'market.auctions.sold': { risk: 'R', role: 'viewer' },
  'market.shop.volume': { risk: 'R', role: 'viewer' }, 'market.price.trend': { risk: 'R', role: 'viewer' },
  'territory.stats': { risk: 'R', role: 'viewer' },
  // players - W1 / W2
  'player.kick': { risk: 'W1', role: 'mod' }, 'player.tp': { risk: 'W1', role: 'mod' },
  'player.gamemode': { risk: 'W1', role: 'mod' }, 'player.heal': { risk: 'W1', role: 'mod' },
  'player.feed': { risk: 'W1', role: 'mod' }, 'player.msg': { risk: 'W1', role: 'mod' },
  'server.broadcast': { risk: 'W1', role: 'mod' }, 'server.save': { risk: 'W1', role: 'mod' },
  'server.broadcast.restart': { risk: 'W1', role: 'mod' },
  'server.backup.local': { risk: 'W1', role: 'admin' }, 'server.backup.prune': { risk: 'W1', role: 'admin' },
  'server.backup.list': { risk: 'R', role: 'admin' },
  'player.ban': { risk: 'W2', role: 'admin' }, 'player.ban.ip': { risk: 'W2', role: 'admin' },
  'player.unban': { risk: 'W2', role: 'admin' }, 'player.freeze': { risk: 'W2', role: 'admin' },
  'player.unfreeze': { risk: 'W2', role: 'admin' }, 'player.give': { risk: 'W2', role: 'admin' },
  'whitelist.manage': { risk: 'W2', role: 'admin' },
  // econ money - W2 + financial (idemKey mandatory)
  'econ.grant': { risk: 'W2', role: 'admin', financial: true },
  'econ.deduct': { risk: 'W2', role: 'admin', financial: true },
  'econ.transfer': { risk: 'W2', role: 'admin', financial: true },
  'econ.grant.all': { risk: 'W2', role: 'admin', financial: true },
  'econ.freeze': { risk: 'W2', role: 'admin' }, 'econ.unfreeze': { risk: 'W2', role: 'admin' },
  'econ.resume.global': { risk: 'W2', role: 'admin' },
  'market.auction.pause': { risk: 'W2', role: 'admin' }, 'market.auction.resume': { risk: 'W2', role: 'admin' },
  'market.shop.pause': { risk: 'W2', role: 'admin' }, 'market.shop.resume': { risk: 'W2', role: 'admin' },
  'pairing.rotate': { risk: 'W2', role: 'owner' },
  // D class
  'econ.pause.global': { risk: 'D', role: 'owner' },
  'gov.freeze.global': { risk: 'D', role: 'owner' },
  'server.restart': { risk: 'D', role: 'owner' },
  'server.stop': { risk: 'D', role: 'owner' },
  'econ.rollback.tx': { risk: 'D', role: 'owner' }, 'gov.rollback.window': { risk: 'D', role: 'owner' },
  // Core+ gaps (agent answers E_CORE_MISSING; role floor kept for UX)
  'market.auction.cancel': { risk: 'W2', role: 'admin' },
  'market.auctions.cancel.bulk': { risk: 'W2', role: 'admin' },
  'market.shop.price.set': { risk: 'W2', role: 'admin' },
  'market.item.cap': { risk: 'W2', role: 'admin' }, 'market.item.ban': { risk: 'W2', role: 'admin' },
  'solidus.reload': { risk: 'W1', role: 'admin' },
  'gov.tax.run': { risk: 'W2', role: 'admin' }, 'gov.tax.config': { risk: 'W2', role: 'admin' },
  // relay-side commands (handled here, never forwarded)
  relaySide: [
    'alert.rule.manage', 'alert.rule.templates', 'alert.silence', 'alert.channel.test',
    'audit.query', 'audit.export', 'session.list', 'session.revoke',
  ],
};

const RANK = { viewer: 0, mod: 1, admin: 2, owner: 3 };

module.exports = { config, COMMAND_META, RANK };
