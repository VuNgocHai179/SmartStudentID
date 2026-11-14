package com.example.smartstudentid.ui.demo;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.example.smartstudentid.R;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * "Phòng demo" tách riêng từng chuyên đề tối ưu §1–§5: mỗi tab là một trang demo độc lập,
 * chỉ chứa thao tác và số đo của đúng chuyên đề đó để trình bày trước giảng viên.
 */
public class TopicDemoActivity extends BaseSecureActivity {

    public static final String EXTRA_TOPIC = "topic_index";
    private static final int TOPIC_COUNT = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_topic_demo);
        applySafeAreaPadding(findViewById(R.id.demoHeader), true, false);

        findViewById(R.id.btnCloseDemo).setOnClickListener(v -> finish());

        ViewPager2 pager = findViewById(R.id.demoPager);
        pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return TopicDemoFragment.newInstance(position);
            }

            @Override
            public int getItemCount() {
                return TOPIC_COUNT;
            }
        });

        TabLayout tabs = findViewById(R.id.demoTabs);
        new TabLayoutMediator(tabs, pager,
                (tab, position) -> tab.setText("§" + (position + 1))).attach();

        int start = getIntent().getIntExtra(EXTRA_TOPIC, 0);
        pager.setCurrentItem(Math.max(0, Math.min(TOPIC_COUNT - 1, start)), false);
    }
}
