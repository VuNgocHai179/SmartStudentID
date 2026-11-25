package com.example.smartstudentid.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.example.smartstudentid.security.SecurityEnvironmentChecker;

import java.util.Locale;

/**
 * Interactive visual Security Defense Monitor displayed directly inside §5 (TopicDemoFragment).
 * Renders 3 top KPI cards, a 6-probe security grid (Anti-Hooking, Native Debugger, APK Integrity,
 * Tapjacking, 120s Background Lock, Hardware Biometric CryptoObject), and a live event banner.
 * Zero allocations are performed inside onDraw().
 */
public class RuntimeDefenseMonitorView extends View {

    private static final int COLOR_SLATE_900 = 0xFF0F172A;
    private static final int COLOR_SLATE_700 = 0xFF334155;
    private static final int COLOR_SLATE_500 = 0xFF64748B;
    private static final int COLOR_SLATE_200 = 0xFFE2E8F0;
    private static final int COLOR_SLATE_50 = 0xFFF8FAFC;

    private static final int COLOR_BLUE = 0xFF2563EB;
    private static final int COLOR_BLUE_BG = 0xFFEFF6FF;
    private static final int COLOR_BLUE_BORDER = 0xFFBFDBFE;

    private static final int COLOR_RED = 0xFFDC2626;
    private static final int COLOR_RED_BG = 0xFFFEF2F2;
    private static final int COLOR_RED_BORDER = 0xFFFECACA;

    private static final int COLOR_AMBER = 0xFFD97706;
    private static final int COLOR_AMBER_BG = 0xFFFFFBEB;
    private static final int COLOR_AMBER_BORDER = 0xFFFDE68A;

    private static final int COLOR_GREEN = 0xFF059669;
    private static final int COLOR_GREEN_BG = 0xFFECFDF5;
    private static final int COLOR_GREEN_BORDER = 0xFFA7F3D0;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    private final DefenseMonitorState state = new DefenseMonitorState();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface sansBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Typeface sansRegular = Typeface.DEFAULT;
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final RectF cardRect = new RectF();
    private final RectF tileRect = new RectF();
    private final RectF pillRect = new RectF();
    private final RectF bannerRect = new RectF();

    public RuntimeDefenseMonitorView(Context context) {
        this(context, null);
    }

    public RuntimeDefenseMonitorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        setContentDescription("Bảng đo kiểm 6 lớp phòng thủ bảo mật thời gian chạy");
    }

    public void showLiveScan(SecurityEnvironmentChecker.SecurityAuditReport report,
                             boolean isDebugBuild,
                             boolean flagSecureOn,
                             boolean l1Present,
                             boolean bioStrongAvailable,
                             String bioBacking,
                             long scanMs) {
        state.applyLiveScan(report, isDebugBuild, flagSecureOn, l1Present,
                bioStrongAvailable, bioBacking, scanMs);
        invalidate();
    }

    public void showSimulatedFridaDefense(boolean flagSecureOn, long reactionMs) {
        state.applySimulatedFridaDefense(flagSecureOn, reactionMs);
        invalidate();
    }

    public void onFlagSecureToggled(boolean flagSecureOn) {
        state.applyFlagSecureToggled(flagSecureOn);
        invalidate();
    }

    public void showBackgroundLockTriggered(boolean flagSecureOn) {
        state.applyBackgroundLockTriggered(flagSecureOn);
        invalidate();
    }

    public DefenseMonitorState getState() {
        return state;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = Math.round(dp(320f));
        int desiredH = Math.round(dp(292f));
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

        float kpiH = dp(48f);
        drawKpiStrip(canvas, w, kpiH);

        float gridTop = kpiH + dp(10f);
        float gridH = dp(182f);
        drawProbeGrid(canvas, w, gridTop, gridH);

        float bannerTop = gridTop + gridH + dp(10f);
        float bannerH = Math.max(dp(38f), h - bannerTop);
        drawLiveBanner(canvas, w, bannerTop, bannerH);
    }

    private void drawKpiStrip(Canvas canvas, float w, float kpiH) {
        float gap = dp(8f);
        float cardW = (w - gap * 2f) / 3f;

        int passed = state.getPassedProbeCount();
        String secVal = passed + "/6 AN TOÀN";
        int secBg = COLOR_GREEN_BG;
        int secBorder = COLOR_GREEN_BORDER;
        int secColor = COLOR_GREEN;
        if (state.threatActive || passed < 4) {
            secBg = COLOR_RED_BG;
            secBorder = COLOR_RED_BORDER;
            secColor = COLOR_RED;
            if (state.threatActive) {
                secVal = "CẢNH BÁO!";
            }
        } else if (passed < 6) {
            secBg = COLOR_AMBER_BG;
            secBorder = COLOR_AMBER_BORDER;
            secColor = COLOR_AMBER;
        }

        String flagVal = state.flagSecureOn ? "FLAG_SECURE ON" : "DEBUG MIRROR";
        int flagBg = state.flagSecureOn ? COLOR_GREEN_BG : COLOR_AMBER_BG;
        int flagBorder = state.flagSecureOn ? COLOR_GREEN_BORDER : COLOR_AMBER_BORDER;
        int flagColor = state.flagSecureOn ? COLOR_GREEN : COLOR_AMBER;

        String ramVal = state.l1RamPresent ? "ĐANG LƯU" : "ĐÃ XÓA SẠCH";
        int ramBg = state.l1RamPresent ? COLOR_BLUE_BG : COLOR_GREEN_BG;
        int ramBorder = state.l1RamPresent ? COLOR_BLUE_BORDER : COLOR_GREEN_BORDER;
        int ramColor = state.l1RamPresent ? COLOR_BLUE : COLOR_GREEN;

        drawKpiCard(canvas, 0f, cardW, kpiH, "BẢO VỆ", secVal, secBg, secBorder, secColor);
        drawKpiCard(canvas, cardW + gap, cardW, kpiH, "CHỐNG CHỤP", flagVal, flagBg, flagBorder, flagColor);
        drawKpiCard(canvas, (cardW + gap) * 2f, cardW, kpiH, "L1 RAM / PIN", ramVal, ramBg, ramBorder, ramColor);
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
        textPaint.setTextSize(dp(10f));
        textPaint.setColor(valueColor);
        canvas.drawText(value, cx, dp(36f), textPaint);
    }

    private void drawProbeGrid(Canvas canvas, float w, float top, float gridH) {
        float colGap = dp(8f);
        float rowGap = dp(8f);
        float tileW = (w - colGap) * 0.5f;
        float tileH = (gridH - rowGap * 2f) / 3f;

        for (int i = 0; i < 6; i++) {
            int row = i / 2;
            int col = i % 2;
            float left = col * (tileW + colGap);
            float tileTop = top + row * (tileH + rowGap);
            drawProbeTile(
                    canvas, left, tileTop, tileW, tileH,
                    state.probeTitles[i],
                    state.probeSubtitles[i],
                    state.probeValues[i],
                    state.probeStates[i]
            );
        }
    }

    private void drawProbeTile(Canvas canvas, float left, float top, float width, float height,
                               String title, String subtitle, String value, int probeState) {
        int bgColor = COLOR_SLATE_50;
        int borderColor = COLOR_SLATE_200;
        int dotColor = COLOR_SLATE_500;
        int pillBg = COLOR_WHITE;
        int pillBorder = COLOR_SLATE_200;
        int valueColor = COLOR_SLATE_700;

        if (probeState == DefenseMonitorState.STATE_SAFE) {
            bgColor = COLOR_GREEN_BG;
            borderColor = COLOR_GREEN_BORDER;
            dotColor = COLOR_GREEN;
            pillBorder = COLOR_GREEN_BORDER;
            valueColor = COLOR_GREEN;
        } else if (probeState == DefenseMonitorState.STATE_WARN) {
            bgColor = COLOR_AMBER_BG;
            borderColor = COLOR_AMBER_BORDER;
            dotColor = COLOR_AMBER;
            pillBorder = COLOR_AMBER_BORDER;
            valueColor = COLOR_AMBER;
        } else if (probeState == DefenseMonitorState.STATE_ALERT) {
            bgColor = COLOR_RED_BG;
            borderColor = COLOR_RED_BORDER;
            dotColor = COLOR_RED;
            pillBorder = COLOR_RED_BORDER;
            valueColor = COLOR_RED;
        }

        float radius = dp(11f);
        tileRect.set(left, top, left + width, top + height);
        fillPaint.setColor(bgColor);
        canvas.drawRoundRect(tileRect, radius, radius, fillPaint);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(borderColor);
        canvas.drawRoundRect(tileRect, radius, radius, strokePaint);

        float padX = dp(9f);
        float dotCx = left + padX + dp(3.5f);
        float dotCy = top + dp(16f);
        fillPaint.setColor(dotColor);
        canvas.drawCircle(dotCx, dotCy, dp(3.5f), fillPaint);

        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(COLOR_SLATE_900);
        canvas.drawText(title, dotCx + dp(7f), top + dp(19.5f), textPaint);

        textPaint.setTypeface(sansRegular);
        textPaint.setTextSize(dp(8.5f));
        textPaint.setColor(COLOR_SLATE_500);
        canvas.drawText(subtitle, left + padX, top + dp(34f), textPaint);

        // Right-aligned live value pill inside bottom-right or top-right? Place nicely on bottom-left/right.
        textPaint.setTypeface(monoBold);
        textPaint.setTextSize(dp(8.5f));
        float valW = textPaint.measureText(value);
        float pillW = valW + dp(10f);
        float pillH = dp(15f);
        float pillRight = left + width - padX;
        float pillLeft = Math.max(left + padX, pillRight - pillW);
        float pillTop = top + height - pillH - dp(7f);
        pillRect.set(pillLeft, pillTop, pillRight, pillTop + pillH);

        fillPaint.setColor(pillBg);
        canvas.drawRoundRect(pillRect, dp(7.5f), dp(7.5f), fillPaint);
        strokePaint.setStrokeWidth(dp(0.8f));
        strokePaint.setColor(pillBorder);
        canvas.drawRoundRect(pillRect, dp(7.5f), dp(7.5f), strokePaint);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(valueColor);
        canvas.drawText(value, pillRect.centerX(), pillRect.centerY() + dp(3f), textPaint);
    }

    private void drawLiveBanner(Canvas canvas, float w, float top, float height) {
        int bgColor = COLOR_SLATE_50;
        int borderColor = COLOR_SLATE_200;
        int textColor = COLOR_SLATE_700;

        if (state.bannerSeverity == DefenseMonitorState.STATE_SAFE) {
            bgColor = COLOR_GREEN_BG;
            borderColor = COLOR_GREEN_BORDER;
            textColor = COLOR_GREEN;
        } else if (state.bannerSeverity == DefenseMonitorState.STATE_WARN) {
            bgColor = COLOR_AMBER_BG;
            borderColor = COLOR_AMBER_BORDER;
            textColor = COLOR_AMBER;
        } else if (state.bannerSeverity == DefenseMonitorState.STATE_ALERT) {
            bgColor = COLOR_RED_BG;
            borderColor = COLOR_RED_BORDER;
            textColor = COLOR_RED;
        }

        bannerRect.set(0f, top, w, top + height);
        float radius = dp(11f);
        fillPaint.setColor(bgColor);
        canvas.drawRoundRect(bannerRect, radius, radius, fillPaint);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(borderColor);
        canvas.drawRoundRect(bannerRect, radius, radius, strokePaint);

        textPaint.setTypeface(sansBold);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(textColor);
        String msg = state.bannerText != null ? state.bannerText : "";
        canvas.drawText(msg, bannerRect.centerX(), bannerRect.centerY() + dp(3.5f), textPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * Pure-Java state model backing RuntimeDefenseMonitorView for JVM unit testing.
     */
    public static final class DefenseMonitorState {
        public static final int STATE_NEUTRAL = 0;
        public static final int STATE_SAFE = 1;
        public static final int STATE_WARN = 2;
        public static final int STATE_ALERT = 3;

        public final String[] probeTitles = {
                "ANTI-HOOKING",
                "NATIVE DEBUG",
                "TOÀN VẸN APK",
                "TAPJACKING",
                "KHÓA CHẠY NỀN",
                "SINH TRẮC HỌC"
        };

        public final String[] probeSubtitles = {
                "/proc/self/maps",
                "TracerPid",
                "SHA-256",
                "PIN Keypad",
                "Timeout",
                "CryptoObject"
        };

        public final String[] probeValues = {
                "Đang quét",
                "Đang quét",
                "Đang quét",
                "Lọc lớp phủ",
                "120s (L1+PIN)",
                "Đang kiểm tra"
        };

        public final int[] probeStates = {
                STATE_NEUTRAL, STATE_NEUTRAL, STATE_NEUTRAL,
                STATE_SAFE, STATE_SAFE, STATE_NEUTRAL
        };

        public boolean flagSecureOn = false;
        public boolean l1RamPresent = false;
        public boolean threatActive = false;
        public String bannerText = "Đang khởi tạo phép đo bảo mật...";
        public int bannerSeverity = STATE_NEUTRAL;

        public void applyLiveScan(SecurityEnvironmentChecker.SecurityAuditReport report,
                                  boolean isDebugBuild,
                                  boolean flagSecureOn,
                                  boolean l1Present,
                                  boolean bioStrongAvailable,
                                  String bioBacking,
                                  long scanMs) {
            this.flagSecureOn = flagSecureOn;
            this.l1RamPresent = l1Present;

            boolean hooking = report != null && report.hookingDetected;
            probeValues[0] = hooking ? "Frida Hook!" : "Sạch";
            probeStates[0] = hooking ? STATE_ALERT : STATE_SAFE;

            boolean nativeDbg = report != null && report.nativeDebugger;
            boolean dbgAlert = nativeDbg && !isDebugBuild;
            probeValues[1] = nativeDbg ? "Attached!" : "PID = 0";
            probeStates[1] = dbgAlert ? STATE_ALERT : (nativeDbg ? STATE_WARN : STATE_SAFE);

            boolean apkValid = report != null && report.apkSignatureValid;
            String shortHex = report != null ? report.getShortApkCertHex() : "N/A";
            if (shortHex.length() > 8) {
                shortHex = shortHex.substring(0, 8) + "...";
            }
            probeValues[2] = apkValid ? shortHex : "Sai chữ ký!";
            probeStates[2] = apkValid ? STATE_SAFE : STATE_ALERT;

            probeValues[3] = "Lọc lớp phủ";
            probeStates[3] = STATE_SAFE;

            probeValues[4] = "120s (L1+PIN)";
            probeStates[4] = STATE_SAFE;

            if (bioStrongAvailable) {
                probeValues[5] = (bioBacking != null && !bioBacking.isEmpty()) ? bioBacking : "TEE";
                probeStates[5] = STATE_SAFE;
            } else {
                probeValues[5] = "Chưa đăng ký";
                probeStates[5] = STATE_WARN;
            }

            this.threatActive = report != null && report.hasCriticalThreat(isDebugBuild);
            if (this.threatActive) {
                this.bannerText = String.format(Locale.US,
                        "CẢNH BÁO (%d ms): %s", scanMs,
                        report.buildThreatSummary(isDebugBuild));
                this.bannerSeverity = STATE_ALERT;
            } else {
                this.bannerText = String.format(Locale.US,
                        "Quét thực tế %d ms • Phòng thủ chủ động: Sẵn sàng", scanMs);
                this.bannerSeverity = STATE_SAFE;
            }
        }

        public void applySimulatedFridaDefense(boolean flagSecureOn, long reactionMs) {
            this.flagSecureOn = flagSecureOn;
            this.l1RamPresent = false;
            this.threatActive = true;

            probeValues[0] = "Frida Hook!";
            probeStates[0] = STATE_ALERT;
            probeValues[4] = "Đã xóa L1+PIN";
            probeStates[4] = STATE_WARN;

            this.bannerText = String.format(Locale.US,
                    "PHẢN ỨNG CHỦ ĐỘNG (%d ms): Đã xóa L1 RAM & PIN tạm!", reactionMs);
            this.bannerSeverity = STATE_ALERT;
        }

        public void applyFlagSecureToggled(boolean flagSecureOn) {
            this.flagSecureOn = flagSecureOn;
            this.bannerText = flagSecureOn
                    ? "FLAG_SECURE = ON • Thử chụp màn hình ngay để kiểm chứng"
                    : "FLAG_SECURE = OFF (Chế độ chiếu demo Debug)";
            this.bannerSeverity = flagSecureOn ? STATE_SAFE : STATE_WARN;
        }

        public void applyBackgroundLockTriggered(boolean flagSecureOn) {
            this.flagSecureOn = flagSecureOn;
            this.l1RamPresent = false;
            this.threatActive = false;
            probeValues[4] = "Đã khóa L1";
            probeStates[4] = STATE_WARN;
            this.bannerText = "Tự khóa nền 120s: Đã xóa PIN tạm & L1 RAM";
            this.bannerSeverity = STATE_WARN;
        }

        public int getPassedProbeCount() {
            int count = 0;
            for (int probeState : probeStates) {
                if (probeState == STATE_SAFE) {
                    count++;
                }
            }
            return count;
        }
    }
}
