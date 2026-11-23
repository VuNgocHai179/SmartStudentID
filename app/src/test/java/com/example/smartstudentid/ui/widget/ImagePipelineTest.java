package com.example.smartstudentid.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ImagePipelineView.ImagePipelineState} covering Before full-bitmap PNG
 * 8 KB overflow & GC churn vs. After two-pass WebP compression, 8 KB buffer reuse, and GPU VRAM.
 */
public class ImagePipelineTest {

    @Test
    public void beforeRunTracksFullBitmapPngOverflowAndChurn() {
        ImagePipelineView.ImagePipelineState state = new ImagePipelineView.ImagePipelineState();

        state.beginBeforeRun(11.0);
        assertTrue(state.hasRun);
        assertEquals(ImagePipelineView.ImagePipelineState.MODE_BEFORE, state.mode);
        assertTrue(state.runInProgress);
        assertFalse(state.cardWriteOk);
        assertEquals(ImagePipelineView.ImagePipelineState.STEP_RUNNING, state.stepState[0]);

        state.onBeforeDecoded("1200×1200", 5.5, 182.0, 758);
        assertEquals(182.0, state.sizeKb, 0.01);
        assertEquals(758, state.packets);
        assertEquals(5.5, state.ramMb, 0.01);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_FAULT, state.decodeNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_ACTIVE, state.compressNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_FAULT, state.chipNodeState);
        assertTrue(state.getCapacityBarRatio() >= 0.15f);

        state.onBeforeChurnProgress(24);
        assertEquals(24, state.churnAllocatedCount);
        assertEquals(0.5f, state.getCapacityBarRatio(), 0.001f);
        assertEquals("24/48 mảng", state.stepValues[1]);

        state.finishBeforeRun("1200×1200", 182.0, 758, 5.5, 3L, 28L);
        assertFalse(state.runInProgress);
        assertEquals(ImagePipelineView.ImagePipelineState.MODE_BEFORE, state.mode);
        assertEquals(182.0, state.sizeKb, 0.01);
        assertEquals(758, state.packets);
        assertFalse(state.cardWriteOk);
        assertEquals(48, state.churnAllocatedCount);
        assertEquals(1f, state.getCapacityBarRatio(), 0.001f);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_FAULT, state.decodeNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_FAULT, state.compressNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_FAULT, state.chipNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.STEP_DONE_FAULT, state.stepState[0]);
        assertEquals(ImagePipelineView.ImagePipelineState.STEP_DONE_FAULT, state.stepState[1]);
        assertEquals(ImagePipelineView.ImagePipelineState.STEP_DONE_FAULT, state.stepState[2]);
    }

    @Test
    public void afterRunCompressesToWebpUnder8KbWithGpuVram() {
        ImagePipelineView.ImagePipelineState state = new ImagePipelineView.ImagePipelineState();

        state.beginAfterRun();
        assertEquals(ImagePipelineView.ImagePipelineState.MODE_AFTER, state.mode);
        assertTrue(state.runInProgress);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_ACTIVE, state.decodeNodeState);

        state.onAfterCompressed(8, 3.4, 15);
        assertEquals(8, state.inSampleSize);
        assertEquals(3.4, state.sizeKb, 0.01);
        assertEquals(15, state.bufferReuses);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_OK, state.decodeNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_OK, state.compressNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_ACTIVE, state.chipNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.STEP_RUNNING, state.stepState[2]);

        state.finishAfterRun(8, 3.4, 15, 0.0, true, 15, 0L, 0L, true);
        assertFalse(state.runInProgress);
        assertEquals(ImagePipelineView.ImagePipelineState.MODE_AFTER, state.mode);
        assertEquals(3.4, state.sizeKb, 0.01);
        assertEquals(15, state.packets);
        assertTrue(state.inGpuVram);
        assertTrue(state.cardWriteOk);
        assertEquals((float) (3.4 / 8.0), state.getCapacityBarRatio(), 0.001f);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_OK, state.decodeNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_OK, state.compressNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.NODE_OK, state.chipNodeState);
        assertEquals(ImagePipelineView.ImagePipelineState.STEP_DONE_OK, state.stepState[0]);
        assertEquals(ImagePipelineView.ImagePipelineState.STEP_DONE_OK, state.stepState[1]);
        assertEquals(ImagePipelineView.ImagePipelineState.STEP_DONE_OK, state.stepState[2]);
        assertEquals("GPU VRAM", state.stepValues[2]);
    }
}
