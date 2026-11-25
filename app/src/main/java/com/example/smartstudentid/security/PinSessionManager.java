package com.example.smartstudentid.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.util.Arrays;

/**
 * Manages the PIN-protected session lifecycle (SEC-02 / MASVS-AUTH):
 * 1. First-time login requires Smart Card scan + RSA Challenge-Response + PIN entry.
 * 2. Subsequent app launches keep the session bound to the card and require only the 6-digit PIN
 *    (verified against a PBKDF2WithHmacSHA256 salted hash stored inside EncryptedSharedPreferences).
 * 3. Entering an incorrect PIN 3 consecutive times on the device automatically wipes the local
 *    session cache (Auto-Wipe) and forces a fresh card scan.
 *
 * <p>§5.2: the PIN is passed in as char[] and the PBKDF2 derivation zero-fills it, so no String
 * copy is created for the verifier path; the transient on-card PIN is kept as char[] too.</p>
 */
public class PinSessionManager {

    private static final String KEY_HAS_ACTIVE_SESSION = "session_has_active_card";
    private static final String KEY_ACTIVE_STUDENT_ID = "session_active_student_id";
    private static final String KEY_ACTIVE_STUDENT_NAME = "session_active_student_name";
    private static final String KEY_PIN_SALT_B64 = "session_pin_salt_b64";
    private static final String KEY_PIN_HASH_B64 = "session_pin_hash_b64";
    private static final String KEY_FAILED_UNLOCK_COUNT = "session_failed_unlock_count";
    private static final String KEY_SESSION_UNLOCK_NONCE_B64 = "session_unlock_nonce_b64";

    public static final int MAX_LOCAL_UNLOCK_ATTEMPTS = 3;
    public static final long BACKGROUND_LOCK_TIMEOUT_MS = 120_000L;

    private final EncryptedCacheManager cacheManager;
    private final SharedPreferences prefs;

    // Transient in-memory copy of the verified PIN for silent background card verification
    private static char[] transientVerifiedPin = null;

    public PinSessionManager(Context context) {
        this.cacheManager = EncryptedCacheManager.getInstance(context);
        this.prefs = cacheManager.getRawSharedPreferences();
    }

    /**
     * Registers a new authenticated card session after successful first-time card scan + RSA + PIN check.
     * The supplied char[] is copied to the transient buffer and then zero-filled by PBKDF2.
     */
    public void createPinSession(String studentId, String studentName, char[] pin) {
        byte[] salt = CryptoManager.generateRandomBytes(16);
        String saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP);
        byte[] unlockNonce = CryptoManager.generateRandomBytes(32);
        String unlockNonceB64 = Base64.encodeToString(unlockNonce, Base64.NO_WRAP);
        Arrays.fill(unlockNonce, (byte) 0);
        setTransientPin(pin);
        String verifierHash = CryptoManager.computePinVerifierHash(pin, salt);

        BiometricKeyManager.clearBindingPrefs(prefs);
        prefs.edit()
                .putBoolean(KEY_HAS_ACTIVE_SESSION, true)
                .putString(KEY_ACTIVE_STUDENT_ID, studentId)
                .putString(KEY_ACTIVE_STUDENT_NAME, studentName)
                .putString(KEY_PIN_SALT_B64, saltB64)
                .putString(KEY_PIN_HASH_B64, verifierHash)
                .putString(KEY_SESSION_UNLOCK_NONCE_B64, unlockNonceB64)
                .putInt(KEY_FAILED_UNLOCK_COUNT, 0)
                .apply();
    }

    /**
     * Verifies the user's PIN during subsequent app launches without requiring a new card scan.
     * Automatically wipes the session if 3 consecutive attempts fail.
     * The supplied char[] is zero-filled by PBKDF2 on every attempt.
     */
    public boolean verifyLocalSessionPin(char[] inputPin) {
        if (!hasActiveSession()) {
            return false;
        }
        String saltB64 = prefs.getString(KEY_PIN_SALT_B64, null);
        String expectedHash = prefs.getString(KEY_PIN_HASH_B64, null);
        if (saltB64 == null || expectedHash == null) {
            clearSession();
            return false;
        }

        char[] verifiedCopy = inputPin != null ? inputPin.clone() : new char[0];
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        String actualHash = CryptoManager.computePinVerifierHash(inputPin, salt);

        if (CryptoManager.constantTimeEquals(expectedHash, actualHash)) {
            prefs.edit().putInt(KEY_FAILED_UNLOCK_COUNT, 0).apply();
            setTransientPin(verifiedCopy);
            return true;
        } else {
            Arrays.fill(verifiedCopy, '\0');
            int fails = getFailedUnlockCount() + 1;
            if (fails >= MAX_LOCAL_UNLOCK_ATTEMPTS) {
                // Physical security defence: Auto-wipe session & encrypted cache after 3 failed PIN attempts
                clearSession();
            } else {
                prefs.edit().putInt(KEY_FAILED_UNLOCK_COUNT, fails).apply();
            }
            return false;
        }
    }

    /**
     * Updates the PBKDF2 verifier hash when the student changes their card PIN (INS 0x11).
     */
    public void updateSessionPin(char[] newPin) {
        if (!hasActiveSession()) return;
        createPinSession(getActiveStudentId(), getActiveStudentName(), newPin);
    }

    public boolean hasActiveSession() {
        return prefs.getBoolean(KEY_HAS_ACTIVE_SESSION, false);
    }

    public String getActiveStudentId() {
        return prefs.getString(KEY_ACTIVE_STUDENT_ID, "");
    }

    public String getActiveStudentName() {
        return prefs.getString(KEY_ACTIVE_STUDENT_NAME, "");
    }

    public int getFailedUnlockCount() {
        return prefs.getInt(KEY_FAILED_UNLOCK_COUNT, 0);
    }

    public int getRemainingUnlockAttempts() {
        return Math.max(0, MAX_LOCAL_UNLOCK_ATTEMPTS - getFailedUnlockCount());
    }

    /**
     * Returns a copy of the transient PIN as char[] so callers can build APDU bytes and then
     * wipe it; the session's own copy is never handed out directly.
     */
    public char[] getTransientVerifiedPinChars() {
        return transientVerifiedPin != null ? transientVerifiedPin.clone() : null;
    }

    private static void setTransientPin(char[] pin) {
        clearTransientPin();
        if (pin != null) {
            transientVerifiedPin = pin.clone();
        }
    }

    private static void clearTransientPin() {
        if (transientVerifiedPin != null) {
            Arrays.fill(transientVerifiedPin, '\0');
            transientVerifiedPin = null;
        }
    }

    /**
     * Returns the 32-byte session unlock nonce used to bind hardware Biometric CryptoObject keys.
     * Callers must zero-fill the returned byte[] after use.
     */
    public byte[] getOrCreateSessionUnlockNonce() {
        String existing = prefs.getString(KEY_SESSION_UNLOCK_NONCE_B64, null);
        if (existing != null && !existing.isEmpty()) {
            return Base64.decode(existing, Base64.NO_WRAP);
        }
        byte[] fresh = CryptoManager.generateRandomBytes(32);
        prefs.edit()
                .putString(KEY_SESSION_UNLOCK_NONCE_B64, Base64.encodeToString(fresh, Base64.NO_WRAP))
                .apply();
        return fresh;
    }

    /**
     * Clears only the transient in-memory PIN char[] without ending the persisted session.
     */
    public static void clearTransientPinOnly() {
        clearTransientPin();
    }

    /**
     * Locks an active session after background timeout or threat detection:
     * wipes transient RAM PIN and evicts L1 RAM cache while retaining L2 TEE verification state.
     */
    public void lockSessionAndEvictRam() {
        clearTransientPin();
        cacheManager.evictL1RamOnly();
    }

    /**
     * Pure-Java evaluation of whether background duration exceeded the auto-lock threshold.
     */
    public static boolean shouldAutoLockAfterBackground(long backgroundStartElapsedMs,
                                                        long resumeElapsedMs,
                                                        long timeoutMs) {
        if (backgroundStartElapsedMs <= 0L || timeoutMs <= 0L || resumeElapsedMs < backgroundStartElapsedMs) {
            return false;
        }
        return (resumeElapsedMs - backgroundStartElapsedMs) >= timeoutMs;
    }

    /**
     * Completely wipes the active PIN session and cached student profile ("Switch Card" or Auto-Wipe).
     */
    public void clearSession() {
        clearTransientPin();
        BiometricKeyManager.clearBindingPrefs(prefs);
        prefs.edit()
                .remove(KEY_HAS_ACTIVE_SESSION)
                .remove(KEY_ACTIVE_STUDENT_ID)
                .remove(KEY_ACTIVE_STUDENT_NAME)
                .remove(KEY_PIN_SALT_B64)
                .remove(KEY_PIN_HASH_B64)
                .remove(KEY_SESSION_UNLOCK_NONCE_B64)
                .remove(KEY_FAILED_UNLOCK_COUNT)
                .apply();
        cacheManager.wipeStudentSessionCache();
    }
}
