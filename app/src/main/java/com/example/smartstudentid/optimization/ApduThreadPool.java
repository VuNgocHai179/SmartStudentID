package com.example.smartstudentid.optimization;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-threading manager implementing OPT-03 (60 FPS UI rendering):
 * - In Optimized Mode (ON): Offloads all blocking Smart Card APDU I/O and RSA/AES cryptography
 *   to a fixed 4-thread background ExecutorService pool and posts results back via MainLooper Handler.
 * - In Unoptimized Mode (OFF): Executes tasks directly on the calling (Main) thread so that
 *   UI thread blocking time and frame jank can be measured and compared in Android Profiler.
 */
public final class ApduThreadPool {

    private static final int CORE_POOL_SIZE = 4;
    private static ApduThreadPool instance;

    private final ExecutorService backgroundExecutor;
    private final Handler mainThreadHandler;

    public interface TaskCallback<T> {
        void onComplete(T result, long mainThreadBlockedMs, long totalElapsedMs);

        default void onError(Exception e) {
        }
    }

    private ApduThreadPool() {
        this.backgroundExecutor = Executors.newFixedThreadPool(CORE_POOL_SIZE);
        this.mainThreadHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized ApduThreadPool getInstance() {
        if (instance == null) {
            instance = new ApduThreadPool();
        }
        return instance;
    }

    /**
     * Executes a task according to the active OptimizationConfig mode (Background Pool vs Main Thread).
     */
    public <T> void execute(Callable<T> task, TaskCallback<T> callback) {
        if (OptimizationConfig.isOptimizedModeEnabled()) {
            executeAsyncOnPool(task, callback);
        } else {
            executeBlockingOnCurrentThread(task, callback);
        }
    }

    /**
     * Optimized path: Runs the heavy operation on the 4-thread background pool.
     * The reported main-thread cost is the measured cost of handing the task to the pool
     * (for the UI it is normally below 1 ms, so the UI shows "&lt;1 ms").
     */
    public <T> void executeAsyncOnPool(Callable<T> task, TaskCallback<T> callback) {
        long mainStartNs = System.nanoTime();
        AtomicLong dispatchMicros = new AtomicLong(0);
        backgroundExecutor.submit(() -> {
            long bgStartNs = System.nanoTime();
            try {
                T result = task.call();
                long totalMs = Math.max(1, (System.nanoTime() - bgStartNs) / 1_000_000);
                mainThreadHandler.post(() -> {
                    if (callback != null) {
                        callback.onComplete(result, dispatchMicros.get() / 1_000, totalMs);
                    }
                });
            } catch (Exception e) {
                mainThreadHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(e);
                    }
                });
            }
        });
        // Time the Main Thread actually spent queuing the job (submit + lazy worker start).
        dispatchMicros.set((System.nanoTime() - mainStartNs) / 1_000);
    }

    /**
     * Unoptimized path: Runs directly on the calling thread, blocking UI rendering during I/O.
     */
    public <T> void executeBlockingOnCurrentThread(Callable<T> task, TaskCallback<T> callback) {
        long startNs = System.nanoTime();
        try {
            T result = task.call();
            long elapsedMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            if (callback != null) {
                callback.onComplete(result, elapsedMs, elapsedMs);
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.onError(e);
            }
        }
    }
}
