package com.example.smartstudentid.ui.student;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.model.AttendanceCheckInResult;
import com.example.smartstudentid.model.AttendanceRecord;
import com.example.smartstudentid.model.AttendanceSessionRecord;
import com.example.smartstudentid.model.CourseAttendanceProgress;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tab 2 Fragment: Real-world class attendance workflow.
 * Fetches open sessions matching the student's semester enrollments and supports:
 * 1) Scanning the lecturer's rotating HMAC QR via CameraX (QR_SCAN)
 * 2) Entering the 6-digit backup code shown on the projector (OTP_CODE)
 * 3) Tapping the classroom card reader bound strictly to an open session (CARD_READER)
 * Every check-in executes a 4-layer server pre-check, signs a canonical 10-byte payload
 * with on-card RSA-1024 (INS 0x31, P1=0x01), and verifies all 5 security layers on BridgeServer.
 */
public class DynamicQrFragment extends Fragment {

    private static final long SESSION_POLL_INTERVAL_MS = 10_000L;

    private TextView tvQrStudentSubtitle;
    private TextView tvAttendanceServerPill;
    private LinearLayout llActiveSessions;
    private TextView tvNoActiveSessionHint;
    private TextView tvAttendanceSignResult;
    private TextView tvAttendanceSummaryPill;
    private LinearLayout llCourseProgressList;
    private TextView tvCourseProgressEmpty;

    private EncryptedCacheManager cacheManager;
    private PinSessionManager pinSessionManager;

    private final List<AttendanceSessionRecord> latestSessions = new ArrayList<>();
    private final List<AttendanceRecord> latestAttendanceHistory = new ArrayList<>();
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> qrScannerLauncher;
    private int pendingScannerSessionId = 0;
    private boolean isCheckInProcessing = false;

    private final Runnable sessionPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            loadActiveSessionsFromServer();
            pollHandler.postDelayed(this, SESSION_POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        qrScannerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (!isAdded() || result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        return;
                    }
                    Intent data = result.getData();
                    if (data.getBooleanExtra(QrScannerActivity.EXTRA_FALLBACK_OTP, false)) {
                        handleScannerOtpFallback();
                        return;
                    }
                    String qrContent = data.getStringExtra(QrScannerActivity.EXTRA_QR_CONTENT);
                    if (qrContent != null && !qrContent.trim().isEmpty()) {
                        int sessionId = pendingScannerSessionId > 0
                                ? pendingScannerSessionId
                                : parseSessionIdFromQr(qrContent.trim());
                        executeVerifiedAttendanceCheckIn("QR_SCAN", sessionId, qrContent.trim(), "");
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dynamic_qr, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cacheManager = EncryptedCacheManager.getInstance(requireContext());
        pinSessionManager = new PinSessionManager(requireContext());
        view.setVerticalScrollBarEnabled(false);

        tvQrStudentSubtitle = view.findViewById(R.id.tvQrStudentSubtitle);
        tvAttendanceServerPill = view.findViewById(R.id.tvAttendanceServerPill);
        llActiveSessions = view.findViewById(R.id.llActiveSessions);
        tvNoActiveSessionHint = view.findViewById(R.id.tvNoActiveSessionHint);
        tvAttendanceSignResult = view.findViewById(R.id.tvAttendanceSignResult);
        tvAttendanceSummaryPill = view.findViewById(R.id.tvAttendanceSummaryPill);
        llCourseProgressList = view.findViewById(R.id.llCourseProgressList);
        tvCourseProgressEmpty = view.findViewById(R.id.tvCourseProgressEmpty);

        MaterialButton btnRefreshSessions = view.findViewById(R.id.btnRefreshSessions);
        btnRefreshSessions.setOnClickListener(v -> refreshAllAttendanceData());

        MaterialButton btnScanClassQr = view.findViewById(R.id.btnScanClassQr);
        btnScanClassQr.setOnClickListener(v -> launchQrScannerForSession(0));

        refreshStudentSubtitle();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStudentSubtitle();
        refreshAllAttendanceData();
        pollHandler.removeCallbacks(sessionPollRunnable);
        pollHandler.postDelayed(sessionPollRunnable, SESSION_POLL_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        pollHandler.removeCallbacks(sessionPollRunnable);
        super.onPause();
    }

    private void refreshStudentSubtitle() {
        StudentCardRecord profile = cacheManager.getStudentProfile();
        if (profile != null && profile.getStudentId() != null) {
            tvQrStudentSubtitle.setText(profile.getFullName() + " • "
                    + profile.getStudentId() + " • Lớp " + profile.getClassName());
        }
    }

    private String getCurrentStudentId() {
        StudentCardRecord profile = cacheManager.getStudentProfile();
        return (profile != null && profile.getStudentId() != null && !profile.getStudentId().isEmpty())
                ? profile.getStudentId() : "CT070315";
    }

    private void refreshAllAttendanceData() {
        loadActiveSessionsFromServer();
        renderAttendanceHistory();
    }

    private void launchQrScannerForSession(int sessionId) {
        pendingScannerSessionId = sessionId;
        Intent intent = new Intent(requireContext(), QrScannerActivity.class);
        qrScannerLauncher.launch(intent);
    }

    private int parseSessionIdFromQr(String qrContent) {
        try {
            String[] parts = qrContent.split("\\|", -1);
            if (parts.length >= 2 && "ATT".equals(parts[0])) {
                return Integer.parseInt(parts[1].trim());
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void handleScannerOtpFallback() {
        AttendanceSessionRecord target = null;
        if (pendingScannerSessionId > 0) {
            for (AttendanceSessionRecord s : latestSessions) {
                if (s.getSessionId() == pendingScannerSessionId) {
                    target = s;
                    break;
                }
            }
        }
        if (target == null) {
            for (AttendanceSessionRecord s : latestSessions) {
                if (!s.isAlreadyCheckedIn()) {
                    target = s;
                    break;
                }
            }
        }
        if (target == null) {
            Toast.makeText(requireContext(),
                    "Hiện không có ca học nào đang mở để nhập mã 6 số.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        showOtpInputDialog(target);
    }

    private void loadActiveSessionsFromServer() {
        final String studentId = getCurrentStudentId();
        ApduThreadPool.getInstance().execute(
                () -> ApduClient.getInstance().fetchActiveAttendanceSessions(studentId),
                (payload, mainBlockedMs, totalMs) -> {
                    if (!isAdded()) return;
                    renderActiveSessionsPayload(payload);
                    renderCourseProgressList(payload);
                }
        );
    }

    private void renderActiveSessionsPayload(ApduClient.ActiveSessionsPayload payload) {
        latestSessions.clear();
        llActiveSessions.removeAllViews();

        if (payload == null || !payload.serverReachable) {
            tvAttendanceServerPill.setText("● Ngoại tuyến");
            tvAttendanceServerPill.setBackgroundResource(R.drawable.bg_pill_red);
            tvAttendanceServerPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_600));
            tvNoActiveSessionHint.setVisibility(View.VISIBLE);
            tvNoActiveSessionHint.setText(
                    "Không kết nối được BridgeServer (cổng 8080).\n"
                            + "Điểm danh lớp học yêu cầu nối mạng LAN phòng học hoặc cáp USB adb reverse."
            );
            return;
        }

        tvAttendanceServerPill.setText("● Sẵn sàng");
        tvAttendanceServerPill.setBackgroundResource(R.drawable.bg_pill_green);
        tvAttendanceServerPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_700));

        if (payload.activeSessions.isEmpty()) {
            tvNoActiveSessionHint.setVisibility(View.VISIBLE);
            tvNoActiveSessionHint.setText("Hiện chưa có ca điểm danh nào đang mở.");
            return;
        }

        tvNoActiveSessionHint.setVisibility(View.GONE);
        latestSessions.addAll(payload.activeSessions);
        for (AttendanceSessionRecord session : payload.activeSessions) {
            llActiveSessions.addView(buildSessionCardView(session));
        }
    }

    private View buildSessionCardView(AttendanceSessionRecord session) {
        float density = getResources().getDisplayMetrics().density;

        MaterialCardView card = new MaterialCardView(requireContext());
        card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.slate_50));
        card.setRadius(16f * density);
        card.setCardElevation(0f);
        card.setStrokeColor(ContextCompat.getColor(requireContext(),
                session.isAlreadyCheckedIn() ? R.color.emerald_500 : R.color.slate_200));
        card.setStrokeWidth(Math.round(1f * density));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = Math.round(10f * density);
        card.setLayoutParams(cardLp);

        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14f * density);
        inner.setPadding(pad, pad, pad, pad);

        // Top row: Course title + Remaining minutes pill
        LinearLayout topRow = new LinearLayout(requireContext());
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvCourse = new TextView(requireContext());
        tvCourse.setText(session.getCourseCode() + " - " + session.getCourseName());
        tvCourse.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_900));
        tvCourse.setTextSize(14f);
        tvCourse.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams courseLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        courseLp.setMarginEnd(Math.round(8f * density));
        tvCourse.setLayoutParams(courseLp);

        long minsLeft = Math.max(1L, (session.getRemainingSessionSec() + 59L) / 60L);
        TextView tvTimer = new TextView(requireContext());
        tvTimer.setText("Còn " + minsLeft + " phút");
        tvTimer.setBackgroundResource(R.drawable.bg_pill_blue);
        tvTimer.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_600));
        tvTimer.setTextSize(11f);
        tvTimer.setTypeface(null, Typeface.BOLD);
        tvTimer.setPadding(Math.round(9f * density), Math.round(4f * density),
                Math.round(9f * density), Math.round(4f * density));

        topRow.addView(tvCourse);
        topRow.addView(tvTimer);
        inner.addView(topRow);

        TextView tvMeta = new TextView(requireContext());
        tvMeta.setText(session.getRoom() + " • " + session.getLecturerName()
                + " • Phiên #" + session.getSessionId());
        tvMeta.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_500));
        tvMeta.setTextSize(12f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Math.round(4f * density);
        tvMeta.setLayoutParams(metaLp);
        inner.addView(tvMeta);

        if (session.isAlreadyCheckedIn()) {
            TextView tvDone = new TextView(requireContext());
            tvDone.setText("✓ Đã điểm danh");
            tvDone.setBackgroundResource(R.drawable.bg_pill_green);
            tvDone.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_700));
            tvDone.setTextSize(12f);
            tvDone.setTypeface(null, Typeface.BOLD);
            tvDone.setPadding(Math.round(12f * density), Math.round(7f * density),
                    Math.round(12f * density), Math.round(7f * density));
            LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            doneLp.topMargin = Math.round(10f * density);
            tvDone.setLayoutParams(doneLp);
            inner.addView(tvDone);
        } else {
            LinearLayout actionsRow = new LinearLayout(requireContext());
            actionsRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = Math.round(12f * density);
            actionsRow.setLayoutParams(rowLp);

            MaterialButton btnQr = buildActionChipButton("Quét QR", true, false);
            btnQr.setOnClickListener(v -> launchQrScannerForSession(session.getSessionId()));

            MaterialButton btnOtp = buildActionChipButton("Mã 6 số", false, false);
            btnOtp.setOnClickListener(v -> showOtpInputDialog(session));

            MaterialButton btnCard = buildActionChipButton("Đầu đọc thẻ", false, true);
            btnCard.setOnClickListener(v -> executeVerifiedAttendanceCheckIn(
                    "CARD_READER", session.getSessionId(), "", ""));

            LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(
                    0, Math.round(42f * density), 1f);
            b1Lp.setMarginEnd(Math.round(6f * density));
            btnQr.setLayoutParams(b1Lp);

            LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(
                    0, Math.round(42f * density), 1f);
            b2Lp.setMarginEnd(Math.round(6f * density));
            btnOtp.setLayoutParams(b2Lp);

            LinearLayout.LayoutParams b3Lp = new LinearLayout.LayoutParams(
                    0, Math.round(42f * density), 1.15f);
            btnCard.setLayoutParams(b3Lp);

            actionsRow.addView(btnQr);
            actionsRow.addView(btnOtp);
            actionsRow.addView(btnCard);
            inner.addView(actionsRow);
        }

        card.addView(inner);
        return card;
    }

    private MaterialButton buildActionChipButton(String text, boolean primary, boolean outlined) {
        MaterialButton btn = new MaterialButton(requireContext());
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(12f);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        btn.setMinimumHeight(0);
        btn.setMinHeight(0);
        btn.setPadding(8, 0, 8, 0);
        btn.setCornerRadius(Math.round(12f * getResources().getDisplayMetrics().density));
        if (primary) {
            btn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.brand_600)));
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        } else if (outlined) {
            btn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.white)));
            btn.setStrokeColor(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.slate_300)));
            btn.setStrokeWidth(Math.round(1f * getResources().getDisplayMetrics().density));
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_700));
        } else {
            btn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.brand_50)));
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_600));
        }
        return btn;
    }

    private void showOtpInputDialog(AttendanceSessionRecord session) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20f * density);
        container.setPadding(pad, Math.round(10f * density), pad, 0);

        TextView tvSub = new TextView(requireContext());
        tvSub.setText(session.getCourseCode() + " - " + session.getCourseName()
                + "\nNhập mã dự phòng 6 chữ số đang hiển thị trên màn chiếu giảng viên.");
        tvSub.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600));
        tvSub.setTextSize(12.5f);
        container.addView(tvSub);

        EditText etOtp = new EditText(requireContext());
        etOtp.setHint("Ví dụ: 482915");
        etOtp.setInputType(InputType.TYPE_CLASS_NUMBER);
        etOtp.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        etOtp.setBackgroundResource(R.drawable.bg_input);
        int inputPad = Math.round(12f * density);
        etOtp.setPadding(inputPad, inputPad, inputPad, inputPad);
        etOtp.setTextSize(16f);
        etOtp.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12f * density);
        etOtp.setLayoutParams(lp);
        container.addView(etOtp);

        new AlertDialog.Builder(requireContext())
                .setTitle("Điểm danh bằng mã 6 số")
                .setView(container)
                .setPositiveButton("Xác nhận", (dialog, which) -> {
                    String otp = etOtp.getText().toString().trim();
                    if (!otp.matches("\\d{6}")) {
                        Toast.makeText(requireContext(),
                                "Vui lòng nhập đủ 6 chữ số hiển thị trên màn chiếu.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    executeVerifiedAttendanceCheckIn("OTP_CODE", session.getSessionId(), "", otp);
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void executeVerifiedAttendanceCheckIn(String method, int sessionId,
                                                  String qrPayload, String otpCode) {
        if (isCheckInProcessing) {
            Toast.makeText(requireContext(), "Đang xử lý điểm danh, vui lòng đợi...", Toast.LENGTH_SHORT).show();
            return;
        }
        isCheckInProcessing = true;
        final String studentId = getCurrentStudentId();
        final String verifiedPin = pinSessionManager.getTransientVerifiedPin();

        tvAttendanceSignResult.setVisibility(View.VISIBLE);
        tvAttendanceSignResult.setBackgroundResource(R.drawable.bg_soft_panel);
        tvAttendanceSignResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600));
        tvAttendanceSignResult.setText("Đang điểm danh...");

        ApduThreadPool.getInstance().execute(() -> {
            ApduClient client = ApduClient.getInstance();

            // 1. 4-layer server pre-check before touching chip EEPROM counters
            AttendanceCheckInResult precheck = client.precheckAttendance(
                    studentId, sessionId, method, qrPayload, otpCode);
            if (!precheck.isOk()) {
                return precheck;
            }

            // 2. Re-assert card PIN state when available so INS 0x31 never hits 0x6982 after server restarts
            if (verifiedPin != null && !verifiedPin.isEmpty()) {
                CardResponse pinResp = client.sendApdu(
                        ApduCommands.CLA_SMART_STUDENT,
                        ApduCommands.INS_VERIFY_PIN,
                        (byte) 0x00,
                        (byte) 0x00,
                        verifiedPin.getBytes(StandardCharsets.UTF_8)
                );
                if (!pinResp.isSuccess()) {
                    return AttendanceCheckInResult.error("PIN_REQUIRED",
                            "Xác thực mã PIN trên thẻ thất bại. Vui lòng đăng nhập lại.");
                }
            }

            // 3. Build 10-byte canonical payload & sign on-card with RSA-1024 (INS 0x31, P1=0x01)
            long tsSec = precheck.getServerNowSec() > 0
                    ? precheck.getServerNowSec()
                    : (System.currentTimeMillis() / 1000L);
            long timeSlot = precheck.getTimeSlot();
            byte[] payload = ApduCommands.buildCanonicalAttendancePayload(
                    precheck.getSessionId(), (int) tsSec, (int) timeSlot);

            CardResponse apduResp = client.sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_BORROW_OR_ATTENDANCE,
                    ApduCommands.P1_ACTION_ATTENDANCE,
                    (byte) 0x00,
                    payload
            );
            if (!apduResp.isSuccess()) {
                return AttendanceCheckInResult.error("CARD_APDU_" + apduResp.getSwHex(),
                        "Thẻ chưa sẵn sàng để điểm danh. Vui lòng xác thực lại mã PIN.");
            }

            // 4. Submit full 128-byte RSA-1024 signature to server for 5-layer verification
            String fullSigHex = ApduCommands.bytesToHexCompact(apduResp.getData());
            return client.submitVerifiedAttendanceCheckIn(
                    studentId,
                    precheck.getSessionId(),
                    method,
                    qrPayload,
                    otpCode,
                    timeSlot,
                    tsSec,
                    fullSigHex
            );
        }, (result, mainBlockedMs, totalMs) -> {
            isCheckInProcessing = false;
            if (!isAdded()) return;
            if (result != null && result.isOk()) {
                StudentCardRecord profile = cacheManager.getStudentProfile();
                if (profile != null) {
                    if (result.getAttendanceCount() > 0) {
                        profile.setAttendanceCount(result.getAttendanceCount());
                    } else {
                        profile.setAttendanceCount(profile.getAttendanceCount() + 1);
                    }
                    if (result.getTrainingScore() > 0) {
                        profile.setTrainingScore(result.getTrainingScore());
                    } else {
                        profile.setTrainingScore(Math.min(100, profile.getTrainingScore() + 1));
                    }
                    cacheManager.saveStudentProfile(profile);
                }

                tvAttendanceSignResult.setVisibility(View.VISIBLE);
                tvAttendanceSignResult.setBackgroundResource(R.drawable.bg_pill_green);
                tvAttendanceSignResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_700));
                tvAttendanceSignResult.setText("✓ Điểm danh thành công");
                Toast.makeText(requireContext(), "Điểm danh thành công!", Toast.LENGTH_SHORT).show();
                refreshAllAttendanceData();
            } else {
                String errMsg = (result != null && result.getMessage() != null && !result.getMessage().isEmpty())
                        ? result.getMessage()
                        : "Điểm danh không thành công.";
                tvAttendanceSignResult.setVisibility(View.VISIBLE);
                tvAttendanceSignResult.setBackgroundResource(R.drawable.bg_pill_red);
                tvAttendanceSignResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_600));
                tvAttendanceSignResult.setText("❌ " + errMsg);
                Toast.makeText(requireContext(), errMsg, Toast.LENGTH_LONG).show();
                loadActiveSessionsFromServer();
            }
        });
    }

    private void renderCourseProgressList(ApduClient.ActiveSessionsPayload payload) {
        llCourseProgressList.removeAllViews();
        if (payload == null || !payload.serverReachable) {
            tvAttendanceSummaryPill.setText("Ngoại tuyến");
            tvAttendanceSummaryPill.setBackgroundResource(R.drawable.bg_pill_red);
            tvAttendanceSummaryPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_600));
            tvCourseProgressEmpty.setVisibility(View.VISIBLE);
            tvCourseProgressEmpty.setText("Chưa kết nối được máy chủ để tải tiến độ môn học.");
            return;
        }
        if (payload.enrolledCourseProgress.isEmpty()) {
            tvAttendanceSummaryPill.setText("0 môn học");
            tvAttendanceSummaryPill.setBackgroundResource(R.drawable.bg_pill_slate);
            tvAttendanceSummaryPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600));
            tvCourseProgressEmpty.setVisibility(View.VISIBLE);
            tvCourseProgressEmpty.setText("Chưa có dữ liệu đăng ký học phần.");
            return;
        }

        tvCourseProgressEmpty.setVisibility(View.GONE);
        int totalAttended = 0;
        int totalPlanned = 0;
        for (CourseAttendanceProgress course : payload.enrolledCourseProgress) {
            totalAttended += Math.max(0, course.getAttendedSessions());
            totalPlanned += Math.max(1, course.getTotalSessions());
            llCourseProgressList.addView(buildCourseProgressCard(course));
        }
        tvAttendanceSummaryPill.setText(payload.enrolledCourseProgress.size() + " môn • "
                + totalAttended + "/" + totalPlanned + " buổi");
        tvAttendanceSummaryPill.setBackgroundResource(R.drawable.bg_pill_blue);
        tvAttendanceSummaryPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_600));
    }

    private View buildCourseProgressCard(CourseAttendanceProgress course) {
        float density = getResources().getDisplayMetrics().density;
        int total = Math.max(1, course.getTotalSessions());
        int attended = Math.max(0, course.getAttendedSessions());
        int pct = Math.min(100, Math.round((attended * 100f) / total));

        MaterialCardView card = new MaterialCardView(requireContext());
        card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.slate_50));
        card.setRadius(16f * density);
        card.setCardElevation(0f);
        card.setStrokeColor(ContextCompat.getColor(requireContext(), R.color.slate_200));
        card.setStrokeWidth(Math.round(1f * density));
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = Math.round(10f * density);
        card.setLayoutParams(cardLp);
        card.setOnClickListener(v -> showCourseAttendanceDetailDialog(course));

        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14f * density);
        inner.setPadding(pad, pad, pad, pad);

        // Row 1: Course code pill (left) + Attended/Total pill (right)
        LinearLayout row1 = new LinearLayout(requireContext());
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvCode = new TextView(requireContext());
        tvCode.setText(course.getCourseCode());
        tvCode.setBackgroundResource(R.drawable.bg_pill_blue);
        tvCode.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_600));
        tvCode.setTextSize(11f);
        tvCode.setTypeface(null, Typeface.BOLD);
        tvCode.setPadding(Math.round(9f * density), Math.round(4f * density),
                Math.round(9f * density), Math.round(4f * density));

        View row1Spacer = new View(requireContext());
        row1Spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));

        TextView tvRatio = new TextView(requireContext());
        tvRatio.setText(attended + " / " + total + " buổi");
        tvRatio.setTextSize(11f);
        tvRatio.setTypeface(null, Typeface.BOLD);
        tvRatio.setPadding(Math.round(10f * density), Math.round(4f * density),
                Math.round(10f * density), Math.round(4f * density));
        if (pct >= 80) {
            tvRatio.setBackgroundResource(R.drawable.bg_pill_green);
            tvRatio.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_700));
        } else if (attended > 0) {
            tvRatio.setBackgroundResource(R.drawable.bg_pill_blue);
            tvRatio.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_600));
        } else {
            tvRatio.setBackgroundResource(R.drawable.bg_pill_slate);
            tvRatio.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600));
        }

        row1.addView(tvCode);
        row1.addView(row1Spacer);
        row1.addView(tvRatio);
        inner.addView(row1);

        // Row 2: Full-width Course Name
        TextView tvName = new TextView(requireContext());
        tvName.setText(course.getCourseName());
        tvName.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_900));
        tvName.setTextSize(14.5f);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setMaxLines(2);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Math.round(8f * density);
        tvName.setLayoutParams(nameLp);
        inner.addView(tvName);

        // Row 3: Lecturer + Room
        TextView tvMeta = new TextView(requireContext());
        tvMeta.setText(course.getLecturerName() + " • " + course.getRoom());
        tvMeta.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_500));
        tvMeta.setTextSize(11.5f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Math.round(3f * density);
        tvMeta.setLayoutParams(metaLp);
        inner.addView(tvMeta);

        // Row 3: ProgressBar
        ProgressBar pb = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(total);
        pb.setProgress(Math.min(attended, total));
        pb.setProgressDrawable(ContextCompat.getDrawable(requireContext(),
                pct >= 80 ? R.drawable.progress_metric_green : R.drawable.progress_metric_blue));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(8f * density));
        pbLp.topMargin = Math.round(8f * density);
        pb.setLayoutParams(pbLp);
        inner.addView(pb);

        // Row 4: Percentage + Latest check-in
        LinearLayout row4 = new LinearLayout(requireContext());
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row4Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row4Lp.topMargin = Math.round(6f * density);
        row4.setLayoutParams(row4Lp);

        TextView tvPct = new TextView(requireContext());
        tvPct.setText(attended > 0 ? (pct + "% hoàn thành") : "Chưa điểm danh buổi nào");
        tvPct.setTextSize(11.5f);
        tvPct.setTypeface(null, attended > 0 ? Typeface.BOLD : Typeface.NORMAL);
        tvPct.setTextColor(ContextCompat.getColor(requireContext(),
                pct >= 80 ? R.color.emerald_700 : (attended > 0 ? R.color.brand_600 : R.color.slate_500)));
        tvPct.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvLatest = new TextView(requireContext());
        if (course.getLastAttendedAt() > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
            tvLatest.setText("Gần nhất: " + sdf.format(new Date(course.getLastAttendedAt() * 1000L)) + " ›");
        } else {
            tvLatest.setText("Xem chi tiết ›");
        }
        tvLatest.setTextSize(11.5f);
        tvLatest.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_500));

        row4.addView(tvPct);
        row4.addView(tvLatest);
        inner.addView(row4);

        card.addView(inner);
        return card;
    }

    private void showCourseAttendanceDetailDialog(CourseAttendanceProgress course) {
        if (!isAdded()) return;
        float density = getResources().getDisplayMetrics().density;
        int total = Math.max(1, course.getTotalSessions());
        int attended = Math.max(0, course.getAttendedSessions());
        int remaining = Math.max(0, total - attended);
        int pct = Math.min(100, Math.round((attended * 100f) / total));

        View popupView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_course_attendance_detail, null, false);
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(popupView);
        dialog.setCancelable(true);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.round(getResources().getDisplayMetrics().widthPixels * 0.92f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvCode = popupView.findViewById(R.id.tvDetailCourseCode);
        TextView tvSemester = popupView.findViewById(R.id.tvDetailSemester);
        TextView tvCourseName = popupView.findViewById(R.id.tvDetailCourseName);
        TextView tvRatioBig = popupView.findViewById(R.id.tvDetailRatioBig);
        TextView tvPctPill = popupView.findViewById(R.id.tvDetailPctPill);
        ProgressBar pbProgress = popupView.findViewById(R.id.pbDetailProgress);
        TextView tvRemaining = popupView.findViewById(R.id.tvDetailRemaining);
        TextView tvLecturer = popupView.findViewById(R.id.tvDetailLecturer);
        TextView tvRoom = popupView.findViewById(R.id.tvDetailRoom);
        TextView tvSchedule = popupView.findViewById(R.id.tvDetailSchedule);
        TextView tvSessionCountPill = popupView.findViewById(R.id.tvDetailSessionCountPill);
        ScrollView svSessions = popupView.findViewById(R.id.svDetailSessions);
        LinearLayout llSessionsList = popupView.findViewById(R.id.llDetailSessionsList);
        LinearLayout llEmptyState = popupView.findViewById(R.id.llDetailEmptyState);

        tvCode.setText(course.getCourseCode());
        tvSemester.setText(course.getSemester() != null && !course.getSemester().isEmpty()
                ? course.getSemester() : "HK1 2026-2027");
        tvCourseName.setText(course.getCourseName());
        tvRatioBig.setText(attended + " / " + total + " buổi");
        tvPctPill.setText(pct + "%");

        if (pct >= 80) {
            tvRatioBig.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_600));
            tvPctPill.setBackgroundResource(R.drawable.bg_pill_green);
            tvPctPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_700));
            pbProgress.setProgressDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.progress_metric_green));
        } else if (attended > 0) {
            tvRatioBig.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_600));
            tvPctPill.setBackgroundResource(R.drawable.bg_pill_blue);
            tvPctPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_600));
            pbProgress.setProgressDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.progress_metric_blue));
        } else {
            tvRatioBig.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_700));
            tvPctPill.setBackgroundResource(R.drawable.bg_pill_slate);
            tvPctPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600));
            pbProgress.setProgressDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.progress_metric_blue));
        }
        pbProgress.setMax(total);
        pbProgress.setProgress(Math.min(attended, total));

        tvRemaining.setText(remaining > 0
                ? ("Còn lại " + remaining + " buổi trong học kỳ")
                : "Đã hoàn thành đủ số buổi học phần");
        tvLecturer.setText(course.getLecturerName() != null ? course.getLecturerName() : "-");
        tvRoom.setText(course.getRoom() != null ? course.getRoom() : "-");
        tvSchedule.setText(course.getScheduleText() != null && !course.getScheduleText().isEmpty()
                ? course.getScheduleText() : "Theo thời khóa biểu học kỳ");

        List<AttendanceRecord> matched = new ArrayList<>();
        for (AttendanceRecord rec : latestAttendanceHistory) {
            if (course.getCourseCode() != null && course.getCourseCode().equalsIgnoreCase(rec.getCourseCode())) {
                matched.add(rec);
            }
        }

        tvSessionCountPill.setText(matched.size() + " buổi");
        if (matched.isEmpty()) {
            tvSessionCountPill.setBackgroundResource(R.drawable.bg_pill_slate);
            tvSessionCountPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600));
            svSessions.setVisibility(View.GONE);
            llEmptyState.setVisibility(View.VISIBLE);
        } else {
            tvSessionCountPill.setBackgroundResource(R.drawable.bg_pill_green);
            tvSessionCountPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_700));
            llEmptyState.setVisibility(View.GONE);
            svSessions.setVisibility(View.VISIBLE);

            SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm", Locale.getDefault());
            for (int i = 0; i < matched.size(); i++) {
                AttendanceRecord rec = matched.get(i);
                int sessionOrder = matched.size() - i;
                String dateLabel = formatVietnameseWeekdayDate(rec.getTimestampSeconds());
                String timeStr = sdfTime.format(new Date(rec.getTimestampSeconds() * 1000L));
                llSessionsList.addView(buildDetailSessionRow(
                        dateLabel,
                        "Buổi " + sessionOrder + " • Lúc " + timeStr,
                        i < matched.size() - 1,
                        density
                ));
            }
            if (matched.size() > 4) {
                ViewGroup.LayoutParams svLp = svSessions.getLayoutParams();
                svLp.height = Math.round(230f * density);
                svSessions.setLayoutParams(svLp);
            }
        }

        popupView.findViewById(R.id.btnDetailCloseIcon).setOnClickListener(v -> dialog.dismiss());
        popupView.findViewById(R.id.btnDetailClose).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private View buildDetailSessionRow(String dateTitle, String timeSubtitle,
                                         boolean addBottomMargin, float density) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_soft_panel);
        row.setPadding(Math.round(12f * density), Math.round(10f * density),
                Math.round(12f * density), Math.round(10f * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (addBottomMargin) {
            lp.bottomMargin = Math.round(8f * density);
        }
        row.setLayoutParams(lp);

        LinearLayout iconTile = new LinearLayout(requireContext());
        int tileSize = Math.round(34f * density);
        iconTile.setLayoutParams(new LinearLayout.LayoutParams(tileSize, tileSize));
        iconTile.setBackgroundResource(R.drawable.bg_icon_tile_green);
        iconTile.setGravity(Gravity.CENTER);

        ImageView iv = new ImageView(requireContext());
        int iconSize = Math.round(17f * density);
        iv.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        iv.setImageResource(R.drawable.ic_verified);
        iv.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.emerald_600)));
        iconTile.addView(iv);

        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(Math.round(10f * density));
        textLp.setMarginEnd(Math.round(8f * density));
        textCol.setLayoutParams(textLp);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(dateTitle);
        tvTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_900));
        tvTitle.setTextSize(13f);
        tvTitle.setTypeface(null, Typeface.BOLD);

        TextView tvSub = new TextView(requireContext());
        tvSub.setText(timeSubtitle);
        tvSub.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_500));
        tvSub.setTextSize(11.5f);

        textCol.addView(tvTitle);
        textCol.addView(tvSub);

        TextView tvBadge = new TextView(requireContext());
        tvBadge.setText("Có mặt");
        tvBadge.setBackgroundResource(R.drawable.bg_pill_green);
        tvBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.emerald_700));
        tvBadge.setTextSize(11f);
        tvBadge.setTypeface(null, Typeface.BOLD);
        tvBadge.setPadding(Math.round(9f * density), Math.round(4f * density),
                Math.round(9f * density), Math.round(4f * density));

        row.addView(iconTile);
        row.addView(textCol);
        row.addView(tvBadge);
        return row;
    }

    private String formatVietnameseWeekdayDate(long timestampSeconds) {
        Date date = new Date(timestampSeconds * 1000L);
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        String dayLabel;
        switch (dow) {
            case Calendar.SUNDAY:
                dayLabel = "Chủ nhật";
                break;
            case Calendar.MONDAY:
                dayLabel = "Thứ 2";
                break;
            case Calendar.TUESDAY:
                dayLabel = "Thứ 3";
                break;
            case Calendar.WEDNESDAY:
                dayLabel = "Thứ 4";
                break;
            case Calendar.THURSDAY:
                dayLabel = "Thứ 5";
                break;
            case Calendar.FRIDAY:
                dayLabel = "Thứ 6";
                break;
            default:
                dayLabel = "Thứ 7";
                break;
        }
        SimpleDateFormat sdfDate = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
        return dayLabel + ", " + sdfDate.format(date);
    }

    private void renderAttendanceHistory() {
        final String studentId = getCurrentStudentId();
        ApduThreadPool.getInstance().execute(
                () -> ApduClient.getInstance().fetchAttendanceByStudentFromServerDb(studentId),
                (history, mainBlockedMs, totalMs) -> {
                    if (!isAdded()) return;
                    latestAttendanceHistory.clear();
                    if (history != null) {
                        latestAttendanceHistory.addAll(history);
                    }
                }
        );
    }
}
