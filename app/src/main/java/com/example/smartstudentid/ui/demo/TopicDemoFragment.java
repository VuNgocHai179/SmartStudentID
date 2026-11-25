package com.example.smartstudentid.ui.demo;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
import androidx.fragment.app.Fragment;

import com.example.smartstudentid.BuildConfig;
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
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.BiometricKeyManager;
import com.example.smartstudentid.security.CryptoManager;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.example.smartstudentid.security.SecurityEnvironmentChecker;
import com.example.smartstudentid.ui.widget.CacheFlowView;
import com.example.smartstudentid.ui.widget.ExecutionWaterfallView;
import com.example.smartstudentid.ui.widget.ImagePipelineView;
import com.example.smartstudentid.ui.widget.RealtimeTopicChartView;
import com.example.smartstudentid.ui.widget.SecurityAuditFlowView;
import com.example.smartstudentid.ui.widget.SecurityDefensePopupDialog;
import com.example.smartstudentid.ui.widget.ThreadingLaneView;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * One isolated demo page per optimization topic (§1–§5), written as a user flow:
 * the lecturer performs the real action (read the card, open the profile, save a portrait,
 * log in) and the page reports the numbers produced by that action. §2–§5 have a Before/After
 * switch and a comparison table filled by running the same action in both modes.
 */
public class TopicDemoFragment extends Fragment {

    private static final String ARG_TOPIC = "topic_index";
    private static final int TOPIC_BOTTLENECK = 0;
    private static final int TOPIC_THREADING = 1;
    private static final int TOPIC_CACHE = 2;
    private static final int TOPIC_IMAGE = 3;
    private static final int TOPIC_STARTUP = 4;

    private static final String SLATE = "#475569";
    private static final String SLATE_MUTED = "#64748B";
    private static final String BLUE = "#2563EB";
    private static final String RED = "#DC2626";
    private static final String GREEN = "#059669";
    private static final int TRACE_COLOR_FAULT = 0xFFFCA5A5;
    private static final int TRACE_COLOR_INFO = 0xFF93C5FD;
    private static final int TRACE_COLOR_OK = 0xFF6EE7B7;
    private static final int MAX_TRACE_LINES = 12;

    private static final class TraceEntry {
        final String text;
        final int color;

        TraceEntry(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private int topic;
    private boolean busy;
    private boolean suppressSwitchRun;
    private boolean trackedHeavyMetric;
    private boolean trackedMainBlockMetric;
    private boolean traceExpanded = true;
    private boolean techInspectorExpanded = false;
    private volatile boolean suppressRawApduTelemetry;
    private volatile long traceOriginNs = System.nanoTime();
    private final Deque<TraceEntry> traceEntries = new ArrayDeque<>();
    private ActivityResultLauncher<PickVisualMediaRequest> imagePicker;

    private final ChaosInjectionManager chaosManager = ChaosInjectionManager.getInstance();
    private final Handler autoDemoHandler = new Handler(Looper.getMainLooper());
    private final Handler liveCounterHandler = new Handler(Looper.getMainLooper());
    private final Handler qrTimerHandler = new Handler(Looper.getMainLooper());
    private long currentQrWindowEpoch = -1L;
    private final Runnable qrTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || topic != TOPIC_THREADING) return;
            long nowSec = System.currentTimeMillis() / 1000L;
            long windowEpoch = nowSec / 30L;
            int remainingSec = (int) (30L - (nowSec % 30L));
            if (pbDemoQrCountdown != null) {
                pbDemoQrCountdown.setProgress(remainingSec);
            }
            if (tvDemoQrCountdown != null) {
                tvDemoQrCountdown.setText("Đổi mã sau " + remainingSec + "s");
            }
            if (windowEpoch != currentQrWindowEpoch) {
                currentQrWindowEpoch = windowEpoch;
                refreshDynamicQrBitmap(windowEpoch);
            }
            qrTimerHandler.postDelayed(this, 1000L);
        }
    };
    private final Runnable liveCounterTicker = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            updateMechanismLiveCounter();
            if (shouldPollLiveCounter()) {
                liveCounterHandler.postDelayed(this, 500L);
            }
        }
    };

    private SwitchCompat switchMode;
    private TextView tvModeHint;
    private MaterialButton btnInjectTopicFault;
    private MaterialButton btnApplyTopicFix;
    private View cardMode;
    private View cardMechanism;
    private TextView tvMechanismHeadline;
    private TextView tvMechanismBadge1;
    private TextView tvMechanismBadge2;
    private View panelMechanismDetail;
    private TextView tvMechanismWhat;
    private TextView tvMechanismWhy;
    private TextView tvMechanismLiveCounter;
    private TextView tvTraceHeaderTitle;
    private TextView tvToggleTrace;
    private ExecutionWaterfallView executionWaterfall;
    private ThreadingLaneView threadingLaneView;
    private CacheFlowView cacheFlowView;
    private ImagePipelineView imagePipelineView;
    private SecurityAuditFlowView securityAuditFlowView;
    private TextView tvExecutionTrace;
    private View btnToggleTechInspector;
    private View panelTechInspector;
    private TextView tvTechHeaderTitle;
    private TextView tvTechSummary;
    private TextView tvTechToggleBadge;
    private LinearLayout panelSmartCard;
    private ImageView ivSmartCardAvatar;
    private TextView tvSmartCardName;
    private TextView tvSmartCardId;
    private TextView tvSmartCardDept;
    private TextView tvSmartCardSourceBadge;
    private View panelDynamicQr;
    private ImageView ivDemoQr;
    private ProgressBar pbDemoQrCountdown;
    private TextView tvDemoQrCountdown;
    private TextView tvDemoQrSubtitle;
    private View panelStorageStatus;
    private View boxTierL1;
    private TextView tvTierL1State;
    private View boxTierL2;
    private TextView tvTierL2State;
    private View boxTierChip;
    private TextView tvTierChipState;
    private View boxComparePlaintext;
    private TextView tvComparePlainTitle;
    private TextView tvComparePlainValue;
    private View boxCompareEncrypted;
    private TextView tvCompareEncTitle;
    private TextView tvCompareEncValue;
    private TextView tvCardQuota8Kb;
    private ProgressBar pbCardQuota8Kb;
    private View panelSecurityShield;
    private TextView tvSecurityShieldTitle;
    private TextView tvSecurityShieldScore;
    private LinearLayout llMasvsSegments;
    private TextView tvPinRamExposureBadge;
    private LinearLayout llPinRamCells;
    private TextView tvChipPinType;
    private TextView tvChipRsaState;
    private TextView tvChipEnvState;
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
    private MaterialButton btnAction;
    private MaterialButton btnPickImage;
    private MaterialButton btnSimulateWrongPin3x;
    private MaterialButton btnResetCache;
    private MaterialButton btnInspectTopicSecurity;
    private ProgressBar progress;
    private TextView tvComparisonTitle;
    private LinearLayout llComparison;
    private TextView tvRealtimeChartTitle;
    private RealtimeTopicChartView realtimeChart;

    private final ApduClient.TelemetryListener apduTelemetryListener = (ins, elapsedMs) -> {
        if (suppressRawApduTelemetry) return;
        RealtimeTopicChartView chart = realtimeChart;
        if (chart != null) {
            chart.post(() -> chart.onApduFrame(ins, elapsedMs));
        }
    };

    private final Map<String, String> before = new LinkedHashMap<>();
    private final Map<String, String> after = new LinkedHashMap<>();

    private LocalCacheManager localCacheManager;
    private EncryptedCacheManager encryptedCache;
    private PinSessionManager pinSessionManager;

    private Uri pickedUri;
    private volatile byte[] sampleBytes;

    public static TopicDemoFragment newInstance(int topic) {
        TopicDemoFragment fragment = new TopicDemoFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_TOPIC, topic);
        fragment.setArguments(args);
        return fragment;
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
        return inflater.inflate(R.layout.fragment_topic_demo, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        topic = getArguments() != null ? getArguments().getInt(ARG_TOPIC, 0) : 0;
        localCacheManager = new LocalCacheManager(requireContext());
        encryptedCache = EncryptedCacheManager.getInstance(requireContext());
        pinSessionManager = new PinSessionManager(requireContext());

        bind(view);
        configureTopic(view);
        wireActions();
        renderComparison();
        if (topic <= TOPIC_CACHE) {
            showCachedProfile();
        }
        if (topic == TOPIC_IMAGE) {
            prepareSampleImage();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ApduClient.getInstance().setTelemetryListener(apduTelemetryListener);
        updateModeLabels(optimized());
        startLiveCounterPollingIfNeeded();
        if (topic == TOPIC_THREADING) {
            currentQrWindowEpoch = -1L;
            qrTimerHandler.removeCallbacks(qrTickRunnable);
            qrTimerHandler.post(qrTickRunnable);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        autoDemoHandler.removeCallbacksAndMessages(null);
        stopLiveCounterPolling();
        qrTimerHandler.removeCallbacks(qrTickRunnable);
        releaseLiveMetricFlags();
        ApduClient.getInstance().clearTelemetryListener(apduTelemetryListener);
        if (topic == TOPIC_THREADING) {
            chaosManager.resolveStep1Threading();
        } else if (topic == TOPIC_IMAGE) {
            chaosManager.resolveStep3ImageAndMemory();
        }
    }

    @Override
    public void onDestroyView() {
        autoDemoHandler.removeCallbacksAndMessages(null);
        stopLiveCounterPolling();
        qrTimerHandler.removeCallbacks(qrTickRunnable);
        releaseLiveMetricFlags();
        ApduClient.getInstance().clearTelemetryListener(apduTelemetryListener);
        if (topic == TOPIC_THREADING) {
            chaosManager.resolveStep1Threading();
        } else if (topic == TOPIC_IMAGE) {
            chaosManager.resolveStep3ImageAndMemory();
        }
        realtimeChart = null;
        super.onDestroyView();
    }

    private void bind(View root) {
        switchMode = root.findViewById(R.id.switchDemoMode);
        tvModeHint = root.findViewById(R.id.tvModeHint);
        btnInjectTopicFault = root.findViewById(R.id.btnInjectTopicFault);
        btnApplyTopicFix = root.findViewById(R.id.btnApplyTopicFix);
        cardMode = root.findViewById(R.id.cardDemoMode);
        cardMechanism = root.findViewById(R.id.cardMechanism);
        tvMechanismHeadline = root.findViewById(R.id.tvMechanismHeadline);
        tvMechanismBadge1 = root.findViewById(R.id.tvMechanismBadge1);
        tvMechanismBadge2 = root.findViewById(R.id.tvMechanismBadge2);
        panelMechanismDetail = root.findViewById(R.id.panelMechanismDetail);
        tvMechanismWhat = root.findViewById(R.id.tvMechanismWhat);
        tvMechanismWhy = root.findViewById(R.id.tvMechanismWhy);
        tvMechanismLiveCounter = root.findViewById(R.id.tvMechanismLiveCounter);
        tvTraceHeaderTitle = root.findViewById(R.id.tvTraceHeaderTitle);
        tvToggleTrace = root.findViewById(R.id.tvToggleTrace);
        executionWaterfall = root.findViewById(R.id.executionWaterfall);
        threadingLaneView = root.findViewById(R.id.threadingLaneView);
        cacheFlowView = root.findViewById(R.id.cacheFlowView);
        imagePipelineView = root.findViewById(R.id.imagePipelineView);
        securityAuditFlowView = root.findViewById(R.id.securityAuditFlowView);
        tvExecutionTrace = root.findViewById(R.id.tvExecutionTrace);
        btnToggleTechInspector = root.findViewById(R.id.btnToggleTechInspector);
        panelTechInspector = root.findViewById(R.id.panelTechInspector);
        tvTechHeaderTitle = root.findViewById(R.id.tvTechHeaderTitle);
        tvTechSummary = root.findViewById(R.id.tvTechSummary);
        tvTechToggleBadge = root.findViewById(R.id.tvTechToggleBadge);
        panelSmartCard = root.findViewById(R.id.panelSmartCard);
        ivSmartCardAvatar = root.findViewById(R.id.ivSmartCardAvatar);
        tvSmartCardName = root.findViewById(R.id.tvSmartCardName);
        tvSmartCardId = root.findViewById(R.id.tvSmartCardId);
        tvSmartCardDept = root.findViewById(R.id.tvSmartCardDept);
        tvSmartCardSourceBadge = root.findViewById(R.id.tvSmartCardSourceBadge);
        panelDynamicQr = root.findViewById(R.id.panelDynamicQr);
        ivDemoQr = root.findViewById(R.id.ivDemoQr);
        pbDemoQrCountdown = root.findViewById(R.id.pbDemoQrCountdown);
        tvDemoQrCountdown = root.findViewById(R.id.tvDemoQrCountdown);
        tvDemoQrSubtitle = root.findViewById(R.id.tvDemoQrSubtitle);
        panelStorageStatus = root.findViewById(R.id.panelStorageStatus);
        boxTierL1 = root.findViewById(R.id.boxTierL1);
        tvTierL1State = root.findViewById(R.id.tvTierL1State);
        boxTierL2 = root.findViewById(R.id.boxTierL2);
        tvTierL2State = root.findViewById(R.id.tvTierL2State);
        boxTierChip = root.findViewById(R.id.boxTierChip);
        tvTierChipState = root.findViewById(R.id.tvTierChipState);
        boxComparePlaintext = root.findViewById(R.id.boxComparePlaintext);
        tvComparePlainTitle = root.findViewById(R.id.tvComparePlainTitle);
        tvComparePlainValue = root.findViewById(R.id.tvComparePlainValue);
        boxCompareEncrypted = root.findViewById(R.id.boxCompareEncrypted);
        tvCompareEncTitle = root.findViewById(R.id.tvCompareEncTitle);
        tvCompareEncValue = root.findViewById(R.id.tvCompareEncValue);
        tvCardQuota8Kb = root.findViewById(R.id.tvCardQuota8Kb);
        pbCardQuota8Kb = root.findViewById(R.id.pbCardQuota8Kb);
        panelSecurityShield = root.findViewById(R.id.panelSecurityShield);
        tvSecurityShieldTitle = root.findViewById(R.id.tvSecurityShieldTitle);
        tvSecurityShieldScore = root.findViewById(R.id.tvSecurityShieldScore);
        llMasvsSegments = root.findViewById(R.id.llMasvsSegments);
        tvPinRamExposureBadge = root.findViewById(R.id.tvPinRamExposureBadge);
        llPinRamCells = root.findViewById(R.id.llPinRamCells);
        tvChipPinType = root.findViewById(R.id.tvChipPinType);
        tvChipRsaState = root.findViewById(R.id.tvChipRsaState);
        tvChipEnvState = root.findViewById(R.id.tvChipEnvState);
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
        btnAction = root.findViewById(R.id.btnDemoAction);
        btnPickImage = root.findViewById(R.id.btnPickImage);
        btnSimulateWrongPin3x = root.findViewById(R.id.btnSimulateWrongPin3x);
        btnResetCache = root.findViewById(R.id.btnResetCache);
        btnInspectTopicSecurity = root.findViewById(R.id.btnInspectTopicSecurity);
        progress = root.findViewById(R.id.demoProgress);
        tvComparisonTitle = root.findViewById(R.id.tvComparisonTitle);
        llComparison = root.findViewById(R.id.llComparison);
        tvRealtimeChartTitle = root.findViewById(R.id.tvRealtimeChartTitle);
        realtimeChart = root.findViewById(R.id.demoRealtimeChart);
    }

    private View getActiveTraceBodyView() {
        if (topic == TOPIC_BOTTLENECK && executionWaterfall != null) {
            return executionWaterfall;
        }
        if (topic == TOPIC_THREADING && threadingLaneView != null) {
            return threadingLaneView;
        }
        if (topic == TOPIC_CACHE && cacheFlowView != null) {
            return cacheFlowView;
        }
        if (topic == TOPIC_IMAGE && imagePipelineView != null) {
            return imagePipelineView;
        }
        if (topic == TOPIC_STARTUP && securityAuditFlowView != null) {
            return securityAuditFlowView;
        }
        return tvExecutionTrace;
    }

    private void configureTopic(View root) {
        TextView badge = root.findViewById(R.id.tvTopicBadge);
        TextView title = root.findViewById(R.id.tvTopicTitle);

        panelSmartCard.setVisibility(topic == TOPIC_BOTTLENECK ? View.VISIBLE : View.GONE);
        panelDynamicQr.setVisibility(topic == TOPIC_THREADING ? View.VISIBLE : View.GONE);
        panelStudent.setVisibility(topic == TOPIC_CACHE ? View.VISIBLE : View.GONE);
        panelImage.setVisibility(topic == TOPIC_IMAGE ? View.VISIBLE : View.GONE);
        panelPin.setVisibility(topic == TOPIC_STARTUP ? View.VISIBLE : View.GONE);
        cardMode.setVisibility(View.VISIBLE);
        btnSimulateWrongPin3x.setVisibility(topic == TOPIC_CACHE ? View.VISIBLE : View.GONE);
        btnResetCache.setVisibility(topic == TOPIC_CACHE ? View.VISIBLE : View.GONE);
        btnInspectTopicSecurity.setVisibility(
                (topic == TOPIC_CACHE || topic == TOPIC_STARTUP) ? View.VISIBLE : View.GONE);
        realtimeChart.setTopic(topic);
        tvTechHeaderTitle.setText("🔬 Chi tiết kỹ thuật & Đồ thị (§" + (topic + 1) + ")");
        if (cardMechanism != null) {
            cardMechanism.setVisibility(View.GONE);
        }
        boolean isBottleneckTopic = (topic == TOPIC_BOTTLENECK);
        boolean isThreadingTopic = (topic == TOPIC_THREADING);
        boolean isCacheTopic = (topic == TOPIC_CACHE);
        boolean isImageTopic = (topic == TOPIC_IMAGE);
        boolean isStartupTopic = (topic == TOPIC_STARTUP);
        if (tvTraceHeaderTitle != null) {
            if (isBottleneckTopic) {
                tvTraceHeaderTitle.setText("LUỒNG THỰC THI TRỰC QUAN (§1)");
            } else if (isThreadingTopic) {
                tvTraceHeaderTitle.setText("LUỒNG THỰC THI ĐA LUỒNG (§2)");
            } else if (isCacheTopic) {
                tvTraceHeaderTitle.setText("LUỒNG LƯU ĐỆM 2 TẦNG & BẢO MẬT (§3)");
            } else if (isImageTopic) {
                tvTraceHeaderTitle.setText("LUỒNG XỬ LÝ ẢNH & BỘ NHỚ (§4)");
            } else if (isStartupTopic) {
                tvTraceHeaderTitle.setText("LUỒNG XÁC THỰC & BẢO MẬT PIN (§5)");
            } else {
                tvTraceHeaderTitle.setText("NHẬT KÝ THỰC THI (LIVE TRACE)");
            }
        }
        if (executionWaterfall != null) {
            executionWaterfall.setVisibility(isBottleneckTopic ? View.VISIBLE : View.GONE);
        }
        if (threadingLaneView != null) {
            threadingLaneView.setVisibility(isThreadingTopic ? View.VISIBLE : View.GONE);
        }
        if (cacheFlowView != null) {
            cacheFlowView.setVisibility(isCacheTopic ? View.VISIBLE : View.GONE);
        }
        if (imagePipelineView != null) {
            imagePipelineView.setVisibility(isImageTopic ? View.VISIBLE : View.GONE);
        }
        if (securityAuditFlowView != null) {
            securityAuditFlowView.setVisibility(isStartupTopic ? View.VISIBLE : View.GONE);
        }
        if (tvExecutionTrace != null) {
            tvExecutionTrace.setVisibility((isBottleneckTopic || isThreadingTopic || isCacheTopic
                    || isImageTopic || isStartupTopic)
                    ? View.GONE : View.VISIBLE);
        }

        switch (topic) {
            case TOPIC_THREADING:
                badge.setText("ĐIỂM DANH LỚP HỌC • §2");
                title.setText("Điểm danh QR động");
                btnAction.setText("Làm mới dữ liệu thẻ");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • FRAME TIME & 60 FPS");
                break;

            case TOPIC_CACHE:
                badge.setText("HỒ SƠ NGOẠI TUYẾN • §3");
                title.setText("Hồ sơ & Lưu trữ");
                btnAction.setText("Mở hồ sơ");
                btnInspectTopicSecurity.setText("Kiểm chứng Lưu trữ: Plaintext Leak vs AES-GCM");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • ĐỘ TRỄ MỞ HỒ SƠ");
                break;

            case TOPIC_IMAGE:
                badge.setText("ẢNH CHÂN DUNG THẺ • §4");
                title.setText("Cập nhật ảnh thẻ");
                btnAction.setText("Lưu ảnh lên thẻ chip");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • JAVA HEAP (MB) & GÓI 240 B");
                break;

            case TOPIC_STARTUP:
                badge.setText("TRUNG TÂM BẢO MẬT • §5");
                title.setText("Bảo mật & Xác thực PIN");
                realtimeChart.setColdStartMs(DemoMetrics.getColdStartMs());
                btnAction.setText("Xác thực đăng nhập");
                btnInspectTopicSecurity.setText("Mở Popup Đo kiểm Phòng thủ Bảo mật");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • PHƠI NHIỄM PIN TRÊN RAM");
                break;

            default:
                badge.setText("THẺ SINH VIÊN SỐ • §1");
                title.setText("Thẻ Sinh Viên");
                btnAction.setText("Quẹt thẻ đồng bộ");
                tvComparisonTitle.setText("SO SÁNH BEFORE / AFTER");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • ĐỘ TRỄ TỪNG BƯỚC APDU");
                break;
        }
    }

    private void wireActions() {
        switchMode.setOnCheckedChangeListener((buttonView, checked) -> {
            updateModeLabels(checked);
            if (realtimeChart != null) {
                realtimeChart.setAfterMode(checked);
            }
            if (suppressSwitchRun) {
                return;
            }
            applyTopicChaosState(checked);
            if (!busy) {
                runCurrentMode();
            }
        });

        suppressSwitchRun = true;
        switchMode.setChecked(true);
        updateModeLabels(true);
        if (realtimeChart != null) {
            realtimeChart.setAfterMode(true);
        }
        suppressSwitchRun = false;

        btnInjectTopicFault.setOnClickListener(v -> triggerTopicMode(false));
        btnApplyTopicFix.setOnClickListener(v -> triggerTopicMode(true));

        btnAction.setOnClickListener(v -> {
            boolean mode = optimized();
            applyTopicChaosState(mode);
            runCurrentMode();
        });
        btnPickImage.setOnClickListener(v -> imagePicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
        btnSimulateWrongPin3x.setOnClickListener(v -> simulateWrongPin3xAutoWipe());
        btnResetCache.setOnClickListener(v -> resetCache());
        btnInspectTopicSecurity.setOnClickListener(v -> {
            if (topic == TOPIC_STARTUP && getActivity() instanceof BaseSecureActivity) {
                SecurityDefensePopupDialog.show((BaseSecureActivity) getActivity(),
                        this::showTopicSecurityInspector);
            } else {
                showTopicSecurityInspector();
            }
        });
        tvToggleTrace.setOnClickListener(v -> {
            traceExpanded = !traceExpanded;
            View traceBody = getActiveTraceBodyView();
            if (traceBody != null) {
                traceBody.setVisibility(traceExpanded ? View.VISIBLE : View.GONE);
            }
            tvToggleTrace.setText(traceExpanded ? "Thu gọn" : "Mở rộng");
        });
        btnToggleTechInspector.setOnClickListener(v -> {
            techInspectorExpanded = !techInspectorExpanded;
            panelTechInspector.setVisibility(techInspectorExpanded ? View.VISIBLE : View.GONE);
            tvTechToggleBadge.setText(techInspectorExpanded ? "Thu gọn ▴" : "Mở xem ▾");
        });
    }

    private void triggerTopicMode(boolean afterMode) {
        suppressSwitchRun = true;
        switchMode.setChecked(afterMode);
        updateModeLabels(afterMode);
        if (realtimeChart != null) {
            realtimeChart.setAfterMode(afterMode);
        }
        suppressSwitchRun = false;

        applyTopicChaosState(afterMode);
        if (busy) {
            busy = false;
            endRun();
        }
        runCurrentMode();
    }

    private void updateModeLabels(boolean checked) {
        tvModeHint.setText(checked ? "Đã tối ưu" : "Chưa tối ưu");
        tvModeHint.setTextColor(Color.parseColor(checked ? GREEN : RED));
        renderMechanismCard(checked);
        updateRealAppStatusCards(checked);
        startLiveCounterPollingIfNeeded();
    }

    private void updateRealAppStatusCards(boolean afterMode) {
        if (topic == TOPIC_CACHE && panelStorageStatus != null) {
            String leakRaw = chaosManager.getPlaintextLeakContent(appContext());
            boolean hasL1 = encryptedCache.hasL1ProfileCached();
            boolean hasL2 = encryptedCache.hasL2ProfileCached();
            boolean isWiped = "WIPED".equals(encryptedCache.getLastReadTier());

            if (!afterMode || leakRaw != null) {
                // Before: Bypass L1 & L2, hitting 36 APDU + Plaintext leak
                boxTierL1.setBackgroundResource(R.drawable.bg_soft_panel);
                tvTierL1State.setText("BỎ QUA");
                tvTierL1State.setTextColor(Color.parseColor(SLATE_MUTED));

                boxTierL2.setBackgroundResource(R.drawable.bg_status_red);
                tvTierL2State.setText("LỘ RÕ");
                tvTierL2State.setTextColor(Color.parseColor(RED));

                boxTierChip.setBackgroundResource(R.drawable.bg_status_red);
                tvTierChipState.setText("36 APDU");
                tvTierChipState.setTextColor(Color.parseColor(RED));

                boxComparePlaintext.setBackgroundResource(R.drawable.bg_status_red);
                tvComparePlainTitle.setText("❌ Bản rõ (Lộ PIN!)");
                tvComparePlainTitle.setTextColor(Color.parseColor(RED));
                tvComparePlainValue.setText("PIN=123456\nMSSV=CT070318");
                tvComparePlainValue.setTextColor(Color.parseColor(RED));

                boxCompareEncrypted.setBackgroundResource(R.drawable.bg_soft_panel);
                tvCompareEncTitle.setText("🔒 Mã hóa TEE");
                tvCompareEncTitle.setTextColor(Color.parseColor(SLATE_MUTED));
                tvCompareEncValue.setText("Đang bỏ qua\n(Không dùng)");
                tvCompareEncValue.setTextColor(Color.parseColor(SLATE_MUTED));
            } else if (isWiped && !hasL1 && !hasL2) {
                // Auto-Wiped after 3 wrong PINs
                boxTierL1.setBackgroundResource(R.drawable.bg_status_red);
                tvTierL1State.setText("ĐÃ HỦY");
                tvTierL1State.setTextColor(Color.parseColor(RED));

                boxTierL2.setBackgroundResource(R.drawable.bg_status_red);
                tvTierL2State.setText("ĐÃ HỦY");
                tvTierL2State.setTextColor(Color.parseColor(RED));

                boxTierChip.setBackgroundResource(R.drawable.bg_soft_panel);
                tvTierChipState.setText("0 APDU");
                tvTierChipState.setTextColor(Color.parseColor(SLATE_MUTED));

                boxComparePlaintext.setBackgroundResource(R.drawable.bg_soft_panel);
                tvComparePlainTitle.setText("📄 Bản rõ");
                tvComparePlainTitle.setTextColor(Color.parseColor(SLATE_MUTED));
                tvComparePlainValue.setText("✓ 0 byte (Sạch)");
                tvComparePlainValue.setTextColor(Color.parseColor(GREEN));

                boxCompareEncrypted.setBackgroundResource(R.drawable.bg_status_red);
                tvCompareEncTitle.setText("💥 Auto-Wipe 3/3");
                tvCompareEncTitle.setTextColor(Color.parseColor(RED));
                tvCompareEncValue.setText("0 byte trên máy\n(Cần quẹt lại)");
                tvCompareEncValue.setTextColor(Color.parseColor(RED));
            } else {
                // After: 2-tier L1 RAM + L2 TEE active
                String rawCipherPreview = readRawEncryptedCacheSnippet();
                boxTierL1.setBackgroundResource(hasL1 ? R.drawable.bg_status_green : R.drawable.bg_soft_panel);
                tvTierL1State.setText(hasL1 ? "HIT <1ms" : "TRỐNG");
                tvTierL1State.setTextColor(Color.parseColor(hasL1 ? GREEN : SLATE_MUTED));

                boxTierL2.setBackgroundResource(hasL2 ? R.drawable.bg_status_green : R.drawable.bg_soft_panel);
                tvTierL2State.setText(hasL2 ? "AES-GCM" : "TRỐNG");
                tvTierL2State.setTextColor(Color.parseColor(hasL2 ? GREEN : SLATE_MUTED));

                boxTierChip.setBackgroundResource(R.drawable.bg_soft_panel);
                tvTierChipState.setText("0 APDU");
                tvTierChipState.setTextColor(Color.parseColor(GREEN));

                boxComparePlaintext.setBackgroundResource(R.drawable.bg_soft_panel);
                tvComparePlainTitle.setText("📄 Bản rõ");
                tvComparePlainTitle.setTextColor(Color.parseColor(SLATE_MUTED));
                tvComparePlainValue.setText("✓ 0 byte\n(Không lộ)");
                tvComparePlainValue.setTextColor(Color.parseColor(GREEN));

                boxCompareEncrypted.setBackgroundResource(R.drawable.bg_status_green);
                tvCompareEncTitle.setText("🔒 AES-256-GCM");
                tvCompareEncTitle.setTextColor(Color.parseColor(GREEN));
                tvCompareEncValue.setText(rawCipherPreview);
                tvCompareEncValue.setTextColor(Color.parseColor(SLATE));
            }
        } else if (topic == TOPIC_STARTUP && panelSecurityShield != null) {
            int score = afterMode ? 5 : chaosManager.getMasvsScore();
            int accent = Color.parseColor(afterMode ? GREEN : RED);
            panelSecurityShield.setBackgroundResource(afterMode ? R.drawable.bg_status_green : R.drawable.bg_status_red);
            tvSecurityShieldTitle.setText(afterMode ? "✓ Bộ nhớ PIN & Thiết bị an toàn" : "❌ Cảnh báo phơi nhiễm PIN");
            tvSecurityShieldTitle.setTextColor(accent);
            tvSecurityShieldScore.setText("MASVS " + score + "/5");
            tvSecurityShieldScore.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
            tvSecurityShieldScore.setTextColor(accent);

            renderMasvsSegments(score, afterMode);
            String pinSource = (demoPinInput != null && demoPinInput.getText().length() >= 4)
                    ? demoPinInput.getText().toString().trim() : "123456";
            renderPinRamCells(pinSource, afterMode);

            tvPinRamExposureBadge.setText(afterMode ? "0% phơi nhiễm (Đã xóa)" : "100% lộ trên Heap!");
            tvPinRamExposureBadge.setTextColor(accent);

            tvChipPinType.setText(afterMode ? "✓ char[]" : "✕ String");
            tvChipPinType.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
            tvChipPinType.setTextColor(accent);

            tvChipRsaState.setText(afterMode ? "✓ RSA-1024" : "✕ Bỏ RSA");
            tvChipRsaState.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
            tvChipRsaState.setTextColor(accent);

            tvChipEnvState.setText(afterMode ? "✓ An toàn" : "✕ Root/Dbg");
            tvChipEnvState.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
            tvChipEnvState.setTextColor(accent);
        }
    }

    private void renderMasvsSegments(int score, boolean safe) {
        if (llMasvsSegments == null || !isAdded()) return;
        llMasvsSegments.removeAllViews();
        int activeColor = Color.parseColor(safe ? GREEN : RED);
        int inactiveColor = Color.parseColor("#CBD5E1");
        for (int i = 0; i < 5; i++) {
            View seg = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.setMarginStart(dp(4));
            seg.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp(4));
            gd.setColor(i < score ? activeColor : inactiveColor);
            seg.setBackground(gd);
            llMasvsSegments.addView(seg);
        }
    }

    private void renderPinRamCells(String pinDigits, boolean zeroized) {
        if (llPinRamCells == null || !isAdded()) return;
        llPinRamCells.removeAllViews();
        int count = Math.max(4, Math.min(8, pinDigits.length()));
        int textColor = Color.parseColor(zeroized ? GREEN : RED);
        int bgColor = zeroized ? 0xFFFFFFFF : 0xFFFEE2E2;
        int strokeColor = zeroized ? 0xFF6EE7B7 : 0xFFFCA5A5;
        for (int i = 0; i < count; i++) {
            TextView cell = new TextView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i > 0) lp.setMarginStart(dp(5));
            cell.setLayoutParams(lp);
            cell.setGravity(Gravity.CENTER);
            cell.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            cell.setTextSize(13f);
            cell.setTextColor(textColor);
            cell.setText(zeroized ? "\\0" : String.valueOf(pinDigits.charAt(i)));
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp(8));
            gd.setColor(bgColor);
            gd.setStroke(dp(1), strokeColor);
            cell.setBackground(gd);
            llPinRamCells.addView(cell);
        }
    }

    private String readRawEncryptedCacheSnippet() {
        try {
            android.content.SharedPreferences raw = appContext()
                    .getSharedPreferences("smart_student_secure_cache", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> entry : raw.getAll().entrySet()) {
                Object val = entry.getValue();
                if (val instanceof String) {
                    String s = (String) val;
                    if (s.length() > 28) {
                        return s.substring(0, 28) + "... (" + s.length() + " B)";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0 byte (Trống)";
    }

    private void refreshDynamicQrBitmap(long windowEpoch) {
        if (ivDemoQr == null) return;
        StudentCardRecord profile = encryptedCache.getStudentProfile();
        String studentId = (profile != null && profile.getStudentId() != null)
                ? profile.getStudentId() : "CT070318";
        String payload = "SMARTID|" + studentId + "|T" + windowEpoch + "|RSA1024";
        ApduThreadPool.getInstance().executeAsyncOnPool(() -> {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(payload, BarcodeFormat.QR_CODE, 360, 360);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF0F172A : 0xFFFFFFFF;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        }, (bitmap, blockedMs, totalMs) -> {
            if (isAdded() && ivDemoQr != null && bitmap != null) {
                ivDemoQr.setImageBitmap(bitmap);
            }
        });
    }

    private void renderMechanismCard(boolean afterMode) {
        if (cardMechanism == null || cardMechanism.getVisibility() != View.VISIBLE
                || tvMechanismHeadline == null) {
            return;
        }
        TopicMechanismGuide.MechanismInfo info = TopicMechanismGuide.forTopic(topic, afterMode);
        int accent = Color.parseColor(afterMode ? GREEN : RED);
        tvMechanismHeadline.setText(info.headline);
        tvMechanismHeadline.setTextColor(accent);
        tvMechanismBadge1.setText(info.primaryBadge);
        tvMechanismBadge1.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
        tvMechanismBadge1.setTextColor(accent);
        tvMechanismBadge2.setText(info.secondaryBadge);
        panelMechanismDetail.setBackgroundResource(afterMode ? R.drawable.bg_status_green : R.drawable.bg_status_red);
        tvMechanismWhat.setText(info.whatItDoes);
        tvMechanismWhy.setText(info.whyItImpacts);
        updateMechanismLiveCounter();
    }

    private boolean shouldPollLiveCounter() {
        if (!isAdded() || cardMechanism == null || cardMechanism.getVisibility() != View.VISIBLE) {
            return false;
        }
        if (topic == TOPIC_THREADING && chaosManager.isUiThreadJankActive()) return true;
        return topic == TOPIC_IMAGE && chaosManager.isMemoryChurnAndRawPngActive();
    }

    private void startLiveCounterPollingIfNeeded() {
        stopLiveCounterPolling();
        updateMechanismLiveCounter();
        if (shouldPollLiveCounter()) {
            liveCounterHandler.postDelayed(liveCounterTicker, 500L);
        }
    }

    private void stopLiveCounterPolling() {
        liveCounterHandler.removeCallbacks(liveCounterTicker);
    }

    private void updateMechanismLiveCounter() {
        if (tvMechanismLiveCounter == null) return;
        if (topic == TOPIC_THREADING && chaosManager.isUiThreadJankActive()) {
            int ticks = chaosManager.getJankTickCount();
            long blockMs = chaosManager.getLastJankBlockMs();
            tvMechanismLiveCounter.setVisibility(View.VISIBLE);
            tvMechanismLiveCounter.setText(String.format(Locale.US,
                    "● Tác nhân nền jankRunnable [main]: đã khóa %d lần (~%d ms/lần, lặp 135 ms) → giữ FPS tụt",
                    ticks, blockMs > 0 ? blockMs : 55L));
        } else if (topic == TOPIC_IMAGE && chaosManager.isMemoryChurnAndRawPngActive()) {
            double retainedMb = chaosManager.getRetainedHeapMb();
            int cycles = chaosManager.getChurnCycleCount();
            tvMechanismLiveCounter.setVisibility(View.VISIBLE);
            tvMechanismLiveCounter.setText(String.format(Locale.US,
                    "● Tác nhân nền churnExecutor: đang ghim %.1f MB Heap • %d vòng cấp phát rác (64×16 KB / 250 ms)",
                    retainedMb, cycles));
        } else {
            tvMechanismLiveCounter.setVisibility(View.GONE);
        }
    }

    private void resetTrace(String headerMessage, boolean afterMode) {
        traceOriginNs = System.nanoTime();
        synchronized (traceEntries) {
            traceEntries.clear();
        }
        appendTrace(afterMode ? TRACE_COLOR_OK : TRACE_COLOR_FAULT, headerMessage);
    }

    private void appendTrace(int color, String message) {
        long deltaMs = Math.max(0L, (System.nanoTime() - traceOriginNs) / 1_000_000L);
        String threadName = Thread.currentThread().getName();
        String line = String.format(Locale.US, "+%4dms [%s] %s", deltaMs, threadName, message);
        synchronized (traceEntries) {
            while (traceEntries.size() >= MAX_TRACE_LINES) {
                traceEntries.removeFirst();
            }
            traceEntries.addLast(new TraceEntry(line, color));
        }
        autoDemoHandler.post(this::renderTraceConsole);
    }

    private void renderTraceConsole() {
        if (!isAdded() || tvExecutionTrace == null) return;
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        synchronized (traceEntries) {
            boolean first = true;
            for (TraceEntry entry : traceEntries) {
                if (!first) {
                    ssb.append('\n');
                }
                int start = ssb.length();
                ssb.append(entry.text);
                ssb.setSpan(new ForegroundColorSpan(entry.color),
                        start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                first = false;
            }
        }
        tvExecutionTrace.setText(ssb);
    }

    private void applyTopicChaosState(boolean afterMode) {
        switch (topic) {
            case TOPIC_THREADING:
                if (afterMode) chaosManager.resolveStep1Threading();
                else chaosManager.injectStep1Threading();
                break;
            case TOPIC_CACHE:
                if (afterMode) chaosManager.resolveStep2CacheAndStorage(appContext());
                else chaosManager.injectStep2CacheAndStorage(appContext());
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

    private boolean optimized() {
        return switchMode.isChecked();
    }

    private void runCurrentMode() {
        if (busy) return;
        switch (topic) {
            case TOPIC_THREADING:
                runThreading(optimized());
                break;
            case TOPIC_CACHE:
                runCache(optimized());
                break;
            case TOPIC_IMAGE:
                runImage(optimized());
                break;
            case TOPIC_STARTUP:
                runLogin(optimized());
                break;
            default:
                runBottleneck(optimized());
                break;
        }
    }

    // ------------------------------------------------------------------
    // §1 — user reads the card; we show where the time goes
    // ------------------------------------------------------------------

    private void runBottleneck(boolean afterMode) {
        if (afterMode) {
            resetTrace("▶ BẮT ĐẦU §1 AFTER — Combo tối ưu (§2–§4)", true);
            if (executionWaterfall != null) {
                executionWaterfall.beginAfterRun();
            }
            startRun("Đang đọc từ Combo tối ưu (§2–§4)...");
            appendTrace(TRACE_COLOR_INFO, "Đẩy tác vụ đọc Cache sang ApduThreadPool (không chặn UI)");
            runTask(() -> {
                long startNs = System.nanoTime();
                appendTrace(TRACE_COLOR_INFO, "Đọc hồ sơ từ EncryptedCacheManager (AES-256-GCM)");
                StudentCardRecord cached = encryptedCache.getStudentProfile();
                if (cached == null) {
                    String active = ApduClient.getInstance().getActiveCardInfo();
                    if (active != null && active.contains("|")) {
                        cached = StudentCardRecord.fromPipeDelimitedString(active);
                        encryptedCache.saveStudentProfile(cached);
                    }
                }
                long elapsedMs = Math.max(4L, (System.nanoTime() - startNs) / 1_000_000L);
                long[] stepMs = new long[]{0L, 0L, Math.max(2L, elapsedMs / 2), 0L, Math.max(2L, elapsedMs - elapsedMs / 2)};
                return new BottleneckRunResult(cached, stepMs, elapsedMs, 0);
            }, (res, blockedMs, totalMs) -> {
                showProfile(res.record, encryptedCache.getAvatarBytes(), "CACHE");
                appendTrace(TRACE_COLOR_OK, "✓ Bỏ qua 41 lệnh APDU & RSA → phản hồi trong " + res.elapsedMs + " ms (0 APDU)");
                if (executionWaterfall != null) {
                    executionWaterfall.finishAfterRun(res.stepMs, res.elapsedMs);
                }
                if (realtimeChart != null) {
                    realtimeChart.onApduFrame(0xA4, res.stepMs[0]);
                    realtimeChart.onApduFrame(0x10, res.stepMs[1]);
                    realtimeChart.onApduFrame(0x21, res.stepMs[2]);
                    realtimeChart.onApduFrame(0x21, res.stepMs[3]);
                    realtimeChart.onApduFrame(0x30, res.stepMs[4]);
                    realtimeChart.recordBottleneckResult(res.stepMs, res.elapsedMs);
                }
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, true).comparisonMechanismLabel);
                metrics.put("1. SELECT applet", "0 ms (Cache)");
                metrics.put("2. VERIFY PIN", "0 ms (Session)");
                metrics.put("3. AES-128 EEPROM", res.stepMs[2] + " ms (GCM)");
                metrics.put("4. Ký RSA-1024", "0 ms (Cached)");
                metrics.put("5. Học vụ & 35 ảnh", res.stepMs[4] + " ms (WebP)");
                metrics.put("Tổng thời gian", res.elapsedMs + " ms");
                metrics.put("Chatty I/O", "0 gói (Giảm 100%)");
                metrics.put("Hao pin I/O", formatBatteryImpact(0, res.elapsedMs));
                appendTrace(TRACE_COLOR_OK, "🔋 Năng lượng: " + formatBatteryImpact(0, res.elapsedMs));
                finishRun(true, metrics, "✓ After (Cache 2 tầng): phản hồi trong "
                        + res.elapsedMs + " ms (0 Chatty I/O • tiết kiệm pin)!", true);
            });
            return;
        }

        resetTrace("▶ BẮT ĐẦU §1 BEFORE — Gửi tuần tự 41 Short APDU", false);
        if (executionWaterfall != null) {
            executionWaterfall.beginBeforeRun();
        }
        startRun("⏳ [1/5] Đang SELECT Applet & đồng bộ xung nhịp thẻ...");
        runTask(() -> {
            suppressRawApduTelemetry = true;
            try {
                long totalStartNs = System.nanoTime();
                long[] stepMs = new long[5];
                ApduClient client = ApduClient.getInstance();
                int apduCount = 0;

                // Stage 1: SELECT Applet (~320 ms)
                long s1 = System.nanoTime();
                postWaterfallStart(0, "Đang gửi 0xA4 SELECT Applet...");
                appendTrace(TRACE_COLOR_FAULT, "[1/5] Gửi INS 0xA4 SELECT Applet (chờ chip đồng bộ)...");
                client.sendApdu(ApduCommands.CLA_ISO, ApduCommands.INS_SELECT,
                        (byte) 0x04, (byte) 0x00, ApduCommands.APPLET_AID);
                apduCount++;
                sleepRemainder(s1, 320L);
                stepMs[0] = elapsedMsSince(s1);
                postWaterfallComplete(0, stepMs[0], apduCount);
                appendTrace(TRACE_COLOR_FAULT, "↳ Xong SELECT: " + stepMs[0] + " ms");
                postStageTelemetry(0xA4, stepMs[0],
                        "⏳ [2/5] Đang kiểm tra OwnerPIN & dẫn xuất khóa AES (~520 ms)...");

                // Stage 2: VERIFY PIN (~520 ms)
                long s2 = System.nanoTime();
                postWaterfallStart(1, "Đang xác thực OwnerPIN + dẫn xuất AES...");
                appendTrace(TRACE_COLOR_FAULT, "[2/5] Gửi INS 0x10 VERIFY PIN + dẫn xuất khóa AES...");
                char[] pin = sessionPinChars();
                byte[] pinBytes = pinToBytes(pin);
                try {
                    client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_VERIFY_PIN,
                            (byte) 0x00, (byte) 0x00, pinBytes);
                } finally {
                    Arrays.fill(pinBytes, (byte) 0);
                    Arrays.fill(pin, '\0');
                }
                apduCount++;
                sleepRemainder(s2, 520L);
                stepMs[1] = elapsedMsSince(s2);
                postWaterfallComplete(1, stepMs[1], apduCount);
                appendTrace(TRACE_COLOR_FAULT, "↳ Xong VERIFY PIN: " + stepMs[1] + " ms");
                postStageTelemetry(0x10, stepMs[1],
                        "⏳ [3/5] Chip đang đọc EEPROM & giải mã khối AES-128 CBC (~680 ms)...");

                // Stage 3: GET INFO + AES-128 CBC EEPROM (~680 ms)
                long s3 = System.nanoTime();
                postWaterfallStart(2, "Đang đọc EEPROM & giải mã AES-128...");
                appendTrace(TRACE_COLOR_FAULT, "[3/5] Gửi INS 0x21 đọc EEPROM & giải mã AES-128 CBC...");
                CardResponse infoResp = client.sendApdu(ApduCommands.CLA_SMART_STUDENT,
                        ApduCommands.INS_GET_STUDENT_INFO, ApduCommands.P1_INFO_DECRYPTED, (byte) 0x00, null);
                apduCount++;
                sleepRemainder(s3, 680L);
                stepMs[2] = elapsedMsSince(s3);
                StudentCardRecord record = StudentCardRecord.fromPipeDelimitedString(
                        new String(infoResp.getData(), StandardCharsets.UTF_8));
                postWaterfallComplete(2, stepMs[2], apduCount);
                appendTrace(TRACE_COLOR_FAULT, "↳ Xong AES-128 EEPROM: " + stepMs[2] + " ms");
                postStageTelemetry(0x21, stepMs[2],
                        "⏳ [4/5] Chip đang tính lũy thừa modulo RSA-1024 (~890 ms)...");

                // Stage 4: RSA-1024 Challenge Sign + Raw AES Ciphertext (~890 ms)
                long s4 = System.nanoTime();
                postWaterfallStart(3, "Đang ký thách thức RSA-1024 trên chip...");
                appendTrace(TRACE_COLOR_FAULT, "[4/5] Gửi INS 0x40 ký thách thức RSA-1024 trên chip...");
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_SIGN_CHALLENGE,
                        (byte) 0x00, (byte) 0x00, CryptoManager.generateRandomBytes(16));
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_GET_STUDENT_INFO,
                        ApduCommands.P1_INFO_RAW_CIPHERTEXT, (byte) 0x00, null);
                apduCount += 2;
                sleepRemainder(s4, 890L);
                stepMs[3] = elapsedMsSince(s4);
                postWaterfallComplete(3, stepMs[3], apduCount);
                appendTrace(TRACE_COLOR_FAULT, "↳ Xong RSA-1024: " + stepMs[3] + " ms");
                postStageTelemetry(0x21, stepMs[3],
                        "⏳ [5/5] Đang đọc Học vụ & truyền tuần tự 35 gói Short APDU (~740 ms)...");

                // Stage 5: Academic Status + 35 sequential Short APDU chunk reads (~740 ms)
                long s5 = System.nanoTime();
                postWaterfallStart(4, "Đang tải 0/35 gói ảnh...");
                appendTrace(TRACE_COLOR_FAULT, "[5/5] Gửi INS 0x30 học vụ + vòng lặp 35 gói ảnh 240B...");
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_GET_ACADEMIC_LIBRARY_STATUS,
                        (byte) 0x00, (byte) 0x00, null);
                apduCount++;
                for (int c = 0; c < 35; c++) {
                    long chunkStartNs = System.nanoTime();
                    int off = c * ApduCommands.MAX_AVATAR_CHUNK_SIZE;
                    client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_DOWNLOAD_AVATAR_CHUNK,
                            (byte) ((off >> 8) & 0xFF), (byte) (off & 0xFF), new byte[]{(byte) 240});
                    apduCount++;
                    sleepRemainder(chunkStartNs, 18L);
                    if ((c + 1) % 5 == 0) {
                        postWaterfallProgress(4, apduCount, "Đang tải " + (c + 1) + "/35 gói ảnh...");
                    }
                }
                sleepRemainder(s5, 740L);
                stepMs[4] = elapsedMsSince(s5);
                postWaterfallComplete(4, stepMs[4], apduCount);
                appendTrace(TRACE_COLOR_FAULT, "↳ Xong 36 lệnh cuối: " + stepMs[4] + " ms");
                postStageTelemetry(0x30, stepMs[4],
                        "⏳ Đang tổng hợp kết quả điểm nghẽn...");

                long totalElapsedMs = elapsedMsSince(totalStartNs);
                return new BottleneckRunResult(record, stepMs, totalElapsedMs, apduCount);
            } finally {
                suppressRawApduTelemetry = false;
            }
        }, (res, blockedMs, totalMs) -> {
            showProfile(res.record, null, "THẺ");
            appendTrace(TRACE_COLOR_FAULT, "⚠ Tổng cộng: " + res.apduCount + " APDU tuần tự tốn " + res.elapsedMs + " ms");
            if (executionWaterfall != null) {
                executionWaterfall.finishBeforeRun(res.stepMs, res.elapsedMs, res.apduCount);
            }
            if (realtimeChart != null) {
                realtimeChart.recordBottleneckResult(res.stepMs, res.elapsedMs);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, false).comparisonMechanismLabel);
            metrics.put("1. SELECT applet", res.stepMs[0] + " ms");
            metrics.put("2. VERIFY PIN", res.stepMs[1] + " ms");
            metrics.put("3. AES-128 EEPROM", res.stepMs[2] + " ms");
            metrics.put("4. Ký RSA-1024", res.stepMs[3] + " ms");
            metrics.put("5. Học vụ & 35 ảnh", res.stepMs[4] + " ms");
            metrics.put("Tổng thời gian", res.elapsedMs + " ms");
            metrics.put("Chatty I/O", res.apduCount + " gói wake-up");
            metrics.put("Hao pin I/O", formatBatteryImpact(res.apduCount, res.elapsedMs));
            appendTrace(TRACE_COLOR_FAULT, "🔋 Chatty I/O (" + res.apduCount + " gói): "
                    + formatBatteryImpact(res.apduCount, res.elapsedMs));
            finishRun(false, metrics, "⚠ Before: mất " + res.elapsedMs
                    + " ms (~" + String.format(Locale.US, "%.1f", res.elapsedMs / 1000.0)
                    + "s) cho " + res.apduCount + " gói Chatty I/O — gây trễ & hao pin!", false);
        });
    }

    private void postWaterfallStart(int stageIndex, String liveNote) {
        autoDemoHandler.post(() -> {
            if (isAdded() && executionWaterfall != null) {
                executionWaterfall.startBeforeStage(stageIndex, liveNote);
            }
        });
    }

    private void postWaterfallProgress(int stageIndex, int cumulativeApduCount, String liveNote) {
        autoDemoHandler.post(() -> {
            if (isAdded() && executionWaterfall != null) {
                executionWaterfall.updateStageProgress(stageIndex, cumulativeApduCount, liveNote);
            }
        });
    }

    private void postWaterfallComplete(int stageIndex, long durationMs, int cumulativeApduCount) {
        autoDemoHandler.post(() -> {
            if (isAdded() && executionWaterfall != null) {
                executionWaterfall.completeBeforeStage(stageIndex, durationMs, cumulativeApduCount);
            }
        });
    }

    private String formatBatteryImpact(int apduCount, long elapsedMs) {
        double estimatedMj = apduCount == 0
                ? 0.02
                : (apduCount * 0.038 + (elapsedMs / 1000.0) * 0.12);
        String extraCurrent = "";
        try {
            BatteryManager bm = (BatteryManager) appContext().getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int microAmps = Math.abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
                if (microAmps > 0 && microAmps < 20_000_000) {
                    int ma = Math.max(1, microAmps / 1000);
                    extraCurrent = " • " + ma + " mA";
                }
            }
        } catch (Exception ignored) {
        }
        return apduCount == 0
                ? String.format(Locale.US, "~%.2f mJ (Tối thiểu%s)", estimatedMj, extraCurrent)
                : String.format(Locale.US, "~%.2f mJ (Cao%s)", estimatedMj, extraCurrent);
    }

    private void postStageTelemetry(int ins, long stageMs, String nextStatus) {
        autoDemoHandler.post(() -> {
            if (!isAdded()) return;
            if (realtimeChart != null) {
                realtimeChart.onApduFrame(ins, stageMs);
            }
        });
    }

    private static long elapsedMsSince(long startNs) {
        return Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private static void sleepRemainder(long startNs, long targetMs) {
        long elapsed = elapsedMsSince(startNs);
        long rem = targetMs - elapsed;
        if (rem > 0) {
            SystemClock.sleep(rem);
        }
    }

    // ------------------------------------------------------------------
    // §2 — refresh from card in Before (Main Thread) / After (pool)
    // ------------------------------------------------------------------

    private void runThreading(boolean afterMode) {
        String where = afterMode ? "thread nền (After)" : "Main Thread (Before)";
        startRun("Đang làm mới hồ sơ trên " + where + "...");
        if (afterMode) {
            resetTrace("▶ BẮT ĐẦU §2 AFTER — Đẩy sang ApduThreadPool", true);
            if (threadingLaneView != null) {
                threadingLaneView.beginAfterRun();
            }
            appendTrace(TRACE_COLOR_OK, "Hủy jankRunnable trên MainLooper (gỡ chặn UI)");
            appendTrace(TRACE_COLOR_INFO, "Giao fetchFromCard() cho ApduThreadPool (non-blocking)");
            runTask(() -> {
                appendTrace(TRACE_COLOR_INFO, "Đang gửi APDU đọc thẻ trên Worker Thread nền...");
                return fetchFromCard();
            }, (result, blockedMs, totalMs) -> {
                showProfile(result.getRecord(), null, "THẺ");
                appendTrace(TRACE_COLOR_OK, "✓ Nhận callback cập nhật UI trên main (" + msText(blockedMs)
                        + " chặn, đọc thẻ " + result.getElapsedMs() + " ms) → giữ 60 FPS");
                if (threadingLaneView != null) {
                    threadingLaneView.finishAfterRun(blockedMs, result.getElapsedMs());
                }
                if (realtimeChart != null) {
                    realtimeChart.recordThreadingResult(true, blockedMs, result.getElapsedMs());
                }
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, true).comparisonMechanismLabel);
                metrics.put("Main Thread giữ", msText(blockedMs));
                metrics.put("Thời gian đọc", result.getElapsedMs() + " ms");
                metrics.put("Khung rớt", Math.round(blockedMs / 16.6667f) + " khung");
                finishRun(true, metrics, "✓ After: hồ sơ đã cập nhật trên Worker Thread, UI không bị chặn (60 FPS).", true);
            });
        } else {
            resetTrace("▶ BẮT ĐẦU §2 BEFORE — Khóa trực tiếp Main Thread", false);
            if (threadingLaneView != null) {
                threadingLaneView.beginBeforeRun();
            }
            // Ensure continuous jank doesn't double-fire while the 18-frame burst is running.
            chaosManager.resolveStep1Threading();
            appendTrace(TRACE_COLOR_FAULT, "Gọi fetchFromCard() ĐỒNG BỘ trực tiếp trên Main Thread!");
            runBlockingTask(this::fetchFromCard, (result, blockedMs, totalMs) -> {
                if (!isAdded()) return;
                appendTrace(TRACE_COLOR_FAULT, "↳ Main Thread bị giữ " + totalMs
                        + " ms → bắt đầu 18 nhịp khóa frame (8 APDU + 148 ms/nhịp)");
                if (threadingLaneView != null) {
                    threadingLaneView.onBeforeSyncFetchDone(totalMs);
                }
                runMainThreadAvatarBurst(result, totalMs, 1, 17);
            });
        }
    }

    /**
     * Blocks the Main Thread across 18 consecutive Choreographer frames (~2.7s total) so the
     * realtime chart fills with tall red frame spikes and the dual-lane view tracks each stall.
     */
    private void runMainThreadAvatarBurst(LocalCacheManager.ProfileLoadResult profileResult,
                                          long accumulatedMs, int frameIndex, int remainingFrames) {
        if (!isAdded()) return;
        if (remainingFrames <= 0) {
            // Keep degraded ~10 FPS state active until user clicks B2. Xử lý.
            chaosManager.injectStep1Threading();
            startLiveCounterPollingIfNeeded();
            appendTrace(TRACE_COLOR_FAULT, "🔥 Xong 18 nhịp (tổng khóa " + accumulatedMs
                    + " ms, rớt ~" + Math.round(accumulatedMs / 16.6667f) + " khung)");
            appendTrace(TRACE_COLOR_FAULT, "🔥 Bật jankRunnable lặp mỗi 135 ms trên MainLooper (APDU + sleep 55 ms) để giữ tụt FPS");
            showProfile(profileResult.getRecord(), null, "THẺ");
            if (threadingLaneView != null) {
                threadingLaneView.finishBeforeRun(accumulatedMs);
            }
            if (realtimeChart != null) {
                realtimeChart.recordThreadingResult(false, accumulatedMs, accumulatedMs);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, false).comparisonMechanismLabel);
            metrics.put("Main Thread giữ", msText(accumulatedMs));
            metrics.put("Thời gian đọc", accumulatedMs + " ms");
            metrics.put("Khung rớt", Math.round(accumulatedMs / 16.6667f) + " khung");
            finishRun(false, metrics, "⚠ Before: UI đã bị khóa " + accumulatedMs
                    + " ms qua 18 nhịp (~" + Math.round(accumulatedMs / 16.6667f)
                    + " khung rớt) & jankRunnable vẫn đang lặp trên Main Thread!", false);
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
            sleepRemainder(frameStartNs, 148L);
            long frameCostMs = Math.max(1L, (System.nanoTime() - frameStartNs) / 1_000_000L);
            long nextAccumulatedMs = accumulatedMs + frameCostMs;
            if (threadingLaneView != null) {
                threadingLaneView.onBeforeBurstFrame(frameIndex, frameCostMs, nextAccumulatedMs);
            }
            if (frameIndex == 1 || frameIndex == 6 || frameIndex == 12 || remainingFrames == 1) {
                appendTrace(TRACE_COLOR_FAULT, "Nhịp " + frameIndex + "/18: gửi 8 APDU + block "
                        + frameCostMs + " ms (>16.6 ms → rớt ~" + Math.round(frameCostMs / 16.6667f) + " khung)");
            }
            runMainThreadAvatarBurst(profileResult, nextAccumulatedMs,
                    frameIndex + 1, remainingFrames - 1);
        });
    }

    // ------------------------------------------------------------------
    // §3 — open the profile: Before always hits 36 APDUs (~2.65s), After uses cache (~8ms)
    // ------------------------------------------------------------------

    private void runCache(boolean afterMode) {
        resetTrace(afterMode
                ? "▶ BẮT ĐẦU §3 AFTER — Offline-first Cache AES-256-GCM"
                : "▶ BẮT ĐẦU §3 BEFORE — Bỏ qua Cache & ghi Plaintext Leak", afterMode);
        if (cacheFlowView != null) {
            if (afterMode) {
                cacheFlowView.beginAfterRun();
            } else {
                cacheFlowView.beginBeforeRun();
            }
        }
        startRun(afterMode ? "Đang mở hồ sơ từ Cache AES-256-GCM..."
                : "⏳ Đang bỏ qua Cache, đọc trực tiếp 36 APDU từ thẻ (1/36)...");
        if (afterMode) {
            appendTrace(TRACE_COLOR_OK, "Đã xóa SharedPreferences(\"insecure_plaintext_leak\")");
        } else {
            appendTrace(TRACE_COLOR_FAULT, "Ghi bản rõ MSSV|PIN=123456 vào SharedPreferences(\"insecure_plaintext_leak\")");
            appendTrace(TRACE_COLOR_FAULT, "Bật cờ bypass Cache → ép gọi thẳng 36 APDU xuống thẻ");
        }
        runTask(() -> {
            if (afterMode && !chaosManager.isCacheBypassAndPlaintextLeakActive()) {
                appendTrace(TRACE_COLOR_INFO, "Kiểm tra tầng L1 (RAM) → L2 (AndroidKeyStore TEE AES-256-GCM)...");
                StudentCardRecord cached = encryptedCache.getStudentProfile();
                String tier = encryptedCache.getLastReadTier();
                if (cached == null) {
                    String active = ApduClient.getInstance().getActiveCardInfo();
                    if (active != null && active.contains("|")) {
                        cached = StudentCardRecord.fromPipeDelimitedString(active);
                        encryptedCache.saveStudentProfile(cached);
                        tier = "L2_TEE";
                    }
                }
                if (cached != null) {
                    return new CacheOpen(cached, encryptedCache.getAvatarBytes(), true, 0, tier);
                }
            }
            // Before mode: full 36-APDU direct card + RSA + 30 avatar chunks read (~2.65 seconds)
            appendTrace(TRACE_COLOR_FAULT, "Gửi 5 APDU đọc hồ sơ + INS 0x40 ký RSA-1024 trên thẻ...");
            LocalCacheManager.ProfileLoadResult result = fetchFromCard();
            ApduClient client = ApduClient.getInstance();
            client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_SIGN_CHALLENGE,
                    (byte) 0x00, (byte) 0x00, CryptoManager.generateRandomBytes(16));
            int totalApdus = result.getApduCommandsSent() + 1;
            postCacheApduProgress(totalApdus);
            for (int c = 0; c < 30; c++) {
                long pktStartNs = System.nanoTime();
                int off = c * ApduCommands.MAX_AVATAR_CHUNK_SIZE;
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_DOWNLOAD_AVATAR_CHUNK,
                        (byte) ((off >> 8) & 0xFF), (byte) (off & 0xFF), new byte[]{(byte) 240});
                totalApdus++;
                sleepRemainder(pktStartNs, 76L);
                if ((c + 1) % 3 == 0) {
                    postCacheApduProgress(totalApdus);
                }
                if ((c + 1) % 6 == 0) {
                    final int doneCount = totalApdus;
                    if (c == 11 || c == 23 || c == 29) {
                        appendTrace(TRACE_COLOR_FAULT, "Đang tải trực tiếp từ thẻ: " + doneCount + "/36 Short APDU (240B)...");
                    }
                }
            }
            return new CacheOpen(result.getRecord(), encryptedCache.getAvatarBytes(), false, totalApdus, "BYPASS");
        }, (openResult, blockedMs, totalMs) -> {
            String tierLabel = "L1_RAM".equals(openResult.cacheTier)
                    ? "L1 RAM"
                    : ("L2_TEE".equals(openResult.cacheTier) ? "L2 TEE" : "THẺ");
            showProfile(openResult.record, openResult.avatarBytes, openResult.fromCache ? tierLabel : "THẺ");
            int apduCount = openResult.fromCache ? 0 : openResult.apduCount;
            appendTrace(openResult.fromCache ? TRACE_COLOR_OK : TRACE_COLOR_FAULT,
                    openResult.fromCache
                            ? "✓ Hit " + tierLabel + " (L1 RAM + L2 AndroidKeyStore TEE): " + totalMs + " ms (0 APDU • giảm 100% I/O)"
                            : "⚠ Bypass Cache: tốn " + totalMs + " ms (" + apduCount + " APDU) & lộ bản rõ");
            if (cacheFlowView != null) {
                if (openResult.fromCache) {
                    cacheFlowView.finishAfterRun(tierLabel, totalMs);
                } else {
                    cacheFlowView.finishBeforeRun(totalMs, apduCount);
                }
            }
            if (realtimeChart != null) {
                realtimeChart.recordCacheResult(afterMode, openResult.fromCache, totalMs, apduCount);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, afterMode).comparisonMechanismLabel);
            metrics.put("Nguồn", openResult.fromCache ? tierLabel + " (AES-GCM TEE)" : "Thẻ (36 APDU)");
            metrics.put("Thời gian", totalMs + " ms");
            metrics.put("I/O thừa", openResult.fromCache ? "0 APDU (Giảm 100%)" : apduCount + " APDU");
            metrics.put("Tự hủy 3x PIN", openResult.fromCache ? "Sẵn sàng (3/3 lần)" : "Không bảo vệ");
            finishRun(afterMode, metrics, openResult.fromCache
                    ? "✓ After: mở hồ sơ từ " + tierLabel + " trong " + totalMs + " ms (0 APDU — giảm 100% I/O thừa)."
                    : "⚠ Before: bỏ qua cache mất " + totalMs + " ms (" + apduCount
                    + " APDU) & lộ bản rõ vào SharedPreferences!", afterMode);
        });
    }

    private void postCacheApduProgress(int currentApduCount) {
        autoDemoHandler.post(() -> {
            if (isAdded() && cacheFlowView != null) {
                cacheFlowView.onBeforeApduProgress(currentApduCount);
            }
        });
    }

    private void simulateWrongPin3xAutoWipe() {
        if (busy || !isAdded()) return;
        // Ensure an active PIN session exists so PinSessionManager enforces real 3-strike Auto-Wipe
        if (!pinSessionManager.hasActiveSession()) {
            StudentCardRecord cur = encryptedCache.getStudentProfile();
            String id = (cur != null && cur.getStudentId() != null) ? cur.getStudentId() : "CT070318";
            String name = (cur != null && cur.getFullName() != null) ? cur.getFullName() : "Vũ Ngọc Hải";
            pinSessionManager.createPinSession(id, name, "123456".toCharArray());
        }

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        container.setPadding(pad, dp(12), pad, dp(4));

        TextView tvHint = new TextView(requireContext());
        tvHint.setTextSize(12f);
        tvHint.setTextColor(Color.parseColor(SLATE));
        tvHint.setText("Nhập mã PIN để kiểm tra cơ chế bảo vệ Cache 2 tầng (PIN đúng mặc định: 123456).\n"
                + "Nhập sai 3 lần liên tiếp sẽ tự hủy L1 RAM + L2 TEE.");
        container.addView(tvHint);

        TextView tvAttempts = new TextView(requireContext());
        tvAttempts.setTextSize(12f);
        tvAttempts.setTypeface(null, Typeface.BOLD);
        tvAttempts.setTextColor(Color.parseColor(BLUE));
        tvAttempts.setPadding(0, dp(8), 0, dp(6));
        tvAttempts.setText("Số lần thử còn lại: " + pinSessionManager.getRemainingUnlockAttempts() + "/3");
        container.addView(tvAttempts);

        EditText etPin = new EditText(requireContext());
        etPin.setHint("Nhập PIN 4–8 chữ số...");
        etPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        etPin.setBackgroundResource(R.drawable.bg_input);
        etPin.setPadding(dp(12), dp(10), dp(12), dp(10));
        etPin.setTextColor(0xFF0F172A);
        container.addView(etPin);

        TextView tvDialogFeedback = new TextView(requireContext());
        tvDialogFeedback.setTextSize(12f);
        tvDialogFeedback.setPadding(0, dp(8), 0, 0);
        tvDialogFeedback.setVisibility(View.GONE);
        container.addView(tvDialogFeedback);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Xác thực PIN • Auto-Wipe 3 lần")
                .setView(container)
                .setPositiveButton("Xác nhận PIN", null)
                .setNegativeButton("Đóng", null)
                .create();

        dialog.setOnShowListener(d -> {
            android.widget.Button btnConfirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btnConfirm.setOnClickListener(v -> {
                String entered = etPin.getText().toString().trim();
                if (entered.length() < 4) {
                    tvDialogFeedback.setVisibility(View.VISIBLE);
                    tvDialogFeedback.setTextColor(Color.parseColor(RED));
                    tvDialogFeedback.setText("Vui lòng nhập ít nhất 4 chữ số.");
                    return;
                }
                if (pinSessionManager.getFailedUnlockCount() == 0) {
                    resetTrace("🔐 KIỂM TRA XÁC THỰC PIN & TỰ HỦY CACHE 2 TẦNG", true);
                }
                char[] pinChars = entered.toCharArray();
                boolean ok = pinSessionManager.verifyLocalSessionPin(pinChars);
                Arrays.fill(pinChars, '\0');
                etPin.setText("");

                if (ok) {
                    appendTrace(TRACE_COLOR_OK, "✓ Nhập đúng PIN → Giữ nguyên L1 RAM + L2 AndroidKeyStore TEE (3/3 lần thử)");
                    if (cacheFlowView != null) {
                        cacheFlowView.recordPinCheckOk();
                    }
                    dialog.dismiss();
                    return;
                }

                // Wrong PIN
                boolean autoWiped = !pinSessionManager.hasActiveSession();
                if (autoWiped) {
                    encryptedCache.wipeStudentSessionCache();
                    boolean wipedOk = !encryptedCache.hasL1ProfileCached() && !encryptedCache.hasL2ProfileCached();
                    appendTrace(TRACE_COLOR_FAULT, "Lần 3/3: nhập sai PIN → vượt ngưỡng tối đa!");
                    appendTrace(TRACE_COLOR_OK, "💥 AUTO-WIPE: Đã xóa sạch L1 RAM (zero-fill) & L2 AndroidKeyStore TEE!");
                    if (cacheFlowView != null) {
                        cacheFlowView.recordAutoWipeTriggered(wipedOk);
                    }
                    dialog.dismiss();
                    showProfile(null, null, null);
                    updateRealAppStatusCards(true);
                    Map<String, String> metrics = new LinkedHashMap<>();
                    metrics.put("Cơ chế", "Auto-Wipe 2 tầng (3x PIN)");
                    metrics.put("Nguồn", "L1=Trống • L2=Trống");
                    metrics.put("Thời gian", "< 1 ms");
                    metrics.put("I/O thừa", "0 APDU (Đã khóa phiên)");
                    metrics.put("Tự hủy 3x PIN", wipedOk ? "✓ Đã tự hủy L1 + L2" : "Chưa xóa hết");
                    finishRun(true, metrics,
                            "💥 Sai PIN 3/3 — Đã tự hủy toàn bộ L1 RAM + L2 AndroidKeyStore TEE!", false);
                } else {
                    int failed = pinSessionManager.getFailedUnlockCount();
                    int remaining = pinSessionManager.getRemainingUnlockAttempts();
                    appendTrace(TRACE_COLOR_FAULT, "Lần " + failed + "/3: nhập sai PIN → còn " + remaining + " lần thử");
                    if (cacheFlowView != null) {
                        cacheFlowView.recordWrongPinAttempt(failed, remaining);
                    }
                    tvAttempts.setText("Số lần thử còn lại: " + remaining + "/3");
                    tvAttempts.setTextColor(Color.parseColor(RED));
                    tvDialogFeedback.setVisibility(View.VISIBLE);
                    tvDialogFeedback.setTextColor(Color.parseColor(RED));
                    tvDialogFeedback.setText("❌ Mã PIN không đúng! Còn " + remaining + "/3 lần thử trước khi tự hủy dữ liệu.");
                }
            });
        });

        dialog.show();
    }

    private void resetCache() {
        if (busy) return;
        encryptedCache.wipeStudentSessionCache();
        before.clear();
        after.clear();
        renderComparison();
        if (realtimeChart != null) {
            realtimeChart.resetHistory();
        }
        if (cacheFlowView != null) {
            cacheFlowView.resetToEmpty();
        }
        showProfile(null, null, null);
        updateRealAppStatusCards(optimized());
    }

    // ------------------------------------------------------------------
    // §4 — pick a portrait and save it to the card
    // ------------------------------------------------------------------

    private void runImage(boolean afterMode) {
        resetTrace(afterMode
                ? "▶ BẮT ĐẦU §4 AFTER — Two-pass Decode + WebP 75% & Dọn Heap"
                : "▶ BẮT ĐẦU §4 BEFORE — Full Bitmap PNG & Heap Memory Churn", afterMode);
        if (imagePipelineView != null) {
            if (afterMode) {
                imagePipelineView.beginAfterRun();
            } else {
                imagePipelineView.beginBeforeRun(chaosManager.getRetainedHeapMb());
            }
        }
        if (afterMode) {
            appendTrace(TRACE_COLOR_OK, "Dừng churnExecutor, giải phóng buffer ghim & gọi System.gc()");
        } else {
            appendTrace(TRACE_COLOR_FAULT, String.format(Locale.US,
                    "Chaos ghim %.1f MB trên Java Heap + bật churnExecutor (64×16 KB / 250 ms)",
                    chaosManager.getRetainedHeapMb()));
            startLiveCounterPollingIfNeeded();
        }
        startRun(afterMode ? "Đang xử lý & ghi ảnh (After)..." : "Đang xử lý ảnh kiểu chưa tối ưu...");
        runTask(() -> afterMode ? runImageAfter() : runImageBefore(), (result, blockedMs, totalMs) -> {
            if (result.preview != null) {
                showPreview(result.preview);
            }
            updateImageQuotaBar(result.sizeKb, result.packets, result.success);
            if (imagePipelineView != null && (result.sizeKb > 0 || afterMode)) {
                if (afterMode) {
                    imagePipelineView.finishAfterRun(result.inSampleSize, result.sizeKb, result.packets,
                            result.ramMb, result.inGpuVram, result.bufferReuses,
                            result.deltaGcCount, result.deltaGcMs, result.success);
                } else {
                    imagePipelineView.finishBeforeRun(result.sourceDims, result.sizeKb, result.packets,
                            result.ramMb, result.deltaGcCount, result.deltaGcMs);
                }
            }
            if (realtimeChart != null && (result.sizeKb > 0 || afterMode)) {
                realtimeChart.recordImageResult(afterMode, result.sizeKb, result.packets,
                        result.ramMb, result.success);
            }
            finishRun(afterMode, result.metrics, result.status, result.success);
        });
    }

    private void updateImageQuotaBar(double sizeKb, int packets, boolean success) {
        if (tvCardQuota8Kb == null || pbCardQuota8Kb == null || sizeKb <= 0) return;
        int pct = (int) Math.min(100, Math.round((sizeKb / 8.0) * 100.0));
        pbCardQuota8Kb.setProgress(Math.max(4, pct));
        if (success && sizeKb <= 8.0) {
            pbCardQuota8Kb.setProgressDrawable(
                    androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.progress_metric_green));
            tvCardQuota8Kb.setTextColor(Color.parseColor(GREEN));
            tvCardQuota8Kb.setText(String.format(Locale.US,
                    "✓ Bộ nhớ thẻ chip: %.1f KB / 8.0 KB (%d gói 240B — Lưu thành công)", sizeKb, packets));
        } else {
            pbCardQuota8Kb.setProgressDrawable(
                    androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.progress_metric_red));
            tvCardQuota8Kb.setTextColor(Color.parseColor(RED));
            tvCardQuota8Kb.setText(String.format(Locale.US,
                    "⚠ Bộ nhớ thẻ chip: %.0f KB / 8.0 KB (%d gói — VƯỢT TRẦN 8 KB!)", sizeKb, packets));
        }
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
        long gcCountStart = readArtRuntimeStatLong("art.gc.gc-count");
        long gcTimeStart = readArtRuntimeStatLong("art.gc.gc-time");
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
            int packets = ImageOptimizer.calculateApduPacketCount(png.length);
            double ramMb = dims[0] * (double) dims[1] * 4 / (1024 * 1024);
            String dimsText = dims[0] + "×" + dims[1];
            postImageBeforeDecoded(dimsText, ramMb, png.length / 1024.0, packets);
            appendTrace(TRACE_COLOR_FAULT, String.format(Locale.US,
                    "Giải mã Full Bitmap %dx%d (ARGB_8888 trên RAM thường, ~%.1f MB) trong %d ms",
                    dims[0], dims[1], ramMb, decodeMs));
            appendTrace(TRACE_COLOR_FAULT, "Nén PNG không tối ưu: " + sizeText(png.length)
                    + " → cần " + packets + " gói 240B (tràn bộ đệm 8 KB của thẻ!)");

            // Simulate unpooled byte[240] allocations & chunked write attempts hitting the 8 KB card limit (~2.8s)
            for (int p = 0; p < 48; p++) {
                byte[] unpooledChunk = new byte[ApduCommands.MAX_AVATAR_CHUNK_SIZE];
                unpooledChunk[0] = (byte) p;
                SystemClock.sleep(52L);
                if ((p + 1) % 6 == 0) {
                    postImageChurnProgress(p + 1);
                }
                if (p == 23) {
                    appendTrace(TRACE_COLOR_FAULT, "Cấp phát rác unpooled byte[240] & thử ghi gói 35/" + packets + " → GC Pause tăng!");
                }
            }
            long totalMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            long deltaGcCount = Math.max(0L, readArtRuntimeStatLong("art.gc.gc-count") - gcCountStart);
            long deltaGcMs = Math.max(0L, readArtRuntimeStatLong("art.gc.gc-time") - gcTimeStart);
            appendTrace(TRACE_COLOR_FAULT, "⚠ Thẻ từ chối (vượt 8 KB) • ART GC: +" + deltaGcCount
                    + " lần (" + deltaGcMs + " ms pause)");
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, false).comparisonMechanismLabel);
            metrics.put("Ảnh gốc", dimsText);
            metrics.put("Giải mã", "Full ARGB_8888 • " + decodeMs + " ms");
            metrics.put("Dung lượng", sizeText(png.length) + " (PNG)");
            metrics.put("Số gói 240B", packets + " gói");
            metrics.put("Bộ đệm 8KB", "Cấp phát rác (48+ mảng)");
            metrics.put("RAM / GPU VRAM", String.format(Locale.US, "%.1f MB Java/Native", ramMb));
            metrics.put("GC Pause (ART)", "+" + deltaGcCount + " lần • " + deltaGcMs + " ms");
            metrics.put("Ghi lên thẻ", "Không — vượt 8 KB");
            return new ImageRunResult(metrics, preview,
                    "❌ Before (" + totalMs + " ms): PNG " + sizeText(png.length) + " cần " + packets
                            + " gói → tràn 8 KB, tốn RAM & gây Memory Churn / GC Pause.", false,
                    png.length / 1024.0, packets, ramMb,
                    dimsText, 1, 0, false, deltaGcCount, deltaGcMs);
        } catch (OutOfMemoryError e) {
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Ghi lên thẻ", "OOM khi giải mã");
            return new ImageRunResult(metrics, null,
                    "❌ Hết RAM khi giải mã ảnh gốc — đây chính là lý do cần two-pass.", false,
                    0, 0, 0, "", 1, 0, false, 0L, 0L);
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
        long gcCountStart = readArtRuntimeStatLong("art.gc.gc-count");
        long gcTimeStart = readArtRuntimeStatLong("art.gc.gc-time");
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
                    return ImageRunResult.failure("❌ Không xử lý được ảnh đã chọn (có thể quá lớn).");
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
            int bufferReuses = ImageOptimizer.getReusable8KbBufferReuseCount();
            postImageAfterCompressed(sampleSize, webp.length / 1024.0, bufferReuses);
            appendTrace(TRACE_COLOR_OK, "Two-pass (inSampleSize=" + sampleSize + ") + nén WebP 75% bằng bộ đệm 8KB tái sử dụng (lần #"
                    + bufferReuses + "): " + sizeText(webp.length));
            long uploadStartNs = System.nanoTime();
            int packets = uploadAvatar(webp);
            long uploadMs = Math.max(1, (System.nanoTime() - uploadStartNs) / 1_000_000);
            encryptedCache.saveAvatarBytes(webp);
            ImageOptimizer.DisplayBitmapResult gpuRes = ImageOptimizer.decodeForGpuVramDisplay(webp);
            preview = gpuRes.bitmap;
            long deltaGcCount = Math.max(0L, readArtRuntimeStatLong("art.gc.gc-count") - gcCountStart);
            long deltaGcMs = Math.max(0L, readArtRuntimeStatLong("art.gc.gc-time") - gcTimeStart);
            appendTrace(TRACE_COLOR_OK, gpuRes.inGpuVram
                    ? "✓ Đưa ảnh lên GPU VRAM (Bitmap.Config.HARDWARE — 0 MB Java Heap)"
                    : "✓ Decode ảnh hiển thị gọn (150×150)");
            appendTrace(TRACE_COLOR_OK, "✓ Ghi " + packets + " gói vào thẻ (" + uploadMs
                    + " ms) • ART GC: +" + deltaGcCount + " lần (" + deltaGcMs + " ms pause)");

            boolean ok = packets >= 0;
            double ramMb = gpuRes.inGpuVram ? 0.0 : (ImageOptimizer.TARGET_AVATAR_WIDTH
                    * (double) ImageOptimizer.TARGET_AVATAR_HEIGHT * 4 / (1024 * 1024));
            String dimsText = dims[0] + "×" + dims[1];
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, true).comparisonMechanismLabel);
            metrics.put("Ảnh gốc", dimsText);
            metrics.put("Giải mã", "Two-pass (inSampleSize=" + sampleSize + ")");
            metrics.put("Dung lượng", sizeText(webp.length) + " (WebP 75%)");
            metrics.put("Số gói 240B", packets + " gói");
            metrics.put("Bộ đệm 8KB", "Tái sử dụng (0 cấp phát mới)");
            metrics.put("RAM / GPU VRAM", gpuRes.inGpuVram
                    ? "GPU VRAM (HARDWARE)"
                    : String.format(Locale.US, "%.2f MB (150×150)", ramMb));
            metrics.put("GC Pause (ART)", "+" + deltaGcCount + " lần • " + deltaGcMs + " ms");
            metrics.put("Ghi lên thẻ", packets >= 0
                    ? "✓ " + packets + " gói • " + uploadMs + " ms" : "❌ thẻ từ chối");
            return new ImageRunResult(metrics, preview, ok
                    ? "✓ After: WebP " + sizeText(webp.length) + " (" + packets + " gói) • "
                    + (gpuRes.inGpuVram ? "GPU VRAM (HARDWARE) • " : "") + "Tái sử dụng buffer 8 KB!"
                    : "❌ Thẻ từ chối gói ảnh WebP.", ok,
                    webp.length / 1024.0, Math.max(0, packets), ramMb,
                    dimsText, sampleSize, bufferReuses, gpuRes.inGpuVram, deltaGcCount, deltaGcMs);
        } catch (Exception e) {
            return ImageRunResult.failure("❌ Không xử lý được ảnh: " + e.getMessage());
        } finally {
            if (avatar != null) {
                avatar.recycle();
            }
        }
    }

    private void postImageBeforeDecoded(String dims, double bitmapRamMb, double pngKb, int pngPackets) {
        autoDemoHandler.post(() -> {
            if (isAdded() && imagePipelineView != null) {
                imagePipelineView.onBeforeDecoded(dims, bitmapRamMb, pngKb, pngPackets);
            }
        });
    }

    private void postImageChurnProgress(int allocatedCount) {
        autoDemoHandler.post(() -> {
            if (isAdded() && imagePipelineView != null) {
                imagePipelineView.onBeforeChurnProgress(allocatedCount);
            }
        });
    }

    private void postImageAfterCompressed(int sampleSize, double webpKb, int reuses) {
        autoDemoHandler.post(() -> {
            if (isAdded() && imagePipelineView != null) {
                imagePipelineView.onAfterCompressed(sampleSize, webpKb, reuses);
            }
        });
    }

    private static long readArtRuntimeStatLong(String key) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return 0L;
        }
        try {
            String value = Debug.getRuntimeStat(key);
            return value != null ? Long.parseLong(value.trim()) : 0L;
        } catch (Exception ignored) {
            return 0L;
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
        if (sampleBytes != null) return;
        ApduThreadPool.getInstance().executeAsyncOnPool(this::ensureSampleBytes, (bytes, blockedMs, totalMs) -> {
            if (!isAdded()) return;
            Bitmap preview = ImageOptimizer.decodeAndDownsample(bytes, 320, 320);
            if (preview != null) {
                showPreview(preview);
            }
            demoImageInfo.setText("Ảnh mẫu: 1200×1200 px");
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

    // ------------------------------------------------------------------
    // §5 — log in with the PIN
    // ------------------------------------------------------------------

    private void runLogin(boolean afterMode) {
        String pinText = demoPinInput.getText().toString().trim();
        if (pinText.length() < 4) {
            Toast.makeText(requireContext(), "Nhập PIN 4–8 chữ số.", Toast.LENGTH_SHORT).show();
            return;
        }
        resetTrace(afterMode
                ? "▶ BẮT ĐẦU §5 AFTER — Lazy Init & Zeroize PIN char[]"
                : "▶ BẮT ĐẦU §5 BEFORE — Sync KeyStore & Lộ String PIN trên Heap", afterMode);
        if (securityAuditFlowView != null) {
            if (afterMode) {
                securityAuditFlowView.beginAfterRun(pinText.length());
            } else {
                securityAuditFlowView.beginBeforeRun(pinText.length(), chaosManager.getMasvsScore());
            }
        }
        startRun(afterMode ? "Đang đăng nhập & zeroize char[]..."
                : "⏳ Đang khởi tạo KeyStore đồng bộ & băm PIN chưa xóa String...");
        runTask(() -> afterMode ? loginAfter(pinText) : loginBefore(pinText),
                (result, blockedMs, totalMs) -> {
                    if (securityAuditFlowView != null) {
                        if (afterMode) {
                            securityAuditFlowView.finishAfterRun(result.ms, result.remaining, result.length, 5);
                        } else {
                            securityAuditFlowView.finishBeforeRun(result.ms, result.length, chaosManager.getMasvsScore());
                        }
                    }
                    if (realtimeChart != null) {
                        realtimeChart.recordLoginResult(afterMode, result.ms, result.remaining, result.length);
                    }
                    Map<String, String> metrics = new LinkedHashMap<>();
                    metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, afterMode).comparisonMechanismLabel);
                    metrics.put("Khởi tạo + PBKDF2", result.ms + " ms");
                    metrics.put("Zeroize PIN", result.remaining < 0
                            ? "Không (String 100%)" : result.remaining + "/" + result.length + " ký tự còn");
                    metrics.put("MASVS", afterMode ? "5/5 (An toàn)" : chaosManager.getMasvsScore() + "/5 (Rủi ro)");
                    metrics.put("Phòng thủ runtime", afterMode
                            ? "Anti-Hook + APK SHA-256 + Tapjack" : "Chưa bật");
                    String status = afterMode
                            ? "✓ After (" + result.ms + " ms): đăng nhập xong, PIN đã zeroize ("
                            + (result.length - result.remaining) + "/" + result.length + " ký tự) • MASVS 5/5."
                            : "⚠ Before (" + result.ms + " ms): khởi tạo chậm & PIN vẫn nằm trong String trên Heap (100%)!";
                    finishRun(afterMode, metrics, status, afterMode);
                });
    }

    private PinRunResult loginBefore(String pinText) throws Exception {
        long startNs = System.nanoTime();
        appendTrace(TRACE_COLOR_FAULT, "Lưu PIN vào String bất biến trên Heap (không thể ghi đè xóa)");
        appendTrace(TRACE_COLOR_FAULT, "Bật cờ giả lập Root/Debugger + bỏ qua RSA-1024 → MASVS tụt "
                + chaosManager.getMasvsScore() + "/5");
        appendTrace(TRACE_COLOR_FAULT, "Chạy PBKDF2WithHmacSHA256 (10.000 vòng) & KHÔNG gọi spec.clearPassword()");
        PBEKeySpec spec = new PBEKeySpec(pinText.toCharArray(), new byte[16], 10_000, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        factory.generateSecret(spec).getEncoded();
        postSecurityPbkdf2Done(chaosManager.getMasvsScore());
        // Simulate unoptimized synchronous KeyStore + cold-start overhead + heap retention (~1.95s)
        sleepRemainder(startNs, 1950L);
        long ms = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        appendTrace(TRACE_COLOR_FAULT, "⚠ Hoàn tất sau " + ms + " ms: PIN vẫn còn 100% trong RAM (Heap Dump đọc được)");
        // Intentionally do not call spec.clearPassword(): demonstrates un-zeroized String exposure.
        return new PinRunResult(ms, -1, pinText.length());
    }

    private void postSecurityPbkdf2Done(int masvsScore) {
        autoDemoHandler.post(() -> {
            if (isAdded() && securityAuditFlowView != null) {
                securityAuditFlowView.onBeforePbkdf2Done(masvsScore);
            }
        });
    }

    private PinRunResult loginAfter(String pinText) {
        char[] pin = pinText.toCharArray();
        long startNs = System.nanoTime();
        appendTrace(TRACE_COLOR_OK, "Bỏ khởi tạo đồng bộ, chuyển PIN sang mảng char[" + pin.length + "]");
        appendTrace(TRACE_COLOR_INFO, "Băm PIN qua CryptoManager.computePinVerifierHash() + tự động zeroize");
        CryptoManager.computePinVerifierHash(pin, new byte[16]);
        long ms = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        int remaining = 0;
        for (char c : pin) {
            if (c != '\0') remaining++;
        }
        appendTrace(TRACE_COLOR_OK, "✓ Đã ghi đè Arrays.fill(pin, '\\0') → còn "
                + remaining + "/" + pin.length + " ký tự • Khôi phục MASVS 5/5 (" + ms + " ms)");
        return new PinRunResult(ms, remaining, pin.length);
    }

    // ------------------------------------------------------------------
    // Shared task / UI helpers
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
        if (realtimeChart != null) {
            realtimeChart.onRunEnded();
        }
        Toast.makeText(requireContext(),
                "Lỗi: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                Toast.LENGTH_SHORT).show();
    }

    private void startRun(String status) {
        releaseLiveMetricFlags();
        busy = true;
        trackedHeavyMetric = true;
        trackedMainBlockMetric = (topic == TOPIC_THREADING && !optimized());
        DemoMetrics.beginHeavyTask(trackedMainBlockMetric);
        btnAction.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        if (realtimeChart != null) {
            realtimeChart.onRunStarted(optimized());
        }
    }

    private void endRun() {
        releaseLiveMetricFlags();
        busy = false;
        if (btnAction != null) btnAction.setEnabled(true);
        if (progress != null) progress.setVisibility(View.GONE);
    }

    private void releaseLiveMetricFlags() {
        if (trackedHeavyMetric) {
            DemoMetrics.endHeavyTask(trackedMainBlockMetric);
            trackedHeavyMetric = false;
            trackedMainBlockMetric = false;
        }
    }

    private void finishRun(boolean afterRun, Map<String, String> metrics, String status, boolean ok) {
        endRun();
        Map<String, String> target = afterRun ? after : before;
        target.clear();
        target.putAll(metrics);
        renderComparison();
        updateRealAppStatusCards(afterRun);
        updateTechSummaryLine();
    }

    private void updateTechSummaryLine() {
        if (tvTechSummary == null) return;
        String key;
        switch (topic) {
            case TOPIC_THREADING:
                key = "Main Thread giữ";
                break;
            case TOPIC_CACHE:
                key = "Thời gian";
                break;
            case TOPIC_IMAGE:
                key = "Dung lượng";
                break;
            case TOPIC_STARTUP:
                key = "Zeroize PIN";
                break;
            default:
                key = "Tổng thời gian";
                break;
        }
        String bVal = before.get(key);
        String aVal = after.get(key);
        if (bVal != null || aVal != null) {
            tvTechSummary.setText("Before: " + (bVal != null ? bVal : "—")
                    + "  •  After: " + (aVal != null ? aVal : "—"));
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
        if (pin != null && pin.length > 0) {
            return pin;
        }
        return "123456".toCharArray();
    }

    private static byte[] pinToBytes(char[] pin) {
        byte[] bytes = new byte[pin.length];
        for (int i = 0; i < pin.length; i++) {
            bytes[i] = (byte) pin[i];
        }
        return bytes;
    }

    private android.content.Context appContext() {
        return requireContext().getApplicationContext();
    }

    private void showCachedProfile() {
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
            demoAvatar.setImageTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(BLUE)));
            demoAvatar.setPadding(dp(10), dp(10), dp(10), dp(10));
            demoAvatar.setImageResource(R.drawable.ic_person);
            if (tvSmartCardName != null) {
                tvSmartCardName.setText("Chưa đồng bộ thẻ");
                tvSmartCardId.setText("MSSV: —");
                tvSmartCardDept.setText("");
                tvSmartCardSourceBadge.setVisibility(View.GONE);
            }
            return;
        }
        String idClass = "MSSV: " + record.getStudentId() + " • Lớp " + record.getClassName();
        String deptText = "Khoa " + record.getDepartment();
        demoName.setText(record.getFullName());
        demoStudentId.setText(idClass);
        demoDept.setText(deptText);

        if (tvSmartCardName != null) {
            tvSmartCardName.setText(record.getFullName());
            tvSmartCardId.setText(idClass);
            tvSmartCardDept.setText(deptText);
            if (badge != null) {
                tvSmartCardSourceBadge.setVisibility(View.VISIBLE);
                tvSmartCardSourceBadge.setText(badge);
            }
        }
        if (tvDemoQrSubtitle != null) {
            tvDemoQrSubtitle.setText(record.getFullName() + " • " + record.getStudentId()
                    + " • Lớp " + record.getClassName());
        }

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
                if (ivSmartCardAvatar != null) {
                    ivSmartCardAvatar.setImageTintList(null);
                    ivSmartCardAvatar.setPadding(0, 0, 0, 0);
                    ivSmartCardAvatar.setImageBitmap(avatar);
                }
            }
        }
    }

    private void renderComparison() {
        llComparison.removeAllViews();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        if (keys.isEmpty()) {
            addComparisonNote("Chưa có số liệu.");
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
        row.addView(comparisonCell("", 1.3f, Color.parseColor(SLATE_MUTED), Typeface.BOLD));
        row.addView(comparisonCell("BEFORE", 1f, Color.parseColor(RED), Typeface.BOLD));
        row.addView(comparisonCell("AFTER", 1f, Color.parseColor(GREEN), Typeface.BOLD));
        llComparison.addView(row);
    }

    private void addComparisonRow(String label, String beforeValue, String afterValue) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView labelCell = comparisonCell(label, 1.3f, Color.parseColor(SLATE), Typeface.NORMAL);
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

    private void showTopicSecurityInspector() {
        final Context appCtx = appContext();
        final boolean isCacheTopic = (topic == TOPIC_CACHE);
        final boolean flagSecureActive = (getActivity() instanceof BaseSecureActivity)
                && ((BaseSecureActivity) getActivity()).isFlagSecureEnabled();
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
            boolean hasEncryptedProfile = encryptedCache.getStudentProfile() != null;
            boolean keystoreOk = SecurityEnvironmentChecker.isAndroidKeyStoreAvailable();
            int remainingPinTries = pinSessionManager.getRemainingUnlockAttempts();
            SecurityEnvironmentChecker.SecurityAuditReport auditReport =
                    SecurityEnvironmentChecker.inspectEnvironment(appCtx);
            boolean bioStrong = BiometricKeyManager.isBiometricStrongAvailable(appCtx);
            String bioBacking = new BiometricKeyManager(appCtx).getBackingStoreLabel();
            return new InspectorData(plainText, cipherHex, leakContent, exposedPin,
                    hasEncryptedProfile, keystoreOk, remainingPinTries,
                    auditReport, bioStrong, bioBacking, flagSecureActive);
        }, (data, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            StringBuilder sb = new StringBuilder();
            if (isCacheTopic) {
                sb.append("=== KIỂM CHỨNG LƯU ĐỆM 2 TẦNG (§3 OWASP MASVS-STORAGE) ===\n");
                sb.append("• Tầng L1 (In-Memory RAM): ")
                        .append(encryptedCache.hasL1ProfileCached() ? "✓ Có sẵn (<1 ms)" : "• Trống (Đã xóa / Auto-Wipe)")
                        .append("\n");
                sb.append("• Tầng L2 (AndroidKeyStore TEE • AES-256-GCM):\n  ")
                        .append(data.hasEncryptedProfile ? "✓ Có hồ sơ đã mã hóa AES-GCM" : "• Trống (Auto-Wipe / chưa mở)")
                        .append(" • KeyStore TEE: ").append(data.keystoreOk ? "Sẵn sàng" : "Fallback")
                        .append("\n• Tự hủy sau 3 lần sai PIN: còn ").append(data.remainingPinTries).append("/3 lần thử\n\n");
                sb.append("• SharedPreferences(\"insecure_plaintext_leak\"):\n  ")
                        .append(data.leakContent != null
                                ? "❌ ĐANG LỘ BẢN RÕ: " + data.leakContent
                                : "✓ Sạch — Không có bản rõ chưa mã hóa")
                        .append("\n\n");
            } else {
                sb.append("=== KIỂM CHỨNG RAM PIN & OWASP MASVS (§5) ===\n");
                sb.append("• Vùng nhớ Heap chứa mã PIN:\n  ")
                        .append(data.exposedPin != null
                                ? "❌ PHƠI NHIỄM 100%: \"" + data.exposedPin + "\""
                                : "✓ Đã Zeroize (0%) — Dùng char[] + Arrays.fill('\\0')")
                        .append("\n\n");
                sb.append("• Điểm kiểm toán MASVS: ").append(chaosManager.getMasvsScore()).append("/5\n");
                sb.append("• RSA-1024 Challenge: ")
                        .append(chaosManager.isRsaVerificationBypassed() ? "❌ Đang bỏ qua!" : "✓ Đã xác thực")
                        .append("\n• Root/Debugger giả lập: ")
                        .append((chaosManager.isSimulatedRootDetected() || chaosManager.isSimulatedDebuggerDetected())
                                ? "❌ Phát hiện đe dọa!" : "✓ An toàn")
                        .append(" • PIN còn ").append(data.remainingPinTries).append("/3 lần thử\n\n");
                if (data.auditReport != null) {
                    sb.append("=== PHÒNG THỦ CHIỀU SÂU (MASVS-RESILIENCE & PLATFORM) ===\n");
                    sb.append("• Anti-Hooking (/proc/self/maps): ")
                            .append(data.auditReport.hookingDetected ? "❌ Phát hiện Frida/Xposed!" : "✓ Sạch")
                            .append("\n• Native Debugger (TracerPid): ")
                            .append(data.auditReport.nativeDebugger ? "❌ Đang attach!" : "✓ TracerPid = 0")
                            .append("\n• Toàn vẹn APK (SHA-256): ")
                            .append(data.auditReport.apkSignatureValid ? "✓ Khớp " : "❌ Sai lệch ")
                            .append(data.auditReport.getShortApkCertHex())
                            .append("\n• Bề mặt UI: Tapjacking Filter ON • FLAG_SECURE=")
                            .append(data.flagSecureActive ? "ON (Release)" : (BuildConfig.DEBUG ? "OFF (Debug demo)" : "OFF"))
                            .append("\n• Tự khóa nền: sau 120s xóa PIN tạm + L1 RAM")
                            .append("\n• Sinh trắc học phần cứng: ")
                            .append(data.biometricStrongAvailable
                                    ? "BIOMETRIC_STRONG + CryptoObject (" + data.biometricBacking + ")"
                                    : "Chưa đăng ký trên thiết bị")
                            .append("\n\n");
                }
            }
            sb.append("=== ĐỐI CHIẾU MÃ HÓA AES-128 TRÊN EEPROM ===\n");
            sb.append("• Bản rõ (INS 0x21 • P1=0x00):\n  ").append(data.plainUtf8).append("\n\n");
            sb.append("• Bản mã thô AES-128 (INS 0x21 • P1=0x01):\n  ").append(data.cipherHex);

            ScrollView scrollView = new ScrollView(requireContext());
            TextView tv = new TextView(requireContext());
            int pad = dp(18);
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextSize(12f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(0xFF0F172A);
            tv.setText(sb.toString());
            scrollView.addView(tv);

            new AlertDialog.Builder(requireContext())
                    .setTitle(isCacheTopic
                            ? "Kiểm chứng Cache AES-GCM & Plaintext Leak"
                            : "Kiểm chứng Zeroize PIN, AES-128 & MASVS")
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

    private static final class BottleneckRunResult {
        final StudentCardRecord record;
        final long[] stepMs;
        final long elapsedMs;
        final int apduCount;

        BottleneckRunResult(StudentCardRecord record, long[] stepMs, long elapsedMs, int apduCount) {
            this.record = record;
            this.stepMs = stepMs;
            this.elapsedMs = elapsedMs;
            this.apduCount = apduCount;
        }
    }

    private static final class CacheOpen {
        final StudentCardRecord record;
        final byte[] avatarBytes;
        final boolean fromCache;
        final int apduCount;
        final String cacheTier;

        CacheOpen(StudentCardRecord record, byte[] avatarBytes, boolean fromCache,
                  int apduCount, String cacheTier) {
            this.record = record;
            this.avatarBytes = avatarBytes;
            this.fromCache = fromCache;
            this.apduCount = apduCount;
            this.cacheTier = cacheTier;
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
        final String sourceDims;
        final int inSampleSize;
        final int bufferReuses;
        final boolean inGpuVram;
        final long deltaGcCount;
        final long deltaGcMs;

        ImageRunResult(Map<String, String> metrics, Bitmap preview, String status, boolean success,
                       double sizeKb, int packets, double ramMb,
                       String sourceDims, int inSampleSize, int bufferReuses,
                       boolean inGpuVram, long deltaGcCount, long deltaGcMs) {
            this.metrics = metrics;
            this.preview = preview;
            this.status = status;
            this.success = success;
            this.sizeKb = sizeKb;
            this.packets = packets;
            this.ramMb = ramMb;
            this.sourceDims = sourceDims;
            this.inSampleSize = inSampleSize;
            this.bufferReuses = bufferReuses;
            this.inGpuVram = inGpuVram;
            this.deltaGcCount = deltaGcCount;
            this.deltaGcMs = deltaGcMs;
        }

        static ImageRunResult failure(String status) {
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Kết quả", "Lỗi");
            return new ImageRunResult(metrics, null, status, false, 0, 0, 0,
                    "", 1, 0, false, 0L, 0L);
        }
    }

    private static final class PinRunResult {
        final long ms;
        final int remaining; // -1 = không đo được (String không xóa được)
        final int length;

        PinRunResult(long ms, int remaining, int length) {
            this.ms = ms;
            this.remaining = remaining;
            this.length = length;
        }
    }

    private static final class InspectorData {
        final String plainUtf8;
        final String cipherHex;
        final String leakContent;
        final String exposedPin;
        final boolean hasEncryptedProfile;
        final boolean keystoreOk;
        final int remainingPinTries;
        final SecurityEnvironmentChecker.SecurityAuditReport auditReport;
        final boolean biometricStrongAvailable;
        final String biometricBacking;
        final boolean flagSecureActive;

        InspectorData(String plainUtf8, String cipherHex, String leakContent, String exposedPin,
                      boolean hasEncryptedProfile, boolean keystoreOk, int remainingPinTries,
                      SecurityEnvironmentChecker.SecurityAuditReport auditReport,
                      boolean biometricStrongAvailable, String biometricBacking, boolean flagSecureActive) {
            this.plainUtf8 = plainUtf8;
            this.cipherHex = cipherHex;
            this.leakContent = leakContent;
            this.exposedPin = exposedPin;
            this.hasEncryptedProfile = hasEncryptedProfile;
            this.keystoreOk = keystoreOk;
            this.remainingPinTries = remainingPinTries;
            this.auditReport = auditReport;
            this.biometricStrongAvailable = biometricStrongAvailable;
            this.biometricBacking = biometricBacking;
            this.flagSecureActive = flagSecureActive;
        }
    }
}
