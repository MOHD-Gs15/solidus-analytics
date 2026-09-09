# Versioning

The four Solidus ecosystem mods — **Core**, **Analytics**, **Governance**, and **Enforcer** —
share one **family version**. The family version is the integration contract: it tells a
server owner, at a glance, which releases are built and tested to work together.

| Bump | Meaning | Can you update one mod alone? |
|------|---------|-------------------------------|
| **Patch** `2.1.0 → 2.1.1` | Bug fixes and safe improvements. | Yes — any `2.1.x` works with any other `2.1.y`. |
| **Minor** `2.1.x → 2.2.0` | Breaking change: API, hook signature, config schema, or database layout. | No — the other mods must move to the `2.2` family in lockstep. |
| **Major** `2.x → 3.0.0` | Architectural reset of the ecosystem contract. | No — full coordinated release. |

Current family: **2.1.5** — Core, Analytics, Governance, and Enforcer are aligned on the 2.1.x family (Core rides its own 2.2.x network family per VERSIONING.md; the reflection bridge tolerates both). 2.1.5 closes the SA2 security-audit round: one high (unauthenticated relay crash via hostile request line) and ten medium findings fixed across the cloud-relay (crash-proof request parsing, args-schema enforcement, bounded audit rows + reject budget, SSRF-guarded push endpoints, tenant-isolated alert rules, proxy-aware login lockout, hot-reload of externally edited accounts, connection/token caps, the D-class CONFIRM contract) plus hardening in the mod (pairing-secret redaction, enforced key-file permissions, a monotonic license-clock anchor) and supply-chain coverage (npm Dependabot, JS CodeQL, SHA-pinned actions).

Each mod's `fabric.mod.json` `suggests` entry declares the **minimum family version** it
was integration-tested against (e.g. `"solidus": ">=2.1.0"`).

## Where the number lives

Each mod's version has exactly one source of truth — a single line in `gradle.properties`:

```properties
mod_version = 2.1.0
```

`fabric.mod.json` picks it up through the `${version}` expansion at build time — never
edit it by hand. The jar filename, the `Implementation-Version` manifest attribute, and
the version the Cloud agent reports in `health.meta` all flow from the same line.

## Versioned separately

Two cloud components keep their own version families, on purpose — they release on
their own cadence and must stay compatible across mod patch releases:

| Component | Version | Contract |
|-----------|---------|----------|
| Cloud relay (`cloud-relay/` in Solidus-analytics, Node.js) | `0.x` | see `cloud-relay/README.md` |
| Cloud wire protocol | `1.x` | see `docs/cloud/PROTOCOL.md` §14 (Versioning & Compatibility) |
