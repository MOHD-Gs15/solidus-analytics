package com.solidus.analytics.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DashboardEncryptionTest {
    @Test
    void usesPbkdf2AndRoundTripsPayload() {
        DashboardEncryption encryption = new DashboardEncryption();
        char[] password = "correct horse battery staple".toCharArray();
        String hash = encryption.setupPassword(password);

        assertTrue(hash.startsWith("pbkdf2$sha256$"));
        assertTrue(DashboardEncryption.verifyPassword(password, hash));
        assertFalse(DashboardEncryption.verifyPassword("wrong password".toCharArray(), hash));

        String ciphertext = encryption.encrypt("{\"moneySupply\":42}");
        assertNotNull(ciphertext);
        assertEquals("{\"moneySupply\":42}", encryption.decrypt(ciphertext));
        assertNull(encryption.decrypt("AQID"));

        encryption.lock();
        assertFalse(encryption.isUnlocked());
        assertNull(encryption.encrypt("after lock"));
    }

    @Test
    void rejectsMalformedPasswordRecords() {
        assertFalse(DashboardEncryption.verifyPassword("password".toCharArray(), ""));
        assertFalse(DashboardEncryption.verifyPassword("password".toCharArray(), "pbkdf2$sha256$1$bad$bad"));
        assertFalse(DashboardEncryption.verifyPassword("password".toCharArray(), "not-a-record"));
    }

    /** Legacy pre-PBKDF2 record: hex(16-byte salt):hex(SHA-256(password||salt)).
     *  Used to prove the A-1 web-side migration and the R19 vault migration. */
    static String legacyHashFor(String password) {
        try {
            SecureRandom rnd = new SecureRandom();
            byte[] salt = new byte[16];
            rnd.nextBytes(salt);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            digest.update(salt);
            return HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void legacyHashVerifiesThroughTheMigrationPath() {
        String legacy = legacyHashFor("legacy-secret");
        assertTrue(DashboardEncryption.isLegacyHash(legacy));
        assertTrue(DashboardEncryption.verifyPassword("legacy-secret".toCharArray(), legacy));
        assertFalse(DashboardEncryption.verifyPassword("wrong".toCharArray(), legacy));
        // A PBKDF2 record is NOT legacy.
        String modern = new DashboardEncryption().setupPassword("x".toCharArray());
        assertFalse(DashboardEncryption.isLegacyHash(modern));
    }
}
