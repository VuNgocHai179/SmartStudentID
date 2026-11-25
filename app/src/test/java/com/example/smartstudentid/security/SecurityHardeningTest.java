package com.example.smartstudentid.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Unit tests for MASVS-RESILIENCE runtime checks, APK certificate verification,
 * and background session auto-lock thresholds.
 */
public class SecurityHardeningTest {

    @Test
    public void hookingSignatureDetectsFridaXposedLsposedAndSubstrate() {
        assertTrue(SecurityEnvironmentChecker.containsHookingSignature(
                "7f8a1000-7f8a9000 r-xp /data/local/tmp/frida-agent-64.so"));
        assertTrue(SecurityEnvironmentChecker.containsHookingSignature(
                "7f8a1000-7f8a9000 r--p /system/framework/XposedBridge.jar"));
        assertTrue(SecurityEnvironmentChecker.containsHookingSignature(
                "7f8a1000-7f8a9000 r-xp /data/adb/lspd/liblsposed.so"));
        assertTrue(SecurityEnvironmentChecker.containsHookingSignature(
                "7f8a1000-7f8a9000 r-xp /data/local/libsubstrate.so"));

        assertFalse(SecurityEnvironmentChecker.containsHookingSignature(
                "7f8a1000-7f8a9000 r-xp /system/lib64/libc.so"));
        assertFalse(SecurityEnvironmentChecker.containsHookingSignature(
                "7f8a1000-7f8a9000 r--p /data/app/com.example.smartstudentid/oat/arm64/base.odex"));
        assertFalse(SecurityEnvironmentChecker.containsHookingSignature(null));
    }

    @Test
    public void parseTracerPidExtractsPidOrReturnsMinusOne() {
        assertEquals(0, SecurityEnvironmentChecker.parseTracerPid("TracerPid:\t0"));
        assertEquals(4821, SecurityEnvironmentChecker.parseTracerPid("TracerPid:\t4821"));
        assertEquals(-1, SecurityEnvironmentChecker.parseTracerPid("Pid:\t1234"));
        assertEquals(-1, SecurityEnvironmentChecker.parseTracerPid("TracerPid:\tabc"));
        assertEquals(-1, SecurityEnvironmentChecker.parseTracerPid(null));
    }

    @Test
    public void matchesExpectedSignatureNormalizesCaseAndColons() {
        String actual = SecurityEnvironmentChecker.sha256Hex("SmartStudentID-APK".getBytes(StandardCharsets.UTF_8));
        StringBuilder colonUpper = new StringBuilder();
        for (int i = 0; i < actual.length(); i += 2) {
            if (i > 0) colonUpper.append(':');
            colonUpper.append(actual.substring(i, i + 2).toUpperCase());
        }

        assertTrue(SecurityEnvironmentChecker.matchesExpectedSignature(actual, colonUpper.toString()));
        assertFalse(SecurityEnvironmentChecker.matchesExpectedSignature(actual, "00000000deadbeef"));
        assertFalse(SecurityEnvironmentChecker.matchesExpectedSignature(null, actual));
    }

    @Test
    public void securityAuditReportSeparatesDebugAndReleaseCriticalThreats() {
        SecurityEnvironmentChecker.SecurityAuditReport clean =
                new SecurityEnvironmentChecker.SecurityAuditReport(false, false, false, false, false, true, true, "abcd");
        assertFalse(clean.hasCriticalThreat(true));
        assertFalse(clean.hasCriticalThreat(false));

        SecurityEnvironmentChecker.SecurityAuditReport rootAndDebug =
                new SecurityEnvironmentChecker.SecurityAuditReport(true, true, true, false, false, true, true, "abcd");
        assertFalse(rootAndDebug.hasCriticalThreat(true));
        assertTrue(rootAndDebug.hasCriticalThreat(false));

        SecurityEnvironmentChecker.SecurityAuditReport fridaHook =
                new SecurityEnvironmentChecker.SecurityAuditReport(false, false, false, true, false, true, true, "abcd");
        assertTrue(fridaHook.hasCriticalThreat(true));
        assertTrue(fridaHook.hasCriticalThreat(false));

        SecurityEnvironmentChecker.SecurityAuditReport repackagedApk =
                new SecurityEnvironmentChecker.SecurityAuditReport(false, false, false, false, false, true, false, "abcd");
        assertTrue(repackagedApk.hasCriticalThreat(true));
        assertTrue(repackagedApk.hasCriticalThreat(false));
    }

    @Test
    public void shouldAutoLockAfterBackgroundRespectsTimeoutBoundary() {
        long timeout = PinSessionManager.BACKGROUND_LOCK_TIMEOUT_MS;
        assertFalse(PinSessionManager.shouldAutoLockAfterBackground(0L, 200_000L, timeout));
        assertFalse(PinSessionManager.shouldAutoLockAfterBackground(10_000L, 10_000L + timeout - 1L, timeout));
        assertTrue(PinSessionManager.shouldAutoLockAfterBackground(10_000L, 10_000L + timeout, timeout));
        assertTrue(PinSessionManager.shouldAutoLockAfterBackground(10_000L, 10_000L + timeout + 5_000L, timeout));
        assertFalse(PinSessionManager.shouldAutoLockAfterBackground(20_000L, 10_000L, timeout));
    }
}
