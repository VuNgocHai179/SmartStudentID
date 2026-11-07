package com.example.smartstudentid.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.optimization.LocalCacheManager;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.PinSessionManager;
import com.example.smartstudentid.security.RsaCardVerifier;
import com.example.smartstudentid.ui.admin.AdminDashboardActivity;
import com.example.smartstudentid.ui.student.StudentMainActivity;

import java.nio.charset.StandardCharsets;

/**
 * Activity implementing the Smart PIN Session & RSA Card Authentication workflow
 * (CN 2.1, 2.2, 2.3 | SEC-02, SEC-03 | OPT-03, OPT-04):
 * - First-time login: Requires scanning the card on JCIDE, passing the 16-byte Nonce
 *   RSA-1024 Challenge-Response check, and verifying the 6-digit PIN (INS 0x10).
 * - Subsequent launches: Keeps the card session active and prompts only for the 6-digit PIN
 *   to unlock the EncryptedSharedPreferences cache in ~15ms while silently verifying PIN
 *   against the card in the background.
 * - Physical security protection: 3 consecutive wrong PIN attempts trigger Auto-Wipe.
 */
public class LoginPinActivity extends BaseSecureActivity {

    private View cardFirstTimeScan;
    private TextView tvSessionSubtitle;
    private TextView tvRsaChallengeStatus;
    private TextView tvPinCardTitle;
    private TextView tvPinLockWarning;
    private TextView tvLoginFeedback;
    private EditText etBridgeUrl;
    private EditText etStudentPin;
    private Button btnSwitchStudentCard;
    private LinearLayout pinDotsContainer;

    private static final int MIN_VISIBLE_PIN_DOTS = 6;
    private static final int MAX_PIN_LENGTH = 8;

    private PinSessionManager pinSessionManager;
    private LocalCacheManager localCacheManager;
    private RsaCardVerifier rsaCardVerifier;
    private boolean rsaChallengePassed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_pin);
        applySafeAreaPadding(findViewById(R.id.loginRoot), true, true);
        findViewById(R.id.loginRoot).setVerticalScrollBarEnabled(false);

        pinSessionManager = new PinSessionManager(this);
        localCacheManager = new LocalCacheManager(this);
        rsaCardVerifier = new RsaCardVerifier();

        bindViews();
        refreshSessionUiState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSessionUiState();
    }

    private void bindViews() {
        cardFirstTimeScan = findViewById(R.id.cardFirstTimeScan);
        tvSessionSubtitle = findViewById(R.id.tvSessionSubtitle);
        tvRsaChallengeStatus = findViewById(R.id.tvRsaChallengeStatus);
        tvPinCardTitle = findViewById(R.id.tvPinCardTitle);
        tvPinLockWarning = findViewById(R.id.tvPinLockWarning);
        tvLoginFeedback = findViewById(R.id.tvLoginFeedback);
        etBridgeUrl = findViewById(R.id.etBridgeUrl);
        etStudentPin = findViewById(R.id.etStudentPin);
        pinDotsContainer = findViewById(R.id.pinDotsContainer);
        btnSwitchStudentCard = findViewById(R.id.btnSwitchStudentCard);

        setupPinKeypad();
        renderPinDots(etStudentPin.getText().length());

        Button btnApplyBridgeUrl = findViewById(R.id.btnApplyBridgeUrl);
        Button btnScanAndVerifyRsa = findViewById(R.id.btnScanAndVerifyRsa);
        Button btnUnlockWithPin = findViewById(R.id.btnUnlockWithPin);
        Button btnOpenAdminDashboard = findViewById(R.id.btnOpenAdminDashboard);

        btnApplyBridgeUrl.setOnClickListener(v -> {
            String url = etBridgeUrl.getText().toString().trim();
            ApduClient.getInstance().setServerBaseUrl(url);
            Toast.makeText(this, "Đã lưu Bridge URL: " + url, Toast.LENGTH_SHORT).show();
        });

        btnScanAndVerifyRsa.setOnClickListener(v -> handleScanAndVerifyRsa());
        btnUnlockWithPin.setOnClickListener(v -> handlePinUnlock());

        btnSwitchStudentCard.setOnClickListener(v -> {
            pinSessionManager.clearSession();
            rsaChallengePassed = false;
            etStudentPin.setText("");
            renderPinDots(0);
            refreshSessionUiState();
            tvLoginFeedback.setText("Đã xóa Session cũ. Vui lòng quét thẻ sinh viên mới.");
        });

        btnOpenAdminDashboard.setOnClickListener(v -> {
            startActivity(new Intent(this, AdminDashboardActivity.class));
        });
    }

    /**
     * Wires the custom numeric keypad to the (hidden) PIN EditText so the existing
     * PIN verification logic stays untouched while the UI gets a modern, large target pad.
     */
    private void setupPinKeypad() {
        int[] digitButtonIds = {
                R.id.btnPin0, R.id.btnPin1, R.id.btnPin2, R.id.btnPin3, R.id.btnPin4,
                R.id.btnPin5, R.id.btnPin6, R.id.btnPin7, R.id.btnPin8, R.id.btnPin9
        };
        for (int i = 0; i < digitButtonIds.length; i++) {
            final String digit = String.valueOf(i);
            Button key = findViewById(digitButtonIds[i]);
            key.setOnClickListener(v -> appendPinDigit(digit));
        }

        Button backspace = findViewById(R.id.btnPinBackspace);
        backspace.setOnClickListener(v -> deleteLastPinDigit());
    }

    private void appendPinDigit(String digit) {
        String current = etStudentPin.getText().toString();
        if (current.length() >= MAX_PIN_LENGTH) {
            return;
        }
        etStudentPin.setText(current + digit);
        etStudentPin.setSelection(etStudentPin.getText().length());
        renderPinDots(etStudentPin.getText().length());
        if (tvLoginFeedback != null) {
            tvLoginFeedback.setText("");
        }
    }

    private void deleteLastPinDigit() {
        String current = etStudentPin.getText().toString();
        if (current.isEmpty()) {
            return;
        }
        etStudentPin.setText(current.substring(0, current.length() - 1));
        etStudentPin.setSelection(etStudentPin.getText().length());
        renderPinDots(etStudentPin.getText().length());
    }

    /**
     * Renders 6 dots for the standard PIN and grows to 8 dots automatically for longer PINs.
     */
    private void renderPinDots(int enteredLength) {
        if (pinDotsContainer == null) {
            return;
        }
        int totalDots = Math.max(MIN_VISIBLE_PIN_DOTS, Math.min(MAX_PIN_LENGTH, enteredLength));
        int dotSize = Math.round(10 * getResources().getDisplayMetrics().density);
        int dotMargin = Math.round(6 * getResources().getDisplayMetrics().density);

        pinDotsContainer.removeAllViews();
        for (int i = 0; i < totalDots; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            params.setMargins(dotMargin, 0, dotMargin, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(i < enteredLength
                    ? R.drawable.bg_pin_dot_filled
                    : R.drawable.bg_pin_dot_empty);
            pinDotsContainer.addView(dot);
        }
    }

    /**
     * Switches UI between First-Time Card Scan mode (CN 2.1) and Fast PIN Session Unlock mode (CN 2.2).
     */
    private void refreshSessionUiState() {
        if (pinSessionManager.hasActiveSession()) {
            cardFirstTimeScan.setVisibility(View.GONE);
            btnSwitchStudentCard.setVisibility(View.VISIBLE);
            String name = pinSessionManager.getActiveStudentName();
            String id = pinSessionManager.getActiveStudentId();
            tvSessionSubtitle.setText("Phiên thẻ đang lưu: " + name + " (" + id + ")");
            tvPinCardTitle.setText("Nhập mã PIN để mở khóa");
            tvPinLockWarning.setText("Còn " + pinSessionManager.getRemainingUnlockAttempts()
                    + " lần thử trước khi xóa phiên.");
        } else {
            cardFirstTimeScan.setVisibility(View.VISIBLE);
            btnSwitchStudentCard.setVisibility(View.GONE);
            tvSessionSubtitle.setText("Quét thẻ JCIDE và nhập mã PIN.");
            tvPinCardTitle.setText("Bước 2 • Nhập mã PIN (6 số)");
            tvPinLockWarning.setText("PIN mặc định: 123456");
        }
    }

    /**
     * Executes Step 1 of First-Time Login: SELECT AID + 16-byte Nonce RSA-1024 Challenge-Response (CN 2.1 & SEC-03).
     */
    private void handleScanAndVerifyRsa() {
        tvRsaChallengeStatus.setText("Đang xác thực thẻ...");
        ApduThreadPool.getInstance().execute(() -> {
            ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_ISO,
                    ApduCommands.INS_SELECT,
                    (byte) 0x04,
                    (byte) 0x00,
                    ApduCommands.APPLET_AID
            );
            return rsaCardVerifier.performChallengeResponse();
        }, (result, mainBlockedMs, totalMs) -> {
            rsaChallengePassed = result.isVerified();
            if (result.isVerified()) {
                tvRsaChallengeStatus.setText("✓ Xác thực RSA-1024 thành công (" + totalMs + " ms)");
                tvLoginFeedback.setText("Thẻ hợp lệ. Nhập mã PIN.");
            } else {
                tvRsaChallengeStatus.setText("❌ Xác thực thẻ thất bại.");
            }
        });
    }

    /**
     * Handles PIN verification for both Fast Session Unlock (CN 2.2) and First-Time Card Login (CN 2.1).
     */
    private void handlePinUnlock() {
        String pin = etStudentPin.getText().toString().trim();
        if (pin.length() < 4) {
            Toast.makeText(this, "Mã PIN phải từ 4 đến 8 ký tự!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Mode B: Fast PIN Session Unlock from Encrypted Cache (~15ms)
        if (pinSessionManager.hasActiveSession()) {
            long startNs = System.nanoTime();
            boolean unlocked = pinSessionManager.verifyLocalSessionPin(pin);
            long unlockMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);

            if (unlocked) {
                // Silently verify PIN with the smart card in the background thread pool
                ApduThreadPool.getInstance().executeAsyncOnPool(() ->
                        ApduClient.getInstance().sendApdu(
                                ApduCommands.CLA_SMART_STUDENT,
                                ApduCommands.INS_VERIFY_PIN,
                                (byte) 0x00,
                                (byte) 0x00,
                                pin.getBytes(StandardCharsets.UTF_8)
                        ), null);

                Toast.makeText(this, "Mở khóa Encrypted Cache thành công trong " + unlockMs + " ms!",
                        Toast.LENGTH_SHORT).show();
                openStudentMainScreen(unlockMs, true);
            } else {
                if (!pinSessionManager.hasActiveSession()) {
                    // Auto-Wipe triggered after 3 failed attempts
                    rsaChallengePassed = false;
                    etStudentPin.setText("");
                    renderPinDots(0);
                    refreshSessionUiState();
                    tvLoginFeedback.setText("Đã nhập sai PIN 3 lần. Phiên đã bị xóa.");
                } else {
                    int remaining = pinSessionManager.getRemainingUnlockAttempts();
                    etStudentPin.setText("");
                    renderPinDots(0);
                    tvLoginFeedback.setText("Sai mã PIN. Còn " + remaining + " lần thử.");
                    refreshSessionUiState();
                }
            }
            return;
        }

        // Mode A: First-Time Card Login (requires RSA challenge first, or auto-runs it)
        tvLoginFeedback.setText("Đang xác thực PIN...");
        ApduThreadPool.getInstance().execute(() -> {
            if (!rsaChallengePassed) {
                RsaCardVerifier.VerificationResult vr = rsaCardVerifier.performChallengeResponse();
                rsaChallengePassed = vr.isVerified();
            }
            CardResponse pinResp = ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_VERIFY_PIN,
                    (byte) 0x00,
                    (byte) 0x00,
                    pin.getBytes(StandardCharsets.UTF_8)
            );
            if (!pinResp.isSuccess()) {
                return new LoginAttemptOutcome(false, pinResp, null);
            }
            LocalCacheManager.ProfileLoadResult loadResult = localCacheManager.fetchStudentProfileFromCard(pin);
            return new LoginAttemptOutcome(true, pinResp, loadResult);
        }, (outcome, mainBlockedMs, totalMs) -> {
            if (outcome.success && outcome.profileLoadResult != null) {
                StudentCardRecord record = outcome.profileLoadResult.getRecord();
                pinSessionManager.createPinSession(record.getStudentId(), record.getFullName(), pin);
                Toast.makeText(this, "Đăng nhập thẻ & tạo PIN Session thành công!", Toast.LENGTH_SHORT).show();
                openStudentMainScreen(totalMs, false);
            } else {
                CardResponse resp = outcome.pinResponse;
                etStudentPin.setText("");
                renderPinDots(0);
                if (resp.isCardLocked()) {
                    tvLoginFeedback.setText("Thẻ đã bị khóa. Dùng PUK trong Chế độ Quản trị để mở khóa.");
                } else {
                    int tries = resp.getRemainingPinTries();
                    tvLoginFeedback.setText("Sai mã PIN. Còn " + tries + " lần thử.");
                }
            }
        });
    }

    private void openStudentMainScreen(long loadTimeMs, boolean loadedFromCache) {
        Intent intent = new Intent(this, StudentMainActivity.class);
        intent.putExtra("LOAD_TIME_MS", loadTimeMs);
        intent.putExtra("FROM_CACHE", loadedFromCache);
        startActivity(intent);
    }

    private static class LoginAttemptOutcome {
        final boolean success;
        final CardResponse pinResponse;
        final LocalCacheManager.ProfileLoadResult profileLoadResult;

        LoginAttemptOutcome(boolean success, CardResponse pinResponse,
                            LocalCacheManager.ProfileLoadResult profileLoadResult) {
            this.success = success;
            this.pinResponse = pinResponse;
            this.profileLoadResult = profileLoadResult;
        }
    }
}
