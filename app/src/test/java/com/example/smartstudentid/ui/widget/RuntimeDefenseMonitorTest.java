package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.smartstudentid.security.SecurityEnvironmentChecker;

import org.junit.Test;

/**
 * Unit tests for RuntimeDefenseMonitorView.DefenseMonitorState probe mapping,
 * Frida active-defense reaction, FLAG_SECURE toggle, and background auto-lock transitions.
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

        state.applyLiveScan(clean, true, true, true, true, "TEE", 6L);

        assertEquals(6, state.getPassedProbeCount());
        assertFalse(state.threatActive);
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

        state.applyLiveScan(clean, false, false, false, false, "TEE", 4L);

        assertEquals(5, state.getPassedProbeCount());
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_WARN, state.probeStates[5]);
        assertEquals("Chưa đăng ký", state.probeValues[5]);
        assertFalse(state.threatActive);
    }

    @Test
    public void simulatedFridaDefenseMarksThreatAndEvictsL1Ram() {
        RuntimeDefenseMonitorView.DefenseMonitorState state =
                new RuntimeDefenseMonitorView.DefenseMonitorState();
        state.l1RamPresent = true;

        state.applySimulatedFridaDefense(false, 2L);

        assertTrue(state.threatActive);
        assertFalse(state.l1RamPresent);
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_ALERT, state.probeStates[0]);
        assertEquals("Frida Hook!", state.probeValues[0]);
        assertTrue(state.bannerText.contains("PHẢN ỨNG CHỦ ĐỘNG"));
        assertTrue(state.bannerText.contains("2 ms"));
    }

    @Test
    public void flagSecureToggleAndBackgroundLockUpdateStateCleanly() {
        RuntimeDefenseMonitorView.DefenseMonitorState state =
                new RuntimeDefenseMonitorView.DefenseMonitorState();
        state.l1RamPresent = true;

        state.applyFlagSecureToggled(true);
        assertTrue(state.flagSecureOn);
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_SAFE, state.bannerSeverity);

        state.applyBackgroundLockTriggered(true);
        assertFalse(state.l1RamPresent);
        assertEquals("Đã khóa L1", state.probeValues[4]);
        assertEquals(RuntimeDefenseMonitorView.DefenseMonitorState.STATE_WARN, state.bannerSeverity);
    }
}
