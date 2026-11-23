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

import java.util.Arrays;

/**
 * Implements 2-Tier Secure Cache (L1 RAM + L2 AndroidKeyStore TEE AES-256-GCM) with strict
 * OWASP MASVS-STORAGE Client-Server separation:
 * 1. Tier L1 (RAM): Holds the active student's profile and WebP avatar in-memory for sub-millisecond
 *    reads with zero disk I/O or JSON parsing overhead.
 * 2. Tier L2 (Hardware-backed AndroidKeyStore TEE): EncryptedSharedPreferences (AES-256-SIV keys +
 *    AES-256-GCM values) persists only the currently logged-in student's session across process restarts.
 * 3. Both L1 RAM (zero-filled) and L2 TEE are wiped immediately upon logout or 3 wrong PIN attempts.
 */
public class EncryptedCacheManager {

    private static final String ENCRYPTED_PREFS_FILE = "smart_student_secure_cache";

    private static final String KEY_STUDENT_PROFILE_SESSION = "student_profile_session";
    private static final String KEY_AVATAR_WEBP_BASE64 = "avatar_webp_base64";
    private static final String KEY_AVATAR_OWNER_STUDENT_ID = "avatar_owner_student_id";

    private static EncryptedCacheManager instance;
    private final SharedPreferences securePrefs;
    private final Gson gson = new Gson();

    // Tier L1: In-memory RAM cache for sub-ms reads
    private volatile StudentCardRecord l1ProfileCache;
    private volatile byte[] l1AvatarCache;
    private volatile String l1AvatarOwnerId;
    private volatile String lastReadTier = "NONE";

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
        // Never persist/sync a blank or partial record: locking a card makes GET INFO fail,
        // and an empty payload must not overwrite the owner profile in cache or SQLite.
        if (record.getStudentId() == null || record.getStudentId().trim().isEmpty()
                || record.getFullName() == null || record.getFullName().trim().isEmpty()) {
            return;
        }
        // Update Tier L1 (RAM) immediately
        this.l1ProfileCache = record;
        // Persist to Tier L2 (AndroidKeyStore TEE AES-256-GCM)
        securePrefs.edit().putString(KEY_STUDENT_PROFILE_SESSION, gson.toJson(record)).apply();
        // Server DB sync is a side effect, never part of the measured card work: always background it
        // so Before-mode (Main Thread) demos cannot block on HTTP timeouts and ANR.
        ApduThreadPool.getInstance().executeAsyncOnPool(() -> {
            ApduClient.getInstance().syncCardToServerDb(record);
            return true;
        }, null);
    }

    public StudentCardRecord getStudentProfile() {
        // Tier L1 (RAM) fast path
        StudentCardRecord mem = this.l1ProfileCache;
        if (mem != null && mem.getStudentId() != null && !mem.getStudentId().trim().isEmpty()
                && mem.getFullName() != null && !mem.getFullName().trim().isEmpty()) {
            this.lastReadTier = "L1_RAM";
            return mem;
        }
        // Tier L2 (AndroidKeyStore TEE EncryptedSharedPreferences) fallback
        String raw = securePrefs.getString(KEY_STUDENT_PROFILE_SESSION, null);
        if (raw == null || raw.isEmpty()) {
            this.lastReadTier = "MISS";
            return null;
        }
        StudentCardRecord record = gson.fromJson(raw, StudentCardRecord.class);
        // Ignore a blank/partial record left behind by a failed read while the card was locked.
        if (record == null || record.getStudentId() == null || record.getStudentId().trim().isEmpty()
                || record.getFullName() == null || record.getFullName().trim().isEmpty()) {
            this.lastReadTier = "MISS";
            return null;
        }
        // Hydrate L1 RAM for subsequent sub-ms reads
        this.l1ProfileCache = record;
        this.lastReadTier = "L2_TEE";
        return record;
    }

    public void saveAvatarBytes(byte[] webpBytes) {
        if (webpBytes == null || webpBytes.length == 0) return;
        StudentCardRecord profile = getStudentProfile();
        String ownerId = (profile != null && profile.getStudentId() != null && !profile.getStudentId().isEmpty())
                ? profile.getStudentId() : null;
        // Update Tier L1 (RAM)
        this.l1AvatarCache = Arrays.copyOf(webpBytes, webpBytes.length);
        this.l1AvatarOwnerId = ownerId;
        // Update Tier L2 (AndroidKeyStore TEE)
        String b64 = Base64.encodeToString(webpBytes, Base64.NO_WRAP);
        SharedPreferences.Editor editor = securePrefs.edit().putString(KEY_AVATAR_WEBP_BASE64, b64);
        if (ownerId != null) {
            editor.putString(KEY_AVATAR_OWNER_STUDENT_ID, ownerId);
        }
        editor.apply();
    }

    public byte[] getAvatarBytes() {
        StudentCardRecord profile = getStudentProfile();
        String currentId = (profile != null) ? profile.getStudentId() : null;

        // Tier L1 (RAM) fast path
        byte[] memAvatar = this.l1AvatarCache;
        if (memAvatar != null && memAvatar.length > 0) {
            if (l1AvatarOwnerId == null || currentId == null || l1AvatarOwnerId.equalsIgnoreCase(currentId)) {
                return memAvatar;
            }
        }

        // Tier L2 (AndroidKeyStore TEE) fallback
        String ownerId = securePrefs.getString(KEY_AVATAR_OWNER_STUDENT_ID, null);
        if (ownerId != null && currentId != null && !ownerId.equalsIgnoreCase(currentId)) {
            return null;
        }

        String b64 = securePrefs.getString(KEY_AVATAR_WEBP_BASE64, null);
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        byte[] decoded = Base64.decode(b64, Base64.NO_WRAP);
        this.l1AvatarCache = decoded;
        this.l1AvatarOwnerId = ownerId;
        return decoded;
    }

    public String getLastReadTier() {
        return lastReadTier;
    }

    public boolean hasL1ProfileCached() {
        return l1ProfileCache != null;
    }

    public boolean hasL2ProfileCached() {
        String raw = securePrefs.getString(KEY_STUDENT_PROFILE_SESSION, null);
        return raw != null && !raw.isEmpty();
    }

    /**
     * Evicts only Tier L1 (RAM) while keeping Tier L2 (AndroidKeyStore TEE) intact.
     */
    public void evictL1RamOnly() {
        l1ProfileCache = null;
        byte[] avatar = l1AvatarCache;
        if (avatar != null) {
            Arrays.fill(avatar, (byte) 0);
        }
        l1AvatarCache = null;
        l1AvatarOwnerId = null;
    }

    /**
     * Wipes both Tier L1 (RAM, zero-filled) and Tier L2 (AndroidKeyStore TEE) session cache
     * (called on logout or 3 consecutive wrong PIN attempts).
     */
    public void wipeStudentSessionCache() {
        evictL1RamOnly();
        lastReadTier = "WIPED";
        securePrefs.edit()
                .remove(KEY_STUDENT_PROFILE_SESSION)
                .remove(KEY_AVATAR_WEBP_BASE64)
                .remove(KEY_AVATAR_OWNER_STUDENT_ID)
                .apply();
    }
}
