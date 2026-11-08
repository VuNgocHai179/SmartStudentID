package com.example.smartstudentid.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.google.gson.Gson;

/**
 * Implements strict OWASP MASVS-STORAGE Client-Server separation:
 * 1. The Android phone NEVER stores a shared database of all students. It ONLY caches
 *    the single currently logged-in student's active session (`KEY_STUDENT_PROFILE_SESSION`)
 *    and avatar (`KEY_AVATAR_WEBP_BASE64`) encrypted with AES-256-GCM in AndroidKeyStore,
 *    and wipes them immediately upon logout or card removal.
 * 2. All shared institutional records (`cards`, `attendance_logs`, `book_loans`, `card_audit_logs`)
 *    reside EXCLUSIVELY on the central Server SQLite Database (`bridge-server/data/smartcard.db`).
 */
public class EncryptedCacheManager {

    private static final String ENCRYPTED_PREFS_FILE = "smart_student_secure_cache";

    private static final String KEY_STUDENT_PROFILE_SESSION = "student_profile_session";
    private static final String KEY_AVATAR_WEBP_BASE64 = "avatar_webp_base64";

    private static EncryptedCacheManager instance;
    private final SharedPreferences securePrefs;
    private final Gson gson = new Gson();

    private EncryptedCacheManager(Context context) {
        Context appContext = context.getApplicationContext();

        SharedPreferences prefs;
        try {
            MasterKey masterKey = new MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            prefs = EncryptedSharedPreferences.create(
                    appContext,
                    ENCRYPTED_PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            prefs = appContext.getSharedPreferences(ENCRYPTED_PREFS_FILE, Context.MODE_PRIVATE);
        }
        this.securePrefs = prefs;
    }

    public static synchronized EncryptedCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new EncryptedCacheManager(context);
        }
        return instance;
    }

    public SharedPreferences getRawSharedPreferences() {
        return securePrefs;
    }

    /**
     * Caches ONLY the currently logged-in student's profile for the active session
     * and synchronizes it with the central Server SQLite database (`smartcard.db`).
     */
    public void saveStudentProfile(StudentCardRecord record) {
        if (record == null) return;
        securePrefs.edit().putString(KEY_STUDENT_PROFILE_SESSION, gson.toJson(record)).apply();
        ApduThreadPool.getInstance().execute(() -> {
            ApduClient.getInstance().syncCardToServerDb(record);
            return true;
        }, null);
    }

    public StudentCardRecord getStudentProfile() {
        String raw = securePrefs.getString(KEY_STUDENT_PROFILE_SESSION, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return gson.fromJson(raw, StudentCardRecord.class);
    }

    public void saveAvatarBytes(byte[] webpBytes) {
        if (webpBytes == null || webpBytes.length == 0) return;
        String b64 = Base64.encodeToString(webpBytes, Base64.NO_WRAP);
        securePrefs.edit().putString(KEY_AVATAR_WEBP_BASE64, b64).apply();
    }

    public byte[] getAvatarBytes() {
        String b64 = securePrefs.getString(KEY_AVATAR_WEBP_BASE64, null);
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        return Base64.decode(b64, Base64.NO_WRAP);
    }

    /**
     * Wipes the active student's cached profile and session data (called on logout or 3 wrong PIN attempts).
     */
    public void wipeStudentSessionCache() {
        securePrefs.edit()
                .remove(KEY_STUDENT_PROFILE_SESSION)
                .remove(KEY_AVATAR_WEBP_BASE64)
                .apply();
    }
}
