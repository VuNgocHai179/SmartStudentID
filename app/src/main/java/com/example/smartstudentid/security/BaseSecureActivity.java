package com.example.smartstudentid.security;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Base activity enforcing Light Mode and MASVS-PLATFORM UI security (SEC-05).
 * Automatically sets WindowManager.LayoutParams.FLAG_SECURE to prevent screenshots,
 * screen recording, and sensitive information leakage in the Recent Apps switcher.
 *
 * It also enables edge-to-edge rendering and exposes {@link #applySafeAreaPadding(View, boolean, boolean)}
 * so screens can keep their headers and bottom bars outside the status bar, notch/punch-hole camera,
 * and gesture navigation area (the Android equivalent of Unity's Screen.safeArea).
 */
public abstract class BaseSecureActivity extends AppCompatActivity {

    private boolean flagSecureEnabled = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        configureEdgeToEdgeSystemBars();
        applyFlagSecure(true);
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
