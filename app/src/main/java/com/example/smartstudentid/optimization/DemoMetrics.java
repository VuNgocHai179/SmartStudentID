package com.example.smartstudentid.optimization;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds lightweight runtime telemetry shared across LoginPinActivity, TopicDemoFragment,
 * and LivePerfHudView (cold-start ms, active heavy workload counter, Main Thread block flag).
 */
public final class DemoMetrics {

    private static volatile long coldStartMs = 0;
    private static final AtomicInteger activeHeavyTaskCount = new AtomicInteger(0);
    private static volatile boolean mainThreadBlockedActive = false;

    private DemoMetrics() {
    }

    public static void setColdStartMs(long value) {
        coldStartMs = value;
    }

    public static long getColdStartMs() {
        return coldStartMs;
    }

    public static void beginHeavyTask(boolean blockingMainThread) {
        activeHeavyTaskCount.incrementAndGet();
        if (blockingMainThread) {
            mainThreadBlockedActive = true;
        }
    }

    public static void endHeavyTask(boolean blockingMainThread) {
        int val = activeHeavyTaskCount.decrementAndGet();
        if (val < 0) {
            activeHeavyTaskCount.set(0);
        }
        if (blockingMainThread) {
            mainThreadBlockedActive = false;
        }
    }

    public static void setMainThreadBlockedActive(boolean blocked) {
        mainThreadBlockedActive = blocked;
    }

    public static int getActiveHeavyTaskCount() {
        return Math.max(0, activeHeavyTaskCount.get());
    }

    public static boolean isMainThreadBlockedActive() {
        return mainThreadBlockedActive;
    }
}
