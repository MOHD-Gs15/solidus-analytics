<h1 align="center">Solidus Analytics</h1>

<p align="center"><strong>See exactly where every coin on your Minecraft server goes</strong><br>
Live economy monitoring, a password-protected web dashboard, and automatic fraud detection for the Solidus ecosystem.</p>

<p align="center">
  <a href="https://github.com/MOHD-Gs15/solidus-analytics/actions/workflows/test.yml"><img src="https://github.com/MOHD-Gs15/solidus-analytics/actions/workflows/test.yml/badge.svg" alt="Tests"></a>
  <a href="https://github.com/MOHD-Gs15/solidus-analytics/actions/workflows/codeql.yml"><img src="https://github.com/MOHD-Gs15/solidus-analytics/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
  <img src="https://img.shields.io/badge/version-2.1.5-blue" alt="Version 2.1.5">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2-brightgreen" alt="Minecraft 26.1.2">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT License"></a>
  <a href="https://github.com/MOHD-Gs15"><img src="https://img.shields.io/badge/mod%20by-MOHD--Gs-6f42c1" alt="Mod by MOHD-Gs"></a>
</p>

**Solidus Analytics** is a server-side Fabric mod for Minecraft 26.1.2 that watches every financial transaction on a server running the [Solidus](https://github.com/MOHD-Gs15/solidus-core) economy mod and turns it into clear, live insight: who is getting rich, which items actually trade, whether prices are inflating, and — with the premium features — whether anyone is cheating the economy. It renders its web dashboard as lightweight SVG gauges straight from a built-in web server, with **no external database and no third-party service**: everything runs inside your server and stays yours. Free and open source under the MIT license.

## Why server owners pick Solidus Analytics

- **Answers the question every owner asks** — where is the money going, and is the economy healthy?
- **Zero external dependencies** — no cloud account, no analytics SaaS; the data never leaves your machine unless you choose to publish it.
- **Live from second one** — collection starts automatically after installation, with no configuration.
- **Built for Solidus** — reads the economy through Solidus Core's own connection (SQLite or MySQL), not by poking files behind its back.

## Features

- **Live monitoring** — transaction volume, operation counts, and the economy's pulse, refreshed every few seconds.
- **In-game instant metrics** — wealth distribution, most-traded items, top buyers and sellers, and inflation rates (day/week/month) directly from commands.
- **Password-protected web dashboard** — a numeric dashboard with charts served by a built-in web server; disabled by default and bound to localhost for safety.
- **Publish-to-GitHub hosting** — link a repository and the dashboard data file is pushed there automatically, giving you free hosting for the dashboard without opening a single port.
- **Historical snapshots & export** — periodically capture the economy's state and export data for external analysis.
- **Fraud detection** *(premium license)* — three automatic detectors: unexplained rapid wealth gain, circular trading between linked accounts, and zero-value transfers.
- **Economy health score** *(premium license)* — a 0–100 score combining five weighted factors: wealth inequality (Gini), inflation, money-supply growth, activity, and market liquidity.
- **Weekly reports & Discord notifications** *(premium license)* — a recurring digest delivered straight to your Discord channel.
- **Optional cloud layer** — a built-in, security-first relay service for watching a whole server network, with a mobile-friendly PWA, instant notifications, and per-server alert rules.

## Commands

All commands require GAMEMASTERS-level permission unless noted otherwise.

| Command | What it does |
|---------|--------------|
| `/analytics` | Overall economy status |
| `/analytics wealth` | Wealth distribution between players |
| `/analytics inflation` | Current inflation rate |
| `/analytics top items\|buyers\|sellers` | Most-traded items, best buyers/sellers |
| `/analytics history [days]` | Historical summary (1–90 days, default 7) |
| `/inflation [day\|week\|month]` | Inflation rate for a specific period |
| `/analytics snapshot` *(admins)* | Take an instant economy snapshot |
| `/analytics export` *(admins)* | Export collected data |
| `/analytics dashboard setup <password>` | Enable the web dashboard with a password |
| `/analytics dashboard unlock <password>` | Unlock the dashboard for this session |
| `/analytics dashboard setupfile <file>` · `unlockfile <file>` | Same, but reads the password from a file (safer) |
| `/analytics dashboard github <owner> <repo>` | Link a GitHub repository for dashboard publishing |
| `/analytics dashboard publish` | Push the dashboard data to GitHub now |
| `/analytics license` | Premium-feature license status |
| `/analytics health` *(license)* | The 0–100 economy health score |
| `/analytics fraud list\|scan` *(license; scan for admins)* | View fraud alerts / run a full scan |
| `/analytics fingerprint` *(license)* | A player's financial behavior fingerprint |
| `/analytics report weekly` *(license)* | Generate the weekly report |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) on your server (Minecraft 26.1.2).
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and the [Solidus Core](https://github.com/MOHD-Gs15/solidus-core) mod into `mods` — Analytics is an add-on to Core, not a replacement.
3. Drop `solidus-analytics-2.1.5.jar` into `mods`.
4. Start the server — data collection begins immediately with no configuration.

> Server-side only. Analytics reads the economy through Solidus's own storage connection, whether it runs SQLite or MySQL.

## Quick configuration

Files are created under `config/solidus-analytics/` on first start. The things you may want:

- **Web dashboard** — disabled by default (the secure choice). To use it, enable `webserver.enabled` in the config (default port `9090`, bound to `127.0.0.1`), then reach it through an SSH tunnel or a reverse proxy before exposing it to the internet.
- **GitHub publishing** — create a fine-grained token limited to a single repository, then `/analytics dashboard github <owner> <repo>` and the data file publishes automatically.
- **Premium & cloud features** — see [docs/LICENSE-SYSTEM.md](docs/LICENSE-SYSTEM.md) and [docs/cloud/PROTOCOL.md](docs/cloud/PROTOCOL.md).

## For advanced users

**Pipeline.** The collectors (`LiveMetricsTracker`, `SnapshotScheduler`, `WealthDistributionProvider`, `InflationCalculator`) read the transaction ledger through `CoreLedgerAccess` — a bridge that routes through Core's own connection in MySQL mode (no duplicate connection pools) and falls back to direct read-only file access in SQLite mode. The live loop runs on a dedicated thread, isolated from the database save queue.

**Volume accounting.** An explicit accounting table prevents double counting: escrow-held bid amounts are not counted as volume, auctions are counted once at settlement, and receiving mirrors are excluded.

**Security.** Passwords are stored with scrypt; sessions are bounded; login and WebSocket endpoints are rate-limited; data files carry `0600` permissions; and the full SA security-audit register is documented in the changelog. The optional `cloud-relay` service is a standalone Node.js app (single dependency: `ws`) that sits between your server and supervisors: device pairing, a strict args schema that rejects unknown input, SSRF-guarded webhook delivery, tenant-isolated alert rules, and a PWA for phone monitoring.

**Testing.** The Java suite (19 test classes, 91 tests) runs per push against a real `mariadb:11` service container, and the cloud relay runs its own end-to-end Node suite (smoke + security + restart-durability). Build locally with JDK 25: `./gradlew build`; run the relay tests with `npm test` inside `cloud-relay/`.

## Documentation

| Document | Contents |
|----------|----------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Engine architecture, pipeline, volume accounting table, ledger read path |
| [docs/LICENSE-SYSTEM.md](docs/LICENSE-SYSTEM.md) | Premium license system: activation, verification, time anchoring |
| [docs/cloud/PROTOCOL.md](docs/cloud/PROTOCOL.md) | Cloud relay protocol: pairing, messages, error codes |
| [cloud-relay/README.md](cloud-relay/README.md) | Running the cloud relay service and host configuration |
| [VERSIONING.md](VERSIONING.md) | Version policy and 2.1.x family compatibility |
| [SECURITY.md](SECURITY.md) | Security reporting policy |
| [PROVENANCE.md](PROVENANCE.md) | Code origin and provenance |

## The Solidus family

| Mod | What it adds | Repository |
|-----|--------------|------------|
| **Solidus Core** | The economy engine itself | [MOHD-Gs15/solidus-core](https://github.com/MOHD-Gs15/solidus-core) |
| **Solidus Analytics** (this repo) | Monitoring, dashboards, fraud detection | [MOHD-Gs15/solidus-analytics](https://github.com/MOHD-Gs15/solidus-analytics) |
| **Solidus Governance** | Taxes, limits, policies, audits, backups, recovery | [MOHD-Gs15/Solidus-Governance](https://github.com/MOHD-Gs15/Solidus-Governance) |
| **Solidus Enforcer** | Bounties, hunter licenses, anti-exploit enforcement | [MOHD-Gs15/Solidus-Enforcer](https://github.com/MOHD-Gs15/Solidus-Enforcer) |

## License & credits

- **Mod by [MOHD-Gs](https://github.com/MOHD-Gs15)**
- Licensed under the [MIT License](LICENSE) — free to use, modify, and ship with your server.
