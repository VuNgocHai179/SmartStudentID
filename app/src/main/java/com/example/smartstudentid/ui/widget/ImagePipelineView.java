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
 * Visual 3-stage image optimization & 8 KB card quota pipeline for Topic 4 (§4 Image & Memory).
 * Shows image payload size, 240-byte APDU packet count, RAM vs. GPU VRAM usage, decode/compress/
 * EEPROM pipeline nodes, and a 3-step execution flow list.
 */
public class ImagePipelineView extends View {

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

    private final ImagePipelineState state = new ImagePipelineState();
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

    public ImagePipelineView(Context context) {
        this(context, null);
    }

    public ImagePipelineView(Context context, AttributeSet attrs) {
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
        setContentDescription("Biểu đồ luồng tối ưu ảnh, bộ đệm 8 KB và GPU VRAM");
    }

    public void beginBeforeRun(double pinnedHeapMb) {
        state.beginBeforeRun(pinnedHeapMb);
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void onBeforeDecoded(String dims, double bitmapRamMb, double pngKb, int pngPackets) {
        state.onBeforeDecoded(dims, bitmapRamMb, pngKb, pngPackets);
        invalidate();
        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void onBeforeChurnProgress(int allocatedCount) {
        state.onBeforeChurnProgress(allocatedCount);
        invalidate();
    }

    public void finishBeforeRun(String dims, double pngKb, int pngPackets,
                                double bitmapRamMb, long gcCount, long gcMs) {
        state.finishBeforeRun(dims, pngKb, pngPackets, bitmapRamMb, gcCount, gcMs);
        invalidate();
    }

    public void beginAfterRun() {
        state.beginAfterRun();
        invalidate();
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void onAfterCompressed(int sampleSize, double webpKb, int reuses) {
        state.onAfterCompressed(sampleSize, webpKb, reuses);
        invalidate();
        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    public void finishAfterRun(int sampleSize, double webpKb, int uploadPackets,
                               double displayRamMb, boolean gpuVram, int reuses,
                               long gcCount, long gcMs, boolean writeOk) {
        state.finishAfterRun(sampleSize, webpKb, uploadPackets, displayRamMb,
                gpuVram, reuses, gcCount, gcMs, writeOk);
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

        // 2. 3-Stage Image Compression & 8 KB Quota Card (156dp)
        float pipeTop = kpiH + dp(10f);
        float pipeH = dp(156f);
        drawPipelineCard(canvas, w, pipeTop, pipeH, nowMs);

        // 3. Bottom 3-step execution flow list
        float stepsTop = pipeTop + pipeH + dp(12f);
        drawExecutionSteps(canvas, w, stepsTop, h, nowMs);

        if (state.runInProgress && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawKpiStrip(Canvas canvas, float w, float kpiH) {
        float gap = dp(8f);
        float cardW = (w - gap * 2f) / 3f;

        String sizeVal;
        String packetsVal;
        String memVal;

        int sizeBg = COLOR_SLATE_50;
        int sizeBorder = COLOR_SLATE_200;
        int sizeColor = COLOR_SLATE_900;

        int pktBg = COLOR_SLATE_50;
        int pktBorder = COLOR_SLATE_200;
        int pktColor = COLOR_SLATE_900;

        int memBg = COLOR_SLATE_50;
        int memBorder = COLOR_SLATE_200;
        int memColor = COLOR_SLATE_900;

        if (!state.hasRun) {
            sizeVal = "—";
            packetsVal = "—";
            memVal = "—";
        } else if (state.mode == ImagePipelineState.MODE_BEFORE) {
            sizeVal = state.sizeKb > 0 ? String.format(Locale.US, "%.0f KB", state.sizeKb) : "...";
            packetsVal = state.packets > 0 ? state.packets + " gói" : "...";
            memVal = state.ramMb > 0 ? String.format(Locale.US, "%.1f MB RAM", state.ramMb) : "...";

            sizeBg = COLOR_RED_BG;
            sizeBorder = COLOR_RED_BORDER;
            sizeColor = COLOR_RED;
            pktBg = COLOR_RED_BG;
            pktBorder = COLOR_RED_BORDER;
            pktColor = COLOR_RED;
            memBg = COLOR_AMBER_BG;
            memBorder = COLOR_AMBER_BORDER;
            memColor = COLOR_AMBER;
        } else {
            sizeVal = state.sizeKb > 0 ? String.format(Locale.US, "%.1f KB", state.sizeKb) : "...";
            packetsVal = state.runInProgress ? "..." : (state.packets + " gói");
            memVal = state.runInProgress
                    ? "..."
                    : (state.inGpuVram ? "GPU VRAM" : String.format(Locale.US, "%.2f MB", state.ramMb));

            sizeBg = COLOR_GREEN_BG;
            sizeBorder = COLOR_GREEN_BORDER;
            sizeColor = COLOR_GREEN;
            pktBg = COLOR_GREEN_BG;
            pktBorder = COLOR_GREEN_BORDER;
            pktColor = COLOR_GREEN;
            memBg = COLOR_GREEN_BG;
            memBorder = COLOR_GREEN_BORDER;
            memColor = COLOR_GREEN;
        }

        drawKpiCard(canvas, 0f, cardW, kpiH, "DUNG LƯỢNG ẢNH", sizeVal, sizeBg, sizeBorder, sizeColor);
        drawKpiCard(canvas, cardW + gap, cardW, kpiH, "SỐ GÓI 240B", packetsVal, pktBg, pktBorder, pktColor);
        drawKpiCard(canvas, (cardW + gap) * 2f, cardW, kpiH, "BỘ NHỚ / VRAM", memVal, memBg, memBorder, memColor);
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
        textPaint.setTextSize(dp(11.5f));
        textPaint.setColor(valueColor);
        canvas.drawText(value, cx, dp(36f), textPaint);
    }

    private void drawPipelineCard(Canvas canvas, float w, float top, float pipeH, long nowMs) {
        int cardBg = COLOR_SLATE_50;
        int cardBorder = COLOR_SLATE_200;
        String modeText = "Chờ chạy";
        int pillBorder = COLOR_SLATE_200;
        int pillText = COLOR_SLATE_500;

        if (state.mode == ImagePipelineState.MODE_BEFORE) {
            cardBg = COLOR_RED_BG;
            cardBorder = COLOR_RED_BORDER;
            modeText = "TRÀN 8 KB · CHURN";
            pillBorder = COLOR_RED_BORDER;
            pillText = COLOR_RED;
        } else if (state.mode == ImagePipelineState.MODE_AFTER) {
            cardBg = COLOR_GREEN_BG;
            cardBorder = COLOR_GREEN_BORDER;
            modeText = "WEBP + GPU VRAM";
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
        canvas.drawText("LUỒNG NÉN ẢNH & GIỚI HẠN 8 KB", padX, headerY, textPaint);

        drawInlinePill(canvas, w - padX, headerY, modeText, COLOR_WHITE, pillBorder, pillText);

        // 3 horizontal pipeline nodes: GIẢI MÃ -> NÉN & ĐỆM -> THẺ 8 KB
        float nodesTop = top + dp(28f);
        float nodeH = dp(76f);
        float arrowGap = dp(16f);
        float innerW = w - padX * 2f;
        float nodeW = Math.max(dp(40f), (innerW - arrowGap * 2f) / 3f);

        float n1Left = padX;
        float n2Left = n1Left + nodeW + arrowGap;
        float n3Left = n2Left + nodeW + arrowGap;
        float arrowCy = nodesTop + nodeH * 0.5f;

        String decodeSub = state.mode == ImagePipelineState.MODE_BEFORE
                ? "Full ARGB"
                : (state.mode == ImagePipelineState.MODE_AFTER ? "Two-pass" : "Full / 2-pass");
        String compressSub = state.mode == ImagePipelineState.MODE_BEFORE
                ? "PNG Unpooled"
                : (state.mode == ImagePipelineState.MODE_AFTER ? "WebP + 8KB" : "PNG / WebP");

        drawStageNode(canvas, n1Left, nodesTop, nodeW, nodeH,
                "GIẢI MÃ", decodeSub, state.decodeNodeState, state.decodeBadge);
        drawStageNode(canvas, n2Left, nodesTop, nodeW, nodeH,
                "NÉN & ĐỆM", compressSub, state.compressNodeState, state.compressBadge);
        drawStageNode(canvas, n3Left, nodesTop, nodeW, nodeH,
                "THẺ 8 KB", "EEPROM 8KB", state.chipNodeState, state.chipBadge);

        int arrowColor = state.mode == ImagePipelineState.MODE_BEFORE
                ? COLOR_RED
                : (state.mode == ImagePipelineState.MODE_AFTER ? COLOR_GREEN : COLOR_SLATE_300);
        drawConnectorArrow(canvas, n1Left + nodeW + dp(2f), n2Left - dp(2f), arrowCy, arrowColor, false);
        drawConnectorArrow(canvas, n2Left + nodeW + dp(2f), n3Left - dp(2f), arrowCy, arrowColor,
                state.mode == ImagePipelineState.MODE_BEFORE && !state.runInProgress);

        // Summary line & 8 KB quota progress bar
        float summaryY = top + dp(122f);
        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        if (state.mode == ImagePipelineState.MODE_BEFORE) {
            textPaint.setColor(COLOR_RED);
        } else if (state.mode == ImagePipelineState.MODE_AFTER) {
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

        float ratio = state.getCapacityBarRatio();
        boolean afterPulse = state.runInProgress && state.mode == ImagePipelineState.MODE_AFTER && ratio <= 0f;
        if (afterPulse) {
            ratio = 0.35f + 0.35f * (0.5f + 0.5f * (float) Math.sin(nowMs / 180.0));
        }
        if (ratio > 0f) {
            float fillW = Math.max(dp(10f), trackRect.width() * clamp01(ratio));
            barRect.set(trackRect.left, trackRect.top,
                    Math.min(trackRect.right, trackRect.left + fillW), trackRect.bottom);
            if (state.mode == ImagePipelineState.MODE_BEFORE) {
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

        if (nodeState == ImagePipelineState.NODE_ACTIVE) {
            borderColor = COLOR_BLUE;
            pillBg = COLOR_SLATE_50;
            pillBorder = COLOR_BLUE;
            pillText = COLOR_BLUE;
        } else if (nodeState == ImagePipelineState.NODE_OK) {
            borderColor = COLOR_GREEN_BORDER;
            pillBg = COLOR_GREEN_BG;
            pillBorder = COLOR_GREEN_BORDER;
            pillText = COLOR_GREEN;
        } else if (nodeState == ImagePipelineState.NODE_FAULT) {
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
        float lastCy = stepsTop + (ImagePipelineState.STEP_COUNT - 1) * rowH + rowH * 0.5f;
        strokePaint.setStrokeWidth(dp(1.8f));
        strokePaint.setColor(COLOR_SLATE_200);
        canvas.drawLine(railX, firstCy, railX, lastCy, strokePaint);

        for (int i = 0; i < ImagePipelineState.STEP_COUNT; i++) {
            float rowTop = stepsTop + i * rowH;
            if (rowTop + rowH > h + dp(2f)) break;
            float cy = rowTop + rowH * 0.5f;
            int st = state.stepState[i];

            drawStepNode(canvas, railX, cy, nodeR, st, pulse);

            float baseline = cy + dp(4f);
            textPaint.setTypeface(st == ImagePipelineState.STEP_IDLE ? sansRegular : sansBold);
            textPaint.setTextSize(dp(10.5f));
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(st == ImagePipelineState.STEP_IDLE ? COLOR_SLATE_500 : COLOR_SLATE_900);
            String label = state.stepLabels[i] != null ? state.stepLabels[i] : "";
            canvas.drawText(label, textLeft, baseline, textPaint);

            String val = state.stepValues[i] != null ? state.stepValues[i] : "—";
            textPaint.setTypeface(monoBold);
            textPaint.setTextSize(dp(10f));
            textPaint.setTextAlign(Paint.Align.RIGHT);
            if (st == ImagePipelineState.STEP_DONE_OK) {
                textPaint.setColor(COLOR_GREEN);
            } else if (st == ImagePipelineState.STEP_DONE_FAULT) {
                textPaint.setColor(COLOR_RED);
            } else if (st == ImagePipelineState.STEP_RUNNING) {
                textPaint.setColor(COLOR_BLUE);
            } else {
                textPaint.setColor(COLOR_SLATE_500);
            }
            canvas.drawText(val, w, baseline, textPaint);
        }
    }

    private void drawStepNode(Canvas canvas, float cx, float cy, float r, int stepState, float pulse) {
        switch (stepState) {
            case ImagePipelineState.STEP_RUNNING:
                fillPaint.setColor(COLOR_BLUE_SOFT);
                canvas.drawCircle(cx, cy, r + dp(2f + 1.8f * pulse), fillPaint);
                fillPaint.setColor(COLOR_BLUE);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.3f), fillPaint);
                break;

            case ImagePipelineState.STEP_DONE_FAULT:
                fillPaint.setColor(COLOR_RED_BAR);
                canvas.drawCircle(cx, cy, r, fillPaint);
                fillPaint.setColor(COLOR_WHITE);
                canvas.drawCircle(cx, cy, dp(2.4f), fillPaint);
                break;

            case ImagePipelineState.STEP_DONE_OK:
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
     * Pure-Java image compression & 8 KB card quota pipeline state for unit testing without
     * Android framework dependencies.
     */
    static final class ImagePipelineState {
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

        static final double CARD_LIMIT_KB = 8.0;
        static final int BEFORE_CHURN_TOTAL = 48;

        boolean hasRun;
        int mode = MODE_IDLE;
        boolean runInProgress;
        double sizeKb;
        int packets;
        double ramMb;
        boolean inGpuVram;
        String sourceDims = "1200×1200";
        int inSampleSize = 1;
        int bufferReuses;
        int churnAllocatedCount;
        long deltaGcCount;
        long deltaGcMs;
        boolean cardWriteOk;

        int decodeNodeState = NODE_IDLE;
        int compressNodeState = NODE_IDLE;
        int chipNodeState = NODE_IDLE;
        String decodeBadge = "Chờ";
        String compressBadge = "Chờ";
        String chipBadge = "Trần 8 KB";
        String summaryText = "Bấm Lưu ảnh lên thẻ chip để xem luồng xử lý ảnh";

        final int[] stepState = {STEP_IDLE, STEP_IDLE, STEP_IDLE};
        final String[] stepLabels = {
                "1. Giải mã ảnh (Full vs Two-pass)",
                "2. Nén ảnh & Cấp phát bộ đệm 8 KB",
                "3. Ghi thẻ chip (trần 8 KB) & Hiển thị"
        };
        final String[] stepValues = {"Chờ", "Chờ", "Chờ"};

        void beginBeforeRun(double pinnedHeapMb) {
            hasRun = true;
            mode = MODE_BEFORE;
            runInProgress = true;
            sizeKb = 0.0;
            packets = 0;
            ramMb = pinnedHeapMb;
            inGpuVram = false;
            churnAllocatedCount = 0;
            deltaGcCount = 0L;
            deltaGcMs = 0L;
            cardWriteOk = false;

            decodeNodeState = NODE_ACTIVE;
            decodeBadge = "Full ARGB";
            compressNodeState = NODE_IDLE;
            compressBadge = "Chờ PNG";
            chipNodeState = NODE_IDLE;
            chipBadge = "Trần 8 KB";
            summaryText = "Đang giải mã Full Bitmap ARGB_8888 trên RAM thường...";

            stepLabels[0] = "1. Ghim Heap & giải mã Full ARGB_8888";
            stepState[0] = STEP_RUNNING;
            stepValues[0] = "Đang giải mã";

            stepLabels[1] = "2. Nén PNG & cấp phát rác byte[240]";
            stepState[1] = STEP_IDLE;
            stepValues[1] = "0/" + BEFORE_CHURN_TOTAL + " mảng";

            stepLabels[2] = "3. Ghi thẻ chip (trần 8 KB) & ART GC";
            stepState[2] = STEP_IDLE;
            stepValues[2] = "Chờ";
        }

        void onBeforeDecoded(String dims, double bitmapRamMb, double pngKb, int pngPackets) {
            sourceDims = dims;
            ramMb = bitmapRamMb;
            sizeKb = pngKb;
            packets = pngPackets;

            decodeNodeState = NODE_FAULT;
            decodeBadge = dims != null ? dims : "Full ARGB";
            compressNodeState = NODE_ACTIVE;
            compressBadge = "0/" + BEFORE_CHURN_TOTAL + " rác";
            chipNodeState = NODE_FAULT;
            chipBadge = "> 8 KB";
            summaryText = String.format(Locale.US,
                    "PNG %.0f KB (%d gói) — vượt trần 8 KB, đang gây Churn...", pngKb, pngPackets);

            stepState[0] = STEP_DONE_FAULT;
            stepValues[0] = String.format(Locale.US, "%.1f MB", bitmapRamMb);
            stepState[1] = STEP_RUNNING;
            stepValues[1] = "0/" + BEFORE_CHURN_TOTAL + " mảng";
        }

        void onBeforeChurnProgress(int allocatedCount) {
            churnAllocatedCount = Math.max(0, Math.min(BEFORE_CHURN_TOTAL, allocatedCount));
            compressBadge = churnAllocatedCount + "/" + BEFORE_CHURN_TOTAL + " rác";
            summaryText = "Cấp phát unpooled byte[240]: " + churnAllocatedCount
                    + "/" + BEFORE_CHURN_TOTAL + " mảng (GC Churn)...";
            stepValues[1] = churnAllocatedCount + "/" + BEFORE_CHURN_TOTAL + " mảng";
        }

        void finishBeforeRun(String dims, double pngKb, int pngPackets,
                             double bitmapRamMb, long gcCount, long gcMs) {
            hasRun = true;
            mode = MODE_BEFORE;
            runInProgress = false;
            sourceDims = dims;
            sizeKb = pngKb;
            packets = pngPackets;
            ramMb = bitmapRamMb;
            inGpuVram = false;
            churnAllocatedCount = BEFORE_CHURN_TOTAL;
            deltaGcCount = gcCount;
            deltaGcMs = gcMs;
            cardWriteOk = false;

            decodeNodeState = NODE_FAULT;
            decodeBadge = dims != null ? dims : "Full ARGB";
            compressNodeState = NODE_FAULT;
            compressBadge = BEFORE_CHURN_TOTAL + "+ mảng rác";
            chipNodeState = NODE_FAULT;
            chipBadge = "Từ chối";
            summaryText = String.format(Locale.US,
                    "PNG %.0f KB (%d gói) — Tràn 8 KB · ART GC +%d (%d ms)",
                    pngKb, pngPackets, gcCount, gcMs);

            stepState[0] = STEP_DONE_FAULT;
            stepValues[0] = String.format(Locale.US, "%.1f MB", bitmapRamMb);
            stepState[1] = STEP_DONE_FAULT;
            stepValues[1] = String.format(Locale.US, "%.0f KB", pngKb);
            stepState[2] = STEP_DONE_FAULT;
            stepValues[2] = "Tràn 8 KB";
        }

        void beginAfterRun() {
            hasRun = true;
            mode = MODE_AFTER;
            runInProgress = true;
            sizeKb = 0.0;
            packets = 0;
            ramMb = 0.0;
            inGpuVram = false;
            inSampleSize = 1;
            bufferReuses = 0;
            deltaGcCount = 0L;
            deltaGcMs = 0L;
            cardWriteOk = false;

            decodeNodeState = NODE_ACTIVE;
            decodeBadge = "Two-pass";
            compressNodeState = NODE_IDLE;
            compressBadge = "Buffer 8KB";
            chipNodeState = NODE_IDLE;
            chipBadge = "Chờ ghi";
            summaryText = "Dọn Heap Churn & giải mã Two-pass (inSampleSize)...";

            stepLabels[0] = "1. Dừng Churn & Two-pass (inSampleSize)";
            stepState[0] = STEP_RUNNING;
            stepValues[0] = "Đang xử lý";

            stepLabels[1] = "2. Nén WebP 75% bằng bộ đệm 8 KB";
            stepState[1] = STEP_IDLE;
            stepValues[1] = "Chờ";

            stepLabels[2] = "3. Ghi thẻ 240B & đưa lên GPU VRAM";
            stepState[2] = STEP_IDLE;
            stepValues[2] = "Chờ";
        }

        void onAfterCompressed(int sampleSize, double webpKb, int reuses) {
            inSampleSize = Math.max(1, sampleSize);
            sizeKb = webpKb;
            bufferReuses = reuses;

            decodeNodeState = NODE_OK;
            decodeBadge = "Sample=" + inSampleSize;
            compressNodeState = NODE_OK;
            compressBadge = "Tái dùng #" + reuses;
            chipNodeState = NODE_ACTIVE;
            chipBadge = "Đang ghi...";
            summaryText = String.format(Locale.US,
                    "WebP %.1f KB / 8.0 KB (Buffer 8KB #%d) — Đang ghi thẻ...", webpKb, reuses);

            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "Sample=" + inSampleSize;
            stepState[1] = STEP_DONE_OK;
            stepValues[1] = String.format(Locale.US, "%.1f KB", webpKb);
            stepState[2] = STEP_RUNNING;
            stepValues[2] = "Đang ghi...";
        }

        void finishAfterRun(int sampleSize, double webpKb, int uploadPackets,
                            double displayRamMb, boolean gpuVram, int reuses,
                            long gcCount, long gcMs, boolean writeOk) {
            hasRun = true;
            mode = MODE_AFTER;
            runInProgress = false;
            inSampleSize = Math.max(1, sampleSize);
            sizeKb = webpKb;
            packets = Math.max(0, uploadPackets);
            ramMb = displayRamMb;
            inGpuVram = gpuVram;
            bufferReuses = reuses;
            deltaGcCount = gcCount;
            deltaGcMs = gcMs;
            cardWriteOk = writeOk;

            decodeNodeState = NODE_OK;
            decodeBadge = "Sample=" + inSampleSize;
            compressNodeState = NODE_OK;
            compressBadge = "Tái dùng #" + reuses;
            chipNodeState = writeOk ? NODE_OK : NODE_FAULT;
            chipBadge = writeOk ? (packets + " gói ✓") : "Lỗi ghi";
            summaryText = String.format(Locale.US,
                    "WebP %.1f KB / 8.0 KB (%d gói) · %s · GC +%d",
                    webpKb, packets, gpuVram ? "GPU VRAM" : "150×150", gcCount);

            stepState[0] = STEP_DONE_OK;
            stepValues[0] = "Sample=" + inSampleSize;
            stepState[1] = STEP_DONE_OK;
            stepValues[1] = String.format(Locale.US, "%.1f KB", webpKb);
            stepState[2] = writeOk ? STEP_DONE_OK : STEP_DONE_FAULT;
            stepValues[2] = writeOk ? (gpuVram ? "GPU VRAM" : packets + " gói") : "Từ chối";
        }

        float getCapacityBarRatio() {
            if (mode == MODE_BEFORE) {
                if (runInProgress) {
                    float churnRatio = clamp01(churnAllocatedCount / (float) BEFORE_CHURN_TOTAL);
                    return sizeKb > 0 ? Math.max(0.15f, churnRatio) : churnRatio;
                }
                return 1f;
            }
            if (mode == MODE_AFTER) {
                return clamp01((float) (sizeKb / CARD_LIMIT_KB));
            }
            return 0f;
        }
    }
}
