package com.example.smartstudentid.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
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
 * Visual live Waterfall Timeline & Stepper for Topic 1 (§1 Bottleneck).
 * Replaces plain-text trace logs with 3 KPI cards and a 5-stage sequential
 * waterfall chart highlighting the slowest APDU stage in Before mode vs.
 * bypassed/cached instant hits in After mode.
 */
public class ExecutionWaterfallView extends View {

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
    private static final int COLOR_RED_PEAK = 0xFFB91C1C;
    private static final int COLOR_RED_BG = 0xFFFEF2F2;
    private static final int COLOR_RED_BORDER = 0xFFFECACA;

    private static final int COLOR_AMBER = 0xFFD97706;
    private static final int COLOR_AMBER_BG = 0xFFFFFBEB;
    private static final int COLOR_AMBER_BORDER = 0xFFFDE68A;

    private static final int COLOR_GREEN = 0xFF059669;
    private static final int COLOR_GREEN_BG = 0xFFECFDF5;
    private static final int COLOR_GREEN_BORDER = 0xFFA7F3D0;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    private final WaterfallState state = new WaterfallState();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint railPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface sansBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Typeface sansRegular = Typeface.DEFAULT;
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final RectF cardRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF barRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Path checkPath = new Path();

    public ExecutionWaterfallView(Context context) {
        this(context, null);
    }

    public ExecutionWaterfallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        railPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(COLOR_WHITE);
        setContentDescription("Biểu đồ luồng thực thi 5 bước APDU trực quan");
    }

    public void beginBeforeRun() {
        state.beginBeforeRun(SystemClock.elapsedRealtime());
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void startBeforeStage(int index, String liveNote) {
        state.startBeforeStage(index, SystemClock.elapsedRealtime(), liveNote);
        invalidate();
        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void updateStageProgress(int index, int cumulativeApduCount, String liveNote) {
        state.updateStageProgress(index, cumulativeApduCount, liveNote);
        invalidate();
    }

    public void completeBeforeStage(int index, long durationMs, int cumulativeApduCount) {
        state.completeBeforeStage(index, durationMs, cumulativeApduCount);
        invalidate();
    }

    public void finishBeforeRun(long[] stepMs, long finalTotalMs, int finalApduCount) {
        state.finishBeforeRun(stepMs, finalTotalMs, finalApduCount);
        invalidate();
    }

    public void beginAfterRun() {
        state.beginAfterRun();
        invalidate();
        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void finishAfterRun(long[] stepMs, long finalTotalMs) {
        state.finishAfterRun(stepMs, finalTotalMs);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = Math.round(dp(320f));
        int desiredH = Math.round(dp(364f));
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

        // 1. Top KPI strip
        float kpiH = dp(48f);
        drawKpiStrip(canvas, w, kpiH, nowMs);

        // 2. 5-Stage Waterfall Stepper
        float rowsTop = kpiH + dp(12f);
        float rowH = dp(56f);
        drawStageRows(canvas, w, rowsTop, rowH, nowMs);

        // 3. Bottom timeline axis
        float axisTop = rowsTop + WaterfallState.STAGE_COUNT * rowH;
        drawBottomAxis(canvas, w, axisTop, h, nowMs);

        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawKpiStrip(Canvas canvas, float w, float kpiH, long nowMs) {
        float gap = dp(8f);
        float cardW = (w - gap * 2f) / 3f;

        String timeVal;
        String apduVal;
        String energyVal;

        int timeBg = COLOR_SLATE_50;
        int timeBorder = COLOR_SLATE_200;
        int timeColor = COLOR_SLATE_900;

        int apduBg = COLOR_SLATE_50;
        int apduBorder = COLOR_SLATE_200;
        int apduColor = COLOR_SLATE_900;

        int energyBg = COLOR_SLATE_50;
        int energyBorder = COLOR_SLATE_200;
        int energyColor = COLOR_SLATE_900;

        if (!state.hasRun) {
            timeVal = "—";
            apduVal = "—";
            energyVal = "—";
        } else if (state.afterMode) {
            timeVal = state.runInProgress ? "..." : String.format(Locale.US, "%,d ms", state.totalMs);
            apduVal = "0 APDU";
            energyVal = "~0.02 mJ";

            timeBg = COLOR_GREEN_BG;
            timeBorder = COLOR_GREEN_BORDER;
            timeColor = COLOR_GREEN;

            apduBg = COLOR_GREEN_BG;
            apduBorder = COLOR_GREEN_BORDER;
            apduColor = COLOR_GREEN;

            energyBg = COLOR_GREEN_BG;
            energyBorder = COLOR_GREEN_BORDER;
            energyColor = COLOR_GREEN;
        } else {
            long displayMs = state.getLiveDisplayTotalMs(nowMs);
            double displayEnergy = WaterfallState.computeEnergyMj(state.totalApduCount, displayMs);
            timeVal = String.format(Locale.US, "%,d ms", displayMs);
            apduVal = state.totalApduCount + " APDU";
            energyVal = String.format(Locale.US, "~%.2f mJ", displayEnergy);

            boolean activeBlue = state.runInProgress && state.totalApduCount == 0;
            timeBg = activeBlue ? COLOR_SLATE_50 : COLOR_RED_BG;
            timeBorder = activeBlue ? COLOR_SLATE_200 : COLOR_RED_BORDER;
            timeColor = activeBlue ? COLOR_BLUE : COLOR_RED;

            apduBg = COLOR_RED_BG;
            apduBorder = COLOR_RED_BORDER;
            apduColor = COLOR_RED;

            energyBg = COLOR_AMBER_BG;
            energyBorder = COLOR_AMBER_BORDER;
            energyColor = COLOR_AMBER;
        }

        drawKpiCard(canvas, 0f, cardW, kpiH, "TỔNG THỜI GIAN", timeVal, timeBg, timeBorder, timeColor);
        drawKpiCard(canvas, cardW + gap, cardW, kpiH, "LƯU LƯỢNG I/O", apduVal, apduBg, apduBorder, apduColor);
        drawKpiCard(canvas, (cardW + gap) * 2f, cardW, kpiH, "NĂNG LƯỢNG I/O", energyVal, energyBg, energyBorder, energyColor);
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
        textPaint.setTextSize(dp(12.5f));
        textPaint.setColor(valueColor);
        canvas.drawText(value, cx, dp(36f), textPaint);
    }

    private void drawStageRows(Canvas canvas, float w, float rowsTop, float rowH, long nowMs) {
        float railX = dp(10f);
        float contentLeft = dp(26f);
        float trackW = Math.max(dp(40f), w - contentLeft);
        float nodeRadius = dp(7.5f);

        // Vertical connector rail behind circles
        float firstCy = rowsTop + dp(20f);
        float lastCy = rowsTop + (WaterfallState.STAGE_COUNT - 1) * rowH + dp(20f);
        railPaint.setStrokeWidth(dp(2f));
        railPaint.setColor(COLOR_SLATE_200);
        canvas.drawLine(railX, firstCy, railX, lastCy, railPaint);

        float pulse = 0.5f + 0.5f * (float) Math.sin(nowMs / 160.0);

        for (int i = 0; i < WaterfallState.STAGE_COUNT; i++) {
            float rowTop = rowsTop + i * rowH;
            float cy = rowTop + dp(20f);
            int st = state.stageState[i];
            boolean isPeak = (!state.afterMode && !state.runInProgress && state.bottleneckIndex == i);

            // Status node circle
            drawStatusNode(canvas, railX, cy, nodeRadius, st, isPeak, pulse);

            // Line 1: Stage Title (left) & Status / Timing (right)
            float titleBaseline = rowTop + dp(14f);
            textPaint.setTypeface(sansBold);
            textPaint.setTextSize(dp(11.5f));
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(st == WaterfallState.STATE_IDLE ? COLOR_SLATE_500 : COLOR_SLATE_900);
            canvas.drawText(WaterfallState.STAGE_TITLES[i], contentLeft, titleBaseline, textPaint);

            drawRowRightStatus(canvas, w, titleBaseline, i, st, isPeak);

            // Line 2: Horizontal Waterfall Track & Bar
            float trackTop = rowTop + dp(22f);
            float trackH = dp(10f);
            float trackRadius = trackH * 0.5f;
            trackRect.set(contentLeft, trackTop, contentLeft + trackW, trackTop + trackH);
            fillPaint.setColor(st == WaterfallState.STATE_BYPASSED ? COLOR_GREEN_BG : COLOR_SLATE_100);
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, fillPaint);

            if (st == WaterfallState.STATE_BYPASSED) {
                strokePaint.setStrokeWidth(dp(0.8f));
                strokePaint.setColor(COLOR_GREEN_BORDER);
                canvas.drawRoundRect(trackRect, trackRadius, trackRadius, strokePaint);
            } else if (st != WaterfallState.STATE_IDLE) {
                float startRatio = state.getStageStartRatio(i, nowMs);
                float widthRatio = state.getStageWidthRatio(i, nowMs);
                float minBarW = st == WaterfallState.STATE_CACHE_HIT
                        ? dp(10f)
                        : (st == WaterfallState.STATE_RUNNING ? dp(12f) : dp(6f));
                float barLeft = contentLeft + startRatio * trackW;
                float barW = Math.max(minBarW, widthRatio * trackW);
                if (barLeft + barW > contentLeft + trackW) {
                    barLeft = Math.max(contentLeft, contentLeft + trackW - barW);
                }
                float barRight = Math.min(contentLeft + trackW, barLeft + barW);
                barRect.set(barLeft, trackTop, barRight, trackTop + trackH);

                if (st == WaterfallState.STATE_CACHE_HIT) {
                    fillPaint.setColor(COLOR_GREEN);
                } else if (st == WaterfallState.STATE_RUNNING) {
                    fillPaint.setColor(COLOR_BLUE);
                } else {
                    fillPaint.setColor(isPeak ? COLOR_RED_PEAK : COLOR_RED_BAR);
                }
                canvas.drawRoundRect(barRect, trackRadius, trackRadius, fillPaint);
            }

            // Line 3: Subtitle below track
            float subBaseline = rowTop + dp(45f);
            textPaint.setTypeface(sansRegular);
            textPaint.setTextSize(dp(9.5f));
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(COLOR_SLATE_500);
            canvas.drawText(WaterfallState.STAGE_SUBTITLES[i], contentLeft, subBaseline, textPaint);

            if (st == WaterfallState.STATE_RUNNING && state.stageLiveNote[i] != null) {
                textPaint.setTextAlign(Paint.Align.RIGHT);
                textPaint.setColor(COLOR_BLUE);
                canvas.drawText(state.stageLiveNote[i], w, subBaseline, textPaint);
            } else if (isPeak) {
                textPaint.setTypeface(sansBold);
                textPaint.setTextAlign(Paint.Align.RIGHT);
                textPaint.setColor(COLOR_RED);
                canvas.drawText("Chiếm thời gian cao nhất", w, subBaseline, textPaint);
            }
        }
    }

    private void drawStatusNode(Canvas canvas, float cx, float cy, float r,
                                int stageState, boolean isPeak, float pulse) {
        switch (stageState) {
            case WaterfallState.STATE_RUNNING:
                fillPaint.setColor(COLOR_BLUE_SOFT);
                canvas.drawCircle(cx, cy, r + dp(2.5f + 2f * pulse), fillPaint);
                fillPaint.setColor(COLOR_BLUE);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.5f), fillPaint);
                break;

            case WaterfallState.STATE_DONE:
                fillPaint.setColor(isPeak ? COLOR_RED_PEAK : COLOR_RED_BAR);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, isPeak ? dp(3f) : dp(2.2f), fillPaint);
                break;

            case WaterfallState.STATE_BYPASSED:
            case WaterfallState.STATE_CACHE_HIT:
                fillPaint.setColor(COLOR_GREEN);
                canvas.drawCircle(cx, cy, r, fillPaint);
                checkPath.reset();
                checkPath.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                checkPath.lineTo(cx - r * 0.1f, cy + r * 0.34f);
                checkPath.lineTo(cx + r * 0.44f, cy - r * 0.3f);
                checkPaint.setStrokeWidth(dp(1.8f));
                canvas.drawPath(checkPath, checkPaint);
                break;

            default:
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, r, fillPaint);
                strokePaint.setStrokeWidth(dp(1.5f));
                strokePaint.setColor(COLOR_SLATE_300);
                canvas.drawCircle(cx, cy, r, strokePaint);
                break;
        }
    }

    private void drawRowRightStatus(Canvas canvas, float w, float baselineY,
                                    int index, int stageState, boolean isPeak) {
        textPaint.setTextAlign(Paint.Align.RIGHT);
        if (stageState == WaterfallState.STATE_IDLE) {
            textPaint.setTypeface(sansRegular);
            textPaint.setTextSize(dp(10f));
            textPaint.setColor(COLOR_SLATE_500);
            canvas.drawText("Chờ chạy", w, baselineY, textPaint);
            return;
        }

        if (stageState == WaterfallState.STATE_RUNNING) {
            textPaint.setTypeface(sansBold);
            textPaint.setTextSize(dp(10f));
            textPaint.setColor(COLOR_BLUE);
            canvas.drawText("Đang chạy...", w, baselineY, textPaint);
            return;
        }

        if (stageState == WaterfallState.STATE_BYPASSED) {
            drawInlinePill(canvas, w, baselineY, "Bỏ qua · 0 APDU",
                    COLOR_GREEN_BG, COLOR_GREEN_BORDER, COLOR_GREEN);
            return;
        }

        if (stageState == WaterfallState.STATE_CACHE_HIT) {
            String label = "Cache · " + state.stageDurationMs[index] + " ms";
            drawInlinePill(canvas, w, baselineY, label,
                    COLOR_GREEN_BG, COLOR_GREEN_BORDER, COLOR_GREEN);
            return;
        }

        // STATE_DONE (Before)
        long dur = state.stageDurationMs[index];
        int pct = state.getStagePercent(index);
        String timingText = pct > 0
                ? String.format(Locale.US, "%d ms (%d%%)", dur, pct)
                : String.format(Locale.US, "%d ms", dur);

        textPaint.setTypeface(monoBold);
        textPaint.setTextSize(dp(10.5f));
        textPaint.setColor(isPeak ? COLOR_RED_PEAK : COLOR_RED);
        float timingW = textPaint.measureText(timingText);
        canvas.drawText(timingText, w, baselineY, textPaint);

        if (isPeak) {
            drawInlinePill(canvas, w - timingW - dp(6f), baselineY, "NGHẼN NHẤT",
                    COLOR_RED_BG, COLOR_RED_BORDER, COLOR_RED_PEAK);
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

    private void drawBottomAxis(Canvas canvas, float w, float axisTop, float h, long nowMs) {
        float contentLeft = dp(26f);
        float lineY = axisTop + dp(4f);
        railPaint.setStrokeWidth(dp(1f));
        railPaint.setColor(COLOR_SLATE_200);
        canvas.drawLine(contentLeft, lineY, w, lineY, railPaint);

        float textY = Math.min(h - dp(2f), lineY + dp(13f));
        textPaint.setTypeface(sansRegular);
        textPaint.setTextSize(dp(9f));

        if (!state.hasRun) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(COLOR_SLATE_500);
            canvas.drawText("Bấm Chưa tối ưu / Đã tối ưu để xem trực quan 5 bước",
                    (contentLeft + w) * 0.5f, textY, textPaint);
            return;
        }

        long scaleMs = state.computeEffectiveScaleMs(nowMs);
        textPaint.setColor(COLOR_SLATE_500);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("0 ms", contentLeft, textY, textPaint);

        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.format(Locale.US, "%,d ms", scaleMs / 2L),
                (contentLeft + w) * 0.5f, textY, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        if (state.afterMode && !state.runInProgress) {
            textPaint.setTypeface(sansBold);
            textPaint.setColor(COLOR_GREEN);
            canvas.drawText(state.totalMs + " ms (tiết kiệm >99%)", w, textY, textPaint);
        } else {
            canvas.drawText(String.format(Locale.US, "%,d ms", scaleMs), w, textY, textPaint);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * Pure-Java waterfall timeline state for unit testing stage offsets, bottleneck detection,
     * and Before/After contrast scaling without Android framework dependencies.
     */
    static final class WaterfallState {
        static final int STAGE_COUNT = 5;
        static final int STATE_IDLE = 0;
        static final int STATE_RUNNING = 1;
        static final int STATE_DONE = 2;
        static final int STATE_BYPASSED = 3;
        static final int STATE_CACHE_HIT = 4;

        static final String[] STAGE_TITLES = {
                "1. SELECT Applet",
                "2. VERIFY PIN",
                "3. Giải mã AES-128",
                "4. Ký RSA-1024",
                "5. Học vụ & 35 gói ảnh"
        };

        static final String[] STAGE_SUBTITLES = {
                "0xA4 · 1 gói",
                "0x10 · 1 gói",
                "0x21 · 1 gói",
                "0x40 · 2 gói",
                "0x30/0x23 · 36 gói"
        };

        boolean hasRun;
        boolean afterMode;
        boolean runInProgress;
        final int[] stageState = new int[STAGE_COUNT];
        final long[] stageStartMs = new long[STAGE_COUNT];
        final long[] stageDurationMs = new long[STAGE_COUNT];
        final String[] stageLiveNote = new String[STAGE_COUNT];
        int activeStageIndex = -1;
        long activeStageStartClockMs;
        long totalMs;
        long lastBeforeTotalMs;
        int totalApduCount;
        double energyMj;
        int bottleneckIndex = -1;

        void beginBeforeRun(long nowMs) {
            hasRun = true;
            afterMode = false;
            runInProgress = true;
            Arrays.fill(stageState, STATE_IDLE);
            Arrays.fill(stageStartMs, 0L);
            Arrays.fill(stageDurationMs, 0L);
            Arrays.fill(stageLiveNote, null);
            activeStageIndex = -1;
            activeStageStartClockMs = nowMs;
            totalMs = 0L;
            totalApduCount = 0;
            energyMj = 0.0;
            bottleneckIndex = -1;
        }

        void startBeforeStage(int index, long nowMs, String liveNote) {
            if (index < 0 || index >= STAGE_COUNT) return;
            long offset = 0L;
            for (int i = 0; i < index; i++) {
                offset += Math.max(0L, stageDurationMs[i]);
            }
            stageStartMs[index] = offset;
            stageDurationMs[index] = 0L;
            stageState[index] = STATE_RUNNING;
            stageLiveNote[index] = liveNote;
            activeStageIndex = index;
            activeStageStartClockMs = nowMs;
        }

        void updateStageProgress(int index, int cumulativeApduCount, String liveNote) {
            if (index < 0 || index >= STAGE_COUNT) return;
            totalApduCount = Math.max(0, cumulativeApduCount);
            stageLiveNote[index] = liveNote;
        }

        void completeBeforeStage(int index, long durationMs, int cumulativeApduCount) {
            if (index < 0 || index >= STAGE_COUNT) return;
            long safeDur = Math.max(1L, durationMs);
            stageDurationMs[index] = safeDur;
            stageState[index] = STATE_DONE;
            stageLiveNote[index] = null;
            if (activeStageIndex == index) {
                activeStageIndex = -1;
            }
            totalApduCount = Math.max(totalApduCount, cumulativeApduCount);
            long sum = 0L;
            for (int i = 0; i < STAGE_COUNT; i++) {
                if (stageState[i] == STATE_DONE) {
                    sum += stageDurationMs[i];
                }
            }
            totalMs = sum;
            energyMj = computeEnergyMj(totalApduCount, totalMs);
        }

        void finishBeforeRun(long[] stepMs, long finalTotalMs, int finalApduCount) {
            hasRun = true;
            afterMode = false;
            runInProgress = false;
            activeStageIndex = -1;

            long cursor = 0L;
            long peakVal = -1L;
            int peakIdx = -1;
            for (int i = 0; i < STAGE_COUNT; i++) {
                long dur = (stepMs != null && i < stepMs.length)
                        ? Math.max(1L, stepMs[i])
                        : Math.max(1L, stageDurationMs[i]);
                stageStartMs[i] = cursor;
                stageDurationMs[i] = dur;
                stageState[i] = STATE_DONE;
                stageLiveNote[i] = null;
                cursor += dur;
                if (dur > peakVal) {
                    peakVal = dur;
                    peakIdx = i;
                }
            }
            totalMs = Math.max(cursor, finalTotalMs);
            lastBeforeTotalMs = totalMs;
            totalApduCount = Math.max(0, finalApduCount);
            bottleneckIndex = peakIdx;
            energyMj = computeEnergyMj(totalApduCount, totalMs);
        }

        void beginAfterRun() {
            hasRun = true;
            afterMode = true;
            runInProgress = true;
            Arrays.fill(stageStartMs, 0L);
            Arrays.fill(stageDurationMs, 0L);
            Arrays.fill(stageLiveNote, null);
            activeStageIndex = -1;
            bottleneckIndex = -1;
            totalMs = 0L;
            totalApduCount = 0;
            energyMj = 0.02;

            stageState[0] = STATE_BYPASSED;
            stageLiveNote[0] = "Bỏ qua · 0 APDU";
            stageState[1] = STATE_BYPASSED;
            stageLiveNote[1] = "Bỏ qua · 0 APDU";
            stageState[2] = STATE_RUNNING;
            stageLiveNote[2] = "Cache AES-GCM";
            stageState[3] = STATE_BYPASSED;
            stageLiveNote[3] = "Bỏ qua · 0 APDU";
            stageState[4] = STATE_RUNNING;
            stageLiveNote[4] = "Cache AES-GCM";
        }

        void finishAfterRun(long[] stepMs, long finalTotalMs) {
            hasRun = true;
            afterMode = true;
            runInProgress = false;
            activeStageIndex = -1;
            bottleneckIndex = -1;
            totalApduCount = 0;
            energyMj = 0.02;

            long s2 = (stepMs != null && stepMs.length > 2) ? Math.max(1L, stepMs[2]) : 2L;
            long s4 = (stepMs != null && stepMs.length > 4) ? Math.max(1L, stepMs[4]) : 2L;
            totalMs = Math.max(1L, finalTotalMs);

            stageState[0] = STATE_BYPASSED;
            stageStartMs[0] = 0L;
            stageDurationMs[0] = 0L;
            stageLiveNote[0] = "Bỏ qua · 0 APDU";

            stageState[1] = STATE_BYPASSED;
            stageStartMs[1] = 0L;
            stageDurationMs[1] = 0L;
            stageLiveNote[1] = "Bỏ qua · 0 APDU";

            stageState[2] = STATE_CACHE_HIT;
            stageStartMs[2] = 0L;
            stageDurationMs[2] = s2;
            stageLiveNote[2] = "Cache AES-GCM";

            stageState[3] = STATE_BYPASSED;
            stageStartMs[3] = 0L;
            stageDurationMs[3] = 0L;
            stageLiveNote[3] = "Bỏ qua · 0 APDU";

            stageState[4] = STATE_CACHE_HIT;
            stageStartMs[4] = s2;
            stageDurationMs[4] = s4;
            stageLiveNote[4] = "Cache WebP";
        }

        long getLiveDisplayTotalMs(long nowMs) {
            if (!runInProgress || afterMode) {
                return totalMs;
            }
            long completedSum = 0L;
            for (int i = 0; i < STAGE_COUNT; i++) {
                if (stageState[i] == STATE_DONE) {
                    completedSum += stageDurationMs[i];
                }
            }
            long liveElapsed = activeStageIndex >= 0
                    ? Math.max(0L, nowMs - activeStageStartClockMs)
                    : 0L;
            return completedSum + liveElapsed;
        }

        long computeEffectiveScaleMs(long nowMs) {
            if (afterMode) {
                return Math.max(200L, lastBeforeTotalMs > 0L ? lastBeforeTotalMs : 1000L);
            }
            if (runInProgress) {
                return Math.max(3200L, getLiveDisplayTotalMs(nowMs));
            }
            return Math.max(1L, totalMs > 0L ? totalMs : 3200L);
        }

        float getStageStartRatio(int index, long nowMs) {
            if (index < 0 || index >= STAGE_COUNT) return 0f;
            int st = stageState[index];
            if (st == STATE_IDLE || st == STATE_BYPASSED) return 0f;
            long scale = computeEffectiveScaleMs(nowMs);
            return clamp01(stageStartMs[index] / (float) scale);
        }

        float getStageWidthRatio(int index, long nowMs) {
            if (index < 0 || index >= STAGE_COUNT) return 0f;
            int st = stageState[index];
            if (st == STATE_IDLE || st == STATE_BYPASSED) return 0f;
            long dur;
            if (st == STATE_RUNNING) {
                dur = (!afterMode && activeStageIndex == index)
                        ? Math.max(20L, nowMs - activeStageStartClockMs)
                        : 2L;
            } else {
                dur = Math.max(0L, stageDurationMs[index]);
            }
            long scale = computeEffectiveScaleMs(nowMs);
            return clamp01(dur / (float) scale);
        }

        int getStagePercent(int index) {
            if (index < 0 || index >= STAGE_COUNT) return 0;
            if (stageState[index] != STATE_DONE || totalMs <= 0L) return 0;
            return (int) Math.round((stageDurationMs[index] * 100.0) / totalMs);
        }

        static double computeEnergyMj(int apduCount, long elapsedMs) {
            return apduCount == 0
                    ? 0.02
                    : (apduCount * 0.038 + (elapsedMs / 1000.0) * 0.12);
        }
    }
}
