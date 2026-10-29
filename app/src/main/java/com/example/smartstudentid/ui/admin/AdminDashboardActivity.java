package com.example.smartstudentid.ui.admin;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.example.smartstudentid.security.BaseSecureActivity;
import com.example.smartstudentid.security.EncryptedCacheManager;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Admin Role Dashboard Activity (Phòng Đào Tạo) implementing CN 1.1, 1.2, 1.3:
 * 1. Issue New Student Card: Encrypts profile with AES-128 (INS 0x20), generates on-card
 *    RSA-1024 key pair (INS 0x13, P1=00), compresses WebP avatar (INS 0x22), and stores Public Key.
 * 2. Unblock Card by Admin PUK: Sends 8-digit PUK (INS 0x12) to invoke OwnerPIN.resetAndUnblock().
 * 3. Virtual Card Switcher: Activates any issued student card on the JCIDE reader in < 50ms.
 * 4. Academic & Library Summary: Displays attendance logs and borrowed library books.
 */
public class AdminDashboardActivity extends BaseSecureActivity {

    private EditText etIssueStudentId;
    private EditText etIssueClassName;
    private EditText etIssueFullName;
    private EditText etIssueDob;
    private EditText etIssueDepartment;
    private EditText etAdminPuk;
    private TextView tvIssueCardLog;
    private TextView tvPukStatus;
    private TextView tvAdminAcademicSummary;
    private RecyclerView rvIssuedCards;

    private EncryptedCacheManager cacheManager;
    private List<StudentCardRecord> issuedCards;
    private IssuedCardAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_dashboard);

        cacheManager = EncryptedCacheManager.getInstance(this);
        issuedCards = cacheManager.getIssuedCardsList();

        bindViews();
        setupRecyclerView();
        refreshAcademicSummary();
    }

    private void bindViews() {
        etIssueStudentId = findViewById(R.id.etIssueStudentId);
        etIssueClassName = findViewById(R.id.etIssueClassName);
        etIssueFullName = findViewById(R.id.etIssueFullName);
        etIssueDob = findViewById(R.id.etIssueDob);
        etIssueDepartment = findViewById(R.id.etIssueDepartment);
        etAdminPuk = findViewById(R.id.etAdminPuk);
        tvIssueCardLog = findViewById(R.id.tvIssueCardLog);
        tvPukStatus = findViewById(R.id.tvPukStatus);
        tvAdminAcademicSummary = findViewById(R.id.tvAdminAcademicSummary);
        rvIssuedCards = findViewById(R.id.rvIssuedCards);

        Button btnBackToLogin = findViewById(R.id.btnBackToLogin);
        Button btnIssueNewCard = findViewById(R.id.btnIssueNewCard);
        Button btnUnblockByPuk = findViewById(R.id.btnUnblockByPuk);

        btnBackToLogin.setOnClickListener(v -> finish());
        btnIssueNewCard.setOnClickListener(v -> handleIssueNewCard());
        btnUnblockByPuk.setOnClickListener(v -> handleUnblockByPuk());
    }

    private void setupRecyclerView() {
        rvIssuedCards.setLayoutManager(new LinearLayoutManager(this));
        adapter = new IssuedCardAdapter();
        rvIssuedCards.setAdapter(adapter);
    }

    /**
     * Issues a new student smart card asynchronously on the background thread pool (CN 1.1).
     */
    private void handleIssueNewCard() {
        String studentId = etIssueStudentId.getText().toString().trim();
        String className = etIssueClassName.getText().toString().trim();
        String fullName = etIssueFullName.getText().toString().trim();
        String dob = etIssueDob.getText().toString().trim();
        String dept = etIssueDepartment.getText().toString().trim();

        if (studentId.isEmpty() || fullName.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập đầy đủ MSSV và Họ tên!", Toast.LENGTH_SHORT).show();
            return;
        }

        tvIssueCardLog.setText("Đang khởi tạo thẻ mới trên Background ThreadPool...");
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

            return "Modulus(" + modResp.getData().length + "B) | WebP("
                    + (webpBytes.length / 1024.0) + "KB, " + packets + " APDU packets)";
        }, (summary, mainBlockedMs, totalMs) -> {
            // Update or insert into issued cards list
            boolean updated = false;
            for (int i = 0; i < issuedCards.size(); i++) {
                if (issuedCards.get(i).getStudentId().equalsIgnoreCase(newCard.getStudentId())) {
                    issuedCards.set(i, newCard);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                issuedCards.add(0, newCard);
            }
            cacheManager.saveIssuedCardsList(issuedCards);
            cacheManager.saveStudentProfile(newCard);
            adapter.notifyDataSetChanged();

            tvIssueCardLog.setText(
                    "✅ PHÁT HÀNH THẺ THÀNH CÔNG (" + totalMs + " ms)\n"
                            + "• Sinh viên: " + newCard.getFullName() + " (" + newCard.getStudentId() + ")\n"
                            + "• AES-128 EEPROM & RSA-1024: " + summary
            );
            Toast.makeText(this, "Đã phát hành và gắn thẻ " + newCard.getStudentId() + " vào JCIDE!",
                    Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Unblocks a PIN-locked card using the 8-digit Admin PUK (INS 0x12 - CN 1.3).
     */
    private void handleUnblockByPuk() {
        String puk = etAdminPuk.getText().toString().trim();
        if (puk.length() != 8) {
            Toast.makeText(this, "Mã PUK của Admin phải gồm đúng 8 chữ số!", Toast.LENGTH_SHORT).show();
            return;
        }

        ApduThreadPool.getInstance().execute(() ->
                ApduClient.getInstance().sendApdu(
                        ApduCommands.CLA_SMART_STUDENT,
                        ApduCommands.INS_UNBLOCK_CARD_PUK,
                        (byte) 0x00,
                        (byte) 0x00,
                        puk.getBytes(StandardCharsets.UTF_8)
                ), (resp, mainBlockedMs, totalMs) -> {
            if (resp.isSuccess()) {
                for (StudentCardRecord c : issuedCards) {
                    c.setCardLocked(false);
                }
                cacheManager.saveIssuedCardsList(issuedCards);
                adapter.notifyDataSetChanged();
                tvPukStatus.setText("✅ ĐÃ MỞ KHÓA THẺ THÀNH CÔNG (SW=9000)! OwnerPIN.resetAndUnblock() đã đưa PIN về 123456 (3 lần thử).");
            } else {
                tvPukStatus.setText("❌ SAI MÃ PUK ADMIN (SW=" + resp.getSwHex() + ")! Vui lòng nhập đúng 88888888.");
            }
        });
    }

    private void refreshAcademicSummary() {
        List<AttendanceRecord> attList = cacheManager.getAttendanceHistory();
        List<BookLoanRecord> books = cacheManager.getBookLoans();

        int borrowedCount = 0;
        StringBuilder bookNames = new StringBuilder();
        for (BookLoanRecord b : books) {
            if (b.isBorrowed()) {
                borrowedCount++;
                bookNames.append("  • [0x").append(String.format("%04X", b.getBookCode()))
                        .append("] ").append(b.getBookTitle()).append("\n");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("• Tổng số thẻ SV đã phát hành: ").append(issuedCards.size()).append(" thẻ\n");
        sb.append("• Bản ghi điểm danh chữ ký RSA: ").append(attList.size()).append(" lượt\n");
        sb.append("• Sách thư viện đang được mượn: ").append(borrowedCount).append(" cuốn\n");
        if (borrowedCount > 0) {
            sb.append(bookNames);
        }
        tvAdminAcademicSummary.setText(sb.toString().trim());
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
            holder.tvTitle.setText(item.getStudentId() + " — " + item.getFullName());
            holder.tvSub.setText("Lớp: " + item.getClassName() + " | Khoa: " + item.getDepartment()
                    + " | ĐRL: " + item.getTrainingScore() + " | ĐD: " + item.getAttendanceCount());

            if (item.isCardLocked()) {
                holder.tvStatus.setText("🔒 Trạng thái: Đang bị khóa PIN (Dùng PUK để mở)");
                holder.tvStatus.setTextColor(0xFFF43F5E);
            } else {
                holder.tvStatus.setText("✅ Trạng thái: Hoạt động (AES-128 & RSA-1024 Ready)");
                holder.tvStatus.setTextColor(0xFF34D399);
            }

            holder.btnActivate.setOnClickListener(v -> {
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
            final TextView tvTitle;
            final TextView tvSub;
            final TextView tvStatus;
            final Button btnActivate;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tvItemStudentTitle);
                tvSub = itemView.findViewById(R.id.tvItemStudentSub);
                tvStatus = itemView.findViewById(R.id.tvItemCardStatus);
                btnActivate = itemView.findViewById(R.id.btnActivateVirtualCard);
            }
        }
    }
}
