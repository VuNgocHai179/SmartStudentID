package com.example.smartstudentid.security;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.smartstudentid.BuildConfig;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.ui.auth.LoginPinActivity;

/**
 * Base activity enforcing Light Mode and MASVS-PLATFORM UI security (SEC-05).
 * Automatically sets WindowManager.LayoutParams.FLAG_SECURE in Release builds to prevent
 * screenshots, screen recording, and sensitive information leakage in Recent Apps,
 * while keeping Debug builds mirror-friendly. Also enforces background session auto-lock,
 * runtime anti-hooking / APK integrity defense, and tapjacking touch filtering.
 */
public abstract class BaseSecureActivity extends AppCompatActivity {

    private static int startedActivityCount = 0;
    private static long appBackgroundedAtElapsedMs = 0L;

    private boolean flagSecureEnabled = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        configureEdgeToEdgeSystemBars();
        // Enforce FLAG_SECURE automatically in Release; allow live mirroring in Debug builds.
        applyFlagSecure(!BuildConfig.DEBUG);
    }

    @Override
    protected void onStart() {
        super.onStart();
        boolean returningFromBackground = (startedActivityCount == 0 && appBackgroundedAtElapsedMs > 0L);
        long bgStart = appBackgroundedAtElapsedMs;
        startedActivityCount++;
        if (returningFromBackground) {
            appBackgroundedAtElapsedMs = 0L;
            if (PinSessionManager.shouldAutoLockAfterBackground(
                    bgStart,
                    SystemClock.elapsedRealtime(),
                    PinSessionManager.BACKGROUND_LOCK_TIMEOUT_MS)
                    && requiresActiveSessionLock()) {
                new PinSessionManager(this).lockSessionAndEvictRam();
                onSessionAutoLocked();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ApduThreadPool.getInstance().executeAsyncOnPool(
                () -> SecurityEnvironmentChecker.inspectEnvironment(getApplicationContext()),
                new ApduThreadPool.TaskCallback<SecurityEnvironmentChecker.SecurityAuditReport>() {
                    @Override
                    public void onComplete(SecurityEnvironmentChecker.SecurityAuditReport report,
                                           long mainBlockedMs, long totalMs) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (SecurityEnvironmentChecker.enforceActiveDefense(
                                BaseSecureActivity.this, report, BuildConfig.DEBUG)) {
                            Toast.makeText(
                                    BaseSecureActivity.this,
                                    "Cảnh báo bảo mật: " + report.buildThreatSummary(BuildConfig.DEBUG),
                                    Toast.LENGTH_LONG
                            ).show();
                            if (requiresActiveSessionLock()) {
                                onSessionAutoLocked();
                            }
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                    }
                }
        );
    }

    @Override
    protected void onStop() {
        startedActivityCount = Math.max(0, startedActivityCount - 1);
        if (isChangingConfigurations()) {
            appBackgroundedAtElapsedMs = 0L;
        } else if (startedActivityCount == 0) {
            appBackgroundedAtElapsedMs = SystemClock.elapsedRealtime();
        }
        super.onStop();
    }

    /**
     * Subclasses holding authenticated student data override this to return true so returning from
     * background after timeout or detecting a runtime threat locks the session.
     */
    protected boolean requiresActiveSessionLock() {
        return false;
    }

    /**
     * Redirects back to the lock screen when an active session is auto-locked.
     */
    protected void onSessionAutoLocked() {
        Intent intent = new Intent(this, LoginPinActivity.class);
        intent.putExtra(LoginPinActivity.EXTRA_SESSION_LOCKED_TIMEOUT, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    /**
     * Drops touch events while the window is obscured by another overlay (Tapjacking defense).
     */
    protected static void applyTapjackingProtection(View... views) {
        if (views == null) {
            return;
        }
        for (View view : views) {
            if (view != null) {
                view.setFilterTouchesWhenObscured(true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void configureEdgeToEdgeSystemBars() {
        // The screen background now shows through the system bars, matching the safe-area layout.
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        } else {
            // Android 7.x cannot show dark navigation icons, so keep a dark bar there.
            getWindow().setNavigationBarColor(Color.parseColor("#0F172A"));
        }

        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(params);
        }
    }

    /**
     * Pushes a view inside the system safe area. Call this after setContentView().
     *
     * @param view          root view, header bar, or bottom navigation bar that should move inward
     * @param includeTop    true for headers / scroll content that must clear the status bar and camera cutout
     * @param includeBottom true for bottom bars / scroll content that must clear the gesture bar
     */
    protected void applySafeAreaPadding(View view, boolean includeTop, boolean includeBottom) {
        if (view == null) {
            return;
        }

        final int initialLeft = view.getPaddingLeft();
        final int initialTop = view.getPaddingTop();
        final int initialRight = view.getPaddingRight();
        final int initialBottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets safeInsets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());

            v.setPadding(
                    initialLeft + safeInsets.left,
                    initialTop + (includeTop ? safeInsets.top : 0),
                    initialRight + safeInsets.right,
                    initialBottom + (includeBottom ? safeInsets.bottom : 0)
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * Enables or disables FLAG_SECURE on the current activity window.
     */
    public void applyFlagSecure(boolean enable) {
        this.flagSecureEnabled = enable;
        if (enable) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
            );
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    public boolean isFlagSecureEnabled() {
        return flagSecureEnabled;
    }
}
