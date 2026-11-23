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

import java.util.Locale;

/**
 * Visual authentication, PBKDF2 hashing, char[] PIN zeroization & MASVS compliance flow
 * for Topic 5 (§5 Startup & Security Audit). Replaces plain-text trace logs with 3 KPI cards,
 * a 3-stage pipeline card, and a 3-step execution flow list.
 */
public class SecurityAuditFlowView extends View {

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

    private final SecurityFlowState state = new SecurityFlowState();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface sansBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Typeface sansRegular = Typeface.DEFAULT;
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final RectF cardRect = new RectF();
    private final RectF pipeRect = new RectF();
    private final RectF nodeRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF barRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Path checkPath = new Path();
    private final Path arrowPath = new Path();

    public SecurityAuditFlowView(Context context) {
        this(context, null);
    }

    public SecurityAuditFlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(COLOR_WHITE);
        setContentDescription("Biểu đồ luồng xác thực PIN, Zeroize RAM và điểm kiểm toán MASVS");
    }

    public void beginBeforeRun(int pinLen, int currentMasvs) {
        state.beginBeforeRun(SystemClock.elapsedRealtime(), pinLen, currentMasvs);
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void onBeforePbkdf2Done(int currentMasvs) {
        state.onBeforePbkdf2Done(currentMasvs);
        invalidate();
        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void finishBeforeRun(long totalMs, int pinLen, int finalMasvs) {
        state.finishBeforeRun(totalMs, pinLen, finalMasvs);
        invalidate();
    }

    public void beginAfterRun(int pinLen) {
        state.beginAfterRun(SystemClock.elapsedRealtime(), pinLen);
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void finishAfterRun(long totalMs, int remainingChars, int pinLen, int finalMasvs) {
        state.finishAfterRun(totalMs, remainingChars, pinLen, finalMasvs);
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

        // 2. 3-Stage Security Auth & Zeroize Card (156dp)
        float pipeTop = kpiH + dp(10f);
        float pipeH = dp(156f);
        drawSecurityCard(canvas, w, pipeTop, pipeH, nowMs);

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

        String timeVal;
        String expVal;
        String masvsVal;

        int timeBg = COLOR_SLATE_50;
        int timeBorder = COLOR_SLATE_200;
        int timeColor = COLOR_SLATE_900;

        int expBg = COLOR_SLATE_50;
        int expBorder = COLOR_SLATE_200;
        int expColor = COLOR_SLATE_900;

        int masvsBg = COLOR_SLATE_50;
        int masvsBorder = COLOR_SLATE_200;
        int masvsColor = COLOR_SLATE_900;

        if (!state.hasRun) {
            timeVal = "—";
            expVal = "—";
            masvsVal = "—";
        } else if (state.mode == SecurityFlowState.MODE_BEFORE) {
            long liveMs = state.getLiveDisplayMs(nowMs);
            timeVal = liveMs > 0L ? String.format(Locale.US, "%,d ms", liveMs) : "...";
            expVal = "100% (String)";
            masvsVal = state.masvsScore + "/5 Rủi ro";

            timeBg = COLOR_RED_BG;
            timeBorder = COLOR_RED_BORDER;
            timeColor = COLOR_RED;
            expBg = COLOR_RED_BG;
            expBorder = COLOR_RED_BORDER;
            expColor = COLOR_RED;
            masvsBg = COLOR_AMBER_BG;
            masvsBorder = COLOR_AMBER_BORDER;
            masvsColor = COLOR_AMBER;
        } else {
            timeVal = state.runInProgress ? "..." : (state.elapsedMs + " ms");
            expVal = state.runInProgress ? "..." : (state.exposurePct + "% (Đã xóa)");
            masvsVal = state.masvsScore + "/5 An toàn";

            timeBg = COLOR_GREEN_BG;
            timeBorder = COLOR_GREEN_BORDER;
            timeColor = COLOR_GREEN;
            boolean expClean = state.runInProgress || state.exposurePct == 0;
            expBg = expClean ? COLOR_GREEN_BG : COLOR_RED_BG;
            expBorder = expClean ? COLOR_GREEN_BORDER : COLOR_RED_BORDER;
            expColor = expClean ? COLOR_GREEN : COLOR_RED;
            boolean masvsOk = state.masvsScore == 5;
            masvsBg = masvsOk ? COLOR_GREEN_BG : COLOR_AMBER_BG;
            masvsBorder = masvsOk ? COLOR_GREEN_BORDER : COLOR_AMBER_BORDER;
            masvsColor = masvsOk ? COLOR_GREEN : COLOR_AMBER;
        }

        drawKpiCard(canvas, 0f, cardW, kpiH, "THỜI GIAN ĐĂNG NHẬP", timeVal, timeBg, timeBorder, timeColor);
        drawKpiCard(canvas, cardW + gap, cardW, kpiH, "PHƠI NHIỄM PIN", expVal, expBg, expBorder, expColor);
        drawKpiCard(canvas, (cardW + gap) * 2f, cardW, kpiH, "ĐIỂM MASVS", masvsVal, masvsBg, masvsBorder, masvsColor);
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
        textPaint.setTextSize(dp(8f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(COLOR_SLATE_500);
        canvas.drawText(label, cx, dp(17f), textPaint);

        textPaint.setTypeface(monoBold);
        textPaint.setTextSize(dp(11f));
        textPaint.setColor(valueColor);
        canvas.drawText(value, cx, dp(36f), textPaint);
    }

    private void drawSecurityCard(Canvas canvas, float w, float top, float pipeH, long nowMs) {
        int cardBg = COLOR_SLATE_50;
        int cardBorder = COLOR_SLATE_200;
        String modeText = "Chờ chạy";
        int pillBorder = COLOR_SLATE_200;
        int pillText = COLOR_SLATE_500;

        if (state.mode == SecurityFlowState.MODE_BEFORE) {
            cardBg = COLOR_RED_BG;
            cardBorder = COLOR_RED_BORDER;
            modeText = "LỘ STRING · SYNC";
            pillBorder = COLOR_RED_BORDER;
            pillText = COLOR_RED;
        } else if (state.mode == SecurityFlowState.MODE_AFTER) {
            cardBg = COLOR_GREEN_BG;
            cardBorder = COLOR_GREEN_BORDER;
            modeText = "CHAR[] ZEROIZE";
            pillBorder = COLOR_GREEN_BORDER;
            pillText = COLOR_GREEN;
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
        canvas.drawText("LUỒNG BĂM PBKDF2 & XÓA RAM PIN", padX, headerY, textPaint);

        drawInlinePill(canvas, w - padX, headerY, modeText, COLOR_WHITE, pillBorder, pillText);

        // 3 horizontal pipeline nodes: KHỞI TẠO -> BĂM PBKDF2 -> ZEROIZE RAM
        float nodesTop = top + dp(28f);
        float nodeH = dp(76f);
        float arrowGap = dp(16f);
        float innerW = w - padX * 2f;
        float nodeW = Math.max(dp(40f), (innerW - arrowGap * 2f) / 3f);

        float n1Left = padX;
        float n2Left = n1Left + nodeW + arrowGap;
        float n3Left = n2Left + nodeW + arrowGap;
        float arrowCy = nodesTop + nodeH * 0.5f;

        String initSub = state.mode == SecurityFlowState.MODE_BEFORE
                ? "Sync Init"
                : (state.mode == SecurityFlowState.MODE_AFTER ? "Lazy Init" : "Sync / Lazy");

        drawStageNode(canvas, n1Left, nodesTop, nodeW, nodeH,
                "KHỞI TẠO", initSub, state.initNodeState, state.initBadge);
        drawStageNode(canvas, n2Left, nodesTop, nodeW, nodeH,
                "BĂM PBKDF2", "10.000 vòng", state.pbkdf2NodeState, state.pbkdf2Badge);
        drawStageNode(canvas, n3Left, nodesTop, nodeW, nodeH,
                "ZEROIZE RAM", "Heap RAM", state.zeroizeNodeState, state.zeroizeBadge);

        int arrowColor = state.mode == SecurityFlowState.MODE_BEFORE
                ? COLOR_RED
                : (state.mode == SecurityFlowState.MODE_AFTER ? COLOR_GREEN : COLOR_SLATE_300);
        drawConnectorArrow(canvas, n1Left + nodeW + dp(2f), n2Left - dp(2f), arrowCy, arrowColor);
        drawConnectorArrow(canvas, n2Left + nodeW + dp(2f), n3Left - dp(2f), arrowCy, arrowColor);

        // Summary line & progress bar
        float summaryY = top + dp(122f);
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        if (state.mode == SecurityFlowState.MODE_BEFORE) {
            textPaint.setColor(COLOR_RED);
        } else if (state.mode == SecurityFlowState.MODE_AFTER) {
            textPaint.setColor(COLOR_GREEN);
        } else {
            textPaint.setColor(COLOR_SLATE_700);
        }
        String summary = state.summaryText != null ? state.summaryText : "";
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

        float ratio = state.getProgressBarRatio(nowMs);
        boolean afterPulse = state.runInProgress && state.mode == SecurityFlowState.MODE_AFTER;
        if (afterPulse) {
            ratio = 0.40f + 0.35f * (0.5f + 0.5f * (float) Math.sin(nowMs / 180.0));
        }
        if (ratio > 0f) {
            float fillW = Math.max(dp(10f), trackRect.width() * clamp01(ratio));
            barRect.set(trackRect.left, trackRect.top,
                    Math.min(trackRect.right, trackRect.left + fillW), trackRect.bottom);
            if (state.mode == SecurityFlowState.MODE_BEFORE) {
                fillPaint.setColor(COLOR_RED_BAR);
            } else {
                fillPaint.setColor(afterPulse ? COLOR_BLUE : COLOR_GREEN_BAR);
            }
            canvas.drawRoundRect(barRect, barRadius, barRadius, fillPaint);
        }
    }

    private void drawStageNode(Canvas canvas, float left, float top, float width, float height,
                               String title, String subtitle, int nodeState, String badgeText) {
        int borderColor = COLOR_SLATE_200;
        int pillBg = COLOR_SLATE_100;
        int pillBorder = COLOR_SLATE_200;
        int pillText = COLOR_SLATE_500;

        if (nodeState == SecurityFlowState.NODE_ACTIVE) {
            borderColor = COLOR_BLUE;
            pillBg = COLOR_SLATE_50;
            pillBorder = COLOR_BLUE;
            pillText = COLOR_BLUE;
        } else if (nodeState == SecurityFlowState.NODE_OK) {
            borderColor = COLOR_GREEN_BORDER;
            pillBg = COLOR_GREEN_BG;
            pillBorder = COLOR_GREEN_BORDER;
            pillText = COLOR_GREEN;
        } else if (nodeState == SecurityFlowState.NODE_FAULT) {
            borderColor = COLOR_RED_BORDER;
            pillBg = COLOR_RED_BG;
            pillBorder = COLOR_RED_BORDER;
            pillText = COLOR_RED;
        }

        nodeRect.set(left, top, left + width, top + height);
        float radius = dp(10f);
        fillPaint.setColor(COLOR_WHITE);
        canvas.drawRoundRect(nodeRect, radius, radius, fillPaint);
        strokePaint.setStrokeWidth(dp(1.2f));
        strokePaint.setColor(borderColor);
        canvas.drawRoundRect(nodeRect, radius, radius, strokePaint);

        float cx = nodeRect.centerX();
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(10f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(COLOR_SLATE_900);
        canvas.drawText(title, cx, top + dp(20f), textPaint);

        textPaint.setTypeface(sansRegular);
        textPaint.setTextSize(dp(8.5f));
        textPaint.setColor(COLOR_SLATE_500);
        canvas.drawText(subtitle, cx, top + dp(34f), textPaint);

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

    private void drawConnectorArrow(Canvas canvas, float startX, float endX, float cy, int color) {
        if (endX <= startX + dp(4f)) return;
        float tipX = endX;
        float lineEndX = endX - dp(4f);
        strokePaint.setStrokeWidth(dp(1.6f));
        strokePaint.setColor(color);
        canvas.drawLine(startX, cy, lineEndX, cy, strokePaint);

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
        float lastCy = stepsTop + (SecurityFlowState.STEP_COUNT - 1) * rowH + rowH * 0.5f;
        strokePaint.setStrokeWidth(dp(1.8f));
        strokePaint.setColor(COLOR_SLATE_200);
        canvas.drawLine(railX, firstCy, railX, lastCy, strokePaint);

        for (int i = 0; i < SecurityFlowState.STEP_COUNT; i++) {
            float rowTop = stepsTop + i * rowH;
            if (rowTop + rowH > h + dp(2f)) break;
            float cy = rowTop + rowH * 0.5f;
            int st = state.stepState[i];

            drawStepNode(canvas, railX, cy, nodeR, st, pulse);

            float baseline = cy + dp(4f);
            textPaint.setTypeface(st == SecurityFlowState.STEP_IDLE ? sansRegular : sansBold);
            textPaint.setTextSize(dp(10.5f));
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(st == SecurityFlowState.STEP_IDLE ? COLOR_SLATE_500 : COLOR_SLATE_900);
            String label = state.stepLabels[i] != null ? state.stepLabels[i] : "";
            canvas.drawText(label, textLeft, baseline, textPaint);

            String val = state.stepValues[i] != null ? state.stepValues[i] : "—";
            textPaint.setTypeface(monoBold);
            textPaint.setTextSize(dp(10f));
            textPaint.setTextAlign(Paint.Align.RIGHT);
            if (st == SecurityFlowState.STEP_DONE_OK) {
                textPaint.setColor(COLOR_GREEN);
            } else if (st == SecurityFlowState.STEP_DONE_FAULT) {
                textPaint.setColor(COLOR_RED);
            } else if (st == SecurityFlowState.STEP_RUNNING) {
                textPaint.setColor(COLOR_BLUE);
            } else {
                textPaint.setColor(COLOR_SLATE_500);
            }
            canvas.drawText(val, w, baseline, textPaint);
        }
    }

    private void drawStepNode(Canvas canvas, float cx, float cy, float r, int stepState, float pulse) {
        switch (stepState) {
            case SecurityFlowState.STEP_RUNNING:
                fillPaint.setColor(COLOR_BLUE_SOFT);
                canvas.drawCircle(cx, cy, r + dp(2f + 1.8f * pulse), fillPaint);
                fillPaint.setColor(COLOR_BLUE);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.3f), fillPaint);
                break;

            case SecurityFlowState.STEP_DONE_FAULT:
                fillPaint.setColor(COLOR_RED_BAR);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.4f), fillPaint);
                break;

            case SecurityFlowState.STEP_DONE_OK:
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
     * Pure-Java security authentication, char[] PIN zeroization & MASVS audit state for unit
     * testing without Android framework dependencies.
     */
    static final class SecurityFlowState {
        static final int MODE_IDLE = 0;
        static final int MODE_BEFORE = 1;
        static final int MODE_AFTER = 2;

        static final int NODE_IDLE = 0;
        static final int NODE_ACTIVE = 1;
        static final int NODE_OK = 2;
        static final int NODE_FAULT = 3;

        static final int STEP_COUNT = 3;
        static final int STEP_IDLE = 0;
        static final int STEP_RUNNING = 1;
        static final int STEP_DONE_FAULT = 2;
        static final int STEP_DONE_OK = 3;

        static final long BEFORE_SYNC_TARGET_MS = 1950L;

        boolean hasRun;
        int mode = MODE_IDLE;
        boolean runInProgress;
        long elapsedMs;
        long runStartClockMs;
        int pinLength = 6;
        int remainingPinChars = 6;
        int exposurePct;
        int masvsScore = 5;

        int initNodeState = NODE_IDLE;
        int pbkdf2NodeState = NODE_IDLE;
        int zeroizeNodeState = NODE_IDLE;
        String initBadge = "Chờ";
        String pbkdf2Badge = "Chờ";
        String zeroizeBadge = "Chờ";
        String summaryText = "Bấm Xác thực đăng nhập để kiểm chứng Zeroize PIN & MASVS";

        final int[] stepState = {STEP_IDLE, STEP_IDLE, STEP_IDLE};
        final String[] stepLabels = {
                "1. Khởi tạo KeyStore (Sync vs Lazy)",
                "2. Băm PBKDF2 & Xóa bộ nhớ PIN",
                "3. Kiểm toán RSA-1024 & Bảo vệ môi trường"
        };
        final String[] stepValues = {"Chờ", "Chờ", "Chờ"};

        void beginBeforeRun(long nowMs, int pinLen, int currentMasvs) {
            hasRun = true;
            mode = MODE_BEFORE;
            runInProgress = true;
            elapsedMs = 0L;
            runStartClockMs = nowMs;
            pinLength = Math.max(4, pinLen);
            remainingPinChars = pinLength;
            exposurePct = 100;
            masvsScore = Math.max(1, Math.min(5, currentMasvs));

            initNodeState = NODE_ACTIVE;
            initBadge = "Sync...";
            pbkdf2NodeState = NODE_ACTIVE;
            pbkdf2Badge = "String PIN";
            zeroizeNodeState = NODE_FAULT;
            zeroizeBadge = "100% lộ!";
            summaryText = "Đang khởi tạo KeyStore đồng bộ & băm PBKDF2 (String bất biến)...";

            stepLabels[0] = "1. Khởi tạo KeyStore đồng bộ (Sync Init)";
            stepState[0] = STEP_RUNNING;
            stepValues[0] = "Đang chờ...";

            stepLabels[1] = "2. PBKDF2 10.000 vòng (không clearPassword)";
            stepState[1] = STEP_RUNNING;
            stepValues[1] = "String 100%";

            stepLabels[2] = "3. Kiểm toán RSA-1024 & Root/Debugger";
            stepState[2] = STEP_DONE_FAULT;
            stepValues[2] = "MASVS " + masvsScore + "/5";
        }

        void onBeforePbkdf2Done(int currentMasvs) {
            masvsScore = Math.max(1, Math.min(5, currentMasvs));
            pbkdf2NodeState = NODE_FAULT;
            pbkdf2Badge = "Không xóa";
            zeroizeNodeState = NODE_FAULT;
            zeroizeBadge = "100% lộ!";
            stepState[1] = STEP_DONE_FAULT;
            stepValues[1] = "100% lộ";
            stepValues[2] = "MASVS " + masvsScore + "/5";
        }

        void finishBeforeRun(long totalMs, int pinLen, int finalMasvs) {
            hasRun = true;
            mode = MODE_BEFORE;
            runInProgress = false;
            elapsedMs = Math.max(1L, totalMs);
            pinLength = Math.max(4, pinLen);
            remainingPinChars = pinLength;
            exposurePct = 100;
            masvsScore = Math.max(1, Math.min(5, finalMasvs));

            initNodeState = NODE_FAULT;
            initBadge = elapsedMs + " ms";
            pbkdf2NodeState = NODE_FAULT;
            pbkdf2Badge = "Không xóa";
            zeroizeNodeState = NODE_FAULT;
            zeroizeBadge = "100% lộ!";
            summaryText = String.format(Locale.US,
                    "PIN còn 100%% trên Heap (%d/%d ký tự) · MASVS %d/5 · %,d ms",
                    pinLength, pinLength, masvsScore, elapsedMs);

            stepState[0] = STEP_DONE_FAULT;
            stepValues[0] = String.format(Locale.US, "%,d ms", elapsedMs);
            stepState[1] = STEP_DONE_FAULT;
            stepValues[1] = "100% lộ";
            stepState[2] = STEP_DONE_FAULT;
            stepValues[2] = "MASVS " + masvsScore + "/5";
        }

        void beginAfterRun(long nowMs, int pinLen) {
            hasRun = true;
            mode = MODE_AFTER;
            runInProgress = true;
            elapsedMs = 0L;
            runStartClockMs = nowMs;
            pinLength = Math.max(4, pinLen);
            remainingPinChars = pinLength;
            exposurePct = 100;
            masvsScore = 5;

            initNodeState = NODE_OK;
            initBadge = "Lazy Init";
            pbkdf2NodeState = NODE_ACTIVE;
            pbkdf2Badge = "Đang băm...";
            zeroizeNodeState = NODE_IDLE;
            zeroizeBadge = "Chờ xóa";
            summaryText = "Băm PBKDF2 với mảng char[] & tự động ghi đè '\\0'...";

            stepLabels[0] = "1. Bỏ Sync Init, nạp PIN vào mảng char[]";
            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "Lazy Init";

            stepLabels[1] = "2. Băm PBKDF2 & Arrays.fill(pin, '\\0')";
            stepState[1] = STEP_RUNNING;
            stepValues[1] = "Đang băm...";

            stepLabels[2] = "3. Khôi phục RSA-1024 & Bảo vệ môi trường";
            stepState[2] = STEP_IDLE;
            stepValues[2] = "Chờ";
        }

        void finishAfterRun(long totalMs, int remainingChars, int pinLen, int finalMasvs) {
            hasRun = true;
            mode = MODE_AFTER;
            runInProgress = false;
            elapsedMs = Math.max(1L, totalMs);
            pinLength = Math.max(4, pinLen);
            remainingPinChars = Math.max(0, Math.min(pinLength, remainingChars));
            exposurePct = Math.round((remainingPinChars * 100f) / pinLength);
            masvsScore = Math.max(1, Math.min(5, finalMasvs));
            int wipedCount = pinLength - remainingPinChars;

            initNodeState = NODE_OK;
            initBadge = "Lazy Init";
            pbkdf2NodeState = NODE_OK;
            pbkdf2Badge = elapsedMs + " ms";
            zeroizeNodeState = remainingPinChars == 0 ? NODE_OK : NODE_FAULT;
            zeroizeBadge = remainingPinChars + "/" + pinLength + " ký tự";
            summaryText = String.format(Locale.US,
                    "Đã xóa %d/%d ký tự PIN (%d%% lộ) · MASVS %d/5 · %d ms",
                    wipedCount, pinLength, exposurePct, masvsScore, elapsedMs);

            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "Lazy Init";
            stepState[1] = remainingPinChars == 0 ? STEP_DONE_OK : STEP_DONE_FAULT;
            stepValues[1] = remainingPinChars + "/" + pinLength + " còn";
            stepState[2] = masvsScore == 5 ? STEP_DONE_OK : STEP_DONE_FAULT;
            stepValues[2] = "MASVS " + masvsScore + "/5";
        }

        long getLiveDisplayMs(long nowMs) {
            if (runInProgress && mode == MODE_BEFORE) {
                return Math.max(elapsedMs, nowMs - runStartClockMs);
            }
            return elapsedMs;
        }

        float getProgressBarRatio(long nowMs) {
            if (mode == MODE_BEFORE) {
                return runInProgress
                        ? clamp01(getLiveDisplayMs(nowMs) / (float) BEFORE_SYNC_TARGET_MS)
                        : 1f;
            }
            if (mode == MODE_AFTER) {
                return runInProgress ? 0f : 1f;
            }
            return 0f;
        }
    }
}
