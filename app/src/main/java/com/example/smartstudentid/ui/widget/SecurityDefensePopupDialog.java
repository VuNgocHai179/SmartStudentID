package com.example.smartstudentid.ui.widget;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.smartstudentid.BuildConfig;
import com.example.smartstudentid.R;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.optimization.ChaosInjectionManager;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.BiometricKeyManager;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.SecurityEnvironmentChecker;

/**
 * Strictly read-only modal popup dialog displaying real-time OWASP MASVS runtime security probes.
 * Interactive Before/After testing belongs to TopicDemoFragment (§5); this popup strictly observes
 * and reports the live state (matching the read-only performance HUD).
 */
public final class SecurityDefensePopupDialog {

    private static final int COLOR_GREEN = 0xFF059669;
    private static final int COLOR_RED = 0xFFDC2626;
    private static final int COLOR_AMBER = 0xFFD97706;

    private SecurityDefensePopupDialog() {
    }

    public static void show(@NonNull BaseSecureActivity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        View root = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_security_defense_monitor, null, false);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCancelable(true);

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
            dialogWindow.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            applyWindowFlagSecure(dialogWindow, activity.isFlagSecureEnabled());
        }

        TextView tvBadge = root.findViewById(R.id.tvPopupDefenseBadge);
        View btnClose = root.findViewById(R.id.btnCloseDefensePopup);
        RuntimeDefenseMonitorView monitor = root.findViewById(R.id.popupRuntimeDefenseMonitor);

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        runLiveScan(activity, dialog, monitor, tvBadge);
    }

    private static void runLiveScan(BaseSecureActivity activity,
                                    Dialog dialog,
                                    RuntimeDefenseMonitorView monitor,
                                    TextView tvBadge) {
        final Context appCtx = activity.getApplicationContext();
        tvBadge.setText("Đang quét...");

        ApduThreadPool.getInstance().executeAsyncOnPool(() -> {
            long startNs = System.nanoTime();
            SecurityEnvironmentChecker.SecurityAuditReport report =
                    SecurityEnvironmentChecker.inspectEnvironment(appCtx);
            boolean securityBypass = ChaosInjectionManager.getInstance()
                    .isPinRamLeakAndSecurityBypassActive();
            boolean bioStrong = BiometricKeyManager.isBiometricStrongAvailable(appCtx);
            String bioBacking = new BiometricKeyManager(appCtx).getBackingStoreLabel();
            boolean l1Present = EncryptedCacheManager.getInstance(appCtx).hasL1ProfileCached();
            long scanMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            return new ScanSnapshot(report, securityBypass, bioStrong, bioBacking, l1Present, scanMs);
        }, new ApduThreadPool.TaskCallback<ScanSnapshot>() {
            @Override
            public void onComplete(ScanSnapshot res, long mainBlockedMs, long totalMs) {
                if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                boolean currentFlag = activity.isFlagSecureEnabled();
                monitor.showLiveScan(
                        res.report,
                        BuildConfig.DEBUG,
                        res.securityBypass,
                        currentFlag,
                        res.l1Present,
                        res.bioStrong,
                        res.bioBacking,
                        res.scanMs
                );
                int passed = monitor.getState().getPassedProbeCount();
                boolean threat = monitor.getState().threatActive;
                if (res.securityBypass) {
                    tvBadge.setText("0/6 RỦI RO");
                    tvBadge.setBackgroundResource(R.drawable.bg_pill_red);
                    tvBadge.setTextColor(COLOR_RED);
                } else if (threat) {
                    tvBadge.setText("CẢNH BÁO!");
                    tvBadge.setBackgroundResource(R.drawable.bg_pill_red);
                    tvBadge.setTextColor(COLOR_RED);
                } else if (passed < 6) {
                    tvBadge.setText(passed + "/6 AN TOÀN");
                    tvBadge.setBackgroundResource(R.drawable.bg_pill_amber);
                    tvBadge.setTextColor(COLOR_AMBER);
                } else {
                    tvBadge.setText(passed + "/6 AN TOÀN");
                    tvBadge.setBackgroundResource(R.drawable.bg_pill_green);
                    tvBadge.setTextColor(COLOR_GREEN);
                }
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    private static void applyWindowFlagSecure(@Nullable Window window, boolean enable) {
        if (window == null) {
            return;
        }
        if (enable) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private static final class ScanSnapshot {
        final SecurityEnvironmentChecker.SecurityAuditReport report;
        final boolean securityBypass;
        final boolean bioStrong;
        final String bioBacking;
        final boolean l1Present;
        final long scanMs;

        ScanSnapshot(SecurityEnvironmentChecker.SecurityAuditReport report,
                     boolean securityBypass, boolean bioStrong, String bioBacking,
                     boolean l1Present, long scanMs) {
            this.report = report;
            this.securityBypass = securityBypass;
            this.bioStrong = bioStrong;
            this.bioBacking = bioBacking;
            this.l1Present = l1Present;
            this.scanMs = scanMs;
        }
    }
}
