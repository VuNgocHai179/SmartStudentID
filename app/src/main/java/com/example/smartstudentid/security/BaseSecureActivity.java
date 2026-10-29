package com.example.smartstudentid.security;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Base activity enforcing MASVS-PLATFORM UI security (SEC-05).
 * Automatically sets WindowManager.LayoutParams.FLAG_SECURE to prevent screenshots,
 * screen recording, and sensitive information leakage in the Recent Apps switcher.
 */
public abstract class BaseSecureActivity extends AppCompatActivity {

    private boolean flagSecureEnabled = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyFlagSecure(true);
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
