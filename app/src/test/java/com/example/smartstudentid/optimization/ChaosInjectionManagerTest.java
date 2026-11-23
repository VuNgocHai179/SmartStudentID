package com.example.smartstudentid.optimization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link ChaosInjectionManager}: verifies fault/vulnerability injection
 * across all 4 steps, MASVS score transitions (1/5 -> 5/5), PIN exposure (100% -> 0%),
 * and step-by-step remediation.
 */
public class ChaosInjectionManagerTest {

    private ChaosInjectionManager manager;

    @Before
    public void setUp() {
        manager = ChaosInjectionManager.getInstance();
        manager.resolveAllIssues(null);
    }

    @After
    public void tearDown() {
        manager.resolveAllIssues(null);
    }

    @Test
    public void injectAllIssuesDegradesAllMetricsAndStepByStepRestoresCleanState() {
        assertFalse(manager.isAnyIssueActive());
        assertEquals(0, manager.getActiveIssueCount());
        assertEquals(4, manager.getOptimizedStepCount());
        assertEquals(5, manager.getMasvsScore());
        assertEquals(0, manager.getPinRamExposurePct());
        assertTrue(OptimizationConfig.isOptimizedModeEnabled());

        // Inject all 4 faults & vulnerabilities
        manager.injectAllIssues(null);

        assertTrue(manager.isAnyIssueActive());
        assertTrue(manager.isUiThreadJankActive());
        assertTrue(manager.isCacheBypassAndPlaintextLeakActive());
        assertTrue(manager.isMemoryChurnAndRawPngActive());
        assertTrue(manager.isPinRamLeakAndSecurityBypassActive());
        assertTrue(manager.isSimulatedRootDetected());
        assertTrue(manager.isSimulatedDebuggerDetected());
        assertTrue(manager.isRsaVerificationBypassed());

        assertFalse(OptimizationConfig.isOptimizedModeEnabled());
        assertEquals(4, manager.getActiveIssueCount());
        assertEquals(0, manager.getOptimizedStepCount());
        assertEquals(1, manager.getMasvsScore());
        assertEquals(100, manager.getPinRamExposurePct());
        assertNotNull(manager.getExposedPinInHeap());
        assertEquals(750, manager.getSimulatedApduPacketCount());

        // Step 1: Threading & 60 FPS
        manager.resolveStep1Threading();
        assertFalse(manager.isUiThreadJankActive());
        assertTrue(OptimizationConfig.isOptimizedModeEnabled());
        assertEquals(3, manager.getActiveIssueCount());
        assertEquals(1, manager.getOptimizedStepCount());
        assertEquals(1, manager.getMasvsScore());

        // Step 2: Offline-first AES-GCM Cache & Plaintext removal
        manager.resolveStep2CacheAndStorage(null);
        assertFalse(manager.isCacheBypassAndPlaintextLeakActive());
        assertEquals(2, manager.getActiveIssueCount());
        assertEquals(2, manager.getOptimizedStepCount());
        assertEquals(2, manager.getMasvsScore());

        // Step 3: WebP 75% & Heap Memory Churn cleanup
        manager.resolveStep3ImageAndMemory();
        assertFalse(manager.isMemoryChurnAndRawPngActive());
        assertEquals(15, manager.getSimulatedApduPacketCount());
        assertEquals(1, manager.getActiveIssueCount());
        assertEquals(3, manager.getOptimizedStepCount());

        // Step 4: PIN char[] zeroization & MASVS restoration
        manager.resolveStep4PinAndSecurity();
        assertFalse(manager.isPinRamLeakAndSecurityBypassActive());
        assertFalse(manager.isSimulatedRootDetected());
        assertFalse(manager.isSimulatedDebuggerDetected());
        assertFalse(manager.isRsaVerificationBypassed());
        assertNull(manager.getExposedPinInHeap());
        assertEquals(0, manager.getPinRamExposurePct());
        assertEquals(5, manager.getMasvsScore());
        assertEquals(0, manager.getActiveIssueCount());
        assertEquals(4, manager.getOptimizedStepCount());
        assertFalse(manager.isAnyIssueActive());
    }

    @Test
    public void listenersReceiveNotificationsOnInjectAndResolve() {
        AtomicInteger notifications = new AtomicInteger(0);
        ChaosInjectionManager.Listener listener = notifications::incrementAndGet;
        manager.addListener(listener);
        try {
            manager.injectStep2CacheAndStorage(null);
            manager.resolveStep2CacheAndStorage(null);
            assertEquals(2, notifications.get());
        } finally {
            manager.removeListener(listener);
        }
    }

    @Test
    public void backgroundChaosTelemetryCountersResetOnResolve() throws Exception {
        manager.injectStep3ImageAndMemory();
        Thread.sleep(80L);
        assertTrue(manager.getChurnCycleCount() >= 1);
        assertTrue(manager.getRetainedHeapMb() >= 0.0);

        manager.resolveStep3ImageAndMemory();
        assertEquals(0, manager.getChurnCycleCount());
        assertEquals(0.0, manager.getRetainedHeapMb(), 0.001);

        manager.injectStep1Threading();
        manager.resolveStep1Threading();
        assertEquals(0, manager.getJankTickCount());
        assertEquals(0L, manager.getLastJankBlockMs());
    }
}
