package com.example.smartstudentid.ui.auth;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
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
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.example.smartstudentid.security.RsaCardVerifier;
import com.example.smartstudentid.ui.admin.AdminDashboardActivity;
import com.example.smartstudentid.ui.admin.AdminLoginActivity;
import com.example.smartstudentid.ui.student.StudentMainActivity;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * NFC Smart Student Card Login Screen:
 * - State 1 (Card Session Bound): Displays Student Name • ID + Department, "Mở khóa ứng dụng" button,
 *   and "Đăng xuất thẻ" button.
 * - State 2 (No Card Bound / Logged Out): Displays the Contactless NFC Reader waiting zone
 *   ("Đưa thẻ NFC lại gần thiết bị") without any manual login button. Tapping the NFC zone
 *   simulates bringing the NFC card to the back of the phone and immediately pops up the PIN pad.
 */
public class LoginPinActivity extends BaseSecureActivity {

    private View layoutNfcScanZone;
    private View layoutSavedCardZone;
    private TextView tvNfcPromptTitle;
    private TextView tvNfcPromptSubtitle;
    private TextView tvSessionSubtitle;
    private TextView tvCardHolderName;
    private TextView tvRsaChallengeStatus;
    private TextView tvLoginFeedback;
    private Button btnPrimaryLogin;
    private Button btnLogoutSavedCard;

    private static final int STANDARD_PIN_LENGTH = 6;
    private static final int MAX_PIN_LENGTH = 8;

    private PinSessionManager pinSessionManager;
    private LocalCacheManager localCacheManager;
    private EncryptedCacheManager encryptedCacheManager;
    private RsaCardVerifier rsaCardVerifier;
    private boolean rsaChallengePassed = false;

    private Dialog activePinDialog;
    private final StringBuilder currentPinInput = new StringBuilder();
    private final android.os.Handler nfcPollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean isPollingActive = false;

    private final Runnable nfcBridgePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPollingActive) return;
            ApduThreadPool.getInstance().execute(
                    () -> ApduClient.getInstance().pollBridgeCardTapEvent(),
                    (newCardTapped, mainBlockedMs, totalMs) -> {
                        if (!isPollingActive) return;
                        if (Boolean.TRUE.equals(newCardTapped)) {
                            if (activePinDialog != null && activePinDialog.isShowing()) {
                                activePinDialog.dismiss();
                            }
                            pinSessionManager.clearSession();
                            rsaChallengePassed = true;
                            refreshSessionUiState();
                            showPinEntryPopup();
                        }
                        nfcPollHandler.postDelayed(this, 750L);
                    }
            );
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_pin);
        applySafeAreaPadding(findViewById(R.id.loginRoot), true, true);
        findViewById(R.id.loginRoot).setVerticalScrollBarEnabled(false);

        pinSessionManager = new PinSessionManager(this);
        localCacheManager = new LocalCacheManager(this);
        encryptedCacheManager = EncryptedCacheManager.getInstance(this);
        rsaCardVerifier = new RsaCardVerifier();

        bindViews();
        refreshSessionUiState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSessionUiState();
        isPollingActive = true;
        nfcPollHandler.removeCallbacks(nfcBridgePollRunnable);
        nfcPollHandler.postDelayed(nfcBridgePollRunnable, 500L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isPollingActive = false;
        nfcPollHandler.removeCallbacks(nfcBridgePollRunnable);
    }

    private void bindViews() {
        layoutNfcScanZone = findViewById(R.id.layoutNfcScanZone);
        layoutSavedCardZone = findViewById(R.id.layoutSavedCardZone);
        tvNfcPromptTitle = findViewById(R.id.tvNfcPromptTitle);
        tvNfcPromptSubtitle = findViewById(R.id.tvNfcPromptSubtitle);
        tvSessionSubtitle = findViewById(R.id.tvSessionSubtitle);
        tvCardHolderName = findViewById(R.id.tvCardHolderName);
        tvRsaChallengeStatus = findViewById(R.id.tvRsaChallengeStatus);
        tvLoginFeedback = findViewById(R.id.tvLoginFeedback);
        btnPrimaryLogin = findViewById(R.id.btnPrimaryLogin);
        btnLogoutSavedCard = findViewById(R.id.btnLogoutSavedCard);
        Button btnOpenAdminDashboard = findViewById(R.id.btnOpenAdminDashboard);

        btnPrimaryLogin.setOnClickListener(v -> showPinEntryPopup());

        btnLogoutSavedCard.setOnClickListener(v -> {
            pinSessionManager.clearSession();
            rsaChallengePassed = false;
            refreshSessionUiState();
            hideMainFeedback();
        });

        btnOpenAdminDashboard.setOnClickListener(v ->
                startActivity(new Intent(this, AdminLoginActivity.class)));
    }

    private StudentCardRecord getActiveInsertedCardRecord() {
        String rawInfo = ApduClient.getInstance().getActiveCardInfo();
        return StudentCardRecord.fromPipeDelimitedString(rawInfo);
    }

    private String resolveDepartmentForStudent(String studentId) {
        StudentCardRecord cached = encryptedCacheManager.getStudentProfile();
        if (cached != null && studentId.equalsIgnoreCase(cached.getStudentId())
                && cached.getDepartment() != null && !cached.getDepartment().isEmpty()) {
            return cached.getDepartment();
        }
        return getActiveInsertedCardRecord().getDepartment();
    }

    private void refreshSessionUiState() {
        tvSessionSubtitle.setText("Cổng định danh & tiện ích học vụ điện tử");
        if (pinSessionManager.hasActiveSession()) {
            layoutNfcScanZone.setVisibility(View.GONE);
            layoutSavedCardZone.setVisibility(View.VISIBLE);

            String name = pinSessionManager.getActiveStudentName();
            String id = pinSessionManager.getActiveStudentId();
            tvCardHolderName.setText(name + " • " + id);
            tvRsaChallengeStatus.setText(resolveDepartmentForStudent(id));
            btnPrimaryLogin.setText("Mở khóa ứng dụng");
        } else if (rsaChallengePassed) {
            layoutNfcScanZone.setVisibility(View.GONE);
            layoutSavedCardZone.setVisibility(View.VISIBLE);

            StudentCardRecord activeCard = getActiveInsertedCardRecord();
            tvCardHolderName.setText(activeCard.getFullName() + " • " + activeCard.getStudentId());
            tvRsaChallengeStatus.setText(activeCard.getDepartment());
            btnPrimaryLogin.setText("Mở khóa ứng dụng");
        } else {
            layoutSavedCardZone.setVisibility(View.GONE);
            layoutNfcScanZone.setVisibility(View.VISIBLE);
            tvNfcPromptTitle.setText("Đưa thẻ sinh viên lại gần NFC thiết bị");
            tvNfcPromptSubtitle.setText("Áp mặt thẻ sinh viên vào lưng điện thoại để tự động nhận diện");
        }
    }

    /**
     * Displays the clean modal PIN keypad popup dialog showing the target student's name.
     */
    private void showPinEntryPopup() {
        if (activePinDialog != null && activePinDialog.isShowing()) {
            return;
        }

        currentPinInput.setLength(0);
        View popupView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_pad, null, false);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(popupView);
        dialog.setCancelable(true);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvSubtitle = popupView.findViewById(R.id.tvPinPopupSubtitle);
        TextView tvError = popupView.findViewById(R.id.tvPinPopupError);
        LinearLayout dotsContainer = popupView.findViewById(R.id.pinDotsContainer);
        View btnClose = popupView.findViewById(R.id.btnClosePinPopup);
        Button btnConfirm = popupView.findViewById(R.id.btnConfirmPinPopup);

        String targetHolder;
        if (pinSessionManager.hasActiveSession()) {
            targetHolder = pinSessionManager.getActiveStudentName()
                    + " • " + pinSessionManager.getActiveStudentId();
        } else {
            StudentCardRecord activeCard = getActiveInsertedCardRecord();
            targetHolder = activeCard.getFullName() + " • " + activeCard.getStudentId();
        }
        tvSubtitle.setText(targetHolder);

        renderPinDots(dotsContainer, 0);

        int[] digitIds = {
                R.id.btnPin0, R.id.btnPin1, R.id.btnPin2, R.id.btnPin3, R.id.btnPin4,
                R.id.btnPin5, R.id.btnPin6, R.id.btnPin7, R.id.btnPin8, R.id.btnPin9
        };
        for (int i = 0; i < digitIds.length; i++) {
            final char digitChar = (char) ('0' + i);
            View keyBtn = popupView.findViewById(digitIds[i]);
            keyBtn.setOnClickListener(v -> {
                if (currentPinInput.length() >= MAX_PIN_LENGTH) {
                    return;
                }
                currentPinInput.append(digitChar);
                tvError.setText("");
                renderPinDots(dotsContainer, currentPinInput.length());

                // Automatically submit when reaching 6 digits for fast, seamless UX
                if (currentPinInput.length() == STANDARD_PIN_LENGTH) {
                    submitPinFromPopup(currentPinInput.toString(), dialog, dotsContainer, tvError);
                }
            });
        }

        View btnBackspace = popupView.findViewById(R.id.btnPinBackspace);
        btnBackspace.setOnClickListener(v -> {
            if (currentPinInput.length() > 0) {
                currentPinInput.deleteCharAt(currentPinInput.length() - 1);
                renderPinDots(dotsContainer, currentPinInput.length());
            }
        });

        btnConfirm.setOnClickListener(v -> {
            if (currentPinInput.length() < 4) {
                tvError.setText("Vui lòng nhập từ 4 đến 8 chữ số");
                return;
            }
            submitPinFromPopup(currentPinInput.toString(), dialog, dotsContainer, tvError);
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        activePinDialog = dialog;
        dialog.show();
    }

    private void renderPinDots(LinearLayout container, int enteredLength) {
        if (container == null) return;
        int totalDots = Math.max(STANDARD_PIN_LENGTH, Math.min(MAX_PIN_LENGTH, enteredLength));
        int dotSize = Math.round(11 * getResources().getDisplayMetrics().density);
        int dotMargin = Math.round(7 * getResources().getDisplayMetrics().density);

        container.removeAllViews();
        for (int i = 0; i < totalDots; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            params.setMargins(dotMargin, 0, dotMargin, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(i < enteredLength
                    ? R.drawable.bg_pin_dot_filled
                    : R.drawable.bg_pin_dot_empty);
            container.addView(dot);
        }
    }

    private void submitPinFromPopup(String pin, Dialog dialog, LinearLayout dotsContainer, TextView tvError) {
        // Mode B: Fast PIN Session Unlock from Encrypted Cache (~9-15ms)
        if (pinSessionManager.hasActiveSession()) {
            long startNs = System.nanoTime();
            boolean unlocked = pinSessionManager.verifyLocalSessionPin(pin);
            long unlockMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);

            if (unlocked) {
                ApduThreadPool.getInstance().executeAsyncOnPool(() ->
                        ApduClient.getInstance().sendApdu(
                                ApduCommands.CLA_SMART_STUDENT,
                                ApduCommands.INS_VERIFY_PIN,
                                (byte) 0x00,
                                (byte) 0x00,
                                pin.getBytes(StandardCharsets.UTF_8)
                        ), null);

                dialog.dismiss();
                openStudentMainScreen(unlockMs, true);
            } else {
                currentPinInput.setLength(0);
                renderPinDots(dotsContainer, 0);
                if (!pinSessionManager.hasActiveSession()) {
                    // Auto-Wipe triggered after 3 failed attempts
                    rsaChallengePassed = false;
                    dialog.dismiss();
                    refreshSessionUiState();
                    showMainFeedback("Đã nhập sai mã PIN 3 lần. Dữ liệu đệm đã tự động xóa bảo vệ.");
                } else {
                    int remaining = pinSessionManager.getRemainingUnlockAttempts();
                    tvError.setText("Mã PIN không đúng • Còn " + remaining + " lần thử");
                }
            }
            return;
        }

        // Mode A: First-Time Card Login
        tvError.setText("Đang kiểm tra mã PIN...");
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
                dialog.dismiss();
                openStudentMainScreen(totalMs, false);
            } else {
                CardResponse resp = outcome.pinResponse;
                currentPinInput.setLength(0);
                renderPinDots(dotsContainer, 0);
                if (resp.isCardLocked()) {
                    dialog.dismiss();
                    showMainFeedback("Thẻ đã bị khóa do nhập sai PIN 3 lần. Vui lòng mở khóa bằng PUK ở mục Quản trị.");
                } else {
                    int tries = resp.getRemainingPinTries();
                    tvError.setText("Mã PIN không đúng • Còn " + tries + " lần thử");
                }
            }
        });
    }

    private void showMainFeedback(String message) {
        if (tvLoginFeedback != null) {
            tvLoginFeedback.setText(message);
            tvLoginFeedback.setVisibility(View.VISIBLE);
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideMainFeedback() {
        if (tvLoginFeedback != null) {
            tvLoginFeedback.setText("");
            tvLoginFeedback.setVisibility(View.GONE);
        }
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
