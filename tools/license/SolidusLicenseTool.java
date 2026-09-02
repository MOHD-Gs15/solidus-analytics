/*
 * Solidus Analytics — Offline License Tool (SA2 / Ed25519)
 *
 * A standalone, zero-dependency CLI for the SA2 license scheme.
 * Runs on any Java 17+ using the standard JCA Ed25519 API.
 *
 * Usage (single-file source launcher, no build required):
 *   java tools/license/SolidusLicenseTool.java keygen  --out keys/
 *   java tools/license/SolidusLicenseTool.java issue   --private-key keys/solidus_license_private_key.b64 \
 *                                                      --customer "acme-corp" \
 *                                                      --fingerprint 1A2B3C4D5E6F7081 \
 *                                                      --days 365
 *   java tools/license/SolidusLicenseTool.java verify  --public-key keys/solidus_license_public_key.b64 \
 *                                                      --license "SA2...."
 *   java tools/license/SolidusLicenseTool.java selftest
 *
 * Key format (SA2):   SA2.<base64url(payload)>.<base64url(ed25519-signature)>
 * Payload (pipe-separated, 6 fields):
 *   2 | customer | expiry (ISO date or PERPETUAL) | fingerprint (16 hex or ANY) | product | nonce
 *
 * SECURITY:
 *   - The PRIVATE key must never leave the vendor machine and must never be committed.
 *   - Only the PUBLIC key is embedded into the mod (LicenseVerifier.SA2_PUBLIC_KEY_B64).
 *   - The customer field acts as a watermark: every issued license identifies its buyer.
 */
package com.solidus.analytics.tools.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class SolidusLicenseTool {

    private static final String KEY_PREFIX = "SA2";
    private static final String ALGORITHM = "Ed25519";
    private static final int PAYLOAD_VERSION = 2;
    private static final String DEFAULT_PRODUCT = "analytics-premium";
    private static final String UNIVERSAL_FINGERPRINT = "ANY";
    private static final String PERPETUAL = "PERPETUAL";
    private static final String PRIVATE_KEY_FILE = "solidus_license_private_key.b64";
    private static final String PUBLIC_KEY_FILE = "solidus_license_public_key.b64";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SolidusLicenseTool() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printHelp();
            System.exit(2);
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "keygen" -> keygen(args);
                case "issue" -> issue(args);
                case "verify" -> verify(args);
                case "selftest" -> System.exit(selftest() ? 0 : 1);
                case "help", "--help", "-h" -> printHelp();
                default -> {
                    System.err.println("Unknown command: " + args[0]);
                    printHelp();
                    System.exit(2);
                }
            }
        } catch (CliError e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(e.exitCode);
        } catch (Exception e) {
            System.err.println("UNEXPECTED ERROR: " + e);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // keygen
    // ------------------------------------------------------------------

    private static void keygen(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        Path outDir = Paths.get(opts.getOrDefault("out", "."));
        boolean force = opts.containsKey("force");

        Path privatePath = outDir.resolve(PRIVATE_KEY_FILE);
        Path publicPath = outDir.resolve(PUBLIC_KEY_FILE);
        if (!force && (Files.exists(privatePath) || Files.exists(publicPath))) {
            throw new CliError("Key files already exist in " + outDir.toAbsolutePath()
                    + ". Use --force to overwrite (warning: old licenses stay valid; new ones require the new public key).", 1);
        }

        System.out.println("Generating Ed25519 keypair...");
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        KeyPair keyPair = generator.generateKeyPair();

        String privateB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String publicB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        Files.createDirectories(outDir);
        writeKeyFile(privatePath, privateB64, true);
        writeKeyFile(publicPath, publicB64, false);

        System.out.println();
        System.out.println("Private key written to : " + privatePath.toAbsolutePath());
        System.out.println("Public  key written to : " + publicPath.toAbsolutePath());
        System.out.println();
        System.out.println("===============================================================");
        System.out.println("PUBLIC KEY (safe to publish — embed this in LicenseVerifier):");
        System.out.println();
        System.out.println(publicB64);
        System.out.println();
        System.out.println("Copy the line above into LicenseVerifier.SA2_PUBLIC_KEY_B64.");
        System.out.println("===============================================================");
        System.out.println();
        System.out.println("CRITICAL: Back up '" + privatePath.getFileName() + "' to offline storage");
        System.out.println("(USB drive / password manager) and DELETE it from any synced folder.");
        System.out.println("Anyone holding this private key can forge licenses for all customers.");
    }

    private static void writeKeyFile(Path path, String content, boolean restrictPermissions) throws IOException {
        Files.writeString(path, content + System.lineSeparator(), StandardCharsets.UTF_8);
        if (restrictPermissions) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignore) {
                // Non-POSIX filesystem (e.g. Windows): skip permission hardening.
            }
        }
    }

    // ------------------------------------------------------------------
    // issue
    // ------------------------------------------------------------------

    private static void issue(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);

        String keyFile = require(opts, "private-key");
        String customer = require(opts, "customer");
        String fingerprint = require(opts, "fingerprint");
        String product = opts.getOrDefault("product", DEFAULT_PRODUCT);

        String expiry;
        if (opts.containsKey("perpetual")) {
            expiry = PERPETUAL;
        } else if (opts.containsKey("days")) {
            int days;
            try {
                days = Integer.parseInt(opts.get("days"));
            } catch (NumberFormatException e) {
                throw new CliError("--days must be an integer, got: " + opts.get("days"), 2);
            }
            if (days <= 0) {
                throw new CliError("--days must be positive", 2);
            }
            expiry = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(days).format(DateTimeFormatter.ISO_LOCAL_DATE);
        } else if (opts.containsKey("until")) {
            expiry = parseIsoDate(opts.get("until"));
        } else {
            throw new CliError("Choose a duration: --days <n>, --until <yyyy-MM-dd>, or --perpetual", 2);
        }

        validateCustomer(customer);
        validateProduct(product);
        validateFingerprint(fingerprint);

        PrivateKey privateKey = loadPrivateKey(Paths.get(keyFile));
        String license = issueLicense(privateKey, customer, expiry, fingerprint, product);

        System.out.println();
        System.out.println("License issued:");
        System.out.println("  customer    : " + customer);
        System.out.println("  product     : " + product);
        System.out.println("  fingerprint : " + fingerprint);
        System.out.println("  expires     : " + (expiry.equals(PERPETUAL) ? "never (perpetual)" : expiry));
        System.out.println();
        System.out.println(license);
        System.out.println();

        if (opts.containsKey("out")) {
            Path outFile = Paths.get(opts.get("out"));
            Files.writeString(outFile, license + System.lineSeparator(), StandardCharsets.UTF_8);
            System.out.println("Saved to: " + outFile.toAbsolutePath());
        } else {
            System.out.println("Deliver this single line to the customer "
                    + "(they paste it into config/solidus-analytics/license.key).");
        }
    }

    /** Builds and signs one SA2 license string. Shared by `issue` and `selftest`. */
    static String issueLicense(PrivateKey privateKey, String customer, String expiry,
                               String fingerprint, String product) {
        String nonce = randomHex(8);
        String payload = PAYLOAD_VERSION + "|" + customer + "|" + expiry + "|"
                + fingerprint + "|" + product + "|" + nonce;
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(privateKey);
            signer.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] signature = signer.sign();
            return KEY_PREFIX + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new CliError("Signing failed: " + e.getMessage(), 1);
        }
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        if (!Files.exists(path)) {
            throw new CliError("Private key file not found: " + path.toAbsolutePath(), 2);
        }
        byte[] encoded = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.UTF_8).trim());
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    // ------------------------------------------------------------------
    // verify
    // ------------------------------------------------------------------

    private static void verify(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);

        String keyFile = require(opts, "public-key");
        String licenseValue = require(opts, "license");
        String license = licenseValue.startsWith("@")
                ? Files.readString(Paths.get(licenseValue.substring(1)), StandardCharsets.UTF_8).trim()
                : licenseValue;
        String localFingerprint = opts.get("fingerprint"); // optional

        PublicKey publicKey = loadPublicKey(Paths.get(keyFile));
        VerifyOutcome outcome = verifyLicense(publicKey, license, localFingerprint);

        System.out.println();
        System.out.println("License report");
        System.out.println("------------------------------------------");
        System.out.println("  customer    : " + (outcome.customer() != null ? outcome.customer() : "n/a"));
        System.out.println("  product     : " + (outcome.product() != null ? outcome.product() : "n/a"));
        System.out.println("  fingerprint : " + (outcome.fingerprint() != null ? outcome.fingerprint() : "n/a"));
        System.out.println("  expiry      : " + (outcome.expiry() != null ? outcome.expiry() : "n/a"));
        System.out.println("  nonce       : " + (outcome.nonce() != null ? outcome.nonce() : "n/a"));
        System.out.println("  result      : " + outcome.status()
                + (outcome.message().isEmpty() ? "" : " — " + outcome.message()));
        System.out.println();

        switch (outcome.status()) {
            case VALID -> System.out.println("VALID license");
            case EXPIRED -> {
                System.out.println("EXPIRED license");
                System.exit(3);
            }
            case FINGERPRINT_MISMATCH -> {
                System.out.println("FINGERPRINT MISMATCH (license bound to another server)");
                System.exit(4);
            }
            default -> {
                System.out.println("INVALID license");
                System.exit(1);
            }
        }
    }

    private static PublicKey loadPublicKey(Path path) throws Exception {
        if (!Files.exists(path)) {
            throw new CliError("Public key file not found: " + path.toAbsolutePath(), 2);
        }
        byte[] encoded = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.UTF_8).trim());
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
    }

    /** Verifies one SA2 license string. Shared by `verify` and `selftest`. */
    static VerifyOutcome verifyLicense(PublicKey publicKey, String license, String localFingerprint) {
        String[] parts = license.split("\\.", 3);
        if (parts.length != 3 || !KEY_PREFIX.equals(parts[0])) {
            return new VerifyOutcome(VerifyOutcome.Status.INVALID,
                    "Invalid key structure (expected " + KEY_PREFIX + ".<payload>.<signature>)",
                    null, null, null, null, null);
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return new VerifyOutcome(VerifyOutcome.Status.INVALID, "Payload is not valid Base64URL",
                    null, null, null, null, null);
        }
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return new VerifyOutcome(VerifyOutcome.Status.INVALID, "Signature is not valid Base64URL",
                    null, null, null, null, null);
        }
        boolean signatureValid;
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(payloadBytes);
            signatureValid = verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return new VerifyOutcome(VerifyOutcome.Status.INVALID, "Signature check error: " + e.getMessage(),
                    null, null, null, null, null);
        }
        if (!signatureValid) {
            return new VerifyOutcome(VerifyOutcome.Status.INVALID,
                    "Signature mismatch — key may be forged, corrupted, or signed by another key",
                    null, null, null, null, null);
        }

        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] fields = payload.split("\\|", 6);
        if (fields.length != 6) {
            return new VerifyOutcome(VerifyOutcome.Status.INVALID,
                    "Invalid payload structure (expected 6 fields, got " + fields.length + ")",
                    null, null, null, null, null);
        }
        int version;
        try {
            version = Integer.parseInt(fields[0]);
        } catch (NumberFormatException e) {
            return new VerifyOutcome(VerifyOutcome.Status.INVALID, "Invalid payload version",
                    null, null, null, null, null);
        }
        if (version != PAYLOAD_VERSION) {
            return new VerifyOutcome(VerifyOutcome.Status.INVALID,
                    "Unsupported payload version " + version + " (expected " + PAYLOAD_VERSION + ")",
                    null, null, null, null, null);
        }

        String customer = fields[1];
        String expiry = fields[2];
        String fingerprint = fields[3];
        String product = fields[4];
        String nonce = fields[5];

        if (!PERPETUAL.equals(expiry)) {
            LocalDate expiryDate;
            try {
                expiryDate = LocalDate.parse(expiry, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                return new VerifyOutcome(VerifyOutcome.Status.INVALID, "Invalid expiry date: " + expiry,
                        customer, expiry, fingerprint, product, nonce);
            }
            if (LocalDate.now(java.time.ZoneOffset.UTC).isAfter(expiryDate)) {
                return new VerifyOutcome(VerifyOutcome.Status.EXPIRED, "License expired on " + expiry,
                        customer, expiry, fingerprint, product, nonce);
            }
        }
        if (!UNIVERSAL_FINGERPRINT.equals(fingerprint) && localFingerprint != null
                && !fingerprint.equalsIgnoreCase(localFingerprint)) {
            return new VerifyOutcome(VerifyOutcome.Status.FINGERPRINT_MISMATCH,
                    "License is bound to fingerprint " + fingerprint + ", got " + localFingerprint,
                    customer, expiry, fingerprint, product, nonce);
        }
        return new VerifyOutcome(VerifyOutcome.Status.VALID, "", customer, expiry, fingerprint, product, nonce);
    }

    // ------------------------------------------------------------------
    // selftest
    // ------------------------------------------------------------------

    private static boolean selftest() throws Exception {
        System.out.println("Solidus License Tool — self test (SA2 / " + ALGORITHM + ")");
        System.out.println("===========================================================");

        KeyPair vendor = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        KeyPair attacker = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();

        String fpA = "1A2B3C4D5E6F7081";
        String fpB = "F0E1D2C3B4A59687";
        int failures = 0;

        // 1. Valid dated, fingerprint-bound license
        String l1 = issueLicense(vendor.getPrivate(), "acme-corp", LocalDate.now().plusDays(365)
                .format(DateTimeFormatter.ISO_LOCAL_DATE), fpA, DEFAULT_PRODUCT);
        failures += check(1, "valid dated + fingerprint-bound", VerifyOutcome.Status.VALID,
                verifyLicense(vendor.getPublic(), l1, fpA));

        // 2. Perpetual universal license (ANY fingerprint)
        String l2 = issueLicense(vendor.getPrivate(), "reseller-01", PERPETUAL, UNIVERSAL_FINGERPRINT, DEFAULT_PRODUCT);
        failures += check(2, "perpetual + ANY fingerprint", VerifyOutcome.Status.VALID,
                verifyLicense(vendor.getPublic(), l2, fpB));

        // 3. Tampered payload must fail the signature check
        String tampered = l1.substring(0, 4)
                + (l1.charAt(4) == 'A' ? "B" : "A")
                + l1.substring(5);
        failures += check(3, "tampered payload rejected", VerifyOutcome.Status.INVALID,
                verifyLicense(vendor.getPublic(), tampered, fpA));

        // 4. License bound to fpA must mismatch on server fpB
        failures += check(4, "wrong server fingerprint detected", VerifyOutcome.Status.FINGERPRINT_MISMATCH,
                verifyLicense(vendor.getPublic(), l1, fpB));

        // 5. Expired license
        String l5 = issueLicense(vendor.getPrivate(), "ghost", LocalDate.now().minusDays(1)
                .format(DateTimeFormatter.ISO_LOCAL_DATE), UNIVERSAL_FINGERPRINT, DEFAULT_PRODUCT);
        failures += check(5, "expired license detected", VerifyOutcome.Status.EXPIRED,
                verifyLicense(vendor.getPublic(), l5, fpA));

        // 6. Key signed by a different private key must fail
        String l6 = issueLicense(attacker.getPrivate(), "forger", PERPETUAL, UNIVERSAL_FINGERPRINT, DEFAULT_PRODUCT);
        failures += check(6, "forged key (wrong signing key) rejected", VerifyOutcome.Status.INVALID,
                verifyLicense(vendor.getPublic(), l6, fpA));

        // 7. Wrong scheme prefix rejected
        failures += check(7, "wrong prefix rejected", VerifyOutcome.Status.INVALID,
                verifyLicense(vendor.getPublic(), "SA1-abc-def", fpA));

        // 8. Two issues for the same customer differ (nonce)
        String l8 = issueLicense(vendor.getPrivate(), "acme-corp", PERPETUAL, fpA, DEFAULT_PRODUCT);
        boolean unique = !l8.equals(l1) || !l8.equals(l2);
        System.out.println("test 8  : nonce uniqueness (no identical keys) ............ "
                + (unique ? "PASS" : "FAIL"));
        if (!unique) failures++;

        System.out.println("===========================================================");
        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
            return true;
        }
        System.out.println(failures + " TEST(S) FAILED");
        return false;
    }

    private static int check(int number, String label, VerifyOutcome.Status expected, VerifyOutcome actual) {
        boolean pass = actual.status() == expected;
        System.out.printf("test %-2d : %-46s %s%n", number, label, pass ? "PASS" : "FAIL (got " + actual.status() + ")");
        return pass ? 0 : 1;
    }

    // ------------------------------------------------------------------
    // Outcome model
    // ------------------------------------------------------------------

    record VerifyOutcome(Status status, String message, String customer, String expiry,
                         String fingerprint, String product, String nonce) {
        enum Status { VALID, INVALID, EXPIRED, FINGERPRINT_MISMATCH }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void validateCustomer(String customer) {
        if (customer.isBlank() || customer.length() > 64) {
            throw new CliError("customer must be 1-64 characters", 2);
        }
        if (customer.contains("|")) {
            throw new CliError("customer must not contain '|'", 2);
        }
    }

    private static void validateProduct(String product) {
        if (product.isBlank() || product.contains("|")) {
            throw new CliError("product must be non-empty and must not contain '|'", 2);
        }
    }

    static void validateFingerprint(String fingerprint) {
        if (UNIVERSAL_FINGERPRINT.equals(fingerprint)) {
            return;
        }
        if (!fingerprint.matches("[0-9A-Fa-f]{16}")) {
            throw new CliError("fingerprint must be 'ANY' or exactly 16 hex characters "
                    + "(as printed by /analytics fingerprint), got: " + fingerprint, 2);
        }
    }

    private static String parseIsoDate(String value) {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new CliError("--until must be an ISO date (yyyy-MM-dd), got: " + value, 2);
        }
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String name = arg.substring(2).toLowerCase(Locale.ROOT);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(name, args[++i]);
                } else {
                    opts.put(name, "true");
                }
            }
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String name) {
        String value = opts.get(name);
        if (value == null || value.isBlank()) {
            throw new CliError("Missing required option --" + name, 2);
        }
        return value.trim();
    }

    private static void printHelp() {
        System.out.println("""
                Solidus Analytics License Tool (SA2 / Ed25519)

                Commands:
                  keygen   --out <dir> [--force]
                           Generate an Ed25519 keypair. Run ONCE; back up the private key offline.
                  issue    --private-key <file> --customer <id> --fingerprint <16hex|ANY>
                            (--days <n> | --until <yyyy-MM-dd> | --perpetual)
                           [--product <name>] [--out <file>]
                           Sign and print a license key for one customer/server.
                  verify   --public-key <file> --license <SA2....|@file> [--fingerprint <16hex>]
                           Verify a license offline. Exit codes: 0 valid, 1 invalid,
                           3 expired, 4 fingerprint mismatch.
                  selftest Run the built-in correctness test suite.

                Typical workflow:
                  java tools/license/SolidusLicenseTool.java keygen --out keys/
                  # paste keys/solidus_license_public_key.b64 into LicenseVerifier.SA2_PUBLIC_KEY_B64
                  java tools/license/SolidusLicenseTool.java issue \\
                       --private-key keys/solidus_license_private_key.b64 \\
                       --customer "server-owner-42" --fingerprint 1A2B3C4D5E6F7081 --days 365
                  # customer runs /analytics fingerprint on their server and pastes the
                  # issued key into config/solidus-analytics/license.key
                """);
    }

    static final class CliError extends RuntimeException {
        final int exitCode;

        CliError(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }
    }
}
