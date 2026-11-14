package com.example.smartstudentid.ui.student;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.optimization.ImageOptimizer;
import com.example.smartstudentid.optimization.LocalCacheManager;
import com.example.smartstudentid.optimization.OptimizationConfig;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Tab 1 Fragment: Displays the Apple Wallet / VNeID style Digital Student Card,
 * WebP portrait avatar, academic status metrics, and dialogs for updating profile or PIN.
 */
public class CardProfileFragment extends Fragment {

    private static final String CAMERA_CAPTURE_FILE = "avatar_capture.jpg";

    private ImageView ivStudentAvatar;
    private TextView tvCardFullName;
    private TextView tvCardStudentId;
    private TextView tvCardDepartment;
    private TextView tvAvatarMetaBadge;
    private TextView tvMetricScore;
    private TextView tvMetricAttendance;
    private TextView tvMetricLabAccess;
    private TextView tvProfileSyncStatus;

    private LocalCacheManager localCacheManager;
    private EncryptedCacheManager encryptedCacheManager;
    private PinSessionManager pinSessionManager;

    private ActivityResultLauncher<PickVisualMediaRequest> pickAvatarLauncher;
    private ActivityResultLauncher<Uri> takePhotoLauncher;
    private Uri pendingCameraUri;
    private boolean avatarUploadInProgress;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android Photo Picker: no storage permission required on any supported API level.
        pickAvatarLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) applyPickedAvatar(uri);
                });
        takePhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (Boolean.TRUE.equals(success) && pendingCameraUri != null) {
                        applyPickedAvatar(pendingCameraUri);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_card_profile, container, false);
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        localCacheManager = new LocalCacheManager(requireContext());
        encryptedCacheManager = EncryptedCacheManager.getInstance(requireContext());
        pinSessionManager = new PinSessionManager(requireContext());
        view.setVerticalScrollBarEnabled(false);

        ivStudentAvatar = view.findViewById(R.id.ivStudentAvatar);
        tvCardFullName = view.findViewById(R.id.tvCardFullName);
        tvCardStudentId = view.findViewById(R.id.tvCardStudentId);
        tvCardDepartment = view.findViewById(R.id.tvCardDepartment);
        tvAvatarMetaBadge = view.findViewById(R.id.tvAvatarMetaBadge);
        if (tvAvatarMetaBadge != null) tvAvatarMetaBadge.setVisibility(View.GONE);
        tvMetricScore = view.findViewById(R.id.tvMetricScore);
        tvMetricAttendance = view.findViewById(R.id.tvMetricAttendance);
        tvMetricLabAccess = view.findViewById(R.id.tvMetricLabAccess);
        tvProfileSyncStatus = view.findViewById(R.id.tvProfileSyncStatus);

        MaterialButton btnReloadFromChip = view.findViewById(R.id.btnReloadFromChip);
        View btnOpenEditProfileDialog = view.findViewById(R.id.btnOpenEditProfileDialog);
        View btnOpenChangePinDialog = view.findViewById(R.id.btnOpenChangePinDialog);

        btnReloadFromChip.setOnClickListener(v -> reloadProfileAndAvatarFromCard());
        btnOpenEditProfileDialog.setOnClickListener(v -> showEditProfileDialog());
        btnOpenChangePinDialog.setOnClickListener(v -> showChangePinDialog());

        View cardAvatarContainer = view.findViewById(R.id.cardAvatarContainer);
        if (cardAvatarContainer != null) {
            cardAvatarContainer.setOnClickListener(v -> showAvatarSourceDialog());
        }
        View btnChangeAvatarRow = view.findViewById(R.id.btnChangeAvatarRow);
        if (btnChangeAvatarRow != null) {
            btnChangeAvatarRow.setOnClickListener(v -> showAvatarSourceDialog());
        }

        View btnLogoutCard = view.findViewById(R.id.btnLogoutCard);
        if (btnLogoutCard != null) {
            btnLogoutCard.setOnClickListener(v -> {
                if (getActivity() instanceof StudentMainActivity) {
                    ((StudentMainActivity) getActivity()).logoutCurrentCard();
                } else if (getActivity() != null) {
                    pinSessionManager.clearSession();
                    getActivity().finish();
                }
            });
        }

        loadInitialProfileState();
    }

    @Override
    public void onResume() {
        super.onResume();
        StudentCardRecord cached = encryptedCacheManager.getStudentProfile();
        if (cached != null) {
            bindRecordToUi(cached);
        }
    }

    private void loadInitialProfileState() {
        long cacheStartNs = System.nanoTime();
        StudentCardRecord cached = encryptedCacheManager.getStudentProfile();
        if (cached != null && cached.getStudentId() != null && !cached.getStudentId().isEmpty()) {
            bindRecordToUi(cached);
            renderAvatarFromCacheOrGenerate(cached.getFullName());
            long cacheMs = Math.max(1, (System.nanoTime() - cacheStartNs) / 1_000_000);
            // §3 demo: read-only screens reuse the encrypted cache, so no APDU is sent.
            tvProfileSyncStatus.setText(String.format(Locale.US,
                    "§3 Cache offline-first: 0 APDU • EncryptedSharedPreferences (AES-GCM) %d ms\n"
                            + "Đổi tab rồi quay lại vẫn 0 APDU; bấm \"Đồng bộ\" mới đọc thẻ.",
                    cacheMs));
        } else {
            reloadProfileAndAvatarFromCard();
        }
    }

    private void bindRecordToUi(StudentCardRecord record) {
        if (record == null) return;
        tvCardFullName.setText(record.getFullName());
        tvCardStudentId.setText("MSSV: " + record.getStudentId() + " • Lớp " + record.getClassName());
        tvCardDepartment.setText("Khoa " + record.getDepartment() + " • NS: " + record.getDob());
        tvMetricScore.setText(record.getTrainingScore() + " / 100");
        tvMetricAttendance.setText(record.getAttendanceCount() + " buổi");
        tvMetricLabAccess.setText(record.isLabAccessGranted() ? "Cho phép" : "Tạm khóa");

        if (getActivity() instanceof StudentMainActivity) {
            ((StudentMainActivity) getActivity()).refreshHeaderFromCache();
        }
    }

    private void renderAvatarFromCacheOrGenerate(String fullName) {
        byte[] cachedBytes = encryptedCacheManager.getAvatarBytes();
        if (renderAvatarBytes(cachedBytes)) {
            return;
        }
        String initials = extractInitials(fullName);
        Bitmap defaultAvatar = ImageOptimizer.generateSampleStudentAvatar(initials, 150);
        byte[] webp = ImageOptimizer.compressToOptimizedWebP(defaultAvatar);
        encryptedCacheManager.saveAvatarBytes(webp);
        ivStudentAvatar.setImageBitmap(defaultAvatar);
        tvAvatarMetaBadge.setVisibility(View.GONE);
    }

    /**
     * Renders the cached WebP avatar and updates the compression badge.
     * Returns false when there is no decodable avatar yet.
     */
    private boolean renderAvatarBytes(byte[] webpBytes) {
        if (ivStudentAvatar == null || webpBytes == null || webpBytes.length == 0) {
            return false;
        }
        Bitmap bmp = BitmapFactory.decodeByteArray(webpBytes, 0, webpBytes.length);
        if (bmp == null) {
            return false;
        }
        ivStudentAvatar.setImageBitmap(bmp);
        tvAvatarMetaBadge.setVisibility(View.GONE);
        return true;
    }

    /**
     * Asks whether the new portrait comes from the gallery or from the camera.
     */
    private void showAvatarSourceDialog() {
        String[] options = new String[]{"Chọn ảnh từ thư viện", "Chụp ảnh mới"};
        new AlertDialog.Builder(requireContext())
                .setTitle("Cập nhật ảnh chân dung")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        pickAvatarLauncher.launch(new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build());
                    } else {
                        launchCameraCapture();
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void launchCameraCapture() {
        try {
            File captureFile = new File(requireContext().getCacheDir(), CAMERA_CAPTURE_FILE);
            if (captureFile.exists() && !captureFile.delete()) {
                Toast.makeText(requireContext(), "Không thể tạo tệp ảnh tạm!", Toast.LENGTH_SHORT).show();
                return;
            }
            pendingCameraUri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", captureFile);
            takePhotoLauncher.launch(pendingCameraUri);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Không mở được camera: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Downsamples the picked portrait to 150x150, compresses it to WebP 75%, uploads it to the
     * card in 240-byte APDU chunks (INS 0x22) and refreshes the encrypted local cache.
     */
    private void applyPickedAvatar(Uri uri) {
        if (uri == null || !isAdded()) return;
        if (avatarUploadInProgress) {
            Toast.makeText(requireContext(), "Ảnh trước đang được ghi lên thẻ, vui lòng đợi!",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        avatarUploadInProgress = true;
        tvProfileSyncStatus.setText("Đang xử lý ảnh: Resize 150×150 → WebP 75% → ghi thẻ...");
        Context appContext = requireContext().getApplicationContext();

        ApduThreadPool.getInstance().execute(() -> {
            ImageOptimizer.AvatarOptimizationReport report =
                    ImageOptimizer.decodeAndCompressAvatarReport(appContext, uri);
            if (report == null) {
                return new AvatarUpdateResult(null, 0);
            }
            // Re-assert the card PIN state before writing to EEPROM (INS 0x22 requires a verified PIN).
            char[] verifiedPin = sessionPinChars();
            try {
                byte[] pinBytes = pinToBytes(verifiedPin);
                try {
                    ApduClient.getInstance().sendApdu(ApduCommands.CLA_SMART_STUDENT,
                            ApduCommands.INS_VERIFY_PIN, (byte) 0x00, (byte) 0x00, pinBytes);
                } finally {
                    Arrays.fill(pinBytes, (byte) 0);
                }
            } finally {
                Arrays.fill(verifiedPin, '\0');
            }
            int packets = ApduClient.getInstance().uploadAvatarInChunks(report.webpBytes);
            return new AvatarUpdateResult(report, packets);
        }, (result, mainBlockedMs, totalMs) -> {
            avatarUploadInProgress = false;
            if (!isAdded()) return;
            if (result.report == null) {
                tvProfileSyncStatus.setText("❌ Không đọc được ảnh đã chọn. Vui lòng thử ảnh khác.");
                Toast.makeText(requireContext(), "Không thể giải mã ảnh đã chọn!",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (result.packets < 0) {
                tvProfileSyncStatus.setText("❌ Thẻ từ chối gói ảnh (kiểm tra PIN/session).");
                Toast.makeText(requireContext(), "Ghi ảnh lên thẻ thất bại!",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            ImageOptimizer.AvatarOptimizationReport report = result.report;
            encryptedCacheManager.saveAvatarBytes(report.webpBytes);
            renderAvatarBytes(report.webpBytes);

            double webpKb = report.webpBytes.length / 1024.0;
            double pngKb = report.pngBaselineBytes / 1024.0;
            int savedPct = (int) Math.round(100.0 * (1.0 - (double) report.webpBytes.length
                    / Math.max(1, report.pngBaselineBytes)));
            double avoidedMb = report.sourceWidth * (double) report.sourceHeight * 4 / (1024 * 1024);
            tvProfileSyncStatus.setText(String.format(Locale.US,
                    "§4 Two-pass: %d×%d px → inSampleSize=%d → 150×150 (%d ms)\n"
                            + "WebP 75%%: %.1f KB (%d gói) vs PNG cùng cỡ %.1f KB • −%d%% • tránh full-decode ~%.0f MB\n"
                            + "§3 Đã lưu WebP vào cache AES-GCM.",
                    report.sourceWidth, report.sourceHeight, report.sampleSize, report.decodeMs,
                    webpKb, result.packets, pngKb, Math.max(0, savedPct), avoidedMb));
            Toast.makeText(requireContext(), "Đã cập nhật ảnh chân dung lên thẻ!",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private static class AvatarUpdateResult {
        final ImageOptimizer.AvatarOptimizationReport report;
        final int packets;

        AvatarUpdateResult(ImageOptimizer.AvatarOptimizationReport report, int packets) {
            this.report = report;
            this.packets = packets;
        }
    }

    /**
     * Reads the student profile and WebP avatar chunks from the smart card asynchronously on ApduThreadPool.
     * The result line is the live §1/§2/§3/§4 demo for the card tab.
     */
    private void reloadProfileAndAvatarFromCard() {
        tvProfileSyncStatus.setText("Đang đồng bộ...");
        ApduThreadPool.getInstance().execute(() -> {
            char[] verifiedPin = sessionPinChars();
            try {
                LocalCacheManager.ProfileLoadResult res =
                        localCacheManager.fetchStudentProfileFromCard(verifiedPin);
                byte[] downloadedAvatar = ApduClient.getInstance().downloadAvatarInChunks(
                        OptimizationConfig.isOptimizedModeEnabled()
                );
                if (downloadedAvatar == null || downloadedAvatar.length == 0) {
                    Bitmap generated = ImageOptimizer.generateSampleStudentAvatar(
                            extractInitials(res.getRecord().getFullName()), 150);
                    downloadedAvatar = ImageOptimizer.compressToOptimizedWebP(generated);
                    ApduClient.getInstance().uploadAvatarInChunks(downloadedAvatar);
                    generated.recycle();
                }
                encryptedCacheManager.saveAvatarBytes(downloadedAvatar);
                return new CardReloadPayload(res, downloadedAvatar);
            } finally {
                Arrays.fill(verifiedPin, '\0');
            }
        }, (payload, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            bindRecordToUi(payload.profileResult.getRecord());
            Bitmap bmp = BitmapFactory.decodeByteArray(payload.avatarBytes, 0, payload.avatarBytes.length);
            if (bmp != null) {
                ivStudentAvatar.setImageBitmap(bmp);
            }
            long[] stepMs = payload.profileResult.getStepMs();
            int droppedFrames = Math.round(totalMs / 16.6667f);
            String uiCost = mainBlockedMs <= 0 ? "<1 ms" : mainBlockedMs + " ms";
            String bufferMode = OptimizationConfig.isOptimizedModeEnabled()
                    ? "buffer 8 KB reuse" : "cấp phát mới mỗi gói";
            String threadLine = OptimizationConfig.isOptimizedModeEnabled()
                    ? "Main Thread giữ " + uiCost + " • nếu chạy Main Thread: " + totalMs + " ms ≈ rớt " + droppedFrames + " khung"
                    : "đang chạy trên Main Thread — UI khóa " + totalMs + " ms ≈ rớt " + droppedFrames + " khung";
            tvAvatarMetaBadge.setVisibility(View.GONE);
            tvProfileSyncStatus.setText(String.format(Locale.US,
                    "§1 Điểm nghẽn: %d lệnh = %d ms (SELECT %d · VERIFY %d · INFO %d · CIPHER %d · ACADEMIC %d ms)\n"
                            + "§2 Đa luồng: %s\n"
                            + "§3 Cache: đã ghi AES-GCM • lần sau 0 APDU\n"
                            + "§4 Ảnh: WebP %.1f KB / %d gói (240B) • %s",
                    payload.profileResult.getApduCommandsSent(), payload.profileResult.getElapsedMs(),
                    stepMs[0], stepMs[1], stepMs[2], stepMs[3], stepMs[4],
                    threadLine,
                    payload.avatarBytes.length / 1024.0,
                    ImageOptimizer.calculateApduPacketCount(payload.avatarBytes.length),
                    bufferMode));
        });
    }

    /** Session PIN as char[] for card commands; callers must wipe it after use. */
    private char[] sessionPinChars() {
        char[] pin = pinSessionManager.getTransientVerifiedPinChars();
        return (pin != null && pin.length > 0) ? pin : "123456".toCharArray();
    }

    private static byte[] pinToBytes(char[] pin) {
        byte[] bytes = new byte[pin.length];
        for (int i = 0; i < pin.length; i++) {
            bytes[i] = (byte) pin[i];
        }
        return bytes;
    }

    /**
     * Displays a clean Light Mode dialog to edit student profile fields and re-upload a compressed WebP avatar.
     */
    private void showEditProfileDialog() {
        StudentCardRecord current = encryptedCacheManager.getStudentProfile();
        if (current == null) {
            current = new StudentCardRecord("CT070315", "Vũ Ngọc Hải", "15/08/2003", "CT7C",
                    "An toàn Thông tin", "123456", false, 88, 12);
        }

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        TextView tvNotice = new TextView(requireContext());
        tvNotice.setText("ℹ️ Lớp và Khoa do Học viện quản lý cố định.");
        tvNotice.setTextSize(12f);
        tvNotice.setTextColor(0xFF64748B);
        tvNotice.setPadding(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));
        container.addView(tvNotice);

        EditText etName = createDialogInput("Họ và tên", current.getFullName(), true);
        EditText etDob = createDialogInput("Ngày sinh (DD/MM/YYYY)", current.getDob(), true);
        EditText etClass = createDialogInput("Lớp (Cố định)", current.getClassName(), false);
        EditText etDept = createDialogInput("Khoa (Cố định)", current.getDepartment(), false);

        container.addView(etName);
        container.addView(etDob);
        container.addView(etClass);
        container.addView(etDept);

        StudentCardRecord finalCurrent = current;
        new AlertDialog.Builder(requireContext())
                .setTitle("Cập nhật Hồ sơ cá nhân")
                .setView(container)
                .setPositiveButton("Ghi thẻ", (dialog, which) -> {
                    String inputName = etName.getText().toString().trim();
                    if (!inputName.isEmpty()) {
                        finalCurrent.setFullName(inputName);
                    }
                    String inputDob = etDob.getText().toString().trim();
                    if (!inputDob.isEmpty()) {
                        finalCurrent.setDob(inputDob);
                    }
                    saveUpdatedProfileToCard(finalCurrent);
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void saveUpdatedProfileToCard(StudentCardRecord updated) {
        tvProfileSyncStatus.setText("Đang lưu lên chip...");
        ApduThreadPool.getInstance().execute(() -> {
            ApduClient client = ApduClient.getInstance();
            byte[] utf8 = updated.toPipeDelimitedString().getBytes(StandardCharsets.UTF_8);
            client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_SET_STUDENT_INFO,
                    (byte) 0x00, (byte) 0x00, utf8);

            // Keep the portrait the user already chose; only create the default one when none exists.
            byte[] imageBytes = encryptedCacheManager.getAvatarBytes();
            int packets = 0;
            if (imageBytes == null || imageBytes.length == 0) {
                Bitmap rawBmp = ImageOptimizer.generateSampleStudentAvatar(
                        extractInitials(updated.getFullName()), 150);
                imageBytes = ImageOptimizer.compressToOptimizedWebP(rawBmp);
                rawBmp.recycle();
                packets = client.uploadAvatarInChunks(imageBytes);
            }

            encryptedCacheManager.saveStudentProfile(updated);
            encryptedCacheManager.saveAvatarBytes(imageBytes);
            return packets;
        }, (packets, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            bindRecordToUi(updated);
            renderAvatarFromCacheOrGenerate(updated.getFullName());
            tvProfileSyncStatus.setText("✓ Đã cập nhật hồ sơ trong " + totalMs + " ms.");
            Toast.makeText(requireContext(), "Đã cập nhật hồ sơ lên chip thành công!", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Displays a clean Light Mode dialog to change the 6-digit OwnerPIN on the smart card (INS 0x11).
     */
    private void showChangePinDialog() {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etOldPin = createDialogInput("Mã PIN hiện tại (6 số)", "", true);
        etOldPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        EditText etNewPin = createDialogInput("Mã PIN mới (6 số)", "", true);
        etNewPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        container.addView(etOldPin);
        container.addView(etNewPin);

        new AlertDialog.Builder(requireContext())
                .setTitle("Đổi mã PIN thẻ sinh viên")
                .setView(container)
                .setPositiveButton("Đổi PIN", (dialog, which) -> {
                    String oldPin = etOldPin.getText().toString().trim();
                    String newPin = etNewPin.getText().toString().trim();
                    if (oldPin.length() < 4 || newPin.length() < 4) {
                        Toast.makeText(requireContext(), "Mã PIN phải từ 4 đến 8 chữ số!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    executeChangePinOnCard(oldPin, newPin);
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void executeChangePinOnCard(String oldPin, String newPin) {
        ApduThreadPool.getInstance().execute(() -> {
            byte[] oldBytes = oldPin.getBytes(StandardCharsets.UTF_8);
            byte[] newBytes = newPin.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + oldBytes.length + newBytes.length];
            payload[0] = (byte) oldBytes.length;
            System.arraycopy(oldBytes, 0, payload, 1, oldBytes.length);
            payload[1 + oldBytes.length] = (byte) newBytes.length;
            System.arraycopy(newBytes, 0, payload, 2 + oldBytes.length, newBytes.length);

            return ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_CHANGE_PIN,
                    (byte) 0x00,
                    (byte) 0x00,
                    payload
            );
        }, (resp, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            if (resp.isSuccess()) {
                StudentCardRecord rec = encryptedCacheManager.getStudentProfile();
                if (rec != null) {
                    rec.setDefaultPin(newPin);
                    encryptedCacheManager.saveStudentProfile(rec);
                    pinSessionManager.createPinSession(rec.getStudentId(), rec.getFullName(),
                            newPin.toCharArray());
                }
                tvProfileSyncStatus.setText("✓ Đã đổi mã PIN.");
                Toast.makeText(requireContext(), "Đổi mã PIN thành công!", Toast.LENGTH_SHORT).show();
            } else {
                tvProfileSyncStatus.setText("❌ Đổi mã PIN thất bại.");
                Toast.makeText(requireContext(), "Sai mã PIN hiện tại (SW=" + resp.getSwHex() + ")", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private EditText createDialogInput(String hint, String initialValue, boolean editable) {
        EditText et = new EditText(requireContext());
        et.setHint(hint);
        et.setText(initialValue);
        et.setTextSize(14f);
        if (!editable) {
            et.setEnabled(false);
            et.setFocusable(false);
            et.setTextColor(0xFF64748B);
        }
        return et;
    }

    private String extractInitials(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) return "VH";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length >= 2) {
            String first = parts[0];
            String last = parts[parts.length - 1];
            return ("" + first.charAt(0) + last.charAt(0)).toUpperCase(Locale.ROOT);
        }
        return fullName.substring(0, Math.min(2, fullName.length())).toUpperCase(Locale.ROOT);
    }

    private static class CardReloadPayload {
        final LocalCacheManager.ProfileLoadResult profileResult;
        final byte[] avatarBytes;

        CardReloadPayload(LocalCacheManager.ProfileLoadResult profileResult, byte[] avatarBytes) {
            this.profileResult = profileResult;
            this.avatarBytes = avatarBytes;
        }
    }
}
