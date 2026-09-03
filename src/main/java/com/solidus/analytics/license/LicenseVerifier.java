package com.solidus.analytics.license;

import com.solidus.analytics.SolidusAnalyticsMod;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import net.fabricmc.loader.api.FabricLoader;

/**
 * LicenseVerifier - offline Ed25519 license verification (key format SA2).
 *
 * <p><b>Why SA2 exists:</b> SA1 keys were signed with HMAC-SHA256 using a
 * secret that had to be present on every customer server via the
 * {@code SOLIDUS_LICENSE_SECRET} environment variable. Because that secret
 * shipped to the very party being verified, any buyer could mint unlimited
 * valid keys offline. SA2 replaces the symmetric scheme with an asymmetric
 * Ed25519 signature: the private key never leaves the issuer's machine and
 * the shipped mod embeds only the <b>public</b> verification key, which
 * cannot sign.</p>
 *
 * <p><b>Key format (LICENSE-SYSTEM.md &sect;2):</b>
 * {@code SA2.<base64url(payload)>.<base64url(Ed25519 signature)>}<br>
 * <b>Payload (6 pipe-separated fields):</b>
 * {@code 2|<customer>|<expiry YYYY-MM-DD or PERPETUAL>|<fingerprint 16-hex or ANY>|<product>|<nonce 16-hex>}</p>
 *
 * <p><b>Product binding (audit 4 / F-2):</b> the payload's product field
 * must equal {@link #EXPECTED_PRODUCT} exactly. A license the vendor
 * signed for a DIFFERENT Solidus product (e.g. {@code governance-premium})
 * does NOT activate Analytics premium - without this check one purchased
 * license would unlock every product in the family (cross-product
 * replay). Same hardening the Governance 2.1.1 verifier already carries.</p>
 *
 * <p><b>Trust anchor:</b> the vendor public key is the compile-time constant
 * {@link #SA2_PUBLIC_KEY_B64} embedded in the JAR (LICENSE-SYSTEM.md &sect;3).
 * The legacy {@code SOLIDUS_LICENSE_PUBLIC_KEY} env var and
 * {@code solidus.license.publicKey} system property are <b>ignored</b> in
 * production: letting the customer supply the verification key would
 * re-create the SA1 forgery problem (any buyer could self-sign licenses).
 * A test-only injection point exists for the unit tests (package-private).</p>
 *
 * <p>Legacy {@code SA2-<base64>-<base64>} (4-field, dash-separated) keys and
 * all SA1 keys are rejected outright: the dash format predates the embedded
 * key and its only issuance workflow distributed a customer-settable key.</p>
 */
public final class LicenseVerifier {
    public static final String KEY_PREFIX = "SA2";
    public static final int PAYLOAD_VERSION = 2;
    public static final int PAYLOAD_FIELDS = 6;
    public static final String PERPETUAL = "PERPETUAL";

    /**
     * The one and only product this mod accepts licenses for
     * (LICENSE-SYSTEM.md &sect;2 field 5: "e.g. analytics-premium").
     * Licenses issued for any other product string are rejected even when
     * the vendor signature itself is valid.
     */
    public static final String EXPECTED_PRODUCT = "analytics-premium";

    /**
     * Vendor Ed25519 public key (base64 X.509 SubjectPublicKeyInfo).
     * Replace this placeholder with the key printed by
     * {@code java tools/license/SolidusLicenseTool.java keygen} before a
     * release build (LICENSE-SYSTEM.md &sect;3). While the placeholder is in
     * place every SA2 key fails CLOSED with a clear log message - the mod
     * never accepts licenses against a missing key.
     */
    static final String SA2_PUBLIC_KEY_B64 = "REPLACE_WITH_YOUR_ED25519_PUBLIC_KEY";

    /** Kept for log clarity only: the env var is ignored (see class docs). */
    public static final String PUBLIC_KEY_ENV = "SOLIDUS_LICENSE_PUBLIC_KEY";
    /** Kept for log clarity only: the system property is ignored (see class docs). */
    public static final String PUBLIC_KEY_PROPERTY = "solidus.license.publicKey";

    /** Test-only key injection (used by LicenseVerifierTest). Never set in production code. */
    static volatile String testPublicKeyB64 = null;

    private final Path licenseKeyPath;
    private volatile VerificationState state = VerificationState.UNVERIFIED;
    private volatile String licenseeName;
    private volatile LocalDate expiryDate;
    private volatile boolean perpetual;
    private volatile String fingerprint;
    private volatile String errorMessage;

    public LicenseVerifier(Path configDir) {
        this.licenseKeyPath = configDir.resolve("license.key");
    }

    public VerificationState initialize() {
        SolidusAnalyticsMod.LOGGER.info("Verifying Solidus Analytics license...");
        this.warnOnLegacyKeyOverride();
        String rawKey = this.readLicenseKey();
        if (rawKey == null) {
            this.state = VerificationState.INVALID;
            this.errorMessage = "No license key found. Place your key in " + this.licenseKeyPath;
            SolidusAnalyticsMod.LOGGER.error(this.errorMessage);
            SolidusAnalyticsMod.LOGGER.error(
                "Solidus Analytics Premium requires a valid license key. Create the file '{}' with your license key on a single line.",
                this.licenseKeyPath);
            return this.state;
        }
        this.state = this.verifyLocally(rawKey);
        if (this.state == VerificationState.VERIFIED) {
            SolidusAnalyticsMod.LOGGER.info("License verified for: {} (expires: {})",
                this.licenseeName, this.perpetual ? "never (perpetual)" : this.expiryDate);
            if ("ANY".equals(this.fingerprint)) {
                SolidusAnalyticsMod.LOGGER.info("License type: Universal (any server)");
            } else {
                SolidusAnalyticsMod.LOGGER.info("License type: Server-specific (fingerprint: {}...)",
                    this.fingerprint.substring(0, Math.min(8, this.fingerprint.length())));
            }
        } else {
            SolidusAnalyticsMod.LOGGER.warn("License verification failed: {}", this.errorMessage);
        }
        return this.state;
    }

    public void shutdown() {
    }

    public boolean isPremiumEnabled() {
        if (this.state != VerificationState.VERIFIED) {
            return false;
        }
        if (this.expiryDate != null && LocalDate.now(ZoneOffset.UTC).isAfter(this.expiryDate)) {
            this.state = VerificationState.EXPIRED;
            this.errorMessage = "License expired on " + this.expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            SolidusAnalyticsMod.LOGGER.warn("License has expired: {}", this.errorMessage);
            return false;
        }
        return true;
    }

    public VerificationState getState() {
        return this.state;
    }

    public String getLicenseeName() {
        return this.licenseeName;
    }

    public LocalDate getExpiryDate() {
        return this.expiryDate;
    }

    public boolean isPerpetual() {
        return this.perpetual;
    }

    public String getFingerprint() {
        return this.fingerprint;
    }

    public long getDaysRemaining() {
        if (this.perpetual) {
            return Long.MAX_VALUE;
        }
        if (this.expiryDate == null) {
            return -1L;
        }
        long days = ChronoUnit.DAYS.between(LocalDate.now(ZoneOffset.UTC), this.expiryDate);
        return Math.max(0L, days);
    }

    public String getErrorMessage() {
        return this.errorMessage;
    }

    public VerificationState forceReverify() {
        SolidusAnalyticsMod.LOGGER.info("Re-verifying license...");
        String rawKey = this.readLicenseKey();
        if (rawKey == null) {
            this.state = VerificationState.INVALID;
            this.errorMessage = "License key file not found";
            return this.state;
        }
        this.state = this.verifyLocally(rawKey);
        SolidusAnalyticsMod.LOGGER.info("Re-verification result: {} — {}",
            this.state, this.errorMessage != null ? this.errorMessage : "OK");
        return this.state;
    }

    public static String computeServerFingerprint() {
        try {
            String raw = "";
            try {
                raw = raw + FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
            } catch (Exception ignored) {
                // FabricLoader unavailable (e.g. plain unit tests) - hash what we have.
            }
            raw = raw + InetAddress.getLocalHost().getHostName();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16).toUpperCase();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String readLicenseKey() {
        try {
            if (!Files.exists(this.licenseKeyPath, new LinkOption[0])) {
                return null;
            }
            String key = Files.readString(this.licenseKeyPath).trim();
            for (String line : key.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                return trimmed;
            }
            return null;
        } catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to read license key file", e);
            return null;
        }
    }

    private void warnOnLegacyKeyOverride() {
        // The key a customer could set must never become the trust anchor.
        // Detect the legacy override channels and say so, loudly, exactly once per init.
        if (System.getProperty(PUBLIC_KEY_PROPERTY) != null || System.getenv(PUBLIC_KEY_ENV) != null) {
            SolidusAnalyticsMod.LOGGER.warn(
                "{} / {} are no longer used: the embedded vendor public key is the only license trust anchor. "
                    + "A customer-settable verification key would allow self-signed licenses (the SA1 flaw).",
                PUBLIC_KEY_PROPERTY, PUBLIC_KEY_ENV);
        }
    }

    private VerificationState verifyLocally(String rawKey) {
        if (!rawKey.startsWith(KEY_PREFIX + ".")) {
            this.errorMessage = rawKey.startsWith(KEY_PREFIX + "-")
                ? "Legacy SA2- dash-format keys are retired; request a current SA2. key"
                : "Invalid key format. Expected " + KEY_PREFIX + ".<payload>.<signature> "
                    + "(SA1 keys are no longer accepted - they were forgeable by design)";
            return VerificationState.INVALID;
        }
        String body = rawKey.substring(KEY_PREFIX.length() + 1);
        int firstDot = body.indexOf('.');
        int lastDot = body.lastIndexOf('.');
        if (firstDot < 0 || firstDot != lastDot) {
            this.errorMessage = "Invalid key structure (expected exactly two '.' separators)";
            return VerificationState.INVALID;
        }
        String payloadBase64 = body.substring(0, firstDot);
        String signatureBase64 = body.substring(firstDot + 1);
        String payload;
        byte[] providedSignature;
        try {
            payload = new String(decodeBase64Url(payloadBase64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            this.errorMessage = "Invalid key encoding (payload not valid Base64URL)";
            return VerificationState.INVALID;
        }
        try {
            providedSignature = decodeBase64Url(signatureBase64);
        } catch (IllegalArgumentException e) {
            this.errorMessage = "Invalid key encoding (signature not valid Base64URL)";
            return VerificationState.INVALID;
        }
        if (!verifySignature(payload, providedSignature)) {
            if (this.errorMessage == null) {
                this.errorMessage = "Invalid license key (signature mismatch — key may be forged or corrupted)";
            }
            return VerificationState.INVALID;
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != PAYLOAD_FIELDS) {
            this.errorMessage = "Invalid key payload structure (expected " + PAYLOAD_FIELDS + " fields, got " + fields.length + ")";
            return VerificationState.INVALID;
        }
        int keyVersion;
        try {
            keyVersion = Integer.parseInt(fields[0]);
        } catch (NumberFormatException e) {
            this.errorMessage = "Invalid key version";
            return VerificationState.INVALID;
        }
        if (keyVersion != PAYLOAD_VERSION) {
            this.errorMessage = "Unsupported key version: " + keyVersion + " (expected: " + PAYLOAD_VERSION + ")";
            return VerificationState.INVALID;
        }
        this.licenseeName = fields[1];
        if (this.licenseeName.isBlank()) {
            this.errorMessage = "Invalid key payload (customer field is empty)";
            return VerificationState.INVALID;
        }
        this.perpetual = PERPETUAL.equals(fields[2]);
        if (this.perpetual) {
            this.expiryDate = null;
        } else {
            try {
                this.expiryDate = LocalDate.parse(fields[2], DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                this.errorMessage = "Invalid expiry date in key: " + fields[2]
                    + " (expected YYYY-MM-DD or " + PERPETUAL + ")";
                return VerificationState.INVALID;
            }
            if (LocalDate.now(ZoneOffset.UTC).isAfter(this.expiryDate)) {
                this.errorMessage = "License expired on " + this.expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
                return VerificationState.EXPIRED;
            }
        }
        this.fingerprint = fields[3];
        if (!"ANY".equals(this.fingerprint) && !this.fingerprint.matches("[0-9A-Fa-f]{16}")) {
            this.errorMessage = "Invalid fingerprint field (expected 16 hex chars or ANY)";
            return VerificationState.INVALID;
        }
        if (!LicenseVerifier.EXPECTED_PRODUCT.equals(fields[4])) {
            this.errorMessage = "License is for product '" + fields[4] + "' - this mod only accepts '"
                + LicenseVerifier.EXPECTED_PRODUCT + "'";
            return VerificationState.INVALID;
        }
        if (!fields[5].matches("[0-9A-Fa-f]{16}")) {
            this.errorMessage = "Invalid key payload (nonce must be 16 hex chars)";
            return VerificationState.INVALID;
        }
        if (!"ANY".equals(this.fingerprint) && !this.fingerprint.equalsIgnoreCase(computeServerFingerprint())) {
            this.errorMessage = "This license is tied to a different server. Expected: "
                + this.fingerprint + ", Got: " + computeServerFingerprint();
            return VerificationState.FINGERPRINT_MISMATCH;
        }
        this.errorMessage = null;
        return VerificationState.VERIFIED;
    }

    /** Base64URL without padding, per LICENSE-SYSTEM.md (decoder tolerates padding). */
    private static byte[] decodeBase64Url(String s) {
        return Base64.getUrlDecoder().decode(s.trim());
    }

    /**
     * Verifies the Ed25519 signature over the payload using the embedded
     * vendor public key. Fail-closed: a missing or malformed key disables
     * premium verification entirely rather than trusting the key.
     */
    private boolean verifySignature(String payload, byte[] providedSignature) {
        byte[] publicKeyBytes = getPublicKeyBytes();
        if (publicKeyBytes == null) {
            this.errorMessage = "No vendor public key is embedded in this build "
                + "(SA2_PUBLIC_KEY_B64 placeholder). Premium verification is disabled; "
                + "the vendor must embed the key per docs/LICENSE-SYSTEM.md §3.";
            return false;
        }
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(providedSignature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The ONLY production key source is the embedded constant. The test hook
     * exists solely so unit tests can exercise the verify path before the
     * vendor has generated a real key pair.
     */
    private static byte[] getPublicKeyBytes() {
        String encoded = testPublicKeyB64 != null && !testPublicKeyB64.isBlank()
            ? testPublicKeyB64
            : SA2_PUBLIC_KEY_B64;
        if (encoded == null || encoded.isBlank() || encoded.startsWith("REPLACE_WITH")) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public enum VerificationState {
        UNVERIFIED,
        VERIFIED,
        INVALID,
        EXPIRED,
        FINGERPRINT_MISMATCH
    }
}
