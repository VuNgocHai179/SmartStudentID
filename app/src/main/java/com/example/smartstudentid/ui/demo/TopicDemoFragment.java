package com.example.smartstudentid.ui.demo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.optimization.DemoMetrics;
import com.example.smartstudentid.optimization.ImageOptimizer;
import com.example.smartstudentid.optimization.LocalCacheManager;
import com.example.smartstudentid.security.CryptoManager;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.example.smartstudentid.ui.widget.RealtimeTopicChartView;
import com.google.android.material.button.MaterialButton;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    private ActivityResultLauncher<PickVisualMediaRequest> imagePicker;

    private SwitchCompat switchMode;
    private TextView tvModeHint;
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
    private ProgressBar progress;
    private TextView tvStatus;
    private TextView tvComparisonTitle;
    private LinearLayout llComparison;
    private TextView tvRealtimeChartTitle;
    private TextView tvRealtimeChartSubtitle;
    private RealtimeTopicChartView realtimeChart;

    private final ApduClient.TelemetryListener apduTelemetryListener = (ins, elapsedMs) -> {
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
    private byte[] sampleBytes;

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
    }

    @Override
    public void onPause() {
        super.onPause();
        ApduClient.getInstance().clearTelemetryListener(apduTelemetryListener);
    }

    @Override
    public void onDestroyView() {
        ApduClient.getInstance().clearTelemetryListener(apduTelemetryListener);
        realtimeChart = null;
        super.onDestroyView();
    }

    private void bind(View root) {
        switchMode = root.findViewById(R.id.switchDemoMode);
        tvModeHint = root.findViewById(R.id.tvModeHint);
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
        progress = root.findViewById(R.id.demoProgress);
        tvStatus = root.findViewById(R.id.tvDemoStatus);
        tvComparisonTitle = root.findViewById(R.id.tvComparisonTitle);
        llComparison = root.findViewById(R.id.llComparison);
        tvRealtimeChartTitle = root.findViewById(R.id.tvRealtimeChartTitle);
        tvRealtimeChartSubtitle = root.findViewById(R.id.tvRealtimeChartSubtitle);
        realtimeChart = root.findViewById(R.id.demoRealtimeChart);
    }

    private void configureTopic(View root) {
        TextView badge = root.findViewById(R.id.tvTopicBadge);
        TextView title = root.findViewById(R.id.tvTopicTitle);
        TextView goal = root.findViewById(R.id.tvTopicGoal);
        TextView hint = root.findViewById(R.id.tvTopicHint);

        panelStudent.setVisibility(topic <= TOPIC_CACHE ? View.VISIBLE : View.GONE);
        panelImage.setVisibility(topic == TOPIC_IMAGE ? View.VISIBLE : View.GONE);
        panelPin.setVisibility(topic == TOPIC_STARTUP ? View.VISIBLE : View.GONE);
        cardMode.setVisibility(topic == TOPIC_BOTTLENECK ? View.GONE : View.VISIBLE);
        btnResetCache.setVisibility(topic == TOPIC_CACHE ? View.VISIBLE : View.GONE);
        realtimeChart.setTopic(topic);

        switch (topic) {
            case TOPIC_THREADING:
                badge.setText("CHUYÊN ĐỀ 2 • §2");
                title.setText("Đa luồng & giữ 60 FPS");
                goal.setText("Bấm “Làm mới từ thẻ” ở hai chế độ: Before giao diện đứng hình, After vẫn mượt.");
                hint.setText("Nói: chạy nền chỉ giải phóng Main Thread, không làm 210 ms ngắn lại. "
                        + "Kênh thẻ bị synchronized nên 4 thread không tăng throughput APDU.");
                btnAction.setText("Làm mới từ thẻ");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • FRAME TIME & 60 FPS");
                tvRealtimeChartSubtitle.setText("Đường nét đứt 16.7 ms = 60 FPS; vọt đỏ khi Main Thread bị khóa.");
                break;

            case TOPIC_CACHE:
                badge.setText("CHUYÊN ĐỀ 3 • §3");
                title.setText("Cache offline-first & OWASP");
                goal.setText("Mở hồ sơ lần đầu rồi mở lại: Before luôn đọc thẻ, After mở từ cache mã hóa (0 APDU).");
                hint.setText("Nói: cache chỉ dùng cho hồ sơ/ảnh; ví/điểm danh/mượn sách vẫn đọc thẻ. "
                        + "Auto-Wipe sau 3 lần sai PIN nằm ở màn PIN.");
                btnAction.setText("Mở hồ sơ");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • ĐỘ TRỄ MỞ HỒ SƠ");
                tvRealtimeChartSubtitle.setText("Đỏ = đọc thẻ (5 APDU) • Xanh = đọc cache AES-GCM (0 APDU).");
                break;

            case TOPIC_IMAGE:
                badge.setText("CHUYÊN ĐỀ 4 • §4");
                title.setText("Nén ảnh WebP & quản trị RAM");
                goal.setText("Chọn ảnh rồi bấm lưu: Before PNG quá lớn không ghi được, After WebP ghi thẻ thành công.");
                hint.setText("Nói: ảnh 4000×3000 decode thẳng ~48 MB nên phải two-pass. "
                        + "Ảnh thẻ cuối ~3–5 KB ≈ 15 gói thay vì ~750 gói. "
                        + "Thời gian ghi thẻ phụ thuộc bridge/thiết bị, không phải chi phí xử lý ảnh.");
                btnAction.setText("Lưu ảnh lên thẻ");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • JAVA HEAP (MB) & GÓI 240 B");
                tvRealtimeChartSubtitle.setText("Theo dõi biến động Heap khi giải mã/nén ảnh so với ngưỡng thẻ 8 KB.");
                break;

            case TOPIC_STARTUP:
                badge.setText("CHUYÊN ĐỀ 5 • §5");
                title.setText("Khởi động & bộ nhớ nhạy cảm");
                goal.setText("Nhập PIN và đăng nhập: Before PIN còn trong String, After PIN bị xóa khỏi RAM.");
                long coldStart = DemoMetrics.getColdStartMs();
                realtimeChart.setColdStartMs(coldStart);
                hint.setText((coldStart > 0
                        ? "Cold start đo lúc mở app: " + coldStart + " ms. "
                        : "Cold start: mở lại app rồi vào đây để đo. ")
                        + "PBKDF2 ở hai chế độ xấp xỉ nhau — khác biệt là PIN có bị xóa hay không. "
                        + "Keystore vẫn khởi tạo trong onCreate nên cold start mới là đo, chưa tối ưu.");
                btnAction.setText("Đăng nhập");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • PHƠI NHIỄM PIN TRÊN RAM");
                tvRealtimeChartSubtitle.setText("Before: String giữ 100% trong RAM • After: char[] xóa ngay về 0%.");
                break;

            default:
                badge.setText("CHUYÊN ĐỀ 1 • §1");
                title.setText("Điểm nghẽn kép: APDU, chip & mật mã");
                goal.setText("Bấm đọc thẻ như người dùng thật, rồi xem thời gian rơi vào lệnh nào.");
                hint.setText("Nói: nút thắt nằm ở số round-trip + crypto/EEPROM trên thẻ, không phải 240 B. "
                        + "Before/After thực sự nằm ở các chuyên đề §2–§5.");
                btnAction.setText("Đọc hồ sơ từ thẻ");
                tvComparisonTitle.setText("KẾT QUẢ ĐO");
                tvRealtimeChartTitle.setText("ĐỒ THỊ REALTIME • ĐỘ TRỄ TỪNG LỆNH APDU");
                tvRealtimeChartSubtitle.setText("Nhảy theo thời gian thực khi SELECT → VERIFY → INFO → AES → ACADEMIC hoàn tất.");
                break;
        }
    }

    private void wireActions() {
        switchMode.setOnCheckedChangeListener((buttonView, checked) -> {
            tvModeHint.setText(checked ? "After — đã tối ưu" : "Before — chưa tối ưu");
            tvModeHint.setTextColor(Color.parseColor(checked ? GREEN : RED));
            if (realtimeChart != null) {
                realtimeChart.setAfterMode(checked);
            }
        });
        switchMode.setChecked(false);

        btnAction.setOnClickListener(v -> runCurrentMode());
        btnPickImage.setOnClickListener(v -> imagePicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
        btnResetCache.setOnClickListener(v -> resetCache());
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
                runBottleneck();
                break;
        }
    }

    // ------------------------------------------------------------------
    // §1 — user reads the card; we show where the time goes
    // ------------------------------------------------------------------

    private void runBottleneck() {
        startRun("Đang đọc hồ sơ từ thẻ...");
        runTask(this::fetchFromCard, (result, blockedMs, totalMs) -> {
            showProfile(result.getRecord(), null, "THẺ");
            Map<String, String> metrics = new LinkedHashMap<>();
            long[] step = result.getStepMs();
            if (realtimeChart != null) {
                realtimeChart.recordBottleneckResult(step, result.getElapsedMs());
            }
            metrics.put("SELECT applet", step[0] + " ms");
            metrics.put("VERIFY PIN", step[1] + " ms");
            metrics.put("GET INFO", step[2] + " ms");
            metrics.put("GET CIPHER", step[3] + " ms");
            metrics.put("ACADEMIC", step[4] + " ms");
            metrics.put("Tổng", result.getElapsedMs() + " ms");
            metrics.put("Số lệnh", result.getApduCommandsSent() + " APDU");
            finishRun(false, metrics, "✓ Đọc xong hồ sơ trong " + result.getElapsedMs()
                    + " ms — thời gian nằm ở từng lệnh như bảng dưới.", true);
        });
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
                finishRun(true, metrics, "✓ After: hồ sơ đã cập nhật, UI không bị chặn.", true);
            });
        } else {
            runBlockingTask(this::fetchFromCard, (result, blockedMs, totalMs) -> {
                showProfile(result.getRecord(), null, "THẺ");
                if (realtimeChart != null) {
                    realtimeChart.recordThreadingResult(false, totalMs, totalMs);
                }
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("Main Thread giữ", msText(totalMs));
                metrics.put("Thời gian đọc", result.getElapsedMs() + " ms");
                metrics.put("Khung rớt", Math.round(totalMs / 16.6667f) + " khung");
                finishRun(false, metrics, "✓ Before: UI đã bị khóa " + totalMs
                        + " ms — bạn vừa thấy nhịp UI đứng hình.", true);
            });
        }
    }

    // ------------------------------------------------------------------
    // §3 — open the profile: Before always hits the card, After uses cache
    // ------------------------------------------------------------------

    private void runCache(boolean afterMode) {
        startRun("Đang mở hồ sơ...");
        runTask(() -> {
            if (afterMode) {
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
            if (realtimeChart != null) {
                realtimeChart.recordCacheResult(afterMode, openResult.fromCache, totalMs, apduCount);
            }
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("Nguồn", openResult.fromCache ? "Cache (AES-GCM)" : "Thẻ");
            metrics.put("Thời gian", totalMs + " ms");
            metrics.put("APDU", apduCount + "");
            finishRun(afterMode, metrics, openResult.fromCache
                    ? "✓ After: mở hồ sơ từ cache, 0 APDU."
                    : (afterMode
                    ? "✓ After: lần đầu chưa có cache nên đọc thẻ, đã ghi lại cache."
                    : "✓ Before: luôn đọc thẻ mỗi lần mở hồ sơ."), true);
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
        tvStatus.setText("Đã xóa cache hồ sơ — lần mở tiếp theo sẽ đọc thẻ (mô phỏng lần đầu).");
        tvStatus.setTextColor(Color.parseColor(SLATE_MUTED));
    }

    // ------------------------------------------------------------------
    // §4 — pick a portrait and save it to the card
    // ------------------------------------------------------------------

    private void runImage(boolean afterMode) {
        if (!afterMode && pickedUri == null && sampleBytes == null) {
            tvStatus.setText("Ảnh mẫu đang chuẩn bị, chờ một chút rồi bấm lại.");
            tvStatus.setTextColor(Color.parseColor(RED));
            return;
        }
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

    private ImageRunResult runImageBefore() {
        long startNs = System.nanoTime();
        Bitmap full = null;
        Bitmap preview = null;
        try {
            int[] dims = sourceBounds();
            full = decodeFullSize(dims[0], dims[1]);
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
                    "❌ Ảnh PNG " + sizeText(png.length) + " cần " + packets
                            + " gói → vượt bộ đệm 8 KB của thẻ, không lưu được.", false,
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
            int[] dims = sourceBounds();
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
                avatar = ImageOptimizer.decodeAndDownsample(sampleBytes,
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

    private int[] sourceBounds() throws Exception {
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
        BitmapFactory.decodeByteArray(sampleBytes, 0, sampleBytes.length, bounds);
        return new int[]{Math.max(1, bounds.outWidth), Math.max(1, bounds.outHeight)};
    }

    private Bitmap decodeFullSize(int width, int height) {
        if (pickedUri != null) {
            return ImageOptimizer.decodeAndDownsampleFromUri(appContext(), pickedUri, width, height);
        }
        return ImageOptimizer.decodeAndDownsample(sampleBytes, width, height);
    }

    private void prepareSampleImage() {
        if (sampleBytes != null) return;
        ApduThreadPool.getInstance().executeAsyncOnPool(() -> {
            Bitmap bitmap = ImageOptimizer.generateSampleStudentAvatar("SV", 1200);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                return bos.toByteArray();
            } finally {
                bitmap.recycle();
            }
        }, (bytes, blockedMs, totalMs) -> {
            if (!isAdded()) return;
            sampleBytes = bytes;
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

    // ------------------------------------------------------------------
    // §5 — log in with the PIN
    // ------------------------------------------------------------------

    private void runLogin(boolean afterMode) {
        String pinText = demoPinInput.getText().toString().trim();
        if (pinText.length() < 4) {
            tvStatus.setText("Nhập PIN từ 4–8 chữ số rồi bấm Đăng nhập.");
            tvStatus.setTextColor(Color.parseColor(RED));
            return;
        }
        startRun("Đang đăng nhập...");
        runTask(() -> afterMode ? loginAfter(pinText) : loginBefore(pinText),
                (result, blockedMs, totalMs) -> {
                    if (realtimeChart != null) {
                        realtimeChart.recordLoginResult(afterMode, result.ms, result.remaining, result.length);
                    }
                    Map<String, String> metrics = new LinkedHashMap<>();
                    metrics.put("PBKDF2 10.000 vòng", result.ms + " ms");
                    metrics.put("Zeroize PIN", result.remaining < 0
                            ? "Không (String)" : result.remaining + "/" + result.length + " ký tự còn");
                    String status = afterMode
                            ? "✓ After: đăng nhập xong, PIN đã được xóa khỏi bộ nhớ ("
                            + (result.length - result.remaining) + "/" + result.length + " ký tự)."
                            : "✓ Before: đăng nhập xong nhưng PIN vẫn nằm trong String — không xóa được.";
                    finishRun(afterMode, metrics, status, true);
                });
    }

    private PinRunResult loginBefore(String pinText) throws Exception {
        long startNs = System.nanoTime();
        PBEKeySpec spec = new PBEKeySpec(pinText.toCharArray(), new byte[16], 10_000, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        factory.generateSecret(spec).getEncoded();
        long ms = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        // Cố ý không clearPassword: mô phỏng code Before.
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
        tvStatus.setText("❌ Lỗi: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        tvStatus.setTextColor(Color.parseColor(RED));
    }

    private void startRun(String status) {
        busy = true;
        btnAction.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        tvStatus.setText(status);
        tvStatus.setTextColor(Color.parseColor(SLATE_MUTED));
        if (realtimeChart != null) {
            realtimeChart.onRunStarted(topic != TOPIC_BOTTLENECK && optimized());
        }
    }

    private void endRun() {
        busy = false;
        btnAction.setEnabled(true);
        progress.setVisibility(View.GONE);
    }

    private void finishRun(boolean afterRun, Map<String, String> metrics, String status, boolean ok) {
        endRun();
        Map<String, String> target = afterRun ? after : before;
        target.clear();
        target.putAll(metrics);
        renderComparison();
        tvStatus.setText(status);
        tvStatus.setTextColor(Color.parseColor(ok ? GREEN : RED));
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
        if (topic == TOPIC_BOTTLENECK) {
            if (before.isEmpty()) {
                addComparisonNote("Bấm “Đọc hồ sơ từ thẻ” để điền bảng số đo.");
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
            addComparisonNote("Bấm nút ở chế độ Before, rồi bật After và bấm lại để so sánh.");
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
        final int remaining; // -1 = không đo được (String không xóa được)
        final int length;

        PinRunResult(long ms, int remaining, int length) {
            this.ms = ms;
            this.remaining = remaining;
            this.length = length;
        }
    }
}
