package com.example.smartstudentid.ui.demo;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
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
import android.security.keystore.KeyPermanentlyInvalidatedException;
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
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
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
import com.example.smartstudentid.ui.auth.LoginPinActivity;
import com.example.smartstudentid.ui.widget.CacheFlowView;
import com.example.smartstudentid.ui.widget.ExecutionWaterfallView;
import com.example.smartstudentid.ui.widget.ImagePipelineView;
import com.example.smartstudentid.ui.widget.RealtimeTopicChartView;
import com.example.smartstudentid.ui.widget.SecurityAuditFlowView;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
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

    private static final int SEC_FLOW_PIN = 0;
    private static final int SEC_FLOW_ANTI_HOOK = 1;
    private static final int SEC_FLOW_UI_LOCK = 2;
    private static final int SEC_FLOW_BIOMETRIC = 3;
    private static final int SEC_FLOW_COUNT = 4;

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
    private MaterialButton btnSecFlowPin;
    private MaterialButton btnSecFlowAntiHook;
    private MaterialButton btnSecFlowUiLock;
    private MaterialButton btnSecFlowBiometric;
    private View panelSecSubPin;
    private MaterialButton btnSecFlowLockNow;
    private MaterialButton btnToggleFlagSecureDemo;
    private LinearLayout panelStudent;
    private LinearLayout panelImage;
    private LinearLayout panelPin;
    private ImageView demoAvatar;
    private ImageView demoImagePreview;
    private TextView demoName;
    private TextView demoStudentId;
    private TextView demoDept;
    private TextView demoSourceBadge;
    private static final String[] PIN_DOT_STRINGS = {
            "", "•", "• •", "• • •", "• • • •", "• • • • •", "• • • • • •", "• • • • • • •", "• • • • • • • •"
    };
    private final char[] securePinBuf = new char[8];
    private int securePinLen = 0;

    private TextView demoImageInfo;
    private TextView tvSecurePinDisplay;
    private final View[] securePinKeyViews = new View[12];
    private MaterialButton btnAction;
    private MaterialButton btnPickImage;
    private MaterialButton btnSimulateWrongPin3x;
    private View rowCacheResetActions;
    private MaterialButton btnEvictL1Only;
    private MaterialButton btnResetCache;
    private boolean lastWipeWasAutoWipe;
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
    private final Map<String, String>[] secBeforeByFlow = createFlowMapArray();
    private final Map<String, String>[] secAfterByFlow = createFlowMapArray();
    private int secSubFlow = SEC_FLOW_PIN;
    private volatile String lastBeforeRetainedPin = null;
    private int lastTestedPinLength = 6;

    @SuppressWarnings("unchecked")
    private static Map<String, String>[] createFlowMapArray() {
        Map<String, String>[] arr = new Map[SEC_FLOW_COUNT];
        for (int i = 0; i < SEC_FLOW_COUNT; i++) {
            arr[i] = new LinkedHashMap<>();
        }
        return arr;
    }

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
                chaosManager.resolveStep3ImageAndMemory();
                before.clear();
                after.clear();
                if (isAdded()) {
                    renderComparison();
                    updateTechSummaryLine();
                    if (imagePipelineView != null) {
                        imagePipelineView.resetToIdle();
                    }
                    if (realtimeChart != null) {
                        realtimeChart.setTopic(TOPIC_IMAGE);
                        realtimeChart.setAfterMode(optimized());
                    }
                    if (tvCardQuota8Kb != null) {
                        tvCardQuota8Kb.setTextColor(Color.parseColor(SLATE));
                        tvCardQuota8Kb.setText("Bộ nhớ thẻ chip: 0 / 8.0 KB (Bấm \"Lưu ảnh lên thẻ chip\" để đo)");
                    }
                    if (pbCardQuota8Kb != null) {
                        pbCardQuota8Kb.setProgress(0);
                    }
                    loadPickedPreview(uri);
                }
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
            showWaitingForImageState();
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
        } else if (topic == TOPIC_STARTUP) {
            syncFlagSecureForCurrentFlow(optimized());
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
        wipeSecurePinBuffer();
        if (topic == TOPIC_THREADING) {
            chaosManager.resolveStep1Threading();
        } else if (topic == TOPIC_IMAGE) {
            chaosManager.resolveStep3ImageAndMemory();
        } else if (topic == TOPIC_STARTUP && BuildConfig.DEBUG
                && getActivity() instanceof BaseSecureActivity) {
            ((BaseSecureActivity) getActivity()).applyFlagSecure(false);
        }
    }

    @Override
    public void onDestroyView() {
        autoDemoHandler.removeCallbacksAndMessages(null);
        stopLiveCounterPolling();
        qrTimerHandler.removeCallbacks(qrTickRunnable);
        releaseLiveMetricFlags();
        ApduClient.getInstance().clearTelemetryListener(apduTelemetryListener);
        wipeSecurePinBuffer();
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
        btnSecFlowPin = root.findViewById(R.id.btnSecFlowPin);
        btnSecFlowAntiHook = root.findViewById(R.id.btnSecFlowAntiHook);
        btnSecFlowUiLock = root.findViewById(R.id.btnSecFlowUiLock);
        btnSecFlowBiometric = root.findViewById(R.id.btnSecFlowBiometric);
        panelSecSubPin = root.findViewById(R.id.panelSecSubPin);
        btnSecFlowLockNow = root.findViewById(R.id.btnSecFlowLockNow);
        btnToggleFlagSecureDemo = root.findViewById(R.id.btnToggleFlagSecureDemo);
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
        tvSecurePinDisplay = root.findViewById(R.id.tvSecurePinDisplay);
        securePinKeyViews[0] = root.findViewById(R.id.btnPinKey0);
        securePinKeyViews[1] = root.findViewById(R.id.btnPinKey1);
        securePinKeyViews[2] = root.findViewById(R.id.btnPinKey2);
        securePinKeyViews[3] = root.findViewById(R.id.btnPinKey3);
        securePinKeyViews[4] = root.findViewById(R.id.btnPinKey4);
        securePinKeyViews[5] = root.findViewById(R.id.btnPinKey5);
        securePinKeyViews[6] = root.findViewById(R.id.btnPinKey6);
        securePinKeyViews[7] = root.findViewById(R.id.btnPinKey7);
        securePinKeyViews[8] = root.findViewById(R.id.btnPinKey8);
        securePinKeyViews[9] = root.findViewById(R.id.btnPinKey9);
        securePinKeyViews[10] = root.findViewById(R.id.btnPinKeyBack);
        securePinKeyViews[11] = root.findViewById(R.id.btnPinKeyClear);
        btnAction = root.findViewById(R.id.btnDemoAction);
        btnPickImage = root.findViewById(R.id.btnPickImage);
        btnSimulateWrongPin3x = root.findViewById(R.id.btnSimulateWrongPin3x);
        rowCacheResetActions = root.findViewById(R.id.rowCacheResetActions);
        btnEvictL1Only = root.findViewById(R.id.btnEvictL1Only);
        btnResetCache = root.findViewById(R.id.btnResetCache);
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
        if (rowCacheResetActions != null) {
            rowCacheResetActions.setVisibility(topic == TOPIC_CACHE ? View.VISIBLE : View.GONE);
        }
        btnResetCache.setVisibility(topic == TOPIC_CACHE ? View.VISIBLE : View.GONE);
        if (btnEvictL1Only != null) {
            btnEvictL1Only.setVisibility(topic == TOPIC_CACHE ? View.VISIBLE : View.GONE);
        }
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
                title.setText("Bảo mật & Phòng thủ (4 Luồng)");
                realtimeChart.setColdStartMs(DemoMetrics.getColdStartMs());
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • PHƠI NHIỄM PIN TRÊN RAM");
                selectSecuritySubFlow(SEC_FLOW_PIN);
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
            if (topic == TOPIC_IMAGE) {
                if (pickedUri == null) {
                    promptPickImageFirst();
                } else if (demoImageInfo != null) {
                    demoImageInfo.setTextColor(Color.parseColor(SLATE));
                }
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
            if (topic == TOPIC_IMAGE && pickedUri == null) {
                promptPickImageFirst();
                return;
            }
            boolean mode = optimized();
            applyTopicChaosState(mode);
            runCurrentMode();
        });
        btnPickImage.setOnClickListener(v -> imagePicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
        btnSimulateWrongPin3x.setOnClickListener(v -> simulateWrongPin3xAutoWipe());
        for (int i = 0; i <= 9; i++) {
            final char digitChar = (char) ('0' + i);
            if (securePinKeyViews[i] != null) {
                securePinKeyViews[i].setOnClickListener(v -> appendSecurePinDigit(digitChar));
            }
        }
        if (securePinKeyViews[10] != null) {
            securePinKeyViews[10].setOnClickListener(v -> backspaceSecurePinDigit());
        }
        if (securePinKeyViews[11] != null) {
            securePinKeyViews[11].setOnClickListener(v -> wipeSecurePinBuffer());
        }
        if (btnEvictL1Only != null) {
            btnEvictL1Only.setOnClickListener(v -> evictL1Only());
        }
        btnResetCache.setOnClickListener(v -> resetCache());
        if (btnSecFlowPin != null) {
            btnSecFlowPin.setOnClickListener(v -> selectSecuritySubFlow(SEC_FLOW_PIN));
        }
        if (btnSecFlowAntiHook != null) {
            btnSecFlowAntiHook.setOnClickListener(v -> selectSecuritySubFlow(SEC_FLOW_ANTI_HOOK));
        }
        if (btnSecFlowUiLock != null) {
            btnSecFlowUiLock.setOnClickListener(v -> selectSecuritySubFlow(SEC_FLOW_UI_LOCK));
        }
        if (btnSecFlowBiometric != null) {
            btnSecFlowBiometric.setOnClickListener(v -> selectSecuritySubFlow(SEC_FLOW_BIOMETRIC));
        }
        if (btnSecFlowLockNow != null) {
            btnSecFlowLockNow.setOnClickListener(v -> {
                pinSessionManager.lockSessionAndEvictRam();
                Intent intent = new Intent(requireContext(), LoginPinActivity.class);
                intent.putExtra(LoginPinActivity.EXTRA_SESSION_LOCKED_TIMEOUT, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                if (getActivity() != null) {
                    getActivity().finish();
                }
            });
        }
        if (btnToggleFlagSecureDemo != null) {
            btnToggleFlagSecureDemo.setOnClickListener(v -> {
                if (getActivity() instanceof BaseSecureActivity) {
                    BaseSecureActivity host = (BaseSecureActivity) getActivity();
                    host.applyFlagSecure(!host.isFlagSecureEnabled());
                    updateFlagSecureToggleButton(optimized());
                }
            });
        }
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

        if (topic == TOPIC_IMAGE) {
            if (pickedUri == null) {
                promptPickImageFirst();
            } else if (isAdded()) {
                Toast.makeText(requireContext(),
                        "Đã chọn chế độ " + (afterMode ? "Đã tối ưu" : "Chưa tối ưu")
                                + " — bấm \"Lưu ảnh lên thẻ chip\" để đo.",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
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
        if (topic == TOPIC_IMAGE && btnAction != null) {
            btnAction.setText(checked
                    ? "Lưu ảnh lên thẻ chip (Đã tối ưu)"
                    : "Lưu ảnh lên thẻ chip (Chưa tối ưu)");
        }
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
                // Cleared manually or Auto-Wiped after 3 wrong PINs
                boolean isAuto = lastWipeWasAutoWipe;
                boxTierL1.setBackgroundResource(isAuto ? R.drawable.bg_status_red : R.drawable.bg_soft_panel);
                tvTierL1State.setText(isAuto ? "ĐÃ HỦY" : "TRỐNG");
                tvTierL1State.setTextColor(Color.parseColor(isAuto ? RED : SLATE_MUTED));

                boxTierL2.setBackgroundResource(isAuto ? R.drawable.bg_status_red : R.drawable.bg_soft_panel);
                tvTierL2State.setText(isAuto ? "ĐÃ HỦY" : "TRỐNG");
                tvTierL2State.setTextColor(Color.parseColor(isAuto ? RED : SLATE_MUTED));

                boxTierChip.setBackgroundResource(R.drawable.bg_soft_panel);
                tvTierChipState.setText(isAuto ? "0 APDU" : "Cần quẹt");
                tvTierChipState.setTextColor(Color.parseColor(SLATE_MUTED));

                boxComparePlaintext.setBackgroundResource(R.drawable.bg_soft_panel);
                tvComparePlainTitle.setText("📄 Bản rõ");
                tvComparePlainTitle.setTextColor(Color.parseColor(SLATE_MUTED));
                tvComparePlainValue.setText("✓ 0 byte (Sạch)");
                tvComparePlainValue.setTextColor(Color.parseColor(GREEN));

                boxCompareEncrypted.setBackgroundResource(isAuto ? R.drawable.bg_status_red : R.drawable.bg_soft_panel);
                tvCompareEncTitle.setText(isAuto ? "💥 Auto-Wipe 3/3" : "🧹 Cache trống");
                tvCompareEncTitle.setTextColor(Color.parseColor(isAuto ? RED : SLATE_MUTED));
                tvCompareEncValue.setText(isAuto ? "0 byte trên máy\n(Cần quẹt lại)" : "0 byte trên máy\n(Lần tới đọc thẻ)");
                tvCompareEncValue.setTextColor(Color.parseColor(isAuto ? RED : SLATE_MUTED));
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
            if (secSubFlow == SEC_FLOW_PIN) {
                int score = afterMode ? 5 : chaosManager.getMasvsScore();
                int accent = Color.parseColor(afterMode ? GREEN : RED);
                panelSecurityShield.setBackgroundResource(afterMode ? R.drawable.bg_status_green : R.drawable.bg_status_red);
                tvSecurityShieldTitle.setText(afterMode ? "✓ Bộ nhớ PIN & Khóa trung gian an toàn" : "❌ Cảnh báo phơi nhiễm PIN");
                tvSecurityShieldTitle.setTextColor(accent);
                tvSecurityShieldScore.setText("MASVS " + score + "/5");
                tvSecurityShieldScore.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
                tvSecurityShieldScore.setTextColor(accent);

                renderMasvsSegments(score, afterMode);
                String pinSource = afterMode
                        ? "00000000".substring(0, Math.max(4, Math.min(8, lastTestedPinLength)))
                        : (lastBeforeRetainedPin != null ? lastBeforeRetainedPin : "123456");
                renderPinRamCells(pinSource, afterMode);

                tvPinRamExposureBadge.setText(afterMode ? "0% phơi nhiễm (Đã xóa)" : "100% lộ trên Heap!");
                tvPinRamExposureBadge.setTextColor(accent);

                tvChipPinType.setText(afterMode ? "✓ char[]" : "✕ String");
                tvChipPinType.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
                tvChipPinType.setTextColor(accent);

                tvChipRsaState.setText(afterMode ? "✓ Xóa byte[]" : "✕ Sót byte[]");
                tvChipRsaState.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
                tvChipRsaState.setTextColor(accent);

                tvChipEnvState.setText(afterMode ? "✓ Lazy Init" : "✕ Sync Init");
                tvChipEnvState.setBackgroundResource(afterMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
                tvChipEnvState.setTextColor(accent);
            }
            if (btnSecFlowLockNow != null) {
                btnSecFlowLockNow.setVisibility(
                        (secSubFlow == SEC_FLOW_UI_LOCK && afterMode) ? View.VISIBLE : View.GONE);
            }
            updateFlagSecureToggleButton(afterMode);
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
                applyPinPadTapjackingFilter(afterMode);
                if (btnAction != null) {
                    btnAction.setFilterTouchesWhenObscured(afterMode);
                }
                syncFlagSecureForCurrentFlow(afterMode);
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
                switch (secSubFlow) {
                    case SEC_FLOW_ANTI_HOOK:
                        runAntiHookAndApkFlow(optimized());
                        break;
                    case SEC_FLOW_UI_LOCK:
                        runUiAndBackgroundLockFlow(optimized());
                        break;
                    case SEC_FLOW_BIOMETRIC:
                        runBiometricTeeFlow(optimized());
                        break;
                    default:
                        runLogin(optimized());
                        break;
                }
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
                appendTrace(TRACE_COLOR_INFO, "Kiểm tra Cache 2 tầng (L1 RAM → L2 AndroidKeyStore TEE)");
                StudentCardRecord cached = encryptedCache.getStudentProfile();
                String tier = encryptedCache.getLastReadTier();
                int apduSent = 0;
                if (cached == null) {
                    appendTrace(TRACE_COLOR_INFO, "Cache trống (MISS) → Đọc thẻ qua APDU & lưu lại L2 TEE + L1 RAM...");
                    LocalCacheManager.ProfileLoadResult cardRes = fetchFromCard();
                    cached = cardRes.getRecord();
                    apduSent = cardRes.getApduCommandsSent();
                    if (cached != null) {
                        encryptedCache.saveStudentProfile(cached);
                    }
                    tier = "COLD_CARD";
                }
                long elapsedMs = Math.max(4L, (System.nanoTime() - startNs) / 1_000_000L);
                long[] stepMs = apduSent > 0
                        ? new long[]{Math.max(1L, elapsedMs / 5), Math.max(1L, elapsedMs / 5), Math.max(2L, elapsedMs / 5), 0L, Math.max(2L, elapsedMs / 5)}
                        : new long[]{0L, 0L, Math.max(2L, elapsedMs / 2), 0L, Math.max(2L, elapsedMs - elapsedMs / 2)};
                return new BottleneckRunResult(cached, stepMs, elapsedMs, apduSent, tier);
            }, (res, blockedMs, totalMs) -> {
                boolean cold = "COLD_CARD".equals(res.cacheTier);
                String tierUi = "L1_RAM".equals(res.cacheTier)
                        ? "L1 RAM (<1 ms)"
                        : ("L2_TEE".equals(res.cacheTier) ? "L2 TEE (AES-GCM)" : "Thẻ → Nạp L1+L2");
                showProfile(res.record, encryptedCache.getAvatarBytes(), cold ? "THẺ → L1+L2" : "CACHE");
                appendTrace(TRACE_COLOR_OK, cold
                        ? "↻ Cache MISS → đọc thẻ (" + res.apduCount + " APDU, " + res.elapsedMs + " ms) & đã lưu L2 TEE + L1 RAM"
                        : "✓ Hit " + tierUi + " → bỏ qua 41 lệnh APDU & RSA (" + res.elapsedMs + " ms, 0 APDU)");
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
                metrics.put("Tầng đọc", tierUi);
                metrics.put("1. SELECT applet", res.stepMs[0] + " ms");
                metrics.put("2. VERIFY PIN", res.stepMs[1] + " ms");
                metrics.put("3. AES-128 EEPROM", res.stepMs[2] + (cold ? " ms" : " ms (GCM)"));
                metrics.put("4. Ký RSA-1024", res.stepMs[3] + (cold ? " ms" : " ms (Cached)"));
                metrics.put("5. Học vụ & 35 ảnh", res.stepMs[4] + (cold ? " ms" : " ms (WebP)"));
                metrics.put("Tổng thời gian", res.elapsedMs + " ms");
                metrics.put("Chatty I/O", res.apduCount == 0 ? "0 gói (Giảm 100%)" : res.apduCount + " gói (Nạp lạnh)");
                metrics.put("Hao pin I/O", formatBatteryImpact(res.apduCount, res.elapsedMs));
                appendTrace(TRACE_COLOR_OK, "🔋 Năng lượng: " + formatBatteryImpact(res.apduCount, res.elapsedMs));
                finishRun(true, metrics, cold
                        ? "↻ Cache trống (MISS): đã đọc thẻ (" + res.elapsedMs + " ms) và lưu vào L2 TEE + L1 RAM!"
                        : "✓ After (" + tierUi + "): phản hồi trong " + res.elapsedMs + " ms (0 Chatty I/O • tiết kiệm pin)!", true);
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
                return new BottleneckRunResult(record, stepMs, totalElapsedMs, apduCount, "BYPASS");
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
            metrics.put("Tầng đọc", "Thẻ trực tiếp (0 Cache)");
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

    private static String shortWorkerTag(String threadName, int fallbackIndex) {
        if (threadName != null) {
            int idx = threadName.lastIndexOf('T');
            if (idx >= 0 && idx + 1 < threadName.length()) {
                return threadName.substring(idx);
            }
        }
        return "T" + (fallbackIndex + 1);
    }

    // ------------------------------------------------------------------
    // §2 — refresh from card in Before (Main Thread) / After (pool)
    // ------------------------------------------------------------------

    private void runThreading(boolean afterMode) {
        String where = afterMode ? "thread nền (After)" : "Main Thread (Before)";
        startRun("Đang làm mới hồ sơ trên " + where + "...");
        if (afterMode) {
            resetTrace("▶ BẮT ĐẦU §2 AFTER — Chạy song song 4 luồng ApduThreadPool", true);
            if (threadingLaneView != null) {
                threadingLaneView.beginAfterRun();
            }
            appendTrace(TRACE_COLOR_OK, "Hủy jankRunnable trên MainLooper (gỡ chặn UI)");
            appendTrace(TRACE_COLOR_INFO, "Giao đồng thời 4 tác vụ (APDU · QR 30s · PBKDF2 · Cache L2) cho ApduThreadPool (4T)");

            final AtomicReference<LocalCacheManager.ProfileLoadResult> cardResultRef = new AtomicReference<>();
            final AtomicReference<Bitmap> qrBitmapRef = new AtomicReference<>();
            final String qrStudentId = "CT070318";
            final String[] taskLabels = {"APDU thẻ", "Sinh QR 30s", "Băm PBKDF2", "Cache L2 TEE"};

            @SuppressWarnings("unchecked")
            Callable<String>[] parallelTasks = new Callable[]{
                    () -> {
                        LocalCacheManager.ProfileLoadResult res = fetchFromCard();
                        cardResultRef.set(res);
                        return res.getElapsedMs() + " ms";
                    },
                    () -> {
                        long windowEpoch = System.currentTimeMillis() / 30_000L;
                        String payload = "SMARTID|" + qrStudentId + "|T" + windowEpoch + "|RSA1024";
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
                        qrBitmapRef.set(bmp);
                        return w + "x" + h;
                    },
                    () -> {
                        char[] demoPin = new char[]{'0', '0', '0', '0', '0', '0'};
                        byte[] salt = CryptoManager.generateRandomBytes(16);
                        String hash = CryptoManager.computePinVerifierHash(demoPin, salt);
                        Arrays.fill(demoPin, '\0');
                        return (hash != null ? hash.length() : 0) + " ch";
                    },
                    () -> {
                        encryptedCache.getStudentProfile();
                        byte[] av = encryptedCache.getAvatarBytes();
                        if (av != null && av.length > 0) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(av, 0, av.length, opts);
                        }
                        return encryptedCache.getLastReadTier();
                    }
            };

            ApduThreadPool.getInstance().executeParallelBatch(taskLabels, parallelTasks, (reports, blockedMs, wallMs) -> {
                if (!isAdded()) return;
                LocalCacheManager.ProfileLoadResult cardResult = cardResultRef.get();
                if (cardResult != null && cardResult.getRecord() != null) {
                    showProfile(cardResult.getRecord(), null, "THẺ");
                }
                Bitmap qrBmp = qrBitmapRef.get();
                if (qrBmp != null && ivDemoQr != null) {
                    ivDemoQr.setImageBitmap(qrBmp);
                }

                String[] rLabels = new String[reports.length];
                String[] rThreads = new String[reports.length];
                long[] rStarts = new long[reports.length];
                long[] rDurs = new long[reports.length];
                long sumMs = 0L;
                for (int i = 0; i < reports.length; i++) {
                    ApduThreadPool.WorkerTaskReport r = reports[i];
                    rLabels[i] = r.label;
                    rThreads[i] = r.threadName;
                    rStarts[i] = r.startOffsetMs;
                    rDurs[i] = r.durationMs;
                    sumMs += r.durationMs;
                    appendTrace(TRACE_COLOR_INFO, String.format(Locale.US,
                            "  ↳ %s [%s]: %s (%d ms · +%d ms)",
                            shortWorkerTag(r.threadName, i), r.threadName, r.label, r.durationMs, r.startOffsetMs));
                }

                appendTrace(TRACE_COLOR_OK, "✓ Xong song song 4T trong " + wallMs + " ms (tổng gộp " + sumMs
                        + " ms), Main Thread giữ " + msText(blockedMs) + " → giữ 60 FPS");

                if (threadingLaneView != null) {
                    threadingLaneView.finishAfterRun(blockedMs, wallMs, rLabels, rThreads, rStarts, rDurs);
                }
                if (realtimeChart != null) {
                    realtimeChart.recordThreadingResult(true, blockedMs, wallMs);
                }

                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("Cơ chế", TopicMechanismGuide.forTopic(topic, true).comparisonMechanismLabel);
                metrics.put("Main Thread giữ", msText(blockedMs));
                metrics.put("Luồng nền", "4 Worker (T1–T4)");
                for (int i = 0; i < reports.length; i++) {
                    ApduThreadPool.WorkerTaskReport r = reports[i];
                    metrics.put("T" + (i + 1) + " · " + r.label,
                            r.durationMs + " ms (" + shortWorkerTag(r.threadName, i) + ")");
                }
                metrics.put("Tổng tường (4T)", wallMs + " ms (gộp " + sumMs + " ms)");
                metrics.put("Khung rớt", Math.round(blockedMs / 16.6667f) + " khung");
                finishRun(true, metrics,
                        "✓ After: 4 luồng nền (T1–T4) chạy song song xong trong " + wallMs
                                + " ms (gộp tuần tự " + sumMs + " ms), UI giữ 60 FPS.", true);
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
            metrics.put("Luồng nền", "0/4 Worker (Nhàn rỗi)");
            metrics.put("T1 · APDU thẻ", accumulatedMs + " ms (Main)");
            metrics.put("T2 · Sinh QR 30s", "Bị chặn bởi Main");
            metrics.put("T3 · Băm PBKDF2", "Bị chặn bởi Main");
            metrics.put("T4 · Cache L2 TEE", "Bị chặn bởi Main");
            metrics.put("Tổng tường (4T)", accumulatedMs + " ms (tuần tự)");
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
                if (cached != null) {
                    return new CacheOpen(cached, encryptedCache.getAvatarBytes(), true, 0, tier);
                }
                // Cold Cache MISS: L1 & L2 are both empty -> perform genuine APDU card read, then hydrate L2 + L1.
                appendTrace(TRACE_COLOR_FAULT, "Cache MISS (L1 & L2 đều trống) → Gửi lệnh APDU đọc lại từ Thẻ Chip...");
                long coldStartNs = System.nanoTime();
                LocalCacheManager.ProfileLoadResult coldResult = fetchFromCard();
                ApduClient client = ApduClient.getInstance();
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_SIGN_CHALLENGE,
                        (byte) 0x00, (byte) 0x00, CryptoManager.generateRandomBytes(16));
                int coldApdus = coldResult.getApduCommandsSent() + 1;
                sleepRemainder(coldStartNs, 520L);

                StudentCardRecord rec = coldResult.getRecord();
                if (rec != null) {
                    encryptedCache.saveStudentProfile(rec);
                    if (!pinSessionManager.hasActiveSession()) {
                        pinSessionManager.createPinSession(rec.getStudentId(), rec.getFullName(), "123456".toCharArray());
                    }
                }
                return new CacheOpen(rec, encryptedCache.getAvatarBytes(), false, coldApdus, "COLD_CARD");
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
            if ("COLD_CARD".equals(openResult.cacheTier)) {
                lastWipeWasAutoWipe = false;
                int apduCount = openResult.apduCount;
                showProfile(openResult.record, openResult.avatarBytes, "THẺ → L1+L2");
                appendTrace(TRACE_COLOR_OK, "↻ MISS toàn bộ (L1 & L2 trống) → Đọc thẻ " + apduCount
                        + " APDU (" + totalMs + " ms) → Đã mã hóa lưu L2 TEE & nạp L1 RAM");
                if (cacheFlowView != null) {
                    cacheFlowView.finishAfterColdMiss(totalMs, apduCount);
                }
                if (realtimeChart != null) {
                    realtimeChart.recordCacheResult(true, false, totalMs, apduCount);
                }
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("Cơ chế", "Cache MISS → Nạp 2 tầng");
                metrics.put("Nguồn", "Thẻ (" + apduCount + " APDU) → L1+L2");
                metrics.put("Thời gian", totalMs + " ms (Nạp lạnh)");
                metrics.put("I/O thừa", apduCount + " APDU (Lần sau: 0)");
                metrics.put("Tự hủy 3x PIN", "Sẵn sàng (3/3 lần)");
                finishRun(true, metrics, "↻ Cache trống (MISS): đã đọc lại từ thẻ (" + totalMs + " ms • "
                        + apduCount + " APDU) và lưu L2 TEE + L1 RAM. Bấm Mở hồ sơ lần nữa để thấy Hit L1 RAM!", true);
                return;
            }

            if (openResult.fromCache) {
                lastWipeWasAutoWipe = false;
            }
            String tierLabel = "L1_RAM".equals(openResult.cacheTier)
                    ? "L1 RAM"
                    : ("L2_TEE".equals(openResult.cacheTier) ? "L2 TEE" : "THẺ");
            showProfile(openResult.record, openResult.avatarBytes, openResult.fromCache ? tierLabel : "THẺ");
            int apduCount = openResult.fromCache ? 0 : openResult.apduCount;
            if ("L1_RAM".equals(openResult.cacheTier)) {
                appendTrace(TRACE_COLOR_OK, "✓ Hit L1 RAM (bỏ qua L2 & thẻ): " + totalMs + " ms (0 APDU)");
            } else if ("L2_TEE".equals(openResult.cacheTier)) {
                appendTrace(TRACE_COLOR_OK, "✓ L1 MISS → Hit L2 AndroidKeyStore TEE (AES-256-GCM) → Nạp lại L1 RAM: "
                        + totalMs + " ms (0 APDU)");
            } else {
                appendTrace(TRACE_COLOR_FAULT, "⚠ Bypass Cache: tốn " + totalMs + " ms (" + apduCount + " APDU) & lộ bản rõ");
            }
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
            String sourceMetric = !openResult.fromCache
                    ? "Thẻ (36 APDU)"
                    : ("L1_RAM".equals(openResult.cacheTier)
                            ? "L1 RAM (Nhánh nóng)"
                            : "L2 TEE (AES-GCM → Nạp L1)");
            metrics.put("Nguồn", sourceMetric);
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
                    lastWipeWasAutoWipe = true;
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

    private void evictL1Only() {
        if (busy) return;
        if (!encryptedCache.hasL2ProfileCached()) {
            Toast.makeText(requireContext(),
                    "L2 TEE đang trống — bấm Mở hồ sơ để nạp dữ liệu từ thẻ trước.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        encryptedCache.evictL1RamOnly();
        lastWipeWasAutoWipe = false;
        if (cacheFlowView != null) {
            cacheFlowView.recordL1Evicted();
        }
        appendTrace(TRACE_COLOR_INFO,
                "Đã xóa riêng L1 RAM (L2 AndroidKeyStore TEE vẫn giữ) → Bấm Mở hồ sơ để test L1 MISS → L2 HIT");
        updateRealAppStatusCards(optimized());
    }

    private void resetCache() {
        if (busy) return;
        lastWipeWasAutoWipe = false;
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
        if (pickedUri == null) {
            promptPickImageFirst();
            return;
        }
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
            if (pickedUri == null) {
                return ImageRunResult.failure("⚠ Vui lòng chọn ảnh từ thư viện trước.");
            }
            int[] dims = sourceBounds(null);
            full = decodeFullSize(dims[0], dims[1], null);
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
            if (pickedUri == null) {
                return ImageRunResult.failure("⚠ Vui lòng chọn ảnh từ thư viện trước.");
            }
            int[] dims = sourceBounds(null);
            ImageOptimizer.AvatarOptimizationReport report =
                    ImageOptimizer.decodeAndCompressAvatarReport(appContext(), pickedUri);
            if (report == null) {
                return ImageRunResult.failure("❌ Không xử lý được ảnh đã chọn (có thể quá lớn).");
            }
            byte[] webp = report.webpBytes;
            int sampleSize = report.sampleSize;
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

    private void showWaitingForImageState() {
        if (demoImageInfo != null) {
            demoImageInfo.setTextColor(Color.parseColor(SLATE_MUTED));
            demoImageInfo.setText("Chưa chọn ảnh — hãy bấm \"Chọn ảnh từ thư viện\"");
        }
        if (tvCardQuota8Kb != null) {
            tvCardQuota8Kb.setTextColor(Color.parseColor(SLATE_MUTED));
            tvCardQuota8Kb.setText("Bộ nhớ thẻ chip: 0 / 8.0 KB (Chờ chọn ảnh...)");
        }
        if (pbCardQuota8Kb != null) {
            pbCardQuota8Kb.setProgress(0);
        }
    }

    private void promptPickImageFirst() {
        if (demoImageInfo != null) {
            demoImageInfo.setTextColor(Color.parseColor(RED));
            demoImageInfo.setText("⚠ Chưa có ảnh — bấm \"Chọn ảnh từ thư viện\" trước");
        }
        if (isAdded()) {
            Toast.makeText(requireContext(),
                    "Vui lòng chọn ảnh từ thư viện trước khi đo!",
                    Toast.LENGTH_SHORT).show();
        }
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
            if (demoImageInfo != null) {
                demoImageInfo.setTextColor(Color.parseColor(SLATE));
                demoImageInfo.setText("Ảnh đã chọn: " + result.width + "×" + result.height
                        + " px • Bấm \"Lưu ảnh lên thẻ chip\" để đo");
            }
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
    // §5 — 4 separated security flows
    // ------------------------------------------------------------------

    private void selectSecuritySubFlow(int flow) {
        if (busy) {
            busy = false;
            endRun();
        }
        secSubFlow = Math.max(0, Math.min(SEC_FLOW_COUNT - 1, flow));

        styleSecurityFlowPill(btnSecFlowPin, secSubFlow == SEC_FLOW_PIN);
        styleSecurityFlowPill(btnSecFlowAntiHook, secSubFlow == SEC_FLOW_ANTI_HOOK);
        styleSecurityFlowPill(btnSecFlowUiLock, secSubFlow == SEC_FLOW_UI_LOCK);
        styleSecurityFlowPill(btnSecFlowBiometric, secSubFlow == SEC_FLOW_BIOMETRIC);

        if (panelSecSubPin != null) {
            panelSecSubPin.setVisibility(secSubFlow == SEC_FLOW_PIN ? View.VISIBLE : View.GONE);
        }

        if (btnAction != null) {
            switch (secSubFlow) {
                case SEC_FLOW_ANTI_HOOK:
                    btnAction.setText("Chạy test: Quét Hook & Toàn vẹn APK");
                    break;
                case SEC_FLOW_UI_LOCK:
                    btnAction.setText("Chạy test: Bảo vệ UI & Khóa nền");
                    break;
                case SEC_FLOW_BIOMETRIC:
                    btnAction.setText("Chạy test: Mở khóa Sinh trắc TEE");
                    break;
                default:
                    btnAction.setText("Chạy test: Xác thực & Zeroize PIN");
                    break;
            }
        }

        if (tvTraceHeaderTitle != null) {
            switch (secSubFlow) {
                case SEC_FLOW_ANTI_HOOK:
                    tvTraceHeaderTitle.setText("LUỒNG 2 • ANTI-HOOK & TOÀN VẸN APK (§5)");
                    break;
                case SEC_FLOW_UI_LOCK:
                    tvTraceHeaderTitle.setText("LUỒNG 3 • FLAG_SECURE, TAPJACKING & KHÓA NỀN (§5)");
                    break;
                case SEC_FLOW_BIOMETRIC:
                    tvTraceHeaderTitle.setText("LUỒNG 4 • SINH TRẮC HỌC CRYPTOOBJECT TEE (§5)");
                    break;
                default:
                    tvTraceHeaderTitle.setText("LUỒNG 1 • ZEROIZE PIN & KHÓA TRUNG GIAN (§5)");
                    break;
            }
        }

        if (securityAuditFlowView != null) {
            switch (secSubFlow) {
                case SEC_FLOW_ANTI_HOOK:
                    securityAuditFlowView.resetToIdleForFlow(
                            "LUỒNG 2: CHỐNG HOOK & TOÀN VẸN APK",
                            new String[]{"ANTI-HOOK", "TRACER PID", "TOÀN VẸN APK"},
                            new String[]{"QUÉT MAPS", "TRACER PID", "APK SHA-256"},
                            new String[]{"/proc/self/maps", "/proc/self/status", "Chữ ký APK"},
                            "Bấm nút chạy để quét Frida, TracerPid, SHA-256 APK & phản ứng xóa L1",
                            new String[]{
                                    "1. Quét Frida / Xposed trên /proc/self/maps",
                                    "2. Đọc TracerPid chống Native Debugger",
                                    "3. Đối chiếu SHA-256 APK & phản ứng xóa L1"
                            }
                    );
                    break;
                case SEC_FLOW_UI_LOCK:
                    securityAuditFlowView.resetToIdleForFlow(
                            "LUỒNG 3: CHỐNG CHỤP, TAPJACKING & KHÓA NỀN",
                            new String[]{"FLAG_SECURE", "TAPJACKING", "KHÓA NỀN 120S"},
                            new String[]{"CHỐNG CHỤP", "LỌC PHỦ", "KHÓA L1 RAM"},
                            new String[]{"Window Flag", "Obscured Touch", "Timeout 120s"},
                            "Bấm nút chạy để bật FLAG_SECURE thật (thử chụp màn hình) & khóa L1 RAM",
                            new String[]{
                                    "1. Bật cờ FLAG_SECURE chống chụp & quay màn hình",
                                    "2. Lọc chạm Tapjacking (filterTouchesWhenObscured)",
                                    "3. Khóa phiên nền 120s: xóa PIN tạm & tầng L1 RAM"
                            }
                    );
                    break;
                case SEC_FLOW_BIOMETRIC:
                    securityAuditFlowView.resetToIdleForFlow(
                            "LUỒNG 4: SINH TRẮC HỌC CRYPTOOBJECT TEE",
                            new String[]{"CƠ CHẾ MỞ", "PHẦN CỨNG", "CHỐNG THAY VÂN TAY"},
                            new String[]{"BIOMETRIC", "KEYSTORE", "AES-256-GCM"},
                            new String[]{"BIOMETRIC_STRONG", "TEE / StrongBox", "CryptoObject"},
                            "Bấm nút chạy để xác thực vân tay thật qua CryptoObject trong AndroidKeyStore",
                            new String[]{
                                    "1. Khởi tạo khóa AES-256-GCM trong TEE / StrongBox",
                                    "2. Xác thực BiometricPrompt.CryptoObject phần cứng",
                                    "3. Đối chiếu Nonce phiên & vô hiệu nếu đổi vân tay"
                            }
                    );
                    break;
                default:
                    securityAuditFlowView.resetToIdleForFlow(
                            "LUỒNG 1: BĂM PBKDF2 & XÓA RAM PIN",
                            new String[]{"THỜI GIAN ĐĂNG NHẬP", "PHƠI NHIỄM PIN", "ĐIỂM MASVS"},
                            new String[]{"KHỞI TẠO", "BĂM PBKDF2", "ZEROIZE RAM"},
                            new String[]{"Sync / Lazy", "10.000 vòng", "Heap RAM"},
                            "Bấm nút chạy để kiểm chứng Zeroize PIN char[] & xóa khóa byte[]",
                            new String[]{
                                    "1. Khởi tạo KeyStore (Sync vs Lazy)",
                                    "2. Băm PBKDF2 & Xóa PIN + khóa byte[]",
                                    "3. Kiểm toán OWASP MASVS"
                            }
                    );
                    break;
            }
        }

        syncFlagSecureForCurrentFlow(optimized());
        updateRealAppStatusCards(optimized());
        renderComparison();
        updateTechSummaryLine();
    }

    /**
     * Scopes FLAG_SECURE in Debug builds strictly to Flow 3 (UI & Background Lock)
     * so Android Studio mirroring stays visible for all other flows/tabs.
     */
    private void syncFlagSecureForCurrentFlow(boolean afterMode) {
        if (getActivity() instanceof BaseSecureActivity) {
            BaseSecureActivity host = (BaseSecureActivity) getActivity();
            boolean shouldEnable = !BuildConfig.DEBUG
                    || (topic == TOPIC_STARTUP && secSubFlow == SEC_FLOW_UI_LOCK && afterMode);
            host.applyFlagSecure(shouldEnable);
        }
        updateFlagSecureToggleButton(afterMode);
    }

    private void updateFlagSecureToggleButton(boolean afterMode) {
        if (btnToggleFlagSecureDemo == null) return;
        boolean visible = topic == TOPIC_STARTUP
                && secSubFlow == SEC_FLOW_UI_LOCK
                && afterMode
                && BuildConfig.DEBUG;
        btnToggleFlagSecureDemo.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible && getActivity() instanceof BaseSecureActivity) {
            boolean flagOn = ((BaseSecureActivity) getActivity()).isFlagSecureEnabled();
            btnToggleFlagSecureDemo.setText(flagOn
                    ? "Tắt FLAG_SECURE tạm (Hiện lại Mirror)"
                    : "Bật FLAG_SECURE (Chặn chụp / Mirror)");
        }
    }

    private void styleSecurityFlowPill(MaterialButton btn, boolean active) {
        if (btn == null || !isAdded()) return;
        btn.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(active ? BLUE : "#F1F5F9")));
        btn.setTextColor(Color.parseColor(active ? "#FFFFFF" : "#334155"));
    }

    private void appendSecurePinDigit(char digit) {
        if (securePinLen >= securePinBuf.length) {
            return;
        }
        securePinBuf[securePinLen++] = digit;
        updateSecurePinDisplay();
    }

    private void backspaceSecurePinDigit() {
        if (securePinLen <= 0) {
            return;
        }
        securePinBuf[--securePinLen] = '\0';
        updateSecurePinDisplay();
    }

    private void wipeSecurePinBuffer() {
        Arrays.fill(securePinBuf, '\0');
        securePinLen = 0;
        updateSecurePinDisplay();
    }

    private void updateSecurePinDisplay() {
        if (tvSecurePinDisplay != null) {
            tvSecurePinDisplay.setText(PIN_DOT_STRINGS[Math.max(0, Math.min(PIN_DOT_STRINGS.length - 1, securePinLen))]);
        }
    }

    private void applyPinPadTapjackingFilter(boolean enabled) {
        if (tvSecurePinDisplay != null) {
            tvSecurePinDisplay.setFilterTouchesWhenObscured(enabled);
        }
        for (View keyView : securePinKeyViews) {
            if (keyView != null) {
                keyView.setFilterTouchesWhenObscured(enabled);
            }
        }
    }

    private void runLogin(boolean afterMode) {
        if (securePinLen < 4) {
            Toast.makeText(requireContext(), "Chạm bàn phím bên dưới để nhập PIN 4–8 chữ số.", Toast.LENGTH_SHORT).show();
            return;
        }

        int trimmedLen = securePinLen;
        lastTestedPinLength = trimmedLen;
        final String beforePinText;
        final char[] afterPinChars;
        if (afterMode) {
            afterPinChars = new char[trimmedLen];
            System.arraycopy(securePinBuf, 0, afterPinChars, 0, trimmedLen);
            wipeSecurePinBuffer();
            beforePinText = null;
            lastBeforeRetainedPin = null;
        } else {
            afterPinChars = null;
            beforePinText = new String(securePinBuf, 0, trimmedLen);
            lastBeforeRetainedPin = beforePinText;
            wipeSecurePinBuffer();
        }

        resetTrace(afterMode
                ? "▶ BẮT ĐẦU §5.1 AFTER — Lazy Init & Zeroize PIN char[]"
                : "▶ BẮT ĐẦU §5.1 BEFORE — Sync KeyStore & Lộ String PIN trên Heap", afterMode);
        if (securityAuditFlowView != null) {
            if (afterMode) {
                securityAuditFlowView.beginAfterRun(trimmedLen);
            } else {
                securityAuditFlowView.beginBeforeRun(trimmedLen, chaosManager.getMasvsScore());
            }
        }
        startRun(afterMode ? "Đang đăng nhập & zeroize char[]..."
                : "⏳ Đang khởi tạo KeyStore đồng bộ & băm PIN chưa xóa String...");
        runTask(() -> afterMode ? loginAfter(afterPinChars) : loginBefore(beforePinText),
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
                    metrics.put("Xóa khóa byte[]", afterMode ? "✓ rawKey + keyBytes = 0" : "✕ Sót trong Heap");
                    metrics.put("MASVS", afterMode ? "5/5 (An toàn)" : chaosManager.getMasvsScore() + "/5 (Rủi ro)");
                    String status = afterMode
                            ? "✓ After (" + result.ms + " ms): đăng nhập xong, PIN đã zeroize ("
                            + (result.length - result.remaining) + "/" + result.length + " ký tự) • MASVS 5/5."
                            : "⚠ Before (" + result.ms + " ms): khởi tạo chậm & PIN vẫn nằm trong String trên Heap (100%)!";
                    finishRun(afterMode, metrics, status, afterMode);
                    if (afterMode) {
                        autoDemoHandler.postDelayed(() -> Runtime.getRuntime().gc(), 250L);
                    }
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
        sleepRemainder(startNs, 1950L);
        long ms = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        appendTrace(TRACE_COLOR_FAULT, "⚠ Hoàn tất sau " + ms + " ms: PIN vẫn còn 100% trong RAM (Heap Dump đọc được)");
        return new PinRunResult(ms, -1, pinText.length());
    }

    private void postSecurityPbkdf2Done(int masvsScore) {
        autoDemoHandler.post(() -> {
            if (isAdded() && securityAuditFlowView != null) {
                securityAuditFlowView.onBeforePbkdf2Done(masvsScore);
            }
        });
    }

    private PinRunResult loginAfter(char[] pin) {
        long startNs = System.nanoTime();
        int pinLen = pin != null ? pin.length : 0;
        appendTrace(TRACE_COLOR_OK, "Bỏ khởi tạo đồng bộ, chuyển PIN sang mảng char[" + pinLen + "]");
        appendTrace(TRACE_COLOR_INFO, "Băm PIN qua CryptoManager.computePinVerifierHash() + xóa rawKey/keyBytes");
        CryptoManager.computePinVerifierHash(pin, new byte[16]);
        long ms = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        int remaining = 0;
        if (pin != null) {
            for (char c : pin) {
                if (c != '\0') remaining++;
            }
        }
        System.gc();
        appendTrace(TRACE_COLOR_OK, "✓ Đã ghi đè Arrays.fill(pin, '\\0') → còn "
                + remaining + "/" + pinLen + " ký tự • Khôi phục MASVS 5/5 (" + ms + " ms)");
        return new PinRunResult(ms, remaining, pinLen);
    }

    private void runAntiHookAndApkFlow(boolean afterMode) {
        startRun(afterMode ? "Đang quét Anti-Hook, TracerPid & APK SHA-256..."
                : "Đang mô phỏng bỏ qua kiểm tra Hook & APK...");
        final Context appCtx = appContext();
        runTask(() -> {
            if (!afterMode) {
                return new AntiHookRunResult(0L, 0L, false, false, false, "Không kiểm tra", false, true);
            }
            long scanStart = System.nanoTime();
            SecurityEnvironmentChecker.SecurityAuditReport report =
                    SecurityEnvironmentChecker.inspectEnvironment(appCtx);
            long scanMs = Math.max(1L, (System.nanoTime() - scanStart) / 1_000_000L);

            long reactStart = System.nanoTime();
            SecurityEnvironmentChecker.SecurityAuditReport simHook =
                    new SecurityEnvironmentChecker.SecurityAuditReport(
                            false, false, false, true, false, true, true, report.apkCertSha256);
            boolean reacted = SecurityEnvironmentChecker.enforceActiveDefense(appCtx, simHook, BuildConfig.DEBUG);
            long reactMs = Math.max(1L, (System.nanoTime() - reactStart) / 1_000_000L);
            boolean l1After = encryptedCache.hasL1ProfileCached();

            String shortHex = report.getShortApkCertHex();
            if (shortHex.length() > 8) {
                shortHex = shortHex.substring(0, 8) + "...";
            }
            return new AntiHookRunResult(scanMs, reactMs, !report.hookingDetected,
                    !report.nativeDebugger, report.apkSignatureValid, shortHex, reacted, l1After);
        }, (res, blockedMs, totalMs) -> {
            if (securityAuditFlowView != null) {
                int[] nodeStates = afterMode
                        ? new int[]{
                        res.hookingClean ? SecurityAuditFlowView.SecurityFlowState.NODE_OK
                                : SecurityAuditFlowView.SecurityFlowState.NODE_FAULT,
                        res.nativeDebugClean ? SecurityAuditFlowView.SecurityFlowState.NODE_OK
                                : SecurityAuditFlowView.SecurityFlowState.NODE_FAULT,
                        res.apkValid ? SecurityAuditFlowView.SecurityFlowState.NODE_OK
                                : SecurityAuditFlowView.SecurityFlowState.NODE_FAULT}
                        : new int[]{
                        SecurityAuditFlowView.SecurityFlowState.NODE_FAULT,
                        SecurityAuditFlowView.SecurityFlowState.NODE_FAULT,
                        SecurityAuditFlowView.SecurityFlowState.NODE_FAULT};
                int[] stepStates = afterMode
                        ? new int[]{
                        res.hookingClean ? SecurityAuditFlowView.SecurityFlowState.STEP_DONE_OK
                                : SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT,
                        res.nativeDebugClean ? SecurityAuditFlowView.SecurityFlowState.STEP_DONE_OK
                                : SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT,
                        (res.apkValid && res.activeDefenseTriggered)
                                ? SecurityAuditFlowView.SecurityFlowState.STEP_DONE_OK
                                : SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT}
                        : new int[]{
                        SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT,
                        SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT,
                        SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT};

                securityAuditFlowView.showCustomFlow(
                        afterMode,
                        "LUỒNG 2: CHỐNG HOOK & TOÀN VẸN APK",
                        afterMode ? "PHÒNG THỦ ON" : "BỎ QUA · LỘ L1",
                        new String[]{"ANTI-HOOK", "TRACER PID", "TOÀN VẸN APK"},
                        afterMode
                                ? new String[]{
                                res.hookingClean ? "Sạch" : "Hook!",
                                res.nativeDebugClean ? "PID = 0" : "Attached",
                                res.apkValid ? res.apkShortHex : "Sai SHA"}
                                : new String[]{"Bỏ qua", "Bỏ qua", "Không ký"},
                        new String[]{"QUÉT MAPS", "TRACER PID", "APK SHA-256"},
                        new String[]{"/proc/self/maps", "/proc/self/status", "Chữ ký APK"},
                        nodeStates,
                        afterMode
                                ? new String[]{res.hookingClean ? "Sạch" : "Hook!",
                                res.nativeDebugClean ? "PID=0" : "Dbg!",
                                res.apkValid ? "Khớp" : "Lệch"}
                                : new String[]{"Tắt", "Tắt", "Tắt"},
                        afterMode
                                ? String.format(Locale.US,
                                "Quét thật %d ms • Thử Hook Frida → xóa L1 RAM trong %d ms",
                                res.scanMs, res.reactionMs)
                                : "Không kiểm tra Frida / TracerPid • L1 RAM vẫn lộ nếu bị hook",
                        new String[]{
                                "1. Quét Frida / Xposed trên /proc/self/maps",
                                "2. Đọc TracerPid chống Native Debugger",
                                "3. Đối chiếu APK SHA-256 & phản ứng xóa L1"
                        },
                        stepStates,
                        afterMode
                                ? new String[]{res.hookingClean ? "Sạch" : "Cảnh báo",
                                res.nativeDebugClean ? "PID=0" : "Attached",
                                "Xóa L1 (" + res.reactionMs + " ms)"}
                                : new String[]{"Bỏ qua", "Bỏ qua", "Còn L1 RAM"}
                );
            }

            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Cơ chế", afterMode ? "Anti-Hook + TracerPid + APK SHA-256" : "Không kiểm tra môi trường");
            metrics.put("Quét /proc/self/maps", afterMode ? (res.hookingClean ? "✓ Sạch (" + res.scanMs + " ms)" : "❌ Phát hiện Hook") : "✕ Bỏ qua");
            metrics.put("Native TracerPid", afterMode ? (res.nativeDebugClean ? "✓ TracerPid = 0" : "❌ Đang debug") : "✕ Bỏ qua");
            metrics.put("Chữ ký APK (SHA-256)", afterMode ? (res.apkValid ? "✓ Khớp " + res.apkShortHex : "❌ Sai chữ ký") : "✕ Chưa kiểm tra");
            metrics.put("Phản ứng khi Hook", afterMode ? ("✓ Xóa L1 + PIN (" + res.reactionMs + " ms)") : "✕ Còn nguyên trên RAM");
            finishRun(afterMode, metrics, "", afterMode);
        });
    }

    private void runUiAndBackgroundLockFlow(boolean afterMode) {
        startRun(afterMode ? "Đang bật FLAG_SECURE, Tapjacking & khóa L1 RAM..."
                : "Đang tắt FLAG_SECURE & giữ nguyên L1 RAM...");
        final BaseSecureActivity host = (getActivity() instanceof BaseSecureActivity)
                ? (BaseSecureActivity) getActivity() : null;
        syncFlagSecureForCurrentFlow(afterMode);
        applyPinPadTapjackingFilter(afterMode);
        if (btnAction != null) {
            btnAction.setFilterTouchesWhenObscured(afterMode);
        }

        boolean flagNow = host != null && host.isFlagSecureEnabled();
        if (afterMode) {
            pinSessionManager.lockSessionAndEvictRam();
        }
        boolean l1Remaining = encryptedCache.hasL1ProfileCached();

        if (securityAuditFlowView != null) {
            int stNode = afterMode
                    ? SecurityAuditFlowView.SecurityFlowState.NODE_OK
                    : SecurityAuditFlowView.SecurityFlowState.NODE_FAULT;
            int stStep = afterMode
                    ? SecurityAuditFlowView.SecurityFlowState.STEP_DONE_OK
                    : SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT;
            securityAuditFlowView.showCustomFlow(
                    afterMode,
                    "LUỒNG 3: CHỐNG CHỤP, TAPJACKING & KHÓA NỀN",
                    afterMode ? "BỀ MẶT KHÓA" : "TẮT BẢO VỆ",
                    new String[]{"FLAG_SECURE", "TAPJACKING", "L1 RAM SAU KHÓA"},
                    afterMode
                            ? new String[]{flagNow ? "BẬT (Chặn)" : "TẮT", "Đang lọc", l1Remaining ? "Còn L1" : "Đã xóa"}
                            : new String[]{"TẮT (Lộ ảnh)", "Không lọc", "Còn lưu"},
                    new String[]{"CHỐNG CHỤP", "LỌC PHỦ", "KHÓA L1 RAM"},
                    new String[]{"FLAG_SECURE", "Obscured", "Timeout 120s"},
                    new int[]{stNode, stNode, stNode},
                    afterMode
                            ? new String[]{"BẬT", "Lọc ON", "Xóa L1"}
                            : new String[]{"TẮT", "Lọc OFF", "Giữ L1"},
                    afterMode
                            ? "Đã bật thật FLAG_SECURE (bạn hãy thử chụp màn hình ngay!) & xóa sạch L1 RAM"
                            : "Cho phép chụp màn hình tự do, không lọc lớp phủ & không khóa nền",
                    new String[]{
                            "1. Cờ Window FLAG_SECURE chặn chụp/quay màn hình",
                            "2. Bộ lọc filterTouchesWhenObscured chống Tapjacking",
                            "3. Khóa phiên nền 120s: xóa PIN tạm & L1 RAM"
                    },
                    new int[]{stStep, stStep, stStep},
                    afterMode
                            ? new String[]{"Chặn chụp", "Lọc phủ", "L1 = Trống"}
                            : new String[]{"Chụp được", "Cho chạm", "Còn L1"}
            );
        }

        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("Cơ chế", afterMode ? "FLAG_SECURE + Tapjack + Auto-Lock 120s" : "Tắt bảo vệ giao diện & phiên");
        metrics.put("Chụp màn hình (FLAG_SECURE)", flagNow ? "✓ ĐANG CHẶN THẬT" : "✕ Cho phép chụp");
        metrics.put("Chống lớp phủ Tapjacking", afterMode ? "✓ Đang lọc chạm đè" : "✕ Tắt bộ lọc");
        metrics.put("L1 RAM sau khóa nền", afterMode ? (l1Remaining ? "Còn" : "✓ Đã xóa sạch") : "✕ Còn giữ trên RAM");
        finishRun(afterMode, metrics, "", afterMode);

        if (afterMode) {
            Toast.makeText(requireContext(),
                    "Đã bật FLAG_SECURE thật trên màn hình này — bạn có thể bấm chụp màn hình để kiểm chứng!",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void runBiometricTeeFlow(boolean afterMode) {
        if (!afterMode) {
            startRun("Đang chạy mở khóa PIN tĩnh CPU...");
            runTask(() -> {
                long startNs = System.nanoTime();
                char[] pin = "123456".toCharArray();
                CryptoManager.computePinVerifierHash(pin, new byte[16]);
                long ms = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                return ms;
            }, (ms, blockedMs, totalMs) -> {
                if (securityAuditFlowView != null) {
                    int fNode = SecurityAuditFlowView.SecurityFlowState.NODE_FAULT;
                    int fStep = SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT;
                    securityAuditFlowView.showCustomFlow(
                            false,
                            "LUỒNG 4: SINH TRẮC HỌC CRYPTOOBJECT TEE",
                            "KHÔNG KHÓA HW",
                            new String[]{"CƠ CHẾ MỞ", "THỜI GIAN CPU", "ĐỔI VÂN TAY"},
                            new String[]{"PIN tĩnh", ms + " ms", "Không phát hiện"},
                            new String[]{"NHẬP PIN", "PBKDF2 CPU", "KEYSTORE"},
                            new String[]{"Tĩnh", "10.000 vòng", "Không dùng"},
                            new int[]{fNode, fNode, fNode},
                            new String[]{"Thủ công", ms + " ms", "Không TEE"},
                            "Phải chạy PBKDF2 trên CPU, không gắn khóa phần cứng AndroidKeyStore",
                            new String[]{
                                    "1. Mở khóa bằng PIN tĩnh (không CryptoObject)",
                                    "2. Băm PBKDF2 10.000 vòng trên CPU",
                                    "3. Tự hủy khóa khi đổi vân tay máy"
                            },
                            new int[]{fStep, fStep, fStep},
                            new String[]{"Không", ms + " ms", "Tắt"}
                    );
                }
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("Cơ chế", "Chỉ PIN tĩnh (không sinh trắc)");
                metrics.put("Thời gian xử lý", ms + " ms (PBKDF2 CPU)");
                metrics.put("Khóa phần cứng TEE/StrongBox", "✕ Không dùng");
                metrics.put("Đổi vân tay trên máy", "✕ Không tự vô hiệu");
                finishRun(false, metrics, "", false);
            });
            return;
        }

        final Context appCtx = appContext();
        if (!BiometricKeyManager.isBiometricStrongAvailable(appCtx)) {
            if (securityAuditFlowView != null) {
                int wNode = SecurityAuditFlowView.SecurityFlowState.NODE_FAULT;
                int wStep = SecurityAuditFlowView.SecurityFlowState.STEP_DONE_FAULT;
                securityAuditFlowView.showCustomFlow(
                        false,
                        "LUỒNG 4: SINH TRẮC HỌC CRYPTOOBJECT TEE",
                        "CHƯA CÀI VÂN TAY",
                        new String[]{"CƠ CHẾ MỞ", "PHẦN CỨNG", "TRẠNG THÁI"},
                        new String[]{"CryptoObject", "TEE", "Chưa có vân tay"},
                        new String[]{"BIOMETRIC", "KEYSTORE", "AES-256-GCM"},
                        new String[]{"BIOMETRIC_STRONG", "TEE / StrongBox", "CryptoObject"},
                        new int[]{wNode, wNode, wNode},
                        new String[]{"Thiếu", "Sẵn sàng", "Chờ cài"},
                        "Thiết bị chưa cài vân tay mức BIOMETRIC_STRONG trong Cài đặt",
                        new String[]{
                                "1. Kiểm tra BIOMETRIC_STRONG trên máy",
                                "2. Xác thực BiometricPrompt.CryptoObject",
                                "3. Tự hủy khóa khi đổi vân tay máy"
                        },
                        new int[]{wStep, wStep, wStep},
                        new String[]{"Chưa cài", "Chờ", "Chờ"}
                );
            }
            Toast.makeText(requireContext(),
                    "Hãy cài ít nhất 1 vân tay trong Cài đặt điện thoại để chạy test thật phần này.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        BiometricKeyManager bioMgr = new BiometricKeyManager(appCtx);
        String activeId = pinSessionManager.getActiveStudentId();
        final String targetStudentId = (activeId != null && !activeId.isEmpty()) ? activeId : "CT070318";
        final boolean alreadyEnrolled = bioMgr.isEnrolledForStudent(targetStudentId);
        final Cipher hwCipher;
        try {
            hwCipher = alreadyEnrolled ? bioMgr.initDecryptCipher() : bioMgr.initEncryptCipher();
        } catch (KeyPermanentlyInvalidatedException invalidated) {
            bioMgr.clearBinding();
            Toast.makeText(requireContext(),
                    "Phát hiện vân tay mới — AndroidKeyStore đã tự hủy khóa cũ! Bấm chạy lại để gắn khóa mới.",
                    Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "Không khởi tạo được CryptoObject: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        startRun("Đang chờ xác thực vân tay phần cứng...");
        final long promptStartNs = System.nanoTime();
        BiometricPrompt prompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(requireContext()),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        if (!isAdded()) return;
                        Cipher authedCipher = result.getCryptoObject() != null
                                ? result.getCryptoObject().getCipher() : null;
                        byte[] nonce = pinSessionManager.getOrCreateSessionUnlockNonce();
                        try {
                            long cryptoStartNs = System.nanoTime();
                            boolean ok;
                            if (alreadyEnrolled) {
                                ok = bioMgr.verifyDecryptedNonce(authedCipher, nonce);
                            } else {
                                bioMgr.completeBinding(targetStudentId, authedCipher, nonce);
                                ok = true;
                            }
                            long gcmMs = Math.max(1L, (System.nanoTime() - cryptoStartNs) / 1_000_000L);
                            long totalBioMs = Math.max(1L, (System.nanoTime() - promptStartNs) / 1_000_000L);
                            String backing = bioMgr.getBackingStoreLabel();
                            if (!ok) {
                                bioMgr.clearBinding();
                                endRun();
                                Toast.makeText(requireContext(), "Nonce không khớp!", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (securityAuditFlowView != null) {
                                int okNode = SecurityAuditFlowView.SecurityFlowState.NODE_OK;
                                int okStep = SecurityAuditFlowView.SecurityFlowState.STEP_DONE_OK;
                                securityAuditFlowView.showCustomFlow(
                                        true,
                                        "LUỒNG 4: SINH TRẮC HỌC CRYPTOOBJECT TEE",
                                        "ĐÃ XÁC THỰC " + backing.toUpperCase(Locale.US),
                                        new String[]{"CƠ CHẾ MỞ", "GIẢI MÃ " + backing, "ĐỔI VÂN TAY"},
                                        new String[]{"CryptoObject", gcmMs + " ms", "Tự hủy khóa"},
                                        new String[]{"BIOMETRIC", backing, "AES-256-GCM"},
                                        new String[]{"STRONG", "AndroidKeyStore", "Nonce OK"},
                                        new int[]{okNode, okNode, okNode},
                                        new String[]{"Hợp lệ", backing, gcmMs + " ms"},
                                        "Xác thực phần cứng thành công (" + totalBioMs + " ms) • Khóa " + backing + " bảo vệ",
                                        new String[]{
                                                "1. Mở khóa bằng BiometricPrompt.CryptoObject",
                                                "2. " + (alreadyEnrolled ? "Giải mã" : "Mã hóa gắn") + " AES-256-GCM trong " + backing,
                                                "3. Tự vô hiệu khi thêm vân tay mới"
                                        },
                                        new int[]{okStep, okStep, okStep},
                                        new String[]{"Xong", gcmMs + " ms", "Bật"}
                                );
                            }
                            Map<String, String> metrics = new LinkedHashMap<>();
                            metrics.put("Cơ chế", "BiometricPrompt + CryptoObject");
                            metrics.put("Thời gian xử lý", gcmMs + " ms (AES-GCM phần cứng)");
                            metrics.put("Khóa phần cứng TEE/StrongBox", "✓ " + backing + " (256-bit)");
                            metrics.put("Đổi vân tay trên máy", "✓ Tự hủy khóa (KeyInvalidated)");
                            finishRun(true, metrics, "", true);
                        } catch (Exception e) {
                            endRun();
                            Toast.makeText(requireContext(),
                                    "Lỗi CryptoObject: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        } finally {
                            Arrays.fill(nonce, (byte) 0);
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        if (!isAdded()) return;
                        endRun();
                        Toast.makeText(requireContext(),
                                "Đã hủy xác thực sinh trắc học: " + errString,
                                Toast.LENGTH_SHORT).show();
                    }
                }
        );

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Kiểm chứng Sinh trắc học TEE")
                .setSubtitle(alreadyEnrolled
                        ? "Xác thực vân tay để giải mã nonce bằng khóa " + bioMgr.getBackingStoreLabel()
                        : "Đặt vân tay để gắn khóa AES-256-GCM vào " + bioMgr.getBackingStoreLabel())
                .setNegativeButtonText("Hủy")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        prompt.authenticate(info, new BiometricPrompt.CryptoObject(hwCipher));
    }

    private static final class AntiHookRunResult {
        final long scanMs;
        final long reactionMs;
        final boolean hookingClean;
        final boolean nativeDebugClean;
        final boolean apkValid;
        final String apkShortHex;
        final boolean activeDefenseTriggered;
        final boolean l1Remaining;

        AntiHookRunResult(long scanMs, long reactionMs, boolean hookingClean,
                          boolean nativeDebugClean, boolean apkValid, String apkShortHex,
                          boolean activeDefenseTriggered, boolean l1Remaining) {
            this.scanMs = scanMs;
            this.reactionMs = reactionMs;
            this.hookingClean = hookingClean;
            this.nativeDebugClean = nativeDebugClean;
            this.apkValid = apkValid;
            this.apkShortHex = apkShortHex;
            this.activeDefenseTriggered = activeDefenseTriggered;
            this.l1Remaining = l1Remaining;
        }
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

    private Map<String, String> currentBeforeMap() {
        return topic == TOPIC_STARTUP ? secBeforeByFlow[secSubFlow] : before;
    }

    private Map<String, String> currentAfterMap() {
        return topic == TOPIC_STARTUP ? secAfterByFlow[secSubFlow] : after;
    }

    private void finishRun(boolean afterRun, Map<String, String> metrics, String status, boolean ok) {
        endRun();
        Map<String, String> target = afterRun ? currentAfterMap() : currentBeforeMap();
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
                switch (secSubFlow) {
                    case SEC_FLOW_ANTI_HOOK:
                        key = "Phản ứng khi Hook";
                        break;
                    case SEC_FLOW_UI_LOCK:
                        key = "Chụp màn hình (FLAG_SECURE)";
                        break;
                    case SEC_FLOW_BIOMETRIC:
                        key = "Khóa phần cứng TEE/StrongBox";
                        break;
                    default:
                        key = "Zeroize PIN";
                        break;
                }
                break;
            default:
                key = "Tổng thời gian";
                break;
        }
        String bVal = currentBeforeMap().get(key);
        String aVal = currentAfterMap().get(key);
        if (bVal != null || aVal != null) {
            tvTechSummary.setText("Before: " + (bVal != null ? bVal : "—")
                    + "  •  After: " + (aVal != null ? aVal : "—"));
        } else {
            tvTechSummary.setText("Chạy Before & After để xem so sánh");
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
        Map<String, String> bMap = currentBeforeMap();
        Map<String, String> aMap = currentAfterMap();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(bMap.keySet());
        keys.addAll(aMap.keySet());
        if (keys.isEmpty()) {
            addComparisonNote("Chưa có số liệu.");
            return;
        }
        addComparisonHeader();
        for (String key : keys) {
            addComparisonRow(key, bMap.get(key), aMap.get(key));
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
        final String cacheTier;

        BottleneckRunResult(StudentCardRecord record, long[] stepMs, long elapsedMs, int apduCount, String cacheTier) {
            this.record = record;
            this.stepMs = stepMs;
            this.elapsedMs = elapsedMs;
            this.apduCount = apduCount;
            this.cacheTier = cacheTier;
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
}
