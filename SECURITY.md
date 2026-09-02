# Security notes

## Secrets

Do not commit `SOLIDUS_GITHUB_TOKEN`, `SOLIDUS_LICENSE_PUBLIC_KEY` server values alongside the private signing key, `SOLIDUS_DASHBOARD_PASSWORD`, Discord webhook URLs, license files, server databases, or runtime logs. GitHub publishing reads `SOLIDUS_GITHUB_TOKEN` from the server environment and does not persist it in `dashboard.properties`.

If a secret is exposed, revoke it immediately and issue a replacement with the smallest possible scope. For GitHub publishing, `Contents: Read and write` on the target repository is normally sufficient; full-account permissions are not required.

## Dashboard exposure

The embedded dashboard binds to `127.0.0.1` and is disabled by default. Do not expose it directly to the public Internet. If remote access is required, put it behind an HTTPS reverse proxy with an additional access-control layer.

Basic auth on the dashboard is rate limited per IP: 5 failed attempts in a 60-second window trigger a 5-minute lockout for that source, and locked-out sources are rejected before any PBKDF2 derivation runs.

## Licensing

Licenses use the SA2 format (see `docs/LICENSE-SYSTEM.md` for the full spec):
`SA2.<base64url(payload)>.<base64url(Ed25519 signature)>` with the 6-field
payload `2|<customer>|<expiry YYYY-MM-DD or PERPETUAL>|<fingerprint 16-hex or ANY>|<product>|<nonce 16-hex>`.

Verification is asymmetric AND anchored to an EMBEDDED key (audit round 2):
the runtime verifier trusts ONLY the vendor public key compiled into the JAR
(`SA2_PUBLIC_KEY_B64` in `LicenseVerifier.java`, replaced by the vendor at
build time per LICENSE-SYSTEM.md §3). The `SOLIDUS_LICENSE_PUBLIC_KEY` env
var and `solidus.license.publicKey` system property are **ignored** in
production - a customer-settable verification key would allow self-signed
licenses (the exact SA1 flaw SA2 was designed to remove). While the embedded
key is still the placeholder, every SA2 key fails CLOSED with a clear log
message. The private signing key never leaves the issuer's machine
(`tools/license/SolidusLicenseTool.java`). Legacy SA1 keys and the retired
4-field dash-format `SA2-` keys are rejected outright.
