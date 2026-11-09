package com.example.smartstudentid.ui.admin;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.model.AttendanceRecord;
import com.example.smartstudentid.model.BookLoanRecord;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.optimization.ImageOptimizer;
import com.example.smartstudentid.security.AdminSessionManager;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Admin Role Dashboard Activity (Phòng Đào Tạo) implementing CN 1.1, 1.2, 1.3:
 * Communicates directly with the central Server SQLite Database (`bridge-server/data/smartcard.db`):
 * 1. Issue New Student Card: Encrypts profile with AES-128 (INS 0x20), generates on-card
 *    RSA-1024 key pair (INS 0x13, P1=00), compresses WebP avatar (INS 0x22), and inserts
 *    into the Server's `cards` table (`POST /api/cards`).
 * 2. Unblock Card by Admin PUK: Sends 8-digit PUK (INS 0x12) to invoke OwnerPIN.resetAndUnblock()
 *    and updates the Server's `cards` table (`POST /api/cards/unblock`).
 * 3. Card Action Sheet: Tapping an issued card opens 3 actions (edit student info, unlock with PUK,
 *    lock with PUK). The card is mounted on the reader automatically inside each action.
 * 4. Academic & Library Summary: Queries `cards`, `attendance_logs`, and `book_loans` from Server SQLite.
 */
public class AdminDashboardActivity extends BaseSecureActivity {

    private EditText etIssueStudentId;
    private EditText etIssueClassName;
    private EditText etIssueFullName;
    private EditText etIssueDob;
    private AutoCompleteTextView actvIssueDepartment;
    private TextView tvIssueCardLog;
    private TextView tvCardActionStatus;
    private TextView tvIssuedCardsCount;
    private RecyclerView rvIssuedCards;

    private ImageView ivIssueAvatarPreview;
    private TextView tvIssueAvatarHint;
    private byte[] pendingIssueAvatarWebp;
    private boolean issueAvatarProcessing;
    private Uri pendingCameraUri;
    private ActivityResultLauncher<PickVisualMediaRequest> pickIssueAvatarLauncher;
    private ActivityResultLauncher<Uri> takeIssuePhotoLauncher;

    private static final String CAMERA_CAPTURE_FILE = "issue_avatar_capture.jpg";

    private TabLayout tabLayoutAdmin;
    private View scrollTabManageCards;
    private View scrollTabCreateCard;

    private EncryptedCacheManager cacheManager;
    private final List<StudentCardRecord> issuedCards = new ArrayList<>();
    private IssuedCardAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register the photo pickers before the activity reaches STARTED
        pickIssueAvatarLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) prepareIssueAvatar(uri);
                });
        takeIssuePhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (Boolean.TRUE.equals(success) && pendingCameraUri != null) {
                        prepareIssueAvatar(pendingCameraUri);
                    }
                });

        // Security check: Verify active admin session from SQLite authentication
        if (!AdminSessionManager.getInstance(this).isLoggedIn()) {
            Toast.makeText(this, "Yêu cầu đăng nhập quản trị viên", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, AdminLoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_admin_dashboard);
        applySafeAreaPadding(findViewById(R.id.adminRoot), true, true);
        findViewById(R.id.adminRoot).setVerticalScrollBarEnabled(false);

        cacheManager = EncryptedCacheManager.getInstance(this);

        bindViews();
        setupRecyclerView();
        loadCentralDatabaseFromServer();
    }

    private void bindViews() {
        etIssueStudentId = findViewById(R.id.etIssueStudentId);
        etIssueClassName = findViewById(R.id.etIssueClassName);
        etIssueFullName = findViewById(R.id.etIssueFullName);
        etIssueDob = findViewById(R.id.etIssueDob);
        etIssueDob.setOnClickListener(v -> showDatePickerDialog());
        TextInputLayout tilIssueDob = findViewById(R.id.tilIssueDob);
        if (tilIssueDob != null) {
            tilIssueDob.setEndIconOnClickListener(v -> showDatePickerDialog());
        }

        actvIssueDepartment = findViewById(R.id.actvIssueDepartment);
        String[] departments = new String[]{
                "An toàn thông tin",
                "Công nghệ thông tin",
                "Điện tử viễn thông"
        };
        ArrayAdapter<String> deptAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                departments
        );
        actvIssueDepartment.setAdapter(deptAdapter);
        actvIssueDepartment.setText(departments[0], false);

        tvIssueCardLog = findViewById(R.id.tvIssueCardLog);
        tvCardActionStatus = findViewById(R.id.tvCardActionStatus);
        tvIssuedCardsCount = findViewById(R.id.tvIssuedCardsCount);
        rvIssuedCards = findViewById(R.id.rvIssuedCards);

        ivIssueAvatarPreview = findViewById(R.id.ivIssueAvatarPreview);
        tvIssueAvatarHint = findViewById(R.id.tvIssueAvatarHint);
        Button btnPickIssueAvatar = findViewById(R.id.btnPickIssueAvatar);
        if (btnPickIssueAvatar != null) {
            btnPickIssueAvatar.setOnClickListener(v -> showIssueAvatarSourceDialog());
        }

        TextView tvAdminIdentitySubtitle = findViewById(R.id.tvAdminIdentitySubtitle);
        if (tvAdminIdentitySubtitle != null) {
            AdminSessionManager adm = AdminSessionManager.getInstance(this);
            tvAdminIdentitySubtitle.setText(adm.getFullName());
        }

        Button btnBackToLogin = findViewById(R.id.btnBackToLogin);
        Button btnIssueNewCard = findViewById(R.id.btnIssueNewCard);

        tabLayoutAdmin = findViewById(R.id.tabLayoutAdmin);
        scrollTabManageCards = findViewById(R.id.scrollTabManageCards);
        scrollTabCreateCard = findViewById(R.id.scrollTabCreateCard);

        if (tabLayoutAdmin != null) {
            tabLayoutAdmin.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    if (tab.getPosition() == 0) {
                        if (scrollTabManageCards != null) scrollTabManageCards.setVisibility(View.VISIBLE);
                        if (scrollTabCreateCard != null) scrollTabCreateCard.setVisibility(View.GONE);
                    } else {
                        if (scrollTabManageCards != null) scrollTabManageCards.setVisibility(View.GONE);
                        if (scrollTabCreateCard != null) scrollTabCreateCard.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onTabUnselected(TabLayout.Tab tab) {}

                @Override
                public void onTabReselected(TabLayout.Tab tab) {}
            });
        }

        btnBackToLogin.setOnClickListener(v -> {
            AdminSessionManager.getInstance(this).clearSession();
            Toast.makeText(this, "Đã đăng xuất phiên làm việc quản trị", Toast.LENGTH_SHORT).show();
            finish();
        });
        btnIssueNewCard.setOnClickListener(v -> handleIssueNewCard());
    }

    private void setupRecyclerView() {
        rvIssuedCards.setLayoutManager(new LinearLayoutManager(this));
        rvIssuedCards.setHasFixedSize(true);
        rvIssuedCards.setVerticalScrollBarEnabled(false);
        adapter = new IssuedCardAdapter();
        rvIssuedCards.setAdapter(adapter);
        updateIssuedCardsCount();
    }

    private void updateIssuedCardsCount() {
        if (tvIssuedCardsCount != null) {
            tvIssuedCardsCount.setText(issuedCards.size() + " thẻ");
        }
    }

    /**
     * Fetches `cards`, `attendance_logs`, and `book_loans` directly from the central Server SQLite (`smartcard.db`).
     */
    private void loadCentralDatabaseFromServer() {
        ApduThreadPool.getInstance().execute(() -> {
            ApduClient client = ApduClient.getInstance();
            List<StudentCardRecord> remoteCards = client.fetchCardsFromServerDb();
            List<AttendanceRecord> remoteAtt = client.fetchAttendanceFromServerDb();
            List<BookLoanRecord> remoteBooks = client.fetchBooksFromServerDb();
            return new ServerDbSnapshot(remoteCards, remoteAtt, remoteBooks);
        }, (snapshot, mainBlockedMs, totalMs) -> {
            issuedCards.clear();
            if (snapshot.cards == null) {
                adapter.notifyDataSetChanged();
                updateIssuedCardsCount();
                Toast.makeText(this, "Không kết nối được Máy chủ CSDL (BridgeServer cổng 8080)", Toast.LENGTH_SHORT).show();
                return;
            }

            issuedCards.addAll(snapshot.cards);
            adapter.notifyDataSetChanged();
            updateIssuedCardsCount();
        });
    }

    /**
     * Shows a calendar DatePicker dialog and formats the selected date as dd/MM/yyyy.
     */
    private void showDatePickerDialog() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.YEAR, 2003);
        c.set(Calendar.MONTH, Calendar.JANUARY);
        c.set(Calendar.DAY_OF_MONTH, 1);

        String currentText = etIssueDob.getText() != null ? etIssueDob.getText().toString().trim() : "";
        if (currentText.matches("^(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[012])/\\d{4}$")) {
            try {
                String[] parts = currentText.split("/");
                c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(parts[0]));
                c.set(Calendar.MONTH, Integer.parseInt(parts[1]) - 1);
                c.set(Calendar.YEAR, Integer.parseInt(parts[2]));
            } catch (Exception ignored) {
            }
        }

        DatePickerDialog dpd = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            String formatted = String.format(Locale.getDefault(), "%02d/%02d/%04d", dayOfMonth, month + 1, year);
            etIssueDob.setText(formatted);
            etIssueDob.setError(null);
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));

        // Limit date to realistic student age (at least 15 years old)
        dpd.getDatePicker().setMaxDate(System.currentTimeMillis() - 15L * 365 * 24 * 3600 * 1000L);
        dpd.show();
    }

    /**
     * Strictly validates that the date follows dd/MM/yyyy and corresponds to a realistic student age (16-70).
     */
    private boolean isValidDateOfBirth(String dob) {
        if (dob == null || !dob.matches("^(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[012])/(19[5-9][0-9]|20[0-2][0-9])$")) {
            return false;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
        sdf.setLenient(false);
        try {
            Date d = sdf.parse(dob);
            if (d == null) return false;
            Calendar birth = Calendar.getInstance();
            birth.setTime(d);
            Calendar now = Calendar.getInstance();
            int age = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR);
            if (now.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) {
                age--;
            }
            return age >= 16 && age <= 70;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lets the admin pick the new card's portrait from the gallery or capture it with the camera.
     */
    private void showIssueAvatarSourceDialog() {
        String[] options = new String[]{"Chọn ảnh từ thư viện", "Chụp ảnh mới"};
        new AlertDialog.Builder(this)
                .setTitle("Ảnh chân dung thẻ sinh viên")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        pickIssueAvatarLauncher.launch(new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build());
                    } else {
                        launchIssueCameraCapture();
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void launchIssueCameraCapture() {
        try {
            File captureFile = new File(getCacheDir(), CAMERA_CAPTURE_FILE);
            if (captureFile.exists() && !captureFile.delete()) {
                Toast.makeText(this, "Không thể tạo tệp ảnh tạm!", Toast.LENGTH_SHORT).show();
                return;
            }
            pendingCameraUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", captureFile);
            takeIssuePhotoLauncher.launch(pendingCameraUri);
        } catch (Exception e) {
            Toast.makeText(this, "Không mở được camera: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Downsamples and compresses the chosen portrait right away so issuing the card stays fast.
     */
    private void prepareIssueAvatar(Uri uri) {
        if (uri == null) return;
        issueAvatarProcessing = true;
        if (tvIssueCardLog != null) {
            tvIssueCardLog.setVisibility(View.VISIBLE);
            tvIssueCardLog.setText("Đang xử lý ảnh: Resize 150×150 → WebP 75%...");
        }

        ApduThreadPool.getInstance().execute(
                () -> ImageOptimizer.decodeAndCompressAvatar(getApplicationContext(), uri),
                (webpBytes, mainBlockedMs, totalMs) -> {
                    issueAvatarProcessing = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (webpBytes == null || webpBytes.length == 0) {
                        pendingIssueAvatarWebp = null;
                        if (tvIssueCardLog != null) {
                            tvIssueCardLog.setText("❌ Không đọc được ảnh đã chọn. Vui lòng chọn ảnh khác.");
                        }
                        Toast.makeText(this, "Không thể giải mã ảnh đã chọn!",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    pendingIssueAvatarWebp = webpBytes;
                    Bitmap preview = BitmapFactory.decodeByteArray(webpBytes, 0, webpBytes.length);
                    if (ivIssueAvatarPreview != null && preview != null) {
                        ivIssueAvatarPreview.setPadding(0, 0, 0, 0);
                        ivIssueAvatarPreview.setImageTintList(null);
                        ivIssueAvatarPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        ivIssueAvatarPreview.setImageBitmap(preview);
                    }
                    if (tvIssueAvatarHint != null) {
                        tvIssueAvatarHint.setText(String.format(Locale.US,
                                "WebP %.1f KB • %d gói APDU (240 B)",
                                webpBytes.length / 1024.0,
                                ImageOptimizer.calculateApduPacketCount(webpBytes.length)));
                    }
                    if (tvIssueCardLog != null) {
                        tvIssueCardLog.setText(String.format(Locale.US,
                                "✓ Đã nạp ảnh WebP %.1f KB sẵn sàng ghi lên thẻ.",
                                webpBytes.length / 1024.0));
                    }
                });
    }

    private void resetIssueAvatarPreview() {
        if (ivIssueAvatarPreview == null) return;
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        ivIssueAvatarPreview.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.slate_400)));
        ivIssueAvatarPreview.setImageResource(R.drawable.ic_person);
        ivIssueAvatarPreview.setPadding(pad, pad, pad, pad);
        ivIssueAvatarPreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (tvIssueAvatarHint != null) {
            tvIssueAvatarHint.setText("Tự động Resize 150×150 → WebP 75% (~3.5 KB)");
        }
    }

    /**
     * Issues a new student smart card asynchronously and stores it in Server SQLite (`smartcard.db`).
     */
    private void handleIssueNewCard() {
        String studentId = etIssueStudentId.getText().toString().trim();
        String className = etIssueClassName.getText().toString().trim();
        String fullName = etIssueFullName.getText().toString().trim();
        String dob = etIssueDob.getText().toString().trim();
        String dept = actvIssueDepartment.getText().toString().trim();

        if (issueAvatarProcessing) {
            Toast.makeText(this, "Ảnh chân dung đang được xử lý, vui lòng đợi trong giây lát!",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Strict validation: Student ID
        if (studentId.isEmpty()) {
            etIssueStudentId.setError("Vui lòng nhập Mã sinh viên!");
            etIssueStudentId.requestFocus();
            return;
        }
        if (!studentId.matches("^[a-zA-Z0-9_-]{4,20}$")) {
            etIssueStudentId.setError("Mã sinh viên phải từ 4-20 ký tự (chữ, số, gạch nối, không chứa ký tự đặc biệt)!");
            etIssueStudentId.requestFocus();
            return;
        }

        // 2. Strict validation: Class Name
        if (className.isEmpty()) {
            etIssueClassName.setError("Vui lòng nhập Lớp!");
            etIssueClassName.requestFocus();
            return;
        }
        if (className.contains("|") || className.contains(";")) {
            etIssueClassName.setError("Tên lớp không hợp lệ!");
            etIssueClassName.requestFocus();
            return;
        }

        // 3. Strict validation: Full Name
        if (fullName.isEmpty()) {
            etIssueFullName.setError("Vui lòng nhập Họ và tên sinh viên!");
            etIssueFullName.requestFocus();
            return;
        }
        if (fullName.length() < 2 || fullName.contains("|") || fullName.matches(".*\\d.*")) {
            etIssueFullName.setError("Họ và tên không hợp lệ (không chứa số hoặc ký tự đặc biệt)!");
            etIssueFullName.requestFocus();
            return;
        }

        // 4. Strict validation: Date of Birth
        if (dob.isEmpty() || !isValidDateOfBirth(dob)) {
            etIssueDob.setError("Ngày sinh không hợp lệ! Vui lòng chọn ngày sinh chuẩn (dd/mm/yyyy, độ tuổi 16-70)");
            showDatePickerDialog();
            return;
        }

        // 5. Strict validation: Department
        if (dept.isEmpty()) {
            Toast.makeText(this, "Vui lòng chọn Khoa / Viện từ danh sách!", Toast.LENGTH_SHORT).show();
            return;
        }

        tvIssueCardLog.setVisibility(View.VISIBLE);
        tvIssueCardLog.setText("Đang phát hành thẻ...");
        StudentCardRecord newCard = new StudentCardRecord(
                studentId, fullName, dob, className, dept, "123456", false, 85, 0
        );
        final byte[] selectedPhoto = pendingIssueAvatarWebp;

        ApduThreadPool.getInstance().execute(() -> {
            ApduClient client = ApduClient.getInstance();

            // 1. Activate card slot and verify default PIN
            client.activateVirtualCard(studentId, newCard.toPipeDelimitedString(), "123456");
            client.sendApdu(ApduCommands.CLA_ISO, ApduCommands.INS_SELECT,
                    (byte) 0x04, (byte) 0x00, ApduCommands.APPLET_AID);
            client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_VERIFY_PIN,
                    (byte) 0x00, (byte) 0x00, "123456".getBytes(StandardCharsets.UTF_8));

            // 2. Encrypt and store student profile in EEPROM using AES-128 (INS 0x20)
            byte[] utf8Info = newCard.toPipeDelimitedString().getBytes(StandardCharsets.UTF_8);
            client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_SET_STUDENT_INFO,
                    (byte) 0x00, (byte) 0x00, utf8Info);

            // 3. Generate a new on-card RSA-1024 key pair and export Public Key Modulus N & Exponent E (INS 0x13)
            CardResponse modResp = client.sendApdu(ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_GET_PUBLIC_KEY, ApduCommands.P1_GEN_RSA_KEYPAIR, (byte) 0x00, null);
            CardResponse expResp = client.sendApdu(ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_GET_PUBLIC_KEY, ApduCommands.P1_GET_EXPONENT, (byte) 0x00, null);

            newCard.setPublicKeyModulusHex(ApduCommands.bytesToHex(modResp.getData()));
            newCard.setPublicKeyExponentHex(ApduCommands.bytesToHex(expResp.getData()));

            // 4. Use the admin-picked portrait when available; otherwise generate a placeholder
            byte[] webpBytes = selectedPhoto;
            if (webpBytes == null || webpBytes.length == 0) {
                String initials = fullName.length() >= 2 ? fullName.substring(0, 2).toUpperCase() : "SV";
                Bitmap avatarBmp = ImageOptimizer.generateSampleStudentAvatar(initials, 150);
                webpBytes = ImageOptimizer.compressToOptimizedWebP(avatarBmp);
                avatarBmp.recycle();
            }
            int packets = client.uploadAvatarInChunks(webpBytes);

            // 5. Persist new card record into Server SQLite (`smartcard.db`)
            client.syncCardToServerDb(newCard);

            return "Modulus(" + modResp.getData().length + "B) | WebP("
                    + (webpBytes.length / 1024.0) + "KB, " + packets + " APDU packets)";
        }, (summary, mainBlockedMs, totalMs) -> {
            cacheManager.saveStudentProfile(newCard);
            loadCentralDatabaseFromServer();

            pendingIssueAvatarWebp = null;
            resetIssueAvatarPreview();

            tvIssueCardLog.setVisibility(View.VISIBLE);
            tvIssueCardLog.setText("✓ Đã phát hành thẻ cho " + newCard.getFullName());
            Toast.makeText(this, "Đã phát hành thẻ " + newCard.getStudentId() + "!",
                    Toast.LENGTH_SHORT).show();

            // Clear input fields
            etIssueStudentId.setText("");
            etIssueClassName.setText("");
            etIssueFullName.setText("");
            etIssueDob.setText("");
            actvIssueDepartment.setText("An toàn thông tin", false);

            // Automatically switch back to Tab 0 (Card Management) so admin sees the new card in the list
            if (tabLayoutAdmin != null) {
                tabLayoutAdmin.postDelayed(() -> {
                    TabLayout.Tab tab0 = tabLayoutAdmin.getTabAt(0);
                    if (tab0 != null) tab0.select();
                }, 1000);
            }
        });
    }

    private void setCardActionStatus(String message, boolean success) {
        if (tvCardActionStatus == null) return;
        tvCardActionStatus.setVisibility(View.VISIBLE);
        tvCardActionStatus.setText(message);
        tvCardActionStatus.setTextColor(success ? 0xFF059669 : 0xFFDC2626);
    }

    private String resolveCardPin(StudentCardRecord card) {
        if (card != null && card.getDefaultPin() != null && !card.getDefaultPin().isEmpty()) {
            return card.getDefaultPin();
        }
        return "123456";
    }

    private EditText createCardDialogInput(String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value != null ? value : "");
        et.setTextSize(14f);
        et.setMaxLines(1);
        return et;
    }

    /**
     * Tapping a card in the list opens the three admin actions: edit info, unlock with PUK, lock with PUK.
     * Mounting the card ("Gắn thẻ") is performed automatically inside each action.
     */
    private void showCardActionsDialog(StudentCardRecord item) {
        String title = item.getFullName() + " • " + item.getStudentId()
                + (item.isCardLocked() ? " [Đang khóa]" : "");
        String[] actions = new String[]{
                "Sửa thông tin sinh viên",
                "Mở thẻ bằng PUK quản trị",
                "Khóa thẻ bằng PUK quản trị"
        };

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        if (item.isCardLocked()) {
                            setCardActionStatus("❌ Thẻ đang bị khóa. Hãy chọn \"Mở thẻ bằng PUK\" trước khi sửa thông tin.", false);
                            Toast.makeText(this, "Thẻ đang khóa, cần mở bằng PUK trước!",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showEditStudentInfoDialog(item);
                    } else {
                        promptPukDialog(item, which == 2);
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    /**
     * Edits the student identity fields and writes them back to the selected card (INS 0x20).
     */
    private void showEditStudentInfoDialog(StudentCardRecord item) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etName = createCardDialogInput("Họ và tên", item.getFullName());
        EditText etDob = createCardDialogInput("Ngày sinh (dd/MM/yyyy)", item.getDob());
        EditText etClass = createCardDialogInput("Lớp", item.getClassName());
        EditText etDept = createCardDialogInput("Khoa / Viện", item.getDepartment());
        container.addView(etName);
        container.addView(etDob);
        container.addView(etClass);
        container.addView(etDept);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Sửa thông tin • " + item.getStudentId())
                .setView(container)
                .setPositiveButton("Lưu lên thẻ", null)
                .setNegativeButton("Hủy", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String fullName = etName.getText().toString().trim();
                    String dob = etDob.getText().toString().trim();
                    String className = etClass.getText().toString().trim();
                    String dept = etDept.getText().toString().trim();

                    if (!validateCardInfoInputs(fullName, dob, className, dept)) {
                        return;
                    }
                    dialog.dismiss();
                    saveStudentInfoToCard(item, fullName, dob, className, dept);
                }));
        dialog.show();
    }

    private boolean validateCardInfoInputs(String fullName, String dob, String className, String dept) {
        if (fullName.length() < 2 || fullName.matches(".*\\d.*") || fullName.contains("|")) {
            Toast.makeText(this, "Họ tên không hợp lệ (tối thiểu 2 ký tự, không chứa số hoặc '|')!",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!isValidDateOfBirth(dob)) {
            Toast.makeText(this, "Ngày sinh không hợp lệ (dd/MM/yyyy, độ tuổi 16-70)!",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (className.isEmpty() || className.contains("|")) {
            Toast.makeText(this, "Tên lớp không hợp lệ!", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (dept.isEmpty() || dept.contains("|")) {
            Toast.makeText(this, "Khoa / Viện không hợp lệ!", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    /**
     * Mounts the selected virtual card, verifies its PIN, encrypts the updated profile (INS 0x20)
     * and synchronizes the new values into Server SQLite.
     */
    private void saveStudentInfoToCard(StudentCardRecord original, String fullName, String dob,
                                       String className, String dept) {
        setCardActionStatus("Đang nạp thẻ " + original.getStudentId() + " và ghi hồ sơ mới...", true);
        final String pin = resolveCardPin(original);
        final String infoUtf8 = original.getStudentId() + "|" + fullName + "|" + dob
                + "|" + className + "|" + dept;

        ApduThreadPool.getInstance().execute(() -> {
            ApduClient client = ApduClient.getInstance();
            // "Gắn thẻ" is implicit: mount the selected card before writing to its EEPROM.
            client.activateVirtualCard(original.getStudentId(), original.toPipeDelimitedString(), pin);
            client.sendApdu(ApduCommands.CLA_ISO, ApduCommands.INS_SELECT,
                    (byte) 0x04, (byte) 0x00, ApduCommands.APPLET_AID);

            CardResponse verify = client.sendApdu(ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_VERIFY_PIN, (byte) 0x00, (byte) 0x00,
                    pin.getBytes(StandardCharsets.UTF_8));
            if (!verify.isSuccess()) {
                return new CardInfoUpdateResult(false, verify, null);
            }

            CardResponse writeResp = client.sendApdu(ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_SET_STUDENT_INFO, (byte) 0x00, (byte) 0x00,
                    infoUtf8.getBytes(StandardCharsets.UTF_8));
            if (!writeResp.isSuccess()) {
                return new CardInfoUpdateResult(false, writeResp, null);
            }

            StudentCardRecord updated = new StudentCardRecord(original.getStudentId(), fullName,
                    dob, className, dept, pin, original.isCardLocked(),
                    original.getTrainingScore(), original.getAttendanceCount());
            updated.setPublicKeyModulusHex(original.getPublicKeyModulusHex());
            updated.setPublicKeyExponentHex(original.getPublicKeyExponentHex());
            updated.setLabAccessGranted(original.isLabAccessGranted());
            client.syncCardToServerDb(updated);
            return new CardInfoUpdateResult(true, writeResp, updated);
        }, (result, mainBlockedMs, totalMs) -> {
            if (result.success) {
                setCardActionStatus("✓ Đã cập nhật thông tin thẻ " + original.getStudentId()
                        + " trên chip và CSDL trung tâm.", true);
                Toast.makeText(this, "Đã lưu thông tin sinh viên lên thẻ!",
                        Toast.LENGTH_SHORT).show();
                loadCentralDatabaseFromServer();
            } else {
                String sw = result.response != null ? result.response.getSwHex() : "----";
                setCardActionStatus("❌ Ghi thông tin thất bại (SW=" + sw
                        + "). Kiểm tra thẻ đang khóa hoặc PIN đã thay đổi.", false);
                Toast.makeText(this, "Không ghi được thông tin lên thẻ!",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Asks for the 8-digit Admin PUK before locking or unlocking the selected card.
     */
    private void promptPukDialog(StudentCardRecord item, boolean lock) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText(lock
                ? "Nhập PUK quản trị để khóa thẻ. Sau khi khóa, mọi thao tác PIN đều bị từ chối."
                : "Nhập PUK quản trị để mở thẻ. PIN sẽ được khôi phục về mặc định 123456.");
        hint.setTextSize(12f);
        hint.setTextColor(0xFF64748B);
        hint.setPadding(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));

        EditText etPuk = createCardDialogInput("Mã PUK quản trị (8 chữ số)", "");
        etPuk.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        container.addView(hint);
        container.addView(etPuk);

        String actionLabel = lock ? "Khóa thẻ" : "Mở thẻ";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(actionLabel + " • " + item.getStudentId())
                .setView(container)
                .setPositiveButton(actionLabel, null)
                .setNegativeButton("Hủy", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String puk = etPuk.getText().toString().trim();
                    if (puk.length() != 8 || !puk.matches("\\d{8}")) {
                        etPuk.setError("Mã PUK phải gồm đúng 8 chữ số!");
                        return;
                    }
                    dialog.dismiss();
                    executePukAction(item, puk, lock);
                }));
        dialog.show();
    }

    /**
     * Mounts the target card and sends INS 0x12 with P1=01 (lock) or P1=00 (unlock),
     * then persists the new lock state in Server SQLite.
     */
    private void executePukAction(StudentCardRecord item, String puk, boolean lock) {
        setCardActionStatus((lock ? "Đang khóa thẻ " : "Đang mở thẻ ")
                + item.getStudentId() + " bằng PUK quản trị...", true);
        final String pin = resolveCardPin(item);

        ApduThreadPool.getInstance().execute(() -> {
            ApduClient client = ApduClient.getInstance();
            // An outdated BridgeServer ignores P1 on INS 0x12 and would report a false success.
            if (client.fetchBridgeApiVersion() == 1) {
                return new PukActionResult(null, true);
            }

            client.activateVirtualCard(item.getStudentId(), item.toPipeDelimitedString(), pin);
            client.sendApdu(ApduCommands.CLA_ISO, ApduCommands.INS_SELECT,
                    (byte) 0x04, (byte) 0x00, ApduCommands.APPLET_AID);

            CardResponse resp = client.sendPukAdminAction(puk, lock);
            if (resp.isSuccess()) {
                // Sync the FULL card record (not a partial lock-only payload): this keeps the
                // owner profile intact even when an older BridgeServer is still running.
                item.setCardLocked(lock);
                if (!lock) {
                    item.setDefaultPin("123456");
                }
                client.syncCardToServerDb(item);
            }
            return new PukActionResult(resp, false);
        }, (result, mainBlockedMs, totalMs) -> {
            if (result.outdatedServer) {
                setCardActionStatus("⚠ BridgeServer đang chạy bản cũ (chưa hỗ trợ khóa thẻ bằng PUK). "
                        + "Hãy dừng và chạy lại bridge-server/start-bridge-server.bat rồi thử lại.", false);
                Toast.makeText(this, "BridgeServer cần được khởi động lại để cập nhật!",
                        Toast.LENGTH_LONG).show();
                return;
            }

            CardResponse resp = result.response;
            if (resp != null && resp.isSuccess()) {
                setCardActionStatus((lock ? "✓ Đã khóa thẻ " : "✓ Đã mở thẻ ")
                        + item.getStudentId() + " bằng PUK quản trị."
                        + (lock ? "" : " PIN được khôi phục về 123456."), true);
                Toast.makeText(this, lock ? "Đã khóa thẻ!" : "Đã mở khóa thẻ!",
                        Toast.LENGTH_SHORT).show();
                loadCentralDatabaseFromServer();
            } else if (resp != null && resp.getSw() == 0x6300) {
                setCardActionStatus("❌ Mã PUK quản trị không đúng.", false);
                Toast.makeText(this, "Sai mã PUK quản trị!", Toast.LENGTH_SHORT).show();
            } else {
                String sw = resp != null ? resp.getSwHex() : "----";
                setCardActionStatus("❌ Thao tác thất bại (SW=" + sw + ").", false);
                Toast.makeText(this, "Thao tác thất bại (SW=" + sw + ")!",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static class PukActionResult {
        final CardResponse response;
        final boolean outdatedServer;

        PukActionResult(CardResponse response, boolean outdatedServer) {
            this.response = response;
            this.outdatedServer = outdatedServer;
        }
    }

    private static class CardInfoUpdateResult {
        final boolean success;
        final CardResponse response;
        final StudentCardRecord updated;

        CardInfoUpdateResult(boolean success, CardResponse response, StudentCardRecord updated) {
            this.success = success;
            this.response = response;
            this.updated = updated;
        }
    }

    private String initialBadge(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return "SV";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
        }
        return fullName.substring(0, Math.min(2, fullName.length())).toUpperCase();
    }

    private static class ServerDbSnapshot {
        final List<StudentCardRecord> cards;
        final List<AttendanceRecord> attendance;
        final List<BookLoanRecord> books;

        ServerDbSnapshot(List<StudentCardRecord> cards, List<AttendanceRecord> attendance, List<BookLoanRecord> books) {
            this.cards = cards;
            this.attendance = attendance;
            this.books = books;
        }
    }

    private class IssuedCardAdapter extends RecyclerView.Adapter<IssuedCardAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_issued_card, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            StudentCardRecord item = issuedCards.get(position);
            holder.tvAvatar.setText(initialBadge(item.getFullName()));
            holder.tvTitle.setText(item.getFullName());
            holder.tvSub.setText("MSSV " + item.getStudentId() + " • Lớp " + item.getClassName()
                    + " • " + item.getDepartment());

            if (item.isCardLocked()) {
                holder.tvStatus.setText("Đang khóa PIN");
                holder.tvStatus.setTextColor(0xFFDC2626);
                holder.tvStatus.setBackgroundResource(R.drawable.bg_pill_red);
            } else {
                holder.tvStatus.setText("Sẵn sàng");
                holder.tvStatus.setTextColor(0xFF059669);
                holder.tvStatus.setBackgroundResource(R.drawable.bg_pill_green);
            }

            holder.itemView.setOnClickListener(v -> showCardActionsDialog(item));
        }

        @Override
        public int getItemCount() {
            return issuedCards.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvAvatar;
            final TextView tvTitle;
            final TextView tvSub;
            final TextView tvStatus;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvAvatar = itemView.findViewById(R.id.tvItemAvatarInitials);
                tvTitle = itemView.findViewById(R.id.tvItemStudentTitle);
                tvSub = itemView.findViewById(R.id.tvItemStudentSub);
                tvStatus = itemView.findViewById(R.id.tvItemCardStatus);
            }
        }
    }
}
