package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ThreadingLaneView.ThreadingState} covering Before-mode Main Thread stalls
 * and After-mode Worker Pool offload with zero dropped frames.
 */
public class ThreadingLaneTest {

    @Test
    public void beforeRunTracksMainThreadStallsDroppedFramesAndBackgroundJank() {
        ThreadingLaneView.ThreadingState state = new ThreadingLaneView.ThreadingState();

        state.beginBeforeRun(1000L);
        assertTrue(state.hasRun);
        assertFalse(state.afterMode);
        assertTrue(state.runInProgress);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_RUNNING, state.stepState[0]);

        state.onBeforeSyncFetchDone(140L);
        assertEquals(140L, state.mainBlockedMs);
        assertEquals(Math.round(140L / 16.6667f), state.droppedFrames);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_DONE_FAULT, state.stepState[0]);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_RUNNING, state.stepState[1]);

        for (int i = 1; i <= 17; i++) {
            state.onBeforeBurstFrame(i, 150L, 140L + i * 150L);
            assertEquals(i, state.completedBurstFrames);
            assertEquals(i + "/18 nhịp", state.stepValues[1]);
            float ratio = state.getBurstBarHeightRatio(i - 1);
            assertTrue(ratio > 0f && ratio <= 1f);
        }

        state.finishBeforeRun(2690L);
        assertFalse(state.runInProgress);
        assertTrue(state.backgroundJankActive);
        assertEquals(2690L, state.mainBlockedMs);
        assertEquals(0L, state.workerReadMs);
        assertEquals(18, state.completedBurstFrames);
        assertEquals(Math.round(2690L / 16.6667f), state.droppedFrames);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_DONE_FAULT, state.stepState[0]);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_DONE_FAULT, state.stepState[1]);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_DONE_FAULT, state.stepState[2]);
        assertTrue(state.getBurstBarHeightRatio(17) > 0f);
    }

    @Test
    public void afterRunOffloadsToWorkerPoolWithZeroDroppedFrames() {
        ThreadingLaneView.ThreadingState state = new ThreadingLaneView.ThreadingState();
        state.finishBeforeRun(2690L);

        state.beginAfterRun(5000L);
        assertTrue(state.afterMode);
        assertTrue(state.runInProgress);
        assertFalse(state.backgroundJankActive);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_DONE_OK, state.stepState[0]);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_RUNNING, state.stepState[1]);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_IDLE, state.stepState[2]);

        state.finishAfterRun(0L, 185L);
        assertFalse(state.runInProgress);
        assertFalse(state.backgroundJankActive);
        assertEquals(0L, state.mainBlockedMs);
        assertEquals(185L, state.workerReadMs);
        assertEquals(0, state.droppedFrames);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_DONE_OK, state.stepState[0]);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_DONE_OK, state.stepState[1]);
        assertEquals(ThreadingLaneView.ThreadingState.STEP_DONE_OK, state.stepState[2]);
        assertEquals("185 ms", state.stepValues[1]);
        assertEquals("<1 ms", state.stepValues[2]);
    }
}
