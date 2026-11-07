package com.example.smartstudentid.ui.student;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Tab 1 Fragment: Displays the Apple Wallet / VNeID style Digital Student Card,
 * WebP portrait avatar, academic status metrics, and dialogs for updating profile or PIN.
 */
public class CardProfileFragment extends Fragment {

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
        StudentCardRecord cached = encryptedCacheManager.getStudentProfile();
        if (cached != null && cached.getStudentId() != null && !cached.getStudentId().isEmpty()) {
            bindRecordToUi(cached);
            renderAvatarFromCacheOrGenerate(cached.getFullName());
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
        if (cachedBytes != null && cachedBytes.length > 0) {
            Bitmap bmp = BitmapFactory.decodeByteArray(cachedBytes, 0, cachedBytes.length);
            if (bmp != null) {
                ivStudentAvatar.setImageBitmap(bmp);
                double kb = cachedBytes.length / 1024.0;
                tvAvatarMetaBadge.setText(String.format(Locale.US, "WebP %.1f KB", kb));
                return;
            }
        }
        String initials = extractInitials(fullName);
        Bitmap defaultAvatar = ImageOptimizer.generateSampleStudentAvatar(initials, 150);
        byte[] webp = ImageOptimizer.compressToOptimizedWebP(defaultAvatar);
        encryptedCacheManager.saveAvatarBytes(webp);
        ivStudentAvatar.setImageBitmap(defaultAvatar);
        tvAvatarMetaBadge.setText(String.format(Locale.US, "WebP %.1f KB", webp.length / 1024.0));
    }

    /**
     * Reads the student profile and WebP avatar chunks from the smart card asynchronously on ApduThreadPool.
     */
    private void reloadProfileAndAvatarFromCard() {
        tvProfileSyncStatus.setText("Đang đồng bộ...");
        ApduThreadPool.getInstance().execute(() -> {
            LocalCacheManager.ProfileLoadResult res = localCacheManager.fetchStudentProfileFromCard("123456");
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
        }, (payload, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            bindRecordToUi(payload.profileResult.getRecord());
            Bitmap bmp = BitmapFactory.decodeByteArray(payload.avatarBytes, 0, payload.avatarBytes.length);
            if (bmp != null) {
                ivStudentAvatar.setImageBitmap(bmp);
            }
            double kb = payload.avatarBytes.length / 1024.0;
            tvAvatarMetaBadge.setText(String.format(Locale.US, "WebP %.1f KB", kb));
            tvProfileSyncStatus.setText(String.format(Locale.US,
                    "✓ Đã đồng bộ trong %d ms.", totalMs));
        });
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

        EditText etName = createDialogInput("Họ và tên", current.getFullName());
        EditText etDob = createDialogInput("Ngày sinh (DD/MM/YYYY)", current.getDob());
        EditText etClass = createDialogInput("Lớp", current.getClassName());
        EditText etDept = createDialogInput("Khoa", current.getDepartment());

        container.addView(etName);
        container.addView(etDob);
        container.addView(etClass);
        container.addView(etDept);

        StudentCardRecord finalCurrent = current;
        new AlertDialog.Builder(requireContext())
                .setTitle("Cập nhật Hồ sơ & Ảnh WebP (INS 0x20 / 0x22)")
                .setView(container)
                .setPositiveButton("Mã hóa AES-128 & Ghi Thẻ", (dialog, which) -> {
                    finalCurrent.setFullName(etName.getText().toString().trim());
                    finalCurrent.setDob(etDob.getText().toString().trim());
                    finalCurrent.setClassName(etClass.getText().toString().trim());
                    finalCurrent.setDepartment(etDept.getText().toString().trim());
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

            // Generate and compress portrait according to OptimizationConfig
            Bitmap rawBmp = ImageOptimizer.generateSampleStudentAvatar(extractInitials(updated.getFullName()), 600);
            byte[] imageBytes = OptimizationConfig.isOptimizedModeEnabled()
                    ? ImageOptimizer.compressToOptimizedWebP(rawBmp)
                    : ImageOptimizer.compressToOptimizedWebP(rawBmp); // Always store safe WebP size on 8KB EEPROM
            int packets = client.uploadAvatarInChunks(imageBytes);
            rawBmp.recycle();

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

        EditText etOldPin = createDialogInput("Mã PIN hiện tại (6 số)", "");
        etOldPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        EditText etNewPin = createDialogInput("Mã PIN mới (6 số)", "");
        etNewPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        container.addView(etOldPin);
        container.addView(etNewPin);

        new AlertDialog.Builder(requireContext())
                .setTitle("Đổi Mã PIN Thẻ Sinh Viên (INS 0x11)")
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
                    pinSessionManager.createPinSession(rec.getStudentId(), rec.getFullName(), newPin);
                }
                tvProfileSyncStatus.setText("✓ Đã đổi mã PIN.");
                Toast.makeText(requireContext(), "Đổi mã PIN thành công!", Toast.LENGTH_SHORT).show();
            } else {
                tvProfileSyncStatus.setText("❌ Đổi mã PIN thất bại.");
                Toast.makeText(requireContext(), "Sai mã PIN hiện tại (SW=" + resp.getSwHex() + ")", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private EditText createDialogInput(String hint, String initialValue) {
        EditText et = new EditText(requireContext());
        et.setHint(hint);
        et.setText(initialValue);
        et.setTextSize(14f);
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
