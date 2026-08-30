# Solidus Analytics — Server-Side Minecraft Fabric Mod

[![Platform](https://img.shields.io/badge/Platform-Fabric-blue.svg)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.x-green.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![Server-Side](https://img.shields.io/badge/Server_Side-Only-brightgreen.svg)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Type](https://img.shields.io/badge/Type-Economy_Intelligence-8B5CF6.svg)]()

**Economy intelligence layer for Solidus Core — wealth snapshots, inflation tracking, inequality metrics, health scoring, fraud detection, and a live web dashboard. No client mods required.**

Real-time economic telemetry · AES-256-GCM encrypted publishing · Zero client installation · Minecraft 26.1.x Ready

[Features](#-features) · [Dashboard](#-live-web-dashboard) · [Premium](#-premium-features) · [Quick Start](#-quick-start) · [Configuration](#-configuration) · [Architecture](#-architecture) · [FAQ](#-faq)

---

<!-- Schema.org Structured Data for Search Engines
{
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "name": "Solidus Analytics",
  "applicationCategory": "GameModification",
  "operatingSystem": "Minecraft 26.1.x",
  "programmingLanguage": "Java 25",
  "runtimePlatform": "Fabric Loader 0.19.4+",
  "license": "MIT",
  "description": "Server-side economy intelligence layer for Solidus Core: wealth snapshots, inflation tracking, Gini inequality, health scoring, fraud detection, and a live web dashboard. No client mods required.",
  "author": { "@type": "Person", "name": "MOHD_Gs", "url": "https://github.com/mohd-gs" },
  "url": "https://github.com/mohd-gs/solidus-analytics",
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" }
}
-->

## Why Solidus Analytics?

Solidus Core records every transaction your economy produces — but raw transaction rows don't tell you whether your economy is *healthy*. Solidus Analytics turns that transaction stream into decisions: periodic wealth snapshots with Gini inequality, live daily counters, inflation indicators, a 0–100 economy health score, and fraud signals that flag suspicious wealth accumulation before it ruins your server's balance.

Analytics is a **read-only observer**. It never writes to Core's economy databases — it polls them through SQLite read-only connections, aggregates everything into its own WAL-journaled database, and exposes the results through in-game commands, a localhost web dashboard, and optional encrypted publishing. All processing runs on background workers; the server tick thread never blocks.

### Highlights

* **Fully server-side architecture** — works with any vanilla client; the dashboard runs in any browser
* **Economy snapshots** — total wealth, Gini coefficient, top-1% share, median balance, auction value
* **Live metrics** — id-cursor polling of the transaction log with exact exactly-once volume accounting
* **Inflation tracking** — money supply vs. goods value ratio with 24h/7d/30d inflation rates
* **Economy Health Score** *(premium)* — weighted 0–100 score across five economic factors
* **Fraud detection** *(premium)* — rapid wealth gain, high-frequency trading, unusual transaction size
* **Live web dashboard** — embedded NanoHTTPD server, localhost-bound, PBKDF2 Basic auth
* **Encrypted publishing** *(premium)* — AES-256-GCM dashboard snapshots pushed to GitHub Pages
* **Weekly reports + Discord notifications** *(premium)* — ISO-week reports, webhook allowlist enforced
* **Graceful degradation** — Core is optional; without it, features report unavailable instead of failing

---

## Solidus Ecosystem

Solidus Analytics is the intelligence layer of the **Solidus Economy Ecosystem** — a suite of server-side Fabric mods that work together to create a complete, balanced economy for Minecraft servers.

| Module | License | Description |
|--------|---------|-------------|
| [solidus-core](https://github.com/mohd-gs/solidus-core) | MIT | Economy engine, server shop, auction house |
| **solidus-analytics** | MIT | **Economy intelligence dashboard, inflation tracking, fraud detection** (this repo) |
| [Solidus-Enforcer](https://github.com/mohd-gs/Solidus-Enforcer) | MIT | Bounty hunting, hunter license system, alliance rewards, autonomous anti-monopoly bounties |
| [Solidus-Governance](https://github.com/mohd-gs/Solidus-Governance) | Proprietary | Economy administration, progressive taxation, immutable audit logging, point-in-time rollback recovery |
| [solidus-territory](https://github.com/mohd-gs/solidus-territory) | MIT | Polygon-based land claiming, rent system, territory trading, visual particle borders |

Analytics integrates with Solidus Core through a **reflection-based bridge** and read-only SQLite access — zero compile dependency, automatic activation when Core is present, graceful degradation when absent.

> **Repository status**: this codebase is a verified reconstruction of the recovered `solidus-analytics` artifact (originally decompiled from a JAR). Every subsystem is being rebuilt, tested, and documented rather than treated as authoritative source. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for what is verified today.

---

## Features

### Economy Snapshots

Periodic point-in-time pictures of the entire economy, stored in Analytics' own database and used as the baseline for inflation rates and weekly reports. Snapshots require at least one known player balance — on a fresh install they are skipped rather than recorded as zeros.

* Total wealth, player count, average and median balance (computed in cents)
* Gini coefficient of wealth inequality (optimized algorithm above 1,000 players)
* Top-1% wealth share
* Active auction listings and their total market value
* `HOURLY` snapshots on a configurable interval, plus one `DAILY` snapshot per UTC day

### Live Metrics

A polling tracker reads Solidus Core's `transaction_log` incrementally and maintains today's counters in memory:

* Daily transaction count and daily volume — each money movement counted exactly once
* Per-type breakdown (`SHOP_BUY`, `SHOP_SELL`, `PAY_SEND`, `AUCTION_*`, ...)
* Top bought/sold items by quantity
* Active player count
* UTC-midnight rollover: yesterday's counters are persisted as daily metrics, then reset

### Inflation Tracking

Compares money supply (sum of all player balances) against goods value (active auction listings + 24h shop throughput) and classifies the ratio, with snapshot-based inflation rates over 24h, 7d, and 30d windows. Results are cached for five minutes.

### Live Web Dashboard

An embedded HTTP server (NanoHTTPD) serving a single-page dashboard and a JSON API. Built for local administration:

* Bound to `127.0.0.1` — never exposed to the network directly
* HTTP Basic authentication against a PBKDF2-SHA-256 password hash (210,000 iterations)
* Hardened response headers (`X-Frame-Options`, `nosniff`, `no-store`, `no-referrer`)
* Auto-refreshing browser view every 30 seconds
* **Daily trade volume chart** — dependency-free SVG line chart of the last 30 days from `dailyHistory`, with grid lines, compact axis labels (1.2k / 1.2M), and native hover tooltips per day
* Disabled by default — and refuses to start without a configured password hash

### Premium Features

Premium features unlock automatically when a valid license key is present:

* **Economy Health Score** — weighted composite: Gini 25%, inflation 25%, money growth 20%, activity 15%, liquidity 15%
* **Fraud detection** — three detectors with severity classification and alert history
* **Weekly reports** — ISO-week markdown reports written under `config/solidus-analytics/reports/`
* **Discord notifications** — fraud alerts, inflation warnings, daily summaries, health-score alerts
* **Encrypted GitHub publishing** — AES-256-GCM encrypted dashboard payloads for GitHub Pages

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full premium subsystem reference.

### Graceful Degradation

Solidus Analytics never requires Solidus Core to boot. The integration bridge resolves Core through reflection at startup; if Core is absent, the engine runs in standalone mode and every Core-dependent feature reports an unavailable state — in commands, on the dashboard, and in logs — instead of crashing or fabricating zeros.

---

## Quick Start

### Installation

> **Requirements:** Minecraft 26.1.x · Java 25 · Fabric Loader 0.19.4+ · Fabric API 0.155.2+ · Solidus Core (recommended)

1. Install [Fabric Loader](https://fabricmc.net/use/) on your server
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) on the server
3. Install [Solidus Core](https://github.com/mohd-gs/solidus-core/releases) — Analytics reads its databases
4. Download the latest Solidus Analytics release from [Releases](https://github.com/mohd-gs/solidus-analytics/releases)
5. Place the `.jar` file into your server's `mods/` folder
6. Start the server — configuration is generated at `config/solidus-analytics/analytics.properties`

**For premium features:** place your license key in `config/solidus-analytics/license.key` (single line) before starting the server.

**No client installation required.** The dashboard is viewed in any browser on the machine (or proxy host) running the server.

### First-Time Setup

```
/analytics                                ← Overview: snapshots, live metrics, dashboard status
/analytics wealth                         ← Total wealth and player count
/analytics inflation                      ← Current inflation indicators (24h window)
/analytics history 7                      ← Last 7 days of daily metrics
/analytics snapshot                       ← Force a snapshot now (admin)
```

### Enabling the Dashboard

The web server is **off by default**. To turn it on safely:

1. Set a password: `/analytics dashboard setup <password>`
2. Edit `config/solidus-analytics/dashboard.properties`: `webserver.enabled=true`
3. Restart the server and open `http://127.0.0.1:9090`
4. To expose it beyond localhost, use an HTTPS reverse proxy — never port-forward the raw server

---

## Commands

| Command | Access | Description |
| --- | --- | --- |
| `/analytics` | GameMaster | Overview: latest snapshot, live metrics, dashboard state |
| `/analytics wealth` | GameMaster | Total wealth and player count |
| `/analytics inflation` | GameMaster | Inflation indicators (24h) |
| `/analytics top items` | GameMaster | Most traded items today |
| `/analytics top buyers` | GameMaster | Top buyers (coming soon) |
| `/analytics top sellers` | GameMaster | Top sellers (coming soon) |
| `/analytics history [days]` | GameMaster | Daily metrics history, 1–90 days (default 7) |
| `/analytics snapshot` | Admin | Force an economy snapshot now |
| `/analytics export` | Admin | Export analytics data |
| `/analytics health` | GameMaster | Economy Health Score *(premium)* |
| `/analytics fraud` / `fraud list` | GameMaster | Recent fraud alerts *(premium)* |
| `/analytics fraud scan` | Admin | Run all fraud detectors now *(premium)* |
| `/analytics report weekly` | GameMaster | Generate the weekly report now |
| `/analytics license` | Admin | License verification status |
| `/analytics fingerprint` | Admin | Server fingerprint used by license keys |
| `/analytics dashboard` | Admin | Dashboard subsystem status |
| `/analytics dashboard setup <password>` | Admin | Set dashboard/encryption password (PBKDF2-hashed) |
| `/analytics dashboard unlock <password>` | Admin | Unlock encrypted publishing after restart |
| `/analytics dashboard github <owner> <repo>` | Admin | Configure GitHub Pages publishing target |
| `/analytics dashboard publish` | Admin | Publish an encrypted dashboard snapshot now |
| `/inflation [day\|week\|month]` | GameMaster | Inflation report over 24h/7d/30d |

---

## Configuration

Solidus Analytics generates configuration automatically on first run. Every integration is **disabled by default**.

**Location:** `config/solidus-analytics/analytics.properties`

**Example:**

```properties
snapshot.interval.minutes=30
polling.interval.seconds=30
data.retention.days=90
cleanup.interval.hours=24
discord.enabled=false
discord.webhook.url=
discord.fraud.min_severity=HIGH
```

**Dashboard:** `config/solidus-analytics/dashboard.properties`

```properties
webserver.enabled=false
webserver.port=9090
webserver.password_hash=
github.enabled=false
github.owner=
github.repo=
github.branch=main
publish.interval.seconds=60
```

**Secrets** belong in environment variables — never in Git:

| Variable | Purpose |
| --- | --- |
| `SOLIDUS_GITHUB_TOKEN` | GitHub token for dashboard publishing (a legacy in-file token is ignored with a warning) |
| `SOLIDUS_DASHBOARD_PASSWORD` | Optional: auto-unlocks encrypted publishing on server restart |

Values are validated and clamped on load — invalid numbers fall back to defaults, the fraud severity must be `LOW`/`MEDIUM`/`HIGH`, and Discord is force-disabled unless the webhook URL is an HTTPS `discord.com`/`discordapp.com` webhook.

---

## Compatibility

| Component | Requirement | Notes |
| --- | --- | --- |
| Minecraft | 26.1.2 | Mojang Official Mappings |
| Loader | Fabric 0.19.4+ | Server-side only |
| Fabric API | 0.155.2+26.1.2 | Required |
| Java | 25 | Required |
| Solidus Core | 2.x (recommended) | Optional at boot; required for live data |
| Client | Any (vanilla or modded) | Dashboard runs in a browser, not in-game |
| Database | SQLite (bundled) | WAL journaling, read-only access to Core's files |
| Side | Server only | Zero client-side dependencies |

---

## Architecture

```
com.solidus.analytics/
├── SolidusAnalyticsMod.java     — Entry point, lifecycle, tick scheduler
├── AnalyticsConfig.java         — Validated properties with clamped values
├── engine/
│   ├── AnalyticsEngine.java     — Central coordinator and lifecycle owner
│   ├── LiveMetricsTracker.java  — Id-cursor transaction polling, live counters
│   ├── SnapshotScheduler.java   — HOURLY/DAILY wealth snapshots, Gini
│   └── InflationCalculator.java — Money supply vs. goods value, cached rates
├── storage/
│   └── AnalyticsDatabase.java   — Own WAL-mode SQLite, single-thread worker
├── dashboard/
│   ├── DashboardManager.java    — Config, lifecycle, publish cadence
│   ├── AnalyticsWebServer.java  — NanoHTTPD, localhost, Basic auth
│   ├── DashboardDataBuilder.java— JSON contract for /api/data
│   ├── DashboardEncryption.java — AES-256-GCM payload encryption
│   └── GitHubDataPublisher.java — Contents API publishing
├── premium/
│   ├── EconomyHealthScore.java  — Weighted 0–100 composite score
│   ├── FraudDetector.java       — Wealth/frequency/size detectors
│   ├── WeeklyReportGenerator.java— ISO-week markdown reports
│   └── DiscordWebhookNotifier.java— Allowlisted webhook delivery
├── license/
│   └── LicenseVerifier.java     — License key + SHA-256 server fingerprint
├── integration/
│   └── SolidusIntegration.java  — Reflection bridge to Solidus Core
└── commands/                    — /analytics, /inflation
```

### Key Design Decisions

1. **Read-only observation** — Analytics opens Core's `economy.db` and `auctions.db` with `PRAGMA query_only = ON`. It can never corrupt or mutate economy state, even on a bug.

2. **The cents convention** — Core stores money as decimal `S$` (`REAL`). Analytics converts to integer cents at every read boundary and divides by 100 only at display time, eliminating floating-point drift from stored metrics. Scale-invariant indicators (Gini, shares, ratios, inflation %) are unaffected either way.

3. **Exact incremental polling** — the live tracker cursors on the autoincrement `transaction_log.id` instead of timestamps, so burst transactions sharing one millisecond are never skipped, and receiver-side mirror rows (`PAY_RECEIVE`, `AUCTION_SOLD`) are excluded from volume so each money movement is counted once.

4. **Single-thread worker** — all analytics database work runs on one daemon worker thread; shutdown drains pending tasks before closing the connection, so persisted metrics are never torn.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full architecture documentation — subsystem deep-dives, database schema, JSON contract, thread model, and security posture.

---

## FAQ

### Does this require Solidus Core?

**No — but it is pointless without it.** Analytics boots and serves an empty dashboard even without Core installed. All live data comes from Core's databases; when Core is present, integration activates automatically through reflection.

### Does this require client mods?

**No.** Analytics is entirely server-side. The web dashboard renders in any standard browser; in-game output uses vanilla chat components.

### Is the web dashboard safe to expose publicly?

It is built for localhost. The server binds to `127.0.0.1`, requires Basic auth (PBKDF2-hashed password), refuses to start without a configured password, and sends hardened headers. If you need remote access, put it behind an HTTPS reverse proxy with your own access controls — do not port-forward the raw server.

### What data does it collect?

Aggregated economy metrics only: balances (in aggregate), transaction types/amounts, item trade counts, and auction listings. No chat, no IPs, no item NBT payloads. The dashboard JSON contains player names only inside fraud alert descriptions.

### Which features need a license key?

Health score, fraud detection, weekly reports, Discord notifications, and encrypted GitHub publishing. Snapshots, live metrics, inflation tracking, in-game commands, and the local dashboard are free and always available.

### Where is my data stored?

Everything lives in `config/solidus-analytics/`: `analytics.db` (WAL-mode SQLite), `analytics.properties`, `dashboard.properties`, `license.key` (you provide), and `reports/`. Passwords are stored only as PBKDF2 hashes; tokens stay in environment variables.

### How does GitHub publishing work?

Analytics encrypts the dashboard payload with AES-256-GCM (password-derived key, 210,000 PBKDF2 iterations) and pushes it to a `owner/repo` you configure, using a token from `SOLIDUS_GITHUB_TOKEN`. Consumers decrypt with the same password. Never publish private player data to a public repository without reviewing the data policy first.

### How much does it affect server performance?

Practically nothing on the tick thread: tick handlers do constant-time work, all SQLite I/O runs on the analytics worker, and Core's databases are polled read-only once per interval (default 30s). See the performance section in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Download

| Platform | Link |
| --- | --- |
| GitHub Releases | [Latest Release](https://github.com/mohd-gs/solidus-analytics/releases) |
| Modrinth | [MOHD_Gs on Modrinth](https://modrinth.com/user/MOHD_Gs) |

---

## Contributing

Contributions are welcome.

* Report issues via [GitHub Issues](https://github.com/mohd-gs/solidus-analytics/issues)
* Suggest features or improvements
* Submit pull requests

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for technical details, the testing strategy, and contribution guidelines.

---

## License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE) for details. Core analytics features are 100% free; premium features (health score, fraud detection, weekly reports, Discord, encrypted publishing) require a license key.

---

## Keywords

`minecraft analytics mod` · `minecraft economy dashboard` · `minecraft fabric mod` · `minecraft server economy` · `minecraft inflation tracking` · `minecraft fraud detection` · `minecraft gini coefficient` · `server-side minecraft mod` · `minecraft economy health score` · `minecraft web dashboard` · `solidus analytics` · `minecraft economy monitoring`

---

Built by [MOHD_Gs](https://github.com/mohd-gs) · [Email](mailto:mohdmxmxm@gmail.com) · Discord: **mohd_gs** · Part of the [Solidus Economy Ecosystem](https://github.com/mohd-gs)
