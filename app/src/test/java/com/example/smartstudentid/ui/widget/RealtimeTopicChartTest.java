package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link RealtimeTopicChartView.ChartState} across topics §1–§5.
 */
public class RealtimeTopicChartTest {

    @Test
    public void bottleneckMapsAllFiveApduCommandsIncludingTwoGetInfoCalls() {
        RealtimeTopicChartView.ChartState state = new RealtimeTopicChartView.ChartState();
        state.setTopic(RealtimeTopicChartView.TOPIC_BOTTLENECK, 10f);
        state.onRunStarted(false, 100L, 10f);

        state.onApduFrame(0xA4, 12L, 112L);
        state.onApduFrame(0x10, 28L, 140L);
        state.onApduFrame(0x21, 35L, 175L);
        state.onApduFrame(0x21, 41L, 216L);
        state.onApduFrame(0x30, 19L, 235L);

        assertEquals(12L, state.stepMs[0]);
        assertEquals(28L, state.stepMs[1]);
        assertEquals(35L, state.stepMs[2]);
        assertEquals(41L, state.stepMs[3]);
        assertEquals(19L, state.stepMs[4]);
        assertEquals(19f, state.values[RealtimeTopicChartView.ChartState.CAPACITY - 1], 0.01f);
    }

    @Test
    public void threadingMarksFrameGapOverThresholdAsBeforeJank() {
        RealtimeTopicChartView.ChartState state = new RealtimeTopicChartView.ChartState();
        state.setTopic(RealtimeTopicChartView.TOPIC_THREADING, 10f);

        state.onSampleTick(50L, 16.6f, 1, 10f);
        assertEquals(RealtimeTopicChartView.MODE_AFTER,
                state.modes[RealtimeTopicChartView.ChartState.CAPACITY - 1]);

        state.onSampleTick(250L, 180f, 4, 10f);
        int last = RealtimeTopicChartView.ChartState.CAPACITY - 1;
        assertEquals(RealtimeTopicChartView.MODE_BEFORE, state.modes[last]);
        assertEquals(RealtimeTopicChartView.MODE_BEFORE, state.modes[last - 3]);
        assertEquals(180f, state.values[last], 0.01f);
    }

    @Test
    public void cacheRollsHistoryAndResetsCleanly() {
        RealtimeTopicChartView.ChartState state = new RealtimeTopicChartView.ChartState();
        state.setTopic(RealtimeTopicChartView.TOPIC_CACHE, 10f);

        for (int i = 0; i < 10; i++) {
            boolean cached = i % 2 == 1;
            state.recordCacheResult(cached, cached, cached ? 4L : 200L + i, cached ? 0 : 5);
        }

        assertEquals(RealtimeTopicChartView.ChartState.MAX_HISTORY, state.histCount);
        assertEquals(202L, state.histMs[0]);
        assertFalse(state.histFromCache[0]);
        assertEquals(4L, state.histMs[RealtimeTopicChartView.ChartState.MAX_HISTORY - 1]);
        assertTrue(state.histFromCache[RealtimeTopicChartView.ChartState.MAX_HISTORY - 1]);

        state.resetHistory();
        assertEquals(0, state.histCount);
        assertEquals(-1L, state.lastOpenMs);
    }

    @Test
    public void imageTracksHeapPeakAndLiveUploadPackets() {
        RealtimeTopicChartView.ChartState state = new RealtimeTopicChartView.ChartState();
        state.setTopic(RealtimeTopicChartView.TOPIC_IMAGE, 12f);
        state.onRunStarted(true, 100L, 12f);

        state.onSampleTick(150L, 16f, 1, 18.5f);
        state.onApduFrame(0x22, 5L, 160L);
        state.onApduFrame(0x22, 6L, 166L);
        state.recordImageResult(true, 3.5, 15, 0.1, true, 14.0f);

        assertEquals(2, state.liveUploadPackets);
        assertEquals(18.5f, state.peakHeapMb, 0.01f);
        assertEquals(3.5, state.afterSizeKb, 0.01);
        assertEquals(15, state.afterPackets);
    }

    @Test
    public void startupPinExposureStaysHighInBeforeAndDropsToZeroInAfter() {
        RealtimeTopicChartView.ChartState state = new RealtimeTopicChartView.ChartState();
        state.setTopic(RealtimeTopicChartView.TOPIC_STARTUP, 10f);

        state.onRunStarted(false, 100L, 10f);
        state.recordLoginResult(false, 35L, -1, 6);
        assertEquals(100f, state.pinExposurePct, 0.01f);
        assertEquals(RealtimeTopicChartView.MODE_BEFORE, state.pinExposureMode);

        state.onRunStarted(true, 200L, 10f);
        assertEquals(100f, state.pinExposurePct, 0.01f);
        assertEquals(RealtimeTopicChartView.MODE_AFTER, state.pinExposureMode);

        state.recordLoginResult(true, 32L, 0, 6);
        assertEquals(0f, state.pinExposurePct, 0.01f);
        assertEquals(RealtimeTopicChartView.MODE_AFTER, state.pinExposureMode);
        assertEquals(0f, state.values[RealtimeTopicChartView.ChartState.CAPACITY - 1], 0.01f);
    }
}
