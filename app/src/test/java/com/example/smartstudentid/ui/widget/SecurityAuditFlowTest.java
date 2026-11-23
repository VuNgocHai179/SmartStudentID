package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SecurityAuditFlowView.SecurityFlowState} covering Before sync KeyStore &
 * exposed String PIN vs. After lazy init, char[] zeroization, and MASVS 5/5 restoration.
 */
public class SecurityAuditFlowTest {

    @Test
    public void beforeRunLeavesStringPinExposedAndDropsMasvsScore() {
        SecurityAuditFlowView.SecurityFlowState state = new SecurityAuditFlowView.SecurityFlowState();

        state.beginBeforeRun(1000L, 6, 2);
        assertTrue(state.hasRun);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.MODE_BEFORE, state.mode);
        assertTrue(state.runInProgress);
        assertEquals(100, state.exposurePct);
        assertEquals(6, state.remainingPinChars);
        assertEquals(2, state.masvsScore);

        state.onBeforePbkdf2Done(2);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_FAULT, state.pbkdf2NodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_FAULT, state.zeroizeNodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT, state.stepState[1]);

        state.finishBeforeRun(1952L, 6, 2);
        assertFalse(state.runInProgress);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.MODE_BEFORE, state.mode);
        assertEquals(100, state.exposurePct);
        assertEquals(6, state.remainingPinChars);
        assertEquals(2, state.masvsScore);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_FAULT, state.initNodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_FAULT, state.pbkdf2NodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_FAULT, state.zeroizeNodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT, state.stepState[0]);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT, state.stepState[1]);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT, state.stepState[2]);
        assertEquals(1f, state.getProgressBarRatio(3000L), 0.001f);
    }

    @Test
    public void afterRunUsesLazyInitAndZeroizesCharArrayPin() {
        SecurityAuditFlowView.SecurityFlowState state = new SecurityAuditFlowView.SecurityFlowState();

        state.beginAfterRun(5000L, 6);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.MODE_AFTER, state.mode);
        assertTrue(state.runInProgress);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_OK, state.initNodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_ACTIVE, state.pbkdf2NodeState);

        state.finishAfterRun(28L, 0, 6, 5);
        assertFalse(state.runInProgress);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.MODE_AFTER, state.mode);
        assertEquals(0, state.exposurePct);
        assertEquals(0, state.remainingPinChars);
        assertEquals(5, state.masvsScore);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_OK, state.initNodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_OK, state.pbkdf2NodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.NODE_OK, state.zeroizeNodeState);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.STEP_DONE_OK, state.stepState[0]);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.STEP_DONE_OK, state.stepState[1]);
        assertEquals(SecurityAuditFlowView.SecurityFlowState.STEP_DONE_OK, state.stepState[2]);
        assertEquals("0/6 còn", state.stepValues[1]);
        assertEquals("MASVS 5/5", state.stepValues[2]);
        assertEquals(1f, state.getProgressBarRatio(5100L), 0.001f);
    }
}
