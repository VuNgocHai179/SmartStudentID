package com.example.smartstudentid.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

/**
 * Manages the PIN-protected session lifecycle (SEC-02 / MASVS-AUTH):
 * 1. First-time login requires Smart Card scan + RSA Challenge-Response + PIN entry.
 * 2. Subsequent app launches keep the session bound to the card and require only the 6-digit PIN
 *    (verified against a PBKDF2WithHmacSHA256 salted hash stored inside EncryptedSharedPreferences).
 * 3. Entering an incorrect PIN 3 consecutive times on the device automatically wipes the local
 *    session cache (Auto-Wipe) and forces a fresh card scan.
 */
public class PinSessionManager {

    private static final String KEY_HAS_ACTIVE_SESSION = "session_has_active_card";
    private static final String KEY_ACTIVE_STUDENT_ID = "session_active_student_id";
    private static final String KEY_ACTIVE_STUDENT_NAME = "session_active_student_name";
    private static final String KEY_PIN_SALT_B64 = "session_pin_salt_b64";
    private static final String KEY_PIN_HASH_B64 = "session_pin_hash_b64";
    private static final String KEY_FAILED_UNLOCK_COUNT = "session_failed_unlock_count";

    public static final int MAX_LOCAL_UNLOCK_ATTEMPTS = 3;

    private final EncryptedCacheManager cacheManager;
    private final SharedPreferences prefs;

    // Transient in-memory copy of the verified PIN for silent background card verification
    private static String transientVerifiedPin = null;

    public PinSessionManager(Context context) {
        this.cacheManager = EncryptedCacheManager.getInstance(context);
        this.prefs = cacheManager.getRawSharedPreferences();
    }

    /**
     * Registers a new authenticated card session after successful first-time card scan + RSA + PIN check.
     */
    public void createPinSession(String studentId, String studentName, String pin) {
        byte[] salt = CryptoManager.generateRandomBytes(16);
        String saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP);
        String verifierHash = CryptoManager.computePinVerifierHash(pin, salt);

        prefs.edit()
                .putBoolean(KEY_HAS_ACTIVE_SESSION, true)
                .putString(KEY_ACTIVE_STUDENT_ID, studentId)
                .putString(KEY_ACTIVE_STUDENT_NAME, studentName)
                .putString(KEY_PIN_SALT_B64, saltB64)
                .putString(KEY_PIN_HASH_B64, verifierHash)
                .putInt(KEY_FAILED_UNLOCK_COUNT, 0)
                .apply();

        transientVerifiedPin = pin;
    }

    /**
     * Verifies the user's PIN during subsequent app launches without requiring a new card scan.
     * Automatically wipes the session if 3 consecutive attempts fail.
     */
    public boolean verifyLocalSessionPin(String inputPin) {
        if (!hasActiveSession()) {
            return false;
        }
        String saltB64 = prefs.getString(KEY_PIN_SALT_B64, null);
        String expectedHash = prefs.getString(KEY_PIN_HASH_B64, null);
        if (saltB64 == null || expectedHash == null) {
            clearSession();
            return false;
        }

        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        String actualHash = CryptoManager.computePinVerifierHash(inputPin, salt);

        if (CryptoManager.constantTimeEquals(expectedHash, actualHash)) {
            prefs.edit().putInt(KEY_FAILED_UNLOCK_COUNT, 0).apply();
            transientVerifiedPin = inputPin;
            return true;
        } else {
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
    public void updateSessionPin(String newPin) {
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

    public String getTransientVerifiedPin() {
        return transientVerifiedPin;
    }

    /**
     * Completely wipes the active PIN session and cached student profile ("Switch Card" or Auto-Wipe).
     */
    public void clearSession() {
        transientVerifiedPin = null;
        prefs.edit()
                .remove(KEY_HAS_ACTIVE_SESSION)
                .remove(KEY_ACTIVE_STUDENT_ID)
                .remove(KEY_ACTIVE_STUDENT_NAME)
                .remove(KEY_PIN_SALT_B64)
                .remove(KEY_PIN_HASH_B64)
                .remove(KEY_FAILED_UNLOCK_COUNT)
                .apply();
        cacheManager.wipeStudentSessionCache();
    }
}
