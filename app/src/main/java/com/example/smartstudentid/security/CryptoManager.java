package com.example.smartstudentid.security;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic utility class implementing MASVS-CRYPTO requirements (SEC-01 & SEC-02):
 * - Key derivation from user PIN via PBKDF2WithHmacSHA256 (10,000 iterations, 256-bit key).
 * - Cryptographically secure random Salt and 16-byte Challenge Nonce generation.
 * - SHA-256 salted hashing for offline PIN session verification.
 */
public final class CryptoManager {

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 10_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoManager() {
    }

    /**
     * Generates a cryptographically strong random byte array (e.g., 16-byte Salt or Challenge Nonce).
     */
    public static byte[] generateRandomBytes(int numBytes) {
        byte[] bytes = new byte[numBytes];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * Derives a 256-bit AES key from a student PIN and random salt using PBKDF2WithHmacSHA256.
     */
    public static SecretKey deriveKeyFromPin(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(
                pin.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                KEY_LENGTH_BITS
        );
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        byte[] rawKey = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(rawKey, "AES");
    }

    /**
     * Computes a Base64-encoded PBKDF2 hash of the PIN for local session verification.
     */
    public static String computePinVerifierHash(String pin, byte[] salt) {
        try {
            SecretKey key = deriveKeyFromPin(pin, salt);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(key.getEncoded());
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Constant-time string comparison to mitigate timing side-channel attacks.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] bytesA = a.getBytes(StandardCharsets.UTF_8);
        byte[] bytesB = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(bytesA, bytesB);
    }
}
