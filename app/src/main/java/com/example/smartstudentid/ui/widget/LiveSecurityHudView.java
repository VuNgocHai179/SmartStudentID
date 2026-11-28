package com.example.smartstudentid.ui.widget;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.example.smartstudentid.BuildConfig;
import com.example.smartstudentid.optimization.ChaosInjectionManager;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.BiometricKeyManager;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.SecurityEnvironmentChecker;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Floating Live Security Defense HUD matching LivePerfHudView in look and interaction:
 * dark translucent card, alert border, tap-to-collapse/expand, and smooth dragging inside
 * its parent container. Polls real OWASP MASVS runtime probes off the main thread.
 */
public class LiveSecurityHudView extends View {

    private static final int SAMPLE_INTERVAL_MS = 1000;

    private static final int BG_DARK = 0xE80F172A;
    private static final int BORDER_NORMAL = 0xFF334155;
    private static final int BORDER_ALERT = 0xFFEF4444;
    private static final int DIVIDER_DARK = 0xFF1E293B;
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
    private final Typeface sansBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final RectF cardRect = new RectF();

    private final RuntimeDefenseMonitorView.DefenseMonitorState state =
            new RuntimeDefenseMonitorView.DefenseMonitorState();
    private final Object stateLock = new Object();
    private final ChaosInjectionManager chaos = ChaosInjectionManager.getInstance();
    private final int touchSlop;

    private final ChaosInjectionManager.Listener chaosListener = this::refreshNow;

    private ScheduledExecutorService secExecutor;
    private ScheduledFuture<?> secFuture;

    private boolean expanded = true;
    private boolean running = false;

    private volatile long lastScanMs = 1L;
    private volatile int lastMasvsScore = 5;

    private float downRawX;
    private float downRawY;
    private float startTransX;
    private float startTransY;
    private boolean dragging;

    public LiveSecurityHudView(Context context) {
        this(context, null);
    }

    public LiveSecurityHudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(BG_DARK);
        borderPaint.setStyle(Paint.Style.STROKE);

        setClickable(true);
        setContentDescription("Popup nổi giám sát 6 lớp phòng thủ bảo mật thời gian thực");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.round(dp(expanded ? 242f : 218f));
        int h = Math.round(dp(expanded ? 172f : 34f));
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunningState();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        if (secExecutor != null) {
            secExecutor.shutdownNow();
            secExecutor = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateRunningState();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunningState();
    }

    private void updateRunningState() {
        boolean shouldRun = isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
        if (shouldRun) {
            start();
        } else {
            stop();
        }
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

    /**
     * Triggers an immediate off-thread probe refresh when visible.
     */
    public void refreshNow() {
        if (!running || secExecutor == null || secExecutor.isShutdown()) {
            return;
        }
        try {
            secExecutor.execute(this::sampleSecurityState);
        } catch (Exception ignored) {
        }
    }

    private void start() {
        if (running) return;
        running = true;
        chaos.addListener(chaosListener);
        if (secExecutor == null || secExecutor.isShutdown()) {
            secExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sec-hud-sampler");
                t.setDaemon(true);
                return t;
            });
        }
        if (secFuture == null || secFuture.isCancelled() || secFuture.isDone()) {
            secFuture = secExecutor.scheduleWithFixedDelay(
                    this::sampleSecurityState, 0L, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void stop() {
        running = false;
        chaos.removeListener(chaosListener);
        if (secFuture != null) {
            secFuture.cancel(false);
            secFuture = null;
        }
    }

    private void sampleSecurityState() {
        if (!running) return;
        try {
            Context appCtx = getContext().getApplicationContext();
            long startNs = System.nanoTime();
            SecurityEnvironmentChecker.SecurityAuditReport report =
                    SecurityEnvironmentChecker.inspectEnvironment(appCtx);
            boolean securityBypass = chaos.isPinRamLeakAndSecurityBypassActive();
            BaseSecureActivity host = findHostSecureActivity();
            boolean flagSecureOn = host != null && host.isFlagSecureEnabled();
            boolean l1Present = EncryptedCacheManager.getInstance(appCtx).hasL1ProfileCached();
            boolean bioStrong = BiometricKeyManager.isBiometricStrongAvailable(appCtx);
            String bioBacking = new BiometricKeyManager(appCtx).getBackingStoreLabel();
            long scanMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);

            lastScanMs = scanMs;
            lastMasvsScore = securityBypass ? chaos.getMasvsScore() : 5;

            synchronized (stateLock) {
                state.applyLiveScan(
                        report,
                        BuildConfig.DEBUG,
                        securityBypass,
                        flagSecureOn,
                        l1Present,
                        bioStrong,
                        bioBacking,
                        scanMs
                );
            }
            postInvalidate();
        } catch (Exception ignored) {
        }
    }

    private BaseSecureActivity findHostSecureActivity() {
        Context ctx = getContext();
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof BaseSecureActivity) {
                return (BaseSecureActivity) ctx;
            }
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
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

        synchronized (stateLock) {
            boolean alert = state.securityBypassActive || state.threatActive;
            int passed = state.getPassedProbeCount();

            float radius = dp(expanded ? 14f : 17f);
            float strokeW = dp(1.2f);
            cardRect.set(strokeW, strokeW, w - strokeW, h - strokeW);
            canvas.drawRoundRect(cardRect, radius, radius, bgPaint);

            borderPaint.setStrokeWidth(strokeW);
            borderPaint.setColor(alert ? BORDER_ALERT : BORDER_NORMAL);
            canvas.drawRoundRect(cardRect, radius, radius, borderPaint);

            if (!expanded) {
                drawCompactPill(canvas, w, h, alert, passed);
                return;
            }
            drawExpandedHud(canvas, w, h, alert, passed);
        }
    }

    private void drawCompactPill(Canvas canvas, float w, float h, boolean alert, int passed) {
        float cy = h * 0.5f;
        int dotColor = alert ? COLOR_RED : (passed < 6 ? COLOR_AMBER : COLOR_GREEN);
        barPaint.setColor(dotColor);
        canvas.drawCircle(dp(13f), cy, dp(4f), barPaint);

        textPaint.setTypeface(monoBold);
        textPaint.setTextSize(dp(10f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(TEXT_LIGHT);
        String text;
        if (state.securityBypassActive) {
            text = "SEC 0/6 · RỦI RO!";
        } else {
            String flagTag = state.flagSecureOn ? "ON" : "AUTO";
            String l1Tag = state.l1RamPresent ? "SAVED" : "WIPED";
            text = String.format(Locale.US, "SEC %d/6 · FLAG:%s · L1:%s", passed, flagTag, l1Tag);
        }
        canvas.drawText(text, dp(22f), cy + dp(3.5f), textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(TEXT_MUTED);
        canvas.drawText("[+]", w - dp(10f), cy + dp(3.5f), textPaint);
    }

    private void drawExpandedHud(Canvas canvas, float w, float h, boolean alert, int passed) {
        float padX = dp(10f);
        float innerW = w - padX * 2f;

        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(TEXT_LIGHT);
        canvas.drawText("LIVE SECURITY HUD", padX, dp(16f), textPaint);

        String badgeText;
        int badgeColor;
        if (state.securityBypassActive) {
            badgeText = "0/6 RỦI RO";
            badgeColor = COLOR_RED;
        } else if (state.threatActive) {
            badgeText = "CẢNH BÁO!";
            badgeColor = COLOR_RED;
        } else if (passed < 6) {
            badgeText = passed + "/6 AN TOÀN";
            badgeColor = COLOR_AMBER;
        } else {
            badgeText = passed + "/6 AN TOÀN";
            badgeColor = COLOR_GREEN;
        }
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(badgeColor);
        canvas.drawText(badgeText + "  [–]", w - padX, dp(16f), textPaint);

        barPaint.setColor(DIVIDER_DARK);
        canvas.drawRect(padX, dp(21f), w - padX, dp(22f), barPaint);

        float startY = dp(37f);
        float rowStep = dp(19f);

        drawProbeRow(canvas, padX, innerW, startY, "HOOK",
                state.probeValues[0], colorForProbeState(state.probeStates[0]));
        drawProbeRow(canvas, padX, innerW, startY + rowStep, "DEBUG",
                state.probeValues[1], colorForProbeState(state.probeStates[1]));
        drawProbeRow(canvas, padX, innerW, startY + rowStep * 2f, "APK",
                state.probeValues[2], colorForProbeState(state.probeStates[2]));

        String uiVal = state.securityBypassActive
                ? "TẮT · Không lọc!"
                : (state.flagSecureOn ? "FLAG:ON · Tapjack" : "FLAG:AUTO · Tapjack");
        int uiColor = state.securityBypassActive
                ? COLOR_RED : (state.flagSecureOn ? COLOR_GREEN : COLOR_AMBER);
        drawProbeRow(canvas, padX, innerW, startY + rowStep * 3f, "UI", uiVal, uiColor);

        String ramVal = state.securityBypassActive
                ? "Lộ String 100%!"
                : (state.l1RamPresent ? "L1:Đang lưu · 120s" : "L1:Đã xóa · 120s");
        int ramColor = state.securityBypassActive
                ? COLOR_RED : (state.l1RamPresent ? COLOR_SKY : COLOR_GREEN);
        drawProbeRow(canvas, padX, innerW, startY + rowStep * 4f, "RAM", ramVal, ramColor);

        drawProbeRow(canvas, padX, innerW, startY + rowStep * 5f, "BIO",
                state.probeValues[5], colorForProbeState(state.probeStates[5]));

        barPaint.setColor(DIVIDER_DARK);
        canvas.drawRect(padX, h - dp(21f), w - padX, h - dp(20f), barPaint);

        textPaint.setTypeface(monoBold);
        textPaint.setTextSize(dp(8.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(alert ? COLOR_RED : COLOR_GREEN);
        canvas.drawText(String.format(Locale.US, "MASVS:%d/5 · %dms", lastMasvsScore, lastScanMs),
                padX, h - dp(7f), textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(TEXT_MUTED);
        canvas.drawText("Kéo/Chạm", w - padX, h - dp(7f), textPaint);
    }

    private void drawProbeRow(Canvas canvas, float padX, float innerW, float baselineY,
                              String label, String value, int valueColor) {
        textPaint.setTypeface(monoBold);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(TEXT_MUTED);
        canvas.drawText(label, padX, baselineY, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(valueColor);
        canvas.drawText(value != null ? value : "—", padX + innerW, baselineY, textPaint);
    }

    private static int colorForProbeState(int probeState) {
        if (probeState == RuntimeDefenseMonitorView.DefenseMonitorState.STATE_SAFE) {
            return COLOR_GREEN;
        }
        if (probeState == RuntimeDefenseMonitorView.DefenseMonitorState.STATE_WARN) {
            return COLOR_AMBER;
        }
        if (probeState == RuntimeDefenseMonitorView.DefenseMonitorState.STATE_ALERT) {
            return COLOR_RED;
        }
        return TEXT_LIGHT;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
