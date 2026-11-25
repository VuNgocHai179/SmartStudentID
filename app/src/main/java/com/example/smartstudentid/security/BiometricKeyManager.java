package com.example.smartstudentid.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.RequiresApi;
import androidx.biometric.BiometricManager;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.ProviderException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed Biometric key manager (MASVS-AUTH & MASVS-CRYPTO).
 * Binds biometric authentication to an AES-256-GCM SecretKey inside AndroidKeyStore
 * (StrongBox when available, otherwise TEE) via BiometricPrompt.CryptoObject.
 */
public final class BiometricKeyManager {

    private static final String KEY_ALIAS = "smart_student_biometric_unlock_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String AES_GCM_CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private static final String KEY_BIO_BOUND_STUDENT_ID = "bio_bound_student_id";
    private static final String KEY_BIO_CIPHERTEXT_B64 = "bio_unlock_ciphertext_b64";
    private static final String KEY_BIO_IV_B64 = "bio_unlock_iv_b64";
    private static final String KEY_BIO_IS_STRONGBOX = "bio_key_is_strongbox";

    private final SharedPreferences prefs;

    public BiometricKeyManager(Context context) {
        this.prefs = EncryptedCacheManager.getInstance(context).getRawSharedPreferences();
    }

    public static boolean isBiometricStrongAvailable(Context context) {
        if (context == null) {
            return false;
        }
        try {
            int result = BiometricManager.from(context)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            return result == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEnrolledForStudent(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            return false;
        }
        String boundId = prefs.getString(KEY_BIO_BOUND_STUDENT_ID, "");
        String cipherB64 = prefs.getString(KEY_BIO_CIPHERTEXT_B64, "");
        String ivB64 = prefs.getString(KEY_BIO_IV_B64, "");
        if (!studentId.equalsIgnoreCase(boundId) || cipherB64.isEmpty() || ivB64.isEmpty()) {
            return false;
        }
        return loadKeyFromKeyStore() != null;
    }

    public String getBackingStoreLabel() {
        return prefs.getBoolean(KEY_BIO_IS_STRONGBOX, false) ? "StrongBox" : "TEE";
    }

    public Cipher initEncryptCipher() throws Exception {
        SecretKey key = generateOrReplaceHardwareKey();
        Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher;
    }

    public Cipher initDecryptCipher() throws Exception {
        SecretKey key = loadKeyFromKeyStore();
        if (key == null) {
            throw new IllegalStateException("Biometric key not initialized");
        }
        String ivB64 = prefs.getString(KEY_BIO_IV_B64, null);
        if (ivB64 == null || ivB64.isEmpty()) {
            throw new IllegalStateException("Biometric IV missing");
        }
        byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }

    public void completeBinding(String studentId, Cipher encryptCipher, byte[] sessionNonce) throws Exception {
        if (studentId == null || encryptCipher == null || sessionNonce == null || sessionNonce.length == 0) {
            throw new IllegalArgumentException("Invalid biometric binding input");
        }
        byte[] ciphertext = encryptCipher.doFinal(sessionNonce);
        byte[] iv = encryptCipher.getIV();
        prefs.edit()
                .putString(KEY_BIO_BOUND_STUDENT_ID, studentId)
                .putString(KEY_BIO_CIPHERTEXT_B64, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_BIO_IV_B64, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply();
    }

    public boolean verifyDecryptedNonce(Cipher decryptCipher, byte[] expectedNonce) throws Exception {
        if (decryptCipher == null || expectedNonce == null || expectedNonce.length == 0) {
            return false;
        }
        String cipherB64 = prefs.getString(KEY_BIO_CIPHERTEXT_B64, null);
        if (cipherB64 == null || cipherB64.isEmpty()) {
            return false;
        }
        byte[] ciphertext = Base64.decode(cipherB64, Base64.NO_WRAP);
        byte[] decrypted = null;
        try {
            decrypted = decryptCipher.doFinal(ciphertext);
            return MessageDigest.isEqual(decrypted, expectedNonce);
        } finally {
            if (decrypted != null) {
                Arrays.fill(decrypted, (byte) 0);
            }
        }
    }

    public void clearBinding() {
        clearBindingPrefs(prefs);
        deleteKeyStoreAliasSafely();
    }

    public static void clearBindingPrefs(SharedPreferences prefs) {
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .remove(KEY_BIO_BOUND_STUDENT_ID)
                .remove(KEY_BIO_CIPHERTEXT_B64)
                .remove(KEY_BIO_IV_B64)
                .remove(KEY_BIO_IS_STRONGBOX)
                .apply();
        deleteKeyStoreAliasSafely();
    }

    private SecretKey generateOrReplaceHardwareKey() throws Exception {
        deleteKeyStoreAliasSafely();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                SecretKey strongBoxKey = generateKeyInternal(true);
                prefs.edit().putBoolean(KEY_BIO_IS_STRONGBOX, true).apply();
                return strongBoxKey;
            } catch (ProviderException strongBoxUnavailable) {
                // StrongBoxUnavailableException extends ProviderException; fall back to hardware TEE.
            }
        }
        SecretKey teeKey = generateKeyInternal(false);
        prefs.edit().putBoolean(KEY_BIO_IS_STRONGBOX, false).apply();
        return teeKey;
    }

    private SecretKey generateKeyInternal(boolean useStrongBox) throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Helper.setAuthParameters(builder);
        } else {
            setLegacyAuthValidity(builder);
        }

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28Helper.setStrongBoxBacked(builder, true);
        }

        keyGenerator.init(builder.build());
        return keyGenerator.generateKey();
    }

    @SuppressWarnings("deprecation")
    private static void setLegacyAuthValidity(KeyGenParameterSpec.Builder builder) {
        builder.setUserAuthenticationValidityDurationSeconds(-1);
    }

    private static SecretKey loadKeyFromKeyStore() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(KEY_ALIAS)) {
                return null;
            }
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteKeyStoreAliasSafely() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception ignored) {
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static final class Api28Helper {
        static void setStrongBoxBacked(KeyGenParameterSpec.Builder builder, boolean backed) {
            builder.setIsStrongBoxBacked(backed);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static final class Api30Helper {
        static void setAuthParameters(KeyGenParameterSpec.Builder builder) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        }
    }
}
