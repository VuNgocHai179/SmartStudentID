package com.example.smartstudentid.ui.student;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.PinSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
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

    private Dialog identityQrDialog;
    private final Handler identityQrHandler = new Handler(Looper.getMainLooper());
    private Runnable identityQrTickRunnable;

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
        View btnOpenIdentityQrDialog = view.findViewById(R.id.btnOpenIdentityQrDialog);
        if (btnOpenIdentityQrDialog != null) {
            btnOpenIdentityQrDialog.setOnClickListener(v -> showIdentityQrDialog());
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
        if (identityQrDialog != null && identityQrDialog.isShowing() && identityQrTickRunnable != null) {
            identityQrHandler.removeCallbacks(identityQrTickRunnable);
            identityQrHandler.post(identityQrTickRunnable);
        }
    }

    @Override
    public void onPause() {
        identityQrHandler.removeCallbacksAndMessages(null);
        super.onPause();
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
        tvProfileSyncStatus.setText("Đang cập nhật ảnh chân dung...");
        Context appContext = requireContext().getApplicationContext();

        ApduThreadPool.getInstance().execute(() -> {
            byte[] webpBytes = ImageOptimizer.decodeAndCompressAvatar(appContext, uri);
            if (webpBytes == null) {
                return new AvatarUpdateResult(null, 0);
            }
            // Re-assert the card PIN state before writing to EEPROM (INS 0x22 requires a verified PIN).
            String verifiedPin = pinSessionManager.getTransientVerifiedPin();
            if (verifiedPin != null && !verifiedPin.isEmpty()) {
                ApduClient.getInstance().sendApdu(ApduCommands.CLA_SMART_STUDENT,
                        ApduCommands.INS_VERIFY_PIN, (byte) 0x00, (byte) 0x00,
                        verifiedPin.getBytes(StandardCharsets.UTF_8));
            }
            int packets = ApduClient.getInstance().uploadAvatarInChunks(webpBytes);
            return new AvatarUpdateResult(webpBytes, packets);
        }, (result, mainBlockedMs, totalMs) -> {
            avatarUploadInProgress = false;
            if (!isAdded()) return;
            if (result.webpBytes == null) {
                tvProfileSyncStatus.setText("❌ Không đọc được ảnh đã chọn. Vui lòng thử ảnh khác.");
                Toast.makeText(requireContext(), "Không thể giải mã ảnh đã chọn!",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (result.packets < 0) {
                tvProfileSyncStatus.setText("❌ Cập nhật ảnh thất bại.");
                Toast.makeText(requireContext(), "Cập nhật ảnh thất bại!",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            encryptedCacheManager.saveAvatarBytes(result.webpBytes);
            renderAvatarBytes(result.webpBytes);
            tvProfileSyncStatus.setText("✓ Đã cập nhật ảnh chân dung.");
            Toast.makeText(requireContext(), "Đã cập nhật ảnh chân dung thành công!",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private static class AvatarUpdateResult {
        final byte[] webpBytes;
        final int packets;

        AvatarUpdateResult(byte[] webpBytes, int packets) {
            this.webpBytes = webpBytes;
            this.packets = packets;
        }
    }

    /**
     * Reads the student profile and WebP avatar chunks from the smart card asynchronously on ApduThreadPool.
     */
    private void reloadProfileAndAvatarFromCard() {
        tvProfileSyncStatus.setText("Đang cập nhật thông tin...");
        ApduThreadPool.getInstance().execute(() -> {
            String sessionPin = pinSessionManager.getTransientVerifiedPin();
            String verifiedPin = (sessionPin != null && !sessionPin.isEmpty()) ? sessionPin : "123456";
            LocalCacheManager.ProfileLoadResult res = localCacheManager.fetchStudentProfileFromCard(verifiedPin);
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
            tvAvatarMetaBadge.setVisibility(View.GONE);
            tvProfileSyncStatus.setText("✓ Đã cập nhật thông tin thẻ.");
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
                .setPositiveButton("Lưu", (dialog, which) -> {
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
        tvProfileSyncStatus.setText("Đang lưu thông tin...");
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
            tvProfileSyncStatus.setText("✓ Đã cập nhật hồ sơ.");
            Toast.makeText(requireContext(), "Đã cập nhật hồ sơ thành công!", Toast.LENGTH_SHORT).show();
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
                    pinSessionManager.createPinSession(rec.getStudentId(), rec.getFullName(), newPin);
                }
                tvProfileSyncStatus.setText("✓ Đã đổi mã PIN.");
                Toast.makeText(requireContext(), "Đổi mã PIN thành công!", Toast.LENGTH_SHORT).show();
            } else {
                tvProfileSyncStatus.setText("❌ Đổi mã PIN thất bại.");
                Toast.makeText(requireContext(), "Mã PIN hiện tại không chính xác", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Shows the 30-second RSA-1024 dynamic personal identity QR dialog for 1-to-1 verification
     * at the library counter, exam room, or campus access gates.
     */
    private void showIdentityQrDialog() {
        if (!isAdded()) return;
        if (identityQrDialog != null && identityQrDialog.isShowing()) {
            return;
        }

        View popupView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_identity_qr, null, false);
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(popupView);
        dialog.setCancelable(true);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvInfo = popupView.findViewById(R.id.tvIdentityQrStudentInfo);
        ImageView ivQr = popupView.findViewById(R.id.ivIdentityQr);
        ProgressBar pbCountdown = popupView.findViewById(R.id.pbIdentityQrCountdown);
        TextView tvCountdown = popupView.findViewById(R.id.tvIdentityQrCountdown);
        SwitchMaterial swFlagSecure = popupView.findViewById(R.id.swIdentityFlagSecure);
        MaterialButton btnClose = popupView.findViewById(R.id.btnCloseIdentityQr);

        StudentCardRecord profile = encryptedCacheManager.getStudentProfile();
        String studentId = (profile != null && profile.getStudentId() != null) ? profile.getStudentId() : "CT070315";
        String fullName = (profile != null && profile.getFullName() != null) ? profile.getFullName() : "Vũ Ngọc Hải";
        String className = (profile != null && profile.getClassName() != null) ? profile.getClassName() : "CT7C";
        tvInfo.setText(fullName + " • " + studentId + " • Lớp " + className);

        if (getActivity() instanceof BaseSecureActivity) {
            BaseSecureActivity secureActivity = (BaseSecureActivity) getActivity();
            swFlagSecure.setChecked(secureActivity.isFlagSecureEnabled());
            swFlagSecure.setOnCheckedChangeListener((buttonView, isChecked) ->
                    secureActivity.applyFlagSecure(isChecked));
        } else {
            swFlagSecure.setEnabled(false);
        }

        final long[] lastWindowEpoch = new long[]{-1L};
        identityQrTickRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || identityQrDialog == null || !identityQrDialog.isShowing()) {
                    return;
                }
                long nowSec = System.currentTimeMillis() / 1000L;
                long windowEpoch = nowSec / 30L;
                int remainingSec = (int) (30L - (nowSec % 30L));

                pbCountdown.setProgress(remainingSec);
                tvCountdown.setText("Đổi mã sau " + remainingSec + "s");

                if (windowEpoch != lastWindowEpoch[0]) {
                    lastWindowEpoch[0] = windowEpoch;
                    refreshIdentityQrBitmap(dialog, ivQr, studentId, className, windowEpoch);
                }
                identityQrHandler.postDelayed(this, 1000L);
            }
        };

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            identityQrHandler.removeCallbacksAndMessages(null);
            identityQrTickRunnable = null;
            identityQrDialog = null;
        });

        identityQrDialog = dialog;
        dialog.show();
        identityQrHandler.post(identityQrTickRunnable);
    }

    private void refreshIdentityQrBitmap(Dialog dialog, ImageView targetView,
                                         String studentId, String className, long windowEpoch) {
        ApduThreadPool.getInstance().execute(() -> {
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
            return encodeIdentityQrBitmap(qrContent, 320);
        }, (bitmap, mainBlockedMs, totalMs) -> {
            if (!isAdded() || !dialog.isShowing()) return;
            if (bitmap != null) {
                targetView.setImageBitmap(bitmap);
            }
        });
    }

    private Bitmap encodeIdentityQrBitmap(String content, int sizePx) {
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
