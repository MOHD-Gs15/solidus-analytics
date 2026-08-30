# Source Provenance

## Why some files used to look "decompiled"

Until this cleanup, parts of this repository contained code recovered with
the CFR decompiler (0.152) rather than the original hand-written sources.
Evidence included CFR banner comments ("Decompiled with CFR 0.152"),
optimizer pragma comments ("Enabled aggressive block sorting"), decompiler
warnings ("WARNING - Removed try catching itself - possible behaviour
change"), and decompiler idioms in code bodies (redundant casts,
`indexOf(58)` instead of `indexOf(':')`, `new LinkOption[0]`, verbose
locals, etc.).

This is tracked as finding **R02** in the Solidus ecosystem audit
(`Solidus_Ecosystem_Audit_Report.md`).

## What this cleanup did (Phase 0/1)

- Stripped every CFR banner, optimizer pragma, and decompiler warning
  comment from the sources (comments only - no behavior change).
- Hand-cleaned the decompiler idioms out of files touched by security
  fixes (for example `LicenseVerifier`, `AnalyticsWebServer`).

## Known residual risk (documented, Phase 2)

The decompiler's own warnings flagged places where it may have altered
control flow (removed `try`/`synchronized` wrappers) compared to the
original sources. The shipped code is the source of truth and the test
suite exercises it, but the medium-term fix is to regenerate the original
sources from development history (or rewrite the affected modules) so that
the codebase is fully maintainable again. Keep release JARs and sources
in sync from one build pipeline going forward.
