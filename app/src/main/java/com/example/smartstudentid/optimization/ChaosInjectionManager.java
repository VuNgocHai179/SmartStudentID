package com.example.smartstudentid.optimization;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.security.EncryptedCacheManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controlled Fault & Vulnerability Injector + 4-Step Optimization Resolver.
 * Enables live demonstration of degraded performance (real Main Thread jank / low FPS,
 * cache bypass, heap memory churn) and security exposures (plaintext storage leak,
 * un-zeroized String PIN on heap, simulated MASVS compromise), followed by step-by-step remediation.
 */
public final class ChaosInjectionManager {

    public static final String PLAINTEXT_LEAK_PREFS = "insecure_plaintext_leak";
    public static final String PLAINTEXT_LEAK_KEY = "exposed_student_record";
    public static final String PLAINTEXT_LEAK_VALUE =
            "MSSV=CT070318|HoTen=Vũ Ngọc Hải|PIN=123456|PLAIN_TEXT_EXPOSED";

    private static final long JANK_INTERVAL_MS = 135L;
    private static final long JANK_SLEEP_MS = 55L;
    private static final long CHURN_INTERVAL_MS = 250L;
    private static final int CHURN_RETAINED_BLOCKS = 22; // ~11 MB retained working set
    private static final int CHURN_BLOCK_BYTES = 512 * 1024;

    private static ChaosInjectionManager instance;

    public interface Listener {
        void onChaosStateChanged();
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler;
    private final ScheduledExecutorService churnExecutor;

    private volatile boolean uiThreadJankActive = false;
    private volatile boolean cacheBypassAndPlaintextLeakActive = false;
    private volatile boolean memoryChurnAndRawPngActive = false;
    private volatile boolean pinRamLeakAndSecurityBypassActive = false;

    private volatile boolean simulatedRootDetected = false;
    private volatile boolean simulatedDebuggerDetected = false;
    private volatile boolean rsaVerificationBypassed = false;
    private volatile String exposedPinInHeap = null;

    private volatile List<byte[]> retainedHeapBlocks = null;
    private ScheduledFuture<?> churnFuture;

    private final AtomicInteger jankTickCount = new AtomicInteger(0);
    private final AtomicLong lastJankBlockMs = new AtomicLong(0L);
    private final AtomicInteger churnCycleCount = new AtomicInteger(0);
    private final AtomicLong retainedHeapBytes = new AtomicLong(0L);

    private final Runnable jankRunnable = new Runnable() {
        @Override
        public void run() {
            if (!uiThreadJankActive || mainHandler == null) {
                return;
            }
            // Simulate synchronous blocking APDU + crypto on Main Thread (~85-95 ms)
            // while leaving a short gap so UI input remains responsive without ANR.
            long startNs = System.nanoTime();
            try {
                ApduClient.getInstance().sendApdu(
                        ApduCommands.CLA_SMART_STUDENT,
                        ApduCommands.INS_GET_STUDENT_INFO,
                        ApduCommands.P1_INFO_RAW_CIPHERTEXT,
                        (byte) 0x00,
                        null
                );
            } catch (Exception ignored) {
            }
            sleepSafely(JANK_SLEEP_MS);
            long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            lastJankBlockMs.set(elapsedMs);
            jankTickCount.incrementAndGet();

            if (uiThreadJankActive) {
                mainHandler.postDelayed(this, JANK_INTERVAL_MS - JANK_SLEEP_MS);
            }
        }
    };

    private ChaosInjectionManager() {
        this.mainHandler = createMainHandlerIfAvailable();
        this.churnExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chaos-memory-churn");
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized ChaosInjectionManager getInstance() {
        if (instance == null) {
            instance = new ChaosInjectionManager();
        }
        return instance;
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Activates all 4 performance & security degradation agents at once.
     */
    public void injectAllIssues(Context context) {
        setStep1ThreadingInternal(true);
        setStep2CacheAndStorageInternal(true, context);
        setStep3ImageAndMemoryInternal(true);
        setStep4PinAndSecurityInternal(true);
        notifyListeners();
    }

    /**
     * Resolves all 4 performance & security issues at once.
     */
    public void resolveAllIssues(Context context) {
        setStep1ThreadingInternal(false);
        setStep2CacheAndStorageInternal(false, context);
        setStep3ImageAndMemoryInternal(false);
        setStep4PinAndSecurityInternal(false);
        notifyListeners();
    }

    // ------------------------------------------------------------------
    // Step 1 (§1–§2): Main Thread Jank vs ApduThreadPool (60 FPS)
    // ------------------------------------------------------------------

    public void injectStep1Threading() {
        setStep1ThreadingInternal(true);
        notifyListeners();
    }

    public void resolveStep1Threading() {
        setStep1ThreadingInternal(false);
        notifyListeners();
    }

    private void setStep1ThreadingInternal(boolean inject) {
        uiThreadJankActive = inject;
        OptimizationConfig.setOptimizedModeEnabled(!inject);
        if (!inject) {
            jankTickCount.set(0);
            lastJankBlockMs.set(0L);
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacks(jankRunnable);
            if (inject) {
                mainHandler.postDelayed(jankRunnable, 60L);
            }
        }
    }

    // ------------------------------------------------------------------
    // Step 2 (§3): Cache Bypass & Plaintext Storage Leak vs AES-256-GCM
    // ------------------------------------------------------------------

    public void injectStep2CacheAndStorage(Context context) {
        setStep2CacheAndStorageInternal(true, context);
        notifyListeners();
    }

    public void resolveStep2CacheAndStorage(Context context) {
        setStep2CacheAndStorageInternal(false, context);
        notifyListeners();
    }

    private void setStep2CacheAndStorageInternal(boolean inject, Context context) {
        cacheBypassAndPlaintextLeakActive = inject;
        if (context == null) {
            return;
        }
        Context appCtx = context.getApplicationContext();
        SharedPreferences leakPrefs = appCtx.getSharedPreferences(PLAINTEXT_LEAK_PREFS, Context.MODE_PRIVATE);
        if (inject) {
            leakPrefs.edit().putString(PLAINTEXT_LEAK_KEY, PLAINTEXT_LEAK_VALUE).commit();
        } else {
            leakPrefs.edit().clear().commit();
        }
    }

    // ------------------------------------------------------------------
    // Step 3 (§4): Raw PNG 750 Packets & Heap Memory Churn vs WebP + 8KB Reuse
    // ------------------------------------------------------------------

    public void injectStep3ImageAndMemory() {
        setStep3ImageAndMemoryInternal(true);
        notifyListeners();
    }

    public void resolveStep3ImageAndMemory() {
        setStep3ImageAndMemoryInternal(false);
        notifyListeners();
    }

    private synchronized void setStep3ImageAndMemoryInternal(boolean inject) {
        memoryChurnAndRawPngActive = inject;
        if (inject) {
            allocateSafeWorkingChurn();
            if (churnFuture == null || churnFuture.isCancelled() || churnFuture.isDone()) {
                churnFuture = churnExecutor.scheduleWithFixedDelay(() -> {
                    if (!memoryChurnAndRawPngActive) {
                        return;
                    }
                    try {
                        // Simulate unpooled 750-packet short-lived byte[] allocations causing GC churn
                        for (int i = 0; i < 64; i++) {
                            byte[] waste = new byte[16 * 1024];
                            waste[0] = (byte) i;
                        }
                        churnCycleCount.incrementAndGet();
                        if (retainedHeapBlocks == null || retainedHeapBlocks.isEmpty()) {
                            allocateSafeWorkingChurn();
                        }
                    } catch (OutOfMemoryError ignored) {
                        retainedHeapBlocks = null;
                        retainedHeapBytes.set(0L);
                    }
                }, 0L, CHURN_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        } else {
            if (churnFuture != null) {
                churnFuture.cancel(false);
                churnFuture = null;
            }
            retainedHeapBlocks = null;
            retainedHeapBytes.set(0L);
            churnCycleCount.set(0);
            System.gc();
        }
    }

    private void allocateSafeWorkingChurn() {
        try {
            Runtime rt = Runtime.getRuntime();
            long maxBytes = rt.maxMemory();
            long usedBytes = rt.totalMemory() - rt.freeMemory();
            long budgetBytes = Math.max(0L, (long) (maxBytes * 0.35) - usedBytes);
            int targetBlocks = (int) Math.min(CHURN_RETAINED_BLOCKS, budgetBytes / CHURN_BLOCK_BYTES);
            List<byte[]> blocks = new ArrayList<>(Math.max(1, targetBlocks));
            for (int i = 0; i < targetBlocks; i++) {
                byte[] block = new byte[CHURN_BLOCK_BYTES];
                block[0] = (byte) (i + 1);
                blocks.add(block);
            }
            retainedHeapBlocks = blocks;
            retainedHeapBytes.set((long) targetBlocks * CHURN_BLOCK_BYTES);
        } catch (OutOfMemoryError ignored) {
            retainedHeapBlocks = null;
            retainedHeapBytes.set(0L);
        }
    }

    // ------------------------------------------------------------------
    // Step 4 (§5): Exposed String PIN + MASVS Bypass vs char[] Zeroization
    // ------------------------------------------------------------------

    public void injectStep4PinAndSecurity() {
        setStep4PinAndSecurityInternal(true);
        notifyListeners();
    }

    public void resolveStep4PinAndSecurity() {
        setStep4PinAndSecurityInternal(false);
        notifyListeners();
    }

    private void setStep4PinAndSecurityInternal(boolean inject) {
        pinRamLeakAndSecurityBypassActive = inject;
        if (inject) {
            exposedPinInHeap = "123456 (String trên Heap - Không thể xóa)";
            simulatedRootDetected = true;
            simulatedDebuggerDetected = true;
            rsaVerificationBypassed = true;
        } else {
            // Demonstrate active char[] zeroization before clearing reference
            char[] transientPin = "123456".toCharArray();
            Arrays.fill(transientPin, '\0');
            exposedPinInHeap = null;
            simulatedRootDetected = false;
            simulatedDebuggerDetected = false;
            rsaVerificationBypassed = false;
        }
    }

    // ------------------------------------------------------------------
    // State Queries
    // ------------------------------------------------------------------

    public boolean isUiThreadJankActive() {
        return uiThreadJankActive;
    }

    public boolean isCacheBypassAndPlaintextLeakActive() {
        return cacheBypassAndPlaintextLeakActive;
    }

    public boolean isMemoryChurnAndRawPngActive() {
        return memoryChurnAndRawPngActive;
    }

    public boolean isPinRamLeakAndSecurityBypassActive() {
        return pinRamLeakAndSecurityBypassActive;
    }

    public boolean isSimulatedRootDetected() {
        return simulatedRootDetected;
    }

    public boolean isSimulatedDebuggerDetected() {
        return simulatedDebuggerDetected;
    }

    public boolean isRsaVerificationBypassed() {
        return rsaVerificationBypassed;
    }

    public String getExposedPinInHeap() {
        return exposedPinInHeap;
    }

    public int getPinRamExposurePct() {
        return pinRamLeakAndSecurityBypassActive ? 100 : 0;
    }

    public boolean isAnyIssueActive() {
        return uiThreadJankActive
                || cacheBypassAndPlaintextLeakActive
                || memoryChurnAndRawPngActive
                || pinRamLeakAndSecurityBypassActive;
    }

    public int getActiveIssueCount() {
        int count = 0;
        if (uiThreadJankActive) count++;
        if (cacheBypassAndPlaintextLeakActive) count++;
        if (memoryChurnAndRawPngActive) count++;
        if (pinRamLeakAndSecurityBypassActive) count++;
        return count;
    }

    public int getOptimizedStepCount() {
        return 4 - getActiveIssueCount();
    }

    /**
     * Computes MASVS compliance score (1..5) reflecting active security exposures.
     */
    public int getMasvsScore() {
        int score = 5;
        if (cacheBypassAndPlaintextLeakActive) score--;
        if (pinRamLeakAndSecurityBypassActive) score--;
        if (rsaVerificationBypassed) score--;
        if (simulatedRootDetected || simulatedDebuggerDetected) score--;
        return Math.max(1, score);
    }

    public int getSimulatedApduPacketCount() {
        return memoryChurnAndRawPngActive ? 750 : 15;
    }

    public int getJankTickCount() {
        return jankTickCount.get();
    }

    public long getLastJankBlockMs() {
        return lastJankBlockMs.get();
    }

    public int getChurnCycleCount() {
        return churnCycleCount.get();
    }

    public double getRetainedHeapMb() {
        return retainedHeapBytes.get() / (1024.0 * 1024.0);
    }

    public boolean hasPlaintextLeak(Context context) {
        if (cacheBypassAndPlaintextLeakActive) {
            return true;
        }
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PLAINTEXT_LEAK_PREFS, Context.MODE_PRIVATE);
        return prefs.contains(PLAINTEXT_LEAK_KEY);
    }

    public String getPlaintextLeakContent(Context context) {
        if (context != null) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PLAINTEXT_LEAK_PREFS, Context.MODE_PRIVATE);
            String stored = prefs.getString(PLAINTEXT_LEAK_KEY, null);
            if (stored != null && !stored.isEmpty()) {
                return stored;
            }
        }
        return cacheBypassAndPlaintextLeakActive ? PLAINTEXT_LEAK_VALUE : null;
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try {
                listener.onChaosStateChanged();
            } catch (Exception ignored) {
            }
        }
    }

    private static Handler createMainHandlerIfAvailable() {
        try {
            Looper main = Looper.getMainLooper();
            return main != null ? new Handler(main) : null;
        } catch (RuntimeException ignored) {
            // JVM unit tests do not mock android.os.Looper.
            return null;
        }
    }

    private static void sleepSafely(long ms) {
        try {
            SystemClock.sleep(ms);
        } catch (RuntimeException ignored) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
