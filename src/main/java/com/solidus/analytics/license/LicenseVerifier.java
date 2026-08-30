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
 * customers only receive the public verification key, which cannot sign.</p>
 *
 * <p><b>Key format:</b> {@code SA2-<base64(payload)>-<base64(Ed25519 signature)>}<br>
 * <b>Payload:</b> {@code 2|<licensee>|<expiry ISO-8601>|<fingerprint or ANY>}<br>
 * <b>Public key:</b> base64(X.509 SubjectPublicKeyInfo) provided via the
 * {@code SOLIDUS_LICENSE_PUBLIC_KEY} environment variable (or the
 * {@code solidus.license.publicKey} system property, mainly for tests).<br>
 * <b>Issuer tool:</b> {@code tools/LicenseIssuer.java} in the repository.</p>
 *
 * <p>SA1 keys are rejected outright: they were forgeable by design and there
 * is no safe way to honor them.</p>
 */
public final class LicenseVerifier {
    public static final String KEY_PREFIX = "SA2";
    public static final int PAYLOAD_VERSION = 2;
    public static final String PUBLIC_KEY_ENV = "SOLIDUS_LICENSE_PUBLIC_KEY";
    public static final String PUBLIC_KEY_PROPERTY = "solidus.license.publicKey";

    private final Path licenseKeyPath;
    private volatile VerificationState state = VerificationState.UNVERIFIED;
    private volatile String licenseeName;
    private volatile LocalDate expiryDate;
    private volatile String fingerprint;
    private volatile String errorMessage;

    public LicenseVerifier(Path configDir) {
        this.licenseKeyPath = configDir.resolve("license.key");
    }

    public VerificationState initialize() {
        SolidusAnalyticsMod.LOGGER.info("Verifying Solidus Analytics license...");
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
            SolidusAnalyticsMod.LOGGER.info("License verified for: {} (expires: {})", this.licenseeName, this.expiryDate);
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
        if (this.expiryDate != null && LocalDate.now().isAfter(this.expiryDate)) {
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

    public String getFingerprint() {
        return this.fingerprint;
    }

    public long getDaysRemaining() {
        if (this.expiryDate == null) {
            return -1L;
        }
        long days = ChronoUnit.DAYS.between(LocalDate.now(), this.expiryDate);
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

    private VerificationState verifyLocally(String rawKey) {
        if (!rawKey.startsWith(KEY_PREFIX + "-")) {
            this.errorMessage = "Invalid key format. Expected " + KEY_PREFIX + "-... "
                + "(SA1 keys are no longer accepted - they were forgeable by design; request a new SA2 key)";
            return VerificationState.INVALID;
        }
        String body = rawKey.substring(KEY_PREFIX.length() + 1);
        int lastDash = body.lastIndexOf('-');
        if (lastDash < 0) {
            this.errorMessage = "Invalid key structure (missing signature separator)";
            return VerificationState.INVALID;
        }
        String payloadBase64 = body.substring(0, lastDash);
        String signatureBase64 = body.substring(lastDash + 1);
        String payload;
        byte[] providedSignature;
        try {
            payload = new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            this.errorMessage = "Invalid key encoding (payload not valid Base64)";
            return VerificationState.INVALID;
        }
        try {
            providedSignature = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException e) {
            this.errorMessage = "Invalid key encoding (signature not valid Base64)";
            return VerificationState.INVALID;
        }
        if (!verifySignature(payload, providedSignature)) {
            if (this.errorMessage == null) {
                this.errorMessage = "Invalid license key (signature mismatch — key may be forged or corrupted)";
            }
            return VerificationState.INVALID;
        }
        String[] fields = payload.split("\\|", 4);
        if (fields.length != 4) {
            this.errorMessage = "Invalid key payload structure (expected 4 fields, got " + fields.length + ")";
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
        try {
            this.expiryDate = LocalDate.parse(fields[2], DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            this.errorMessage = "Invalid expiry date in key: " + fields[2];
            return VerificationState.INVALID;
        }
        this.fingerprint = fields[3];
        if (LocalDate.now().isAfter(this.expiryDate)) {
            this.errorMessage = "License expired on " + this.expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            return VerificationState.EXPIRED;
        }
        if (!"ANY".equals(this.fingerprint) && !this.fingerprint.equalsIgnoreCase(computeServerFingerprint())) {
            this.errorMessage = "This license is tied to a different server. Expected: "
                + this.fingerprint + ", Got: " + computeServerFingerprint();
            return VerificationState.FINGERPRINT_MISMATCH;
        }
        this.errorMessage = null;
        return VerificationState.VERIFIED;
    }

    /**
     * Verifies the Ed25519 signature over the payload using the configured
     * public key. Fail-closed: a missing or malformed public key disables
     * premium verification entirely rather than trusting the key.
     */
    private boolean verifySignature(String payload, byte[] providedSignature) {
        byte[] publicKeyBytes = getPublicKeyBytes();
        if (publicKeyBytes == null) {
            this.errorMessage = PUBLIC_KEY_ENV + " is not configured "
                + "(base64 X.509 Ed25519 public key); premium verification is disabled.";
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

    private static byte[] getPublicKeyBytes() {
        String encoded = System.getProperty(PUBLIC_KEY_PROPERTY);
        if (encoded == null || encoded.isBlank()) {
            encoded = System.getenv(PUBLIC_KEY_ENV);
        }
        if (encoded == null || encoded.isBlank()) {
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
