package com.example.smartstudentid.ui.student;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.optimization.ChaosInjectionManager;
import com.example.smartstudentid.optimization.DemoMetrics;
import com.example.smartstudentid.optimization.ImageOptimizer;
import com.example.smartstudentid.optimization.LocalCacheManager;
import com.example.smartstudentid.security.CryptoManager;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.example.smartstudentid.security.SecurityEnvironmentChecker;
import com.example.smartstudentid.ui.widget.RealtimeTopicChartView;
import com.example.smartstudentid.ui.widget.UiHeartbeatView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Tab 4 (Demo): Unified Performance & Security Lab combining the live Chaos Injection Tool,
 * inline §1–§5 Topic Demos (real actions, realtime charts, Before/After tables),
 * 4-step automated remediation pipeline, and OWASP MASVS security auditor.
 */
public class SecurityOptLabFragment extends Fragment {

    private static final int TOPIC_BOTTLENECK = 0;
    private static final int TOPIC_THREADING = 1;
    private static final int TOPIC_CACHE = 2;
    private static final int TOPIC_IMAGE = 3;
    private static final int TOPIC_STARTUP = 4;
    private static final int TOPIC_COUNT = 5;

    private static final String[] TOPIC_TAB_LABELS = {
            "§1 Nghẽn", "§2 60FPS", "§3 Cache", "§4 WebP", "§5 PIN"
    };

    private static final String SLATE = "#475569";
    private static final String SLATE_MUTED = "#64748B";
    private static final String BLUE = "#2563EB";
    private static final String RED = "#DC2626";
    private static final String GREEN = "#059669";

    private final ChaosInjectionManager chaosManager = ChaosInjectionManager.getInstance();
    private final Handler autoFixHandler = new Handler(Looper.getMainLooper());

    @SuppressWarnings("unchecked")
    private final Map<String, String>[] beforeByTopic = new Map[TOPIC_COUNT];
    @SuppressWarnings("unchecked")
    private final Map<String, String>[] afterByTopic = new Map[TOPIC_COUNT];

    private LocalCacheManager localCacheManager;
    private EncryptedCacheManager encryptedCache;
    private PinSessionManager pinSessionManager;
    private ActivityResultLauncher<PickVisualMediaRequest> imagePicker;

    // Hero & Chaos controls
    private LinearLayout layoutHeroContainer;
    private ImageView ivHeroIcon;
    private TextView tvSecurityHeroStatus;
    private TextView tvSecurityHeroSubtitle;
    private TextView tvBadgeFps;
    private TextView tvBadgeCache;
    private TextView tvBadgeWebp;
    private TextView tvBadgeMasvs;
    private UiHeartbeatView heartbeatLab;
    private TextView tvChaosToolStatus;
    private MaterialButton btnInjectAllIssues;
    private MaterialButton btnRunAutoFixPipeline;

    // Inline §1–§5 Topic Demo views
    private TabLayout topicTabs;
    private TextView tvTopicBadge;
    private TextView tvTopicTitle;
    private TextView tvTopicGoal;
    private TextView tvTopicHint;
    private View cardTopicMode;
    private SwitchCompat switchDemoMode;
    private TextView tvModeHint;
    private MaterialButton btnQuickFixStep;
    private LinearLayout panelStudent;
    private LinearLayout panelImage;
    private LinearLayout panelPin;
    private ImageView demoAvatar;
    private ImageView demoImagePreview;
    private TextView demoName;
    private TextView demoStudentId;
    private TextView demoDept;
    private TextView demoSourceBadge;
    private TextView demoImageInfo;
    private EditText demoPinInput;
    private MaterialButton btnDemoAction;
    private MaterialButton btnPickImage;
    private MaterialButton btnResetCache;
    private ProgressBar demoProgress;
    private TextView tvDemoStatus;
    private TextView tvRealtimeChartTitle;
    private TextView tvRealtimeChartSubtitle;
    private RealtimeTopicChartView demoRealtimeChart;
    private TextView tvComparisonTitle;
    private LinearLayout llComparison;

    // Security audit
    private TextView tvSecurityAuditSummary;

    private int currentTopic = TOPIC_BOTTLENECK;
    private boolean busy = false;
    private boolean pipelineRunning = false;
    private boolean suppressSwitchListener = false;
    private boolean suppressTabListener = false;
    private int lastMeasuredFps = 60;
    private Uri pickedUri;
    private volatile byte[] sampleBytes;

    private final ChaosInjectionManager.Listener chaosListener = () -> {
        if (isAdded()) {
            renderGlobalState();
        }
    };

    private final ApduClient.TelemetryListener apduTelemetryListener = (ins, elapsedMs) -> {
        RealtimeTopicChartView chart = demoRealtimeChart;
        if (chart != null) {
            chart.post(() -> chart.onApduFrame(ins, elapsedMs));
        }
    };

    public SecurityOptLabFragment() {
        for (int i = 0; i < TOPIC_COUNT; i++) {
            beforeByTopic[i] = new LinkedHashMap<>();
            afterByTopic[i] = new LinkedHashMap<>();
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imagePicker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                pickedUri = uri;
                loadPickedPreview(uri);
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_security_opt_lab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        localCacheManager = new LocalCacheManager(requireContext());
        encryptedCache = EncryptedCacheManager.getInstance(requireContext());
        pinSessionManager = new PinSessionManager(requireContext());
        view.setVerticalScrollBarEnabled(false);

        bindViews(view);
        setupTopicTabs();
        wireActions();

        chaosManager.addListener(chaosListener);
        applyTopicSelection(TOPIC_BOTTLENECK, true);
        renderGlobalState();
    }

    @Override
    public void onResume() {
        super.onResume();
        ApduClient.getInstance().setTelemetryListener(apduTelemetryListener);
        renderGlobalState();
    }

    @Override
    public void onPause() {
        super.onPause();
        ApduClient.getInstance().clearTelemetryListener(apduTelemetryListener);
    }

    @Override
    public void onDestroyView() {
        autoFixHandler.removeCallbacksAndMessages(null);
        pipelineRunning = false;
        busy = false;
        chaosManager.removeListener(chaosListener);
        ApduClient.getInstance().clearTelemetryListener(apduTelemetryListener);
        if (heartbeatLab != null) {
            heartbeatLab.setStatsListener(null);
        }
        demoRealtimeChart = null;
        super.onDestroyView();
    }

    private void bindViews(View root) {
        layoutHeroContainer = root.findViewById(R.id.layoutHeroContainer);
        ivHeroIcon = root.findViewById(R.id.ivHeroIcon);
        tvSecurityHeroStatus = root.findViewById(R.id.tvSecurityHeroStatus);
        tvSecurityHeroSubtitle = root.findViewById(R.id.tvSecurityHeroSubtitle);
        tvBadgeFps = root.findViewById(R.id.tvBadgeFps);
        tvBadgeCache = root.findViewById(R.id.tvBadgeCache);
        tvBadgeWebp = root.findViewById(R.id.tvBadgeWebp);
        tvBadgeMasvs = root.findViewById(R.id.tvBadgeMasvs);
        heartbeatLab = root.findViewById(R.id.heartbeatLab);
        tvChaosToolStatus = root.findViewById(R.id.tvChaosToolStatus);
        btnInjectAllIssues = root.findViewById(R.id.btnInjectAllIssues);
        btnRunAutoFixPipeline = root.findViewById(R.id.btnRunAutoFixPipeline);

        topicTabs = root.findViewById(R.id.topicTabs);
        tvTopicBadge = root.findViewById(R.id.tvTopicBadge);
        tvTopicTitle = root.findViewById(R.id.tvTopicTitle);
        tvTopicGoal = root.findViewById(R.id.tvTopicGoal);
        tvTopicHint = root.findViewById(R.id.tvTopicHint);
        cardTopicMode = root.findViewById(R.id.cardTopicMode);
        switchDemoMode = root.findViewById(R.id.switchDemoMode);
        tvModeHint = root.findViewById(R.id.tvModeHint);
        btnQuickFixStep = root.findViewById(R.id.btnQuickFixStep);
        panelStudent = root.findViewById(R.id.panelStudent);
        panelImage = root.findViewById(R.id.panelImage);
        panelPin = root.findViewById(R.id.panelPin);
        demoAvatar = root.findViewById(R.id.demoAvatar);
        demoImagePreview = root.findViewById(R.id.demoImagePreview);
        demoName = root.findViewById(R.id.demoName);
        demoStudentId = root.findViewById(R.id.demoStudentId);
        demoDept = root.findViewById(R.id.demoDept);
        demoSourceBadge = root.findViewById(R.id.demoSourceBadge);
        demoImageInfo = root.findViewById(R.id.demoImageInfo);
        demoPinInput = root.findViewById(R.id.demoPinInput);
        btnDemoAction = root.findViewById(R.id.btnDemoAction);
        btnPickImage = root.findViewById(R.id.btnPickImage);
        btnResetCache = root.findViewById(R.id.btnResetCache);
        demoProgress = root.findViewById(R.id.demoProgress);
        tvDemoStatus = root.findViewById(R.id.tvDemoStatus);
        tvRealtimeChartTitle = root.findViewById(R.id.tvRealtimeChartTitle);
        tvRealtimeChartSubtitle = root.findViewById(R.id.tvRealtimeChartSubtitle);
        demoRealtimeChart = root.findViewById(R.id.demoRealtimeChart);
        tvComparisonTitle = root.findViewById(R.id.tvComparisonTitle);
        llComparison = root.findViewById(R.id.llComparison);

        tvSecurityAuditSummary = root.findViewById(R.id.tvSecurityAuditSummary);
        root.findViewById(R.id.btnInspectCipherAndApdu).setOnClickListener(v ->
                showCiphertextAndApduInspectorDialog());
    }

    private void setupTopicTabs() {
        topicTabs.removeAllTabs();
        for (String label : TOPIC_TAB_LABELS) {
            topicTabs.addTab(topicTabs.newTab().setText(label));
        }
        topicTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (suppressTabListener) return;
                applyTopicSelection(tab.getPosition(), false);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void wireActions() {
        btnInjectAllIssues.setOnClickListener(v -> {
            cancelPipeline();
            busy = false;
            endRun();
            chaosManager.injectAllIssues(requireContext());
            seedDegradedBaselines();
            selectTabAndSwitch(TOPIC_THREADING);
            if (demoRealtimeChart != null) {
                demoRealtimeChart.recordThreadingResult(false, 95L, 95L);
            }
            tvDemoStatus.setText("⚠ Đã kích hoạt toàn bộ 4 lỗi: tụt FPS (~10 FPS), Bypass Cache, Memory Churn & Lộ PIN!");
            tvDemoStatus.setTextColor(Color.parseColor(RED));
            Toast.makeText(requireContext(),
                    "Đã tiêm 4 lỗi hiệu năng & bảo mật — quan sát FPS tụt đỏ!",
                    Toast.LENGTH_SHORT).show();
        });

        btnRunAutoFixPipeline.setOnClickListener(v -> startAutoFixPipeline());

        switchDemoMode.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchListener) return;
            cancelPipeline();
            applyChaosForTopic(currentTopic, checked);
            updateModeCardUi();
            if (!busy) {
                runTopicAction(currentTopic, checked);
            }
        });

        btnQuickFixStep.setOnClickListener(v -> {
            cancelPipeline();
            boolean targetAfter = isTopicDegraded(currentTopic);
            switchDemoMode.setChecked(targetAfter);
        });

        btnDemoAction.setOnClickListener(v -> {
            cancelPipeline();
            runCurrentMode();
        });

        btnPickImage.setOnClickListener(v -> imagePicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));

        btnResetCache.setOnClickListener(v -> resetCache());

        heartbeatLab.setStatsListener((fps, worstGapMs, jank) -> {
            if (!isAdded()) return;
            lastMeasuredFps = fps;
            if (tvBadgeFps != null) {
                tvBadgeFps.setText((chaosManager.isUiThreadJankActive() || jank)
                        ? Math.min(fps, 15) + " FPS"
                        : fps + " FPS");
            }
        });
    }

    // ------------------------------------------------------------------
    // Topic selection & Chaos synchronization
    // ------------------------------------------------------------------

    private void selectTabAndSwitch(int topic) {
        if (topicTabs != null && topic >= 0 && topic < topicTabs.getTabCount()) {
            TabLayout.Tab tab = topicTabs.getTabAt(topic);
            if (tab != null && !tab.isSelected()) {
                suppressTabListener = true;
                tab.select();
                suppressTabListener = false;
            }
        }
        applyTopicSelection(topic, false);
    }

    private void applyTopicSelection(int topic, boolean forceInit) {
        boolean changed = (currentTopic != topic) || forceInit;
        currentTopic = Math.max(0, Math.min(TOPIC_COUNT - 1, topic));

        panelStudent.setVisibility(currentTopic <= TOPIC_CACHE ? View.VISIBLE : View.GONE);
        panelImage.setVisibility(currentTopic == TOPIC_IMAGE ? View.VISIBLE : View.GONE);
        panelPin.setVisibility(currentTopic == TOPIC_STARTUP ? View.VISIBLE : View.GONE);
        cardTopicMode.setVisibility(currentTopic == TOPIC_BOTTLENECK ? View.GONE : View.VISIBLE);
        btnResetCache.setVisibility(currentTopic == TOPIC_CACHE ? View.VISIBLE : View.GONE);

        if (demoRealtimeChart != null && changed) {
            demoRealtimeChart.setTopic(currentTopic);
            demoRealtimeChart.setAfterMode(!isTopicDegraded(currentTopic));
        }

        switch (currentTopic) {
            case TOPIC_THREADING:
                tvTopicBadge.setText("CHUYÊN ĐỀ 2 • BƯỚC 1 TỐI ƯU");
                tvTopicTitle.setText("Đa luồng & giữ 60 FPS");
                tvTopicGoal.setText("Before: Main Thread chạy APDU + Sleep bị khóa (~10 FPS). After: ApduThreadPool chạy nền, UI mượt 60 FPS.");
                tvTopicHint.setText("Nói: chạy nền giải phóng Main Thread (<1 ms giao việc), còn kênh thẻ synchronized giúp 4 luồng không xung đột APDU.");
                btnDemoAction.setText("Làm mới từ thẻ");
                tvComparisonTitle.setText("SO SÁNH BEFORE (LỖI) / AFTER (TỐI ƯU)");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • FRAME TIME & 60 FPS");
                tvRealtimeChartSubtitle.setText("Đường nét đứt 16.7 ms = 60 FPS; vọt đỏ khi Main Thread bị khóa.");
                break;

            case TOPIC_CACHE:
                tvTopicBadge.setText("CHUYÊN ĐỀ 3 • BƯỚC 2 TỐI ƯU");
                tvTopicTitle.setText("Cache offline-first & OWASP Storage");
                tvTopicGoal.setText("Before: luôn đọc 5 APDU (~210 ms) & lộ bản rõ. After: mở từ Cache AES-256-GCM (0 APDU ~9 ms).");
                tvTopicHint.setText("Nói: cache AES-256-GCM chỉ lưu hồ sơ/ảnh hiện tại và tự xóa sạch sau 3 lần sai PIN (Auto-Wipe).");
                btnDemoAction.setText("Mở hồ sơ");
                tvComparisonTitle.setText("SO SÁNH BEFORE (LỖI) / AFTER (TỐI ƯU)");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • ĐỘ TRỄ MỞ HỒ SƠ");
                tvRealtimeChartSubtitle.setText("Đỏ = đọc thẻ (5 APDU) • Xanh = đọc cache AES-GCM (0 APDU).");
                break;

            case TOPIC_IMAGE:
                tvTopicBadge.setText("CHUYÊN ĐỀ 4 • BƯỚC 3 TỐI ƯU");
                tvTopicTitle.setText("Nén ảnh WebP & quản trị Heap RAM");
                tvTopicGoal.setText("Before: giải mã full PNG (~180 KB, 750 gói) + Memory Churn trên Heap. After: WebP 75% (~3.5 KB, 15 gói) + Buffer 8 KB.");
                tvTopicHint.setText("Nói: Two-pass inSampleSize tránh decode ảnh 48 MB lên RAM; nén WebP giảm 98% số gói APDU và giữ Heap phẳng.");
                btnDemoAction.setText("Lưu ảnh lên thẻ");
                tvComparisonTitle.setText("SO SÁNH BEFORE (LỖI) / AFTER (TỐI ƯU)");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • JAVA HEAP (MB) & GÓI 240 B");
                tvRealtimeChartSubtitle.setText("Theo dõi biến động Heap khi Memory Churn vs khi tái sử dụng bộ đệm 8 KB.");
                break;

            case TOPIC_STARTUP:
                tvTopicBadge.setText("CHUYÊN ĐỀ 5 • BƯỚC 4 TỐI ƯU");
                tvTopicTitle.setText("Khởi động, Zeroize PIN & MASVS 5/5");
                tvTopicGoal.setText("Before: PIN giữ trong String trên RAM (100%) & tắt kiểm tra RSA/Root. After: Zeroize char[] (0%) & MASVS 5/5.");
                long coldStart = DemoMetrics.getColdStartMs();
                if (demoRealtimeChart != null) {
                    demoRealtimeChart.setColdStartMs(coldStart);
                }
                tvTopicHint.setText((coldStart > 0
                        ? "Cold start đo lúc mở app: " + coldStart + " ms. "
                        : "Cold start: đo từ process → màn PIN. ")
                        + "PBKDF2 10.000 vòng chống brute-force; char[] được điền '\\0' ngay sau khi băm.");
                btnDemoAction.setText("Đăng nhập");
                tvComparisonTitle.setText("SO SÁNH BEFORE (LỖI) / AFTER (TỐI ƯU)");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • PHƠI NHIỄM PIN TRÊN RAM");
                tvRealtimeChartSubtitle.setText("Before: String giữ 100% trong RAM • After: char[] xóa ngay về 0%.");
                break;

            default:
                tvTopicBadge.setText("CHUYÊN ĐỀ 1 • CHẨN ĐOÁN GỐC");
                tvTopicTitle.setText("Điểm nghẽn kép: APDU, chip & mật mã");
                tvTopicGoal.setText("Bấm đọc thẻ thật để đo chi phí từng lệnh APDU (SELECT → VERIFY → INFO → AES → ACADEMIC).");
                tvTopicHint.setText("Nói: điểm nghẽn nằm ở số round-trip bán song công và mật mã AES-128 / RSA-1024 trên chip, kéo theo 4 bước tối ưu ở §2–§5.");
                btnDemoAction.setText("Đọc hồ sơ từ thẻ");
                tvComparisonTitle.setText("KẾT QUẢ ĐO (§1)");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • ĐỘ TRỄ TỪNG LỆNH APDU");
                tvRealtimeChartSubtitle.setText("Nhảy theo thời gian thực khi 5 lệnh APDU hoàn tất.");
                break;
        }

        if (currentTopic <= TOPIC_CACHE) {
            showCachedProfile();
        }
        if (currentTopic == TOPIC_IMAGE) {
            prepareSampleImage();
        }

        tvDemoStatus.setText("");
        updateModeCardUi();
        renderComparison();
    }

    private boolean isTopicDegraded(int topic) {
        switch (topic) {
            case TOPIC_THREADING:
                return chaosManager.isUiThreadJankActive();
            case TOPIC_CACHE:
                return chaosManager.isCacheBypassAndPlaintextLeakActive();
            case TOPIC_IMAGE:
                return chaosManager.isMemoryChurnAndRawPngActive();
            case TOPIC_STARTUP:
                return chaosManager.isPinRamLeakAndSecurityBypassActive();
            default:
                return false;
        }
    }

    private void applyChaosForTopic(int topic, boolean afterMode) {
        switch (topic) {
            case TOPIC_THREADING:
                if (afterMode) chaosManager.resolveStep1Threading();
                else chaosManager.injectStep1Threading();
                break;
            case TOPIC_CACHE:
                if (afterMode) chaosManager.resolveStep2CacheAndStorage(requireContext());
                else chaosManager.injectStep2CacheAndStorage(requireContext());
                break;
            case TOPIC_IMAGE:
                if (afterMode) chaosManager.resolveStep3ImageAndMemory();
                else chaosManager.injectStep3ImageAndMemory();
                break;
            case TOPIC_STARTUP:
                if (afterMode) chaosManager.resolveStep4PinAndSecurity();
                else chaosManager.injectStep4PinAndSecurity();
                break;
            default:
                break;
        }
    }

    private void updateModeCardUi() {
        if (currentTopic == TOPIC_BOTTLENECK || !isAdded()) return;
        boolean afterMode = !isTopicDegraded(currentTopic);

        suppressSwitchListener = true;
        switchDemoMode.setChecked(afterMode);
        suppressSwitchListener = false;

        tvModeHint.setText(afterMode ? "✓ After — Đã tối ưu" : "❌ Before — Đang bị lỗi");
        tvModeHint.setTextColor(Color.parseColor(afterMode ? GREEN : RED));
        if (demoRealtimeChart != null) {
            demoRealtimeChart.setAfterMode(afterMode);
        }

        int greenColor = ContextCompat.getColor(requireContext(), R.color.emerald_600);
        int whiteColor = ContextCompat.getColor(requireContext(), R.color.white);
        int brandSoft = ContextCompat.getColor(requireContext(), R.color.brand_50);
        int brandColor = ContextCompat.getColor(requireContext(), R.color.brand_600);

        if (afterMode) {
            btnQuickFixStep.setText("🔥 Gây lỗi lại bước này để kiểm chứng");
            btnQuickFixStep.setBackgroundTintList(ColorStateList.valueOf(brandSoft));
            btnQuickFixStep.setTextColor(brandColor);
        } else {
            btnQuickFixStep.setText("🛠 Xử lý Bước này theo quy trình");
            btnQuickFixStep.setBackgroundTintList(ColorStateList.valueOf(greenColor));
            btnQuickFixStep.setTextColor(whiteColor);
        }
    }

    private void renderGlobalState() {
        if (!isAdded()) return;
        renderHeroCard();
        renderChaosToolCard();
        updateModeCardUi();
        populateSecurityAuditSummary();
    }

    private void renderHeroCard() {
        int activeIssues = chaosManager.getActiveIssueCount();
        int masvsScore = chaosManager.getMasvsScore();
        boolean compromised = activeIssues > 0;

        layoutHeroContainer.setBackgroundResource(
                compromised ? R.drawable.bg_gradient_red : R.drawable.bg_gradient_green);
        ivHeroIcon.setImageResource(compromised ? R.drawable.ic_bolt : R.drawable.ic_verified);

        if (compromised) {
            tvSecurityHeroStatus.setText(String.format(Locale.US,
                    "⚠ Cảnh báo: Phát hiện %d/4 sự cố!", activeIssues));
            tvSecurityHeroSubtitle.setText(String.format(Locale.US,
                    "Đã xử lý %d/4 bước • MASVS %d/5 • Hiệu năng/Bảo mật suy giảm",
                    chaosManager.getOptimizedStepCount(), masvsScore));
            tvSecurityHeroSubtitle.setTextColor(Color.parseColor("#FEE2E2"));
        } else {
            tvSecurityHeroStatus.setText("Hệ thống tối ưu • Thiết bị an toàn");
            tvSecurityHeroSubtitle.setText("Quy trình 4/4 bước đã hoàn tất • MASVS 5/5");
            tvSecurityHeroSubtitle.setTextColor(Color.parseColor("#ECFDF5"));
        }

        tvBadgeFps.setText(chaosManager.isUiThreadJankActive()
                ? Math.min(lastMeasuredFps, 15) + " FPS"
                : lastMeasuredFps + " FPS");
        tvBadgeCache.setText(chaosManager.isCacheBypassAndPlaintextLeakActive() ? "Lộ Plain" : "AES-GCM");
        tvBadgeWebp.setText(chaosManager.isMemoryChurnAndRawPngActive() ? "PNG 750G" : "WebP 15G");
        tvBadgeMasvs.setText("MASVS " + masvsScore + "/5");
    }

    private void renderChaosToolCard() {
        if (pipelineRunning) return;
        int activeIssues = chaosManager.getActiveIssueCount();
        if (activeIssues == 0) {
            tvChaosToolStatus.setText("✓ Hệ thống đang sạch (4/4 tối ưu). Bấm nút đỏ để gây sự cố thực tế rồi bấm từng tab §2–§5 hoặc chạy Tự động.");
            tvChaosToolStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600));
        } else {
            tvChaosToolStatus.setText(String.format(Locale.US,
                    "⚠ Đang kích hoạt %d/4 sự cố! Chuyển các tab §2–§5 để xem số liệu lỗi Before hoặc chạy Quy trình Tự động để xử lý.",
                    activeIssues));
            tvChaosToolStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_600));
        }
    }

    private void seedDegradedBaselines() {
        beforeByTopic[TOPIC_THREADING].clear();
        beforeByTopic[TOPIC_THREADING].put("Main Thread giữ", "~95 ms/nhịp");
        beforeByTopic[TOPIC_THREADING].put("FPS thực tế", "~10 FPS (Giật)");
        beforeByTopic[TOPIC_THREADING].put("Khung rớt", "~6 khung/nhịp");

        beforeByTopic[TOPIC_CACHE].clear();
        beforeByTopic[TOPIC_CACHE].put("Nguồn", "Thẻ + Lộ Plain");
        beforeByTopic[TOPIC_CACHE].put("Thời gian", "~210 ms");
        beforeByTopic[TOPIC_CACHE].put("APDU", "5 (Bypass)");

        beforeByTopic[TOPIC_IMAGE].clear();
        beforeByTopic[TOPIC_IMAGE].put("Ảnh gốc", "1200×1200");
        beforeByTopic[TOPIC_IMAGE].put("Dung lượng", "180 KB (PNG)");
        beforeByTopic[TOPIC_IMAGE].put("Số gói 240B", "750 gói");
        beforeByTopic[TOPIC_IMAGE].put("Ghi lên thẻ", "Không — vượt 8 KB");

        beforeByTopic[TOPIC_STARTUP].clear();
        beforeByTopic[TOPIC_STARTUP].put("PBKDF2 10.000 vòng", "~34 ms");
        beforeByTopic[TOPIC_STARTUP].put("Zeroize PIN", "Không (String 100%)");
        beforeByTopic[TOPIC_STARTUP].put("MASVS", "1/5 (Nguy hiểm)");
    }

    // ------------------------------------------------------------------
    // Automated 4-Step Pipeline (§2 -> §5)
    // ------------------------------------------------------------------

    private void startAutoFixPipeline() {
        cancelPipeline();
        pipelineRunning = true;
        btnRunAutoFixPipeline.setEnabled(false);
        btnRunAutoFixPipeline.setText("⏳ Đang chạy Quy trình Tối ưu (§2 → §5)...");

        long step1DelayMs;
        if (!chaosManager.isAnyIssueActive()) {
            chaosManager.injectAllIssues(requireContext());
            seedDegradedBaselines();
            selectTabAndSwitch(TOPIC_THREADING);
            if (demoRealtimeChart != null) {
                demoRealtimeChart.recordThreadingResult(false, 95L, 95L);
            }
            tvChaosToolStatus.setText("🔥 Điểm xuất phát: Đã tiêm đủ 4 lỗi (~10 FPS, Lộ Plain, Churn, PIN RAM) — xử lý sau 1.5s...");
            tvChaosToolStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_600));
            step1DelayMs = 1500L;
        } else {
            tvChaosToolStatus.setText("⏳ Chuẩn bị chạy Quy trình 4 bước khắc phục trên từng chuyên đề §2 → §5...");
            tvChaosToolStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_600));
            step1DelayMs = 1200L;
        }

        // Step 1 (§2): Threading & 60 FPS
        autoFixHandler.postDelayed(() -> {
            if (!isAdded() || !pipelineRunning) return;
            busy = false;
            endRun();
            chaosManager.resolveStep1Threading();
            selectTabAndSwitch(TOPIC_THREADING);
            tvChaosToolStatus.setText("⚡ Bước 1/4 (§2): Đã bật ApduThreadPool — khôi phục 60 FPS!");
            tvChaosToolStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_600));
            runThreading(true);
        }, step1DelayMs);

        // Step 2 (§3): Cache AES-GCM & Remove Plaintext Leak
        autoFixHandler.postDelayed(() -> {
            if (!isAdded() || !pipelineRunning) return;
            busy = false;
            endRun();
            chaosManager.resolveStep2CacheAndStorage(requireContext());
            selectTabAndSwitch(TOPIC_CACHE);
            tvChaosToolStatus.setText("🛡️ Bước 2/4 (§3): Đã xóa bản rõ & bật Cache AES-256-GCM (~9 ms, 0 APDU)!");
            runCache(true);
        }, step1DelayMs + 1600L);

        // Step 3 (§4): WebP 75% & Heap Memory Churn Cleanup
        autoFixHandler.postDelayed(() -> {
            if (!isAdded() || !pipelineRunning) return;
            busy = false;
            endRun();
            chaosManager.resolveStep3ImageAndMemory();
            selectTabAndSwitch(TOPIC_IMAGE);
            tvChaosToolStatus.setText("🖼️ Bước 3/4 (§4): Đã nén WebP 15 gói (−98%) & dọn sạch Memory Churn!");
            runImage(true);
        }, step1DelayMs + 3200L);

        // Step 4 (§5): PIN char[] Zeroization & MASVS 5/5
        autoFixHandler.postDelayed(() -> {
            if (!isAdded() || !pipelineRunning) return;
            busy = false;
            endRun();
            chaosManager.resolveStep4PinAndSecurity();
            selectTabAndSwitch(TOPIC_STARTUP);
            runLogin(true);
            pipelineRunning = false;
            btnRunAutoFixPipeline.setEnabled(true);
            btnRunAutoFixPipeline.setText("⚡ Chạy Quy trình Tối ưu Tự động (§2 → §5)");
            tvChaosToolStatus.setText("✓ Hoàn tất Quy trình 4/4 bước: 60 FPS • Cache AES-GCM • WebP 15 gói • MASVS 5/5!");
            Toast.makeText(requireContext(), "Đã xử lý xong toàn bộ quy trình §2 → §5!", Toast.LENGTH_SHORT).show();
        }, step1DelayMs + 4800L);
    }

    private void cancelPipeline() {
        autoFixHandler.removeCallbacksAndMessages(null);
        if (pipelineRunning) {
            pipelineRunning = false;
            if (btnRunAutoFixPipeline != null) {
                btnRunAutoFixPipeline.setEnabled(true);
                btnRunAutoFixPipeline.setText("⚡ Chạy Quy trình Tối ưu Tự động (§2 → §5)");
            }
        }
    }

    // ------------------------------------------------------------------
    // Topic actions (§1–§5 real operations)
    // ------------------------------------------------------------------

    private void runCurrentMode() {
        if (busy) return;
        runTopicAction(currentTopic, !isTopicDegraded(currentTopic));
    }

    private void runTopicAction(int topic, boolean afterMode) {
        if (busy) return;
        switch (topic) {
            case TOPIC_THREADING:
                runThreading(afterMode);
                break;
            case TOPIC_CACHE:
                runCache(afterMode);
                break;
            case TOPIC_IMAGE:
                runImage(afterMode);
                break;
            case TOPIC_STARTUP:
                runLogin(afterMode);
                break;
            default:
                runBottleneck();
                break;
        }
    }

    private void runBottleneck() {
        startRun("Đang đọc hồ sơ từ thẻ...", TOPIC_BOTTLENECK, false);
        runTask(this::fetchFromCard, (result, blockedMs, totalMs) -> {
            showProfile(result.getRecord(), null, "THẺ");
            Map<String, String> metrics = new LinkedHashMap<>();
            long[] step = result.getStepMs();
            if (demoRealtimeChart != null && currentTopic == TOPIC_BOTTLENECK) {
                demoRealtimeChart.recordBottleneckResult(step, result.getElapsedMs());
            }
            metrics.put("SELECT applet", step[0] + " ms");
            metrics.put("VERIFY PIN", step[1] + " ms");
            metrics.put("GET INFO", step[2] + " ms");
            metrics.put("GET CIPHER", step[3] + " ms");
            metrics.put("ACADEMIC", step[4] + " ms");
            metrics.put("Tổng", result.getElapsedMs() + " ms");
            metrics.put("Số lệnh", result.getApduCommandsSent() + " APDU");
            finishRun(TOPIC_BOTTLENECK, false, metrics, "✓ Đọc xong hồ sơ trong " + result.getElapsedMs()
                    + " ms — thời gian nằm ở từng lệnh như bảng dưới.", true);
        });
    }

    private void runThreading(boolean afterMode) {
        String where = afterMode ? "thread nền (After)" : "Main Thread (Before)";
        startRun("Đang làm mới hồ sơ trên " + where + "...", TOPIC_THREADING, afterMode);
        if (afterMode) {
            runTask(this::fetchFromCard, (result, blockedMs, totalMs) -> {
                showProfile(result.getRecord(), null, "THẺ");
                if (demoRealtimeChart != null && currentTopic == TOPIC_THREADING) {
                    demoRealtimeChart.recordThreadingResult(true, blockedMs, result.getElapsedMs());
                }
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("Main Thread giữ", msText(blockedMs));
                metrics.put("Thời gian đọc", result.getElapsedMs() + " ms");
                metrics.put("Khung rớt", Math.round(blockedMs / 16.6667f) + " khung");
                finishRun(TOPIC_THREADING, true, metrics,
                        "✓ After: hồ sơ đã cập nhật, Main Thread không bị chặn (60 FPS).", true);
            });
        } else {
            runBlockingTask(this::fetchFromCard, (result, blockedMs, totalMs) -> {
                if (!isAdded()) return;
                runMainThreadAvatarBurst(result, totalMs, 4);
            });
        }
    }

    private void runMainThreadAvatarBurst(LocalCacheManager.ProfileLoadResult profileResult,
                                          long accumulatedMs, int remainingFrames) {
        if (!isAdded()) return;
        if (remainingFrames <= 0) {
            showProfile(profileResult.getRecord(), null, "THẺ");
            if (demoRealtimeChart != null && currentTopic == TOPIC_THREADING) {
                demoRealtimeChart.recordThreadingResult(false, accumulatedMs, accumulatedMs);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Main Thread giữ", msText(accumulatedMs));
            metrics.put("Thời gian đọc", accumulatedMs + " ms");
            metrics.put("Khung rớt", Math.round(accumulatedMs / 16.6667f) + " khung");
            finishRun(TOPIC_THREADING, false, metrics, "⚠ Before: UI đã bị khóa " + accumulatedMs
                    + " ms qua 5 nhịp — nhịp UI đứng hình & tụt FPS đỏ.", false);
            return;
        }
        Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
            if (!isAdded()) return;
            long frameStartNs = System.nanoTime();
            ApduClient client = ApduClient.getInstance();
            for (int c = 0; c < 8; c++) {
                int offset = c * ApduCommands.MAX_AVATAR_CHUNK_SIZE;
                client.sendApdu(
                        ApduCommands.CLA_SMART_STUDENT,
                        ApduCommands.INS_DOWNLOAD_AVATAR_CHUNK,
                        (byte) ((offset >> 8) & 0xFF),
                        (byte) (offset & 0xFF),
                        new byte[]{(byte) 240}
                );
            }
            long frameCostMs = Math.max(1L, (System.nanoTime() - frameStartNs) / 1_000_000L);
            runMainThreadAvatarBurst(profileResult, accumulatedMs + frameCostMs, remainingFrames - 1);
        });
    }

    private void runCache(boolean afterMode) {
        startRun("Đang mở hồ sơ...", TOPIC_CACHE, afterMode);
        runTask(() -> {
            if (afterMode && !chaosManager.isCacheBypassAndPlaintextLeakActive()) {
                StudentCardRecord cached = encryptedCache.getStudentProfile();
                if (cached != null) {
                    return new CacheOpen(cached, encryptedCache.getAvatarBytes(), true, 0);
                }
            }
            LocalCacheManager.ProfileLoadResult result = fetchFromCard();
            return new CacheOpen(result.getRecord(), encryptedCache.getAvatarBytes(), false,
                    result.getApduCommandsSent());
        }, (openResult, blockedMs, totalMs) -> {
            showProfile(openResult.record, openResult.avatarBytes, openResult.fromCache ? "CACHE" : "THẺ");
            int apduCount = openResult.fromCache ? 0 : openResult.apduCount;
            if (demoRealtimeChart != null && currentTopic == TOPIC_CACHE) {
                demoRealtimeChart.recordCacheResult(afterMode, openResult.fromCache, totalMs, apduCount);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Nguồn", openResult.fromCache ? "Cache (AES-GCM)" : "Thẻ");
            metrics.put("Thời gian", totalMs + " ms");
            metrics.put("APDU", String.valueOf(apduCount));
            finishRun(TOPIC_CACHE, afterMode, metrics, openResult.fromCache
                    ? "✓ After: mở hồ sơ từ Cache AES-256-GCM, 0 APDU."
                    : (afterMode
                    ? "✓ After: lần đầu chưa có cache nên đọc thẻ và đã ghi lại Cache mã hóa."
                    : "⚠ Before: bỏ qua cache — luôn đọc 5 APDU từ thẻ."), afterMode);
        });
    }

    private void resetCache() {
        if (busy) return;
        encryptedCache.wipeStudentSessionCache();
        beforeByTopic[TOPIC_CACHE].clear();
        afterByTopic[TOPIC_CACHE].clear();
        renderComparison();
        if (demoRealtimeChart != null && currentTopic == TOPIC_CACHE) {
            demoRealtimeChart.resetHistory();
        }
        showProfile(null, null, null);
        tvDemoStatus.setText("Đã xóa cache hồ sơ — lần mở tiếp theo sẽ đọc thẻ (mô phỏng lần đầu).");
        tvDemoStatus.setTextColor(Color.parseColor(SLATE_MUTED));
    }

    private void runImage(boolean afterMode) {
        startRun(afterMode ? "Đang xử lý & ghi WebP (After)..." : "Đang giải mã full PNG (Before)...",
                TOPIC_IMAGE, afterMode);
        runTask(() -> afterMode ? runImageAfter() : runImageBefore(), (result, blockedMs, totalMs) -> {
            if (result.preview != null) {
                showPreview(result.preview);
            }
            if (demoRealtimeChart != null && currentTopic == TOPIC_IMAGE && (result.sizeKb > 0 || afterMode)) {
                demoRealtimeChart.recordImageResult(afterMode, result.sizeKb, result.packets,
                        result.ramMb, result.success);
            }
            finishRun(TOPIC_IMAGE, afterMode, result.metrics, result.status, result.success);
        });
    }

    private byte[] ensureSampleBytes() {
        if (sampleBytes != null) {
            return sampleBytes;
        }
        Bitmap bitmap = ImageOptimizer.generateSampleStudentAvatar("SV", 1200);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos);
            sampleBytes = bos.toByteArray();
            return sampleBytes;
        } finally {
            bitmap.recycle();
        }
    }

    private ImageRunResult runImageBefore() {
        long startNs = System.nanoTime();
        Bitmap full = null;
        Bitmap preview = null;
        try {
            byte[] srcBytes = pickedUri == null ? ensureSampleBytes() : null;
            int[] dims = sourceBounds(srcBytes);
            full = decodeFullSize(dims[0], dims[1], srcBytes);
            if (full == null) {
                return ImageRunResult.failure("❌ Không giải mã được ảnh đã chọn.");
            }
            long decodeMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            preview = ImageOptimizer.centerCropToSize(full, 320, 320);
            byte[] png = ImageOptimizer.compressToUnoptimizedPng(full);
            long totalMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            int packets = ImageOptimizer.calculateApduPacketCount(png.length);
            double ramMb = dims[0] * (double) dims[1] * 4 / (1024 * 1024);
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Ảnh gốc", dims[0] + "×" + dims[1]);
            metrics.put("Giải mã", "Full (inSampleSize=1) • " + decodeMs + " ms");
            metrics.put("Dung lượng", sizeText(png.length) + " (PNG)");
            metrics.put("Số gói 240B", packets + " gói");
            metrics.put("Xử lý ảnh", totalMs + " ms");
            metrics.put("RAM bitmap", String.format(Locale.US, "%.1f MB", ramMb));
            metrics.put("Ghi lên thẻ", "Không — vượt 8 KB");
            return new ImageRunResult(metrics, preview,
                    "❌ Before: PNG " + sizeText(png.length) + " cần " + packets
                            + " gói → vượt bộ đệm 8 KB của thẻ.", false,
                    png.length / 1024.0, packets, ramMb);
        } catch (OutOfMemoryError e) {
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Ghi lên thẻ", "OOM khi giải mã");
            return new ImageRunResult(metrics, null,
                    "❌ Hết RAM khi giải mã ảnh gốc — cần two-pass downsampling.", false,
                    0, 0, 0);
        } catch (Exception e) {
            return ImageRunResult.failure("❌ Không xử lý được ảnh: " + e.getMessage());
        } finally {
            if (full != null && full != preview) {
                full.recycle();
            }
        }
    }

    private ImageRunResult runImageAfter() {
        long startNs = System.nanoTime();
        Bitmap avatar = null;
        Bitmap preview = null;
        try {
            byte[] srcBytes = pickedUri == null ? ensureSampleBytes() : null;
            int[] dims = sourceBounds(srcBytes);
            byte[] webp;
            int sampleSize;
            if (pickedUri != null) {
                ImageOptimizer.AvatarOptimizationReport report =
                        ImageOptimizer.decodeAndCompressAvatarReport(appContext(), pickedUri);
                if (report == null) {
                    return ImageRunResult.failure("❌ Không xử lý được ảnh đã chọn.");
                }
                webp = report.webpBytes;
                sampleSize = report.sampleSize;
            } else {
                avatar = ImageOptimizer.decodeAndDownsample(srcBytes,
                        ImageOptimizer.TARGET_AVATAR_WIDTH, ImageOptimizer.TARGET_AVATAR_HEIGHT);
                webp = ImageOptimizer.compressAvatarForCard(avatar);
                sampleSize = ImageOptimizer.calculateInSampleSize(dims[0], dims[1],
                        ImageOptimizer.TARGET_AVATAR_WIDTH, ImageOptimizer.TARGET_AVATAR_HEIGHT);
            }
            long processMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            long uploadStartNs = System.nanoTime();
            int packets = uploadAvatar(webp);
            long uploadMs = Math.max(1, (System.nanoTime() - uploadStartNs) / 1_000_000);
            encryptedCache.saveAvatarBytes(webp);
            preview = BitmapFactory.decodeByteArray(webp, 0, webp.length);

            boolean ok = packets >= 0;
            double ramMb = ImageOptimizer.TARGET_AVATAR_WIDTH
                    * (double) ImageOptimizer.TARGET_AVATAR_HEIGHT * 4 / (1024 * 1024);
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Ảnh gốc", dims[0] + "×" + dims[1]);
            metrics.put("Giải mã", "Two-pass (inSampleSize=" + sampleSize + ")");
            metrics.put("Dung lượng", sizeText(webp.length) + " (WebP 75%)");
            metrics.put("Số gói 240B", packets + " gói");
            metrics.put("Xử lý ảnh", processMs + " ms");
            metrics.put("RAM bitmap", String.format(Locale.US, "%.2f MB (150×150)", ramMb));
            metrics.put("Ghi lên thẻ", packets >= 0
                    ? "✓ " + packets + " gói • " + uploadMs + " ms" : "❌ thẻ từ chối");
            return new ImageRunResult(metrics, preview, ok
                    ? "✓ After: đã lưu WebP " + sizeText(webp.length) + " (" + packets
                    + " gói) — xử lý " + processMs + " ms, ghi thẻ " + uploadMs + " ms."
                    : "❌ Thẻ từ chối gói ảnh WebP.", ok,
                    webp.length / 1024.0, Math.max(0, packets), ramMb);
        } catch (Exception e) {
            return ImageRunResult.failure("❌ Không xử lý được ảnh: " + e.getMessage());
        } finally {
            if (avatar != null) {
                avatar.recycle();
            }
        }
    }

    private int uploadAvatar(byte[] webp) {
        char[] pin = sessionPinChars();
        try {
            byte[] pinBytes = pinToBytes(pin);
            try {
                ApduClient.getInstance().sendApdu(ApduCommands.CLA_SMART_STUDENT,
                        ApduCommands.INS_VERIFY_PIN, (byte) 0x00, (byte) 0x00, pinBytes);
            } finally {
                Arrays.fill(pinBytes, (byte) 0);
            }
        } finally {
            Arrays.fill(pin, '\0');
        }
        return ApduClient.getInstance().uploadAvatarInChunks(webp);
    }

    private int[] sourceBounds(byte[] srcBytes) throws Exception {
        if (pickedUri != null) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = appContext().getContentResolver().openInputStream(pickedUri)) {
                if (is == null) throw new IllegalStateException("Không mở được ảnh");
                BitmapFactory.decodeStream(is, null, bounds);
            }
            return new int[]{Math.max(1, bounds.outWidth), Math.max(1, bounds.outHeight)};
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.length, bounds);
        return new int[]{Math.max(1, bounds.outWidth), Math.max(1, bounds.outHeight)};
    }

    private Bitmap decodeFullSize(int width, int height, byte[] srcBytes) {
        if (pickedUri != null) {
            return ImageOptimizer.decodeAndDownsampleFromUri(appContext(), pickedUri, width, height);
        }
        return ImageOptimizer.decodeAndDownsample(srcBytes, width, height);
    }

    private void prepareSampleImage() {
        if (sampleBytes != null) {
            Bitmap preview = ImageOptimizer.decodeAndDownsample(sampleBytes, 320, 320);
            if (preview != null) showPreview(preview);
            if (pickedUri == null) {
                demoImageInfo.setText("Ảnh mẫu 1200×1200 (chưa chọn ảnh) — bấm “Chọn ảnh” để dùng ảnh thật.");
            }
            return;
        }
        ApduThreadPool.getInstance().executeAsyncOnPool(() -> ensureSampleBytes(), (bytes, blockedMs, totalMs) -> {
            if (!isAdded()) return;
            Bitmap preview = ImageOptimizer.decodeAndDownsample(bytes, 320, 320);
            if (preview != null) {
                showPreview(preview);
            }
            demoImageInfo.setText("Ảnh mẫu 1200×1200 (chưa chọn ảnh) — bấm “Chọn ảnh” để dùng ảnh thật.");
        });
    }

    private void loadPickedPreview(Uri uri) {
        ApduThreadPool.getInstance().executeAsyncOnPool(() -> {
            Bitmap preview = ImageOptimizer.decodeAndDownsampleFromUri(appContext(), uri, 320, 320);
            int[] bounds = sourceBoundsFor(uri);
            return new PreviewResult(preview, bounds[0], bounds[1]);
        }, (result, blockedMs, totalMs) -> {
            if (!isAdded()) return;
            if (result.bitmap != null) {
                showPreview(result.bitmap);
            }
            demoImageInfo.setText("Ảnh đã chọn: " + result.width + "×" + result.height + " px");
        });
    }

    private int[] sourceBoundsFor(Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = appContext().getContentResolver().openInputStream(uri)) {
            if (is != null) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
        } catch (Exception ignored) {
        }
        return new int[]{Math.max(1, bounds.outWidth), Math.max(1, bounds.outHeight)};
    }

    private void showPreview(Bitmap bitmap) {
        demoImagePreview.setImageTintList(null);
        demoImagePreview.setPadding(0, 0, 0, 0);
        demoImagePreview.setImageBitmap(bitmap);
    }

    private void runLogin(boolean afterMode) {
        String pinText = demoPinInput.getText().toString().trim();
        if (pinText.length() < 4) {
            pinText = "123456";
            demoPinInput.setText(pinText);
        }
        final String finalPin = pinText;
        startRun("Đang đăng nhập & kiểm tra bộ nhớ PIN...", TOPIC_STARTUP, afterMode);
        runTask(() -> afterMode ? loginAfter(finalPin) : loginBefore(finalPin),
                (result, blockedMs, totalMs) -> {
                    if (demoRealtimeChart != null && currentTopic == TOPIC_STARTUP) {
                        demoRealtimeChart.recordLoginResult(afterMode, result.ms, result.remaining, result.length);
                    }
                    Map<String, String> metrics = new LinkedHashMap<>();
                    metrics.put("PBKDF2 10.000 vòng", result.ms + " ms");
                    metrics.put("Zeroize PIN", result.remaining < 0
                            ? "Không (String 100%)" : result.remaining + "/" + result.length + " ký tự còn");
                    metrics.put("MASVS", afterMode ? "5/5 (An toàn)" : chaosManager.getMasvsScore() + "/5 (Rủi ro)");
                    String status = afterMode
                            ? "✓ After: đăng nhập xong, PIN đã zeroize ("
                            + (result.length - result.remaining) + "/" + result.length + " ký tự đã xóa) • MASVS 5/5."
                            : "⚠ Before: PIN vẫn nằm trong String trên Heap — không thể xóa!";
                    finishRun(TOPIC_STARTUP, afterMode, metrics, status, afterMode);
                });
    }

    private PinRunResult loginBefore(String pinText) throws Exception {
        long startNs = System.nanoTime();
        PBEKeySpec spec = new PBEKeySpec(pinText.toCharArray(), new byte[16], 10_000, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        factory.generateSecret(spec).getEncoded();
        long ms = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        return new PinRunResult(ms, -1, pinText.length());
    }

    private PinRunResult loginAfter(String pinText) {
        char[] pin = pinText.toCharArray();
        long startNs = System.nanoTime();
        CryptoManager.computePinVerifierHash(pin, new byte[16]);
        long ms = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        int remaining = 0;
        for (char c : pin) {
            if (c != '\0') remaining++;
        }
        return new PinRunResult(ms, remaining, pin.length);
    }

    // ------------------------------------------------------------------
    // Shared task / comparison / profile UI helpers
    // ------------------------------------------------------------------

    private interface ResultHandler<T> {
        void onResult(T result, long blockedMs, long totalMs);
    }

    private <T> void runTask(Callable<T> task, ResultHandler<T> handler) {
        ApduThreadPool.getInstance().executeAsyncOnPool(task, new ApduThreadPool.TaskCallback<T>() {
            @Override
            public void onComplete(T result, long blockedMs, long totalMs) {
                if (isAdded()) handler.onResult(result, blockedMs, totalMs);
            }

            @Override
            public void onError(Exception e) {
                failRun(e);
            }
        });
    }

    private <T> void runBlockingTask(Callable<T> task, ResultHandler<T> handler) {
        ApduThreadPool.getInstance().executeBlockingOnCurrentThread(task,
                new ApduThreadPool.TaskCallback<T>() {
                    @Override
                    public void onComplete(T result, long blockedMs, long totalMs) {
                        if (isAdded()) handler.onResult(result, blockedMs, totalMs);
                    }

                    @Override
                    public void onError(Exception e) {
                        failRun(e);
                    }
                });
    }

    private void failRun(Exception e) {
        if (!isAdded()) return;
        endRun();
        if (demoRealtimeChart != null) {
            demoRealtimeChart.onRunEnded();
        }
        tvDemoStatus.setText("❌ Lỗi: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        tvDemoStatus.setTextColor(Color.parseColor(RED));
    }

    private void startRun(String status, int topic, boolean afterMode) {
        busy = true;
        btnDemoAction.setEnabled(false);
        demoProgress.setVisibility(View.VISIBLE);
        tvDemoStatus.setText(status);
        tvDemoStatus.setTextColor(Color.parseColor(SLATE_MUTED));
        if (demoRealtimeChart != null && currentTopic == topic) {
            demoRealtimeChart.onRunStarted(topic != TOPIC_BOTTLENECK && afterMode);
        }
    }

    private void endRun() {
        busy = false;
        if (btnDemoAction != null) btnDemoAction.setEnabled(true);
        if (demoProgress != null) demoProgress.setVisibility(View.GONE);
    }

    private void finishRun(int topic, boolean afterRun, Map<String, String> metrics,
                           String status, boolean ok) {
        endRun();
        Map<String, String> target = (topic == TOPIC_BOTTLENECK || !afterRun)
                ? beforeByTopic[topic] : afterByTopic[topic];
        target.clear();
        target.putAll(metrics);
        if (currentTopic == topic) {
            renderComparison();
            tvDemoStatus.setText(status);
            tvDemoStatus.setTextColor(Color.parseColor(ok ? GREEN : RED));
        }
    }

    private LocalCacheManager.ProfileLoadResult fetchFromCard() {
        char[] pin = sessionPinChars();
        try {
            return localCacheManager.fetchStudentProfileFromCard(pin);
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    private char[] sessionPinChars() {
        char[] pin = pinSessionManager.getTransientVerifiedPinChars();
        return (pin != null && pin.length > 0) ? pin : "123456".toCharArray();
    }

    private static byte[] pinToBytes(char[] pin) {
        byte[] bytes = new byte[pin.length];
        for (int i = 0; i < pin.length; i++) {
            bytes[i] = (byte) pin[i];
        }
        return bytes;
    }

    private Context appContext() {
        return requireContext().getApplicationContext();
    }

    private void showCachedProfile() {
        if (chaosManager.isCacheBypassAndPlaintextLeakActive()) {
            StudentCardRecord fallback = StudentCardRecord.fromPipeDelimitedString(
                    ApduClient.getInstance().getActiveCardInfo());
            showProfile(fallback, null, "THẺ (BYPASS)");
            return;
        }
        StudentCardRecord record = encryptedCache.getStudentProfile();
        if (record != null) {
            showProfile(record, encryptedCache.getAvatarBytes(), "CACHE");
        }
    }

    private void showProfile(StudentCardRecord record, byte[] avatarBytes, String badge) {
        if (record == null) {
            demoName.setText("Chưa có hồ sơ");
            demoStudentId.setText("—");
            demoDept.setText("");
            demoSourceBadge.setText("");
            demoSourceBadge.setVisibility(View.GONE);
            demoAvatar.setImageTintList(ColorStateList.valueOf(Color.parseColor(BLUE)));
            demoAvatar.setPadding(dp(10), dp(10), dp(10), dp(10));
            demoAvatar.setImageResource(R.drawable.ic_person);
            return;
        }
        demoName.setText(record.getFullName());
        demoStudentId.setText("MSSV: " + record.getStudentId() + " • " + record.getClassName());
        demoDept.setText("Khoa " + record.getDepartment());

        if (badge != null) {
            demoSourceBadge.setVisibility(View.VISIBLE);
            demoSourceBadge.setText(badge);
            boolean fromCache = "CACHE".equals(badge);
            demoSourceBadge.setBackgroundResource(fromCache
                    ? R.drawable.bg_pill_green : R.drawable.bg_pill_blue);
            demoSourceBadge.setTextColor(Color.parseColor(fromCache ? GREEN : BLUE));
        }

        byte[] bytes = avatarBytes != null ? avatarBytes : encryptedCache.getAvatarBytes();
        if (bytes != null && bytes.length > 0) {
            Bitmap avatar = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (avatar != null) {
                demoAvatar.setImageTintList(null);
                demoAvatar.setPadding(0, 0, 0, 0);
                demoAvatar.setImageBitmap(avatar);
            }
        }
    }

    private void renderComparison() {
        llComparison.removeAllViews();
        Map<String, String> before = beforeByTopic[currentTopic];
        Map<String, String> after = afterByTopic[currentTopic];

        if (currentTopic == TOPIC_BOTTLENECK) {
            if (before.isEmpty()) {
                addComparisonNote("Bấm “Đọc hồ sơ từ thẻ” để điền bảng số đo từng lệnh APDU.");
                return;
            }
            for (Map.Entry<String, String> entry : before.entrySet()) {
                addSingleRow(entry.getKey(), entry.getValue());
            }
            return;
        }

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        if (keys.isEmpty()) {
            addComparisonNote("Gạt công tắc Before / After hoặc bấm “Gây lỗi / Tự động” để điền bảng so sánh.");
            return;
        }
        addComparisonHeader();
        for (String key : keys) {
            addComparisonRow(key, before.get(key), after.get(key));
        }
    }

    private void addComparisonHeader() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(4));
        row.addView(comparisonCell("", 1.25f, Color.parseColor(SLATE_MUTED), Typeface.BOLD));
        row.addView(comparisonCell("BEFORE", 1f, Color.parseColor(RED), Typeface.BOLD));
        row.addView(comparisonCell("AFTER", 1f, Color.parseColor(GREEN), Typeface.BOLD));
        llComparison.addView(row);
    }

    private void addComparisonRow(String label, String beforeValue, String afterValue) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView labelCell = comparisonCell(label, 1.25f, Color.parseColor(SLATE), Typeface.NORMAL);
        labelCell.setGravity(Gravity.START);
        row.addView(labelCell);
        row.addView(comparisonCell(beforeValue != null ? beforeValue : "—", 1f,
                Color.parseColor(RED), Typeface.BOLD));
        row.addView(comparisonCell(afterValue != null ? afterValue : "—", 1f,
                Color.parseColor(GREEN), Typeface.BOLD));
        llComparison.addView(row);
        addDivider();
    }

    private void addSingleRow(String label, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView labelCell = comparisonCell(label, 1f, Color.parseColor(SLATE), Typeface.NORMAL);
        labelCell.setGravity(Gravity.START);
        row.addView(labelCell);
        row.addView(comparisonCell(value, 1f, Color.parseColor(BLUE), Typeface.BOLD));
        llComparison.addView(row);
        addDivider();
    }

    private TextView comparisonCell(String text, float weight, int color, int style) {
        TextView cell = new TextView(requireContext());
        cell.setText(text);
        cell.setTextSize(10);
        cell.setTextColor(color);
        cell.setTypeface(Typeface.MONOSPACE, style);
        cell.setGravity(Gravity.END);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        cell.setLayoutParams(params);
        return cell;
    }

    private void addComparisonNote(String text) {
        TextView note = new TextView(requireContext());
        note.setText(text);
        note.setTextSize(10);
        note.setTextColor(Color.parseColor(SLATE_MUTED));
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(0, dp(6), 0, 0);
        llComparison.addView(note);
    }

    private void addDivider() {
        View divider = new View(requireContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#E2E8F0"));
        llComparison.addView(divider);
    }

    private void populateSecurityAuditSummary() {
        boolean realRooted = SecurityEnvironmentChecker.isDeviceRooted();
        boolean realDebugger = SecurityEnvironmentChecker.isDebuggerAttached();
        boolean keystoreOk = SecurityEnvironmentChecker.isAndroidKeyStoreAvailable();
        int remainingPinTries = pinSessionManager.getRemainingUnlockAttempts();

        boolean plaintextLeak = chaosManager.hasPlaintextLeak(requireContext());
        boolean rsaBypassed = chaosManager.isRsaVerificationBypassed();
        boolean pinExposed = chaosManager.isPinRamLeakAndSecurityBypassActive();
        boolean envThreat = realRooted || realDebugger
                || chaosManager.isSimulatedRootDetected()
                || chaosManager.isSimulatedDebuggerDetected();

        StringBuilder sb = new StringBuilder();
        sb.append("✓ MASVS-CRYPTO 1: AES-128 EEPROM trên chip thẻ hoạt động\n");
        sb.append(rsaBypassed
                ? "❌ MASVS-AUTH 2: Đã bỏ qua xác thực chữ ký RSA-1024!\n"
                : "✓ MASVS-AUTH 2: RSA-1024 Challenge-Response đã xác thực\n");
        sb.append(plaintextLeak
                ? "❌ MASVS-STORAGE 3: LỘ BẢN RÕ trong insecure_plaintext_leak!\n"
                : String.format(Locale.US, "✓ MASVS-STORAGE 3: AES-256-GCM + KeyStore (%s)\n",
                keystoreOk ? "sẵn sàng" : "fallback"));
        sb.append(pinExposed
                ? "❌ MASVS-CRYPTO 4: PIN tồn tại dạng String trên Heap (100%)\n"
                : String.format(Locale.US, "✓ MASVS-CRYPTO 4: Zeroize char[] PIN (0%%) • còn %d/3 lần thử\n",
                remainingPinTries));
        sb.append(envThreat
                ? "❌ MASVS-RESILIENCE 5: Phát hiện môi trường Root/Debugger!"
                : "✓ MASVS-RESILIENCE 5: Không phát hiện Root / Debugger");

        tvSecurityAuditSummary.setText(sb.toString());
    }

    private void showCiphertextAndApduInspectorDialog() {
        final Context appCtx = appContext();
        ApduThreadPool.getInstance().executeAsyncOnPool(() -> {
            CardResponse plainResp = ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_GET_STUDENT_INFO,
                    ApduCommands.P1_INFO_DECRYPTED,
                    (byte) 0x00,
                    null
            );
            CardResponse cipherResp = ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_GET_STUDENT_INFO,
                    ApduCommands.P1_INFO_RAW_CIPHERTEXT,
                    (byte) 0x00,
                    null
            );
            String plainText = new String(plainResp.getData(), StandardCharsets.UTF_8);
            String cipherHex = ApduCommands.bytesToHex(cipherResp.getData());
            String leakContent = chaosManager.getPlaintextLeakContent(appCtx);
            String exposedPin = chaosManager.getExposedPinInHeap();
            List<String> logs = ApduClient.getInstance().getRecentApduLogs();
            return new InspectorData(plainText, cipherHex, leakContent, exposedPin, logs);
        }, (data, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;

            StringBuilder sb = new StringBuilder();
            sb.append("=== 1. ĐỐI CHIẾU MÃ HÓA AES-128 TRÊN EEPROM ===\n");
            sb.append("• Bản rõ sau giải mã (INS 0x21 • P1=0x00):\n  ")
                    .append(data.plainUtf8).append("\n\n");
            sb.append("• Bản mã thô AES-128 trong EEPROM (INS 0x21 • P1=0x01):\n  ")
                    .append(data.cipherHex).append("\n\n");

            sb.append("=== 2. KIỂM CHỨNG RÒ RỈ LƯU TRỮ & RAM ===\n");
            sb.append("• SharedPreferences (insecure_plaintext_leak):\n  ")
                    .append(data.leakContent != null
                            ? "❌ ĐANG LỘ BẢN RÕ: " + data.leakContent
                            : "✓ Sạch — Chỉ lưu mã hóa AES-256-GCM trong EncryptedSharedPreferences")
                    .append("\n\n");
            sb.append("• Vùng nhớ Heap chứa mã PIN:\n  ")
                    .append(data.exposedPin != null
                            ? "❌ PHƠI NHIỄM 100%: \"" + data.exposedPin + "\""
                            : "✓ Đã Zeroize (0%) — Dùng char[] và Arrays.fill('\\0')")
                    .append("\n\n");

            sb.append("=== 3. NHẬT KÝ GÓI TIN APDU HEX GẦN NHẤT ===\n");
            int start = Math.max(0, data.apduLogs.size() - 8);
            for (int i = start; i < data.apduLogs.size(); i++) {
                sb.append(data.apduLogs.get(i)).append("\n\n");
            }

            ScrollView scrollView = new ScrollView(requireContext());
            TextView tv = new TextView(requireContext());
            int pad = dp(18);
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextSize(12f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(0xFF0F172A);
            tv.setText(sb.toString().trim());
            scrollView.addView(tv);

            new AlertDialog.Builder(requireContext())
                    .setTitle("Bản mã AES, Rò rỉ Dữ liệu & APDU Hex")
                    .setView(scrollView)
                    .setPositiveButton("Đóng", null)
                    .show();
        });
    }

    private String msText(long ms) {
        return ms <= 0 ? "<1 ms" : ms + " ms";
    }

    private String sizeText(int bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class CacheOpen {
        final StudentCardRecord record;
        final byte[] avatarBytes;
        final boolean fromCache;
        final int apduCount;

        CacheOpen(StudentCardRecord record, byte[] avatarBytes, boolean fromCache, int apduCount) {
            this.record = record;
            this.avatarBytes = avatarBytes;
            this.fromCache = fromCache;
            this.apduCount = apduCount;
        }
    }

    private static final class PreviewResult {
        final Bitmap bitmap;
        final int width;
        final int height;

        PreviewResult(Bitmap bitmap, int width, int height) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
        }
    }

    private static final class ImageRunResult {
        final Map<String, String> metrics;
        final Bitmap preview;
        final String status;
        final boolean success;
        final double sizeKb;
        final int packets;
        final double ramMb;

        ImageRunResult(Map<String, String> metrics, Bitmap preview, String status, boolean success,
                       double sizeKb, int packets, double ramMb) {
            this.metrics = metrics;
            this.preview = preview;
            this.status = status;
            this.success = success;
            this.sizeKb = sizeKb;
            this.packets = packets;
            this.ramMb = ramMb;
        }

        static ImageRunResult failure(String status) {
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Kết quả", "Lỗi");
            return new ImageRunResult(metrics, null, status, false, 0, 0, 0);
        }
    }

    private static final class PinRunResult {
        final long ms;
        final int remaining;
        final int length;

        PinRunResult(long ms, int remaining, int length) {
            this.ms = ms;
            this.remaining = remaining;
            this.length = length;
        }
    }

    private static class InspectorData {
        final String plainUtf8;
        final String cipherHex;
        final String leakContent;
        final String exposedPin;
        final List<String> apduLogs;

        InspectorData(String plainUtf8, String cipherHex, String leakContent,
                      String exposedPin, List<String> apduLogs) {
            this.plainUtf8 = plainUtf8;
            this.cipherHex = cipherHex;
            this.leakContent = leakContent;
            this.exposedPin = exposedPin;
            this.apduLogs = apduLogs;
        }
    }
}
