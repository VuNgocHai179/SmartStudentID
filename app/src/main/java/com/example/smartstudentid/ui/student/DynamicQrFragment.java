package com.example.smartstudentid.ui.student;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.model.AttendanceRecord;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tab 2 Fragment: Generates a Dynamic 30-Second Anti-Replay QR Code (SEC-05)
 * and executes RSA-1024 signed class attendance check-ins (INS 0x31, P1=0x01 - CN 5.1).
 */
public class DynamicQrFragment extends Fragment {

    private TextView tvQrStudentSubtitle;
    private ImageView ivDynamicQr;
    private ProgressBar pbQrCountdown;
    private TextView tvQrCountdown;
    private EditText etAttendanceCourse;
    private TextView tvAttendanceSignResult;
    private TextView tvAttendanceHistoryList;

    private EncryptedCacheManager cacheManager;
    private final Handler qrTimerHandler = new Handler(Looper.getMainLooper());
    private long currentQrWindowEpoch = -1;

    private final Runnable qrTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            long nowSec = System.currentTimeMillis() / 1000L;
            long windowEpoch = nowSec / 30L;
            int remainingSec = (int) (30L - (nowSec % 30L));

            pbQrCountdown.setProgress(remainingSec);
            tvQrCountdown.setText("Đổi mã sau " + remainingSec + "s");

            if (windowEpoch != currentQrWindowEpoch) {
                currentQrWindowEpoch = windowEpoch;
                refreshDynamicQrBitmap(windowEpoch);
            }
            qrTimerHandler.postDelayed(this, 1000L);
        }
    };

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
        view.setVerticalScrollBarEnabled(false);

        tvQrStudentSubtitle = view.findViewById(R.id.tvQrStudentSubtitle);
        ivDynamicQr = view.findViewById(R.id.ivDynamicQr);
        pbQrCountdown = view.findViewById(R.id.pbQrCountdown);
        tvQrCountdown = view.findViewById(R.id.tvQrCountdown);
        etAttendanceCourse = view.findViewById(R.id.etAttendanceCourse);
        tvAttendanceSignResult = view.findViewById(R.id.tvAttendanceSignResult);
        tvAttendanceHistoryList = view.findViewById(R.id.tvAttendanceHistoryList);

        MaterialButton btnSignAttendance = view.findViewById(R.id.btnSignAttendance);
        btnSignAttendance.setOnClickListener(v -> performRsaAttendanceCheckIn());

        refreshStudentSubtitleAndHistory();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStudentSubtitleAndHistory();
        currentQrWindowEpoch = -1;
        qrTimerHandler.removeCallbacks(qrTickRunnable);
        qrTimerHandler.post(qrTickRunnable);
    }

    @Override
    public void onPause() {
        qrTimerHandler.removeCallbacks(qrTickRunnable);
        super.onPause();
    }

    private void refreshStudentSubtitleAndHistory() {
        StudentCardRecord profile = cacheManager.getStudentProfile();
        if (profile != null && profile.getStudentId() != null) {
            tvQrStudentSubtitle.setText(profile.getFullName() + " • "
                    + profile.getStudentId() + " • Lớp " + profile.getClassName());
        }
        renderAttendanceHistory();
    }

    /**
     * Generates a fresh 30-second dynamic QR bitmap on the background thread pool.
     */
    private void refreshDynamicQrBitmap(long windowEpoch) {
        StudentCardRecord profile = cacheManager.getStudentProfile();
        String studentId = (profile != null && profile.getStudentId() != null)
                ? profile.getStudentId() : "CT070315";
        String className = (profile != null && profile.getClassName() != null)
                ? profile.getClassName() : "CT7C";

        ApduThreadPool.getInstance().execute(() -> {
            // Build 6-byte challenge payload for the 30s time window
            byte[] payload = new byte[]{
                    0x01, 0x01,
                    (byte) ((windowEpoch >> 24) & 0xFF),
                    (byte) ((windowEpoch >> 16) & 0xFF),
                    (byte) ((windowEpoch >> 8) & 0xFF),
                    (byte) (windowEpoch & 0xFF)
            };
            CardResponse sigResp = ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_SIGN_CHALLENGE,
                    (byte) 0x00,
                    (byte) 0x00,
                    payload
            );
            String sigHex = ApduCommands.bytesToHex(sigResp.getData()).replace(" ", "");
            String sigPrefix = sigHex.length() > 16 ? sigHex.substring(0, 16) : "RSA1024SIG";
            String qrContent = "SMARTID|" + studentId + "|" + className + "|W" + windowEpoch + "|" + sigPrefix;
            return encodeQrBitmap(qrContent, 320);
        }, (bitmap, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            if (bitmap != null) {
                ivDynamicQr.setImageBitmap(bitmap);
            }
        });
    }

    /**
     * Sends INS 0x31 (P1=0x01) to the smart card to sign a class attendance transaction with RSA-1024.
     */
    private void performRsaAttendanceCheckIn() {
        String courseInput = etAttendanceCourse.getText().toString().trim();
        if (courseInput.isEmpty()) {
            courseInput = "AT19N01 - Phát triển PM Thẻ thông minh";
        }
        final String finalCourse = courseInput;
        tvAttendanceSignResult.setText("Đang điểm danh...");

        final long[] signedTsHolder = new long[1];
        ApduThreadPool.getInstance().execute(() -> {
            long ts = System.currentTimeMillis() / 1000L;
            signedTsHolder[0] = ts;
            byte[] data = new byte[]{
                    0x01, 0x01,
                    (byte) ((ts >> 24) & 0xFF),
                    (byte) ((ts >> 16) & 0xFF),
                    (byte) ((ts >> 8) & 0xFF),
                    (byte) (ts & 0xFF)
            };
            return ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_BORROW_OR_ATTENDANCE,
                    ApduCommands.P1_ACTION_ATTENDANCE,
                    (byte) 0x00,
                    data
            );
        }, (resp, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            if (resp.isSuccess()) {
                StudentCardRecord profile = cacheManager.getStudentProfile();
                String studentId = "CT070315";
                if (profile != null) {
                    studentId = profile.getStudentId();
                    profile.setAttendanceCount(profile.getAttendanceCount() + 1);
                    profile.setTrainingScore(Math.min(100, profile.getTrainingScore() + 1));
                    cacheManager.saveStudentProfile(profile);
                }

                String fullSigHex = ApduCommands.bytesToHex(resp.getData()).replace(" ", "");

                AttendanceRecord newRecord = new AttendanceRecord(
                        studentId,
                        "AT19N01",
                        finalCourse,
                        signedTsHolder[0],
                        fullSigHex,
                        false
                );

                ApduThreadPool.getInstance().execute(() -> {
                    ApduClient.getInstance().syncAttendanceToServerDb(newRecord);
                    return true;
                }, (ok, bMs, tMs) -> renderAttendanceHistory());

                tvAttendanceSignResult.setText("✓ Điểm danh thành công");
                Toast.makeText(requireContext(), "Đã ghi nhận điểm danh thành công!", Toast.LENGTH_SHORT).show();
            } else {
                tvAttendanceSignResult.setText("❌ Điểm danh thất bại.");
            }
        });
    }

    private void renderAttendanceHistory() {
        StudentCardRecord profile = cacheManager.getStudentProfile();
        final String studentId = (profile != null && profile.getStudentId() != null)
                ? profile.getStudentId() : "CT070315";

        ApduThreadPool.getInstance().execute(
                () -> ApduClient.getInstance().fetchAttendanceByStudentFromServerDb(studentId),
                (history, mainBlockedMs, totalMs) -> {
                    if (!isAdded()) return;
                    if (history == null || history.isEmpty()) {
                        tvAttendanceHistoryList.setText("• Chưa có lịch sử điểm danh.");
                        return;
                    }
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
                    StringBuilder sb = new StringBuilder();
                    int limit = Math.min(5, history.size());
                    for (int i = 0; i < limit; i++) {
                        AttendanceRecord item = history.get(i);
                        String timeStr = sdf.format(new Date(item.getTimestampSeconds() * 1000L));
                        sb.append("• ").append(timeStr).append(" — ").append(item.getCourseName()).append("\n");
                    }
                    tvAttendanceHistoryList.setText(sb.toString().trim());
                }
        );
    }

    private Bitmap encodeQrBitmap(String content, int sizePx) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            int darkColor = Color.parseColor("#0F172A");
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? darkColor : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, width, 0, 0, width, height);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
