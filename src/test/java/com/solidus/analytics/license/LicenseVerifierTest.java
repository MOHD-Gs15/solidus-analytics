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
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the SA2 (Ed25519) license scheme. These lock in the R01 fix:
 * verification uses an asymmetric signature with a public key - no client
 * held secret can ever be used to mint valid keys again.
 */
@DisplayName("LicenseVerifier (SA2 / Ed25519)")
class LicenseVerifierTest {

    private static KeyPair keyPair;
    private Path configDir;

    @BeforeAll
    static void setUpKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        System.setProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY,
            Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    @AfterAll
    static void tearDownKeys() {
        System.clearProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY);
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

    private static String issueKey(String licensee, LocalDate expiry, String fingerprint) throws Exception {
        String payload = "2|" + licensee + "|" + expiry.format(DateTimeFormatter.ISO_LOCAL_DATE) + "|" + fingerprint;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return "SA2-"
            + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "-"
            + Base64.getEncoder().encodeToString(signer.sign());
    }

    private LicenseVerifier verifierWithKeyFile(String keyContent) throws Exception {
        Files.writeString(configDir.resolve("license.key"), keyContent);
        return new LicenseVerifier(configDir);
    }

    @Test
    @DisplayName("a correctly signed SA2 key verifies via initialize()")
    void validKeyVerifies() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile(
            issueKey("Acme Server", LocalDate.now().plusDays(30), "ANY") + "\n");

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.initialize());
        assertTrue(verifier.isPremiumEnabled());
        assertEquals("Acme Server", verifier.getLicenseeName());
        assertTrue(verifier.getDaysRemaining() >= 0);
    }

    @Test
    @DisplayName("comment lines in license.key are skipped")
    void commentLinesSkipped() throws Exception {
        String key = issueKey("Acme Server", LocalDate.now().plusDays(30), "ANY");
        LicenseVerifier verifier = verifierWithKeyFile(
            "# Solidus Analytics license\n" + key + "\n");

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.initialize());
    }

    @Test
    @DisplayName("an expired key is detected and premium stays off")
    void expiredKeyDetected() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile(
            issueKey("Acme Server", LocalDate.now().minusDays(1), "ANY"));

        assertEquals(LicenseVerifier.VerificationState.EXPIRED, verifier.initialize());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("a tampered signature is rejected")
    void tamperedSignatureRejected() throws Exception {
        String key = issueKey("Acme Server", LocalDate.now().plusDays(30), "ANY");
        String[] parts = key.split("-");
        byte[] sig = Base64.getDecoder().decode(parts[2]);
        sig[0] ^= 0x01;
        LicenseVerifier verifier = verifierWithKeyFile(
            parts[0] + "-" + parts[1] + "-" + Base64.getEncoder().encodeToString(sig));

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());
        assertFalse(verifier.isPremiumEnabled());
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
    @DisplayName("a server-bound key fails on a different server fingerprint")
    void fingerprintMismatchDetected() throws Exception {
        LicenseVerifier verifier = verifierWithKeyFile(
            issueKey("Acme Server", LocalDate.now().plusDays(30), "0123456789ABCDEF"));

        assertEquals(LicenseVerifier.VerificationState.FINGERPRINT_MISMATCH, verifier.initialize());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("missing public key fails closed (premium disabled, no crash)")
    void missingPublicKeyFailsClosed() throws Exception {
        try {
            System.clearProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY);
            LicenseVerifier verifier = verifierWithKeyFile(
                issueKey("Acme Server", LocalDate.now().plusDays(30), "ANY"));

            assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.initialize());
            assertFalse(verifier.isPremiumEnabled());
            assertTrue(verifier.getErrorMessage().contains("SOLIDUS_LICENSE_PUBLIC_KEY"),
                "error should name the missing public key, got: " + verifier.getErrorMessage());
        } finally {
            System.setProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
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
            issueKey("Late Buyer", LocalDate.now().plusDays(5), "ANY"));
        assertNotEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.forceReverify());
        assertTrue(verifier.isPremiumEnabled());
    }
}
