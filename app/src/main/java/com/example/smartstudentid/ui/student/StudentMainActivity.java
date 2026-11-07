package com.example.smartstudentid.ui.student;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.example.smartstudentid.R;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.google.android.material.button.MaterialButton;

/**
 * Main Student Activity hosting the 4 Light Mode functional tabs:
 * - Tab 0: CardProfileFragment (Apple Wallet / VNeID Digital Card, Avatar WebP, Profile & PIN)
 * - Tab 1: DynamicQrFragment (30-Second Anti-Replay Dynamic QR & RSA-1024 Attendance Check-In)
 * - Tab 2: LibraryLabFragment (Atomic Book Borrow/Return, Lab Door Access & 10-Entry Circular Log)
 * - Tab 3: SecurityOptLabFragment (Before/After Optimization Switch, Benchmark & 5 MASVS Security Layers)
 */
public class StudentMainActivity extends BaseSecureActivity {

    private TextView tvHeaderGreeting;
    private TextView tvHeaderPerfBadge;
    private ViewPager2 viewPagerStudent;

    private TextView tvNavLabelCard;
    private TextView tvNavLabelQr;
    private TextView tvNavLabelLibrary;
    private TextView tvNavLabelSecurityOpt;

    private ImageView ivNavIconCard;
    private ImageView ivNavIconQr;
    private ImageView ivNavIconLibrary;
    private ImageView ivNavIconSecurityOpt;

    private FrameLayout navIconBgCard;
    private FrameLayout navIconBgQr;
    private FrameLayout navIconBgLibrary;
    private FrameLayout navIconBgSecurityOpt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student_main);
        applySafeAreaPadding(findViewById(R.id.headerBar), true, false);
        applySafeAreaPadding(findViewById(R.id.bottomNavBar), false, true);

        bindViews();
        setupViewPager();
        refreshHeaderFromCache();
    }

    private void bindViews() {
        tvHeaderGreeting = findViewById(R.id.tvHeaderGreeting);
        tvHeaderPerfBadge = findViewById(R.id.tvHeaderPerfBadge);
        viewPagerStudent = findViewById(R.id.viewPagerStudent);

        tvNavLabelCard = findViewById(R.id.tvNavLabelCard);
        tvNavLabelQr = findViewById(R.id.tvNavLabelQr);
        tvNavLabelLibrary = findViewById(R.id.tvNavLabelLibrary);
        tvNavLabelSecurityOpt = findViewById(R.id.tvNavLabelSecurityOpt);

        ivNavIconCard = findViewById(R.id.ivNavIconCard);
        ivNavIconQr = findViewById(R.id.ivNavIconQr);
        ivNavIconLibrary = findViewById(R.id.ivNavIconLibrary);
        ivNavIconSecurityOpt = findViewById(R.id.ivNavIconSecurityOpt);

        navIconBgCard = findViewById(R.id.navIconBgCard);
        navIconBgQr = findViewById(R.id.navIconBgQr);
        navIconBgLibrary = findViewById(R.id.navIconBgLibrary);
        navIconBgSecurityOpt = findViewById(R.id.navIconBgSecurityOpt);

        LinearLayout navTabCard = findViewById(R.id.navTabCard);
        LinearLayout navTabQr = findViewById(R.id.navTabQr);
        LinearLayout navTabLibrary = findViewById(R.id.navTabLibrary);
        LinearLayout navTabSecurityOpt = findViewById(R.id.navTabSecurityOpt);
        MaterialButton btnHeaderSwitchCard = findViewById(R.id.btnHeaderSwitchCard);

        navTabCard.setOnClickListener(v -> viewPagerStudent.setCurrentItem(0, true));
        navTabQr.setOnClickListener(v -> viewPagerStudent.setCurrentItem(1, true));
        navTabLibrary.setOnClickListener(v -> viewPagerStudent.setCurrentItem(2, true));
        navTabSecurityOpt.setOnClickListener(v -> viewPagerStudent.setCurrentItem(3, true));

        btnHeaderSwitchCard.setOnClickListener(v -> finish());

        updateBottomNavHighlight(0);
    }

    private void setupViewPager() {
        // Keep one page either side in memory: smooth swiping without holding all 4 fragments.
        viewPagerStudent.setOffscreenPageLimit(1);
        viewPagerStudent.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 0:
                        return new CardProfileFragment();
                    case 1:
                        return new DynamicQrFragment();
                    case 2:
                        return new LibraryLabFragment();
                    default:
                        return new SecurityOptLabFragment();
                }
            }

            @Override
            public int getItemCount() {
                return 4;
            }
        });

        // Subtle depth while swiping (cheap property animation on the compositor).
        viewPagerStudent.setPageTransformer((page, position) -> {
            float distance = Math.abs(position);
            page.setAlpha(1f - 0.18f * distance);
            page.setScaleX(1f - 0.03f * distance);
            page.setScaleY(1f - 0.03f * distance);
        });

        viewPagerStudent.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateBottomNavHighlight(position);
            }
        });
    }

    private void updateBottomNavHighlight(int activeTab) {
        applyNavHighlight(activeTab == 0, tvNavLabelCard, ivNavIconCard, navIconBgCard);
        applyNavHighlight(activeTab == 1, tvNavLabelQr, ivNavIconQr, navIconBgQr);
        applyNavHighlight(activeTab == 2, tvNavLabelLibrary, ivNavIconLibrary, navIconBgLibrary);
        applyNavHighlight(activeTab == 3, tvNavLabelSecurityOpt, ivNavIconSecurityOpt, navIconBgSecurityOpt);
    }

    private void applyNavHighlight(boolean active, TextView label, ImageView icon, FrameLayout iconBackground) {
        int color = Color.parseColor(active ? "#2563EB" : "#64748B");

        label.setTextColor(color);
        label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        icon.setImageTintList(ColorStateList.valueOf(color));
        icon.setSelected(active);
        iconBackground.setBackgroundResource(active ? R.drawable.bg_nav_active : 0);
        icon.animate()
                .scaleX(active ? 1.08f : 1f)
                .scaleY(active ? 1.08f : 1f)
                .setDuration(160L)
                .start();
    }

    /**
     * Updates the top greeting bar whenever the student profile is loaded or modified.
     */
    public void refreshHeaderFromCache() {
        StudentCardRecord profile = EncryptedCacheManager.getInstance(this).getStudentProfile();
        if (profile != null && profile.getFullName() != null) {
            tvHeaderGreeting.setText(profile.getFullName() + " • " + profile.getClassName());
        }

        long loadMs = getIntent().getLongExtra("LOAD_TIME_MS", 14L);
        boolean fromCache = getIntent().getBooleanExtra("FROM_CACHE", true);
        if (fromCache) {
            tvHeaderPerfBadge.setText("● Phiên PIN bảo mật • " + loadMs + " ms");
        } else {
            tvHeaderPerfBadge.setText("● Xác thực RSA-1024 • " + loadMs + " ms");
        }
    }
}
