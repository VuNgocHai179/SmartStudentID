package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link LivePerfHudView.RamMetrics} verifying real App PSS / Device RAM
 * ratio formatting and churn threshold evaluation.
 */
public class LivePerfHudRamTest {

    @Test
    public void evaluatesNormalAppPssAgainstDeviceTotalRam() {
        LivePerfHudView.RamMetrics.RamStatus status = LivePerfHudView.RamMetrics.evaluate(
                112f, 8192f, 27f, 512f, false, 0.0
        );

        assertEquals("App 112M / 8.0G (1.4%)", status.expandedLabel);
        assertEquals("112M/8.0G", status.compactLabel);
        assertFalse(status.churnActive);
        assertEquals(8.0f, status.deviceTotalGb, 0.01f);
        assertEquals(112f / 8192f, status.deviceRatio, 0.0001f);
    }

    @Test
    public void doesNotTriggerFalseChurnOnModerateHeapDrift() {
        LivePerfHudView.RamMetrics.RamStatus status = LivePerfHudView.RamMetrics.evaluate(
                45f, 8192f, 27f, 512f, false, 0.0
        );

        assertFalse(status.churnActive);
    }

    @Test
    public void triggersChurnWhenChaosMemoryChurnActiveOrHeapPressureHigh() {
        LivePerfHudView.RamMetrics.RamStatus chaosStatus = LivePerfHudView.RamMetrics.evaluate(
                120f, 8192f, 90f, 512f, true, 11.0
        );
        assertTrue(chaosStatus.churnActive);
        assertEquals("App 120M / 8.0G (+11M)", chaosStatus.expandedLabel);

        LivePerfHudView.RamMetrics.RamStatus heapPressureStatus = LivePerfHudView.RamMetrics.evaluate(
                390f, 8192f, 384f, 512f, false, 0.0
        );
        assertTrue(heapPressureStatus.churnActive);
    }

    @Test
    public void handlesZeroOrNegativeDeviceTotalRamGracefully() {
        LivePerfHudView.RamMetrics.RamStatus status = LivePerfHudView.RamMetrics.evaluate(
                32f, 0f, 16f, 256f, false, 0.0
        );

        assertFalse(Float.isNaN(status.deviceRatio));
        assertTrue(status.deviceRatio > 0f && status.deviceRatio <= 1f);
        assertEquals(1.0f, status.deviceTotalGb, 0.01f);
        assertEquals("App 32M / 1.0G (3.1%)", status.expandedLabel);
    }
}
