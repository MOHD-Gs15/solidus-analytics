# Solidus Cloud Relay

The cloud half of the Solidus Cloud tier (see `../docs/cloud/PROTOCOL.md`):
a small Node.js service that sits between the in-game **Cloud Agent**
(shipped inside `solidus-analytics`) and the **PWA**.

Responsibilities (v1):

- **Agent endpoint** `wss://…/agent` — the mod dials *out* to this; pairing is
  `serverId + 64-hex secret` (the relay stores only `sha256(secret)`).
- **Client endpoint** `wss://…/app` + PWA static hosting at `/`.
- **HTTP edge hardening** — CSP (same-origin + host-bound ws/wss),
  `X-Frame-Options: DENY`, `nosniff`, `no-referrer`, CORP; API responses are
  `no-store`.
- **Login throttling** — 5 failed logins per IP *and* per account name in 60 s
  locks both for 5 minutes (checked BEFORE scrypt; audited as `E_LOCKOUT`).
- **WebSocket tickets** — `POST /api/ws-ticket` (Bearer) mints a 30 s
  single-use ticket that authenticates the `/app` upgrade; long-lived tokens
  never ride URLs.
- **Store & forward** — 200-event ring buffer per server; offline command queue
  bounded by command TTL (60 s / 90 s for D-class).
- **Allow-list + roles + entitlement** — every command id in the catalog has a
  risk class and a minimum role; inactive subscription closes the command
  channel (`E_ENTITLEMENT`).
- **Risk-tiered confirmations** — W2 requires `confirm.typed == target` + a
  reason; D-class requires `prepare` → single-use `confirmToken` + password
  re-entry + server-enforced 30 s hold.
- **Idempotency** — 10-minute relay cache for financial commands (the agent
  additionally keeps a 48 h persistent window in `cloud.db`).
- **Rate ceilings** — grants ≤ 10/min, W1 ≤ 30/min, D ≤ 3/h, etc.
- **Authoritative audit ledger** — append-only `data/audit.jsonl`, queryable
  from the PWA with a backward tail scan (flat response time as the ledger
  grows; ≤ 200 rows per query, ≤ 2000 per export), retained 90 days.
- **Alerts** — rule engine on the live event stream (TPS/RAM/CPU), built-in
  `agent.heartbeat.lost` after 120 s of silence, Web Push delivery when VAPID
  keys are configured.

## Run (dev)

```bash
cd cloud-relay
npm install                 # ws (+ optional web-push)
npm run user   -- --name owner --password 'strong-pass'   # first user = owner
npm start                  # ws://localhost:8787 (set RELAY_ALLOW_INSECURE=true for local dev)
```

Pair a server (run once — or use the PWA "Pair server" form):

```bash
npm run pair -- --user owner --serverId srv-a1b2c3 --secret <64-hex from the server console> --name "My Server"
```

Then in the mod's config (`config/solidus-analytics/cloud.properties`):

```properties
cloud.enabled=true
cloud.relayUrl=ws://localhost:8787/agent     # wss://… in production
```

The agent logs `serverId` + `pairingSecret` on first boot.

## Production

- Terminate TLS in front (Caddy/nginx) and drop `RELAY_ALLOW_INSECURE`;
  agents and clients connect over `wss://` on 443 only.
- Set `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` (generate with `npm run keys`)
  for Web Push.
- Point `RELAY_DATA_DIR` at persistent storage.
- Run under systemd/PM2; the process is stateless apart from `data/`.

## Environment

| var | default | meaning |
|-----|---------|---------|
| `RELAY_PORT` | 8787 | listen port |
| `RELAY_HOST` | 0.0.0.0 | bind host |
| `RELAY_DATA_DIR` | `./data` | users/servers/alerts/audit storage |
| `RELAY_ALLOW_INSECURE` | false | permit plain `ws://` (dev only) |
| `RELAY_DESTRUCTIVE_HOLD_MS` | 30000 | D-class prepare→submit hold |
| `RELAY_FIN_PER_MIN` / `RELAY_W2_PER_MIN` / `RELAY_W1_PER_MIN` / `RELAY_R_PER_MIN` | 10/20/30/120 | rate ceilings |
| `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` / `VAPID_SUBJECT` | — | Web Push |

## Test

```bash
npm test       # smoke (end-to-end: fake agent + fake client + W2/D confirm flow + audit)
               # + security (headers, login lockout, ticket single-use)
npm run smoke  # the smoke half alone
```

## Roadmap (relay-side)

Stripe-backed entitlement (v1 ships the manual flag), Discord DM bot,
HTTPS long-poll fallback for pathological networks, per-message HMAC signing,
migration of the JSON store to SQLite (audit P2).
