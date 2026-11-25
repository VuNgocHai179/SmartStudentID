package com.example.smartstudentid.ui.widget;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.smartstudentid.BuildConfig;
import com.example.smartstudentid.R;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.BiometricKeyManager;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.example.smartstudentid.security.SecurityEnvironmentChecker;
import com.example.smartstudentid.ui.auth.LoginPinActivity;
import com.google.android.material.button.MaterialButton;

/**
 * Reusable modal popup dialog displaying the 6-probe Runtime Security Defense Monitor
 * and interactive verification actions (Rescan, Toggle FLAG_SECURE, Simulate Frida Hook,
 * Simulate 120s Background Lock).
 */
public final class SecurityDefensePopupDialog {

    private static final int COLOR_GREEN = 0xFF059669;
    private static final int COLOR_RED = 0xFFDC2626;
    private static final int COLOR_AMBER = 0xFFD97706;

    private SecurityDefensePopupDialog() {
    }

    public static void show(@NonNull BaseSecureActivity activity) {
        show(activity, null);
    }

    public static void show(@NonNull BaseSecureActivity activity,
                            @Nullable Runnable onRawAuditClick) {
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
        MaterialButton btnRescan = root.findViewById(R.id.btnPopupDefenseRescan);
        MaterialButton btnToggleFlag = root.findViewById(R.id.btnPopupToggleFlagSecure);
        MaterialButton btnSimFrida = root.findViewById(R.id.btnPopupSimFrida);
        MaterialButton btnSimBgLock = root.findViewById(R.id.btnPopupSimBgLock);
        MaterialButton btnRawAudit = root.findViewById(R.id.btnPopupRawAudit);

        btnClose.setOnClickListener(v -> dialog.dismiss());

        btnToggleFlag.setText(activity.isFlagSecureEnabled() ? "Tắt FLAG_SECURE" : "Bật FLAG_SECURE");
        btnRescan.setOnClickListener(v -> runLiveScan(activity, dialog, monitor, tvBadge, btnToggleFlag));

        btnToggleFlag.setOnClickListener(v -> {
            boolean next = !activity.isFlagSecureEnabled();
            activity.applyFlagSecure(next);
            applyWindowFlagSecure(dialog.getWindow(), next);
            btnToggleFlag.setText(next ? "Tắt FLAG_SECURE" : "Bật FLAG_SECURE");
            monitor.onFlagSecureToggled(next);
            Toast.makeText(
                    activity,
                    next
                            ? "Đã bật FLAG_SECURE — thử chụp màn hình để kiểm chứng!"
                            : "Đã tắt FLAG_SECURE cho chế độ chiếu demo.",
                    Toast.LENGTH_SHORT
            ).show();
        });

        btnSimFrida.setOnClickListener(v -> {
            Context appCtx = activity.getApplicationContext();
            long startNs = System.nanoTime();
            SecurityEnvironmentChecker.SecurityAuditReport simReport =
                    new SecurityEnvironmentChecker.SecurityAuditReport(
                            false, false, false, true, false, true, true, "");
            SecurityEnvironmentChecker.enforceActiveDefense(appCtx, simReport, BuildConfig.DEBUG);
            long reactionMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);

            monitor.showSimulatedFridaDefense(activity.isFlagSecureEnabled(), reactionMs);
            tvBadge.setText("ĐÃ CHẶN HOOK");
            tvBadge.setBackgroundResource(R.drawable.bg_pill_red);
            tvBadge.setTextColor(COLOR_RED);
            Toast.makeText(
                    activity,
                    "Phản ứng chủ động: đã xóa L1 RAM & PIN tạm trong " + reactionMs + " ms!",
                    Toast.LENGTH_SHORT
            ).show();
        });

        btnSimBgLock.setOnClickListener(v -> {
            new PinSessionManager(activity).lockSessionAndEvictRam();
            monitor.showBackgroundLockTriggered(activity.isFlagSecureEnabled());
            tvBadge.setText("ĐÃ KHÓA NỀN");
            tvBadge.setBackgroundResource(R.drawable.bg_pill_amber);
            tvBadge.setTextColor(COLOR_AMBER);
            dialog.dismiss();

            Intent intent = new Intent(activity, LoginPinActivity.class);
            intent.putExtra(LoginPinActivity.EXTRA_SESSION_LOCKED_TIMEOUT, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            activity.finish();
        });

        if (onRawAuditClick != null) {
            btnRawAudit.setVisibility(View.VISIBLE);
            btnRawAudit.setOnClickListener(v -> {
                dialog.dismiss();
                onRawAuditClick.run();
            });
        }

        dialog.show();
        runLiveScan(activity, dialog, monitor, tvBadge, btnToggleFlag);
    }

    private static void runLiveScan(BaseSecureActivity activity,
                                    Dialog dialog,
                                    RuntimeDefenseMonitorView monitor,
                                    TextView tvBadge,
                                    MaterialButton btnToggleFlag) {
        final Context appCtx = activity.getApplicationContext();
        final boolean flagSecureOn = activity.isFlagSecureEnabled();
        tvBadge.setText("Đang quét...");
        btnToggleFlag.setText(flagSecureOn ? "Tắt FLAG_SECURE" : "Bật FLAG_SECURE");

        ApduThreadPool.getInstance().executeAsyncOnPool(() -> {
            long startNs = System.nanoTime();
            SecurityEnvironmentChecker.SecurityAuditReport report =
                    SecurityEnvironmentChecker.inspectEnvironment(appCtx);
            boolean bioStrong = BiometricKeyManager.isBiometricStrongAvailable(appCtx);
            String bioBacking = new BiometricKeyManager(appCtx).getBackingStoreLabel();
            boolean l1Present = EncryptedCacheManager.getInstance(appCtx).hasL1ProfileCached();
            long scanMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            return new ScanSnapshot(report, bioStrong, bioBacking, l1Present, scanMs);
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
                        currentFlag,
                        res.l1Present,
                        res.bioStrong,
                        res.bioBacking,
                        res.scanMs
                );
                int passed = monitor.getState().getPassedProbeCount();
                boolean threat = monitor.getState().threatActive;
                tvBadge.setText(threat ? "CẢNH BÁO!" : passed + "/6 AN TOÀN");
                tvBadge.setBackgroundResource(threat ? R.drawable.bg_pill_red : R.drawable.bg_pill_green);
                tvBadge.setTextColor(threat ? COLOR_RED : COLOR_GREEN);
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
        final boolean bioStrong;
        final String bioBacking;
        final boolean l1Present;
        final long scanMs;

        ScanSnapshot(SecurityEnvironmentChecker.SecurityAuditReport report,
                     boolean bioStrong, String bioBacking,
                     boolean l1Present, long scanMs) {
            this.report = report;
            this.bioStrong = bioStrong;
            this.bioBacking = bioBacking;
            this.l1Present = l1Present;
            this.scanMs = scanMs;
        }
    }
}
