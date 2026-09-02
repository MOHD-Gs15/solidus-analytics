# Solidus Cloud Protocol — v1.0

> **Status**: Approved contract (all 8 catalog decision points accepted by the project owner)
> **Scope**: `solidus-analytics` Cloud Agent ⇄ Solidus Cloud Relay ⇄ PWA clients
> **Related**: the approved *Solidus Cloud Command Catalog v0.1* (75 commands/events × 8
> domains, 6 governing rules, 13 known gaps) is the feature source of truth; this
> document is the wire source of truth.
> **Version**: `1.0` — 2026-09-01

---

## Table of Contents

1. [Topology & Transport](#1-topology--transport)
2. [Governing Rules (normative)](#2-governing-rules-normative)
3. [Envelope](#3-envelope)
4. [Session Lifecycle](#4-session-lifecycle)
5. [Event Stream (agent → relay → PWA)](#5-event-stream-agent--relay--pwa)
6. [Command Channel (PWA → relay → agent)](#6-command-channel-pwa--relay--agent)
7. [Confirmation Contract](#7-confirmation-contract)
8. [Idempotency](#8-idempotency)
9. [Rate Ceilings](#9-rate-ceilings)
10. [Roles & Entitlement](#10-roles--entitlement)
11. [Security](#11-security)
12. [Audit](#12-audit)
13. [Alert Rules](#13-alert-rules)
14. [Versioning & Compatibility](#14-versioning--compatibility)
15. [Command Catalog Wire Map](#15-command-catalog-wire-map)
16. [Appendix A — Full Session Example](#16-appendix-a--full-session-example)
17. [Appendix B — v1 implementation matrix](#17-appendix-b--v1-implementation-matrix)

---

## 1. Topology & Transport

```
+---------------------------+        outbound WSS :443        +---------------------+
| solidus-analytics (Fabric)|  ─────────────────────────────► |  Solidus Cloud      |
|  Cloud Agent (in-process) |  ◄───────────────────────────── │  Relay (store&fwd,  |
|  - telemetry emitter      |          commands + acks        |  auth, audit, ent.) |
|  - command executor       |                                +----------┬----------+
|  - veto hook (Core)       |                                           │ WSS :443
+---------------------------+                                           ▼
                                                            +---------------------+
                                                            |  PWA (phone/desktop)|
                                                            |  + Web Push / Discord|
                                                            +---------------------+
```

- **One direction is opened, by the agent**: the agent dials out to the relay over
  `wss://<relay>/agent` on port 443. No inbound port is ever opened on the game server.
  This works behind NAT, firewalls, and restrictive hosts (443 egress is required by
  Mojang services anyway).
- Clients (PWA) dial the relay over `wss://<relay>/app`.
- All traffic is TLS 1.2+ (TLS 1.3 preferred). Plain `ws://` is rejected by the relay in
  production mode; allowed only behind a local reverse proxy (`relay.allowInsecure`).
- JSON text frames only. Binary frames are a protocol error. Per-message compression
  (`permessage-deflate`) may be enabled transparently at the WS layer — payloads are
  compression-friendly by design (short keys, delta batches, §5.3).
- **Fallback ladder** (documented in D4/architecture, not implemented in v1): WSS →
  HTTPS long-poll → HTTPS poll. The envelope in §3 is transport-agnostic so a fallback
  can carry the same messages in POST bodies.
- **View tier** (free, GitHub Pages) is NOT part of this protocol: it consumes the
  existing encrypted snapshot publishing pipeline already shipped in the mod. Only the
  Cloud tier speaks this protocol.

## 2. Governing Rules (normative)

These rules were approved with the catalog and are **binding on every implementation**:

| # | Rule | Enforcement point |
|---|------|-------------------|
| G1 | **Allow-list, never raw commands.** The agent only accepts the 75 catalog IDs; unknown IDs are rejected with `E_UNKNOWN_CMD`. There is no "free console" message type. The `Console` path renders a *pre-templated* vanilla command server-side; the wire never carries raw command text. | agent + relay |
| G2 | **Confirmation tied to risk class.** R: none · W1: single tap with preview · W2: typed target name + mandatory `reason` · D: typed name + reason + password re-entry + hold. See §7. | relay (primary), agent (reason re-check) |
| G3 | **Idempotency key mandatory on financial commands.** `econ.grant`, `econ.deduct`, `econ.transfer`, `econ.grant.all` carry `idemKey`; duplicates return the first result. See §8. | agent (48 h window, persistent) + relay (10 min) |
| G4 | **Rate ceilings per class.** See §9. A compromised account stays containable in time. | relay |
| G5 | **No privilege granting over the cloud, ever.** No command ID for op/deop/permission grants exists in the catalog; any such request is structurally impossible. Compensating control: `agent.security.change` alert on ops/whitelist/mod-list changes. | by construction |
| G6 | **IP addresses masked by default.** Player IPs travel and display as `1.2.3.*`. Full IP only via explicit reveal action that writes an audit row. | agent |

Additional fixed decisions (approved):

- No free-form command box in v1 (revisit after 1 year of operation).
- `econ.grant.all` stays W2 **with a mandatory aggregate amount cap per batch** (`args.cap`).
- `player.freeze` (movement anchor lock) ships in v1.
- Temp bans are **out of v1** (permanent ban + `/pardon` only) — needs a restart-proof scheduler.
- Chat moderation is **out of scope permanently** (not Solidus's core product).
- First-release UI language: English only.

## 3. Envelope

Every frame (either direction, both sockets) is a single JSON object:

```jsonc
{
  "sv": 1,                    // protocol major version this message targets
  "id": "m-8f2k3",            // unique message id (sender-generated, url-safe, <= 64 chars)
  "t": "evt" | "cmd" | "ack"  // kind: event | command | ack
  // ---- event frames (agent -> relay, relay -> client) ----
  "type": "health.tps",       // catalog event id (dots preserved)
  "seq": 1041,                // per-agent monotonic sequence (gap detection)
  "ts": 1725170100000,        // epoch ms, agent clock for events
  "d": { ... }                // event payload (see section 5)
  // ---- command frames (client -> relay -> agent) ----
  "cmd": "econ.grant",        // catalog command id
  "target": "Notch",          // primary target (player name / server id); required for W2/D
  "args": { ... },            // command payload (see section 15)
  "reason": "event refund",   // MANDATORY for W2/D (agent re-validates non-empty)
  "actor": { "uid": "u-01…", "name": "MOHD-Gs15", "role": "owner", "dev": "web-9a2" },
  "issuedAt": 1725170100000,  // client clock
  "expiresAt": 1725170160000, // issuedAt + ttl (default 60 s; D-class 90 s)
  "idemKey": "id-77…",        // required for financial commands (G3)
  "confirm": { ... }          // confirmation proof (section 7); relay-validated
}
```

- Field order is irrelevant; receivers must not rely on it.
- Unknown fields are ignored (forward compatibility), except inside `args` where strict
  validation applies (see §6.4).
- Numbers are IEEE-754 doubles on the wire; **money is carried in integer cents** under
  `…C`-suffixed fields (`amountC`, `balanceC`) to keep exactness across languages. One
  display convention (2 decimals, thousands separators) lives in the client only.
- All strings are UTF-8. Player names are matched **case-exactly** (no case folding) so
  the typed-name confirmation (G2) is unambiguous.

## 4. Session Lifecycle

### 4.1 Agent → relay (`/agent`)

1. Connect `wss://relay/agent`.
2. Agent sends `hello`:
   ```jsonc
   { "sv":1, "id":"m-h1", "t":"evt", "type":"hello",
     "serverId":"srv-a1b2c3", "secret":"<64-hex pairing secret>",
     "agent":"1.2.0", "mc":"26.1.2", "loader":"0.19.4", "java":"25",
     "solidusCore":"2.1.0",                    // or null if Core absent
     "governance": false,
     "modsHash":"sha256:9f…",                  // fingerprint of (modId@version) list
     "restartCapable": true,
     "caps": ["econ.grant","econ.freeze","player.kick", …]  // supported command ids
   }
   ```
3. Relay validates `serverId` + `secret` (SHA-256 compare against the paired record),
   entitlement (active subscription → command channel enabled; else events only), and
   replies `hello.ok` (with `sessionId`, `relayTs`, `protoMin`, `features`) or
   `hello.err` (`E_AUTH`, `E_UNKNOWN_SERVER`, `E_ENTITLEMENT`, `E_PROTO`).
   On `hello.err` the relay closes the socket; the agent backs off (§4.4).
4. Agent immediately sends the **state snapshot** batch: `agent.state` (veto flags,
   frozen players, restart capability), `health.meta`, `players.list`, then enters the
   periodic cadence (§5.3).
5. Heartbeat: agent sends `{"t":"evt","type":"hb","d":{}}` every 15 s. The relay marks
   the agent **offline** after 120 s of silence → fires `agent.heartbeat.lost` (catalog
   alert) and starts holding commands in queue (TTL-bounded, §6.6).

### 4.2 Client → relay (`/app`)

1. Client obtains a session token from `POST /api/login` (username+password; tokens
   expire 30 d and are revocable — `session.revoke`). Login is rate-limited per IP and
   per account name (5 failures / 60 s → 5 min lockout, §11).
2. Client exchanges the token for a **single-use WebSocket ticket**:
   `POST /api/ws-ticket` (Bearer) → `{ ticket, expiresAt }`. Tickets live 30 s.
3. Connect `wss://relay/app?ticket=<ticket>` — the ticket is consumed by the upgrade;
   it cannot be replayed, and long-lived tokens never appear in URLs (proxy/media log
   leakage). Passing `?token=` is rejected outright (close 4001).
4. Relay replies `auth.ok{ user, role, servers:[{serverId,name,online,entitled}] }` and
   floods the ring-buffer tail for the user's currently selected server (≤ 200 events,
   §6.7) so the UI is warm instantly.
5. Client switches servers with `select{serverId}`; relay re-points the event feed.
6. On reconnect, the client fetches a fresh ticket first (same flow).

### 4.3 Ordering

- Events reach the client in **agent seq order** per server. The relay buffers and
  orders by `seq`; a gap > 1 triggers a `resync` event telling the client to refetch
  the full snapshot (it never silently loses data).
- Commands and events share the socket; commands are never reordered relative to their
  own `ack`/result (matched by `id`).

### 4.4 Reconnect & backoff (agent side)

- Exponential: 1, 2, 4, 8, 15, 30, 60 s (cap), ± 20 % jitter.
- While disconnected the agent buffers up to **2 000 events** (drops oldest, keeps a
  `dropped` counter surfaced in the next `health.meta`) and flushes on reconnect after
  `hello.ok`. Telemetry cadences (§5.3) resume from the current state — no replay of
  periodic data older than 5 min (it is stale by definition).
- Persisted state (§5.4 veto flags) is re-announced in `agent.state` on every reconnect,
  so the UI truth survives restarts on both ends.

## 5. Event Stream (agent → relay → PWA)

### 5.1 Common payload conventions

- `…C` fields = integer cents. `pct` fields = 0–100 double. `tps` = 0–20 double.
- Player references: `{ "n": "Notch", "uuid": "…", "ip": "91.198.44.*" }` — IP masked (G6).
- Missing/unavailable readings are `null`, never omitted, so schemas stay stable.
- Every event carries `seq` (per-agent, resets on agent restart, announced via `hello`).

### 5.2 Event payloads (by catalog id)

| id | cadence | payload `d` |
|----|---------|-------------|
| `health.tps` | 10 s | `{ tps1: 19.8, tps5: 19.9, tps15: 20.0, msptAvg: 8.1, msptP95: 41.2, spikes: 0 }` — `spikes` = ticks > 75 ms in window |
| `health.ram` | 10 s | `{ heapUsedB: 3120, heapMaxB: 8192, heapCommittedB: 4096, nonHeapB: 210, gc: { "G1 Young": { count: 12, ms: 41 } } }` |
| `health.cpu` | 10 s | `{ procPct: 63.0, sysPct: 71.0, load1: 1.84 }` |
| `health.disk` | 60 s | `{ worldsB: 52884, logsB: 912, economyDbB: 143, auctionsDbB: 57, analyticsDbB: 96, walB: 2, freeB: 22010 }` |
| `health.world` | 60 s | `{ levels: [ { name:"minecraft:overworld", chunks: 8421, entities: 1042, diskB: 28112 } ] }` |
| `health.entities` | 60 s | `{ top: [ { type:"minecraft:item", count: 611 }, … ] }` |
| `health.meta` | on hello + hourly + on change | `{ agent:"1.2.0", mc:"26.1.2", loader:"0.19.4", java:"25", core:"2.1.0", governance:false, uptimeS: 3600, modsHash:"sha256:…", droppedEvts: 0, playersMax: 40 }` |
| `health.lag_spike` | on trigger | `{ run: 4, ms: 812, tps1: 14.1, at: 1725170100000 }` — ≥ 3 consecutive ticks > 75 ms |
| `players.list` | 10 s full snapshot (join/leave are separate instant events) | `{ full:true, max:40, players:[ { n, uuid, ping: 41, level:"overworld", mode:"survival", sessS: 1800, balC: null\|int } ] }` — `balC` present only when Core API available |
| `player.join` | event | `{ n, uuid, ip, first: false }` |
| `player.leave` | event | `{ n, uuid, sessS: 1800 }` |
| `econ.top` | 60 s | `{ entries:[ { n, uuid, balC } ] }` (top 10) |
| `econ.supply` | 60 s | `{ supplyC: 12000000, delta24hC: -40000, players: 512 }` |
| `econ.flow` | 60 s | `{ dayVolC: 81200, dayCount: 142, byType: { "PAY_TRANSFER": 61, "SHOP_BUY": 44, … }, activePlayers: 88 }` — from `LiveMetricsTracker` |
| `econ.distribution` | 60 s | `{ gini: 0.71, top1Pct: 44.2, medianC: 940, source:"snapshot" }` — latest `analytics_snapshots` row |
| `econ.inflation` | 300 s | `{ rate: 2.1, band:"STABLE" }` — from `InflationCalculator` output |
| `econ.notifications` | 60 s | `{ pending: 3 }` |
| `market.auctions.active` | 60 s | `{ count: 12, totalValueC: 45000, listings:[ { id, seller, material, qty, priceC, endsInS } ] }` (top 10 by price) |
| `market.auctions.sold` | 300 s | `{ recent:[ { id, seller, buyer, material, qty, priceC, at } ], avgPriceC: … }` (last 20) |
| `market.shop.volume` | 300 s | `{ topBought:[ [ "dirt", 210 ] ], topSold:[ … ] }` — from `LiveMetricsTracker` |
| `market.price.trend` | on demand (query) | result of `market.price.trend` query — §15 |
| `territory.stats` | 300 s | `{ claims: null }` — null when territory mod absent (capability-gated) |
| `agent.state` | on change + reconnect + 60 s | `{ pause:{ active, reason, by, at }, auctionsPaused:{…}, shopPaused:{…}, frozen:[ { n, uuid, by, at, reason } ], cap: 40 }` |
| `agent.security.change` | event | `{ what:"mods\|ops\|whitelist", digest:"sha256:…", detail:"added mod fabric-proxy@1.2" }` |
| `cmd.audit` | event | mirror of every command outcome (§12 fields) — live feed to PWA |
| `hb` | 15 s | `{}` |

Readings not yet produced by the v1 agent are simply **not sent**; the client renders
them as *unavailable* based on `caps`. The catalog ids `market.price.trend`,
`econ.tx.search`, `player.profile`, `player.inspect` are **query commands** (§6), not
pushed streams, because their payloads are parameterized.

### 5.3 Cadence summary

| class | interval | notes |
|-------|----------|-------|
| fast health (tps/ram/cpu) + players.list | 10 s | catalog "live deltas every ten seconds" |
| economy & market readings | 60 s | |
| slow readings (inflation, sold, territory, disk, world, entities) | 60–300 s | |
| on-change events (join/leave/spike/security/state) | immediate | |

### 5.4 Veto-state persistence

`econ.pause.global`, `market.auction.pause`, `market.shop.pause`, `econ.freeze` states
are **durable**: written to the agent's own `cloud.db` (`cloud_state` table — a file
deliberately separate from `analytics.db` and Core's databases, so the agent never
writes into a database it does not own) and re-applied at agent boot *before* the
hook accepts any
transaction. A server that restarts while frozen comes back frozen. This makes the
UI's truth (relay + PWA) converge to the server's actual state on every `hello`.

## 6. Command Channel (PWA → relay → agent)

### 6.1 Flow

```
PWA                relay                       agent
 │  cmd (W1)        │                            │
 │ ───────────────► │ validate schema, role,     │
 │                  │ rate, confirm, idem        │
 │                  │ ──────── cmd ────────────► │ ack (<= 2 s)
 │                  │ ◄────── ack ────────────── │
 │                  │                            │ execute (server thread)
 │                  │ ◄──── result ───────────── │ result {status: applied}
 │ ◄─────────────── │ forward + audit            │
```

1. Client submits the command frame (§3) to the relay.
2. Relay validates: schema (`args`), role (`minRole`), entitlement, rate ceiling (§9),
   confirmation proof (§7), idempotency cache (10 min), TTL (`expiresAt`).
   Invalid → immediate `result{status:"rejected", code:…}`; **never forwarded**.
3. If the agent is offline: the command is queued (§6.6), status `queued`, and the client
   sees it as pending.
4. Agent receives → replies `ack{id}` within 2 s → executes on the **server thread**
   (via `MinecraftServer.execute`) → replies `result{id, status}`.
5. Result statuses: `applied` (+ `data`), `rejected` (+ `code`, agent-side validation),
   `failed` (+ `error` message). Lifecycle closes.
6. If no result within 120 s of `ack` (or of queueing while online), the relay marks the
   command `timeout` and audits it as unresolved — the UI shows "unknown outcome",
   which for financial commands is resolved by `idemKey` replay (§8).

### 6.2 Command frame (relay → agent)

Identical to §3 plus relay bookkeeping: `rid` (relay command id), `queuedAt` (null when
forwarded immediately). The agent validates **again** (defense in depth): known ID,
`args` schema, `reason` non-empty for W2/D, `idemKey` present for financial, `expiresAt`
in the future. Rejections use the same error-code table (§6.5).

### 6.3 Agent-side execution paths

Exactly the six catalog paths — the agent's `CommandSpec` registry binds each ID to one:

- **API** — reflection bridge (`SolidusIntegration`), offline variants preferred so
  commands work whether the player is online or not.
- **Hook** — `CloudVetoHook` registered into `SolidusAPI.registerTransactionHook` as a
  reflection proxy named `"solidus-cloud-agent"` (fail-open per Core contract).
- **Console** — pre-templated vanilla commands executed with the server's command source
  at permission level 4. **The command string is assembled server-side from validated
  `args` (names are matched against the known-player list first) — the wire never
  carries command text.** (G1)
- **Fabric** — direct server/player objects (tick events, JMX, level stats).
- **Core+** — not executable in v1 → capability absent → relay answers
  `E_CORE_MISSING` without forwarding.
- **Agent** — local features (backups, idempotency store, pairing rotation, anchor freeze).

### 6.4 `args` validation rules (all commands)

- Unknown keys in `args` → `E_ARGS`.
- Types must match §15 exactly (`string`, `int`, `bool`, `enum`, `array`).
- Strings: trimmed; length caps: player name ≤ 16, reason ≤ 256, message ≤ 512.
- Ints: explicit min/max in §15 (e.g. `amountC > 0`, `amountC ≤ 100_000_00`).
- Enums: closed sets; anything else → `E_ARGS`.
- Player-name targets: the agent resolves `target` against `player_balances` (known
  players) and the online list. Unknown name → `E_NO_SUCH_PLAYER` (this also blocks
  name-probing through the command channel).

### 6.5 Error codes

| code | meaning | stage |
|------|---------|-------|
| `E_PROTO` | bad envelope / unsupported `sv` | both |
| `E_AUTH` | bad credentials (secret, token) | relay |
| `E_UNKNOWN_SERVER` | serverId not paired | relay |
| `E_UNKNOWN_CMD` | command id not in the 75-card allow-list | relay + agent |
| `E_ENTITLEMENT` | subscription inactive — command channel closed | relay |
| `E_ROLE` | role below `minRole` for this command | relay |
| `E_ARGS` | schema/type/range violation | relay + agent |
| `E_CONFIRM_MISSING` | confirmation proof absent for W2/D | relay |
| `E_CONFIRM_MISMATCH` | typed name ≠ target, or wrong/expired token | relay |
| `E_HOLD` | destructive command submitted before hold elapsed | relay |
| `E_RATE` | rate ceiling exceeded (retry-after in `data`) | relay |
| `E_IDEM_DUP` | duplicate idemKey (surfaces the original result) | relay + agent |
| `E_EXPIRED` | `expiresAt` passed | relay + agent |
| `E_NO_SUCH_PLAYER` | target unknown to the server | agent |
| `E_CORE_MISSING` | required Core/Governance capability absent | relay (caps) or agent |
| `E_EXEC` | execution threw — `error` carries detail | agent |
| `E_STATE` | command invalid in current state (e.g. resume when not paused) | agent |

### 6.6 Store-and-forward while agent offline

- Commands received while the agent is offline are queued **only if** `expiresAt − now ≥
  30 s`; the queue is flushed in order on reconnect; anything expired by then returns
  `E_EXPIRED`. Queue cap: 64 per server (excess → `E_RATE`).
- Events are ring-buffered per server (last **200**), replayed to clients on
  connect/resync (§4.3). Buffer overflow discards oldest and bumps `droppedEvts`.

### 6.7 Command results to other clients

Every terminal status is also broadcast to all *other* authenticated clients of the
same server as a `cmd.audit` event (§12) — the second admin's phone sees the first
admin's action within a second.

## 7. Confirmation Contract

Risk classes are the catalog's R / W1 / W2 / D. The relay enforces; the agent re-checks
`reason` presence for W2/D (it cannot see the typed-name UI).

| class | UI affordance | wire proof |
|-------|---------------|------------|
| R | none | — |
| W1 | single tap, preview panel | none |
| W2 | modal shows target + effect + current & projected values (balance preview for money) | `confirm.typed` must equal `target` exactly; `reason` non-empty |
| D | modal + password re-entry + 30 s hold with countdown | `prepare` → relay issues `confirmToken` (bound to user+cmd+target hash, 120 s validity) → submit carries `confirm.{token, password}`; relay enforces `now ≥ preparedAt + hold` |

- The relay verifies the password (argon2id/bcrypt hash, same as login) — **not** a
  cache of it.
- `confirmToken` is single-use; replay → `E_CONFIRM_MISMATCH`.
- D-class commands: `server.stop`, `server.restart`, `econ.pause.global`,
  `gov.freeze.global`, `gov.rollback.window`, `econ.rollback.tx`.
- The 30 s hold (`relay.destructiveHoldMs`, default 30 000) is enforced server-side
  between `prepare` and submit; the UI mirrors it with a countdown button.

## 8. Idempotency

- Financial commands (`econ.grant`, `econ.deduct`, `econ.transfer`, `econ.grant.all`)
  **must** carry `idemKey` (client-generated, unique per logical operation, UUID-ish).
- Relay: per-user × per-command cache, 10 min — duplicate submissions return the
  original outcome with `duplicate:true` instead of forwarding.
- Agent: persistent table `cloud_idempotency(idemKey TEXT PRIMARY KEY, cmd, ts,
  status, resultJson)` retained 48 h and vacuumed periodically; survives agent/server
  restarts. Duplicate → re-emit stored result (marked `duplicate:true`), **never
  re-executes**.
- Non-financial commands may omit `idemKey`; retries are naturally safe (kick twice is
  idempotent-ish, pause is a state set).

## 9. Rate Ceilings

Per user, per server, enforced at the relay (G4):

| class | ceiling |
|-------|---------|
| financial (W2 money) | 10 / min |
| other W2 | 20 / min |
| W1 | 30 / min |
| R queries | 120 / min |
| D | 1 concurrent pending + 3 / hour |
| `server.broadcast` | 6 / min (anti-spam) |

Excess → `result{status:"rejected", code:"E_RATE", data:{retryAfterMs}}`. Ceilings are
relay-configurable (`relay.limits.*`) for testing.

## 10. Roles & Entitlement

| role | powers |
|------|--------|
| `viewer` | all R events + queries; no commands |
| `mod` | viewer + W1 commands whose `minRole` = mod (kick, msg, broadcast, tp, gamemode, heal/feed, save) |
| `admin` | viewer + mod + all W2 (money, freeze, bans, market pauses, alerts) |
| `owner` | everything incl. D-class, `pairing.rotate`, `session.*`, `alert.rule.manage` |

- Entitlement: `servers.userId → subscription {status, renewsAt}`. Inactive → events
  continue for 14 days (read-only grace), commands closed (`E_ENTITLEMENT`), then the
  server record is archived (not deleted) per the approved tier table.
- First user in a fresh relay install is `owner`. Additional users are created by the
  owner (`user.add` is a **relay-side** management API, not a catalog command — it never
  reaches the agent).
- Sessions: bearer tokens (30 d), listed and revocable (`session.list`,
  `session.revoke` — catalog audit domain).

## 11. Security

- **Pairing secret**: 64 hex chars, generated by the agent on first boot, stored in
  `config/solidus-analytics/cloud.properties` (never inside the JAR — approved
  decision). The relay stores only `SHA-256(secret)`. Rotation (`pairing.rotate`,
  W2/owner): agent generates a new secret, writes it to the config file, re-hellos with
  it; the old secret dies instantly. Recommended cadence: 90 days + 1-week warning.
- **Agent identity**: `serverId` (8 chars, agent-generated, stable across restarts) +
  `modsHash` fingerprint announced in `hello` and every `health.meta`; changes fire
  `agent.security.change` (G5 compensating control, also covers ops/whitelist digest
  changes where readable).
- **Transport**: TLS only (§1). The agent pins the relay certificate's SHA-256
  (`cloud.pinSha256` optional) — cheap pinning against MITM on shared hosts.
- **Clock**: commands carry client `issuedAt`; the relay rejects commands whose
  `issuedAt` skews > 300 s from relay time (`E_ARGS`) to blunt replay via `expiresAt`
  forgery. (HMAC per-message signing is a v1.1 roadmap item; TLS + TTL + idempotency +
  nonces bound the v1 threat model.)
- **IP hygiene** (G6): masked in every event; reveal = `player.inspect` with
  `reveal:true` (admin+) → audit row `ip.reveal` with full address, target, actor.
- **No privilege grants** (G5): no such command id exists; `agent.security.change`
  alerts on ops/whitelist/mod-list changes.
- **HTTP edge hardening** (audit P0): every relay HTTP response carries
  `Content-Security-Policy` (default-src 'self'; connect-src allows ws/wss only for
  the serving host), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`, `Cross-Origin-Resource-Policy: same-origin`; API
  responses add `Cache-Control: no-store`.
- **Login throttling** (audit P0): `POST /api/login` is rate-limited per source IP AND
  per account name — 5 failed attempts inside a sliding 60 s window lock both keys for
  5 minutes. The lockout check runs BEFORE the scrypt derivation (cheap rejection —
  request floods cannot pin the relay CPU), successful login clears the counters, and
  lockouts are audited as `auth` rows with code `E_LOCKOUT`. State is in-memory by
  design: a relay restart gives a clean slate (never a permanently locked-out owner).
- **WebSocket tickets** (audit P1): long-lived session tokens never travel in URLs.
  `POST /api/ws-ticket` (Bearer) mints a 30-second single-use ticket that authenticates
  the `/app` upgrade; the underlying session is re-validated at consumption (expiry and
  revocation still apply).

## 12. Audit

- **Relay is the authoritative ledger.** Every command — accepted or rejected — is
  appended to an append-only store with: `rid, cmd, target, args, reason, actor{id,name,
  role,dev}, receivedAt, status, code, resultData, expiresAt, idemKey, serverId`.
  Rows are immutable from the API; `audit.query` reads, `audit.export` dumps (CSV/JSON).
  Retention ≥ 90 days.
- **Query cost model** (audit P1): `audit.query` scans the ledger BACKWARD from the
  file tail in 64 KiB chunks (newest-first, early-stop once rows are older than
  `fromMs`) instead of loading the whole file; response time stays flat as the ledger
  grows to hundreds of thousands of rows. Caps: `audit.query` ≤ 200 rows,
  `audit.export` ≤ 2000 rows.
- **Agent mirror**: terminal outcomes are written to the agent's own `cloud.db`
  (`cloud_command_log`) so the server owner holds a local, tamper-resistant copy
  even if the relay is wiped.
- **Live feed**: every terminal status is broadcast to other clients as `cmd.audit`
  events (§6.7).
- Resolution closure: a command with no terminal status 120 s after `ack` (or after
  queueing while online) is auto-marked `timeout` and surfaced as *outcome unknown*
  (§6.1 step 6).

## 13. Alert Rules

- Rules live on the relay (catalog alerts domain): `{ metric, op, threshold, forMs,
  channels:["push","discord"], silenceMin, enabled }`.
- Metric-gated rules evaluate on the live event stream (e.g. `health.tps.tps1 < 15
  for 300 000 ms`); absence rules evaluate on the heartbeat (`agent.heartbeat.lost`,
  120 s — built-in, non-deletable).
- Delivery: Web Push (VAPID) to subscribed PWA devices + optional Discord bot DM.
- Built-in defaults (owner can disable all but heartbeat): heartbeat lost, TPS < 15
  for 5 min, RAM > 90 % for 5 min, `agent.security.change`, W2/D command executed.
- `alert.silence` sets a maintenance window (suppression, not deletion).
- Threshold noise control: `forMs` (sustain) + `silenceMin` (cooldown) exactly as the
  catalog specifies.

## 14. Versioning & Compatibility

- `sv` (envelope) = 1. Bump on breaking envelope changes. Unknown `sv` → `E_PROTO`.
- Feature negotiation: the agent's `caps` array (from `hello`) is the set of command ids
  it can execute this boot; the relay/UI render only those (plus relay-side commands
  which are always present). A v1.1 agent adding commands is read correctly by a v1
  relay because unknown ids simply never appear in an old UI.
- Command schemas may gain **optional** fields only (`E_ARGS` treats unknown keys as
  errors, so additions require a `sv` bump or a capability flag — prefer capability
  flags, e.g. `caps:["econ.grant.v2"]`).
- `relay.protoMin` in `hello.ok` tells the agent the minimum protocol the relay
  supports; agents below it log a loud warning and continue in read-only mode.

## 15. Command Catalog Wire Map

`minRole`: V=viewer, M=mod, A=admin, O=owner. `args` types: s=string, i=int, b=bool,
e=enum(values), a=array. All money as `…C` integer cents. `reason` required (✓) for
W2/D per G2 (validated on both ends).

### health (queries only — data arrives via events)

| id | risk | minRole | args | result `data` |
|----|------|---------|------|----------------|
| `health.tps` | R | V | `{}` | same payload as event |
| `health.ram` / `health.cpu` / `health.disk` / `health.world` / `health.entities` / `health.meta` | R | V | `{}` | same payload as event |

### players

| id | risk | minRole | args | notes |
|----|------|---------|------|-------|
| `players.list` | R | V | `{}` | full snapshot (event `d`) |
| `player.profile` | R | V | `{ target }` | `{ balC, lastSeen, tx:[…20], sessS, frozen:bool }` |
| `player.inspect` | R | A | `{ target, reveal:b }` | `{ pos, level, mode, health, food, inv:[…], ip }` — `reveal:true` unmasks + audits |
| `player.kick` | W1 | M | `{ target }` + reason | Console: `/kick <t> <reason>` |
| `player.ban` | W2 | A | `{ target }` + reason | `/ban <t> <reason>` — name must exist in known list |
| `player.ban.ip` | W2 | A | `{ target }` + reason | `/ban-ip <t> <reason>`; IP resolved server-side, never accepted from wire |
| `player.unban` | W2 | A | `{ target }` | `/pardon <t>` |
| `player.freeze` | W2 | A | `{ target, anchor:e(current\|spawn) }` + reason | movement anchor lock, 2-block radius |
| `player.unfreeze` | W2 | A | `{ target }` | |
| `player.tp` | W1 | M | `{ target, to:{ kind:e(spawn\|coords\|player), x?,y?,z?, player? } }` | |
| `player.gamemode` | W1 | M | `{ target, mode:e(survival\|creative\|adventure\|spectator) }` | |
| `player.heal` | W1 | M | `{ target }` | |
| `player.feed` | W1 | M | `{ target }` | |
| `player.give` | W2 | A | `{ target, item:s, qty:i 1..64 }` + reason | economic warning in UI (creates value) |
| `player.msg` | W1 | M | `{ target, message:s }` | |
| `server.broadcast` | W1 | M | `{ message:s, tier:e(all\|ops) }` | |
| `whitelist.manage` | W2 | A | `{ action:e(add\|remove\|on\|off), target? }` | |
| `player.ban.temp` | — | — | *deferred by approved decision 5* (needs restart-proof scheduler) | capability absent in v1 |
| `player.join`/`player.leave` | R | — | *events only* | |

### econ

| id | risk | minRole | args | notes |
|----|------|---------|------|-------|
| `econ.top` | R | V | `{ limit i 1..50 }` | API `getTopBalances` |
| `econ.supply` | R | V | `{}` | DB read-only `SUM(balance)` |
| `econ.tx.search` | R | V | `{ type?e, player?s, material?s, minC?i, maxC?i, sinceMs?i, limit i 1..100 }` | read-only query on `transaction_log` |
| `econ.distribution` / `econ.inflation` / `econ.flow` / `econ.notifications` | R | V | `{}` | latest computed values |
| `econ.grant` | W2 | A | `{ target, amountC i 1..10000000 }` + reason + idemKey | offline-capable via `addBalanceOffline` |
| `econ.deduct` | W2 | A | `{ target, amountC i 1..10000000 }` + reason + idemKey | |
| `econ.transfer` | W2 | A | `{ target(from), to s, amountC i 1..10000000 }` + reason + idemKey | `transferOffline` |
| `econ.grant.all` | W2 | A | `{ scope:e(online\|known), amountC, capC i 1..100000000 }` + reason + idemKey | aggregate cap `capC` **mandatory** (approved decision 3) |
| `econ.freeze` | W2 | A | `{ target }` + reason | veto hook denies that player's money movement |
| `econ.unfreeze` | W2 | A | `{ target }` | |
| `econ.pause.global` | D | O | `{}` + reason | circuit breaker, durable (§5.4) |
| `econ.resume.global` | W2 | A | `{}` + reason | resume — `E_STATE` when not paused |
| `econ.rollback.tx` | D | O | `{ txId i }` + reason | **Core+ gap** → `E_CORE_MISSING` in v1 |

### market

| id | risk | minRole | args | notes |
|----|------|---------|------|-------|
| `market.auctions.active` / `market.auctions.sold` / `market.shop.volume` / `market.price.trend` | R | V | `market.price.trend`: `{ material s, points i 10..500 }` | trend: per-material price series from `auctions.db`/`transaction_log` |
| `territory.stats` | R | V | `{}` | null payload when territory mod absent |
| `market.auction.pause` | W2 | A | `{}` + reason | hook vetoes listing + purchase |
| `market.shop.pause` | W2 | A | `{}` + reason | hook vetoes purchase + sell |
| `market.auction.resume` / `market.shop.resume` | W2 | A | `{}` | `E_STATE` when not paused |
| `market.item.cap` | W2 | A | `{ material s, priceC i }` + reason | **hook signature gap**: `allowAuctionListing` receives no material → v1 answers `E_CORE_MISSING` pending Core 2.2 hook enrichment (documented gap) |
| `market.item.ban` | W2 | A | `{ material s }` + reason | same gap as cap |
| `market.auction.cancel` / `market.auctions.cancel.bulk` / `market.shop.price.set` | W2 | A | … | **Core+ gaps** → `E_CORE_MISSING` |

### lifecycle

| id | risk | minRole | args | notes |
|----|------|---------|------|-------|
| `server.save` | W1 | M | `{}` | save-all; **auto-run before every D-class command** |
| `server.broadcast.restart` | W1 | M | `{ delayS i 30..300 }` | countdown broadcast |
| `server.restart` | D | O | `{}` + reason | save → broadcast → stop; requires `restartCapable` (advertised in `hello`; button hidden otherwise) |
| `server.stop` | D | O | `{}` + reason | save → stop |
| `server.backup.local` | W1 | A | `{ worlds:b=true, dbs:b=true }` | timestamped folder under `backups/` |
| `server.backup.list` | R | A | `{}` | `[{name, bytes, at}]` |
| `server.backup.prune` | W1 | A | `{ keepDays i 1..365 }` | |
| `solidus.reload` | W1 | A | `{}` | **Core+ gap** → `E_CORE_MISSING` |

### governance (capability-gated on Governance mod presence)

| id | risk | minRole | args | notes |
|----|------|---------|------|-------|
| `gov.tax.run` | W2 | A | `{}` + reason | v1: `E_CORE_MISSING` (Governance bridge not specified yet) |
| `gov.tax.config` | W2 | A | `{ … }` | same |
| `gov.freeze.global` | D | O | `{}` + reason | **unified circuit breaker** — same state as `econ.pause.global` (approved: one truth), one audit row |
| `gov.rollback.window` | D | O | `{ sinceMs i, untilMs i }` + reason | v1: `E_CORE_MISSING` |

### alerts & audit (relay-side — never forwarded to agent)

| id | risk | minRole | args | notes |
|----|------|---------|------|-------|
| `alert.rule.manage` | W1 | O | `{ action:e(create\|update\|delete), rule? }` | rule = §13 shape |
| `alert.rule.templates` | W1 | O | `{ template:e(tps\|ram\|fraud\|monopoly) }` | installs preset |
| `alert.silence` | W1 | O | `{ minutes i 5..1440 }` | maintenance window |
| `alert.channel.test` | W1 | O | `{ channel:e(push\|discord) }` | |
| `agent.heartbeat.lost` | — | — | *relay-generated alert event* | |
| `audit.query` | R | A | `{ fromMs?i, toMs?i, actor?s, cmd?s, target?s, limit i 1..200 }` | |
| `audit.export` | R | O | `{ …as query, format:e(json\|csv) }` | |
| `session.list` | R | O | `{}` | `[{dev, lastSeen, expiresAt}]` |
| `session.revoke` | W1 | O | `{ dev s }` | |
| `pairing.rotate` | W2 | O | `{}` | agent-side (writes new secret, re-hello) |

## 16. Appendix A — Full Session Example

```jsonc
// 1) agent connects
→ {"sv":1,"id":"m-h1","t":"evt","type":"hello","serverId":"srv-a1b2c3","secret":"9f86…","agent":"1.2.0","mc":"26.1.2","restartCapable":true,"caps":["econ.grant","econ.freeze","player.kick"]}
← {"sv":1,"id":"m-r1","t":"evt","type":"hello.ok","d":{"sessionId":"s-7","relayTs":1725170090000,"protoMin":1}}

// 2) telemetry flows (10 s cadence, seq monotonic)
→ {"sv":1,"id":"m-e9","t":"evt","seq":9,"ts":1725170100000,"type":"health.tps","d":{"tps1":19.8,"tps5":19.9,"tps15":20.0,"msptAvg":8.1,"msptP95":41.2,"spikes":0}}

// 3) owner grants 250.00 S$ (W2 → typed name + reason; financial → idemKey)
← {"sv":1,"id":"m-c42","t":"cmd","cmd":"econ.grant","target":"Notch","args":{"amountC":25000},"reason":"event prize",
   "actor":{"uid":"u-01","name":"MOHD-Gs15","role":"owner","dev":"web-9a2"},"issuedAt":1725170123000,"expiresAt":1725170183000,
   "idemKey":"id-77ab","confirm":{"typed":"Notch"}}
→ {"sv":1,"id":"m-c42","t":"ack","ok":true}
→ {"sv":1,"id":"m-c42","t":"evt","type":"cmd.result","d":{"rid":"r-551","cmd":"econ.grant","target":"Notch","status":"applied","data":{"balanceC":125000},"tookMs":34}}

// 4) duplicate submission after a network retry → no double credit
← {…same idemKey "id-77ab"…}
→ {"sv":1,"id":"m-c43","t":"evt","type":"cmd.result","d":{"rid":"r-552","cmd":"econ.grant","target":"Notch","status":"rejected","code":"E_IDEM_DUP","data":{"originalRid":"r-551"}}}

// 5) circuit breaker (D → prepare/confirm token + password + hold)
← {"sv":1,"id":"m-p9","t":"cmd","cmd":"prepare","cmdTarget":"econ.pause.global"}
→ {"sv":1,"id":"m-p9","t":"evt","type":"prepare.ok","d":{"token":"cf-31aa","validUntil":1725170200000}}
   // …30 s countdown in UI…
← {"sv":1,"id":"m-c51","t":"cmd","cmd":"econ.pause.global","target":"","args":{},"reason":"exploit containment",
   "actor":{…},"confirm":{"token":"cf-31aa","password":"••••"},…}
→ ack → result {"status":"applied"} + agent.state event + cmd.audit broadcast
```

## 17. Appendix B — v1 implementation matrix

Implemented by the v1 agent (this repo, `com.solidus.analytics.cloud`):

- Transport/session: WSS client with backoff, heartbeat, buffer, hello/caps (§4)
- Events: all health.* (incl. lag_spike, meta+modsHash), players.list/join/leave,
  econ.top/supply/flow/distribution/notifications, market.auctions.active/sold,
  market.shop.volume, agent.state, cmd.audit, hb
- Commands: econ.grant/deduct/transfer/grant.all, econ.freeze/unfreeze,
  econ.pause.global/resume, market.auction.pause/resume, market.shop.pause/resume,
  player.kick/ban/unban/ban.ip/freeze/unfreeze/tp/gamemode/heal/feed/give/msg/inspect/profile,
  server.broadcast/save/stop/restart/backup.*, whitelist.manage, pairing.rotate,
  queries (health.*, econ.*, players.list, market.price.trend, econ.tx.search)

Deferred with `E_CORE_MISSING` (matches the catalog's 13-row gap map + 2 discovered
signature gaps):

- Core 2.2: auction cancel (single/bulk), live shop price set, settings reload hook,
  veto/freeze state query API, hook enrichment for item-aware veto
  (`market.item.cap`, `market.item.ban`), rollback (tx + window)
- Governance bridge: `gov.*` execution
- Agent roadmap: deferred command scheduler (temp bans, scheduled grants),
  per-message HMAC signing
- Relay roadmap: HTTPS long-poll fallback, Discord DM bot (webhook notifier exists
  mod-side), Stripe-backed entitlement (v1 ships manual entitlement flag)
