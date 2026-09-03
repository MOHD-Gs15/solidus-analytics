# Solidus License System — SA2 (Ed25519)

> **Status**: Implemented on `main` | **Scheme**: offline, asymmetric | **Dependency**: none (JDK standard library)

---

## 1. Why SA2 exists (the SA1 problem)

The legacy **SA1** scheme signs keys with **HMAC-SHA256** using a shared secret
(`SOLIDUS_LICENSE_SECRET`). A shared secret has a fatal flaw for distributed
software: **every verifying server must hold it**. Any customer can:

1. Read the secret from their own server's environment,
2. Run the same HMAC over a forged payload,
3. Produce unlimited valid keys for anyone.

**SA2 replaces the shared secret with an asymmetric signature.** Licenses are
signed with an Ed25519 **private key that never leaves the vendor machine**.
The mod ships only with the matching **public key**, which is mathematically
useless for signing. Forging a key now requires breaking Ed25519, not reading
an environment variable.

| Property | SA1 (legacy) | SA2 (current) |
|---|---|---|
| Algorithm | HMAC-SHA256 | Ed25519 |
| Secret location | Every customer server | Vendor machine only |
| Customer can forge keys | **Yes** | No |
| Works offline | Yes | Yes |
| Customer watermark | No | Yes (`customer` field) |
| Perpetual licenses | No (date required) | Yes (`PERPETUAL`) |

SA1 remains supported for backward compatibility; new issues should use SA2.

---

## 2. Key format

```
SA2.<base64url(payload)>.<base64url(ed25519-signature)>
```

Payload — pipe-separated, 6 fields, UTF-8:

| # | Field | Value | Notes |
|---|---|---|---|
| 1 | version | `2` | Format version |
| 2 | customer | free text, no `\|` | **Watermark** — identifies the buyer in every leaked key |
| 3 | expiry | `YYYY-MM-DD` or `PERPETUAL` | `PERPETUAL` = never expires |
| 4 | fingerprint | 16 hex chars or `ANY` | Output of `/analytics fingerprint`; `ANY` = universal (reseller) key |
| 5 | product | **must be exactly `analytics-premium`** | Product binding (audit 4): keys issued for any other Solidus product are rejected even with a valid vendor signature |
| 6 | nonce | 16 hex chars | Random — guarantees two identical orders never share a key |

Verification order: structure → Base64URL decode → **Ed25519 signature** →
payload version → expiry → fingerprint. Any failure maps onto the existing
`VerificationState` enum (`INVALID`, `EXPIRED`, `FINGERPRINT_MISMATCH`), so
`/analytics license` output works unchanged.

---

## 3. Key ceremony (do this ONCE, then guard the private key)

```bash
java tools/license/SolidusLicenseTool.java keygen --out keys/
```

* `keys/solidus_license_private_key.b64` — **the signing key**. Back it up
  offline (USB / password manager) and delete it from synced folders. Anyone
  holding it can forge licenses for every customer.
* `keys/solidus_license_public_key.b64` — safe to publish.

Then embed the public key into the mod: open
`src/main/java/com/solidus/analytics/license/LicenseVerifier.java`, replace

```java
private static final String SA2_PUBLIC_KEY_B64 = "REPLACE_WITH_YOUR_ED25519_PUBLIC_KEY";
```

with the Base64 line printed by `keygen`. Build and ship.

> If the placeholder is left in place, SA2 keys fail **closed** with a clear
> log message — the mod never accepts licenses against a missing key.

`.gitignore` already excludes `tools/license/keys/` and `*private_key*.b64`.

---

## 4. Issuing workflow (per sale)

1. Buyer pays (e.g. USDT) and runs `/analytics fingerprint` on their server.
2. Issue the license:

```bash
java tools/license/SolidusLicenseTool.java issue \
     --private-key keys/solidus_license_private_key.b64 \
     --customer "server-owner-42" \
     --fingerprint 1A2B3C4D5E6F7081 \
     --days 365
# alternatives: --until 2027-12-31 | --perpetual
# optional:     --product analytics-premium   --out issued/server-owner-42.key
```

3. Deliver the single `SA2.…` line to the buyer. They paste it into
   `config/solidus-analytics/license.key` (first non-comment line) and restart,
   or run `/analytics license` to re-verify.

### Verifying a key manually (support desk)

```bash
java tools/license/SolidusLicenseTool.java verify \
     --public-key keys/solidus_license_public_key.b64 \
     --license @issued/server-owner-42.key \
     --fingerprint 1A2B3C4D5E6F7081
```

Exit codes: `0` valid · `1` invalid · `3` expired · `4` fingerprint mismatch.

### Built-in correctness suite

```bash
java tools/license/SolidusLicenseTool.java selftest
```

Covers: valid dated/bound keys, perpetual `ANY` keys, tampered payloads,
wrong fingerprints, expired keys, forged signing keys, wrong prefixes, and
nonce uniqueness.

---

## 5. What SA2 does and does not protect against

**Mitigated**

* Key forgery without the private key (Ed25519).
* One license shared across many servers (fingerprint binding; `ANY` keys
  should be rare and tracked).
* Silent leaks — the `customer` watermark identifies whose key leaked.
* Key corruption / truncation (signature check).

**Not mitigated (accepted risk)**

* A determined attacker can patch the verifier out of the JAR bytecode.
  Mitigate in depth with ProGuard obfuscation, multiple deferred license
  checks, and by distributing **updates only through a license-gated
  channel** — updates are the real product for an actively developed mod.
* Clock rollback on the customer server can extend a dated license.
* Fingerprint spoofing — fingerprinting is an anti-sharing convenience, the
  signature is the actual security boundary.

---

## 6. Roadmap: online activation (planned)

When customer volume justifies it, add a Cloudflare Workers + D1 activation
endpoint (free tier, commercial use allowed) that:

1. Records `license → fingerprint` activations and detects abnormal sharing,
2. Returns a short-lived (72 h) Ed25519-signed token the mod caches locally,
3. Enables remote revocation, while the offline grace window keeps customer
   servers running if the API is unreachable.

SA2's embedded-public-key design is directly compatible with this: the
activation token would be signed by the same (or a rotated) key pair.
