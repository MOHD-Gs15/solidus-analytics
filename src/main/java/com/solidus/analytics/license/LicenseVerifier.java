/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.fabricmc.loader.api.FabricLoader
 */
package com.solidus.analytics.license;

import com.solidus.analytics.SolidusAnalyticsMod;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.fabricmc.loader.api.FabricLoader;

public final class LicenseVerifier {
    private static final int KEY_VERSION = 1;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String LICENSE_SECRET_ENV = "SOLIDUS_LICENSE_SECRET";
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
            this.errorMessage = "No license key found. Place your key in " + String.valueOf(this.licenseKeyPath);
            SolidusAnalyticsMod.LOGGER.error(this.errorMessage);
            SolidusAnalyticsMod.LOGGER.error("Solidus Analytics Premium requires a valid license key. Create the file '{}' with your license key on a single line.", (Object)this.licenseKeyPath);
            return this.state;
        }
        this.state = this.verifyLocally(rawKey);
        if (this.state == VerificationState.VERIFIED) {
            SolidusAnalyticsMod.LOGGER.info("License verified for: {} (expires: {})", (Object)this.licenseeName, (Object)this.expiryDate);
            if ("ANY".equals(this.fingerprint)) {
                SolidusAnalyticsMod.LOGGER.info("License type: Universal (any server)");
            } else {
                SolidusAnalyticsMod.LOGGER.info("License type: Server-specific (fingerprint: {}...)", (Object)this.fingerprint);
            }
        } else {
            SolidusAnalyticsMod.LOGGER.warn("License verification failed: {}", (Object)this.errorMessage);
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
            SolidusAnalyticsMod.LOGGER.warn("License has expired: {}", (Object)this.errorMessage);
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
        SolidusAnalyticsMod.LOGGER.info("Re-verification result: {} \u2014 {}", (Object)this.state, (Object)(this.errorMessage != null ? this.errorMessage : "OK"));
        return this.state;
    }

    public static String computeServerFingerprint() {
        try {
            Object raw = "";
            try {
                raw = (String)raw + FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
            }
            catch (Exception exception) {
                // empty catch block
            }
            raw = (String)raw + InetAddress.getLocalHost().getHostName();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(((String)raw).getBytes(StandardCharsets.UTF_8));
            return LicenseVerifier.bytesToHex(hash).substring(0, 16).toUpperCase();
        }
        catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String readLicenseKey() {
        try {
            String[] lines;
            if (!Files.exists(this.licenseKeyPath, new LinkOption[0])) {
                return null;
            }
            String key = Files.readString(this.licenseKeyPath).trim();
            for (String line : lines = key.split("\n")) {
                if ((line = line.trim()).isEmpty() || line.startsWith("#")) continue;
                return line;
            }
            return null;
        }
        catch (IOException e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to read license key file", (Throwable)e);
            return null;
        }
    }

    private VerificationState verifyLocally(String rawKey) {
        try {
            String serverFingerprint;
            int keyVersion;
            byte[] providedSignature;
            String payload;
            if (!rawKey.startsWith("SA1-")) {
                this.errorMessage = "Invalid key format. Expected SA1-...";
                return VerificationState.INVALID;
            }
            String body = rawKey.substring(3);
            int lastDash = body.lastIndexOf(45);
            if (lastDash < 0) {
                this.errorMessage = "Invalid key structure (missing signature separator)";
                return VerificationState.INVALID;
            }
            String payloadBase64 = body.substring(0, lastDash);
            String signatureBase64 = body.substring(lastDash + 1);
            try {
                payload = new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
            }
            catch (IllegalArgumentException e) {
                this.errorMessage = "Invalid key encoding (payload not valid Base64)";
                return VerificationState.INVALID;
            }
            try {
                providedSignature = Base64.getDecoder().decode(signatureBase64);
            }
            catch (IllegalArgumentException e) {
                this.errorMessage = "Invalid key encoding (signature not valid Base64)";
                return VerificationState.INVALID;
            }
            byte[] expectedSignature = LicenseVerifier.computeHMAC(payload);
            if (!LicenseVerifier.constantTimeEquals(expectedSignature, providedSignature)) {
                this.errorMessage = "Invalid license key (signature mismatch \u2014 key may be forged or corrupted)";
                return VerificationState.INVALID;
            }
            String[] fields = payload.split("\\|", 4);
            if (fields.length != 4) {
                this.errorMessage = "Invalid key payload structure (expected 4 fields, got " + fields.length + ")";
                return VerificationState.INVALID;
            }
            try {
                keyVersion = Integer.parseInt(fields[0]);
            }
            catch (NumberFormatException e) {
                this.errorMessage = "Invalid key version";
                return VerificationState.INVALID;
            }
            if (keyVersion != 1) {
                this.errorMessage = "Unsupported key version: " + keyVersion + " (expected: 1)";
                return VerificationState.INVALID;
            }
            this.licenseeName = fields[1];
            try {
                this.expiryDate = LocalDate.parse(fields[2], DateTimeFormatter.ISO_LOCAL_DATE);
            }
            catch (Exception e) {
                this.errorMessage = "Invalid expiry date in key: " + fields[2];
                return VerificationState.INVALID;
            }
            this.fingerprint = fields[3];
            if (LocalDate.now().isAfter(this.expiryDate)) {
                this.errorMessage = "License expired on " + this.expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
                return VerificationState.EXPIRED;
            }
            if (!"ANY".equals(this.fingerprint) && !this.fingerprint.equalsIgnoreCase(serverFingerprint = LicenseVerifier.computeServerFingerprint())) {
                this.errorMessage = "This license is tied to a different server. Expected: " + this.fingerprint + ", Got: " + serverFingerprint;
                return VerificationState.FINGERPRINT_MISMATCH;
            }
            this.errorMessage = null;
            return VerificationState.VERIFIED;
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Unexpected error during license verification", (Throwable)e);
            this.errorMessage = "Verification error: " + e.getMessage();
            return VerificationState.INVALID;
        }
    }

    private static byte[] computeHMAC(String payload) {
        String secret = System.getenv(LICENSE_SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(LICENSE_SECRET_ENV + " is not configured; premium verification is disabled.");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        }
        catch (InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; ++i) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static enum VerificationState {
        UNVERIFIED,
        VERIFIED,
        INVALID,
        EXPIRED,
        FINGERPRINT_MISMATCH;

    }
}
