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
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

/**
 * Dual-lane thread timeline & frame budget visualizer for Topic 2 (§2 Threading).
 * Shows Main Thread (UI) 18-frame burst stalls vs. 16.7 ms (60 FPS) budget and
 * ApduThreadPool Worker Thread offload activity, plus a 3-step execution flow list.
 */
public class ThreadingLaneView extends View {

    private static final int COLOR_SLATE_900 = 0xFF0F172A;
    private static final int COLOR_SLATE_700 = 0xFF334155;
    private static final int COLOR_SLATE_500 = 0xFF64748B;
    private static final int COLOR_SLATE_300 = 0xFFCBD5E1;
    private static final int COLOR_SLATE_200 = 0xFFE2E8F0;
    private static final int COLOR_SLATE_100 = 0xFFF1F5F9;
    private static final int COLOR_SLATE_50 = 0xFFF8FAFC;

    private static final int COLOR_BLUE = 0xFF2563EB;
    private static final int COLOR_BLUE_SOFT = 0x332563EB;
    private static final int COLOR_RED = 0xFFDC2626;
    private static final int COLOR_RED_BAR = 0xFFEF4444;
    private static final int COLOR_RED_BG = 0xFFFEF2F2;
    private static final int COLOR_RED_BORDER = 0xFFFECACA;

    private static final int COLOR_AMBER = 0xFFD97706;
    private static final int COLOR_AMBER_BG = 0xFFFFFBEB;
    private static final int COLOR_AMBER_BORDER = 0xFFFDE68A;

    private static final int COLOR_GREEN = 0xFF059669;
    private static final int COLOR_GREEN_BAR = 0xFF10B981;
    private static final int COLOR_GREEN_BG = 0xFFECFDF5;
    private static final int COLOR_GREEN_BORDER = 0xFFA7F3D0;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    private final ThreadingState state = new ThreadingState();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface sansBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Typeface sansRegular = Typeface.DEFAULT;
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final DashPathEffect dashEffect;
    private final RectF cardRect = new RectF();
    private final RectF laneRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF barRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Path checkPath = new Path();

    public ThreadingLaneView(Context context) {
        this(context, null);
    }

    public ThreadingLaneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashEffect = new DashPathEffect(new float[]{dp(4f), dp(3f)}, 0f);
        dashPaint.setPathEffect(dashEffect);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(COLOR_WHITE);
        setContentDescription("Biểu đồ hai luồng Main Thread và Worker Thread");
    }

    public void beginBeforeRun() {
        state.beginBeforeRun(SystemClock.elapsedRealtime());
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void onBeforeSyncFetchDone(long fetchMs) {
        state.onBeforeSyncFetchDone(fetchMs);
        invalidate();
        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void onBeforeBurstFrame(int frameIndex, long frameCostMs, long accumulatedMs) {
        state.onBeforeBurstFrame(frameIndex, frameCostMs, accumulatedMs);
        invalidate();
        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void finishBeforeRun(long totalBlockedMs) {
        state.finishBeforeRun(totalBlockedMs);
        invalidate();
    }

    public void beginAfterRun() {
        state.beginAfterRun(SystemClock.elapsedRealtime());
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void finishAfterRun(long blockedMs, long readMs) {
        state.finishAfterRun(blockedMs, readMs);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = Math.round(dp(320f));
        int desiredH = Math.round(dp(344f));
        setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        long nowMs = SystemClock.elapsedRealtime();

        // 1. Top KPI strip (48dp)
        float kpiH = dp(48f);
        drawKpiStrip(canvas, w, kpiH);

        // 2. Swimlane 1: Main Thread UI (82dp)
        float mainTop = kpiH + dp(10f);
        float mainH = dp(82f);
        drawMainThreadLane(canvas, w, mainTop, mainH, nowMs);

        // 3. Swimlane 2: Worker Thread Pool (68dp)
        float workerTop = mainTop + mainH + dp(10f);
        float workerH = dp(68f);
        drawWorkerThreadLane(canvas, w, workerTop, workerH, nowMs);

        // 4. Bottom 3-step execution flow list
        float stepsTop = workerTop + workerH + dp(12f);
        drawExecutionSteps(canvas, w, stepsTop, h, nowMs);

        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawKpiStrip(Canvas canvas, float w, float kpiH) {
        float gap = dp(8f);
        float cardW = (w - gap * 2f) / 3f;

        String blockedVal;
        String droppedVal;
        String threadVal;

        int blockedBg = COLOR_SLATE_50;
        int blockedBorder = COLOR_SLATE_200;
        int blockedColor = COLOR_SLATE_900;

        int droppedBg = COLOR_SLATE_50;
        int droppedBorder = COLOR_SLATE_200;
        int droppedColor = COLOR_SLATE_900;

        int threadBg = COLOR_SLATE_50;
        int threadBorder = COLOR_SLATE_200;
        int threadColor = COLOR_SLATE_900;

        if (!state.hasRun) {
            blockedVal = "—";
            droppedVal = "—";
            threadVal = "—";
        } else if (state.afterMode) {
            blockedVal = state.runInProgress
                    ? "0 ms"
                    : (state.mainBlockedMs <= 0L ? "<1 ms" : state.mainBlockedMs + " ms");
            droppedVal = state.droppedFrames + " khung";
            threadVal = "Worker Pool";

            blockedBg = COLOR_GREEN_BG;
            blockedBorder = COLOR_GREEN_BORDER;
            blockedColor = COLOR_GREEN;

            droppedBg = COLOR_GREEN_BG;
            droppedBorder = COLOR_GREEN_BORDER;
            droppedColor = COLOR_GREEN;

            threadBg = COLOR_GREEN_BG;
            threadBorder = COLOR_GREEN_BORDER;
            threadColor = COLOR_GREEN;
        } else {
            blockedVal = state.mainBlockedMs > 0L
                    ? String.format(Locale.US, "%,d ms", state.mainBlockedMs)
                    : "...";
            droppedVal = state.mainBlockedMs > 0L
                    ? "~" + state.droppedFrames + " khung"
                    : "...";
            threadVal = "Main (Khóa)";

            blockedBg = COLOR_RED_BG;
            blockedBorder = COLOR_RED_BORDER;
            blockedColor = COLOR_RED;

            droppedBg = COLOR_RED_BG;
            droppedBorder = COLOR_RED_BORDER;
            droppedColor = COLOR_RED;

            threadBg = COLOR_AMBER_BG;
            threadBorder = COLOR_AMBER_BORDER;
            threadColor = COLOR_AMBER;
        }

        drawKpiCard(canvas, 0f, cardW, kpiH, "MAIN THREAD GIỮ", blockedVal, blockedBg, blockedBorder, blockedColor);
        drawKpiCard(canvas, cardW + gap, cardW, kpiH, "KHUNG HÌNH RỚT", droppedVal, droppedBg, droppedBorder, droppedColor);
        drawKpiCard(canvas, (cardW + gap) * 2f, cardW, kpiH, "LUỒNG THỰC THI", threadVal, threadBg, threadBorder, threadColor);
    }

    private void drawKpiCard(Canvas canvas, float left, float cardW, float kpiH,
                             String label, String value, int bgColor, int borderColor, int valueColor) {
        float radius = dp(12f);
        cardRect.set(left, 0f, left + cardW, kpiH);
        fillPaint.setColor(bgColor);
        canvas.drawRoundRect(cardRect, radius, radius, fillPaint);

        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(borderColor);
        canvas.drawRoundRect(cardRect, radius, radius, strokePaint);

        float cx = left + cardW * 0.5f;
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(8.5f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(COLOR_SLATE_500);
        canvas.drawText(label, cx, dp(17f), textPaint);

        textPaint.setTypeface(monoBold);
        textPaint.setTextSize(dp(12f));
        textPaint.setColor(valueColor);
        canvas.drawText(value, cx, dp(36f), textPaint);
    }

    private void drawMainThreadLane(Canvas canvas, float w, float top, float laneH, long nowMs) {
        int bgColor = COLOR_SLATE_50;
        int borderColor = COLOR_SLATE_200;
        if (state.hasRun) {
            bgColor = state.afterMode ? COLOR_GREEN_BG : COLOR_RED_BG;
            borderColor = state.afterMode ? COLOR_GREEN_BORDER : COLOR_RED_BORDER;
        }

        laneRect.set(0f, top, w, top + laneH);
        float radius = dp(12f);
        fillPaint.setColor(bgColor);
        canvas.drawRoundRect(laneRect, radius, radius, fillPaint);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(borderColor);
        canvas.drawRoundRect(laneRect, radius, radius, strokePaint);

        float padX = dp(10f);
        float headerY = top + dp(18f);
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(10.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(COLOR_SLATE_900);
        canvas.drawText("MAIN THREAD (UI)", padX, headerY, textPaint);

        if (!state.hasRun) {
            drawInlinePill(canvas, w - padX, headerY, "Chờ chạy",
                    COLOR_WHITE, COLOR_SLATE_200, COLOR_SLATE_500);
        } else if (state.afterMode) {
            drawInlinePill(canvas, w - padX, headerY, "MƯỢT · 60 FPS",
                    COLOR_WHITE, COLOR_GREEN_BORDER, COLOR_GREEN);
        } else if (state.runInProgress) {
            drawInlinePill(canvas, w - padX, headerY,
                    "Khóa nhịp " + state.completedBurstFrames + "/" + ThreadingState.TOTAL_BURST_FRAMES,
                    COLOR_WHITE, COLOR_RED_BORDER, COLOR_RED);
        } else {
            drawInlinePill(canvas, w - padX, headerY, "BỊ CHẶN · 18 nhịp",
                    COLOR_WHITE, COLOR_RED_BORDER, COLOR_RED);
        }

        // 18-frame burst plot strip
        float plotLeft = padX;
        float plotRight = w - padX;
        float plotTop = top + dp(26f);
        float plotBottom = top + laneH - dp(8f);
        float plotH = Math.max(dp(20f), plotBottom - plotTop);

        trackRect.set(plotLeft, plotTop, plotRight, plotBottom);
        fillPaint.setColor(COLOR_WHITE);
        canvas.drawRoundRect(trackRect, dp(8f), dp(8f), fillPaint);

        // 16.7 ms frame budget dashed guide line
        float budgetY = plotBottom - dp(8f);
        dashPaint.setStrokeWidth(dp(1f));
        dashPaint.setColor(COLOR_GREEN);
        canvas.drawLine(plotLeft + dp(4f), budgetY, plotRight - dp(4f), budgetY, dashPaint);

        float labelReserve = dp(44f);
        float barsLeft = plotLeft + dp(6f);
        float barsRight = Math.max(barsLeft + dp(40f), plotRight - labelReserve);
        float barsW = barsRight - barsLeft;

        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(8f));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(COLOR_GREEN);
        canvas.drawText("16.7 ms", plotRight - dp(6f), budgetY - dp(2.5f), textPaint);

        int count = ThreadingState.TOTAL_BURST_FRAMES;
        float gap = dp(2.5f);
        float barW = Math.max(dp(2f), (barsW - gap * (count - 1)) / count);
        float barBottom = plotBottom - dp(2f);
        float maxBarH = Math.max(dp(10f), barBottom - plotTop - dp(4f));
        float pulse = 0.5f + 0.5f * (float) Math.sin(nowMs / 160.0);

        for (int i = 0; i < count; i++) {
            float bx = barsLeft + i * (barW + gap);
            if (!state.hasRun) {
                barRect.set(bx, barBottom - dp(3f), bx + barW, barBottom);
                fillPaint.setColor(COLOR_SLATE_200);
                canvas.drawRoundRect(barRect, dp(1.5f), dp(1.5f), fillPaint);
            } else if (state.afterMode) {
                float hBar = (i == count - 1) ? dp(5f) : dp(3.8f);
                barRect.set(bx, barBottom - hBar, bx + barW, barBottom);
                fillPaint.setColor(i == count - 1 ? COLOR_BLUE : COLOR_GREEN_BAR);
                canvas.drawRoundRect(barRect, dp(1.5f), dp(1.5f), fillPaint);
            } else {
                long dur = state.burstFrameMs[i];
                if (dur > 0L) {
                    float ratio = state.getBurstBarHeightRatio(i);
                    float hBar = Math.max(dp(12f), maxBarH * ratio);
                    barRect.set(bx, barBottom - hBar, bx + barW, barBottom);
                    fillPaint.setColor(COLOR_RED_BAR);
                    canvas.drawRoundRect(barRect, dp(1.5f), dp(1.5f), fillPaint);
                } else if (state.runInProgress && i == state.completedBurstFrames) {
                    float hBar = dp(10f + 14f * pulse);
                    barRect.set(bx, barBottom - hBar, bx + barW, barBottom);
                    fillPaint.setColor(COLOR_BLUE);
                    canvas.drawRoundRect(barRect, dp(1.5f), dp(1.5f), fillPaint);
                } else {
                    barRect.set(bx, barBottom - dp(3f), bx + barW, barBottom);
                    fillPaint.setColor(COLOR_SLATE_200);
                    canvas.drawRoundRect(barRect, dp(1.5f), dp(1.5f), fillPaint);
                }
            }
        }
    }

    private void drawWorkerThreadLane(Canvas canvas, float w, float top, float laneH, long nowMs) {
        int bgColor = COLOR_SLATE_50;
        int borderColor = COLOR_SLATE_200;
        if (state.hasRun) {
            bgColor = state.afterMode ? COLOR_GREEN_BG : COLOR_AMBER_BG;
            borderColor = state.afterMode ? COLOR_GREEN_BORDER : COLOR_AMBER_BORDER;
        }

        laneRect.set(0f, top, w, top + laneH);
        float radius = dp(12f);
        fillPaint.setColor(bgColor);
        canvas.drawRoundRect(laneRect, radius, radius, fillPaint);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(borderColor);
        canvas.drawRoundRect(laneRect, radius, radius, strokePaint);

        float padX = dp(10f);
        float headerY = top + dp(18f);
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(10.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(COLOR_SLATE_900);
        canvas.drawText("WORKER POOL (ApduThreadPool)", padX, headerY, textPaint);

        if (!state.hasRun) {
            drawInlinePill(canvas, w - padX, headerY, "Sẵn sàng · 4T",
                    COLOR_WHITE, COLOR_SLATE_200, COLOR_SLATE_500);
        } else if (!state.afterMode) {
            drawInlinePill(canvas, w - padX, headerY, "NHÀN RỖI",
                    COLOR_WHITE, COLOR_AMBER_BORDER, COLOR_AMBER);
        } else if (state.runInProgress) {
            drawInlinePill(canvas, w - padX, headerY, "Đang đọc thẻ...",
                    COLOR_WHITE, COLOR_SLATE_200, COLOR_BLUE);
        } else {
            drawInlinePill(canvas, w - padX, headerY, "Xong " + state.workerReadMs + " ms",
                    COLOR_WHITE, COLOR_GREEN_BORDER, COLOR_GREEN);
        }

        float trackTop = top + dp(28f);
        float trackBottom = top + laneH - dp(10f);
        float trackH = Math.max(dp(18f), trackBottom - trackTop);
        float trackRadius = dp(7f);
        trackRect.set(padX, trackTop, w - padX, trackBottom);

        if (!state.hasRun) {
            fillPaint.setColor(COLOR_WHITE);
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, fillPaint);
            strokePaint.setStrokeWidth(dp(0.8f));
            strokePaint.setColor(COLOR_SLATE_200);
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, strokePaint);

            textPaint.setTypeface(sansRegular);
            textPaint.setTextSize(dp(9.5f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(COLOR_SLATE_500);
            canvas.drawText("Chờ tác vụ nền...", trackRect.centerX(),
                    trackRect.centerY() + dp(3.5f), textPaint);
            return;
        }

        if (!state.afterMode) {
            fillPaint.setColor(COLOR_WHITE);
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, fillPaint);
            dashPaint.setStrokeWidth(dp(1f));
            dashPaint.setColor(COLOR_AMBER);
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, dashPaint);

            textPaint.setTypeface(sansRegular);
            textPaint.setTextSize(dp(9.5f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(COLOR_AMBER);
            canvas.drawText("Không dùng luồng nền — dồn lên Main Thread",
                    trackRect.centerX(), trackRect.centerY() + dp(3.5f), textPaint);
            return;
        }

        // After mode: active worker bar
        fillPaint.setColor(COLOR_WHITE);
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, fillPaint);

        float trackW = trackRect.width();
        float fillRatio = state.runInProgress
                ? (0.35f + 0.45f * (0.5f + 0.5f * (float) Math.sin(nowMs / 180.0)))
                : 1f;
        barRect.set(trackRect.left, trackRect.top,
                trackRect.left + Math.max(dp(24f), trackW * fillRatio), trackRect.bottom);
        fillPaint.setColor(state.runInProgress ? COLOR_BLUE : COLOR_GREEN_BAR);
        canvas.drawRoundRect(barRect, trackRadius, trackRadius, fillPaint);

        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(COLOR_WHITE);
        String workerText = state.runInProgress
                ? "fetchFromCard() · Đang chạy nền..."
                : "fetchFromCard() · APDU nền · 0 chặn UI";
        canvas.drawText(workerText, trackRect.centerX(), trackRect.centerY() + dp(3.5f), textPaint);
    }

    private void drawExecutionSteps(Canvas canvas, float w, float stepsTop, float h, long nowMs) {
        float rowH = dp(35f);
        float railX = dp(10f);
        float textLeft = dp(26f);
        float nodeR = dp(7f);
        float pulse = 0.5f + 0.5f * (float) Math.sin(nowMs / 160.0);

        float firstCy = stepsTop + rowH * 0.5f;
        float lastCy = stepsTop + (ThreadingState.STEP_COUNT - 1) * rowH + rowH * 0.5f;
        strokePaint.setStrokeWidth(dp(1.8f));
        strokePaint.setColor(COLOR_SLATE_200);
        canvas.drawLine(railX, firstCy, railX, lastCy, strokePaint);

        for (int i = 0; i < ThreadingState.STEP_COUNT; i++) {
            float rowTop = stepsTop + i * rowH;
            if (rowTop + rowH > h + dp(2f)) break;
            float cy = rowTop + rowH * 0.5f;
            int st = state.stepState[i];

            drawStepNode(canvas, railX, cy, nodeR, st, pulse);

            float baseline = cy + dp(4f);
            textPaint.setTypeface(st == ThreadingState.STEP_IDLE ? sansRegular : sansBold);
            textPaint.setTextSize(dp(10.5f));
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(st == ThreadingState.STEP_IDLE ? COLOR_SLATE_500 : COLOR_SLATE_900);
            String label = state.stepLabels[i] != null ? state.stepLabels[i] : "";
            canvas.drawText(label, textLeft, baseline, textPaint);

            String val = state.stepValues[i] != null ? state.stepValues[i] : "—";
            textPaint.setTypeface(monoBold);
            textPaint.setTextSize(dp(10f));
            textPaint.setTextAlign(Paint.Align.RIGHT);
            if (st == ThreadingState.STEP_DONE_OK) {
                textPaint.setColor(COLOR_GREEN);
            } else if (st == ThreadingState.STEP_DONE_FAULT) {
                textPaint.setColor(COLOR_RED);
            } else if (st == ThreadingState.STEP_RUNNING) {
                textPaint.setColor(COLOR_BLUE);
            } else {
                textPaint.setColor(COLOR_SLATE_500);
            }
            canvas.drawText(val, w, baseline, textPaint);
        }
    }

    private void drawStepNode(Canvas canvas, float cx, float cy, float r, int stepState, float pulse) {
        switch (stepState) {
            case ThreadingState.STEP_RUNNING:
                fillPaint.setColor(COLOR_BLUE_SOFT);
                canvas.drawCircle(cx, cy, r + dp(2f + 1.8f * pulse), fillPaint);
                fillPaint.setColor(COLOR_BLUE);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.3f), fillPaint);
                break;

            case ThreadingState.STEP_DONE_FAULT:
                fillPaint.setColor(COLOR_RED_BAR);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.4f), fillPaint);
                break;

            case ThreadingState.STEP_DONE_OK:
                fillPaint.setColor(COLOR_GREEN);
                canvas.drawCircle(cx, cy, r, fillPaint);
                checkPath.reset();
                checkPath.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                checkPath.lineTo(cx - r * 0.1f, cy + r * 0.34f);
                checkPath.lineTo(cx + r * 0.44f, cy - r * 0.3f);
                checkPaint.setStrokeWidth(dp(1.7f));
                canvas.drawPath(checkPath, checkPaint);
                break;

            default:
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, r, fillPaint);
                strokePaint.setStrokeWidth(dp(1.4f));
                strokePaint.setColor(COLOR_SLATE_300);
                canvas.drawCircle(cx, cy, r, strokePaint);
                break;
        }
    }

    private void drawInlinePill(Canvas canvas, float rightEdge, float textBaseline,
                                String text, int bgColor, int borderColor, int textColor) {
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(8.5f));
        float textW = textPaint.measureText(text);
        float padH = dp(6f);
        float pillW = textW + padH * 2f;
        float pillTop = textBaseline - dp(10.5f);
        float pillBottom = textBaseline + dp(3f);
        pillRect.set(rightEdge - pillW, pillTop, rightEdge, pillBottom);

        float radius = (pillBottom - pillTop) * 0.5f;
        fillPaint.setColor(bgColor);
        canvas.drawRoundRect(pillRect, radius, radius, fillPaint);

        strokePaint.setStrokeWidth(dp(0.8f));
        strokePaint.setColor(borderColor);
        canvas.drawRoundRect(pillRect, radius, radius, strokePaint);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(textColor);
        canvas.drawText(text, pillRect.centerX(), textBaseline - dp(0.5f), textPaint);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * Pure-Java dual-lane threading state for unit testing Before/After execution lifecycles,
     * dropped-frame math, and burst frame bar ratios without Android framework dependencies.
     */
    static final class ThreadingState {
        static final int TOTAL_BURST_FRAMES = 18;
        static final int STEP_COUNT = 3;
        static final int STEP_IDLE = 0;
        static final int STEP_RUNNING = 1;
        static final int STEP_DONE_FAULT = 2;
        static final int STEP_DONE_OK = 3;
        static final float FRAME_BUDGET_MS = 16.6667f;

        boolean hasRun;
        boolean afterMode;
        boolean runInProgress;
        boolean backgroundJankActive;
        long mainBlockedMs;
        long workerReadMs;
        int droppedFrames;
        int completedBurstFrames;
        final long[] burstFrameMs = new long[TOTAL_BURST_FRAMES];
        final int[] stepState = new int[STEP_COUNT];
        final String[] stepLabels = {
                "1. Gọi tác vụ đọc thẻ",
                "2. Trạng thái khung hình UI",
                "3. Kết quả cập nhật giao diện"
        };
        final String[] stepValues = {"Chờ", "Chờ", "Chờ"};
        long runStartClockMs;

        void beginBeforeRun(long nowMs) {
            hasRun = true;
            afterMode = false;
            runInProgress = true;
            backgroundJankActive = false;
            mainBlockedMs = 0L;
            workerReadMs = 0L;
            droppedFrames = 0;
            completedBurstFrames = 0;
            Arrays.fill(burstFrameMs, 0L);
            runStartClockMs = nowMs;

            stepLabels[0] = "1. Gọi fetchFromCard() đồng bộ trên main";
            stepLabels[1] = "2. Chặn 18 nhịp frame (~148 ms/nhịp)";
            stepLabels[2] = "3. Bật jankRunnable lặp 135 ms trên UI";

            stepState[0] = STEP_RUNNING;
            stepValues[0] = "Đang khóa...";
            stepState[1] = STEP_IDLE;
            stepValues[1] = "0/" + TOTAL_BURST_FRAMES + " nhịp";
            stepState[2] = STEP_IDLE;
            stepValues[2] = "Chờ";
        }

        void onBeforeSyncFetchDone(long fetchMs) {
            mainBlockedMs = Math.max(1L, fetchMs);
            droppedFrames = computeDroppedFrames(mainBlockedMs);
            stepState[0] = STEP_DONE_FAULT;
            stepValues[0] = mainBlockedMs + " ms";
            stepState[1] = STEP_RUNNING;
            stepValues[1] = "0/" + TOTAL_BURST_FRAMES + " nhịp";
        }

        void onBeforeBurstFrame(int frameIndex, long frameCostMs, long accumulatedMs) {
            int slot = Math.max(0, Math.min(TOTAL_BURST_FRAMES - 1, frameIndex - 1));
            burstFrameMs[slot] = Math.max(1L, frameCostMs);
            completedBurstFrames = Math.max(completedBurstFrames, slot + 1);
            mainBlockedMs = Math.max(mainBlockedMs, accumulatedMs);
            droppedFrames = computeDroppedFrames(mainBlockedMs);
            stepState[1] = STEP_RUNNING;
            stepValues[1] = completedBurstFrames + "/" + TOTAL_BURST_FRAMES + " nhịp";
        }

        void finishBeforeRun(long totalBlockedMs) {
            hasRun = true;
            afterMode = false;
            runInProgress = false;
            backgroundJankActive = true;
            mainBlockedMs = Math.max(1L, totalBlockedMs);
            workerReadMs = 0L;
            droppedFrames = computeDroppedFrames(mainBlockedMs);

            for (int i = 0; i < TOTAL_BURST_FRAMES; i++) {
                if (burstFrameMs[i] <= 0L) {
                    burstFrameMs[i] = 148L;
                }
            }
            completedBurstFrames = TOTAL_BURST_FRAMES;

            stepState[0] = STEP_DONE_FAULT;
            stepState[1] = STEP_DONE_FAULT;
            stepValues[1] = TOTAL_BURST_FRAMES + "/" + TOTAL_BURST_FRAMES + " nhịp";
            stepState[2] = STEP_DONE_FAULT;
            stepValues[2] = "Đang lặp";
        }

        void beginAfterRun(long nowMs) {
            hasRun = true;
            afterMode = true;
            runInProgress = true;
            backgroundJankActive = false;
            mainBlockedMs = 0L;
            workerReadMs = 0L;
            droppedFrames = 0;
            completedBurstFrames = 0;
            Arrays.fill(burstFrameMs, 0L);
            runStartClockMs = nowMs;

            stepLabels[0] = "1. Hủy jankRunnable (Gỡ chặn UI)";
            stepLabels[1] = "2. Đẩy đọc APDU sang ApduThreadPool";
            stepLabels[2] = "3. Callback cập nhật UI trên Main";

            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "Xong";
            stepState[1] = STEP_RUNNING;
            stepValues[1] = "Đang chạy...";
            stepState[2] = STEP_IDLE;
            stepValues[2] = "Chờ";
        }

        void finishAfterRun(long blockedMs, long readMs) {
            hasRun = true;
            afterMode = true;
            runInProgress = false;
            backgroundJankActive = false;
            mainBlockedMs = Math.max(0L, blockedMs);
            workerReadMs = Math.max(1L, readMs);
            droppedFrames = computeDroppedFrames(mainBlockedMs);

            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "Xong";
            stepState[1] = STEP_DONE_OK;
            stepValues[1] = workerReadMs + " ms";
            stepState[2] = STEP_DONE_OK;
            stepValues[2] = mainBlockedMs <= 0L ? "<1 ms" : mainBlockedMs + " ms";
        }

        float getBurstBarHeightRatio(int slot) {
            if (slot < 0 || slot >= TOTAL_BURST_FRAMES) return 0f;
            long dur = burstFrameMs[slot];
            return dur > 0L ? clamp01(dur / 180f) : 0f;
        }

        static int computeDroppedFrames(long blockedMs) {
            return Math.round(Math.max(0L, blockedMs) / FRAME_BUDGET_MS);
        }
    }
}
