package com.example.smartstudentid.optimization;

/**
 * Holds the cold-start measurement taken once by LoginPinActivity so the §5 demo page can
 * display it without re-measuring (process start is only meaningful on the first screen).
 */
public final class DemoMetrics {

    private static volatile long coldStartMs = 0;

    private DemoMetrics() {
    }

    public static void setColdStartMs(long value) {
        coldStartMs = value;
    }

    public static long getColdStartMs() {
        return coldStartMs;
    }
}
