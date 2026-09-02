package com.solidus.analytics.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the SA2 (Ed25519) license scheme. These lock in the contract
 * documented in docs/LICENSE-SYSTEM.md:
 *
 * <ul>
 *   <li>verification uses an asymmetric signature with the EMBEDDED vendor
 *       public key - no client-held secret can ever be used to mint valid
 *       keys again (the SA1 flaw);</li>
 *   <li>the wire format is {@code SA2.<base64url(payload)>.<base64url(sig)>}
 *       with the 6-field payload, exactly what
 *       {@code tools/license/SolidusLicenseTool.issueLicense} emits;</li>
 *   <li>customer-settable env/property keys are IGNORED (audit D-1) - only
 *       the package-private test hook can inject a key, and nothing in
 *       production code sets it.</li>
 * </ul>
 */
@DisplayName("LicenseVerifier (SA2 / Ed25519)")
class LicenseVerifierTest {

    private static KeyPair keyPair;
    private static String previousTestKey;
    private Path configDir;

    @BeforeAll
    static void setUpKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // Test-only injection point (never read from env/property in production).
        previousTestKey = LicenseVerifier.testPublicKeyB64;
        LicenseVerifier.testPublicKeyB64 =
            Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @AfterAll
    static void tearDownKeys() {
        LicenseVerifier.testPublicKeyB64 = previousTestKey;
    }

    @BeforeEach
    void setUp() throws Exception {
        configDir = Files.createTempDirectory("solidus-license-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.walk(configDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(p -> p.toFile().delete());
    }

    /**
     * Mirrors SolidusLicenseTool.issueLicense exactly (dot separators,
     * Base64URL without padding, 6 pipe-separated fields) so this test is
     * the issuer <-> verifier contract test (audit D-2 gap).
     */
    private static String issueKey(String customer, LocalDate expiry, String fingerprint) throws Exception {
        return issueKey(customer, expiry, fingerprint, "analytics-premium", "0123456789ABCDEF");
    }

    private static String issueKey(String customer, LocalDate expiry, String fingerprint,
                                   String product, String nonce) throws Exception {
        String expiryField = expiry == null ? "PERPETUAL" : expiry.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String payload = "2|" + customer + "|" + expiryField + "|" + fingerprint + "|" + product + "|" + nonce;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return "SA2."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private LicenseVerifier verifierWithKeyFile(String keyContent) throws Exception {
        Files.writeString(configDir.resolve("license.key"), keyContent);
        return new LicenseVerifier(configDir);
    }

    @Test
    @DisplayName("a correctly signed 6-field SA2. key (tool format) verifies via initialize()")
    void validKeyVerifies() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile(
            issueKey("Acme Server", LocalDate.now(ZoneOffset.UTC).plusDays(30), "ANY") + "\n");

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.initialize());
        assertTrue(verifier.isPremiumEnabled());
        assertEquals("Acme Server", verifier.getLicenseeName());
        assertTrue(verifier.getDaysRemaining() >= 0);
        assertFalse(verifier.isPerpetual());
    }

    @Test
    @DisplayName("a PERPETUAL key verifies and never reports days-remaining 0")
    void perpetualKeyVerifies() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile(
            issueKey("Acme Server", null, "ANY"));

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.initialize());
        assertTrue(verifier.isPremiumEnabled());
        assertTrue(verifier.isPerpetual());
        assertEquals(Long.MAX_VALUE, verifier.getDaysRemaining());
    }

    @Test
    @DisplayName("comment lines in license.key are skipped")
    void commentLinesSkipped() throws Exception {
        String key = issueKey("Acme Server", LocalDate.now(ZoneOffset.UTC).plusDays(30), "ANY");
        LicenseVerifier verifier = verifierWithKeyFile(
            "# Solidus Analytics license\n" + key + "\n");

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.initialize());
    }

    @Test
    @DisplayName("an expired key is detected and premium stays off")
    void expiredKeyDetected() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile(
            issueKey("Acme Server", LocalDate.now(ZoneOffset.UTC).minusDays(1), "ANY"));

        assertEquals(LicenseVerifier.VerificationState.EXPIRED, verifier.initialize());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("a tampered signature is rejected")
    void tamperedSignatureRejected() throws Exception {
        String key = issueKey("Acme Server", LocalDate.now(ZoneOffset.UTC).plusDays(30), "ANY");
        String[] parts = key.split("\\.");
        byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
        sig[0] ^= 0x01;
        LicenseVerifier verifier = verifierWithKeyFile(
            parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig));

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("a tampered payload (extra field shifted) is rejected")
    void wrongFieldCountRejected() throws Exception {
        // 4-field legacy payload signed by a VALID vendor key: must still be rejected.
        String payload = "2|Acme|9999-12-31|ANY";
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String key = "SA2."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());

        LicenseVerifier verifier = verifierWithKeyFile(key);
        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());
        assertTrue(verifier.getErrorMessage().contains("fields"),
            "error should explain the field-count contract, got: " + verifier.getErrorMessage());
    }

    @Test
    @DisplayName("legacy SA1 keys are rejected outright")
    void sa1KeysRejected() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile("SA1-eyJhIjoxfQ-someSignature");

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());
        assertTrue(verifier.getErrorMessage().contains("no longer accepted"),
            "error should explain SA1 rejection, got: " + verifier.getErrorMessage());
    }

    @Test
    @DisplayName("legacy SA2- dash-format keys are rejected with guidance")
    void legacyDashFormatRejected() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile(
            "SA2-cGVybWlzc2lvbnxpc3BhcmFtZWN8MjAyNy0wMS0wMXxBTlk="
            + "-c29tZVNpZ25hdHVyZQ==");

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());
        assertTrue(verifier.getErrorMessage().contains("dash-format"),
            "error should explain the retired dash format, got: " + verifier.getErrorMessage());
    }

    @Test
    @DisplayName("a server-bound key fails on a different server fingerprint")
    void fingerprintMismatchDetected() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile(
            issueKey("Acme Server", LocalDate.now(ZoneOffset.UTC).plusDays(30), "0123456789ABCDEF"));

        assertEquals(LicenseVerifier.VerificationState.FINGERPRINT_MISMATCH, verifier.initialize());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("missing embedded public key fails closed (premium disabled, no crash)")
    void missingPublicKeyFailsClosed() throws Exception {
        String savedKey = LicenseVerifier.testPublicKeyB64;
        try {
            LicenseVerifier.testPublicKeyB64 = null;  // embedded constant is a placeholder -> fail closed
            LicenseVerifier verifier = verifierWithKeyFile(
                issueKey("Acme Server", LocalDate.now(ZoneOffset.UTC).plusDays(30), "ANY"));

            assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());
            assertFalse(verifier.isPremiumEnabled());
            assertTrue(verifier.getErrorMessage().contains("embedded"),
                "error should name the embedded key requirement, got: " + verifier.getErrorMessage());
        } finally {
            LicenseVerifier.testPublicKeyB64 = savedKey;
        }
    }

    @Test
    @DisplayName("customer-settable env/property keys are IGNORED (D-1: self-signed license must fail)")
    void customerSuppliedKeyIsIgnored() throws Exception {
        // A different key pair entirely: if the verifier honored customer-settable
        // key sources, this self-signed license would verify.
        KeyPair attackerPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String payload = "2|Attacker|9999-12-31|ANY|analytics-premium|0123456789ABCDEF";
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(attackerPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String forgedKey = "SA2."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());

        String savedProperty = System.getProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY);
        try {
            // The attacker sets BOTH legacy override channels.
            System.setProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY,
                Base64.getEncoder().encodeToString(attackerPair.getPublic().getEncoded()));
            LicenseVerifier verifier = verifierWithKeyFile(forgedKey);

            assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize(),
                "a customer-supplied verification key must never be trusted (SA1 flaw)");
            assertFalse(verifier.isPremiumEnabled());
        } finally {
            if (savedProperty == null) {
                System.clearProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY);
            } else {
                System.setProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY, savedProperty);
            }
        }
    }

    @Test
    @DisplayName("a missing key file reports INVALID with guidance")
    void missingKeyFileReportsInvalid() {
        LicenseVerifier verifier = new LicenseVerifier(configDir);

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());
        assertFalse(verifier.isPremiumEnabled());
        assertNotNull(verifier.getErrorMessage());
    }

    @Test
    @DisplayName("forceReverify re-reads the key file")
    void forceReverifyWorks() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier(configDir);
        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());

        Files.writeString(configDir.resolve("license.key"),
            issueKey("Late Buyer", LocalDate.now(ZoneOffset.UTC).plusDays(5), "ANY"));
        assertNotEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.forceReverify());
        assertTrue(verifier.isPremiumEnabled());
    }
}
