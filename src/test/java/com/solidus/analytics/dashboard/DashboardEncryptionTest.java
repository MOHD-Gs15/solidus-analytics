package com.solidus.analytics.dashboard;

import static org.junit.jupiter.api.Assertions.*;

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
}
