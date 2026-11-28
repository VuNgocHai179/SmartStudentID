package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.smartstudentid.security.SecurityEnvironmentChecker;

import org.junit.Test;

/**
 * Unit tests for RuntimeDefenseMonitorView.DefenseMonitorState live scan mapping
 * across optimized (§5 After) and unoptimized (§5 Before) states.
 */
public class RuntimeDefenseMonitorTest {

    @Test
    public void cleanScanWithBiometricScoresAllSixProbesSafe() {
        RuntimeDefenseMonitorView.DefenseMonitorState state =
                new RuntimeDefenseMonitorView.DefenseMonitorState();
        SecurityEnvironmentChecker.SecurityAuditReport clean =
                new SecurityEnvironmentChecker.SecurityAuditReport(
                        false, false, false, false, false, true, true,
                        "9f8e7d6c5b4a3210fedcba9876543210");

        state.applyLiveScan(clean, true, false, true, true, true, "TEE", 6L);

        assertEquals(6, state.getPassedProbeCount());
        assertFalse(state.threatActive);
        assertFalse(state.securityBypassActive);
        assertTrue(state.flagSecureOn);
        assertTrue(state.l1RamPresent);
        assertEquals("9F8E7D6C...", state.probeValues[2]);
        assertEquals("TEE", state.probeValues[5]);
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_SAFE, state.bannerSeverity);
    }

    @Test
    public void cleanScanWithoutBiometricWarnsOnlyOnBiometricProbe() {
        RuntimeDefenseMonitorView.DefenseMonitorState state =
                new RuntimeDefenseMonitorView.DefenseMonitorState();
        SecurityEnvironmentChecker.SecurityAuditReport clean =
                new SecurityEnvironmentChecker.SecurityAuditReport(
                        false, false, false, false, false, true, true, "abcdef12");

        state.applyLiveScan(clean, false, false, false, false, false, "TEE", 4L);

        assertEquals(5, state.getPassedProbeCount());
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_WARN, state.probeStates[5]);
        assertEquals("Chưa đăng ký", state.probeValues[5]);
        assertFalse(state.threatActive);
    }

    @Test
    public void unoptimizedBypassShowsZeroSafeAndAlertsAllSixProbes() {
        RuntimeDefenseMonitorView.DefenseMonitorState state =
                new RuntimeDefenseMonitorView.DefenseMonitorState();
        SecurityEnvironmentChecker.SecurityAuditReport clean =
                new SecurityEnvironmentChecker.SecurityAuditReport(
                        false, false, false, false, false, true, true, "abcdef12");

        state.applyLiveScan(clean, true, true, false, true, true, "TEE", 3L);

        assertTrue(state.securityBypassActive);
        assertTrue(state.threatActive);
        assertEquals(0, state.getPassedProbeCount());
        for (int s : state.probeStates) {
            assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_ALERT, s);
        }
        assertTrue(state.bannerText.contains("CHƯA TỐI ƯU"));
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_ALERT, state.bannerSeverity);
    }

    @Test
    public void realFridaHookMarksThreatWhenBypassOff() {
        RuntimeDefenseMonitorView.DefenseMonitorState state =
                new RuntimeDefenseMonitorView.DefenseMonitorState();
        SecurityEnvironmentChecker.SecurityAuditReport hooked =
                new SecurityEnvironmentChecker.SecurityAuditReport(
                        false, false, false, true, false, true, true, "abcdef12");

        state.applyLiveScan(hooked, true, false, false, false, true, "TEE", 5L);

        assertTrue(state.threatActive);
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_ALERT, state.probeStates[0]);
        assertEquals("Frida Hook!", state.probeValues[0]);
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_ALERT, state.bannerSeverity);
    }
}
