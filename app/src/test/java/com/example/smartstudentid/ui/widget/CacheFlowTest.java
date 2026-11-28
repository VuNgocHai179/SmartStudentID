package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link CacheFlowView.CacheFlowState} covering Before bypass/plaintext leak,
 * After 2-tier L1/L2 cache hit, 3-strike Auto-Wipe lifecycle, and clean cache reset.
 */
public class CacheFlowTest {

    @Test
    public void beforeRunBypassesCacheExposesPlaintextAndCounts36Apdus() {
        CacheFlowView.CacheFlowState state = new CacheFlowView.CacheFlowState();

        state.beginBeforeRun(1000L);
        assertTrue(state.hasRun);
        assertEquals(CacheFlowView.CacheFlowState.MODE_BEFORE, state.mode);
        assertTrue(state.runInProgress);
        assertTrue(state.plaintextExposed);
        assertEquals(CacheFlowView.CacheFlowState.NODE_BYPASS, state.l1State);
        assertEquals(CacheFlowView.CacheFlowState.NODE_FAULT, state.l2State);
        assertEquals(CacheFlowView.CacheFlowState.NODE_ACTIVE, state.chipState);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_FAULT, state.stepState[0]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_FAULT, state.stepState[1]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_RUNNING, state.stepState[2]);

        state.onBeforeApduProgress(18);
        assertEquals(18, state.apduCount);
        assertEquals(0.5f, state.getProgressRatio(), 0.001f);
        assertEquals("18/36 APDU", state.chipBadge);

        state.finishBeforeRun(2650L, 36);
        assertFalse(state.runInProgress);
        assertEquals(CacheFlowView.CacheFlowState.MODE_BEFORE, state.mode);
        assertTrue(state.plaintextExposed);
        assertEquals(CacheFlowView.CacheFlowState.NODE_BYPASS, state.l1State);
        assertEquals(CacheFlowView.CacheFlowState.NODE_FAULT, state.l2State);
        assertEquals(CacheFlowView.CacheFlowState.NODE_FAULT, state.chipState);
        assertEquals(36, state.apduCount);
        assertEquals(1f, state.getProgressRatio(), 0.001f);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_FAULT, state.stepState[0]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_FAULT, state.stepState[1]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_FAULT, state.stepState[2]);
    }

    @Test
    public void afterRunHitsL1OrL2CacheWithZeroApdu() {
        CacheFlowView.CacheFlowState state = new CacheFlowView.CacheFlowState();

        state.beginAfterRun(2000L);
        assertEquals(CacheFlowView.CacheFlowState.MODE_AFTER, state.mode);
        assertTrue(state.runInProgress);
        assertFalse(state.plaintextExposed);

        state.finishAfterRun("L1 RAM", 3L);
        assertFalse(state.runInProgress);
        assertEquals(CacheFlowView.CacheFlowState.MODE_AFTER, state.mode);
        assertFalse(state.plaintextExposed);
        assertEquals(0, state.apduCount);
        assertEquals(CacheFlowView.CacheFlowState.NODE_HIT, state.l1State);
        assertEquals("HIT <1 ms", state.l1Badge);
        assertEquals(CacheFlowView.CacheFlowState.NODE_HIT, state.l2State);
        assertEquals(CacheFlowView.CacheFlowState.NODE_HIT, state.chipState);
        assertEquals(1f, state.getProgressRatio(), 0.001f);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[0]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[1]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[2]);

        state.finishAfterRun("L2 TEE", 6L);
        assertEquals("Nạp L1", state.l1Badge);
        assertEquals("HIT TEE", state.l2Badge);
        assertEquals(0, state.apduCount);
        assertEquals(1f, state.getProgressRatio(), 0.001f);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[0]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[1]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[2]);
    }

    @Test
    public void autoWipeLifecycleTracksWrongPinsWipesAndResetsCleanly() {
        CacheFlowView.CacheFlowState state = new CacheFlowView.CacheFlowState();

        state.recordWrongPinAttempt(1, 2);
        assertEquals(CacheFlowView.CacheFlowState.MODE_PIN_WARN, state.mode);
        assertEquals(1, state.failedPinAttempts);
        assertEquals(2, state.remainingPinAttempts);
        assertEquals(1f / 3f, state.getProgressRatio(), 0.001f);

        state.recordWrongPinAttempt(2, 1);
        assertEquals(CacheFlowView.CacheFlowState.MODE_PIN_WARN, state.mode);
        assertEquals(2, state.failedPinAttempts);
        assertEquals(1, state.remainingPinAttempts);

        state.recordAutoWipeTriggered(true);
        assertEquals(CacheFlowView.CacheFlowState.MODE_WIPED, state.mode);
        assertEquals(CacheFlowView.CacheFlowState.NODE_WIPED, state.l1State);
        assertEquals(CacheFlowView.CacheFlowState.NODE_WIPED, state.l2State);
        assertFalse(state.plaintextExposed);
        assertEquals(0, state.apduCount);
        assertEquals(1f, state.getProgressRatio(), 0.001f);
        assertEquals("Đã xóa", state.stepValues[1]);

        state.recordPinCheckOk();
        assertEquals(CacheFlowView.CacheFlowState.MODE_AFTER, state.mode);
        assertEquals(0, state.failedPinAttempts);
        assertEquals(3, state.remainingPinAttempts);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[0]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[1]);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[2]);

        state.resetToEmpty();
        assertEquals(CacheFlowView.CacheFlowState.MODE_IDLE, state.mode);
        assertFalse(state.hasRun);
        assertEquals(CacheFlowView.CacheFlowState.NODE_IDLE, state.l1State);
        assertEquals(CacheFlowView.CacheFlowState.NODE_IDLE, state.l2State);
        assertEquals(CacheFlowView.CacheFlowState.NODE_IDLE, state.chipState);
        assertEquals(0f, state.getProgressRatio(), 0.001f);
    }

    @Test
    public void coldMissAfterWipeAndL1EvictionTrackTwoTierTransitions() {
        CacheFlowView.CacheFlowState state = new CacheFlowView.CacheFlowState();

        state.resetToEmpty();
        state.finishAfterColdMiss(360L, 6);
        assertFalse(state.runInProgress);
        assertFalse(state.plaintextExposed);
        assertEquals(6, state.apduCount);
        assertEquals("MISS→Nạp", state.l1Badge);
        assertEquals("Lưu TEE", state.l2Badge);
        assertEquals("6 APDU", state.chipBadge);
        assertEquals(CacheFlowView.CacheFlowState.STEP_DONE_OK, state.stepState[1]);

        state.recordL1Evicted();
        assertEquals(CacheFlowView.CacheFlowState.NODE_IDLE, state.l1State);
        assertEquals("Đã xóa", state.l1Badge);
        assertEquals(CacheFlowView.CacheFlowState.NODE_HIT, state.l2State);
        assertEquals("AES-GCM", state.l2Badge);
        assertEquals(0, state.apduCount);

        state.finishAfterRun("L2 TEE", 4L);
        assertEquals("Nạp L1", state.l1Badge);
        assertEquals("HIT TEE", state.l2Badge);

        state.finishAfterRun("L1 RAM", 1L);
        assertEquals("HIT <1 ms", state.l1Badge);
    }
}
