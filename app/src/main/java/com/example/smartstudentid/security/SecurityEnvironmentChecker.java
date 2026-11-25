package com.example.smartstudentid.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runtime security environment inspector implementing MASVS-RESILIENCE checks (SEC-05):
 * 1. Root / su binary / Magisk / test-keys detection.
 * 2. Active Java and native debugger detection (Debug.isDebuggerConnected() + /proc/self/status TracerPid).
 * 3. Hooking framework detection (Frida / Xposed / LSPosed / Substrate via /proc/self/maps).
 * 4. APK signing certificate SHA-256 anti-repackaging verification.
 * 5. Emulator / virtual device detection and AndroidKeyStore availability check.
 * 6. Active defense that evicts transient RAM secrets when a critical threat is detected.
 */
public final class SecurityEnvironmentChecker {

    private static final String KEY_PINNED_APK_SIG_SHA256 = "pinned_apk_sig_sha256";

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

    private static final String[] HOOKING_SIGNATURES = new String[]{
            "frida",
            "frida-agent",
            "frida-gadget",
            "xposed",
            "edxposed",
            "lsposed",
            "substrate",
            "libsubstrate",
            "cydia",
            "magisk"
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
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger() || isNativeDebuggerAttached();
    }

    /**
     * Inspects /proc/self/status for TracerPid > 0 to detect native ptrace/gdb/lldb attachment.
     */
    public static boolean isNativeDebuggerAttached() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int pid = parseTracerPid(line);
                if (pid > 0) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Pure-Java helper that parses the TracerPid value from a /proc/self/status line.
     * Returns -1 if the line does not carry a valid TracerPid field.
     */
    public static int parseTracerPid(String statusLine) {
        if (statusLine == null) {
            return -1;
        }
        String trimmed = statusLine.trim();
        if (!trimmed.startsWith("TracerPid:")) {
            return -1;
        }
        String valuePart = trimmed.substring("TracerPid:".length()).trim();
        if (valuePart.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(valuePart);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Detects runtime instrumentation/hooking frameworks (Frida, Xposed, LSPosed, Substrate)
     * in /proc/self/maps and loaded classes.
     */
    public static boolean isHookingFrameworkDetected() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (Throwable ignored) {
        }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (containsHookingSignature(line)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Pure-Java helper that matches known dynamic instrumentation library names in a memory map line.
     */
    public static boolean containsHookingSignature(String mapsLine) {
        if (mapsLine == null || mapsLine.isEmpty()) {
            return false;
        }
        String lower = mapsLine.toLowerCase(Locale.US);
        for (String sig : HOOKING_SIGNATURES) {
            if (lower.contains(sig)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes lowercase SHA-256 hex digest of raw bytes.
     */
    public static String sha256Hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Constant-time comparison of normalized SHA-256 hex signatures (strips colons and whitespace).
     */
    public static boolean matchesExpectedSignature(String actualSha256, String expectedSha256) {
        if (actualSha256 == null || expectedSha256 == null) {
            return false;
        }
        String normActual = actualSha256.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.US);
        String normExpected = expectedSha256.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.US);
        if (normActual.isEmpty() || normExpected.isEmpty()) {
            return false;
        }
        return CryptoManager.constantTimeEquals(normActual, normExpected);
    }

    /**
     * Extracts the SHA-256 fingerprint of the APK signing certificate.
     */
    @SuppressWarnings("deprecation")
    public static String getApkSigningCertSha256(Context context) {
        if (context == null) {
            return "";
        }
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signingInfo = info.signingInfo;
                if (signingInfo != null) {
                    Signature[] signers = signingInfo.getApkContentsSigners();
                    if (signers == null || signers.length == 0) {
                        signers = signingInfo.getSigningCertificateHistory();
                    }
                    if (signers != null && signers.length > 0) {
                        return sha256Hex(signers[0].toByteArray());
                    }
                }
            } else {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (info.signatures != null && info.signatures.length > 0) {
                    return sha256Hex(info.signatures[0].toByteArray());
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Anti-Repackaging check: pins the APK certificate SHA-256 on first launch inside
     * hardware-backed EncryptedSharedPreferences (TOFU) and verifies it on subsequent runs.
     */
    public static boolean verifyApkIntegrity(Context context) {
        if (context == null) {
            return false;
        }
        String actual = getApkSigningCertSha256(context);
        try {
            SharedPreferences prefs = EncryptedCacheManager.getInstance(context).getRawSharedPreferences();
            String pinned = prefs.getString(KEY_PINNED_APK_SIG_SHA256, null);
            if (pinned == null || pinned.isEmpty()) {
                if (!actual.isEmpty()) {
                    prefs.edit().putString(KEY_PINNED_APK_SIG_SHA256, actual).apply();
                }
                return true;
            }
            if (actual.isEmpty()) {
                return false;
            }
            return matchesExpectedSignature(actual, pinned);
        } catch (Exception e) {
            return !actual.isEmpty();
        }
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

    /**
     * Runs a full runtime resilience audit across Root, Java/Native Debugger, Hooking,
     * Emulator, KeyStore TEE, and APK Signature Integrity.
     */
    public static SecurityAuditReport inspectEnvironment(Context context) {
        boolean rooted = isDeviceRooted();
        boolean javaDebugger = Debug.isDebuggerConnected() || Debug.waitingForDebugger();
        boolean nativeDebugger = isNativeDebuggerAttached();
        boolean hooking = isHookingFrameworkDetected();
        boolean emulator = isRunningOnEmulator();
        boolean keystoreOk = isAndroidKeyStoreAvailable();
        String certSha256 = getApkSigningCertSha256(context);
        boolean apkValid = verifyApkIntegrity(context);
        return new SecurityAuditReport(
                rooted,
                javaDebugger,
                nativeDebugger,
                hooking,
                emulator,
                keystoreOk,
                apkValid,
                certSha256
        );
    }

    /**
     * Active Defense reaction: when a critical threat is present for the current build type,
     * immediately wipe transient PIN chars and evict L1 RAM caches.
     */
    public static boolean enforceActiveDefense(Context context, SecurityAuditReport report, boolean isDebugBuild) {
        if (report == null || !report.hasCriticalThreat(isDebugBuild)) {
            return false;
        }
        PinSessionManager.clearTransientPinOnly();
        if (context != null) {
            EncryptedCacheManager.getInstance(context).evictL1RamOnly();
        }
        return true;
    }

    public static final class SecurityAuditReport {
        public final boolean rooted;
        public final boolean javaDebugger;
        public final boolean nativeDebugger;
        public final boolean hookingDetected;
        public final boolean emulator;
        public final boolean keystoreAvailable;
        public final boolean apkSignatureValid;
        public final String apkCertSha256;

        public SecurityAuditReport(boolean rooted, boolean javaDebugger, boolean nativeDebugger,
                                   boolean hookingDetected, boolean emulator, boolean keystoreAvailable,
                                   boolean apkSignatureValid, String apkCertSha256) {
            this.rooted = rooted;
            this.javaDebugger = javaDebugger;
            this.nativeDebugger = nativeDebugger;
            this.hookingDetected = hookingDetected;
            this.emulator = emulator;
            this.keystoreAvailable = keystoreAvailable;
            this.apkSignatureValid = apkSignatureValid;
            this.apkCertSha256 = apkCertSha256 != null ? apkCertSha256 : "";
        }

        public boolean hasCriticalThreat(boolean isDebugBuild) {
            if (hookingDetected || !apkSignatureValid) {
                return true;
            }
            if (!isDebugBuild && (rooted || javaDebugger || nativeDebugger)) {
                return true;
            }
            return false;
        }

        public String getShortApkCertHex() {
            if (apkCertSha256.isEmpty()) {
                return "N/A";
            }
            String upper = apkCertSha256.toUpperCase(Locale.US);
            return upper.length() > 16 ? upper.substring(0, 16) : upper;
        }

        public String buildThreatSummary(boolean isDebugBuild) {
            List<String> threats = new ArrayList<>();
            if (hookingDetected) threats.add("Frida/Xposed Hook");
            if (!apkSignatureValid) threats.add("Sai chữ ký APK");
            if (!isDebugBuild && rooted) threats.add("Root/Magisk");
            if (!isDebugBuild && (javaDebugger || nativeDebugger)) threats.add("Debugger/TracerPid");
            if (threats.isEmpty()) {
                return "An toàn";
            }
            return String.join(", ", threats);
        }
    }
}
