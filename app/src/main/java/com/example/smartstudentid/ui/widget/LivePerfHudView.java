package com.example.smartstudentid.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.example.smartstudentid.optimization.ChaosInjectionManager;
import com.example.smartstudentid.optimization.DemoMetrics;

import java.util.Arrays;
import java.util.Locale;

/**
 * Floating Live Performance & Security Profiler HUD (FPS, Frame Gap, CPU %, Java/Native RAM,
 * Thread state, Sparklines, PIN RAM exposure & MASVS score). Supports tap-to-collapse/expand
 * and smooth dragging within its parent container.
 */
public class LivePerfHudView extends View {

    private static final int SAMPLE_INTERVAL_MS = 250;
    private static final int GAP_WINDOWS = 4;
    private static final int HIST_SIZE = 40;

    private static final int BG_DARK = 0xE80F172A;
    private static final int BORDER_NORMAL = 0xFF334155;
    private static final int BORDER_ALERT = 0xFFEF4444;
    private static final int TRACK_DARK = 0xFF1E293B;
    private static final int TEXT_LIGHT = 0xFFF8FAFC;
    private static final int TEXT_MUTED = 0xFF94A3B8;
    private static final int COLOR_GREEN = 0xFF10B981;
    private static final int COLOR_AMBER = 0xFFF59E0B;
    private static final int COLOR_RED = 0xFFEF4444;
    private static final int COLOR_SKY = 0xFF38BDF8;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF sparkRect = new RectF();
    private final Path sparkPath = new Path();

    private final long[] gapRing = new long[GAP_WINDOWS];
    private final float[] fpsHist = new float[HIST_SIZE];
    private final float[] cpuHist = new float[HIST_SIZE];
    private final float[] ramHist = new float[HIST_SIZE];

    private final ChaosInjectionManager chaos = ChaosInjectionManager.getInstance();
    private final Choreographer.FrameCallback frameCallback = this::onFrame;
    private final int touchSlop;

    private boolean expanded = true;
    private boolean running = false;

    private long lastFrameNs;
    private long windowStartNs;
    private int framesInWindow;
    private long windowMaxGapMs;
    private int ringIndex;

    private long lastCpuMs;
    private long lastWallMs;

    private int currentFps = 60;
    private long worstGapMs = 17L;
    private float currentCpuPct = 4f;
    private float usedHeapMb = 12f;
    private float nativeHeapMb = 8f;
    private float baselineHeapMb = -1f;
    private float peakHeapMb = 12f;

    private float downRawX;
    private float downRawY;
    private float startTransX;
    private float startTransY;
    private boolean dragging;

    public LivePerfHudView(Context context) {
        this(context, null);
    }

    public LivePerfHudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(BG_DARK);

        borderPaint.setStyle(Paint.Style.STROKE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        Arrays.fill(fpsHist, 1f);
        Arrays.fill(cpuHist, 0.05f);
        Arrays.fill(ramHist, 0.3f);

        setClickable(true);
        setContentDescription("Popup giám sát hiệu năng CPU, RAM và FPS thời gian thực");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.round(dp(expanded ? 216f : 198f));
        int h = Math.round(dp(expanded ? 156f : 34f));
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    public void setExpanded(boolean expanded) {
        if (this.expanded != expanded) {
            this.expanded = expanded;
            requestLayout();
            invalidate();
            post(this::clampTranslationToParent);
        }
    }

    public boolean isExpanded() {
        return expanded;
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrameNs = 0L;
        windowStartNs = 0L;
        framesInWindow = 0;
        windowMaxGapMs = 0L;
        ringIndex = 0;
        Arrays.fill(gapRing, 0L);
        lastCpuMs = Process.getElapsedCpuTime();
        lastWallMs = SystemClock.elapsedRealtime();
        sampleMemory();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void onFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNs != 0L) {
            long gapMs = (frameTimeNanos - lastFrameNs) / 1_000_000L;
            if (gapMs > windowMaxGapMs) {
                windowMaxGapMs = gapMs;
            }
        }
        lastFrameNs = frameTimeNanos;

        if (windowStartNs == 0L) {
            windowStartNs = frameTimeNanos;
        }
        framesInWindow++;
        long elapsedMs = (frameTimeNanos - windowStartNs) / 1_000_000L;
        if (elapsedMs >= SAMPLE_INTERVAL_MS) {
            int rawFps = Math.max(0, Math.round(framesInWindow * 1000f / elapsedMs));
            currentFps = windowMaxGapMs > 32L
                    ? Math.min(rawFps, Math.max(6, Math.round(1000f / windowMaxGapMs)))
                    : rawFps;
            framesInWindow = 0;
            windowStartNs = frameTimeNanos;

            gapRing[ringIndex % GAP_WINDOWS] = windowMaxGapMs;
            ringIndex++;
            windowMaxGapMs = 0L;
            long worst = 0L;
            for (long g : gapRing) {
                worst = Math.max(worst, g);
            }
            worstGapMs = worst;

            sampleCpuAndMemory();
            pushSparklineHistory();
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        usedHeapMb = Math.max(0.5f, (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f));
        nativeHeapMb = Math.max(0.5f, Debug.getNativeHeapAllocatedSize() / (1024f * 1024f));
        if (baselineHeapMb <= 0f) {
            baselineHeapMb = usedHeapMb;
        }
        if (usedHeapMb > peakHeapMb) {
            peakHeapMb = usedHeapMb;
        }
    }

    private void sampleCpuAndMemory() {
        sampleMemory();
        long cpuNow = Process.getElapsedCpuTime();
        long wallNow = SystemClock.elapsedRealtime();
        long dCpu = Math.max(0L, cpuNow - lastCpuMs);
        long dWall = Math.max(1L, wallNow - lastWallMs);
        lastCpuMs = cpuNow;
        lastWallMs = wallNow;

        float measuredPct = Math.min(100f, (dCpu * 100f) / dWall);
        boolean mainBlocked = DemoMetrics.isMainThreadBlockedActive()
                || chaos.isUiThreadJankActive()
                || worstGapMs > 32L;
        boolean heavyWork = DemoMetrics.getActiveHeavyTaskCount() > 0
                || chaos.isMemoryChurnAndRawPngActive();

        if (mainBlocked) {
            currentCpuPct = Math.max(measuredPct, 78f + (worstGapMs % 16L));
        } else if (heavyWork) {
            currentCpuPct = Math.max(measuredPct, 54f + ((cpuNow % 22L)));
        } else {
            currentCpuPct = Math.max(2f, Math.min(40f, measuredPct));
        }
    }

    private void pushSparklineHistory() {
        shiftPush(fpsHist, clamp01(currentFps / 60f));
        shiftPush(cpuHist, clamp01(currentCpuPct / 100f));
        float ramNorm = clamp01(usedHeapMb / Math.max(24f, peakHeapMb * 1.15f));
        shiftPush(ramHist, ramNorm);
    }

    private static void shiftPush(float[] arr, float val) {
        System.arraycopy(arr, 1, arr, 0, arr.length - 1);
        arr[arr.length - 1] = val;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startTransX = getTranslationX();
                startTransY = getTranslationY();
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                }
                if (dragging) {
                    applyClampedTranslation(startTransX + dx, startTransY + dy);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (!dragging) {
                    setExpanded(!expanded);
                    performClick();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void clampTranslationToParent() {
        applyClampedTranslation(getTranslationX(), getTranslationY());
    }

    private void applyClampedTranslation(float targetX, float targetY) {
        if (!(getParent() instanceof View)) {
            setTranslationX(targetX);
            setTranslationY(targetY);
            return;
        }
        View parent = (View) getParent();
        float pad = dp(4f);
        float minX = -getLeft() + pad;
        float maxX = parent.getWidth() - getRight() - pad;
        float minY = -getTop() + pad;
        float maxY = parent.getHeight() - getBottom() - pad;
        setTranslationX(Math.max(minX, Math.min(maxX, targetX)));
        setTranslationY(Math.max(minY, Math.min(maxY, targetY)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        boolean jank = currentFps < 45 || worstGapMs > 32L
                || chaos.isUiThreadJankActive() || DemoMetrics.isMainThreadBlockedActive();
        boolean churn = chaos.isMemoryChurnAndRawPngActive()
                || (usedHeapMb - baselineHeapMb) >= 6.0f;
        boolean secAlert = chaos.isAnyIssueActive() && chaos.getMasvsScore() < 5;
        boolean anyAlert = jank || churn || secAlert;

        float radius = dp(expanded ? 14f : 17f);
        float strokeW = dp(1.2f);
        cardRect.set(strokeW, strokeW, w - strokeW, h - strokeW);
        canvas.drawRoundRect(cardRect, radius, radius, bgPaint);

        borderPaint.setStrokeWidth(strokeW);
        borderPaint.setColor(anyAlert ? BORDER_ALERT : BORDER_NORMAL);
        canvas.drawRoundRect(cardRect, radius, radius, borderPaint);

        if (!expanded) {
            drawCompactPill(canvas, w, h, jank);
            return;
        }
        drawExpandedHud(canvas, w, h, jank, churn);
    }

    private void drawCompactPill(Canvas canvas, float w, float h, boolean jank) {
        float cy = h * 0.5f;
        barPaint.setColor(jank ? COLOR_RED : COLOR_GREEN);
        canvas.drawCircle(dp(13f), cy, dp(4f), barPaint);

        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(dp(10f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(TEXT_LIGHT);
        String text = String.format(Locale.US, "%d FPS · CPU %.0f%% · %.1fM",
                currentFps, currentCpuPct, usedHeapMb);
        canvas.drawText(text, dp(22f), cy + dp(3.5f), textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(TEXT_MUTED);
        canvas.drawText("[+]", w - dp(10f), cy + dp(3.5f), textPaint);
    }

    private void drawExpandedHud(Canvas canvas, float w, float h, boolean jank, boolean churn) {
        float padX = dp(10f);
        float innerW = w - padX * 2f;

        // Header
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(TEXT_LIGHT);
        canvas.drawText("⚡ LIVE SYSTEM HUD", padX, dp(16f), textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(jank || churn ? COLOR_RED : COLOR_GREEN);
        canvas.drawText((jank ? "NGHẼN!" : (churn ? "CHURN!" : "ỔN ĐỊNH")) + "  [–]",
                w - padX, dp(16f), textPaint);

        // Divider
        barPaint.setColor(0xFF1E293B);
        canvas.drawRect(padX, dp(21f), w - padX, dp(22f), barPaint);

        // 1. FPS Row
        int fpsColor = jank ? COLOR_RED : COLOR_GREEN;
        String fpsVal = String.format(Locale.US, "%d fps · %dms%s",
                currentFps, worstGapMs, jank ? " JANK" : "");
        drawMetricRow(canvas, padX, innerW, dp(34f), "FPS",
                clamp01(currentFps / 60f), fpsColor, fpsVal);

        // 2. CPU Row
        boolean mainBlocked = DemoMetrics.isMainThreadBlockedActive() || chaos.isUiThreadJankActive();
        int cpuColor = currentCpuPct >= 75f ? COLOR_RED : (currentCpuPct >= 45f ? COLOR_AMBER : COLOR_GREEN);
        String cpuVal = String.format(Locale.US, "%.0f%% · %s",
                currentCpuPct, mainBlocked ? "Main Khoá!" : "Pool 4T");
        drawMetricRow(canvas, padX, innerW, dp(57f), "CPU",
                clamp01(currentCpuPct / 100f), cpuColor, cpuVal);

        // 3. RAM Row
        float deltaMb = Math.max(0f, usedHeapMb - baselineHeapMb);
        int ramColor = churn ? COLOR_RED : COLOR_SKY;
        String ramVal = churn
                ? String.format(Locale.US, "%.1fM (+%.1fM)", usedHeapMb, deltaMb)
                : String.format(Locale.US, "%.1fM (Nat %.1fM)", usedHeapMb, nativeHeapMb);
        drawMetricRow(canvas, padX, innerW, dp(80f), "RAM",
                clamp01(usedHeapMb / Math.max(28f, peakHeapMb * 1.15f)), ramColor, ramVal);

        // Mini Sparklines (FPS, CPU, RAM)
        float sparkTop = dp(95f);
        float sparkBottom = h - dp(21f);
        sparkRect.set(padX, sparkTop, w - padX, sparkBottom);
        barPaint.setColor(0x99020617);
        canvas.drawRoundRect(sparkRect, dp(6f), dp(6f), barPaint);

        linePaint.setStrokeWidth(dp(1.4f));
        drawSparkline(canvas, sparkRect, fpsHist, jank ? COLOR_RED : COLOR_GREEN);
        drawSparkline(canvas, sparkRect, cpuHist, COLOR_AMBER);
        drawSparkline(canvas, sparkRect, ramHist, COLOR_SKY);

        // Footer: Security / MASVS & Drag hint
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(dp(8.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        int masvs = chaos.getMasvsScore();
        int pinPct = chaos.getPinRamExposurePct();
        textPaint.setColor((masvs < 5 || pinPct > 0) ? COLOR_RED : COLOR_GREEN);
        canvas.drawText(String.format(Locale.US, "PIN:%d%% MASVS:%d/5", pinPct, masvs),
                padX, h - dp(7f), textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(TEXT_MUTED);
        canvas.drawText("Kéo/Chạm", w - padX, h - dp(7f), textPaint);
    }

    private void drawMetricRow(Canvas canvas, float padX, float innerW, float baselineY,
                               String label, float ratio, int color, String valueText) {
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(TEXT_MUTED);
        canvas.drawText(label, padX, baselineY, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(color);
        canvas.drawText(valueText, padX + innerW, baselineY, textPaint);

        float barTop = baselineY + dp(3.5f);
        float barH = dp(4.5f);
        trackRect.set(padX, barTop, padX + innerW, barTop + barH);
        barPaint.setColor(TRACK_DARK);
        canvas.drawRoundRect(trackRect, barH / 2f, barH / 2f, barPaint);

        float fillW = Math.max(dp(4f), innerW * clamp01(ratio));
        trackRect.set(padX, barTop, padX + fillW, barTop + barH);
        barPaint.setColor(color);
        canvas.drawRoundRect(trackRect, barH / 2f, barH / 2f, barPaint);
    }

    private void drawSparkline(Canvas canvas, RectF area, float[] data, int color) {
        float pad = dp(3f);
        float left = area.left + pad;
        float top = area.top + pad;
        float w = Math.max(1f, area.width() - pad * 2f);
        float h = Math.max(1f, area.height() - pad * 2f);

        sparkPath.reset();
        float stepX = w / (data.length - 1);
        for (int i = 0; i < data.length; i++) {
            float x = left + i * stepX;
            float y = top + h * (1f - clamp01(data[i]));
            if (i == 0) {
                sparkPath.moveTo(x, y);
            } else {
                sparkPath.lineTo(x, y);
            }
        }
        linePaint.setColor(color);
        canvas.drawPath(sparkPath, linePaint);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
