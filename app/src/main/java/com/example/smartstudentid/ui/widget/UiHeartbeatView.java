package com.example.smartstudentid.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

/**
 * Visual proof for §2 (60 FPS / non-blocking UI): a heartbeat driven by Choreographer on the
 * Main Thread. While the UI thread is smooth the blocks keep moving and the frame rate stays at
 * the display refresh rate; when a Before-mode task blocks the Main Thread the animation freezes
 * and the measured fps / worst frame gap expose the jank.
 */
public class UiHeartbeatView extends View {

    private static final int SEGMENTS = 16;
    private static final long SEGMENT_MS = 90L;
    private static final int FPS_WINDOW_MS = 500;
    private static final int GAP_WINDOWS = 6; // ~3 s rolling worst frame gap

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final long[] gapRing = new long[GAP_WINDOWS];

    private final Choreographer.FrameCallback frameCallback = this::onFrame;

    private long lastFrameNs;
    private long windowStartNs;
    private int framesInWindow;
    private int fps = 60;
    private long windowMaxGapMs;
    private int ringIndex;
    private long worstGapMs;
    private boolean running;

    public UiHeartbeatView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setContentDescription("Nhịp UI: animation chạy trên Main Thread để phát hiện giật/đứng hình");
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
        lastFrameNs = 0;
        windowStartNs = 0;
        framesInWindow = 0;
        windowMaxGapMs = 0;
        ringIndex = 0;
        worstGapMs = 0;
        Arrays.fill(gapRing, 0);
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void onFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNs != 0) {
            long gapMs = (frameTimeNanos - lastFrameNs) / 1_000_000;
            if (gapMs > windowMaxGapMs) {
                windowMaxGapMs = gapMs;
            }
        }
        lastFrameNs = frameTimeNanos;

        if (windowStartNs == 0) {
            windowStartNs = frameTimeNanos;
        }
        framesInWindow++;
        long elapsedMs = (frameTimeNanos - windowStartNs) / 1_000_000;
        if (elapsedMs >= FPS_WINDOW_MS) {
            fps = Math.max(0, Math.round(framesInWindow * 1000f / elapsedMs));
            framesInWindow = 0;
            windowStartNs = frameTimeNanos;

            gapRing[ringIndex % GAP_WINDOWS] = windowMaxGapMs;
            ringIndex++;
            windowMaxGapMs = 0;
            long worst = 0;
            for (long gap : gapRing) {
                worst = Math.max(worst, gap);
            }
            worstGapMs = worst;
        }

        invalidate();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float barHeight = dp(10);
        float gap = dp(3);
        float segmentWidth = (width - gap * (SEGMENTS - 1)) / SEGMENTS;
        int activeColor = fps >= 50 ? Color.parseColor("#059669")
                : fps >= 30 ? Color.parseColor("#D97706")
                : Color.parseColor("#DC2626");
        int activeSegment = (int) ((SystemClock.elapsedRealtime() / SEGMENT_MS) % SEGMENTS);
        float top = dp(5);
        for (int i = 0; i < SEGMENTS; i++) {
            float left = i * (segmentWidth + gap);
            rect.set(left, top, left + segmentWidth, top + barHeight);
            paint.setColor(i == activeSegment ? activeColor : Color.parseColor("#E2E8F0"));
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint);
        }

        paint.setColor(Color.parseColor("#64748B"));
        paint.setTextSize(dp(10));
        canvas.drawText(String.format(Locale.US, "UI %d fps • trễ max %d ms", fps, worstGapMs),
                0, height - dp(6), paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
