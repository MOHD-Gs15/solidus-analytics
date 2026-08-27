# Solidus Analytics

Server-side analytics, economy health scoring, inflation reporting, fraud signals, and an optional dashboard for Solidus on Minecraft Java 26.1.2.

## Status

This repository is a clean source reconstruction of the recovered `solidus-analytics` artifact. The original artifact was decompiled from a JAR, so the implementation is being rebuilt and verified rather than treated as authoritative source. The current build resolves Loom 1.16-SNAPSHOT to 1.16.3; Loom 1.17 can be adopted once its plugin marker is published in the configured repositories.

## Compatibility

| Component | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.4 or newer |
| Fabric API | 0.155.2+26.1.2 |
| Fabric Loom | 1.16-SNAPSHOT (resolved to 1.16.3) |
| Java | 25 or newer |

## Features

Solidus Analytics records economy snapshots, daily and item metrics, inflation indicators, inequality measurements, economy health scores, and fraud signals. It can optionally expose a local dashboard and publish selected dashboard files to a GitHub Pages repository. The Core dependency is optional at startup; when Core is unavailable, features that require Core data report an unavailable state rather than pretending that they are working.

## Secure defaults

The embedded web server is disabled by default and should be bound to localhost or protected by an HTTPS reverse proxy before being exposed externally. GitHub publishing and Discord notifications are disabled by default. Tokens and webhook URLs belong in the server configuration or environment-managed secret storage and must never be committed to Git.

The public dashboard is not a secret store. Enable encrypted publishing only when the dashboard consumer supports the project’s encrypted format, and never publish private player data to a public repository without reviewing the data policy first.

## Build

```bash
./gradlew clean test
./gradlew build
```

The final JAR is written to `build/libs`. Runtime data is created under `config/solidus-analytics` and is intentionally ignored by Git.

## Integration

Analytics discovers Solidus Core through a small compatibility bridge. Core integration is intentionally isolated so that future Solidus versions can provide a stable API without requiring direct access to Core’s SQLite internals. Until that API is available, treat the reflection bridge as compatibility code and test it against the exact Core version installed on the server.

## License

MIT. See `LICENSE`.
