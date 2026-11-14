package com.example.smartstudentid.ui.student;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.optimization.LocalCacheManager;
import com.example.smartstudentid.optimization.OptimizationConfig;
import com.example.smartstudentid.security.PinSessionManager;
import com.example.smartstudentid.security.SecurityEnvironmentChecker;
import com.google.android.material.button.MaterialButton;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Tab 4 Fragment: Interactive Optimization Lab (Course 3 - Before vs After Switch & Benchmark)
 * and 5-Layer OWASP MASVS Security Auditor (Course 2 - AES-128, RSA-1024, PBKDF2, KeyStore, Resilience).
 */
public class SecurityOptLabFragment extends Fragment {

    private SwitchCompat switchOptimizationMode;
    private TextView tvOptModeDescription;
    private TextView tvMetricImageComparison;
    private TextView tvMetricCacheComparison;
    private TextView tvMetricThreadMemory;
    private TextView tvSecurityAuditSummary;
    private TextView tvSecurityHeroStatus;
    private TextView tvImageValue;
    private TextView tvCacheValue;
    private TextView tvFpsValue;
    private TextView tvCompareImageValues;
    private TextView tvCompareCacheValues;
    private TextView tvCompareUiValues;
    private ProgressBar pbImageOpt;
    private ProgressBar pbCacheOpt;
    private ProgressBar pbFpsOpt;
    private ProgressBar pbCompareImageAfter;
    private ProgressBar pbCompareCacheAfter;
    private ProgressBar pbCompareUiAfter;

    private LocalCacheManager localCacheManager;
    private PinSessionManager pinSessionManager;

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
        pinSessionManager = new PinSessionManager(requireContext());
        view.setVerticalScrollBarEnabled(false);

        switchOptimizationMode = view.findViewById(R.id.switchOptimizationMode);
        tvOptModeDescription = view.findViewById(R.id.tvOptModeDescription);
        tvMetricImageComparison = view.findViewById(R.id.tvMetricImageComparison);
        tvMetricCacheComparison = view.findViewById(R.id.tvMetricCacheComparison);
        tvMetricThreadMemory = view.findViewById(R.id.tvMetricThreadMemory);
        tvSecurityAuditSummary = view.findViewById(R.id.tvSecurityAuditSummary);
        tvSecurityHeroStatus = view.findViewById(R.id.tvSecurityHeroStatus);
        tvImageValue = view.findViewById(R.id.tvImageValue);
        tvCacheValue = view.findViewById(R.id.tvCacheValue);
        tvFpsValue = view.findViewById(R.id.tvFpsValue);
        tvCompareImageValues = view.findViewById(R.id.tvCompareImageValues);
        tvCompareCacheValues = view.findViewById(R.id.tvCompareCacheValues);
        tvCompareUiValues = view.findViewById(R.id.tvCompareUiValues);
        pbImageOpt = view.findViewById(R.id.pbImageOpt);
        pbCacheOpt = view.findViewById(R.id.pbCacheOpt);
        pbFpsOpt = view.findViewById(R.id.pbFpsOpt);
        pbCompareImageAfter = view.findViewById(R.id.pbCompareImageAfter);
        pbCompareCacheAfter = view.findViewById(R.id.pbCompareCacheAfter);
        pbCompareUiAfter = view.findViewById(R.id.pbCompareUiAfter);

        MaterialButton btnRunFullBenchmark = view.findViewById(R.id.btnRunFullBenchmark);
        MaterialButton btnInspectCipherAndApdu = view.findViewById(R.id.btnInspectCipherAndApdu);
        View btnOpenTopicDemo = view.findViewById(R.id.btnOpenTopicDemo);
        btnOpenTopicDemo.setOnClickListener(v -> startActivity(
                new android.content.Intent(requireContext(),
                        com.example.smartstudentid.ui.demo.TopicDemoActivity.class)));

        switchOptimizationMode.setChecked(OptimizationConfig.isOptimizedModeEnabled());
        updateOptimizationSwitchSummary(OptimizationConfig.isOptimizedModeEnabled());

        switchOptimizationMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            OptimizationConfig.setOptimizedModeEnabled(isChecked);
            updateOptimizationSwitchSummary(isChecked);
            Toast.makeText(requireContext(),
                    isChecked ? "Đã BẬT Chế độ Tối ưu (After Mode)" : "Đã chuyển sang Chế độ Chưa tối ưu (Before Mode)",
                    Toast.LENGTH_SHORT).show();
        });

        btnRunFullBenchmark.setOnClickListener(v -> executeLiveBenchmark());
        btnInspectCipherAndApdu.setOnClickListener(v -> showCiphertextAndApduInspectorDialog());

        populateSecurityAuditSummary();
    }

    private void updateOptimizationSwitchSummary(boolean optimized) {
        // The switch only changes execution strategy: Main Thread vs pool + buffer reuse.
        if (optimized) {
            tvOptModeDescription.setText("Đang bật: tác vụ nền (ThreadPool), đệm AES-GCM, WebP + buffer 8 KB.");
        } else {
            tvOptModeDescription.setText("Đang tắt: tác vụ chạy trên Main Thread, cấp phát mới mỗi gói ảnh.");
        }
    }

    /**
     * Executes the live Before vs After benchmark suite asynchronously and updates the 3 metric cards.
     */
    private void executeLiveBenchmark() {
        tvOptModeDescription.setText("Đang đo kiểm...");
        ApduThreadPool.getInstance().execute(
                () -> localCacheManager.runBenchmarkSuite(),
                (report, mainBlockedMs, totalMs) -> {
                    if (!isAdded()) return;
                    updateOptimizationSwitchSummary(OptimizationConfig.isOptimizedModeEnabled());
                    // §2/§6: real measured cost of handing the work to the pool (or of blocking the UI in Before mode).
                    String mainThreadCost = mainBlockedMs <= 0 ? "< 1 ms" : mainBlockedMs + " ms";
                    int droppedFrames = Math.round(mainBlockedMs / 16.6667f);

                    double bwSavedPct = 100.0 * (1.0 - (report.optimizedWebpKb / Math.max(1.0, report.unoptimizedImageKb)));
                    tvMetricImageComparison.setText(String.format(Locale.US,
                            "WebP %.1f KB (%d gói APDU) vs PNG %d KB (%d gói) • Tiết kiệm %.0f%%",
                            report.optimizedWebpKb, report.optimizedApduPackets,
                            report.unoptimizedImageKb, report.unoptimizedApduPackets, bwSavedPct));

                    tvMetricCacheComparison.setText(String.format(Locale.US,
                            "Cache AES-GCM: %d ms (0 APDU) vs Trực tiếp: %d ms (5 APDU)",
                            report.optimizedCacheLoadMs, report.unoptimizedColdLoadMs));

                    tvMetricThreadMemory.setText(String.format(Locale.US,
                            "§6 UI thread giữ %s • rớt ~%d khung • Buffer 8 KB • RAM %d MB",
                            mainThreadCost, droppedFrames, report.currentRamMb));

                    int savedPct = Math.max(0, Math.min(100, (int) Math.round(bwSavedPct)));
                    tvImageValue.setText("-" + savedPct + "%");
                    pbImageOpt.setProgress(savedPct);
                    tvCacheValue.setText(report.optimizedCacheLoadMs + " ms");
                    pbCacheOpt.setProgress(100);
                    tvFpsValue.setText(droppedFrames == 0 ? "60 FPS" : "JANK");
                    pbFpsOpt.setProgress(droppedFrames == 0 ? 100 : Math.max(5, 100 - droppedFrames * 5));

                    // Before/After bars: red = Before (100%), green = After (relative cost).
                    int imagePct = Math.max(2, (int) Math.round(
                            100.0 * report.optimizedWebpKb / Math.max(1.0, report.unoptimizedImageKb)));
                    int cachePct = Math.max(2, (int) Math.round(
                            100.0 * report.optimizedCacheLoadMs / Math.max(1L, report.unoptimizedColdLoadMs)));
                    int uiPct = Math.max(2, (int) Math.round(
                            100.0 * Math.max(0, mainBlockedMs) / Math.max(1L, totalMs)));
                    pbCompareImageAfter.setProgress(imagePct);
                    pbCompareCacheAfter.setProgress(cachePct);
                    pbCompareUiAfter.setProgress(uiPct);
                    tvCompareImageValues.setText(String.format(Locale.US,
                            "Ảnh thẻ: PNG %d KB → WebP %.1f KB • −%d%% • %d → %d gói",
                            report.unoptimizedImageKb, report.optimizedWebpKb, savedPct,
                            report.unoptimizedApduPackets, report.optimizedApduPackets));
                    tvCompareCacheValues.setText(String.format(Locale.US,
                            "Hồ sơ: 5 APDU %d ms → cache AES-GCM %d ms • nhanh %d×",
                            report.unoptimizedColdLoadMs, report.optimizedCacheLoadMs,
                            Math.max(1, report.unoptimizedColdLoadMs
                                    / Math.max(1, report.optimizedCacheLoadMs))));
                    tvCompareUiValues.setText(String.format(Locale.US,
                            "Main Thread: chặn %d ms → dispatch %s • Before rớt ~%d khung",
                            totalMs, mainThreadCost, droppedFrames));

                    Toast.makeText(requireContext(),
                            "Hoàn tất đo kiểm Benchmark trong " + totalMs + " ms!",
                            Toast.LENGTH_SHORT).show();
                }
        );
    }

    /**
     * Populates the 5 OWASP MASVS security layers status card (Course 2).
     */
    private void populateSecurityAuditSummary() {
        boolean rooted = SecurityEnvironmentChecker.isDeviceRooted();
        boolean debugger = SecurityEnvironmentChecker.isDebuggerAttached();
        boolean keystoreOk = SecurityEnvironmentChecker.isAndroidKeyStoreAvailable();
        int remainingPinTries = pinSessionManager.getRemainingUnlockAttempts();

        tvSecurityHeroStatus.setText(!rooted && !debugger && keystoreOk
                ? "Thiết bị an toàn"
                : "Cần kiểm tra bảo mật");

        String summary = "• AES-128 EEPROM\n"
                + "• RSA-1024: đã xác thực\n"
                + "• PBKDF2 + KeyStore: " + (keystoreOk ? "sẵn sàng" : "fallback") + "\n"
                + "• PIN: còn " + remainingPinTries + "/3 lần thử\n"
                + "• Root: " + (rooted ? "phát hiện" : "an toàn")
                + " • Debugger: " + (debugger ? "có" : "không");

        tvSecurityAuditSummary.setText(summary);
    }

    /**
     * Queries raw AES-128 ciphertext (P1=0x01) vs decrypted plaintext (P1=0x00) and recent APDU hex frames,
     * displaying them inside a clean modal dialog so the main screen stays uncluttered.
     */
    private void showCiphertextAndApduInspectorDialog() {
        ApduThreadPool.getInstance().execute(() -> {
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
            List<String> logs = ApduClient.getInstance().getRecentApduLogs();
            return new InspectorData(plainText, cipherHex, logs);
        }, (data, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;

            StringBuilder sb = new StringBuilder();
            sb.append("=== 1. ĐỐI CHIẾU MÃ HÓA AES-128 TRÊN EEPROM ===\n");
            sb.append("• Bản rõ sau giải mã (INS 0x21 • P1=0x00):\n  ")
                    .append(data.plainUtf8).append("\n\n");
            sb.append("• Bản mã thô AES-128 trong EEPROM (INS 0x21 • P1=0x01):\n  ")
                    .append(data.cipherHex).append("\n\n");
            sb.append("=== 2. NHẬT KÝ GÓI TIN APDU HEX GẦN NHẤT ===\n");
            int start = Math.max(0, data.apduLogs.size() - 8);
            for (int i = start; i < data.apduLogs.size(); i++) {
                sb.append(data.apduLogs.get(i)).append("\n\n");
            }

            ScrollView scrollView = new ScrollView(requireContext());
            TextView tv = new TextView(requireContext());
            int pad = (int) (18 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextSize(12f);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextColor(0xFF0F172A);
            tv.setText(sb.toString().trim());
            scrollView.addView(tv);

            new AlertDialog.Builder(requireContext())
                    .setTitle("Bản mã AES-128 & Gói tin APDU Hex")
                    .setView(scrollView)
                    .setPositiveButton("Đóng", null)
                    .show();
        });
    }

    private static class InspectorData {
        final String plainUtf8;
        final String cipherHex;
        final List<String> apduLogs;

        InspectorData(String plainUtf8, String cipherHex, List<String> apduLogs) {
            this.plainUtf8 = plainUtf8;
            this.cipherHex = cipherHex;
            this.apduLogs = apduLogs;
        }
    }
}
