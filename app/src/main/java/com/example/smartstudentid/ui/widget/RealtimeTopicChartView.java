package com.example.smartstudentid.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

/**
 * Realtime scrolling waveform + per-topic metric bar chart for optimization topics §1–§5.
 * Uses Choreographer for live sampling (~20 Hz) and holds pure-Java telemetry state in
 * {@link ChartState} so topic transitions and ring-buffer math are directly unit-testable.
 */
public class RealtimeTopicChartView extends View {

    public static final int TOPIC_BOTTLENECK = 0;
    public static final int TOPIC_THREADING = 1;
    public static final int TOPIC_CACHE = 2;
    public static final int TOPIC_IMAGE = 3;
    public static final int TOPIC_STARTUP = 4;

    public static final int MODE_APDU = 0;
    public static final int MODE_BEFORE = 1;
    public static final int MODE_AFTER = 2;

    private static final long SAMPLE_INTERVAL_NS = 50_000_000L; // 50 ms (~20 Hz)
    private static final long FPS_WINDOW_NS = 500_000_000L;
    private static final int GAP_WINDOWS = 6;

    private static final int COLOR_BLUE = 0xFF2563EB;
    private static final int COLOR_BLUE_FILL = 0x262563EB;
    private static final int COLOR_RED = 0xFFDC2626;
    private static final int COLOR_RED_FILL = 0x26DC2626;
    private static final int COLOR_GREEN = 0xFF059669;
    private static final int COLOR_GREEN_FILL = 0x26059669;
    private static final int COLOR_SLATE_900 = 0xFF0F172A;
    private static final int COLOR_SLATE_700 = 0xFF334155;
    private static final int COLOR_SLATE_500 = 0xFF64748B;
    private static final int COLOR_SLATE_400 = 0xFF94A3B8;
    private static final int COLOR_SLATE_200 = 0xFFE2E8F0;
    private static final int COLOR_SLATE_100 = 0xFFF1F5F9;
    private static final int COLOR_SLATE_50 = 0xFFF8FAFC;

    private static final String[] STEP_LABELS = {"SEL", "PIN", "INFO", "AES", "ACAD"};

    private final ChartState state = new ChartState();
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thresholdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plotRect = new RectF();
    private final RectF barRect = new RectF();
    private final Path areaPath = new Path();

    private final long[] gapRing = new long[GAP_WINDOWS];
    private final Choreographer.FrameCallback frameCallback = this::onFrame;

    private boolean running;
    private long lastFrameNs;
    private long windowStartNs;
    private int framesInWindow;
    private long windowMaxGapMs;
    private int ringIndex;
    private long lastSampleNs;
    private float tickMaxGapMs;

    public RealtimeTopicChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(COLOR_SLATE_200);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(COLOR_SLATE_200);
        thresholdPaint.setStyle(Paint.Style.STROKE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        barPaint.setStyle(Paint.Style.FILL);
        setContentDescription("Đồ thị thời gian thực theo từng chuyên đề tối ưu");
    }

    public void setTopic(int topic) {
        state.setTopic(topic, readUsedHeapMb());
        invalidate();
    }

    public void setAfterMode(boolean afterMode) {
        state.setAfterSwitch(afterMode);
        invalidate();
    }

    public void setColdStartMs(long coldStartMs) {
        state.setColdStartMs(coldStartMs);
        invalidate();
    }

    public void onRunStarted(boolean afterMode) {
        state.onRunStarted(afterMode, SystemClock.elapsedRealtime(), readUsedHeapMb());
        invalidate();
    }

    public void onRunEnded() {
        state.onRunEnded();
        invalidate();
    }

    public void onApduFrame(int ins, long elapsedMs) {
        state.onApduFrame(ins, elapsedMs, SystemClock.elapsedRealtime());
        invalidate();
    }

    public void recordBottleneckResult(long[] stepMs, long totalMs) {
        state.recordBottleneckResult(stepMs, totalMs);
        invalidate();
    }

    public void recordThreadingResult(boolean afterMode, long blockedMs, long totalMs) {
        state.recordThreadingResult(afterMode, blockedMs, totalMs);
        invalidate();
    }

    public void recordCacheResult(boolean afterMode, boolean fromCache, long totalMs, int apduCount) {
        state.recordCacheResult(afterMode, fromCache, totalMs, apduCount);
        invalidate();
    }

    public void recordImageResult(boolean afterMode, double sizeKb, int packets, double ramMb, boolean ok) {
        state.recordImageResult(afterMode, sizeKb, packets, ramMb, ok, readUsedHeapMb());
        invalidate();
    }

    public void recordLoginResult(boolean afterMode, long pbkdf2Ms, int remaining, int length) {
        state.recordLoginResult(afterMode, pbkdf2Ms, remaining, length);
        invalidate();
    }

    public void resetHistory() {
        state.resetHistory();
        invalidate();
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

    private void start() {
        if (running) return;
        running = true;
        lastFrameNs = 0L;
        windowStartNs = 0L;
        framesInWindow = 0;
        windowMaxGapMs = 0L;
        ringIndex = 0;
        lastSampleNs = 0L;
        tickMaxGapMs = 0f;
        Arrays.fill(gapRing, 0L);
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void onFrame(long frameTimeNanos) {
        if (!running) return;

        float gapMs = 16.67f;
        if (lastFrameNs != 0L) {
            gapMs = Math.max(1f, (frameTimeNanos - lastFrameNs) / 1_000_000f);
            long gapRounded = Math.round(gapMs);
            if (gapRounded > windowMaxGapMs) {
                windowMaxGapMs = gapRounded;
            }
            if (gapMs > tickMaxGapMs) {
                tickMaxGapMs = gapMs;
            }
        } else {
            tickMaxGapMs = 16.67f;
        }
        lastFrameNs = frameTimeNanos;

        if (windowStartNs == 0L) {
            windowStartNs = frameTimeNanos;
        }
        framesInWindow++;
        long windowElapsedNs = frameTimeNanos - windowStartNs;
        if (windowElapsedNs >= FPS_WINDOW_NS) {
            int fps = Math.max(0, Math.round(framesInWindow * 1_000_000_000f / windowElapsedNs));
            framesInWindow = 0;
            windowStartNs = frameTimeNanos;
            gapRing[ringIndex % GAP_WINDOWS] = windowMaxGapMs;
            ringIndex++;
            windowMaxGapMs = 0L;
            long worst = 0L;
            for (long g : gapRing) {
                worst = Math.max(worst, g);
            }
            state.updateFrameStats(fps, worst);
        }

        if (lastSampleNs == 0L) {
            lastSampleNs = frameTimeNanos;
            state.seedHeapIfNeeded(readUsedHeapMb());
        } else {
            long sinceSampleNs = frameTimeNanos - lastSampleNs;
            if (sinceSampleNs >= SAMPLE_INTERVAL_NS) {
                int missedTicks = (int) Math.max(1L, Math.min(15L, sinceSampleNs / SAMPLE_INTERVAL_NS));
                lastSampleNs = frameTimeNanos;
                float sampleGap = tickMaxGapMs > 0f ? tickMaxGapMs : gapMs;
                tickMaxGapMs = 0f;
                state.onSampleTick(SystemClock.elapsedRealtime(), sampleGap, missedTicks, readUsedHeapMb());
                invalidate();
            }
        }

        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        borderPaint.setStrokeWidth(dp(1f));
        gridPaint.setStrokeWidth(dp(0.8f));
        thresholdPaint.setStrokeWidth(dp(1.1f));
        thresholdPaint.setPathEffect(new DashPathEffect(new float[]{dp(4f), dp(3f)}, 0f));
        linePaint.setStrokeWidth(dp(2f));

        drawTopHeader(canvas, w);

        float plotTop = dp(20f);
        float plotBottom = Math.max(plotTop + dp(48f), h - dp(56f));
        plotRect.set(0f, plotTop, w, plotBottom);
        drawWaveformPlot(canvas, plotRect);

        float bottomTop = plotBottom + dp(8f);
        drawTopicBottomPanel(canvas, w, bottomTop, h - dp(2f));
    }

    private void drawTopHeader(Canvas canvas, float w) {
        String left;
        String right;
        int rightColor;

        switch (state.topic) {
            case TOPIC_THREADING:
                left = String.format(Locale.US, "%d FPS • trễ max %d ms", state.fps, state.worstGapMs);
                boolean jank = state.worstGapMs > 24;
                right = jank ? "Rớt khung" : "Mượt 60 FPS";
                rightColor = jank ? COLOR_RED : COLOR_GREEN;
                break;

            case TOPIC_CACHE:
                left = state.lastOpenMs >= 0
                        ? String.format(Locale.US, "Lần gần nhất: %d ms (%s)",
                        state.lastOpenMs, state.lastFromCache ? "CACHE" : "THẺ")
                        : "Chờ mở hồ sơ...";
                right = state.histCount > 0 ? state.histCount + " lần đo" : "Đỏ=Thẻ · Xanh=Cache";
                rightColor = state.lastOpenMs >= 0
                        ? (state.lastFromCache ? COLOR_GREEN : COLOR_RED)
                        : COLOR_SLATE_500;
                break;

            case TOPIC_IMAGE:
                left = String.format(Locale.US, "Heap: %.1f MB (đỉnh %.1f MB)",
                        state.currentHeapMb, state.peakHeapMb);
                if (state.liveUploadPackets > 0) {
                    right = "APDU 0x22: " + state.liveUploadPackets + " gói";
                    rightColor = COLOR_GREEN;
                } else if (state.afterPackets > 0) {
                    right = "WebP: " + state.afterPackets + " gói";
                    rightColor = COLOR_GREEN;
                } else {
                    right = "Ngưỡng thẻ 8 KB";
                    rightColor = state.afterSwitch ? COLOR_GREEN : COLOR_RED;
                }
                break;

            case TOPIC_STARTUP:
                left = (state.coldStartMs > 0 ? "Cold: " + state.coldStartMs + " ms • " : "")
                        + String.format(Locale.US, "PIN RAM: %.0f%%", state.pinExposurePct);
                if (state.runInProgress && state.activeRunAfterMode) {
                    right = "Đang băm PBKDF2...";
                    rightColor = COLOR_GREEN;
                } else if (state.pinExposurePct > 0f) {
                    right = "Còn trong String";
                    rightColor = COLOR_RED;
                } else {
                    right = "Đã xóa (0%)";
                    rightColor = COLOR_GREEN;
                }
                break;

            default:
                left = state.lastTotalMs > 0
                        ? String.format(Locale.US, "Tổng 5 APDU: %d ms", state.lastTotalMs)
                        : (state.runInProgress ? "Đang đo từng lệnh APDU..." : "Chờ đọc thẻ...");
                right = "Kênh ISO 7816-4";
                rightColor = COLOR_BLUE;
                break;
        }

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(dp(10.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(COLOR_SLATE_900);
        canvas.drawText(left, 0f, dp(13f), textPaint);

        textPaint.setTextSize(dp(10f));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(rightColor);
        canvas.drawText(right, w, dp(13f), textPaint);
    }

    private void drawWaveformPlot(Canvas canvas, RectF rect) {
        float radius = dp(10f);
        bgPaint.setColor(COLOR_SLATE_50);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        canvas.drawRoundRect(rect, radius, radius, borderPaint);

        float yMax = state.computeYMax();
        float padX = dp(4f);
        float padTop = dp(10f);
        float padBottom = dp(11f);
        float innerLeft = rect.left + padX;
        float innerRight = rect.right - padX;
        float innerTop = rect.top + padTop;
        float innerBottom = rect.bottom - padBottom;
        float innerH = Math.max(1f, innerBottom - innerTop);
        float innerW = Math.max(1f, innerRight - innerLeft);

        canvas.save();
        canvas.clipRect(rect.left + dp(1f), rect.top + dp(1f), rect.right - dp(1f), rect.bottom - dp(1f));

        for (int g = 1; g <= 3; g++) {
            float gy = innerBottom - (innerH * g / 4f);
            canvas.drawLine(rect.left, gy, rect.right, gy, gridPaint);
        }

        drawThresholdIfNeeded(canvas, rect, innerTop, innerBottom, innerH, yMax);

        int n = ChartState.CAPACITY;
        float stepX = innerW / (n - 1);
        float prevX = innerLeft;
        float prevY = valueToY(state.values[0], innerTop, innerBottom, yMax);

        for (int i = 1; i < n; i++) {
            float x = innerLeft + i * stepX;
            float y = valueToY(state.values[i], innerTop, innerBottom, yMax);
            int mode = state.modes[i];

            areaPath.reset();
            areaPath.moveTo(prevX, innerBottom);
            areaPath.lineTo(prevX, prevY);
            areaPath.lineTo(x, y);
            areaPath.lineTo(x, innerBottom);
            areaPath.close();

            fillPaint.setColor(fillColorForMode(mode));
            canvas.drawPath(areaPath, fillPaint);

            linePaint.setColor(strokeColorForMode(mode));
            canvas.drawLine(prevX, prevY, x, y, linePaint);

            prevX = x;
            prevY = y;
        }

        int latestMode = state.modes[n - 1];
        int dotColor = strokeColorForMode(latestMode);
        float pulse = 0.5f + 0.5f * (float) Math.sin(SystemClock.elapsedRealtime() / 180.0);
        barPaint.setColor((dotColor & 0x00FFFFFF) | 0x33000000);
        canvas.drawCircle(prevX, prevY, dp(4f + 2f * pulse), barPaint);
        barPaint.setColor(dotColor);
        canvas.drawCircle(prevX, prevY, dp(2.8f), barPaint);

        canvas.restore();

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(dp(8.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(COLOR_SLATE_500);
        canvas.drawText(formatYAxisMax(yMax), rect.left + dp(6f), rect.top + dp(10f), textPaint);
        textPaint.setColor(COLOR_SLATE_400);
        canvas.drawText("0", rect.left + dp(6f), rect.bottom - dp(2f), textPaint);
    }

    private void drawThresholdIfNeeded(Canvas canvas, RectF rect, float innerTop, float innerBottom,
                                       float innerH, float yMax) {
        float thresholdVal = -1f;
        String label = null;
        int color = COLOR_SLATE_400;

        if (state.topic == TOPIC_THREADING) {
            thresholdVal = 16.67f;
            label = "16.7 ms (60 FPS)";
            color = COLOR_GREEN;
        } else if (state.topic == TOPIC_CACHE && yMax >= 25f) {
            thresholdVal = 20f;
            label = "20 ms";
            color = COLOR_SLATE_400;
        } else if (state.topic == TOPIC_STARTUP) {
            thresholdVal = 50f;
            label = "50%";
            color = COLOR_SLATE_400;
        }

        if (thresholdVal <= 0f || thresholdVal > yMax) return;

        float ty = innerBottom - (thresholdVal / yMax) * innerH;
        thresholdPaint.setColor(color);
        canvas.drawLine(rect.left, ty, rect.right, ty, thresholdPaint);

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(dp(8.5f));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(color);
        float labelY = Math.max(rect.top + dp(10f), ty - dp(3f));
        canvas.drawText(label, rect.right - dp(6f), labelY, textPaint);
    }

    private void drawTopicBottomPanel(Canvas canvas, float w, float top, float bottom) {
        if (state.topic == TOPIC_BOTTLENECK) {
            drawBottleneckBars(canvas, w, top, bottom);
        } else if (state.topic == TOPIC_CACHE) {
            drawCacheHistoryBars(canvas, w, top, bottom);
        } else {
            drawTwoComparisonRows(canvas, w, top, bottom);
        }
    }

    private void drawBottleneckBars(Canvas canvas, float w, float top, float bottom) {
        float gap = dp(6f);
        float colW = (w - gap * 4f) / 5f;
        long maxMs = 1L;
        for (long v : state.stepMs) {
            if (v > maxMs) maxMs = v;
        }

        float trackTop = top + dp(12f);
        float trackBottom = Math.max(trackTop + dp(8f), bottom - dp(12f));
        float trackH = trackBottom - trackTop;

        for (int i = 0; i < 5; i++) {
            float left = i * (colW + gap);
            float centerX = left + colW / 2f;
            long val = state.stepMs[i];

            barRect.set(left, trackTop, left + colW, trackBottom);
            barPaint.setColor(COLOR_SLATE_100);
            canvas.drawRoundRect(barRect, dp(4f), dp(4f), barPaint);

            if (val > 0L) {
                float fillH = Math.max(dp(4f), trackH * (val / (float) maxMs));
                barRect.set(left, trackBottom - fillH, left + colW, trackBottom);
                barPaint.setColor(COLOR_BLUE);
                canvas.drawRoundRect(barRect, dp(4f), dp(4f), barPaint);
            }

            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(9f));
            textPaint.setColor(val > 0L ? COLOR_BLUE : COLOR_SLATE_400);
            canvas.drawText(val > 0L ? val + "ms" : "—", centerX, top + dp(9f), textPaint);

            textPaint.setColor(COLOR_SLATE_700);
            textPaint.setTextSize(dp(8.5f));
            canvas.drawText(STEP_LABELS[i], centerX, bottom, textPaint);
        }
    }

    private void drawCacheHistoryBars(Canvas canvas, float w, float top, float bottom) {
        if (state.histCount == 0) {
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(dp(10f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(COLOR_SLATE_500);
            canvas.drawText("Bấm “Mở hồ sơ” ở Before & After để xem lịch sử trên đồ thị",
                    w / 2f, (top + bottom) / 2f + dp(3f), textPaint);
            return;
        }

        int slots = ChartState.MAX_HISTORY;
        float gap = dp(5f);
        float colW = (w - gap * (slots - 1)) / slots;
        long maxMs = 1L;
        for (int i = 0; i < state.histCount; i++) {
            if (state.histMs[i] > maxMs) maxMs = state.histMs[i];
        }

        float trackTop = top + dp(12f);
        float trackBottom = Math.max(trackTop + dp(8f), bottom - dp(12f));
        float trackH = trackBottom - trackTop;

        for (int i = 0; i < slots; i++) {
            float left = i * (colW + gap);
            float centerX = left + colW / 2f;

            barRect.set(left, trackTop, left + colW, trackBottom);
            barPaint.setColor(COLOR_SLATE_100);
            canvas.drawRoundRect(barRect, dp(4f), dp(4f), barPaint);

            if (i < state.histCount) {
                long ms = state.histMs[i];
                boolean cached = state.histFromCache[i];
                int color = cached ? COLOR_GREEN : COLOR_RED;
                float fillH = Math.max(dp(4f), trackH * (ms / (float) maxMs));
                barRect.set(left, trackBottom - fillH, left + colW, trackBottom);
                barPaint.setColor(color);
                canvas.drawRoundRect(barRect, dp(4f), dp(4f), barPaint);

                textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setTextSize(dp(8.5f));
                textPaint.setColor(color);
                canvas.drawText(ms + "ms", centerX, top + dp(9f), textPaint);

                textPaint.setColor(COLOR_SLATE_700);
                textPaint.setTextSize(dp(8f));
                canvas.drawText("#" + (i + 1) + "·" + state.histApdu[i] + "A", centerX, bottom, textPaint);
            }
        }
    }

    private void drawTwoComparisonRows(Canvas canvas, float w, float top, float bottom) {
        float rowH = Math.max(dp(16f), (bottom - top - dp(6f)) / 2f);
        float beforeRatio = 0f;
        float afterRatio = 0f;
        String beforeText;
        String afterText;

        if (state.topic == TOPIC_THREADING) {
            long scale = Math.max(1L, Math.max(state.beforeBlockedMs, state.afterTotalMs));
            beforeRatio = state.beforeBlockedMs >= 0 ? clamp01(state.beforeBlockedMs / (float) scale) : 0f;
            afterRatio = state.afterBlockedMs >= 0 ? Math.max(0.04f, clamp01(state.afterBlockedMs / (float) scale)) : 0f;
            beforeText = state.beforeBlockedMs >= 0
                    ? state.beforeBlockedMs + " ms • " + Math.round(state.beforeBlockedMs / 16.6667f) + " khung rớt"
                    : "Chưa đo (chạy ở Before)";
            afterText = state.afterBlockedMs >= 0
                    ? (state.afterBlockedMs <= 0 ? "<1 ms" : state.afterBlockedMs + " ms")
                    + " • 0 khung (" + state.afterTotalMs + " ms)"
                    : "Chưa đo (chạy ở After)";
        } else if (state.topic == TOPIC_IMAGE) {
            double scaleKb = Math.max(16.0, Math.max(state.beforeSizeKb, state.afterSizeKb));
            beforeRatio = state.beforeSizeKb >= 0 ? clamp01((float) (state.beforeSizeKb / scaleKb)) : 0f;
            afterRatio = state.afterSizeKb >= 0 ? Math.max(0.05f, clamp01((float) (state.afterSizeKb / scaleKb))) : 0f;
            beforeText = state.beforeSizeKb >= 0
                    ? String.format(Locale.US, "%.0f KB • %d gói (>8 KB)", state.beforeSizeKb, state.beforePackets)
                    : "Chưa đo (Before PNG)";
            afterText = state.afterSizeKb >= 0
                    ? String.format(Locale.US, "%.1f KB • %d gói (<8 KB)", state.afterSizeKb, state.afterPackets)
                    : "Chưa đo (After WebP)";
        } else {
            beforeRatio = state.beforePbkdf2Ms >= 0 ? 1f : 0f;
            float afterPct = state.afterLength > 0 ? (state.afterRemaining / (float) state.afterLength) : 0f;
            afterRatio = state.afterPbkdf2Ms >= 0 ? Math.max(0.04f, clamp01(afterPct)) : 0f;
            beforeText = state.beforePbkdf2Ms >= 0
                    ? "100% còn (String) • " + state.beforePbkdf2Ms + " ms"
                    : "Chưa đo (Before String)";
            afterText = state.afterPbkdf2Ms >= 0
                    ? state.afterRemaining + "/" + state.afterLength + " ký tự • " + state.afterPbkdf2Ms + " ms"
                    : "Chưa đo (After char[])";
        }

        drawComparisonRow(canvas, w, top, rowH, "Before", beforeRatio, beforeText, COLOR_RED);
        drawComparisonRow(canvas, w, top + rowH + dp(6f), rowH, "After", afterRatio, afterText, COLOR_GREEN);
    }

    private void drawComparisonRow(Canvas canvas, float w, float rowTop, float rowH,
                                   String label, float ratio, String summary, int color) {
        float textBaseline = rowTop + rowH * 0.72f;
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(color);
        canvas.drawText(label, 0f, textBaseline, textPaint);

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setColor(ratio > 0f ? COLOR_SLATE_900 : COLOR_SLATE_400);
        float summaryW = textPaint.measureText(summary);
        canvas.drawText(summary, w, textBaseline, textPaint);

        float trackLeft = dp(40f);
        float maxFitW = w - trackLeft - dp(8f) - summaryW;
        float trackW = Math.max(dp(24f), Math.min(w * 0.30f, maxFitW));
        float barH = dp(7f);
        float barTop = rowTop + (rowH - barH) / 2f;

        barRect.set(trackLeft, barTop, trackLeft + trackW, barTop + barH);
        barPaint.setColor(COLOR_SLATE_100);
        canvas.drawRoundRect(barRect, barH / 2f, barH / 2f, barPaint);

        if (ratio > 0f) {
            float fillW = Math.max(dp(4f), trackW * ratio);
            barRect.set(trackLeft, barTop, trackLeft + fillW, barTop + barH);
            barPaint.setColor(color);
            canvas.drawRoundRect(barRect, barH / 2f, barH / 2f, barPaint);
        }
    }

    private float valueToY(float value, float innerTop, float innerBottom, float yMax) {
        float clamped = Math.max(0f, Math.min(yMax, value));
        return innerBottom - (clamped / Math.max(0.001f, yMax)) * (innerBottom - innerTop);
    }

    private String formatYAxisMax(float yMax) {
        if (state.topic == TOPIC_IMAGE) {
            return String.format(Locale.US, "%.0f MB", yMax);
        }
        if (state.topic == TOPIC_STARTUP) {
            return "100%";
        }
        return String.format(Locale.US, "%.0f ms", yMax);
    }

    private static int strokeColorForMode(int mode) {
        if (mode == MODE_BEFORE) return COLOR_RED;
        if (mode == MODE_AFTER) return COLOR_GREEN;
        return COLOR_BLUE;
    }

    private static int fillColorForMode(int mode) {
        if (mode == MODE_BEFORE) return COLOR_RED_FILL;
        if (mode == MODE_AFTER) return COLOR_GREEN_FILL;
        return COLOR_BLUE_FILL;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float readUsedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return Math.max(0.1f, (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * Pure-Java telemetry state and 60-sample sliding window for unit testing and Canvas drawing.
     */
    static final class ChartState {
        static final int CAPACITY = 60;
        static final int MAX_HISTORY = 8;

        final float[] values = new float[CAPACITY];
        final int[] modes = new int[CAPACITY];

        int topic = TOPIC_BOTTLENECK;
        boolean afterSwitch;
        boolean runInProgress;
        boolean activeRunAfterMode;
        long runStartMs;
        long currentStepStartMs;

        // §1 Bottleneck
        final long[] stepMs = new long[5];
        boolean gotInfoInRun;
        long lastTotalMs;

        // §2 Threading
        int fps = 60;
        long worstGapMs = 17;
        long beforeBlockedMs = -1;
        long afterBlockedMs = -1;
        long afterTotalMs = -1;

        // §3 Cache
        final long[] histMs = new long[MAX_HISTORY];
        final boolean[] histFromCache = new boolean[MAX_HISTORY];
        final int[] histApdu = new int[MAX_HISTORY];
        int histCount;
        long lastOpenMs = -1;
        boolean lastFromCache;

        // §4 Image & Heap
        boolean heapSeeded;
        float currentHeapMb;
        float peakHeapMb;
        int liveUploadPackets;
        double beforeSizeKb = -1;
        int beforePackets = -1;
        double beforeRamMb = -1;
        double afterSizeKb = -1;
        int afterPackets = -1;
        boolean afterOk;

        // §5 Startup & Zeroization
        long coldStartMs;
        long beforePbkdf2Ms = -1;
        long afterPbkdf2Ms = -1;
        int afterRemaining = -1;
        int afterLength;
        float pinExposurePct;
        int pinExposureMode = MODE_AFTER;

        void setTopic(int newTopic, float usedHeapMb) {
            this.topic = newTopic;
            this.runInProgress = false;
            resetWaveformBaseline(usedHeapMb);
        }

        void setAfterSwitch(boolean afterMode) {
            this.afterSwitch = afterMode;
        }

        void setColdStartMs(long coldStartMs) {
            this.coldStartMs = coldStartMs;
        }

        void seedHeapIfNeeded(float usedHeapMb) {
            if (topic == TOPIC_IMAGE && !heapSeeded) {
                heapSeeded = true;
                currentHeapMb = usedHeapMb;
                peakHeapMb = usedHeapMb;
                Arrays.fill(values, usedHeapMb);
            }
        }

        void updateFrameStats(int fps, long worstGapMs) {
            this.fps = fps;
            this.worstGapMs = worstGapMs;
        }

        void onRunStarted(boolean afterMode, long nowMs, float usedHeapMb) {
            runInProgress = true;
            activeRunAfterMode = afterMode;
            runStartMs = nowMs;
            currentStepStartMs = nowMs;

            if (topic == TOPIC_BOTTLENECK) {
                Arrays.fill(stepMs, 0L);
                gotInfoInRun = false;
                lastTotalMs = 0L;
            } else if (topic == TOPIC_IMAGE) {
                liveUploadPackets = 0;
                currentHeapMb = usedHeapMb;
                peakHeapMb = usedHeapMb;
            } else if (topic == TOPIC_STARTUP) {
                pinExposurePct = 100f;
                pinExposureMode = afterMode ? MODE_AFTER : MODE_BEFORE;
                pushSample(100f, pinExposureMode);
            }
        }

        void onRunEnded() {
            runInProgress = false;
        }

        void onApduFrame(int ins, long elapsedMs, long nowMs) {
            if (topic == TOPIC_BOTTLENECK) {
                int idx = mapStepIndex(ins);
                if (idx >= 0) {
                    stepMs[idx] = elapsedMs;
                    currentStepStartMs = nowMs;
                    pushSample(Math.max(1f, elapsedMs), MODE_APDU);
                }
            } else if (topic == TOPIC_IMAGE && ins == 0x22) {
                liveUploadPackets++;
            }
        }

        private int mapStepIndex(int ins) {
            switch (ins) {
                case 0xA4:
                    return 0;
                case 0x10:
                    return 1;
                case 0x21:
                    if (!gotInfoInRun) {
                        gotInfoInRun = true;
                        return 2;
                    }
                    return 3;
                case 0x30:
                    return 4;
                default:
                    return -1;
            }
        }

        void onSampleTick(long nowMs, float sampleGapMs, int missedTicks, float usedHeapMb) {
            switch (topic) {
                case TOPIC_THREADING: {
                    int mode = sampleGapMs > 24f ? MODE_BEFORE : MODE_AFTER;
                    int repeats = sampleGapMs > 30f ? Math.max(1, missedTicks) : 1;
                    for (int i = 0; i < repeats; i++) {
                        pushSample(sampleGapMs, mode);
                    }
                    break;
                }
                case TOPIC_CACHE: {
                    float val = runInProgress ? Math.max(1f, nowMs - runStartMs) : 0f;
                    int mode = runInProgress
                            ? (activeRunAfterMode ? MODE_AFTER : MODE_BEFORE)
                            : (lastFromCache ? MODE_AFTER : (afterSwitch ? MODE_AFTER : MODE_BEFORE));
                    pushSample(val, mode);
                    break;
                }
                case TOPIC_IMAGE: {
                    heapSeeded = true;
                    currentHeapMb = usedHeapMb;
                    if (usedHeapMb > peakHeapMb) {
                        peakHeapMb = usedHeapMb;
                    }
                    int mode = runInProgress
                            ? (activeRunAfterMode ? MODE_AFTER : MODE_BEFORE)
                            : (afterSwitch ? MODE_AFTER : MODE_BEFORE);
                    pushSample(usedHeapMb, mode);
                    break;
                }
                case TOPIC_STARTUP: {
                    pushSample(pinExposurePct, pinExposureMode);
                    break;
                }
                default: {
                    float val = runInProgress ? Math.max(0f, nowMs - currentStepStartMs) : 0f;
                    pushSample(val, MODE_APDU);
                    break;
                }
            }
        }

        void recordBottleneckResult(long[] steps, long totalMs) {
            runInProgress = false;
            lastTotalMs = totalMs;
            if (steps != null) {
                int len = Math.min(5, steps.length);
                System.arraycopy(steps, 0, stepMs, 0, len);
            }
        }

        void recordThreadingResult(boolean afterMode, long blockedMs, long totalMs) {
            runInProgress = false;
            if (afterMode) {
                afterBlockedMs = blockedMs;
                afterTotalMs = totalMs;
            } else {
                beforeBlockedMs = blockedMs;
            }
        }

        void recordCacheResult(boolean afterMode, boolean fromCache, long totalMs, int apduCount) {
            runInProgress = false;
            lastOpenMs = totalMs;
            lastFromCache = fromCache;
            int mode = fromCache ? MODE_AFTER : MODE_BEFORE;
            pushSample(Math.max(1f, totalMs), mode);

            if (histCount == MAX_HISTORY) {
                System.arraycopy(histMs, 1, histMs, 0, MAX_HISTORY - 1);
                System.arraycopy(histFromCache, 1, histFromCache, 0, MAX_HISTORY - 1);
                System.arraycopy(histApdu, 1, histApdu, 0, MAX_HISTORY - 1);
                histCount--;
            }
            histMs[histCount] = totalMs;
            histFromCache[histCount] = fromCache;
            histApdu[histCount] = apduCount;
            histCount++;
        }

        void recordImageResult(boolean afterMode, double sizeKb, int packets, double ramMb,
                               boolean ok, float usedHeapMb) {
            runInProgress = false;
            currentHeapMb = usedHeapMb;
            peakHeapMb = Math.max(peakHeapMb, usedHeapMb);
            if (afterMode) {
                afterSizeKb = sizeKb;
                afterPackets = packets;
                afterOk = ok;
            } else {
                beforeSizeKb = sizeKb;
                beforePackets = packets;
                beforeRamMb = ramMb;
            }
        }

        void recordLoginResult(boolean afterMode, long pbkdf2Ms, int remaining, int length) {
            runInProgress = false;
            if (afterMode) {
                afterPbkdf2Ms = pbkdf2Ms;
                afterRemaining = Math.max(0, remaining);
                afterLength = Math.max(1, length);
                float pct = (afterRemaining * 100f) / afterLength;
                pinExposurePct = pct;
                pinExposureMode = MODE_AFTER;
                pushSample(pct, MODE_AFTER);
            } else {
                beforePbkdf2Ms = pbkdf2Ms;
                pinExposurePct = 100f;
                pinExposureMode = MODE_BEFORE;
                pushSample(100f, MODE_BEFORE);
            }
        }

        void resetHistory() {
            histCount = 0;
            lastOpenMs = -1;
            lastFromCache = false;
            Arrays.fill(histMs, 0L);
            Arrays.fill(histFromCache, false);
            Arrays.fill(histApdu, 0);
            resetWaveformBaseline(currentHeapMb);
        }

        float computeYMax() {
            if (topic == TOPIC_STARTUP) {
                return 100f;
            }
            float maxVal = 0f;
            for (float v : values) {
                if (v > maxVal) maxVal = v;
            }
            if (topic == TOPIC_BOTTLENECK) {
                for (long s : stepMs) {
                    if (s > maxVal) maxVal = s;
                }
                return Math.max(10f, maxVal * 1.2f);
            }
            if (topic == TOPIC_THREADING) {
                return Math.max(40f, maxVal * 1.15f);
            }
            if (topic == TOPIC_CACHE) {
                for (int i = 0; i < histCount; i++) {
                    if (histMs[i] > maxVal) maxVal = histMs[i];
                }
                return Math.max(30f, maxVal * 1.2f);
            }
            return Math.max(25f, Math.max(peakHeapMb, maxVal) * 1.15f);
        }

        void pushSample(float value, int mode) {
            System.arraycopy(values, 1, values, 0, CAPACITY - 1);
            System.arraycopy(modes, 1, modes, 0, CAPACITY - 1);
            values[CAPACITY - 1] = value;
            modes[CAPACITY - 1] = mode;
        }

        private void resetWaveformBaseline(float usedHeapMb) {
            if (topic == TOPIC_THREADING) {
                Arrays.fill(values, 16.67f);
                Arrays.fill(modes, MODE_AFTER);
            } else if (topic == TOPIC_IMAGE) {
                heapSeeded = true;
                currentHeapMb = usedHeapMb;
                peakHeapMb = usedHeapMb;
                Arrays.fill(values, usedHeapMb);
                Arrays.fill(modes, afterSwitch ? MODE_AFTER : MODE_BEFORE);
            } else if (topic == TOPIC_BOTTLENECK) {
                Arrays.fill(values, 0f);
                Arrays.fill(modes, MODE_APDU);
            } else {
                Arrays.fill(values, 0f);
                Arrays.fill(modes, MODE_AFTER);
            }
        }
    }
}
