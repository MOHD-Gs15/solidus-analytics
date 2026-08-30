package com.solidus.analytics.dashboard;

import com.solidus.analytics.SolidusAnalyticsMod;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class DashboardEncryption {
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int PBKDF2_ITERATIONS = 210000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private volatile char[] password;
    private volatile boolean unlocked = false;

    public String setupPassword(char[] password) {
        String hash = DashboardEncryption.hashPassword(password);
        this.password = (char[])password.clone();
        this.unlocked = true;
        SolidusAnalyticsMod.LOGGER.info("Dashboard encryption password set. Data will be encrypted.");
        return hash;
    }

    public boolean unlock(char[] password, String storedHash) {
        if (DashboardEncryption.verifyPassword(password, storedHash)) {
            if (this.password != null) {
                Arrays.fill(this.password, '\u0000');
            }
            this.password = (char[])password.clone();
            this.unlocked = true;
            SolidusAnalyticsMod.LOGGER.info("Dashboard encryption unlocked successfully.");
            return true;
        }
        SolidusAnalyticsMod.LOGGER.warn("Dashboard encryption unlock failed \u2014 incorrect password.");
        return false;
    }

    public void lock() {
        if (this.password != null) {
            Arrays.fill(this.password, '\u0000');
            this.password = null;
        }
        this.unlocked = false;
        SolidusAnalyticsMod.LOGGER.info("Dashboard encryption locked. Password cleared from memory.");
    }

    public boolean isUnlocked() {
        return this.unlocked;
    }

    public String encrypt(String plaintext) {
        if (!this.unlocked || this.password == null) {
            SolidusAnalyticsMod.LOGGER.warn("Cannot encrypt: dashboard encryption is not unlocked.");
            return null;
        }
        try {
            byte[] salt = new byte[SALT_LENGTH];
            SECURE_RANDOM.nextBytes(salt);
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            SecretKey key = this.deriveKey(this.password, salt);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] output = new byte[salt.length + iv.length + ciphertext.length];
            System.arraycopy(salt, 0, output, 0, salt.length);
            System.arraycopy(iv, 0, output, salt.length, iv.length);
            System.arraycopy(ciphertext, 0, output, salt.length + iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(output);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to encrypt dashboard data", (Throwable)e);
            return null;
        }
    }

    public String decrypt(String encryptedBase64) {
        if (!this.unlocked || this.password == null) {
            return null;
        }
        try {
            byte[] data = Base64.getDecoder().decode(encryptedBase64);
            if (data.length < SALT_LENGTH + IV_LENGTH + (GCM_TAG_LENGTH / 8)) {
                SolidusAnalyticsMod.LOGGER.warn("Refusing to decrypt malformed dashboard payload.");
                return null;
            }
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[data.length - SALT_LENGTH - IV_LENGTH];
            System.arraycopy(data, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(data, SALT_LENGTH, iv, 0, IV_LENGTH);
            System.arraycopy(data, SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertext.length);
            SecretKey key = this.deriveKey(this.password, salt);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            SolidusAnalyticsMod.LOGGER.error("Failed to decrypt dashboard data", (Throwable)e);
            return null;
        }
    }

    private SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static String hashPassword(char[] password) {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        try {
            byte[] hash = derivePasswordHash(password, salt, PBKDF2_ITERATIONS);
            return "pbkdf2$sha256$" + PBKDF2_ITERATIONS + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        }
        catch (Exception e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        }
    }

    public static boolean verifyPassword(char[] password, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            String[] parts = storedHash.split("\\$", -1);
            if (parts.length != 5 || !"pbkdf2".equals(parts[0]) || !"sha256".equals(parts[1])) {
                return verifyLegacyPassword(password, storedHash);
            }
            int iterations = Integer.parseInt(parts[2]);
            if (iterations < 100000 || iterations > 1000000) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[3]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[4]);
            if (salt.length != SALT_LENGTH || expected.length == 0) {
                return false;
            }
            byte[] actual = derivePasswordHash(password, salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static byte[] derivePasswordHash(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        }
        finally {
            spec.clearPassword();
        }
    }

    private static boolean verifyLegacyPassword(char[] password, String storedHash) {
        try {
            String[] parts = storedHash.split(":", 2);
            if (parts.length != 2) return false;
            byte[] salt = hexToBytes(parts[0]);
            byte[] expectedHash = hexToBytes(parts[1]);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(new String(password).getBytes(StandardCharsets.UTF_8));
            digest.update(salt);
            return MessageDigest.isEqual(digest.digest(), expectedHash);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte)((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
