package com.example.smartstudentid.optimization;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Execution telemetry for a single worker slot inside a parallel batch. */
    public static final class WorkerTaskReport {
        public final int slotIndex;
        public final String label;
        public final String threadName;
        public final long startOffsetMs;
        public final long durationMs;
        public final boolean success;
        public final String detail;

        public WorkerTaskReport(int slotIndex, String label, String threadName,
                                long startOffsetMs, long durationMs,
                                boolean success, String detail) {
            this.slotIndex = slotIndex;
            this.label = label;
            this.threadName = threadName;
            this.startOffsetMs = startOffsetMs;
            this.durationMs = durationMs;
            this.success = success;
            this.detail = detail;
        }
    }

    /** Completion callback invoked on the Main Thread after all tasks in a parallel batch finish. */
    public interface ParallelBatchCallback {
        void onComplete(WorkerTaskReport[] reports, long mainThreadBlockedMs, long wallElapsedMs);
    }

    private ApduThreadPool() {
        final AtomicInteger threadCounter = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Worker-T" + threadCounter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
        this.backgroundExecutor = Executors.newFixedThreadPool(CORE_POOL_SIZE, factory);
        if (this.backgroundExecutor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) this.backgroundExecutor).prestartAllCoreThreads();
        }
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
     * Dispatches multiple independent tasks concurrently across the 4-thread worker pool
     * and collects per-worker timing and thread-name telemetry on completion.
     */
    public void executeParallelBatch(String[] labels, Callable<String>[] tasks, ParallelBatchCallback callback) {
        if (tasks == null || tasks.length == 0) {
            if (callback != null) {
                mainThreadHandler.post(() -> callback.onComplete(new WorkerTaskReport[0], 0L, 0L));
            }
            return;
        }
        final long batchStartNs = System.nanoTime();
        final AtomicLong dispatchMicros = new AtomicLong(0);
        final int count = tasks.length;
        final WorkerTaskReport[] reports = new WorkerTaskReport[count];
        final AtomicInteger remaining = new AtomicInteger(count);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            final String label = (labels != null && idx < labels.length && labels[idx] != null)
                    ? labels[idx]
                    : "Task " + (idx + 1);
            final Callable<String> task = tasks[idx];
            backgroundExecutor.submit(() -> {
                long startNs = System.nanoTime();
                long startOffsetMs = Math.max(0L, (startNs - batchStartNs) / 1_000_000L);
                String tName = Thread.currentThread().getName();
                boolean ok = true;
                String detail = "";
                try {
                    if (task != null) {
                        String res = task.call();
                        detail = res != null ? res : "OK";
                    }
                } catch (Exception e) {
                    ok = false;
                    detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                long durationMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                reports[idx] = new WorkerTaskReport(idx, label, tName, startOffsetMs, durationMs, ok, detail);

                if (remaining.decrementAndGet() == 0) {
                    long wallMs = Math.max(1L, (System.nanoTime() - batchStartNs) / 1_000_000L);
                    mainThreadHandler.post(() -> {
                        if (callback != null) {
                            callback.onComplete(reports, dispatchMicros.get() / 1_000L, wallMs);
                        }
                    });
                }
            });
        }
        dispatchMicros.set((System.nanoTime() - batchStartNs) / 1_000L);
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
