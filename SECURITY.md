# Security notes

## Secrets

Do not commit `SOLIDUS_GITHUB_TOKEN`, `SOLIDUS_LICENSE_PUBLIC_KEY` server values alongside the private signing key, `SOLIDUS_DASHBOARD_PASSWORD`, Discord webhook URLs, license files, server databases, or runtime logs. GitHub publishing reads `SOLIDUS_GITHUB_TOKEN` from the server environment and does not persist it in `dashboard.properties`.

If a secret is exposed, revoke it immediately and issue a replacement with the smallest possible scope. For GitHub publishing, `Contents: Read and write` on the target repository is normally sufficient; full-account permissions are not required.

## Dashboard exposure

The embedded dashboard binds to `127.0.0.1` and is disabled by default. Do not expose it directly to the public Internet. If remote access is required, put it behind an HTTPS reverse proxy with an additional access-control layer.

Basic auth on the dashboard is rate limited per IP: 5 failed attempts in a 60-second window trigger a 5-minute lockout for that source, and locked-out sources are rejected before any PBKDF2 derivation runs.

## Licensing

Licenses use the SA2 format: `SA2-<base64(payload)>-<base64(Ed25519 signature)>` with payload `2|<licensee>|<expiry ISO-8601>|<fingerprint|ANY>`.

Verification is asymmetric: the runtime verifier only needs the PUBLIC Ed25519 key via `SOLIDUS_LICENSE_PUBLIC_KEY` (base64 X.509 SubjectPublicKeyInfo). The private signing key never leaves the issuer's machine and is used exclusively by `tools/LicenseIssuer.java` on a trusted, offline-capable machine. A private key must never be included in the public mod JAR or repository, and legacy SA1 keys (which required a client-held HMAC secret and were forgeable by design) are rejected outright.
