package com.example.smartstudentid.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
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
    private static final int FRAMES_PER_SEGMENT = 4;
    private static final int FPS_WINDOW_MS = 250;
    private static final int GAP_WINDOWS = 4; // ~1 s rolling worst frame gap

    public interface StatsListener {
        void onFrameStats(int fps, long worstGapMs, boolean jank);
    }

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
    private long worstGapMs = 17;
    private int frameStep;
    private boolean running;
    private StatsListener statsListener;

    public UiHeartbeatView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setContentDescription("Nhịp UI: animation chạy trên Main Thread để phát hiện giật/đứng hình");
    }

    public void setStatsListener(StatsListener listener) {
        this.statsListener = listener;
    }

    public int getCurrentFps() {
        return fps;
    }

    public long getWorstGapMs() {
        return worstGapMs;
    }

    public boolean isJankState() {
        return fps < 45 || worstGapMs > 32;
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
        worstGapMs = 17;
        fps = 60;
        frameStep = 0;
        Arrays.fill(gapRing, 0);
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void onFrame(long frameTimeNanos) {
        if (!running) return;
        frameStep++;
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
            int rawFps = Math.max(0, Math.round(framesInWindow * 1000f / elapsedMs));
            fps = windowMaxGapMs > 32
                    ? Math.min(rawFps, Math.max(6, Math.round(1000f / windowMaxGapMs)))
                    : rawFps;
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

            if (statsListener != null) {
                statsListener.onFrameStats(fps, worstGapMs, isJankState());
            }
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
        boolean jank = isJankState();
        int activeColor = !jank ? Color.parseColor("#059669")
                : fps >= 30 ? Color.parseColor("#D97706")
                : Color.parseColor("#DC2626");
        int activeSegment = (frameStep / FRAMES_PER_SEGMENT) % SEGMENTS;
        float top = dp(5);
        for (int i = 0; i < SEGMENTS; i++) {
            float left = i * (segmentWidth + gap);
            rect.set(left, top, left + segmentWidth, top + barHeight);
            paint.setColor(i == activeSegment ? activeColor : Color.parseColor("#E2E8F0"));
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint);
        }

        paint.setColor(Color.parseColor(jank ? "#DC2626" : "#059669"));
        paint.setTextSize(dp(10));
        String statusText = jank
                ? String.format(Locale.US, "⚠ UI BỊ NGHẼN: %d FPS • trễ frame %d ms (Rớt khung!)", fps, worstGapMs)
                : String.format(Locale.US, "✓ UI MƯỢT: %d FPS • trễ max %d ms (Chuẩn 60 FPS)", fps, worstGapMs);
        canvas.drawText(statusText, 0, height - dp(6), paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
