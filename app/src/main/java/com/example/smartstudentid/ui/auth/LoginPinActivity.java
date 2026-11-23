package com.example.smartstudentid.ui.auth;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import com.example.smartstudentid.optimization.DemoMetrics;
import com.example.smartstudentid.optimization.LocalCacheManager;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.example.smartstudentid.security.RsaCardVerifier;
import com.example.smartstudentid.ui.admin.AdminDashboardActivity;
import com.example.smartstudentid.ui.admin.AdminLoginActivity;
import com.example.smartstudentid.ui.student.StudentMainActivity;

import java.util.Arrays;
import java.util.Locale;

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
    private final char[] pinBuffer = new char[MAX_PIN_LENGTH];
    private int pinLength = 0;
    private TextView tvPerfFooter;
    private long coldStartMs = 0;
    private Long lastPbkdf2Ms = null;
    private boolean pinSubmitInProgress = false;
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
        measureColdStart();
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
        View btnOpenAdminDashboard = findViewById(R.id.btnOpenAdminDashboard);
        tvPerfFooter = findViewById(R.id.tvPerfFooter);

        btnPrimaryLogin.setOnClickListener(v -> showPinEntryPopup());
        layoutNfcScanZone.setOnClickListener(v -> {
            rsaChallengePassed = true;
            refreshSessionUiState();
            showPinEntryPopup();
        });

        btnLogoutSavedCard.setOnClickListener(v -> {
            pinSessionManager.clearSession();
            rsaChallengePassed = false;
            refreshSessionUiState();
            hideMainFeedback();
        });

        btnOpenAdminDashboard.setOnClickListener(v ->
                startActivity(new Intent(this, AdminLoginActivity.class)));
    }

    /**
     * §5 demo: measures cold start from process start (Process.getStartElapsedRealtime) until the
     * PIN screen has been laid out and is ready to accept taps.
     */
    private void measureColdStart() {
        View root = findViewById(R.id.loginRoot);
        if (root == null) return;
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!root.getViewTreeObserver().isAlive()) return true;
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                coldStartMs = Math.max(1,
                        SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime());
                DemoMetrics.setColdStartMs(coldStartMs);
                updatePerfFooter();
                return true;
            }
        });
        updatePerfFooter();
    }

    /**
     * §5 footer: cold start, last PBKDF2 cost (§1.2) and the zeroization guarantee.
     */
    private void updatePerfFooter() {
        if (tvPerfFooter == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("§5 Khởi động nguội: ")
                .append(coldStartMs > 0 ? coldStartMs + " ms" : "đang đo...")
                .append(" (process → màn PIN sẵn sàng)");
        if (lastPbkdf2Ms != null) {
            sb.append(" • §1 PBKDF2 10.000 vòng: ").append(lastPbkdf2Ms).append(" ms");
        }
        sb.append("\n§5 Zeroize: PIN giữ ở char[], xóa bằng PBEKeySpec.clearPassword() + Arrays.fill");
        tvPerfFooter.setText(sb.toString());
    }

    private static void showPinCryptoInfo(TextView tv, String text) {
        if (tv != null) {
            tv.setText(text);
        }
    }

    private static byte[] pinToBytes(char[] pin) {
        byte[] bytes = new byte[pin.length];
        for (int i = 0; i < pin.length; i++) {
            bytes[i] = (byte) pin[i];
        }
        return bytes;
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

        pinLength = 0;
        Arrays.fill(pinBuffer, '\0');
        View popupView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_pad, null, false);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(popupView);
        dialog.setCancelable(true);
        // Never leave the keypad locked if the dialog is closed while a card check is in flight.
        dialog.setOnDismissListener(d -> pinSubmitInProgress = false);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvSubtitle = popupView.findViewById(R.id.tvPinPopupSubtitle);
        TextView tvError = popupView.findViewById(R.id.tvPinPopupError);
        TextView tvPinCryptoInfo = popupView.findViewById(R.id.tvPinCryptoInfo);
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
                if (pinSubmitInProgress || pinLength >= MAX_PIN_LENGTH) {
                    return;
                }
                pinBuffer[pinLength++] = digitChar;
                tvError.setText("");
                renderPinDots(dotsContainer, pinLength);

                // Automatically submit when reaching 6 digits for fast, seamless UX
                if (pinLength == STANDARD_PIN_LENGTH) {
                    submitPinFromPopup(Arrays.copyOf(pinBuffer, pinLength), dialog, dotsContainer,
                            tvError, tvPinCryptoInfo);
                }
            });
        }

        View btnBackspace = popupView.findViewById(R.id.btnPinBackspace);
        btnBackspace.setOnClickListener(v -> {
            if (pinSubmitInProgress) {
                return;
            }
            if (pinLength > 0) {
                pinBuffer[--pinLength] = '\0';
                renderPinDots(dotsContainer, pinLength);
            }
        });

        btnConfirm.setOnClickListener(v -> {
            if (pinSubmitInProgress) {
                return;
            }
            if (pinLength < 4) {
                tvError.setText("Vui lòng nhập từ 4 đến 8 chữ số");
                return;
            }
            submitPinFromPopup(Arrays.copyOf(pinBuffer, pinLength), dialog, dotsContainer,
                    tvError, tvPinCryptoInfo);
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

    private void submitPinFromPopup(char[] pin, Dialog dialog, LinearLayout dotsContainer,
                                    TextView tvError, TextView tvPinCryptoInfo) {
        pinSubmitInProgress = true;
        pinLength = 0;
        Arrays.fill(pinBuffer, '\0');

        // Mode B: Fast PIN Session Unlock from Encrypted Cache (~9-15ms)
        if (pinSessionManager.hasActiveSession()) {
            byte[] pinBytes = pinToBytes(pin);
            long startNs = System.nanoTime();
            // verifyLocalSessionPin zero-fills the char[] PIN after PBKDF2 (§5.2 zeroization).
            boolean unlocked = pinSessionManager.verifyLocalSessionPin(pin);
            long unlockMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            lastPbkdf2Ms = unlockMs;
            updatePerfFooter();
            showPinCryptoInfo(tvPinCryptoInfo, String.format(Locale.US,
                    "§1 PBKDF2 10.000 vòng: %d ms • §5 PIN đã zeroize (char[])", unlockMs));

            if (unlocked) {
                ApduThreadPool.getInstance().executeAsyncOnPool(() -> {
                    try {
                        return ApduClient.getInstance().sendApdu(
                                ApduCommands.CLA_SMART_STUDENT,
                                ApduCommands.INS_VERIFY_PIN,
                                (byte) 0x00,
                                (byte) 0x00,
                                pinBytes
                        );
                    } finally {
                        Arrays.fill(pinBytes, (byte) 0);
                    }
                }, null);

                pinSubmitInProgress = false;
                dialog.dismiss();
                openStudentMainScreen(unlockMs, true);
            } else {
                Arrays.fill(pinBytes, (byte) 0);
                pinSubmitInProgress = false;
                renderPinDots(dotsContainer, 0);
                if (!pinSessionManager.hasActiveSession()) {
                    // Auto-Wipe triggered after 3 failed attempts
                    rsaChallengePassed = false;
                    dialog.dismiss();
                    refreshSessionUiState();
                    showMainFeedback("§3 Auto-Wipe: sai PIN 3 lần — session + cache AES-GCM đã bị xóa.");
                } else {
                    int remaining = pinSessionManager.getRemainingUnlockAttempts();
                    tvError.setText("Mã PIN không đúng • §3 còn " + remaining + "/3 lần thử");
                }
            }
            return;
        }

        // Mode A: First-Time Card Login
        tvError.setText("Đang kiểm tra mã PIN...");
        ApduThreadPool.getInstance().execute(() -> {
            long rsaMicros = 0;
            try {
                if (!rsaChallengePassed) {
                    RsaCardVerifier.VerificationResult vr = rsaCardVerifier.performChallengeResponse();
                    rsaChallengePassed = vr.isVerified();
                    rsaMicros = vr.getVerifyMicros();
                }
                byte[] pinBytes = pinToBytes(pin);
                CardResponse pinResp;
                try {
                    pinResp = ApduClient.getInstance().sendApdu(
                            ApduCommands.CLA_SMART_STUDENT,
                            ApduCommands.INS_VERIFY_PIN,
                            (byte) 0x00,
                            (byte) 0x00,
                            pinBytes
                    );
                } finally {
                    Arrays.fill(pinBytes, (byte) 0);
                }
                if (!pinResp.isSuccess()) {
                    return new LoginAttemptOutcome(false, pinResp, null, rsaMicros, 0);
                }
                LocalCacheManager.ProfileLoadResult loadResult =
                        localCacheManager.fetchStudentProfileFromCard(pin);

                // Fetch the portrait stored on THIS card so a previously cached student's photo never lingers.
                byte[] avatarBytes = ApduClient.getInstance().downloadAvatarInChunks(true);
                if (avatarBytes != null && avatarBytes.length > 0) {
                    encryptedCacheManager.saveAvatarBytes(avatarBytes);
                }

                // PBKDF2 verifier for the local session; also zero-fills the PIN char[].
                long pbkdf2StartNs = System.nanoTime();
                StudentCardRecord record = loadResult.getRecord();
                pinSessionManager.createPinSession(record.getStudentId(), record.getFullName(), pin);
                long pbkdf2Ms = Math.max(1, (System.nanoTime() - pbkdf2StartNs) / 1_000_000);
                return new LoginAttemptOutcome(true, pinResp, loadResult, rsaMicros, pbkdf2Ms);
            } finally {
                Arrays.fill(pin, '\0');
            }
        }, (outcome, mainBlockedMs, totalMs) -> {
            pinSubmitInProgress = false;
            if (outcome.success && outcome.profileLoadResult != null) {
                lastPbkdf2Ms = outcome.pbkdf2Ms;
                updatePerfFooter();
                dialog.dismiss();
                openStudentMainScreen(totalMs, false);
            } else {
                CardResponse resp = outcome.pinResponse;
                renderPinDots(dotsContainer, 0);
                showPinCryptoInfo(tvPinCryptoInfo, outcome.rsaVerifyMicros > 0
                        ? String.format(Locale.US,
                                "§1 RSA-1024 verify: %.2f ms • §5 PIN đã zeroize",
                                outcome.rsaVerifyMicros / 1000.0)
                        : "§5 PIN đã zeroize (char[])");
                if (resp != null && resp.isCardLocked()) {
                    dialog.dismiss();
                    showMainFeedback("Thẻ đã bị khóa do nhập sai PIN 3 lần. Vui lòng mở khóa bằng PUK ở mục Quản trị.");
                } else if (resp != null) {
                    int tries = resp.getRemainingPinTries();
                    tvError.setText("Mã PIN không đúng • Còn " + tries + " lần thử");
                } else {
                    tvError.setText("Không đọc được phản hồi từ thẻ.");
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
        final long rsaVerifyMicros;
        final long pbkdf2Ms;

        LoginAttemptOutcome(boolean success, CardResponse pinResponse,
                            LocalCacheManager.ProfileLoadResult profileLoadResult,
                            long rsaVerifyMicros, long pbkdf2Ms) {
            this.success = success;
            this.pinResponse = pinResponse;
            this.profileLoadResult = profileLoadResult;
            this.rsaVerifyMicros = rsaVerifyMicros;
            this.pbkdf2Ms = pbkdf2Ms;
        }
    }
}
