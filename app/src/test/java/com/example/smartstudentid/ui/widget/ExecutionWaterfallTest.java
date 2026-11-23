package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ExecutionWaterfallView.WaterfallState} covering sequential stage offsets,
 * live progress updates, peak bottleneck detection, and After-mode bypass/cache scaling.
 */
public class ExecutionWaterfallTest {

    @Test
    public void beforeRunBuildsSequentialOffsetsAndDetectsSlowestBottleneckStage() {
        ExecutionWaterfallView.WaterfallState state = new ExecutionWaterfallView.WaterfallState();

        state.beginBeforeRun(1000L);
        assertTrue(state.hasRun);
        assertFalse(state.afterMode);
        assertTrue(state.runInProgress);

        state.startBeforeStage(0, 1000L, "Đang gửi 0xA4 SELECT Applet...");
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_RUNNING, state.stageState[0]);
        assertEquals(0L, state.stageStartMs[0]);
        state.completeBeforeStage(0, 320L, 1);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_DONE, state.stageState[0]);
        assertEquals(320L, state.stageDurationMs[0]);

        state.startBeforeStage(1, 1320L, "Đang xác thực PIN...");
        assertEquals(320L, state.stageStartMs[1]);
        state.completeBeforeStage(1, 520L, 2);

        state.startBeforeStage(2, 1840L, "Đang giải mã AES-128...");
        assertEquals(840L, state.stageStartMs[2]);
        state.completeBeforeStage(2, 680L, 3);

        state.startBeforeStage(3, 2520L, "Đang ký RSA-1024...");
        assertEquals(1520L, state.stageStartMs[3]);
        state.completeBeforeStage(3, 890L, 5);

        state.startBeforeStage(4, 3410L, "Đang tải 0/35 gói ảnh...");
        assertEquals(2410L, state.stageStartMs[4]);
        state.updateStageProgress(4, 21, "Đang tải 15/35 gói ảnh...");
        assertEquals(21, state.totalApduCount);
        assertEquals("Đang tải 15/35 gói ảnh...", state.stageLiveNote[4]);
        state.completeBeforeStage(4, 740L, 41);

        long[] steps = {320L, 520L, 680L, 890L, 740L};
        state.finishBeforeRun(steps, 3158L, 41);

        assertFalse(state.runInProgress);
        assertEquals(3, state.bottleneckIndex);
        assertEquals(0L, state.stageStartMs[0]);
        assertEquals(320L, state.stageStartMs[1]);
        assertEquals(840L, state.stageStartMs[2]);
        assertEquals(1520L, state.stageStartMs[3]);
        assertEquals(2410L, state.stageStartMs[4]);
        assertEquals(3158L, state.totalMs);
        assertEquals(3158L, state.lastBeforeTotalMs);
        assertEquals(41, state.totalApduCount);
        assertTrue(state.energyMj > 1.5);
        assertEquals(28, state.getStagePercent(3));

        int totalPct = 0;
        for (int i = 0; i < ExecutionWaterfallView.WaterfallState.STAGE_COUNT; i++) {
            totalPct += state.getStagePercent(i);
        }
        assertTrue(totalPct >= 98 && totalPct <= 102);

        float lastEnd = state.getStageStartRatio(4, 5000L) + state.getStageWidthRatio(4, 5000L);
        assertTrue(lastEnd <= 1.001f);
    }

    @Test
    public void afterRunMarksBypassedAndCacheHitStagesWithZeroApdu() {
        ExecutionWaterfallView.WaterfallState state = new ExecutionWaterfallView.WaterfallState();
        state.finishBeforeRun(new long[]{320L, 520L, 680L, 890L, 740L}, 3150L, 41);

        state.beginAfterRun();
        assertTrue(state.runInProgress);
        assertTrue(state.afterMode);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_BYPASSED, state.stageState[0]);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_BYPASSED, state.stageState[1]);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_RUNNING, state.stageState[2]);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_BYPASSED, state.stageState[3]);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_RUNNING, state.stageState[4]);

        state.finishAfterRun(new long[]{0L, 0L, 2L, 0L, 2L}, 4L);

        assertFalse(state.runInProgress);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_BYPASSED, state.stageState[0]);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_BYPASSED, state.stageState[1]);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_CACHE_HIT, state.stageState[2]);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_BYPASSED, state.stageState[3]);
        assertEquals(ExecutionWaterfallView.WaterfallState.STATE_CACHE_HIT, state.stageState[4]);
        assertEquals(0, state.totalApduCount);
        assertEquals(0.02, state.energyMj, 0.0001);
        assertEquals(-1, state.bottleneckIndex);
        assertEquals(3150L, state.lastBeforeTotalMs);
        assertEquals(3150L, state.computeEffectiveScaleMs(9000L));
        assertEquals(0f, state.getStageWidthRatio(0, 9000L), 0.0001f);
        assertTrue(state.getStageWidthRatio(2, 9000L) < 0.01f);
    }
}
