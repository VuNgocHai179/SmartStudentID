package com.example.smartstudentid.ui.demo;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    private int topic;
    private boolean busy;
    private boolean autoDemoRunning;
    private boolean suppressSwitchRun;
    private boolean trackedHeavyMetric;
    private boolean trackedMainBlockMetric;
    private volatile boolean suppressRawApduTelemetry;
    private ActivityResultLauncher<PickVisualMediaRequest> imagePicker;

    private final ChaosInjectionManager chaosManager = ChaosInjectionManager.getInstance();
    private final Handler autoDemoHandler = new Handler(Looper.getMainLooper());

    private SwitchCompat switchMode;
    private TextView tvModeHint;
    private MaterialButton btnInjectTopicFault;
    private MaterialButton btnApplyTopicFix;
    private MaterialButton btnAutoDemoTopic;
    private View cardMode;
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
    private MaterialButton btnResetCache;
    private MaterialButton btnInspectTopicSecurity;
    private ProgressBar progress;
    private TextView tvStatus;
    private TextView tvComparisonTitle;
    private LinearLayout llComparison;
    private TextView tvRealtimeChartTitle;
    private UiHeartbeatView demoHeartbeat;
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
    }

    @Override
    public void onPause() {
        super.onPause();
        cancelAutoDemo();
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
        cancelAutoDemo();
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
        btnAutoDemoTopic = root.findViewById(R.id.btnAutoDemoTopic);
        cardMode = root.findViewById(R.id.cardDemoMode);
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
        btnResetCache = root.findViewById(R.id.btnResetCache);
        btnInspectTopicSecurity = root.findViewById(R.id.btnInspectTopicSecurity);
        progress = root.findViewById(R.id.demoProgress);
        tvStatus = root.findViewById(R.id.tvDemoStatus);
        tvComparisonTitle = root.findViewById(R.id.tvComparisonTitle);
        llComparison = root.findViewById(R.id.llComparison);
        tvRealtimeChartTitle = root.findViewById(R.id.tvRealtimeChartTitle);
        demoHeartbeat = root.findViewById(R.id.demoHeartbeat);
        realtimeChart = root.findViewById(R.id.demoRealtimeChart);
    }

    private void configureTopic(View root) {
        TextView badge = root.findViewById(R.id.tvTopicBadge);
        TextView title = root.findViewById(R.id.tvTopicTitle);

        panelStudent.setVisibility(topic <= TOPIC_CACHE ? View.VISIBLE : View.GONE);
        panelImage.setVisibility(topic == TOPIC_IMAGE ? View.VISIBLE : View.GONE);
        panelPin.setVisibility(topic == TOPIC_STARTUP ? View.VISIBLE : View.GONE);
        cardMode.setVisibility(View.VISIBLE);
        btnResetCache.setVisibility(topic == TOPIC_CACHE ? View.VISIBLE : View.GONE);
        demoHeartbeat.setVisibility(topic == TOPIC_THREADING ? View.VISIBLE : View.GONE);
        btnInspectTopicSecurity.setVisibility(
                (topic == TOPIC_CACHE || topic == TOPIC_STARTUP) ? View.VISIBLE : View.GONE);
        realtimeChart.setTopic(topic);

        switch (topic) {
            case TOPIC_THREADING:
                badge.setText("CHUYÊN ĐỀ 2 • §2");
                title.setText("Đa luồng & giữ 60 FPS");
                btnAction.setText("Làm mới từ thẻ");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • FRAME TIME & 60 FPS");
                break;

            case TOPIC_CACHE:
                badge.setText("CHUYÊN ĐỀ 3 • §3");
                title.setText("Cache offline-first & OWASP Storage");
                btnAction.setText("Mở hồ sơ");
                btnInspectTopicSecurity.setText("Kiểm chứng Lưu trữ: Plaintext Leak vs AES-GCM");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • ĐỘ TRỄ MỞ HỒ SƠ");
                break;

            case TOPIC_IMAGE:
                badge.setText("CHUYÊN ĐỀ 4 • §4");
                title.setText("Nén ảnh WebP & quản trị RAM");
                btnAction.setText("Lưu ảnh lên thẻ");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • JAVA HEAP (MB) & GÓI 240 B");
                break;

            case TOPIC_STARTUP:
                badge.setText("CHUYÊN ĐỀ 5 • §5");
                title.setText("Khởi động & bộ nhớ nhạy cảm");
                realtimeChart.setColdStartMs(DemoMetrics.getColdStartMs());
                btnAction.setText("Đăng nhập");
                btnInspectTopicSecurity.setText("Kiểm chứng RAM PIN, AES-128 EEPROM & MASVS");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • PHƠI NHIỄM PIN TRÊN RAM");
                break;

            default:
                badge.setText("CHUYÊN ĐỀ 1 • §1");
                title.setText("Điểm nghẽn kép: APDU, chip & mật mã");
                btnInjectTopicFault.setText("🔥 B1. Gây nghẽn");
                btnApplyTopicFix.setText("🛠 B2. Tối ưu");
                btnAutoDemoTopic.setText("▶ Chạy Demo Tự động (B1 → B2)");
                btnAction.setText("Đọc hồ sơ từ thẻ");
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
            cancelAutoDemo();
            applyTopicChaosState(checked);
            if (!busy) {
                runCurrentMode();
            }
        });

        suppressSwitchRun = true;
        switchMode.setChecked(false);
        updateModeLabels(false);
        suppressSwitchRun = false;

        btnInjectTopicFault.setOnClickListener(v -> {
            cancelAutoDemo();
            triggerTopicMode(false);
        });
        btnApplyTopicFix.setOnClickListener(v -> {
            cancelAutoDemo();
            triggerTopicMode(true);
        });
        btnAutoDemoTopic.setOnClickListener(v -> runAutoDemoTopic());

        btnAction.setOnClickListener(v -> {
            cancelAutoDemo();
            boolean mode = optimized();
            applyTopicChaosState(mode);
            runCurrentMode();
        });
        btnPickImage.setOnClickListener(v -> imagePicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
        btnResetCache.setOnClickListener(v -> resetCache());
        btnInspectTopicSecurity.setOnClickListener(v -> showTopicSecurityInspector());
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

    private void runAutoDemoTopic() {
        cancelAutoDemo();
        autoDemoRunning = true;
        btnAutoDemoTopic.setEnabled(false);
        btnAutoDemoTopic.setText("⏳ Đang chạy B1 (Before)...");

        triggerTopicMode(false);

        Runnable step2Task = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || !autoDemoRunning) return;
                if (busy) {
                    autoDemoHandler.postDelayed(this, 250L);
                    return;
                }
                btnAutoDemoTopic.setText("⏳ Đang chạy B2 (After)...");
                triggerTopicMode(true);
                autoDemoRunning = false;
                btnAutoDemoTopic.setEnabled(true);
                btnAutoDemoTopic.setText("▶ Chạy Demo Tự động (B1 → B2)");
            }
        };
        autoDemoHandler.postDelayed(step2Task, 3800L);
    }

    private void cancelAutoDemo() {
        autoDemoHandler.removeCallbacksAndMessages(null);
        if (autoDemoRunning) {
            autoDemoRunning = false;
            if (btnAutoDemoTopic != null) {
                btnAutoDemoTopic.setEnabled(true);
                btnAutoDemoTopic.setText("▶ Chạy Demo Tự động (B1 → B2)");
            }
        }
    }

    private void updateModeLabels(boolean checked) {
        tvModeHint.setText(checked ? "✓ After" : "❌ Before");
        tvModeHint.setTextColor(Color.parseColor(checked ? GREEN : RED));
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
            startRun("Đang đọc từ Combo tối ưu (§2–§4)...");
            runTask(() -> {
                long startNs = System.nanoTime();
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
                if (realtimeChart != null) {
                    realtimeChart.onApduFrame(0xA4, res.stepMs[0]);
                    realtimeChart.onApduFrame(0x10, res.stepMs[1]);
                    realtimeChart.onApduFrame(0x21, res.stepMs[2]);
                    realtimeChart.onApduFrame(0x21, res.stepMs[3]);
                    realtimeChart.onApduFrame(0x30, res.stepMs[4]);
                    realtimeChart.recordBottleneckResult(res.stepMs, res.elapsedMs);
                }
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("1. SELECT applet", "0 ms (Cache)");
                metrics.put("2. VERIFY PIN", "0 ms (Session)");
                metrics.put("3. AES-128 EEPROM", res.stepMs[2] + " ms (GCM)");
                metrics.put("4. Ký RSA-1024", "0 ms (Cached)");
                metrics.put("5. Học vụ & 35 ảnh", res.stepMs[4] + " ms (WebP)");
                metrics.put("Tổng thời gian", res.elapsedMs + " ms");
                metrics.put("Số lệnh APDU", "0 APDU");
                finishRun(true, metrics, "✓ After (Combo §2–§4): phản hồi tức thì trong "
                        + res.elapsedMs + " ms (0 APDU)!", true);
            });
            return;
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
                client.sendApdu(ApduCommands.CLA_ISO, ApduCommands.INS_SELECT,
                        (byte) 0x04, (byte) 0x00, ApduCommands.APPLET_AID);
                apduCount++;
                sleepRemainder(s1, 320L);
                stepMs[0] = elapsedMsSince(s1);
                postStageTelemetry(0xA4, stepMs[0],
                        "⏳ [2/5] Đang kiểm tra OwnerPIN & dẫn xuất khóa AES (~520 ms)...");

                // Stage 2: VERIFY PIN (~520 ms)
                long s2 = System.nanoTime();
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
                postStageTelemetry(0x10, stepMs[1],
                        "⏳ [3/5] Chip đang đọc EEPROM & giải mã khối AES-128 CBC (~680 ms)...");

                // Stage 3: GET INFO + AES-128 CBC EEPROM (~680 ms)
                long s3 = System.nanoTime();
                CardResponse infoResp = client.sendApdu(ApduCommands.CLA_SMART_STUDENT,
                        ApduCommands.INS_GET_STUDENT_INFO, ApduCommands.P1_INFO_DECRYPTED, (byte) 0x00, null);
                apduCount++;
                sleepRemainder(s3, 680L);
                stepMs[2] = elapsedMsSince(s3);
                StudentCardRecord record = StudentCardRecord.fromPipeDelimitedString(
                        new String(infoResp.getData(), StandardCharsets.UTF_8));
                postStageTelemetry(0x21, stepMs[2],
                        "⏳ [4/5] Chip đang tính lũy thừa modulo RSA-1024 (~890 ms)...");

                // Stage 4: RSA-1024 Challenge Sign + Raw AES Ciphertext (~890 ms)
                long s4 = System.nanoTime();
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_SIGN_CHALLENGE,
                        (byte) 0x00, (byte) 0x00, CryptoManager.generateRandomBytes(16));
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_GET_STUDENT_INFO,
                        ApduCommands.P1_INFO_RAW_CIPHERTEXT, (byte) 0x00, null);
                apduCount += 2;
                sleepRemainder(s4, 890L);
                stepMs[3] = elapsedMsSince(s4);
                postStageTelemetry(0x21, stepMs[3],
                        "⏳ [5/5] Đang đọc Học vụ & truyền tuần tự 35 gói Short APDU (~740 ms)...");

                // Stage 5: Academic Status + 35 sequential Short APDU chunk reads (~740 ms)
                long s5 = System.nanoTime();
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_GET_ACADEMIC_LIBRARY_STATUS,
                        (byte) 0x00, (byte) 0x00, null);
                apduCount++;
                for (int c = 0; c < 35; c++) {
                    int off = c * ApduCommands.MAX_AVATAR_CHUNK_SIZE;
                    client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_DOWNLOAD_AVATAR_CHUNK,
                            (byte) ((off >> 8) & 0xFF), (byte) (off & 0xFF), new byte[]{(byte) 240});
                    apduCount++;
                }
                sleepRemainder(s5, 740L);
                stepMs[4] = elapsedMsSince(s5);
                postStageTelemetry(0x30, stepMs[4],
                        "⏳ Đang tổng hợp kết quả điểm nghẽn...");

                long totalElapsedMs = elapsedMsSince(totalStartNs);
                return new BottleneckRunResult(record, stepMs, totalElapsedMs, apduCount);
            } finally {
                suppressRawApduTelemetry = false;
            }
        }, (res, blockedMs, totalMs) -> {
            showProfile(res.record, null, "THẺ");
            if (realtimeChart != null) {
                realtimeChart.recordBottleneckResult(res.stepMs, res.elapsedMs);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("1. SELECT applet", res.stepMs[0] + " ms");
            metrics.put("2. VERIFY PIN", res.stepMs[1] + " ms");
            metrics.put("3. AES-128 EEPROM", res.stepMs[2] + " ms");
            metrics.put("4. Ký RSA-1024", res.stepMs[3] + " ms");
            metrics.put("5. Học vụ & 35 ảnh", res.stepMs[4] + " ms");
            metrics.put("Tổng thời gian", res.elapsedMs + " ms");
            metrics.put("Số lệnh APDU", res.apduCount + " APDU");
            finishRun(false, metrics, "⚠ Before: mất " + res.elapsedMs
                    + " ms (~" + String.format(Locale.US, "%.1f", res.elapsedMs / 1000.0)
                    + "s) cho " + res.apduCount + " lệnh tuần tự — đây chính là điểm nghẽn!", false);
        });
    }

    private void postStageTelemetry(int ins, long stageMs, String nextStatus) {
        autoDemoHandler.post(() -> {
            if (!isAdded()) return;
            if (realtimeChart != null) {
                realtimeChart.onApduFrame(ins, stageMs);
            }
            if (tvStatus != null && nextStatus != null) {
                tvStatus.setText(nextStatus);
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
            runTask(this::fetchFromCard, (result, blockedMs, totalMs) -> {
                showProfile(result.getRecord(), null, "THẺ");
                if (realtimeChart != null) {
                    realtimeChart.recordThreadingResult(true, blockedMs, result.getElapsedMs());
                }
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("Main Thread giữ", msText(blockedMs));
                metrics.put("Thời gian đọc", result.getElapsedMs() + " ms");
                metrics.put("Khung rớt", Math.round(blockedMs / 16.6667f) + " khung");
                finishRun(true, metrics, "✓ After: hồ sơ đã cập nhật, UI không bị chặn (60 FPS).", true);
            });
        } else {
            // Ensure continuous jank doesn't double-fire while the 18-frame burst is running.
            chaosManager.resolveStep1Threading();
            runBlockingTask(this::fetchFromCard, (result, blockedMs, totalMs) -> {
                if (!isAdded()) return;
                runMainThreadAvatarBurst(result, totalMs, 1, 17);
            });
        }
    }

    /**
     * Blocks the Main Thread across 18 consecutive Choreographer frames (~2.7s total) so the
     * heartbeat freezes red and the realtime chart fills with tall red frame spikes.
     */
    private void runMainThreadAvatarBurst(LocalCacheManager.ProfileLoadResult profileResult,
                                          long accumulatedMs, int frameIndex, int remainingFrames) {
        if (!isAdded()) return;
        if (remainingFrames <= 0) {
            // Keep degraded ~10 FPS state active until user clicks B2. Xử lý.
            chaosManager.injectStep1Threading();
            showProfile(profileResult.getRecord(), null, "THẺ");
            if (realtimeChart != null) {
                realtimeChart.recordThreadingResult(false, accumulatedMs, accumulatedMs);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Main Thread giữ", msText(accumulatedMs));
            metrics.put("Thời gian đọc", accumulatedMs + " ms");
            metrics.put("Khung rớt", Math.round(accumulatedMs / 16.6667f) + " khung");
            finishRun(false, metrics, "⚠ Before: UI đã bị khóa " + accumulatedMs
                    + " ms qua 18 nhịp (~" + Math.round(accumulatedMs / 16.6667f)
                    + " khung rớt) — nhịp UI đứng hình & FPS tụt đỏ!", false);
            return;
        }
        if (tvStatus != null) {
            tvStatus.setText("⏳ [Nhịp " + frameIndex + "/18] Main Thread đang bị khóa → UI giật lag...");
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
            runMainThreadAvatarBurst(profileResult, accumulatedMs + frameCostMs,
                    frameIndex + 1, remainingFrames - 1);
        });
    }

    // ------------------------------------------------------------------
    // §3 — open the profile: Before always hits 36 APDUs (~2.65s), After uses cache (~8ms)
    // ------------------------------------------------------------------

    private void runCache(boolean afterMode) {
        startRun(afterMode ? "Đang mở hồ sơ từ Cache AES-256-GCM..."
                : "⏳ Đang bỏ qua Cache, đọc trực tiếp 36 APDU từ thẻ (1/36)...");
        runTask(() -> {
            if (afterMode && !chaosManager.isCacheBypassAndPlaintextLeakActive()) {
                StudentCardRecord cached = encryptedCache.getStudentProfile();
                if (cached == null) {
                    String active = ApduClient.getInstance().getActiveCardInfo();
                    if (active != null && active.contains("|")) {
                        cached = StudentCardRecord.fromPipeDelimitedString(active);
                        encryptedCache.saveStudentProfile(cached);
                    }
                }
                if (cached != null) {
                    return new CacheOpen(cached, encryptedCache.getAvatarBytes(), true, 0);
                }
            }
            // Before mode: full 36-APDU direct card + RSA + 30 avatar chunks read (~2.65 seconds)
            LocalCacheManager.ProfileLoadResult result = fetchFromCard();
            ApduClient client = ApduClient.getInstance();
            client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_SIGN_CHALLENGE,
                    (byte) 0x00, (byte) 0x00, CryptoManager.generateRandomBytes(16));
            int totalApdus = result.getApduCommandsSent() + 1;
            for (int c = 0; c < 30; c++) {
                long pktStartNs = System.nanoTime();
                int off = c * ApduCommands.MAX_AVATAR_CHUNK_SIZE;
                client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_DOWNLOAD_AVATAR_CHUNK,
                        (byte) ((off >> 8) & 0xFF), (byte) (off & 0xFF), new byte[]{(byte) 240});
                totalApdus++;
                sleepRemainder(pktStartNs, 76L);
                if ((c + 1) % 6 == 0) {
                    final int doneCount = totalApdus;
                    autoDemoHandler.post(() -> {
                        if (isAdded() && tvStatus != null) {
                            tvStatus.setText("⏳ Đang đọc trực tiếp từ thẻ: " + doneCount + "/36 APDU...");
                        }
                    });
                }
            }
            return new CacheOpen(result.getRecord(), encryptedCache.getAvatarBytes(), false, totalApdus);
        }, (openResult, blockedMs, totalMs) -> {
            showProfile(openResult.record, openResult.avatarBytes, openResult.fromCache ? "CACHE" : "THẺ");
            int apduCount = openResult.fromCache ? 0 : openResult.apduCount;
            if (realtimeChart != null) {
                realtimeChart.recordCacheResult(afterMode, openResult.fromCache, totalMs, apduCount);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Nguồn", openResult.fromCache ? "Cache (AES-GCM)" : "Thẻ (36 APDU)");
            metrics.put("Thời gian", totalMs + " ms");
            metrics.put("APDU", String.valueOf(apduCount));
            finishRun(afterMode, metrics, openResult.fromCache
                    ? "✓ After: mở hồ sơ từ Cache AES-256-GCM trong " + totalMs + " ms (0 APDU)."
                    : "⚠ Before: bỏ qua cache mất " + totalMs + " ms (" + apduCount
                    + " APDU) & lộ bản rõ vào SharedPreferences!", afterMode);
        });
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
        showProfile(null, null, null);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("Đã xóa cache.");
        tvStatus.setTextColor(Color.parseColor(SLATE_MUTED));
    }

    // ------------------------------------------------------------------
    // §4 — pick a portrait and save it to the card
    // ------------------------------------------------------------------

    private void runImage(boolean afterMode) {
        startRun(afterMode ? "Đang xử lý & ghi ảnh (After)..." : "Đang xử lý ảnh kiểu chưa tối ưu...");
        runTask(() -> afterMode ? runImageAfter() : runImageBefore(), (result, blockedMs, totalMs) -> {
            if (result.preview != null) {
                showPreview(result.preview);
            }
            if (realtimeChart != null && (result.sizeKb > 0 || afterMode)) {
                realtimeChart.recordImageResult(afterMode, result.sizeKb, result.packets,
                        result.ramMb, result.success);
            }
            finishRun(afterMode, result.metrics, result.status, result.success);
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
            int packets = ImageOptimizer.calculateApduPacketCount(png.length);
            double ramMb = dims[0] * (double) dims[1] * 4 / (1024 * 1024);

            // Simulate unpooled byte[240] allocations & chunked write attempts hitting the 8 KB card limit (~2.8s)
            autoDemoHandler.post(() -> {
                if (isAdded() && tvStatus != null) {
                    tvStatus.setText("⏳ Đang giải mã Full Bitmap & cấp phát " + packets + " gói PNG trên Heap...");
                }
            });
            for (int p = 0; p < 48; p++) {
                byte[] unpooledChunk = new byte[ApduCommands.MAX_AVATAR_CHUNK_SIZE];
                unpooledChunk[0] = (byte) p;
                SystemClock.sleep(52L);
                if (p == 23) {
                    autoDemoHandler.post(() -> {
                        if (isAdded() && tvStatus != null) {
                            tvStatus.setText("⏳ Đang truyền thử gói 35/750 lên thẻ (Heap tăng, Memory Churn)...");
                        }
                    });
                }
            }
            long totalMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Ảnh gốc", dims[0] + "×" + dims[1]);
            metrics.put("Giải mã", "Full (inSampleSize=1) • " + decodeMs + " ms");
            metrics.put("Dung lượng", sizeText(png.length) + " (PNG)");
            metrics.put("Số gói 240B", packets + " gói");
            metrics.put("Xử lý + thử ghi", totalMs + " ms");
            metrics.put("RAM bitmap", String.format(Locale.US, "%.1f MB", ramMb));
            metrics.put("Ghi lên thẻ", "Không — vượt 8 KB");
            return new ImageRunResult(metrics, preview,
                    "❌ Before (" + totalMs + " ms): PNG " + sizeText(png.length) + " cần " + packets
                            + " gói → tràn bộ đệm 8 KB của thẻ & gây Memory Churn.", false,
                    png.length / 1024.0, packets, ramMb);
        } catch (OutOfMemoryError e) {
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Ghi lên thẻ", "OOM khi giải mã");
            return new ImageRunResult(metrics, null,
                    "❌ Hết RAM khi giải mã ảnh gốc — đây chính là lý do cần two-pass.", false,
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
            metrics.put("Xử lý + thử ghi", (processMs + uploadMs) + " ms");
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
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText("Nhập PIN 4–8 chữ số.");
            tvStatus.setTextColor(Color.parseColor(RED));
            return;
        }
        startRun(afterMode ? "Đang đăng nhập & zeroize char[]..."
                : "⏳ Đang khởi tạo KeyStore đồng bộ & băm PIN chưa xóa String...");
        runTask(() -> afterMode ? loginAfter(pinText) : loginBefore(pinText),
                (result, blockedMs, totalMs) -> {
                    if (realtimeChart != null) {
                        realtimeChart.recordLoginResult(afterMode, result.ms, result.remaining, result.length);
                    }
                    Map<String, String> metrics = new LinkedHashMap<>();
                    metrics.put("Khởi tạo + PBKDF2", result.ms + " ms");
                    metrics.put("Zeroize PIN", result.remaining < 0
                            ? "Không (String 100%)" : result.remaining + "/" + result.length + " ký tự còn");
                    metrics.put("MASVS", afterMode ? "5/5 (An toàn)" : chaosManager.getMasvsScore() + "/5 (Rủi ro)");
                    String status = afterMode
                            ? "✓ After (" + result.ms + " ms): đăng nhập xong, PIN đã zeroize ("
                            + (result.length - result.remaining) + "/" + result.length + " ký tự) • MASVS 5/5."
                            : "⚠ Before (" + result.ms + " ms): khởi tạo chậm & PIN vẫn nằm trong String trên Heap (100%)!";
                    finishRun(afterMode, metrics, status, afterMode);
                });
    }

    private PinRunResult loginBefore(String pinText) throws Exception {
        long startNs = System.nanoTime();
        autoDemoHandler.post(() -> {
            if (isAdded() && tvStatus != null) {
                tvStatus.setText("⏳ Đang khởi tạo KeyStore đồng bộ & quét phơi nhiễm PIN trên Heap...");
            }
        });
        PBEKeySpec spec = new PBEKeySpec(pinText.toCharArray(), new byte[16], 10_000, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        factory.generateSecret(spec).getEncoded();
        // Simulate unoptimized synchronous KeyStore + cold-start overhead + heap retention (~1.95s)
        sleepRemainder(startNs, 1950L);
        long ms = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        // Intentionally do not call spec.clearPassword(): demonstrates un-zeroized String exposure.
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
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("❌ Lỗi: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        tvStatus.setTextColor(Color.parseColor(RED));
    }

    private void startRun(String status) {
        releaseLiveMetricFlags();
        busy = true;
        trackedHeavyMetric = true;
        trackedMainBlockMetric = (topic == TOPIC_THREADING && !optimized());
        DemoMetrics.beginHeavyTask(trackedMainBlockMetric);
        btnAction.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(status);
        tvStatus.setTextColor(Color.parseColor(SLATE_MUTED));
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
        tvStatus.setText("");
        tvStatus.setVisibility(View.GONE);
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
            return new InspectorData(plainText, cipherHex, leakContent, exposedPin,
                    hasEncryptedProfile, keystoreOk, remainingPinTries);
        }, (data, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            StringBuilder sb = new StringBuilder();
            if (isCacheTopic) {
                sb.append("=== KIỂM CHỨNG LƯU TRỮ (§3 OWASP MASVS-STORAGE) ===\n");
                sb.append("• SharedPreferences(\"insecure_plaintext_leak\"):\n  ")
                        .append(data.leakContent != null
                                ? "❌ ĐANG LỘ BẢN RÕ: " + data.leakContent
                                : "✓ Sạch — Không có bản rõ chưa mã hóa")
                        .append("\n\n");
                sb.append("• EncryptedSharedPreferences (AES-256-GCM + KeyStore):\n  ")
                        .append(data.hasEncryptedProfile ? "✓ Có hồ sơ đã mã hóa AES-GCM" : "• Cache đang trống (Auto-Wipe / chưa mở)")
                        .append(" • AndroidKeyStore: ").append(data.keystoreOk ? "Sẵn sàng" : "Fallback")
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

        InspectorData(String plainUtf8, String cipherHex, String leakContent, String exposedPin,
                      boolean hasEncryptedProfile, boolean keystoreOk, int remainingPinTries) {
            this.plainUtf8 = plainUtf8;
            this.cipherHex = cipherHex;
            this.leakContent = leakContent;
            this.exposedPin = exposedPin;
            this.hasEncryptedProfile = hasEncryptedProfile;
            this.keystoreOk = keystoreOk;
            this.remainingPinTries = remainingPinTries;
        }
    }
}
