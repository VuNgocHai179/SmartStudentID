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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.example.smartstudentid.R;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.ui.demo.TopicDemoFragment;
import com.example.smartstudentid.ui.widget.LivePerfHudView;
import com.google.android.material.button.MaterialButton;

/**
 * Main Student Activity hosting the 5 isolated Optimization & Security Topic Demo tabs (§1–§5)
 * directly on the bottom navigation bar so each topic can be demonstrated independently.
 */
public class StudentMainActivity extends BaseSecureActivity {

    private static final int TOPIC_TAB_COUNT = 5;

    private TextView tvHeaderGreeting;
    private TextView tvHeaderPerfBadge;
    private ViewPager2 viewPagerStudent;
    private LivePerfHudView livePerfHud;
    private MaterialButton btnTogglePerfHud;

    private final TextView[] navLabels = new TextView[TOPIC_TAB_COUNT];
    private final ImageView[] navIcons = new ImageView[TOPIC_TAB_COUNT];
    private final FrameLayout[] navIconBgs = new FrameLayout[TOPIC_TAB_COUNT];

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

        int[] tabIds = {R.id.navTab1, R.id.navTab2, R.id.navTab3, R.id.navTab4, R.id.navTab5};
        int[] labelIds = {R.id.tvNavLabel1, R.id.tvNavLabel2, R.id.tvNavLabel3, R.id.tvNavLabel4, R.id.tvNavLabel5};
        int[] iconIds = {R.id.ivNavIcon1, R.id.ivNavIcon2, R.id.ivNavIcon3, R.id.ivNavIcon4, R.id.ivNavIcon5};
        int[] bgIds = {R.id.navIconBg1, R.id.navIconBg2, R.id.navIconBg3, R.id.navIconBg4, R.id.navIconBg5};

        for (int i = 0; i < TOPIC_TAB_COUNT; i++) {
            final int tabIndex = i;
            navLabels[i] = findViewById(labelIds[i]);
            navIcons[i] = findViewById(iconIds[i]);
            navIconBgs[i] = findViewById(bgIds[i]);
            LinearLayout tab = findViewById(tabIds[i]);
            tab.setOnClickListener(v -> viewPagerStudent.setCurrentItem(tabIndex, true));
        }

        livePerfHud = findViewById(R.id.livePerfHud);
        btnTogglePerfHud = findViewById(R.id.btnTogglePerfHud);
        updateHudToggleButtonStyle(true);
        btnTogglePerfHud.setOnClickListener(v -> {
            boolean show = livePerfHud.getVisibility() != View.VISIBLE;
            livePerfHud.setVisibility(show ? View.VISIBLE : View.GONE);
            updateHudToggleButtonStyle(show);
        });

        MaterialButton btnHeaderSwitchCard = findViewById(R.id.btnHeaderSwitchCard);
        btnHeaderSwitchCard.setOnClickListener(v -> logoutCurrentCard());

        updateBottomNavHighlight(0);
    }

    private void updateHudToggleButtonStyle(boolean hudVisible) {
        int bg = ContextCompat.getColor(this, hudVisible ? R.color.brand_600 : R.color.brand_50);
        int fg = ContextCompat.getColor(this, hudVisible ? R.color.white : R.color.brand_600);
        btnTogglePerfHud.setBackgroundTintList(ColorStateList.valueOf(bg));
        btnTogglePerfHud.setTextColor(fg);
    }

    public void logoutCurrentCard() {
        new com.example.smartstudentid.security.PinSessionManager(this).clearSession();
        android.widget.Toast.makeText(this, "Đã đăng xuất thẻ sinh viên", android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    private void setupViewPager() {
        viewPagerStudent.setOffscreenPageLimit(1);
        viewPagerStudent.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return TopicDemoFragment.newInstance(position);
            }

            @Override
            public int getItemCount() {
                return TOPIC_TAB_COUNT;
            }
        });

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
        for (int i = 0; i < TOPIC_TAB_COUNT; i++) {
            applyNavHighlight(i == activeTab, navLabels[i], navIcons[i], navIconBgs[i]);
        }
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
            tvHeaderPerfBadge.setVisibility(View.GONE);
        } else {
            tvHeaderPerfBadge.setText("● Xác thực RSA-1024 • " + loadMs + " ms");
            tvHeaderPerfBadge.setVisibility(View.GONE);
        }
    }
}
