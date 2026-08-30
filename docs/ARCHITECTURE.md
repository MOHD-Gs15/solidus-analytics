# Solidus-Analytics Architecture Documentation

> **Version**: 1.1.0 | **Minecraft**: 26.1.2 | **Fabric**: 0.19.4+ | **Java**: 25  
> **License**: MIT | **Environment**: 100% Server-Side Only

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture Philosophy & Design Principles](#2-architecture-philosophy--design-principles)
3. [High-Level System Architecture](#3-high-level-system-architecture)
4. [Initialization & Lifecycle](#4-initialization--lifecycle)
5. [Package Structure](#5-package-structure)
6. [Core Subsystem: Data Collection](#6-core-subsystem-data-collection)
   - 6.1 [LiveMetricsTracker — Incremental Transaction Polling](#61-livemetricstracker--incremental-transaction-polling)
   - 6.2 [SnapshotScheduler — Periodic Wealth Snapshots](#62-snapshotscheduler--periodic-wealth-snapshots)
   - 6.3 [InflationCalculator — Money Supply vs. Goods Value](#63-inflationcalculator--money-supply-vs-goods-value)
7. [Core Subsystem: Analytics Storage](#7-core-subsystem-analytics-storage)
8. [Core Subsystem: Web Dashboard](#8-core-subsystem-web-dashboard)
9. [Core Subsystem: Encrypted Publishing](#9-core-subsystem-encrypted-publishing)
10. [Premium Subsystem](#10-premium-subsystem)
    - 10.1 [LicenseVerifier — License Gate](#101-licenseverifier--license-gate)
    - 10.2 [EconomyHealthScore — Weighted Composite Score](#102-economyhealthscore--weighted-composite-score)
    - 10.3 [FraudDetector — Suspicious Pattern Detection](#103-frauddetector--suspicious-pattern-detection)
    - 10.4 [WeeklyReportGenerator & DiscordWebhookNotifier](#104-weeklyreportgenerator--discordwebhooknotifier)
11. [Cross-Cutting: SolidusIntegration — Reflection Bridge](#11-cross-cutting-solidusintegration--reflection-bridge)
12. [Cross-Cutting: Money & Units Convention](#12-cross-cutting-money--units-convention)
13. [Cross-Cutting: Dashboard JSON Contract](#13-cross-cutting-dashboard-json-contract)
14. [Thread Safety Model](#14-thread-safety-model)
15. [Database Schema](#15-database-schema)
16. [Configuration System](#16-configuration-system)
17. [Command Reference](#17-command-reference)
18. [Testing Strategy](#18-testing-strategy)
19. [Extension Points & Integration Hooks](#19-extension-points--integration-hooks)
20. [Security Considerations](#20-security-considerations)
21. [Performance Characteristics](#21-performance-characteristics)
22. [Glossary](#22-glossary)

---

## 1. System Overview

**Solidus-Analytics** is a server-side economy intelligence layer for Minecraft Fabric. It observes a running [Solidus Core](https://github.com/mohd-gs/solidus-core) economy and turns its transaction stream into operational insight: wealth snapshots with Gini inequality, live daily counters, inflation indicators, a weighted economy health score, fraud signals, and an authenticated single-page web dashboard with optional encrypted publishing to GitHub Pages.

The mod operates as a **read-only observer**: it opens Solidus Core's SQLite databases in query-only mode, polls the transaction log incrementally on a background worker, and writes all derived metrics into its own WAL-journaled database. It never mutates economy state — a bug in Analytics can lose analytics data, but never economy data.

### Key Capabilities

| Feature | Description |
|---------|-------------|
| **Economy Snapshots** | `HOURLY`/`DAILY` snapshots: total wealth, player count, Gini coefficient, top-1% share, median balance, auction market value |
| **Live Metrics** | Id-cursor incremental polling of `transaction_log` with exactly-once volume accounting and UTC daily rollover |
| **Inflation Tracking** | Money supply vs. goods value ratio, cached 5 minutes, plus 24h/7d/30d snapshot-based rates |
| **Analytics Database** | Own SQLite storage (WAL mode) with automatic retention cleanup |
| **Web Dashboard** | Embedded NanoHTTPD server bound to `127.0.0.1`, PBKDF2 Basic auth, hardened headers, JSON API |
| **Encrypted Publishing** | AES-256-GCM encrypted dashboard payloads pushed to a GitHub repository *(premium)* |
| **Economy Health Score** | Weighted 0–100 composite across five factors *(premium)* |
| **Fraud Detection** | Rapid wealth gain, high-frequency trading, unusual transaction size *(premium)* |
| **Weekly Reports** | ISO-week-based markdown reports under `config/solidus-analytics/reports/` *(premium)* |
| **Discord Notifications** | Allowlisted-HTTPS webhook delivery on a dedicated executor *(premium)* |
| **Graceful Degradation** | Boots without Solidus Core; every Core-dependent feature reports unavailable instead of failing |

---

## 2. Architecture Philosophy & Design Principles

### 2.1 Read-Only Observation

Analytics treats Solidus Core's databases as an immutable source of truth. Every connection to `economy.db` or `auctions.db` executes `PRAGMA query_only = ON` immediately after opening:

- No analytics code path can `INSERT`, `UPDATE`, or `DELETE` economy rows
- Concurrent writes by Core never block analytics reads (SQLite WAL on Core's side)
- The worst-case failure is a logged read error and a zeroed metric — never economy corruption

### 2.2 Async-First, Never Block the Tick Thread

The Minecraft server runs on a single main tick thread. Analytics registers exactly two tick hooks and both do constant-time work:

- `END_SERVER_TICK` → tick counters, compare against intervals, submit async work
- All SQLite I/O, HTTP publishing, and fraud scans run on background executors
- No `.join()` or blocking `.get()` exists on the tick path

### 2.3 Single-Thread Executor Serialization

All analytics database operations are serialized through one dedicated daemon worker (`Solidus-Analytics-Worker`). This guarantees:

- Snapshot inserts, daily-metric upserts, and retention deletes never interleave
- The persisted database state is always a valid sequence of completed tasks
- Shutdown drains queued tasks (including final persistence) **before** closing the connection

### 2.4 The Cents Convention

Solidus Core stores monetary values as decimal `S$` figures (`REAL` columns: `balance`, `amount`, `price`). Solidus Analytics' internal contract is **integer cents everywhere**:

- Every read boundary converts explicitly: `Math.round(rs.getDouble(col) * 100.0)`
- Display boundaries divide by 100 (the dashboard renders cents as `S$`)
- Scale-invariant indicators — Gini coefficient, top-1% share, money-to-goods ratio, inflation % — are computed from same-unit values, so the convention cannot distort them

This convention exists because a raw `getLong()` on a `REAL` column both truncates fractions (`12.75 → 12`) and stores the wrong unit — two distinct bug classes that unit-aware boundaries eliminate.

### 2.5 Exact Incremental Polling

The live metrics cursor is the autoincrement `transaction_log.id`, not a timestamp:

- Timestamp cursors permanently skip rows written in the same millisecond as the cursor (burst writes are common) — id cursors cannot skip rows
- An empty transaction log at startup is a **valid cursor position** (0 = "everything from id 1 onward is new"), so a fresh install starts collecting metrics immediately
- If the economy database is not readable yet, cursor seeding retries on every poll cycle until it succeeds
- Receiver-side mirror rows (`PAY_RECEIVE`, `AUCTION_SOLD`) are excluded from volume aggregation so each money movement is counted exactly once — while still counting toward activity statistics

### 2.6 Secure by Default

Every outward-facing integration ships disabled and stays disabled until explicitly configured:

- Web dashboard: off by default, binds `127.0.0.1`, refuses to start without a PBKDF2 password hash
- GitHub publishing: off by default; the token is read from the `SOLIDUS_GITHUB_TOKEN` environment variable (in-file tokens are ignored with a warning)
- Discord: off by default; force-disabled unless the webhook URL matches the HTTPS `discord.com`/`discordapp.com` webhook allowlist
- Passwords are stored only as PBKDF2-SHA-256 hashes (210,000 iterations, per-hash random salt)

### 2.7 Graceful Degradation

Analytics boots and operates even when pieces are missing:

- **Core absent** → the reflection bridge reports unavailable; commands and the dashboard render `null`/unavailable states instead of crashing
- **No license** → premium subsystems are not constructed; free features run unchanged
- **Publishing locked** → payloads are still built locally; only the push is skipped
- **Corrupt/unexpected numbers** → dashboard JSON maps `NaN`/`Infinity` to `null` so one bad value cannot break the whole payload

---

## 3. High-Level System Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Minecraft Server                             │
│                                                                      │
│  ┌─────────────┐   ┌──────────────────┐   ┌──────────────────────┐  │
│  │  Brigadier   │   │  Fabric Events   │   │  Minecraft Server    │  │
│  │  Commands    │   │  (lifecycle)     │   │  Tick Loop           │  │
│  └──────┬───────┘   └────────┬─────────┘   └──────────┬───────────┘  │
│         │                    │                        │              │
│         ▼                    ▼                        ▼              │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    SolidusAnalyticsMod.java                    │  │
│  │                (DedicatedServerModInitializer)                 │  │
│  │                                                               │  │
│  │  ┌──────────────────── AnalyticsEngine ────────────────────┐  │  │
│  │  │                                                         │  │  │
│  │  │  ┌──────────────────┐  ┌──────────────────┐             │  │  │
│  │  │  │ LiveMetrics      │  │ Snapshot         │             │  │  │
│  │  │  │ Tracker          │  │ Scheduler        │             │  │  │
│  │  │  │ (id-cursor poll) │  │ (HOURLY/DAILY)   │             │  │  │
│  │  │  └────────┬─────────┘  └────────┬─────────┘             │  │  │
│  │  │           │        ┌────────────┘                       │  │  │
│  │  │           ▼        ▼                                    │  │  │
│  │  │  ┌─────────────────────────────────────────────┐        │  │  │
│  │  │  │        AnalyticsDatabase (WAL SQLite)       │        │  │  │
│  │  │  │   Solidus-Analytics-Worker (single thread)  │        │  │  │
│  │  │  └────────────────────┬────────────────────────┘        │  │  │
│  │  │                       │                                 │  │  │
│  │  │  ┌────────────────────┴───────────┐                     │  │  │
│  │  │  │      DashboardManager          │                     │  │  │
│  │  │  │  ┌────────────┐ ┌────────────┐ │                     │  │  │
│  │  │  │  │ Analytics  │ │ GitHubData │ │                     │  │  │
│  │  │  │  │ WebServer  │ │ Publisher  │ │                     │  │  │
│  │  │  │  │ (NanoHTTPD)│ │ + Encrypt. │ │                     │  │  │
│  │  │  │  └────────────┘ └────────────┘ │                     │  │  │
│  │  │  └────────────────────────────────┘                     │  │  │
│  │  │                                                         │  │  │
│  │  │  ┌─────────────── Premium (license-gated) ───────────┐  │  │
│  │  │  │ LicenseVerifier · EconomyHealthScore              │  │  │
│  │  │  │ FraudDetector · WeeklyReports · DiscordNotifier   │  │  │
│  │  │  └───────────────────────────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                                                               │  │
│  │  ┌───────────────────────────────────────────────────────┐   │  │
│  │  │  SolidusIntegration (reflection bridge to Core)       │   │  │
│  │  └───────────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘

   READ-ONLY (PRAGMA query_only)          EXTERNAL CONSUMERS
  ┌─────────────────────────┐          ┌──────────────────────────┐
  │  Solidus Core files:    │          │  Browser dashboard       │
  │   economy.db            │◄─────────│  (http://127.0.0.1:9090) │
  │   auctions.db           │          └──────────────────────────┘
  └─────────────────────────┘          ┌──────────────────────────┐
                                       │  GitHub repository       │
                                       │  (encrypted payload)     │
                                       └──────────────────────────┘
                                       ┌──────────────────────────┐
                                       │  Discord webhook         │
                                       │  (allowlisted HTTPS)     │
                                       └──────────────────────────┘
```

---

## 4. Initialization & Lifecycle

The entire mod lifecycle is managed by `SolidusAnalyticsMod.java`, which implements `DedicatedServerModInitializer`. Registration happens at mod init; heavy initialization is deferred to `SERVER_STARTED` so the config directory and game directory are fully available.

```
┌──────────────────────────────────────────────────────────────┐
│                  onInitializeServer()                        │
│                                                              │
│  1. new AnalyticsEngine()                                    │
│  2. Register SERVER_STARTED hook                             │
│     └─ analyticsEngine.initialize(configDir)                 │
│  3. Register END_SERVER_TICK hook                            │
│     └─ engine.onServerTick(tick) when initialized            │
│  4. Register CommandRegistrationCallback                     │
│     └─ /analytics, /inflation (+ premium subcommands)        │
│  5. Register SERVER_STOPPING hook                            │
│     └─ analyticsEngine.shutdown()                            │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│            initialize(configDir)  [SERVER_STARTED]           │
│                                                              │
│  1. AnalyticsConfig.load()                                   │
│     └─ analytics.properties; validation + clamping           │
│  2. SolidusIntegration.initialize()                          │
│     └─ Reflection probe of Solidus Core (ACTIVE/UNAVAILABLE)  │
│  3. Resolve Core database paths                              │
│     └─ config/solidus/economy.db, config/solidus/auctions.db │
│  4. AnalyticsDatabase.initialize()                           │
│     └─ Opens analytics.db, WAL, creates tables               │
│     └─ On failure: engine does NOT start                     │
│  5. LiveMetricsTracker.start()                               │
│     └─ Seeds id cursor, submits polling loop to worker       │
│  6. SnapshotScheduler (interval from config)                 │
│  7. InflationCalculator                                      │
│  8. WeeklyReportGenerator                                    │
│  9. initializePremium(configDir)                             │
│     └─ LicenseVerifier → EconomyHealthScore, FraudDetector,  │
│        DiscordWebhookNotifier, initial fraud scan            │
│ 10. DashboardManager.initialize()                            │
│     └─ dashboard.properties → web server / publishing        │
└──────────────────────────────────────────────────────────────┘
```

### Tick Handlers

`onServerTick(tick)` performs only counter arithmetic on the tick thread:

| Handler | Cadence | Async work submitted |
|---------|---------|----------------------|
| `SnapshotScheduler.onTick` | `snapshot.interval.minutes` (default 30 min) | `computeSnapshot()` + insert |
| `DashboardManager.onTick` | `publish.interval.seconds` (min 30s) | Build JSON → encrypt → publish |
| Retention sweep | fixed 720,000 ticks (~10h at 20 TPS) | `DELETE` rows older than `data.retention.days` |

### Shutdown Sequence

```
SERVER_STOPPING event fires:
  1. dashboardManager.shutdown()   — Web server stop + publisher shutdown
  2. liveMetrics.stop()            — forcePersist() of today's counters
  3. licenseVerifier.shutdown()    — Release license resources
  4. discordNotifier.shutdown()    — Drain webhook queue (5s grace)
  5. database.shutdown()           — Drain worker queue, close analytics.db
```

The shutdown order drains outward-facing consumers first and the database last, so `forcePersist` output is guaranteed to be written before the connection closes.

---

## 5. Package Structure

```
com.solidus.analytics/
├── SolidusAnalyticsMod.java       — Entry point, lifecycle, tick scheduler
├── AnalyticsConfig.java           — analytics.properties loader, validation, clamping
├── commands/
│   ├── AnalyticsCommand.java      — /analytics root (overview, wealth, top, history…)
│   ├── InflationCommand.java      — /inflation [day|week|month]
│   └── PremiumCommand.java        — /analytics health|fraud|license|dashboard…
├── engine/
│   ├── AnalyticsEngine.java       — Central coordinator and lifecycle owner
│   ├── LiveMetricsTracker.java    — Id-cursor polling, live counters, daily rollover
│   ├── SnapshotScheduler.java     — Periodic snapshots, Gini, daily dedup
│   └── InflationCalculator.java   — Money supply vs. goods value, cached report
├── storage/
│   └── AnalyticsDatabase.java     — Own WAL-mode SQLite, single-thread worker
├── dashboard/
│   ├── DashboardManager.java      — Dashboard config, cadence, publish loop
│   ├── AnalyticsWebServer.java    — NanoHTTPD, localhost, Basic auth, headers
│   ├── DashboardDataBuilder.java  — JSON contract builder (NaN-safe)
│   ├── DashboardEncryption.java   — AES-256-GCM payload encryption, PBKDF2
│   └── GitHubDataPublisher.java   — GitHub Contents API publishing
├── premium/
│   ├── EconomyHealthScore.java    — Weighted 0–100 composite score
│   ├── FraudDetector.java         — Wealth/frequency/size detectors
│   ├── WeeklyReportGenerator.java — ISO-week markdown reports
│   └── DiscordWebhookNotifier.java— Allowlisted webhook delivery
├── license/
│   └── LicenseVerifier.java       — License key + SHA-256 server fingerprint
├── integration/
│   └── SolidusIntegration.java    — Reflection bridge to Solidus Core
└── util/
    └── GiniCoefficient.java       — Exact and optimized Gini algorithms

resources/
├── fabric.mod.json                — environment: "server", suggests: solidus
└── web/
    ├── index.html                 — Single-page dashboard shell
    ├── css/style.css              — Dark-theme styling + SVG chart classes
    └── js/app.js                  — 30s auto-refresh against /api/data;
                                      renders a dependency-free SVG line
                                      chart of dailyHistory volume (oldest
                                      → newest, grid, hover tooltips)
```

---
## 6. Core Subsystem: Data Collection

The collection layer is the read-only bridge between Solidus Core's databases and Analytics' own storage. It consists of three independent engines, all running on the shared `Solidus-Analytics-Worker` thread.

### 6.1 LiveMetricsTracker — Incremental Transaction Polling

**File**: `com.solidus.analytics.engine.LiveMetricsTracker`

`LiveMetricsTracker` polls Core's `transaction_log` table on a fixed interval (default 30s, minimum 5s) and maintains today's metrics in lock-free in-memory counters.

#### Architecture: Cursor-Based Incremental Poll

```
┌────────────────────────────────────────────────────────────┐
│  polling loop (on Solidus-Analytics-Worker)                │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 1. Seed cursor (retry until economy.db is readable)  │  │
│  │    SELECT MAX(id) FROM transaction_log               │  │
│  │    ├─ rows exist  → cursor = MAX(id)                 │  │
│  │    └─ table empty → cursor = 0  (VALID position:     │  │
│  │       every future row id ≥ 1 is new)                │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ▼                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 2. SELECT … WHERE id > ? ORDER BY id ASC             │  │
│  │    (PRAGMA query_only = ON)                          │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ▼                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 3. Per row:                                          │  │
│  │    amountCents = round(amount * 100)                 │  │
│  │    volume      += |amountCents| UNLESS mirror row    │  │
│  │                  (PAY_RECEIVE / AUCTION_SOLD)        │  │
│  │    txCount++, byType[type]++, topItems, activePlayers│  │
│  │    cursor = MAX(cursor, row.id)                      │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ▼                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 4. UTC midnight rollover:                            │  │
│  │    persistDailyMetrics(yesterday) → reset counters   │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

#### Why an Id Cursor and Not a Timestamp

| Cursor type | Failure mode | Behavior |
|-------------|-------------|----------|
| `WHERE timestamp > ?` (legacy) | Rows written in the same millisecond as the cursor advance are **permanently skipped** — burst transactions within 1ms are common | Silent data loss |
| `WHERE id > ?` (current) | None — `id` is `INTEGER PRIMARY KEY AUTOINCREMENT`, strictly increasing | Exact, exactly-once |

#### Volume vs. Activity Accounting

Solidus Core writes **one audit row per participant** of a money movement:

| Economic event | Rows written |
|----------------|--------------|
| `/pay` 100 S$ | `PAY_SEND` (sender) + `PAY_RECEIVE` (receiver), same amount |
| Auction sale 250.50 S$ | `AUCTION_BOUGHT` (buyer) + `AUCTION_SOLD` (seller), same price |
| Shop buy / shop sell | Single row |

Volume aggregation therefore excludes the receiver-side mirrors (`PAY_RECEIVE`, `AUCTION_SOLD`) from `dailyVolumeCents` only. The mirror rows still count toward the transaction counter, per-type statistics, top-item rankings, and the active-player set — so volume measures *economic throughput* while counts measure *audit activity*.

#### Key State

| Field | Type | Purpose |
|-------|------|---------|
| `lastPolledId` | `AtomicLong` | Id cursor; 0 after seeding an empty log |
| `cursorInitialized` | `volatile boolean` | Distinguishes "not seeded" from "seeded empty" |
| `dailyVolumeCents` | `AtomicLong` | Today's money movement in cents |
| `dailyTransactionCount` | `AtomicLong` | Today's audit row count |
| `transactionsByType` | `ConcurrentHashMap` | Per-type counters |
| `topItemsBought` / `topItemsSold` | `ConcurrentHashMap` | Per-material quantity counters |
| `activePlayers` | `ConcurrentHashMap` | UUIDs seen today |

`stop()` calls `forcePersist()` so counters accumulated since the last rollover are written to `analytics_daily_metrics` before shutdown.

### 6.2 SnapshotScheduler — Periodic Wealth Snapshots

**File**: `com.solidus.analytics.engine.SnapshotScheduler`

`SnapshotScheduler` takes point-in-time pictures of the economy on a tick-driven interval (default 36,000 ticks = 30 minutes) and on demand (`/analytics snapshot`).

#### Computation Pipeline

```
player_balances (read-only)          auction_listings (read-only)
  SELECT balance ORDER BY ASC          SELECT COUNT(*), SUM(price)
            │                          WHERE status = 0
            ▼                                    │
  balances → cents (×100)                        ▼
            │                          auctionActiveListings
            ▼                          auctionTotalValue (×100 → cents)
  totalWealth, avgBalance,
  medianBalance,
  GiniCoefficient (calculate,
  or calculateOptimized > 1000
  players), top1PercentShare
            │
            ▼
  Snapshot(timestamp, "HOURLY") → analytics.db
            │
            ├── first snapshot of the UTC day → extra "DAILY" snapshot
            └── first snapshot of a new UTC day → weekly report check (premium)
```

**Behavioral notes**:

- A snapshot with zero known player balances is **skipped** (logged) rather than recorded as zeros — fresh installs don't pollute history
- Expired listings (`status != 0`) are excluded from auction value
- `totalWealth` is reused as `moneySupply` in the snapshot record

### 6.3 InflationCalculator — Money Supply vs. Goods Value

**File**: `com.solidus.analytics.engine.InflationCalculator`

Computes the money-to-goods ratio and snapshot-based inflation rates. Results are cached for 5 minutes (`getCachedOrCalculate()`).

| Input | Source | Unit conversion |
|-------|--------|-----------------|
| Money supply | `SUM(balance)` from `player_balances` | ×100 → cents |
| Auction goods value | `SUM(price) WHERE status = 0` from `auction_listings` | ×100 → cents |
| Shop throughput | `SUM(ABS(amount))` of `SHOP_BUY`/`SHOP_SELL` in last 24h | ×100 → cents |

#### Ratio Interpretation

| moneyToGoodsRatio | Status |
|-------------------|--------|
| `< 0` (no goods available) | `NO GOODS AVAILABLE` |
| `< 2.0` | `DEFLATION` |
| `< 5.0` | `HEALTHY` |
| `< 10.0` | `MODERATE INFLATION` |
| `≥ 10.0` | `INFLATION WARNING` |

Inflation rates (24h/7d/30d) compare the latest snapshot's `totalWealth` against the snapshot nearest each window boundary, returning `null` when no baseline exists.

---

## 7. Core Subsystem: Analytics Storage

**File**: `com.solidus.analytics.storage.AnalyticsDatabase`

Analytics' own persistence layer. All writes flow through a single daemon worker thread named `Solidus-Analytics-Worker`; the database file lives at `config/solidus-analytics/analytics.db`.

#### SQLite Tuning

```sql
PRAGMA journal_mode = WAL;       -- crash resilience, concurrent reads
PRAGMA synchronous = NORMAL;     -- WAL-appropriate durability/speed balance
PRAGMA temp_store = MEMORY;      -- temp tables in RAM
PRAGMA mmap_size = 67108864;     -- 64 MB memory-mapped I/O
PRAGMA cache_size = -2000;       -- ~2 MB page cache
```

WAL mode guarantees that committed analytics rows survive a hard crash, and lets the dashboard read (via its own connection) while the worker writes.

#### Responsibilities

| Concern | Implementation |
|---------|----------------|
| Schema lifecycle | `CREATE TABLE IF NOT EXISTS` for 4 tables + indexes at initialize |
| Async writes | `insertSnapshot`, `upsertDailyMetrics*`, item metrics — all `CompletableFuture` on the worker |
| Reads | `getLatestSnapshot`, `getSnapshotBefore`, `getRecentDailyMetrics` |
| Retention | `runCleanup(retentionDays)` deletes rows older than the horizon from all metric tables |
| Shutdown | Executor drain (await with timeout, forced shutdown fallback) → connection close, unconditional on `initialized` |

---

## 8. Core Subsystem: Web Dashboard

**Files**: `com.solidus.analytics.dashboard.DashboardManager`, `AnalyticsWebServer`, `DashboardDataBuilder`

The dashboard is an embedded [NanoHTTPD](https://github.com/NanoHttpd/NanoHttpd) server serving a single-page application plus a JSON API.

#### Request Flow

```
Browser ──GET http://127.0.0.1:9090──► AnalyticsWebServer (NanoHTTPD)
                                          │
                            ┌─────────────┴─────────────┐
                            │ OPTIONS? → Allow: GET      │
                            │ non-GET? → 405             │
                            │ Basic auth vs PBKDF2 hash? │
                            │   fail → 401 + WWW-        │
                            │   Authenticate (generic    │
                            │   body: never leaks the    │
                            │   setup command)           │
                            └─────────────┬─────────────┘
                                          ▼
                     ┌────────────────────────────────────┐
                     │ / , /index.html → index.html       │
                     │ /api/data       → cached JSON      │
                     │ /css/style.css  → stylesheet       │
                     │ /js/app.js      → app (30s refresh)│
                     │ else            → 404              │
                     └────────────────────────────────────┘
                                          ▼
                 Headers on every response: Cache-Control: no-store,
                 X-Content-Type-Options: nosniff, X-Frame-Options: DENY,
                 Referrer-Policy: no-referrer
```

#### Hardening Rules

- Binds to the loopback interface `127.0.0.1` — never `0.0.0.0`
- Refuses to start when `webserver.enabled=true` but `webserver.password_hash` is blank
- Credentials verified against a PBKDF2-SHA-256 hash (`pbkdf2$sha256$210000$salt$hash` storage format); the plaintext password never touches disk
- The 401 response body is generic; `WWW-Authenticate: Basic realm="Solidus Analytics"` is added so standard auth clients prompt correctly
- JSON is served from a `volatile` cached snapshot built by the worker — requests never trigger computation on the HTTP thread

#### Dashboard Data Flow

```
Tick (publish.interval.seconds, min 30)
  └─► DashboardManager.onTick
        └─► DashboardDataBuilder.buildJson(engine)     (worker thread)
              ├─ liveMetrics counters
              ├─ latest snapshot (analytics.db)
              ├─ inflation report (cached)
              ├─ health score + fraud alerts (premium, null-gated)
              ├─ daily history (30 days)
              └─ top items
        └─► webServer.updateData(json)                  (volatile swap)
        └─► [publishing enabled] encrypt → GitHub push
```

---

## 9. Core Subsystem: Encrypted Publishing

**Files**: `com.solidus.analytics.dashboard.DashboardEncryption`, `GitHubDataPublisher`

Publishing lets an operator mirror an *encrypted* dashboard payload to a GitHub repository (typically rendered by GitHub Pages or a decrypting consumer).

#### Encryption Format

```
payload = salt(16B) ‖ iv(12B) ‖ AES-256-GCM-ciphertext ‖ tag(16B)

key     = PBKDF2-SHA-256(password, salt, 210000 iterations, 256-bit)
cipher  = AES/GCM/NoPadding, 128-bit tag
```

- The password is set once via `/analytics dashboard setup <password>` and stored only as a PBKDF2 hash (`encryption.password_hash`)
- After a restart the vault is locked; unlock with `/analytics dashboard unlock <password>` or set `SOLIDUS_DASHBOARD_PASSWORD` for automatic unlock
- While locked, payloads can still be built and served locally — only encrypted publishing is blocked

#### GitHub Publishing

| Aspect | Behavior |
|--------|----------|
| Target | `github.owner` / `github.repo` / `github.branch` (default `main`) from `dashboard.properties` |
| API | GitHub Contents API (`PUT /repos/{owner}/{repo}/contents/{path}`), Base64 content |
| Credential | `SOLIDUS_GITHUB_TOKEN` environment variable only; a legacy in-file `github.token` is **ignored with a warning** |
| Validation | owner/repo/branch must match `[A-Za-z0-9._/-]` shape constraints before any request is made |
| Cadence | `publish.interval.seconds` (minimum 30s / 600 ticks) on the tick counter |
| Failure mode | Logged, non-fatal; dashboard keeps serving locally |

---

## 10. Premium Subsystem

Premium features are constructed **only** after `LicenseVerifier` accepts the key in `config/solidus-analytics/license.key`. Without a license the objects are never instantiated — no code path can reach them.

### 10.1 LicenseVerifier — License Gate

**File**: `com.solidus.analytics.license.LicenseVerifier`

| Aspect | Behavior |
|--------|----------|
| Key source | `config/solidus-analytics/license.key` (single line) |
| Binding | SHA-256 server fingerprint; `ANY` fingerprints are accepted as wildcard |
| States | valid / missing / invalid — logged at startup with actionable messages |
| Re-verification | `/analytics license` (admin) shows status; fingerprint via `/analytics fingerprint` |

### 10.2 EconomyHealthScore — Weighted Composite Score

**File**: `com.solidus.analytics.premium.EconomyHealthScore`

Produces a 0–100 score with a letter grade and a human-readable summary:

| Factor | Weight | Input |
|--------|--------|-------|
| Gini inequality | 25% | Latest snapshot `giniCoefficient` |
| Inflation | 25% | Inflation calculator rates |
| Money growth | 20% | Snapshot wealth deltas |
| Activity | 15% | Live transaction counters |
| Liquidity | 15% | Money-to-goods ratio |

`overallScore` is clamped to `[0, 100]`. Discord alerts fire when the score crosses the configured threshold (`discord.health_score.threshold`, default 50).

### 10.3 FraudDetector — Suspicious Pattern Detection

**File**: `com.solidus.analytics.premium.FraudDetector`

| Detector | Window | Trigger | Severity |
|----------|--------|---------|----------|
| `RAPID_WEALTH_GAIN` | 1 hour | Player income (`SUM(amount > 0)`) > 5× server average income | `HIGH` > 10×, else `MEDIUM` |
| `HIGH_FREQUENCY` | 1 minute | > 30 transactions by one player | `HIGH` > 90, else `LOW` |
| `UNUSUAL_SIZE` | 1 hour | Single transaction > 10× average `ABS(amount)` (top 10) | `HIGH` > 20×, else `MEDIUM` |

All amount comparisons read `REAL` columns as `double` (fractional `S$` preserved); alert text stays in raw `S$` display units. Alerts are kept in a bounded in-memory ring (100 most recent) surfaced via `/analytics fraud` and the dashboard. An initial full scan runs on startup; `/analytics fraud scan` (admin) reruns all detectors on demand.

### 10.4 WeeklyReportGenerator & DiscordWebhookNotifier

**Files**: `com.solidus.analytics.premium.WeeklyReportGenerator`, `DiscordWebhookNotifier`

- **Weekly reports** are keyed by `IsoFields.WEEK_BASED_YEAR` + week-of-week-based-year — the calendar-year week identity used previously collided near year boundaries (week 1 of the new year vs. week 52/53 of the old one), producing skipped or duplicated reports. `checkAndGenerate()` runs on the first snapshot of each new UTC day and writes a report when the ISO week identity has changed; files land under `config/solidus-analytics/reports/`.
- **Discord notifications** run on a dedicated single-thread webhook executor (drained with a 5-second grace at shutdown). The webhook URL is validated against the HTTPS Discord allowlist at config load; per-category delivery toggles (`fraud`, `inflation`, `daily summary`, `health score`) and the fraud minimum severity are configurable.

---

## 11. Cross-Cutting: SolidusIntegration — Reflection Bridge

**File**: `com.solidus.analytics.integration.SolidusIntegration`

The bridge is the only code aware of Solidus Core. It resolves Core **by class name at runtime** — zero compile-time dependency:

```
Class.forName("com.solidus.api.SolidusAPI")        → API availability probe
Class.forName("com.solidus.economy.EconomyEngine") → engine handle
Class.forName("com.solidus.economy.SQLiteStorage") → storage access
Class.forName("com.solidus.economy.TransactionLog")→ log access
… MethodHandles cached once, reused per call
```

**Key Design Decision**: Analytics reads Core's state through the bridge when available, and through **read-only SQLite queries** against Core's database files either way. If Core is absent:

- `initialize()` returns `apiIntegrationAvailable = false`; the engine logs `Standalone (DB-only)` mode
- Live polling and snapshots simply observe empty/missing tables and log unavailable states
- Nothing crashes; the dashboard renders `null` sections

This isolation exists so a future stable Core API can replace the internal-class reflection without touching Analytics' engines.

---
## 12. Cross-Cutting: Money & Units Convention

This is the single most important correctness convention in the codebase. Solidus Core stores money as decimal `S$` figures in `REAL` columns; Solidus Analytics' internal representation is **integer cents**.

#### Conversion Points

| Source column | Consumer | Rule |
|---------------|----------|------|
| `player_balances.balance` (`REAL S$`) | SnapshotScheduler, InflationCalculator | `Math.round(getDouble * 100.0)` |
| `transaction_log.amount` (`REAL S$`) | LiveMetricsTracker volume | `Math.round(getDouble * 100.0)` |
| `auction_listings.price` (`REAL S$`) | SnapshotScheduler auction value, InflationCalculator goods value | `Math.round(getDouble * 100.0)` |
| FraudDetector reads | Alert text (display) | Kept as raw `S$` doubles — display units, deliberately not cents |
| Dashboard JSON | Browser | Cents served; `app.js` divides by 100 for display |

#### Why the Boundary Must Be Explicit

```java
// WRONG — two bugs in one line:
data.auctionTotalValue = rs.getLong("total_val");
// 1. Truncation: 1250.50 S$ → 1250
// 2. Unit error: stored as-if cents → renders as 12.50 S$ (100x too small)

// RIGHT:
data.auctionTotalValue = Math.round(rs.getDouble("total_val") * 100.0);
```

A `getLong()` on a `REAL` column silently truncates fractions **and** stores the wrong unit — two distinct bug classes in one call. The cents convention confines both to read boundaries, where they are visible and testable.

#### Scale Invariance

Not every metric needs conversion. Gini coefficient, top-1% share, money-to-goods ratio, and inflation percentages are ratios of same-unit values — multiplying all inputs by 100 cancels out. Only absolute figures (wealth, volume, values) carry unit risk, and those are exactly the figures converted at every read boundary.

---

## 13. Cross-Cutting: Dashboard JSON Contract

`DashboardDataBuilder.buildJson(engine)` produces the payload served at `/api/data` and (encrypted) via publishing. `app.js` reads exactly these field names.

```json
{
  "timestamp": 1756300000000,
  "server": { "name": "…", "fingerprint": "…" },
  "liveMetrics": {
    "dailyVolume": 0,
    "dailyTransactionCount": 0,
    "activePlayerCount": 0,
    "transactionsByType": { "SHOP_BUY": 0 }
  },
  "latestSnapshot": {
    "timestamp": 0, "type": "HOURLY",
    "totalWealth": 0, "playerCount": 0,
    "giniCoefficient": 0.0, "avgBalance": 0, "medianBalance": 0,
    "top1PercentShare": 0.0, "moneySupply": 0,
    "auctionActiveListings": 0, "auctionTotalValue": 0
  },
  "inflation": {
    "moneySupplyCents": 0, "goodsValueCents": 0,
    "moneyToGoodsRatio": 0.0, "status": "HEALTHY",
    "inflationRate24h": null, "inflationRate7d": null, "inflationRate30d": null
  },
  "healthScore": { "overallScore": 0, "grade": "…", "summary": "…",
    "giniScore": 0, "inflationScore": 0, "moneyGrowthScore": 0,
    "activityScore": 0, "liquidityScore": 0 },
  "fraudAlerts": [ { "timestamp": 0, "type": "…", "playerName": "…",
    "severity": "HIGH", "description": "…" } ],
  "dailyHistory": [ { "date": "2026-08-27", "transactionCount": 0,
    "transactionVolume": 0, "activePlayers": 0, "inflationRate": null } ],
  "topItems": {
    "bought": [ { "item": "DIAMOND", "quantity": 0 } ],
    "sold":   [ { "item": "DIAMOND", "quantity": 0 } ]
  }
}
```

**Nullability rules** — a consumer must tolerate `null` for:

- `latestSnapshot` — no snapshot taken yet (fresh install, or zero balances)
- `inflation` — calculator failed or no baseline snapshots exist
- `healthScore`, `fraudAlerts` — premium disabled, or license absent
- every `*Rate` field — no snapshot baseline within the window
- any numeric field that would have been `NaN`/`Infinity` — the builder maps non-finite doubles to `null` so a single corrupt value cannot invalidate the whole payload

**Text safety** — all strings pass through `escapeJson` (quotes, backslashes, control characters); monetary figures are served in cents to avoid floating-point formatting drift.

---

## 14. Thread Safety Model

### Thread Inventory

| Thread | Origin | Responsibilities |
|--------|--------|------------------|
| Server thread | Minecraft | Tick counters (`onServerTick`), command execution; never blocks |
| `Solidus-Analytics-Worker` | `AnalyticsDatabase` | All analytics DB I/O, snapshot computation, JSON build, initial fraud scan, live polling loop |
| NanoHTTPD pool | `AnalyticsWebServer` | Serve static files + cached JSON; no computation |
| Webhook executor | `DiscordWebhookNotifier` | Serialized webhook delivery |

### Safe Patterns

- **Lock-free counters** — `AtomicLong` for volume/count/cursor; `ConcurrentHashMap` for type/item/player maps; no `synchronized` on the hot path
- **Volatile snapshots** — `cachedData` (dashboard JSON) and `currentDate` are swapped wholesale; readers never see partial state
- **Serialized writes** — every analytics DB mutation runs on the single worker; the shutdown drain guarantees task-before-close ordering
- **Cross-database reads** — Core's files are opened per-operation with `PRAGMA query_only = ON`, so analytics never holds long-lived locks against Core's WAL

---

## 15. Database Schema

### Analytics Database: `config/solidus-analytics/analytics.db`

#### Table: `analytics_snapshots`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Snapshot ID |
| `timestamp` | INTEGER | NOT NULL | Epoch millis |
| `snapshot_type` | TEXT | NOT NULL | `HOURLY` / `DAILY` / custom |
| `total_wealth` | INTEGER | NOT NULL | Total wealth (cents) |
| `player_count` | INTEGER | NOT NULL | Known players |
| `gini_coefficient` | REAL | NOT NULL | Gini (0.0–1.0) |
| `avg_balance` | INTEGER | NOT NULL | Mean balance (cents) |
| `median_balance` | INTEGER | NOT NULL | Median balance (cents) |
| `top1_percent_share` | REAL | NOT NULL | Top-1% wealth share |
| `money_supply` | INTEGER | NOT NULL | Same as total wealth (cents) |
| `auction_active_listings` | INTEGER | NOT NULL | Active listings count |
| `auction_total_value` | INTEGER | NOT NULL | Active listing value (cents) |

```sql
CREATE INDEX IF NOT EXISTS idx_snapshots_type_time
    ON analytics_snapshots (snapshot_type, timestamp DESC);
```

#### Table: `analytics_daily_metrics`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `date` | TEXT | PRIMARY KEY | ISO date (UTC) |
| `transaction_count` | INTEGER | NOT NULL | Audit rows seen |
| `transaction_volume` | INTEGER | NOT NULL | Money movement (cents, mirrors excluded) |
| `shop_buy_count` | INTEGER | NOT NULL | SHOP_BUY rows |
| `shop_sell_count` | INTEGER | NOT NULL | SHOP_SELL rows |
| `auction_count` | INTEGER | NOT NULL | AUCTION_* rows |
| `pay_transfer_count` | INTEGER | NOT NULL | PAY_SEND rows |
| `new_players` | INTEGER | NOT NULL | Reserved |
| `active_players` | INTEGER | NOT NULL | Distinct UUIDs seen |
| `inflation_rate` | REAL | | Nullable daily inflation |
| `top_item_bought` | TEXT | | Nullable material name |
| `top_item_sold` | TEXT | | Nullable material name |

#### Table: `analytics_item_metrics`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `date` | TEXT | PK (compound) | ISO date (UTC) |
| `material` | TEXT | PK (compound) | Material registry key |
| `buy_count` | INTEGER | NOT NULL | Buy events |
| `sell_count` | INTEGER | NOT NULL | Sell events |
| `total_quantity` | INTEGER | NOT NULL | Items traded |
| `total_value` | INTEGER | NOT NULL | Value moved (cents) |

```sql
PRIMARY KEY (date, material)
```

#### Table: `analytics_metadata`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `key` | TEXT | PRIMARY KEY | Metadata key |
| `value` | TEXT | NOT NULL | Metadata value |

#### Retention

The periodic sweep (fixed 72,000-tick counter ≈ every 10 hours at 20 TPS) deletes rows older than `data.retention.days` from all three metric tables. `cleanup.interval.hours` is parsed and clamped for forward compatibility; the sweep cadence itself is currently fixed.

---

## 16. Configuration System

### Config Directory Structure

```
config/solidus-analytics/
├── analytics.properties    // Engine behavior (intervals, retention, Discord)
├── dashboard.properties    // Dashboard + publishing (generated on demand)
├── license.key             // Premium license (operator-provided, single line)
├── analytics.db            // Analytics SQLite database (+ WAL files)
└── reports/                // Weekly markdown reports
```

### `analytics.properties`

| Key | Default | Constraints | Description |
|-----|---------|-------------|-------------|
| `snapshot.interval.minutes` | 30 | ≥ 1 | Snapshot cadence |
| `polling.interval.seconds` | 30 | ≥ 5 | Transaction poll cadence |
| `data.retention.days` | 90 | ≥ 1 | Deletion horizon for metrics |
| `cleanup.interval.hours` | 24 | ≥ 1 | Reserved (sweep cadence is fixed) |
| `discord.enabled` | false | allowlist-gated | Master Discord switch |
| `discord.webhook.url` | — | HTTPS webhook allowlist | Discord endpoint |
| `discord.notify.fraud` | true | | Fraud alerts |
| `discord.notify.inflation` | true | | Inflation warnings |
| `discord.notify.daily_summary` | true | | Daily summaries |
| `discord.notify.health_score` | true | | Health-score alerts |
| `discord.health_score.threshold` | 50.0 | [0, 100], NaN→default | Alert threshold |
| `discord.fraud.min_severity` | HIGH | LOW/MEDIUM/HIGH | Minimum alert severity |

### `dashboard.properties`

| Key | Default | Constraints | Description |
|-----|---------|-------------|-------------|
| `webserver.enabled` | false | | Master dashboard switch |
| `webserver.port` | 9090 | | Listen port (loopback only) |
| `webserver.password_hash` | — | required to enable | PBKDF2 hash for Basic auth |
| `encryption.password_hash` | — | | Publishing vault hash |
| `github.enabled` | false | | Master publishing switch |
| `github.owner` / `github.repo` | — | charset-validated | Publish target |
| `github.branch` | main | `[A-Za-z0-9._/-]{1,200}` | Publish branch |
| `publish.interval.seconds` | 60 | ≥ 30 | Publish cadence |

### Environment Variables

| Variable | Purpose |
|----------|---------|
| `SOLIDUS_GITHUB_TOKEN` | GitHub credential for publishing (in-file tokens are ignored with a warning) |
| `SOLIDUS_DASHBOARD_PASSWORD` | Optional auto-unlock of the publishing vault on restart |

### Validation Model

`AnalyticsConfig.validateAndNormalize()` clamps every numeric to a sane range, falls back to defaults on parse errors, whitelists the fraud severity enum, and **force-disables Discord** unless the webhook URL matches `https://discord.com/api/webhooks/` or `https://discordapp.com/api/webhooks/`. Configuration changes take effect after restart.

---

## 17. Command Reference

| Command | Access | Description |
|---------|--------|-------------|
| `/analytics` | GameMaster | Overview: snapshot, live metrics, dashboard state |
| `/analytics wealth` | GameMaster | Total wealth and player count |
| `/analytics inflation` | GameMaster | Inflation indicators (24h) |
| `/analytics top items` | GameMaster | Most traded items today |
| `/analytics top buyers` / `top sellers` | GameMaster | Planned (requires per-player volume tracking) |
| `/analytics history [days]` | GameMaster | Daily metrics, 1–90 (default 7) |
| `/analytics snapshot` | Admin | Force snapshot |
| `/analytics export` | Admin | Export data |
| `/analytics health` | GameMaster *(premium)* | Health score breakdown |
| `/analytics fraud` / `fraud list` | GameMaster *(premium)* | Recent alerts |
| `/analytics fraud scan` | Admin *(premium)* | Run all detectors |
| `/analytics report weekly` | GameMaster | Generate weekly report |
| `/analytics license` | Admin | License status |
| `/analytics fingerprint` | Admin | Server fingerprint |
| `/analytics dashboard [status]` | Admin | Dashboard subsystem state |
| `/analytics dashboard setup <password>` | Admin | Set PBKDF2 password (auth + encryption) |
| `/analytics dashboard unlock <password>` | Admin | Unlock publishing vault |
| `/analytics dashboard github <owner> <repo>` | Admin | Set publish target |
| `/analytics dashboard publish` | Admin | Publish encrypted payload now |
| `/inflation [day\|week\|month]` | GameMaster | 24h/7d/30d inflation report |

Access levels are Fabric `PermissionLevel` defaults (`GAMEMASTERS` = level 2, `ADMINS` = level 3); premium commands additionally require an active license.

---

## 18. Testing Strategy

Analytics ships a JUnit 5 suite (`./gradlew clean test`) covering the highest-risk correctness areas with real SQLite databases in temporary directories — no Minecraft runtime required.

### Test Files

| Test | Coverage |
|------|----------|
| `AnalyticsConfigTest` | Property normalization: clamping, NaN threshold fallback, severity whitelist |
| `DashboardEncryptionTest` | Password hashing round-trip, payload encrypt/decrypt, wrong-password rejection |
| `AnalyticsDatabaseTest` | Initialization, snapshot insert + round-trip |
| `LiveMetricsTrackerPollingTest` | **P0 regression**: metrics collected when the transaction log starts empty; mirror rows (`PAY_RECEIVE`/`AUCTION_SOLD`) counted once in volume, fully in activity |
| `SnapshotSchedulerMoneyUnitsTest` | **P1 regression**: balances and auction value converted to cents; expired listings excluded from auction value |
| `FraudDetectorPrecisionTest` | **P2 regression**: fractional incomes (`300.25`) preserved through detection and alert text |

### Known Testing Gap

Unit tests validate engines against schema-accurate SQLite fixtures, but they are not a substitute for a runtime smoke test on a real Dedicated Server (startup ordering, Core-present/Core-absent transitions, dashboard auth flow, shutdown persistence). That scenario is documented in the README and remains the recommended pre-release verification step.

### CI Pipeline

Run locally or in CI with:

```bash
./gradlew clean test   # 8 tests, JUnit 5
./gradlew build        # produces build/libs/solidus-analytics-<version>.jar
```

---

## 19. Extension Points & Integration Hooks

### For External Analysis Tools

- `analytics.db` is standard SQLite (WAL) — query snapshots and daily metrics directly with any SQLite client while the server runs (WAL allows concurrent reads)
- All monetary columns are integer cents; divide by 100 for `S$`
- `/api/data` returns the full dashboard JSON contract (see section 13) with Basic auth

### For Dashboard Consumers

- The encrypted publishing payload decrypts with the operator password: parse `salt ‖ iv ‖ ciphertext`, derive the AES-256 key via PBKDF2 (210,000 iterations), verify the GCM tag
- Build rendering on the documented JSON contract; treat every nullable field as nullable

### For Future Solidus Ecosystem Modules

- The `SolidusIntegration` bridge is the single seam for Core access; when Core ships a stable public API, only this class changes
- Premium gating pattern: construct subsystems only after license verification; free code paths must not reference premium objects directly

---

## 20. Security Considerations

### Exploit & Exposure Prevention

| Vulnerability | Mitigation |
|---------------|------------|
| Dashboard exposed to network | Binds `127.0.0.1`; disabled by default; refuses to start without a password hash |
| Credential theft from config | Only PBKDF2-SHA-256 hashes stored (210,000 iterations, random salt); plaintext never persisted |
| Brute-force / probing | 401 with generic body; the setup command is never disclosed to unauthenticated callers |
| Clickjacking / MIME sniffing / caching | `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer` |
| Token leakage via Git | GitHub token read from `SOLIDUS_GITHUB_TOKEN` env only; legacy in-file token ignored with warning |
| Malicious webhook exfiltration | Discord allowlist (HTTPS `discord.com`/`discordapp.com` webhooks only); force-disable otherwise |
| Economy corruption by analytics | `PRAGMA query_only = ON` on every Core connection; analytics owns its write path only |
| SQL injection | Fully parameterized statements; publish target fields charset-validated before any request |
| Invalid JSON breaking the dashboard | Non-finite doubles (`NaN`/`Infinity`) mapped to `null`; all strings JSON-escaped |
| Publishing vault persistence | Vault locks on restart; auto-unlock only via explicit env var |

### Data Minimization

Analytics stores aggregated economic metrics only. Player names appear solely inside fraud alert descriptions and active-player counting (UUIDs in memory, not persisted as a player registry). No chat content, IPs, or item NBT is recorded.

---

## 21. Performance Characteristics

### Memory Usage

| Component | Footprint | Growth |
|-----------|-----------|--------|
| Live metric counters (`AtomicLong` maps) | ~100–200 bytes per active type/item | Bounded by distinct types/items per day |
| Active player set | ~60 bytes per active player | Reset at UTC midnight |
| Fraud alert ring | ≤ 100 alerts, fixed cap | Constant |
| Dashboard JSON cache | ~10–50 KB | Constant (volatile swap) |
| SQLite page cache | ~2 MB (`cache_size = -2000`) | Constant |
| Inflation cache | Single report, 5-min TTL | Constant |

### Operation Latency

| Operation | Latency | Explanation |
|-----------|---------|-------------|
| Tick handler | < 0.1ms | Integer counter comparisons only |
| Transaction poll (30s) | ~1–10ms | Indexed `id >` range scan on worker thread |
| Snapshot (30 min) | ~5–50ms | Two read-only queries + Gini (optimized variant > 1,000 players) |
| Dashboard request | < 1ms | Static resource or cached JSON on NanoHTTPD pool |
| JSON rebuild (publish cadence) | ~1–5ms | Reads cached report + counters; no Core queries on HTTP path |
| Retention sweep (~10h) | ~10–100ms | Three indexed `DELETE` statements on worker |

### Database Size Estimates

| Player base | `analytics.db` (90-day retention) |
|-------------|-----------------------------------|
| 50 players | < 5 MB |
| 500 players | ~10–30 MB |
| 5,000 players | ~50–150 MB |

Snapshots dominate: 48 `HOURLY` rows/day plus one `DAILY` row. The retention sweep bounds growth deterministically.

---

## 22. Glossary

| Term | Definition |
|------|------------|
| **S$** | Solidus currency symbol (Core's virtual currency) |
| **Cents** | Analytics' internal money unit: 1 S$ = 100 cents (`long`) |
| **Mirror row** | A transaction-log row recording the receiver side of a movement (`PAY_RECEIVE`, `AUCTION_SOLD`); excluded from volume, kept in activity counts |
| **Id cursor** | The `lastPolledId` position: poll rows with `id >` cursor; 0 is valid ("log was empty at seed") |
| **Cursor seeding** | `SELECT MAX(id)` at startup to avoid re-importing history |
| **WAL** | Write-Ahead Logging — SQLite journal mode allowing concurrent reads during writes; crash-safe |
| **Gini coefficient** | Wealth inequality measure, 0 (equal) to 1 (concentrated) |
| **Money-to-goods ratio** | Money supply ÷ goods value; drives the inflation status label |
| **Health score** | Premium weighted 0–100 composite of five economic factors |
| **Vault** | The unlock state guarding encrypted publishing; locked on restart |
| **PBKDF2** | Password-Based Key Derivation Function 2; 210,000 SHA-256 iterations here |
| **AES-256-GCM** | Authenticated encryption used for published payloads (128-bit tag) |
| **NanoHTTPD** | Embedded Java HTTP server powering the dashboard |
| **`PRAGMA query_only`** | SQLite connection mode that rejects all writes; used on every Core DB connection |
| **ISO week-based year** | `IsoFields.WEEK_BASED_YEAR`; week identity that stays correct across year boundaries |
| **DedicatedServerModInitializer** | Fabric entry point running only on dedicated servers |
| **`CompletableFuture`** | Java async result handle; analytics DB reads/writes return these from the worker |
| **`AtomicLong` / `ConcurrentHashMap`** | Lock-free primitives behind live metric counters |
| **`volatile`** | Java visibility keyword; guards cross-thread flags and the dashboard JSON swap |

---

> **For questions, issues, or contributions**, visit [github.com/mohd-gs/solidus-analytics](https://github.com/mohd-gs/solidus-analytics)  
> **Author**: MOHD_Gs | **License**: MIT | Part of the [Solidus Economy Ecosystem](https://github.com/mohd-gs)
