package com.example.smartstudentid.security;

import android.os.Build;
import android.os.Debug;

import java.io.File;
import java.security.KeyStore;

/**
 * Runtime security environment inspector implementing MASVS-RESILIENCE checks (SEC-05):
 * 1. Root / su binary / Magisk / test-keys detection.
 * 2. Active Debugger attachment detection (Debug.isDebuggerConnected()).
 * 3. Emulator / virtual device detection.
 * 4. AndroidKeyStore availability check.
 */
public final class SecurityEnvironmentChecker {

    private static final String[] SU_PATHS = new String[]{
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/su/bin/su",
            "/sbin/magisk"
    };

    private SecurityEnvironmentChecker() {
    }

    /**
     * Checks whether the device has root indicators (su binary, Magisk, or test-keys build tags).
     */
    public static boolean isDeviceRooted() {
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }
        for (String path : SU_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a Java or native debugger is currently attached to the application process.
     */
    public static boolean isDebuggerAttached() {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    /**
     * Detects whether the application is running inside an Android Emulator.
     */
    public static boolean isRunningOnEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu");
    }

    /**
     * Verifies that the AndroidKeyStore provider is initialized and accessible.
     */
    public static boolean isAndroidKeyStoreAvailable() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
