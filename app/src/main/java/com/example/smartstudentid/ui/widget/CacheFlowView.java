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

import java.util.Locale;

/**
 * Visual 2-Tier Cache & Auto-Wipe pipeline view for Topic 3 (§3 Offline-First Storage).
 * Displays open latency, APDU I/O volume, storage security posture, live L1 RAM -> L2 TEE -> Chip
 * routing nodes, and a 3-step execution flow list.
 */
public class CacheFlowView extends View {

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

    private final CacheFlowState state = new CacheFlowState();
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
    private final RectF pipeRect = new RectF();
    private final RectF nodeRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF barRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Path checkPath = new Path();
    private final Path arrowPath = new Path();

    public CacheFlowView(Context context) {
        this(context, null);
    }

    public CacheFlowView(Context context, AttributeSet attrs) {
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
        setContentDescription("Biểu đồ luồng lưu đệm 2 tầng và tự hủy bảo mật");
    }

    public void beginBeforeRun() {
        state.beginBeforeRun(SystemClock.elapsedRealtime());
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void onBeforeApduProgress(int currentApduCount) {
        state.onBeforeApduProgress(currentApduCount);
        invalidate();
    }

    public void finishBeforeRun(long totalMs, int finalApduCount) {
        state.finishBeforeRun(totalMs, finalApduCount);
        invalidate();
    }

    public void beginAfterRun() {
        state.beginAfterRun(SystemClock.elapsedRealtime());
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void finishAfterRun(String tierLabel, long totalMs) {
        state.finishAfterRun(tierLabel, totalMs);
        invalidate();
    }

    public void recordPinCheckOk() {
        state.recordPinCheckOk();
        invalidate();
    }

    public void recordWrongPinAttempt(int failedCount, int remainingCount) {
        state.recordWrongPinAttempt(failedCount, remainingCount);
        invalidate();
    }

    public void recordAutoWipeTriggered(boolean wipedOk) {
        state.recordAutoWipeTriggered(wipedOk);
        invalidate();
    }

    public void resetToEmpty() {
        state.resetToEmpty();
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
        drawKpiStrip(canvas, w, kpiH, nowMs);

        // 2. 2-Tier Routing Pipeline Card (156dp)
        float pipeTop = kpiH + dp(10f);
        float pipeH = dp(156f);
        drawRoutingPipeline(canvas, w, pipeTop, pipeH, nowMs);

        // 3. Bottom 3-step execution flow list
        float stepsTop = pipeTop + pipeH + dp(12f);
        drawExecutionSteps(canvas, w, stepsTop, h, nowMs);

        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawKpiStrip(Canvas canvas, float w, float kpiH, long nowMs) {
        float gap = dp(8f);
        float cardW = (w - gap * 2f) / 3f;

        String latencyVal;
        String ioVal;
        String secVal;

        int latBg = COLOR_SLATE_50;
        int latBorder = COLOR_SLATE_200;
        int latColor = COLOR_SLATE_900;

        int ioBg = COLOR_SLATE_50;
        int ioBorder = COLOR_SLATE_200;
        int ioColor = COLOR_SLATE_900;

        int secBg = COLOR_SLATE_50;
        int secBorder = COLOR_SLATE_200;
        int secColor = COLOR_SLATE_900;

        switch (state.mode) {
            case CacheFlowState.MODE_BEFORE:
                long liveMs = state.getLiveDisplayMs(nowMs);
                latencyVal = liveMs > 0L ? String.format(Locale.US, "%,d ms", liveMs) : "...";
                ioVal = state.apduCount + " APDU";
                secVal = "Lộ bản rõ";

                latBg = COLOR_RED_BG;
                latBorder = COLOR_RED_BORDER;
                latColor = COLOR_RED;
                ioBg = COLOR_RED_BG;
                ioBorder = COLOR_RED_BORDER;
                ioColor = COLOR_RED;
                secBg = COLOR_RED_BG;
                secBorder = COLOR_RED_BORDER;
                secColor = COLOR_RED;
                break;

            case CacheFlowState.MODE_AFTER:
                latencyVal = state.runInProgress
                        ? "..."
                        : (state.elapsedMs <= 0L ? "<1 ms" : state.elapsedMs + " ms");
                ioVal = "0 APDU";
                secVal = "AES-GCM";

                latBg = COLOR_GREEN_BG;
                latBorder = COLOR_GREEN_BORDER;
                latColor = COLOR_GREEN;
                ioBg = COLOR_GREEN_BG;
                ioBorder = COLOR_GREEN_BORDER;
                ioColor = COLOR_GREEN;
                secBg = COLOR_GREEN_BG;
                secBorder = COLOR_GREEN_BORDER;
                secColor = COLOR_GREEN;
                break;

            case CacheFlowState.MODE_PIN_WARN:
                latencyVal = "Đang giữ";
                ioVal = "0 APDU";
                secVal = "Sai " + state.failedPinAttempts + "/3";

                latBg = COLOR_AMBER_BG;
                latBorder = COLOR_AMBER_BORDER;
                latColor = COLOR_AMBER;
                ioBg = COLOR_AMBER_BG;
                ioBorder = COLOR_AMBER_BORDER;
                ioColor = COLOR_AMBER;
                secBg = COLOR_AMBER_BG;
                secBorder = COLOR_AMBER_BORDER;
                secColor = COLOR_AMBER;
                break;

            case CacheFlowState.MODE_WIPED:
                latencyVal = "<1 ms";
                ioVal = "0 APDU";
                secVal = "Đã tự hủy";

                latBg = COLOR_RED_BG;
                latBorder = COLOR_RED_BORDER;
                latColor = COLOR_RED;
                ioBg = COLOR_AMBER_BG;
                ioBorder = COLOR_AMBER_BORDER;
                ioColor = COLOR_AMBER;
                secBg = COLOR_RED_BG;
                secBorder = COLOR_RED_BORDER;
                secColor = COLOR_RED;
                break;

            default:
                latencyVal = "—";
                ioVal = "—";
                secVal = "—";
                break;
        }

        drawKpiCard(canvas, 0f, cardW, kpiH, "ĐỘ TRỄ MỞ", latencyVal, latBg, latBorder, latColor);
        drawKpiCard(canvas, cardW + gap, cardW, kpiH, "LƯU LƯỢNG I/O", ioVal, ioBg, ioBorder, ioColor);
        drawKpiCard(canvas, (cardW + gap) * 2f, cardW, kpiH, "BẢO MẬT LƯU TRỮ", secVal, secBg, secBorder, secColor);
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

    private void drawRoutingPipeline(Canvas canvas, float w, float top, float pipeH, long nowMs) {
        int cardBg = COLOR_SLATE_50;
        int cardBorder = COLOR_SLATE_200;
        String modeText = "Chờ chạy";
        int pillBg = COLOR_WHITE;
        int pillBorder = COLOR_SLATE_200;
        int pillTextColor = COLOR_SLATE_500;

        switch (state.mode) {
            case CacheFlowState.MODE_BEFORE:
                cardBg = COLOR_RED_BG;
                cardBorder = COLOR_RED_BORDER;
                modeText = "BYPASS · 36 APDU";
                pillBorder = COLOR_RED_BORDER;
                pillTextColor = COLOR_RED;
                break;
            case CacheFlowState.MODE_AFTER:
                cardBg = COLOR_GREEN_BG;
                cardBorder = COLOR_GREEN_BORDER;
                modeText = "CACHE 2 TẦNG";
                pillBorder = COLOR_GREEN_BORDER;
                pillTextColor = COLOR_GREEN;
                break;
            case CacheFlowState.MODE_PIN_WARN:
                cardBg = COLOR_AMBER_BG;
                cardBorder = COLOR_AMBER_BORDER;
                modeText = "CẢNH BÁO PIN";
                pillBorder = COLOR_AMBER_BORDER;
                pillTextColor = COLOR_AMBER;
                break;
            case CacheFlowState.MODE_WIPED:
                cardBg = COLOR_RED_BG;
                cardBorder = COLOR_RED_BORDER;
                modeText = "AUTO-WIPE 3/3";
                pillBorder = COLOR_RED_BORDER;
                pillTextColor = COLOR_RED;
                break;
            default:
                break;
        }

        pipeRect.set(0f, top, w, top + pipeH);
        float radius = dp(12f);
        fillPaint.setColor(cardBg);
        canvas.drawRoundRect(pipeRect, radius, radius, fillPaint);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(cardBorder);
        canvas.drawRoundRect(pipeRect, radius, radius, strokePaint);

        float padX = dp(10f);
        float headerY = top + dp(18f);
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(10.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(COLOR_SLATE_900);
        canvas.drawText("LUỒNG ĐỊNH TUYẾN DỮ LIỆU", padX, headerY, textPaint);

        drawInlinePill(canvas, w - padX, headerY, modeText, pillBg, pillBorder, pillTextColor);

        // 3 horizontal architecture nodes: L1 RAM -> L2 TEE -> THẺ CHIP
        float nodesTop = top + dp(28f);
        float nodeH = dp(76f);
        float arrowGap = dp(16f);
        float innerW = w - padX * 2f;
        float nodeW = Math.max(dp(40f), (innerW - arrowGap * 2f) / 3f);

        float n1Left = padX;
        float n2Left = n1Left + nodeW + arrowGap;
        float n3Left = n2Left + nodeW + arrowGap;
        float arrowCy = nodesTop + nodeH * 0.5f;

        drawTierNode(canvas, n1Left, nodesTop, nodeW, nodeH,
                "L1 RAM", "RAM <1ms", state.l1State, state.l1Badge);
        drawTierNode(canvas, n2Left, nodesTop, nodeW, nodeH,
                "L2 TEE", "AES-GCM", state.l2State, state.l2Badge);
        drawTierNode(canvas, n3Left, nodesTop, nodeW, nodeH,
                "THẺ CHIP", "ISO 7816-4", state.chipState, state.chipBadge);

        boolean beforeBypass = (state.mode == CacheFlowState.MODE_BEFORE);
        boolean afterHit = (state.mode == CacheFlowState.MODE_AFTER);
        drawConnectorArrow(canvas, n1Left + nodeW + dp(2f), n2Left - dp(2f), arrowCy,
                beforeBypass ? COLOR_RED : (afterHit ? COLOR_GREEN : COLOR_SLATE_300), beforeBypass);
        drawConnectorArrow(canvas, n2Left + nodeW + dp(2f), n3Left - dp(2f), arrowCy,
                beforeBypass ? COLOR_RED : COLOR_SLATE_300, !beforeBypass);

        // Summary text + progress bar below the 3 nodes
        float summaryY = top + dp(122f);
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        if (state.mode == CacheFlowState.MODE_BEFORE || state.mode == CacheFlowState.MODE_WIPED) {
            textPaint.setColor(COLOR_RED);
        } else if (state.mode == CacheFlowState.MODE_AFTER) {
            textPaint.setColor(COLOR_GREEN);
        } else if (state.mode == CacheFlowState.MODE_PIN_WARN) {
            textPaint.setColor(COLOR_AMBER);
        } else {
            textPaint.setColor(COLOR_SLATE_700);
        }
        String summary = state.routeSummaryText != null ? state.routeSummaryText : "";
        canvas.drawText(summary, padX, summaryY, textPaint);

        float barTop = top + dp(131f);
        float barH = dp(10f);
        float barRadius = barH * 0.5f;
        trackRect.set(padX, barTop, w - padX, barTop + barH);
        fillPaint.setColor(COLOR_WHITE);
        canvas.drawRoundRect(trackRect, barRadius, barRadius, fillPaint);
        strokePaint.setStrokeWidth(dp(0.8f));
        strokePaint.setColor(COLOR_SLATE_200);
        canvas.drawRoundRect(trackRect, barRadius, barRadius, strokePaint);

        float ratio = state.getProgressRatio();
        if (state.runInProgress && state.mode == CacheFlowState.MODE_AFTER) {
            float pulse = 0.45f + 0.35f * (0.5f + 0.5f * (float) Math.sin(nowMs / 180.0));
            ratio = pulse;
        }
        if (ratio > 0f) {
            float fillW = Math.max(dp(10f), trackRect.width() * clamp01(ratio));
            barRect.set(trackRect.left, trackRect.top,
                    Math.min(trackRect.right, trackRect.left + fillW), trackRect.bottom);
            if (state.mode == CacheFlowState.MODE_AFTER) {
                fillPaint.setColor(state.runInProgress ? COLOR_BLUE : COLOR_GREEN_BAR);
            } else if (state.mode == CacheFlowState.MODE_PIN_WARN) {
                fillPaint.setColor(COLOR_AMBER);
            } else {
                fillPaint.setColor(COLOR_RED_BAR);
            }
            canvas.drawRoundRect(barRect, barRadius, barRadius, fillPaint);
        }
    }

    private void drawTierNode(Canvas canvas, float left, float top, float width, float height,
                              String title, String subtitle, int nodeState, String badgeText) {
        int bgColor = COLOR_WHITE;
        int borderColor = COLOR_SLATE_200;
        int titleColor = COLOR_SLATE_900;
        int pillBg = COLOR_SLATE_100;
        int pillBorder = COLOR_SLATE_200;
        int pillText = COLOR_SLATE_500;
        boolean dashed = false;

        switch (nodeState) {
            case CacheFlowState.NODE_BYPASS:
                bgColor = COLOR_SLATE_50;
                borderColor = COLOR_SLATE_300;
                titleColor = COLOR_SLATE_500;
                pillBg = COLOR_SLATE_100;
                pillBorder = COLOR_SLATE_300;
                pillText = COLOR_SLATE_500;
                dashed = true;
                break;

            case CacheFlowState.NODE_ACTIVE:
                borderColor = COLOR_BLUE;
                pillBg = COLOR_SLATE_50;
                pillBorder = COLOR_BLUE;
                pillText = COLOR_BLUE;
                break;

            case CacheFlowState.NODE_HIT:
                borderColor = COLOR_GREEN_BORDER;
                pillBg = COLOR_GREEN_BG;
                pillBorder = COLOR_GREEN_BORDER;
                pillText = COLOR_GREEN;
                break;

            case CacheFlowState.NODE_FAULT:
            case CacheFlowState.NODE_WIPED:
                borderColor = COLOR_RED_BORDER;
                pillBg = COLOR_RED_BG;
                pillBorder = COLOR_RED_BORDER;
                pillText = COLOR_RED;
                break;

            default:
                break;
        }

        nodeRect.set(left, top, left + width, top + height);
        float radius = dp(10f);
        fillPaint.setColor(bgColor);
        canvas.drawRoundRect(nodeRect, radius, radius, fillPaint);

        if (dashed) {
            dashPaint.setStrokeWidth(dp(1f));
            dashPaint.setColor(borderColor);
            canvas.drawRoundRect(nodeRect, radius, radius, dashPaint);
        } else {
            strokePaint.setStrokeWidth(dp(1.2f));
            strokePaint.setColor(borderColor);
            canvas.drawRoundRect(nodeRect, radius, radius, strokePaint);
        }

        float cx = nodeRect.centerX();
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(10f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(titleColor);
        canvas.drawText(title, cx, top + dp(20f), textPaint);

        textPaint.setTypeface(sansRegular);
        textPaint.setTextSize(dp(8.5f));
        textPaint.setColor(COLOR_SLATE_500);
        canvas.drawText(subtitle, cx, top + dp(34f), textPaint);

        // Centered badge inside bottom of node
        String badge = badgeText != null ? badgeText : "—";
        pillRect.set(left + dp(6f), top + dp(45f), left + width - dp(6f), top + dp(65f));
        float pr = pillRect.height() * 0.5f;
        fillPaint.setColor(pillBg);
        canvas.drawRoundRect(pillRect, pr, pr, fillPaint);
        strokePaint.setStrokeWidth(dp(0.8f));
        strokePaint.setColor(pillBorder);
        canvas.drawRoundRect(pillRect, pr, pr, strokePaint);

        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(8.5f));
        textPaint.setColor(pillText);
        canvas.drawText(badge, pillRect.centerX(), pillRect.centerY() + dp(3f), textPaint);
    }

    private void drawConnectorArrow(Canvas canvas, float startX, float endX, float cy,
                                    int color, boolean dashed) {
        if (endX <= startX + dp(4f)) return;
        float tipX = endX;
        float lineEndX = endX - dp(4f);
        if (dashed) {
            dashPaint.setStrokeWidth(dp(1.4f));
            dashPaint.setColor(color);
            canvas.drawLine(startX, cy, lineEndX, cy, dashPaint);
        } else {
            strokePaint.setStrokeWidth(dp(1.6f));
            strokePaint.setColor(color);
            canvas.drawLine(startX, cy, lineEndX, cy, strokePaint);
        }
        arrowPath.reset();
        arrowPath.moveTo(tipX, cy);
        arrowPath.lineTo(tipX - dp(5f), cy - dp(3.5f));
        arrowPath.lineTo(tipX - dp(5f), cy + dp(3.5f));
        arrowPath.close();
        fillPaint.setColor(color);
        canvas.drawPath(arrowPath, fillPaint);
    }

    private void drawExecutionSteps(Canvas canvas, float w, float stepsTop, float h, long nowMs) {
        float rowH = dp(35f);
        float railX = dp(10f);
        float textLeft = dp(26f);
        float nodeR = dp(7f);
        float pulse = 0.5f + 0.5f * (float) Math.sin(nowMs / 160.0);

        float firstCy = stepsTop + rowH * 0.5f;
        float lastCy = stepsTop + (CacheFlowState.STEP_COUNT - 1) * rowH + rowH * 0.5f;
        strokePaint.setStrokeWidth(dp(1.8f));
        strokePaint.setColor(COLOR_SLATE_200);
        canvas.drawLine(railX, firstCy, railX, lastCy, strokePaint);

        for (int i = 0; i < CacheFlowState.STEP_COUNT; i++) {
            float rowTop = stepsTop + i * rowH;
            if (rowTop + rowH > h + dp(2f)) break;
            float cy = rowTop + rowH * 0.5f;
            int st = state.stepState[i];

            drawStepNode(canvas, railX, cy, nodeR, st, pulse);

            float baseline = cy + dp(4f);
            textPaint.setTypeface(st == CacheFlowState.STEP_IDLE ? sansRegular : sansBold);
            textPaint.setTextSize(dp(10.5f));
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(st == CacheFlowState.STEP_IDLE ? COLOR_SLATE_500 : COLOR_SLATE_900);
            String label = state.stepLabels[i] != null ? state.stepLabels[i] : "";
            canvas.drawText(label, textLeft, baseline, textPaint);

            String val = state.stepValues[i] != null ? state.stepValues[i] : "—";
            textPaint.setTypeface(monoBold);
            textPaint.setTextSize(dp(10f));
            textPaint.setTextAlign(Paint.Align.RIGHT);
            if (st == CacheFlowState.STEP_DONE_OK) {
                textPaint.setColor(COLOR_GREEN);
            } else if (st == CacheFlowState.STEP_DONE_FAULT) {
                textPaint.setColor(COLOR_RED);
            } else if (st == CacheFlowState.STEP_RUNNING) {
                textPaint.setColor(COLOR_BLUE);
            } else {
                textPaint.setColor(COLOR_SLATE_500);
            }
            canvas.drawText(val, w, baseline, textPaint);
        }
    }

    private void drawStepNode(Canvas canvas, float cx, float cy, float r, int stepState, float pulse) {
        switch (stepState) {
            case CacheFlowState.STEP_RUNNING:
                fillPaint.setColor(COLOR_BLUE_SOFT);
                canvas.drawCircle(cx, cy, r + dp(2f + 1.8f * pulse), fillPaint);
                fillPaint.setColor(COLOR_BLUE);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.3f), fillPaint);
                break;

            case CacheFlowState.STEP_DONE_FAULT:
                fillPaint.setColor(COLOR_RED_BAR);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.4f), fillPaint);
                break;

            case CacheFlowState.STEP_DONE_OK:
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
     * Pure-Java 2-tier cache & Auto-Wipe pipeline state for unit testing without Android dependencies.
     */
    static final class CacheFlowState {
        static final int MODE_IDLE = 0;
        static final int MODE_BEFORE = 1;
        static final int MODE_AFTER = 2;
        static final int MODE_PIN_WARN = 3;
        static final int MODE_WIPED = 4;

        static final int NODE_IDLE = 0;
        static final int NODE_BYPASS = 1;
        static final int NODE_ACTIVE = 2;
        static final int NODE_HIT = 3;
        static final int NODE_FAULT = 4;
        static final int NODE_WIPED = 5;

        static final int STEP_COUNT = 3;
        static final int STEP_IDLE = 0;
        static final int STEP_RUNNING = 1;
        static final int STEP_DONE_FAULT = 2;
        static final int STEP_DONE_OK = 3;

        static final int TARGET_BEFORE_APDUS = 36;

        boolean hasRun;
        int mode = MODE_IDLE;
        boolean runInProgress;
        long elapsedMs;
        long runStartClockMs;
        int apduCount;
        String hitTierLabel = "TRỐNG";
        boolean plaintextExposed;
        int failedPinAttempts;
        int remainingPinAttempts = 3;

        int l1State = NODE_IDLE;
        int l2State = NODE_IDLE;
        int chipState = NODE_IDLE;
        String l1Badge = "Trống";
        String l2Badge = "Trống";
        String chipBadge = "0 APDU";
        String routeSummaryText = "Bấm Mở hồ sơ (Chưa tối ưu / Đã tối ưu) để xem luồng Cache";

        final int[] stepState = {STEP_IDLE, STEP_IDLE, STEP_IDLE};
        final String[] stepLabels = {
                "1. Kiểm tra bản rõ lưu trữ",
                "2. Định tuyến Cache 2 tầng (L1 → L2)",
                "3. Lưu lượng APDU & Bảo vệ Auto-Wipe"
        };
        final String[] stepValues = {"Chờ", "Chờ", "Chờ"};

        void beginBeforeRun(long nowMs) {
            hasRun = true;
            mode = MODE_BEFORE;
            runInProgress = true;
            elapsedMs = 0L;
            runStartClockMs = nowMs;
            apduCount = 0;
            hitTierLabel = "THẺ";
            plaintextExposed = true;

            l1State = NODE_BYPASS;
            l1Badge = "Bỏ qua";
            l2State = NODE_FAULT;
            l2Badge = "Lộ bản rõ!";
            chipState = NODE_ACTIVE;
            chipBadge = "0/36 APDU";
            routeSummaryText = "Đang bỏ qua Cache, đọc trực tiếp 36 APDU...";

            stepLabels[0] = "1. Ghi bản rõ SharedPreferences";
            stepState[0] = STEP_DONE_FAULT;
            stepValues[0] = "Lộ PIN!";

            stepLabels[1] = "2. Bỏ qua L1 RAM & L2 TEE";
            stepState[1] = STEP_DONE_FAULT;
            stepValues[1] = "Bypass";

            stepLabels[2] = "3. Đọc trực tiếp 36 APDU từ thẻ";
            stepState[2] = STEP_RUNNING;
            stepValues[2] = "0/36 gói";
        }

        void onBeforeApduProgress(int currentApduCount) {
            apduCount = Math.max(0, currentApduCount);
            chipBadge = apduCount + "/36 APDU";
            routeSummaryText = "Đọc trực tiếp thẻ: " + apduCount + "/36 Short APDU";
            stepValues[2] = apduCount + "/36 gói";
        }

        void finishBeforeRun(long totalMs, int finalApduCount) {
            hasRun = true;
            mode = MODE_BEFORE;
            runInProgress = false;
            elapsedMs = Math.max(1L, totalMs);
            apduCount = Math.max(1, finalApduCount);
            hitTierLabel = "THẺ";
            plaintextExposed = true;

            chipState = NODE_FAULT;
            chipBadge = apduCount + " APDU";
            routeSummaryText = "Hoàn tất: " + apduCount + " APDU trực tiếp (Chậm & lộ bản rõ)";

            stepState[2] = STEP_DONE_FAULT;
            stepValues[2] = apduCount + " APDU";
        }

        void beginAfterRun(long nowMs) {
            hasRun = true;
            mode = MODE_AFTER;
            runInProgress = true;
            elapsedMs = 0L;
            runStartClockMs = nowMs;
            apduCount = 0;
            plaintextExposed = false;

            l1State = NODE_ACTIVE;
            l1Badge = "Kiểm tra...";
            l2State = NODE_ACTIVE;
            l2Badge = "AES-GCM";
            chipState = NODE_IDLE;
            chipBadge = "0 APDU";
            routeSummaryText = "Đang đọc tầng L1 RAM → L2 AndroidKeyStore TEE...";

            stepLabels[0] = "1. Xóa sạch bản rõ (insecure_plaintext_leak)";
            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "0 byte";

            stepLabels[1] = "2. Đọc Cache 2 tầng (L1 RAM → L2 TEE)";
            stepState[1] = STEP_RUNNING;
            stepValues[1] = "Đang đọc...";

            stepLabels[2] = "3. Triệt tiêu I/O thẻ & Auto-Wipe 3x";
            stepState[2] = STEP_IDLE;
            stepValues[2] = "Chờ";
        }

        void finishAfterRun(String tierLabel, long totalMs) {
            hasRun = true;
            mode = MODE_AFTER;
            runInProgress = false;
            elapsedMs = Math.max(1L, totalMs);
            apduCount = 0;
            plaintextExposed = false;
            hitTierLabel = tierLabel != null ? tierLabel : "L2 TEE";

            if ("L1 RAM".equals(hitTierLabel)) {
                l1State = NODE_HIT;
                l1Badge = "HIT <1 ms";
                l2State = NODE_HIT;
                l2Badge = "AES-GCM";
            } else {
                l1State = NODE_HIT;
                l1Badge = "Nạp L1";
                l2State = NODE_HIT;
                l2Badge = "HIT TEE";
            }
            chipState = NODE_HIT;
            chipBadge = "0 APDU";
            routeSummaryText = "Hit " + hitTierLabel + " — 0 APDU xuống thẻ (Giảm 100% I/O)";

            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "0 byte";
            stepState[1] = STEP_DONE_OK;
            stepValues[1] = "Hit " + hitTierLabel;
            stepState[2] = STEP_DONE_OK;
            stepValues[2] = "0 APDU";
        }

        void recordPinCheckOk() {
            hasRun = true;
            mode = MODE_AFTER;
            runInProgress = false;
            failedPinAttempts = 0;
            remainingPinAttempts = 3;
            plaintextExposed = false;

            l1State = NODE_HIT;
            l1Badge = "An toàn";
            l2State = NODE_HIT;
            l2Badge = "AES-GCM";
            chipState = NODE_HIT;
            chipBadge = "0 APDU";
            routeSummaryText = "Đúng PIN — Giữ nguyên L1 RAM + L2 TEE (3/3 lần thử)";

            stepLabels[0] = "1. Xác thực mã PIN cục bộ";
            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "Đúng PIN";

            stepLabels[1] = "2. Bảo vệ giới hạn 3 lần sai PIN";
            stepState[1] = STEP_DONE_OK;
            stepValues[1] = "Còn 3/3";

            stepLabels[2] = "3. Trạng thái L1 RAM + L2 TEE";
            stepState[2] = STEP_DONE_OK;
            stepValues[2] = "An toàn";
        }

        void recordWrongPinAttempt(int failedCount, int remainingCount) {
            hasRun = true;
            mode = MODE_PIN_WARN;
            runInProgress = false;
            failedPinAttempts = failedCount;
            remainingPinAttempts = remainingCount;
            apduCount = 0;

            l1State = NODE_ACTIVE;
            l1Badge = "Cảnh báo";
            l2State = NODE_ACTIVE;
            l2Badge = "Còn " + remainingCount + "/3";
            chipState = NODE_IDLE;
            chipBadge = "0 APDU";
            routeSummaryText = "Sai PIN lần " + failedCount + "/3 — Còn " + remainingCount + " lần trước khi tự hủy";

            stepLabels[0] = "1. Xác thực mã PIN cục bộ";
            stepState[0] = STEP_DONE_FAULT;
            stepValues[0] = "Sai " + failedCount + "/3";

            stepLabels[1] = "2. Bảo vệ giới hạn 3 lần sai PIN";
            stepState[1] = STEP_RUNNING;
            stepValues[1] = "Còn " + remainingCount + "/3";

            stepLabels[2] = "3. Trạng thái L1 RAM + L2 TEE";
            stepState[2] = STEP_IDLE;
            stepValues[2] = "Chưa xóa";
        }

        void recordAutoWipeTriggered(boolean wipedOk) {
            hasRun = true;
            mode = MODE_WIPED;
            runInProgress = false;
            elapsedMs = 1L;
            apduCount = 0;
            plaintextExposed = false;
            failedPinAttempts = 3;
            remainingPinAttempts = 0;

            l1State = NODE_WIPED;
            l1Badge = "ĐÃ XÓA 0B";
            l2State = NODE_WIPED;
            l2Badge = "ĐÃ HỦY";
            chipState = NODE_IDLE;
            chipBadge = "Quẹt lại";
            routeSummaryText = "Sai PIN 3/3 — Auto-Wipe xóa sạch L1 RAM + L2 TEE";

            stepLabels[0] = "1. Nhập sai mã PIN lần 3/3";
            stepState[0] = STEP_DONE_FAULT;
            stepValues[0] = "Vượt ngưỡng";

            stepLabels[1] = "2. Kích hoạt Auto-Wipe bảo mật";
            stepState[1] = STEP_DONE_OK;
            stepValues[1] = wipedOk ? "Đã xóa" : "Đang xử lý";

            stepLabels[2] = "3. Trạng thái dữ liệu trên máy";
            stepState[2] = STEP_DONE_OK;
            stepValues[2] = "0 byte";
        }

        void resetToEmpty() {
            hasRun = false;
            mode = MODE_IDLE;
            runInProgress = false;
            elapsedMs = 0L;
            apduCount = 0;
            hitTierLabel = "TRỐNG";
            plaintextExposed = false;
            failedPinAttempts = 0;
            remainingPinAttempts = 3;

            l1State = NODE_IDLE;
            l2State = NODE_IDLE;
            chipState = NODE_IDLE;
            l1Badge = "Trống";
            l2Badge = "Trống";
            chipBadge = "0 APDU";
            routeSummaryText = "L1 RAM: Trống · L2 TEE: Trống (Đã xóa bộ nhớ đệm)";

            stepLabels[0] = "1. Kiểm tra bản rõ lưu trữ";
            stepLabels[1] = "2. Định tuyến Cache 2 tầng (L1 → L2)";
            stepLabels[2] = "3. Lưu lượng APDU & Bảo vệ Auto-Wipe";
            stepState[0] = STEP_IDLE;
            stepState[1] = STEP_IDLE;
            stepState[2] = STEP_IDLE;
            stepValues[0] = "Chờ";
            stepValues[1] = "Chờ";
            stepValues[2] = "Chờ";
        }

        float getProgressRatio() {
            if (mode == MODE_BEFORE) {
                return clamp01(apduCount / (float) TARGET_BEFORE_APDUS);
            }
            if (mode == MODE_AFTER || mode == MODE_WIPED) {
                return 1f;
            }
            if (mode == MODE_PIN_WARN) {
                return clamp01(failedPinAttempts / 3f);
            }
            return 0f;
        }

        long getLiveDisplayMs(long nowMs) {
            if (runInProgress && mode == MODE_BEFORE) {
                return Math.max(elapsedMs, nowMs - runStartClockMs);
            }
            return elapsedMs;
        }
    }
}
