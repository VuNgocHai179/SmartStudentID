package com.example.smartstudentid.ui.admin;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
 * 3. Virtual Card Switcher: Activates any issued student card on the JCIDE reader in < 50ms.
 * 4. Academic & Library Summary: Queries `cards`, `attendance_logs`, and `book_loans` from Server SQLite.
 */
public class AdminDashboardActivity extends BaseSecureActivity {

    private EditText etIssueStudentId;
    private EditText etIssueClassName;
    private EditText etIssueFullName;
    private EditText etIssueDob;
    private AutoCompleteTextView actvIssueDepartment;
    private EditText etAdminPuk;
    private TextView tvIssueCardLog;
    private TextView tvPukStatus;
    private TextView tvTargetUnblockCard;
    private TextView tvIssuedCardsCount;
    private RecyclerView rvIssuedCards;
    private String selectedUnblockStudentId = "";

    private TabLayout tabLayoutAdmin;
    private View scrollTabManageCards;
    private View scrollTabCreateCard;

    private EncryptedCacheManager cacheManager;
    private final List<StudentCardRecord> issuedCards = new ArrayList<>();
    private IssuedCardAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        etAdminPuk = findViewById(R.id.etAdminPuk);
        tvIssueCardLog = findViewById(R.id.tvIssueCardLog);
        tvPukStatus = findViewById(R.id.tvPukStatus);
        tvTargetUnblockCard = findViewById(R.id.tvTargetUnblockCard);
        tvIssuedCardsCount = findViewById(R.id.tvIssuedCardsCount);
        rvIssuedCards = findViewById(R.id.rvIssuedCards);

        TextView tvAdminIdentitySubtitle = findViewById(R.id.tvAdminIdentitySubtitle);
        if (tvAdminIdentitySubtitle != null) {
            AdminSessionManager adm = AdminSessionManager.getInstance(this);
            tvAdminIdentitySubtitle.setText(adm.getFullName());
        }

        Button btnBackToLogin = findViewById(R.id.btnBackToLogin);
        Button btnIssueNewCard = findViewById(R.id.btnIssueNewCard);
        Button btnUnblockByPuk = findViewById(R.id.btnUnblockByPuk);

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
        btnUnblockByPuk.setOnClickListener(v -> handleUnblockByPuk());
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

            // Auto-select locked card or maintain selected card for unblocking
            StudentCardRecord targetCard = null;
            if (selectedUnblockStudentId != null && !selectedUnblockStudentId.isEmpty()) {
                for (StudentCardRecord c : issuedCards) {
                    if (c.getStudentId().equalsIgnoreCase(selectedUnblockStudentId)) {
                        targetCard = c;
                        break;
                    }
                }
            }
            if (targetCard == null) {
                for (StudentCardRecord c : issuedCards) {
                    if (c.isCardLocked()) {
                        targetCard = c;
                        break;
                    }
                }
            }
            if (targetCard == null && !issuedCards.isEmpty()) {
                targetCard = issuedCards.get(0);
            }
            if (targetCard != null) {
                selectedUnblockStudentId = targetCard.getStudentId();
                if (tvTargetUnblockCard != null) {
                    tvTargetUnblockCard.setText("Thẻ đang chọn để mở khóa: " + targetCard.getFullName()
                            + " (" + targetCard.getStudentId() + ")"
                            + (targetCard.isCardLocked() ? " [Đang khóa PIN]" : ""));
                }
            }
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
     * Issues a new student smart card asynchronously and stores it in Server SQLite (`smartcard.db`).
     */
    private void handleIssueNewCard() {
        String studentId = etIssueStudentId.getText().toString().trim();
        String className = etIssueClassName.getText().toString().trim();
        String fullName = etIssueFullName.getText().toString().trim();
        String dob = etIssueDob.getText().toString().trim();
        String dept = actvIssueDepartment.getText().toString().trim();

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

            // 4. Downsample portrait to 150x150px, compress to WebP 75% (~3.5KB), and upload in APDU chunks (INS 0x22)
            String initials = fullName.length() >= 2 ? fullName.substring(0, 2).toUpperCase() : "SV";
            Bitmap avatarBmp = ImageOptimizer.generateSampleStudentAvatar(initials, 150);
            byte[] webpBytes = ImageOptimizer.compressToOptimizedWebP(avatarBmp);
            int packets = client.uploadAvatarInChunks(webpBytes);
            avatarBmp.recycle();

            // 5. Persist new card record into Server SQLite (`smartcard.db`)
            client.syncCardToServerDb(newCard);

            return "Modulus(" + modResp.getData().length + "B) | WebP("
                    + (webpBytes.length / 1024.0) + "KB, " + packets + " APDU packets)";
        }, (summary, mainBlockedMs, totalMs) -> {
            cacheManager.saveStudentProfile(newCard);
            loadCentralDatabaseFromServer();

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

    /**
     * Unblocks a PIN-locked card using the 8-digit Admin PUK (INS 0x12 - CN 1.3) and updates Server SQLite.
     * Default Admin PUK is 88888888. Only unblocks the selected/active card.
     */
    private void handleUnblockByPuk() {
        String puk = etAdminPuk.getText() != null ? etAdminPuk.getText().toString().trim() : "";
        if (puk.isEmpty()) {
            puk = "88888888";
            etAdminPuk.setText(puk);
        }
        if (puk.length() != 8) {
            Toast.makeText(this, "Mã PUK của Admin phải gồm đúng 8 chữ số (mặc định: 88888888)!", Toast.LENGTH_SHORT).show();
            return;
        }

        final String finalPuk = puk;
        final String targetStudentId = selectedUnblockStudentId;
        ApduThreadPool.getInstance().execute(() -> {
            ApduClient client = ApduClient.getInstance();
            CardResponse resp = client.sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_UNBLOCK_CARD_PUK,
                    (byte) 0x00,
                    (byte) 0x00,
                    finalPuk.getBytes(StandardCharsets.UTF_8)
            );
            if (resp.isSuccess()) {
                client.syncUnblockCardOnServerDb(targetStudentId);
            }
            return resp;
        }, (resp, mainBlockedMs, totalMs) -> {
            tvPukStatus.setVisibility(View.VISIBLE);
            if (resp.isSuccess()) {
                loadCentralDatabaseFromServer();
                String targetDisplay = !targetStudentId.isEmpty() ? (" [" + targetStudentId + "]") : "";
                tvPukStatus.setText("✓ Đã mở khóa thẻ" + targetDisplay + " thành công. PIN mặc định: 123456");
                Toast.makeText(this, "Đã mở khóa thẻ" + targetDisplay + "! PIN mặc định: 123456", Toast.LENGTH_SHORT).show();
            } else {
                tvPukStatus.setText("❌ Mở khóa thất bại (Kiểm tra lại thẻ đang gắn hoặc mã PUK)");
            }
        });
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

            holder.btnActivate.setOnClickListener(v -> {
                selectedUnblockStudentId = item.getStudentId();
                if (tvTargetUnblockCard != null) {
                    tvTargetUnblockCard.setText("Thẻ đang chọn để mở khóa: " + item.getFullName()
                            + " (" + item.getStudentId() + ")"
                            + (item.isCardLocked() ? " [Đang khóa PIN]" : ""));
                }
                long startNs = System.nanoTime();
                ApduThreadPool.getInstance().execute(() -> {
                    ApduClient.getInstance().activateVirtualCard(
                            item.getStudentId(),
                            item.toPipeDelimitedString(),
                            item.getDefaultPin() != null ? item.getDefaultPin() : "123456"
                    );
                    return true;
                }, (ok, mainBlockedMs, totalMs) -> {
                    long switchMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
                    cacheManager.saveStudentProfile(item);
                    Toast.makeText(AdminDashboardActivity.this,
                            "Đã gắn thẻ " + item.getStudentId() + " (" + item.getFullName()
                                    + ") vào JCIDE trong " + switchMs + " ms!",
                            Toast.LENGTH_SHORT).show();
                });
            });
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
            final Button btnActivate;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvAvatar = itemView.findViewById(R.id.tvItemAvatarInitials);
                tvTitle = itemView.findViewById(R.id.tvItemStudentTitle);
                tvSub = itemView.findViewById(R.id.tvItemStudentSub);
                tvStatus = itemView.findViewById(R.id.tvItemCardStatus);
                btnActivate = itemView.findViewById(R.id.btnActivateVirtualCard);
            }
        }
    }
}
