package com.example.smartstudentid.ui.student;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.model.WalletTransactionRecord;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.example.smartstudentid.security.RsaCardVerifier;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * E-Wallet tab (Phân hệ Ví điện tử chuẩn Nghiệp vụ Ngân hàng & EMV Smart Card).
 * Features funding-source / merchant selection, banking limits (1,000 VND step,
 * 50,000,000 VND cap), 2-step transaction confirmation with PIN verification (0x10),
 * 13-byte canonical RSA-1024 receipt signing & verification (0x40/0x41/0x42),
 * balance masking, cashflow totals, and interactive bank statement ledger.
 */
public class SecurityOptLabFragment extends Fragment {

    private static final long MAX_WALLET_BALANCE_VND = 50_000_000L;
    private static final long MIN_CREDIT_VND = 10_000L;
    private static final long MAX_CREDIT_VND = 20_000_000L;
    private static final long MIN_DEBIT_VND = 1_000L;
    private static final long MAX_DEBIT_VND = 10_000_000L;
    private static final long DENOMINATION_STEP_VND = 1_000L;

    private static final String FILTER_ALL = "ALL";
    private static final String FILTER_CREDIT = "CREDIT";
    private static final String FILTER_DEBIT = "DEBIT";

    private TextView tvWalletAccountNumber;
    private TextView tvWalletBalance;
    private TextView tvWalletOwnerInfo;
    private TextView tvWalletSyncBadge;
    private TextView tvWalletStatus;
    private TextView tvWalletTotalCredit;
    private TextView tvWalletTotalDebit;
    private MaterialButton btnToggleBalanceMask;
    private MaterialButton btnSyncWalletBalance;

    private MaterialCardView cardWalletLastReceipt;
    private TextView tvWalletLastReceiptTitle;
    private TextView tvWalletLastReceiptDetail;
    private TextView tvWalletLastReceiptSig;

    private MaterialButton btnSourceVcb;
    private MaterialButton btnSourceBidv;
    private MaterialButton btnSourceMb;
    private MaterialButton btnSourceCounter;
    private TextView tvSelectedFundingSource;
    private EditText etWalletCreditAmount;
    private MaterialButton btnWalletCredit;

    private TextView tvSelectedMerchant;
    private EditText etWalletDebitAmount;
    private EditText etWalletDebitNote;
    private MaterialButton btnWalletDebit;

    private MaterialButton btnFilterAll;
    private MaterialButton btnFilterCredit;
    private MaterialButton btnFilterDebit;
    private MaterialButton btnRefreshWalletHistory;
    private TextView tvWalletTxEmpty;
    private LinearLayout llWalletTransactions;

    private EncryptedCacheManager encryptedCacheManager;
    private final List<WalletTransactionRecord> offlineSessionTransactions = new ArrayList<>();
    private final List<WalletTransactionRecord> currentDisplaySource = new ArrayList<>();

    private String selectedFundingSource = "Vietcombank • *8829";
    private String selectedMerchant = "Căng-tin Học viện Khu A";
    private String activeFilter = FILTER_ALL;
    private boolean balanceMasked = false;
    private int currentLiveBalance = 500_000;
    private WalletTransactionRecord lastCompletedTx = null;
    private boolean isProcessing = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_security_opt_lab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        encryptedCacheManager = EncryptedCacheManager.getInstance(requireContext());

        bindViews(view);
        setupListeners(view);
        updateSourceButtonStyles(btnSourceVcb);
        updateFilterButtonStyles();
        bindCachedHeader();
        syncBalanceFromCard(false);
        loadWalletTransactions();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (encryptedCacheManager != null) {
            bindCachedHeader();
            syncBalanceFromCard(false);
            loadWalletTransactions();
        }
    }

    private void bindViews(View view) {
        tvWalletAccountNumber = view.findViewById(R.id.tvWalletAccountNumber);
        tvWalletBalance = view.findViewById(R.id.tvWalletBalance);
        tvWalletOwnerInfo = view.findViewById(R.id.tvWalletOwnerInfo);
        tvWalletSyncBadge = view.findViewById(R.id.tvWalletSyncBadge);
        tvWalletStatus = view.findViewById(R.id.tvWalletStatus);
        tvWalletTotalCredit = view.findViewById(R.id.tvWalletTotalCredit);
        tvWalletTotalDebit = view.findViewById(R.id.tvWalletTotalDebit);
        btnToggleBalanceMask = view.findViewById(R.id.btnToggleBalanceMask);
        btnSyncWalletBalance = view.findViewById(R.id.btnSyncWalletBalance);

        cardWalletLastReceipt = view.findViewById(R.id.cardWalletLastReceipt);
        tvWalletLastReceiptTitle = view.findViewById(R.id.tvWalletLastReceiptTitle);
        tvWalletLastReceiptDetail = view.findViewById(R.id.tvWalletLastReceiptDetail);
        tvWalletLastReceiptSig = view.findViewById(R.id.tvWalletLastReceiptSig);

        btnSourceVcb = view.findViewById(R.id.btnSourceVcb);
        btnSourceBidv = view.findViewById(R.id.btnSourceBidv);
        btnSourceMb = view.findViewById(R.id.btnSourceMb);
        btnSourceCounter = view.findViewById(R.id.btnSourceCounter);
        tvSelectedFundingSource = view.findViewById(R.id.tvSelectedFundingSource);
        etWalletCreditAmount = view.findViewById(R.id.etWalletCreditAmount);
        btnWalletCredit = view.findViewById(R.id.btnWalletCredit);

        tvSelectedMerchant = view.findViewById(R.id.tvSelectedMerchant);
        etWalletDebitAmount = view.findViewById(R.id.etWalletDebitAmount);
        etWalletDebitNote = view.findViewById(R.id.etWalletDebitNote);
        btnWalletDebit = view.findViewById(R.id.btnWalletDebit);

        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterCredit = view.findViewById(R.id.btnFilterCredit);
        btnFilterDebit = view.findViewById(R.id.btnFilterDebit);
        btnRefreshWalletHistory = view.findViewById(R.id.btnRefreshWalletHistory);
        tvWalletTxEmpty = view.findViewById(R.id.tvWalletTxEmpty);
        llWalletTransactions = view.findViewById(R.id.llWalletTransactions);
    }

    private void setupListeners(View view) {
        btnToggleBalanceMask.setOnClickListener(v -> {
            balanceMasked = !balanceMasked;
            btnToggleBalanceMask.setText(balanceMasked ? "Hiện số dư" : "Ẩn số dư");
            renderBalanceText();
        });

        btnSyncWalletBalance.setOnClickListener(v -> syncBalanceFromCard(true));

        cardWalletLastReceipt.setOnClickListener(v -> {
            if (lastCompletedTx != null) {
                showTransactionReceiptDialog(lastCompletedTx);
            }
        });

        btnSourceVcb.setOnClickListener(v -> selectFundingSource(btnSourceVcb, "Vietcombank • *8829"));
        btnSourceBidv.setOnClickListener(v -> selectFundingSource(btnSourceBidv, "BIDV • *4510"));
        btnSourceMb.setOnClickListener(v -> selectFundingSource(btnSourceMb, "MB Bank • *1902"));
        btnSourceCounter.setOnClickListener(v -> selectFundingSource(btnSourceCounter, "Quầy Tài vụ Học viện KMA"));

        view.findViewById(R.id.btnQuickCredit50k).setOnClickListener(v -> setCreditPreset(50_000));
        view.findViewById(R.id.btnQuickCredit100k).setOnClickListener(v -> setCreditPreset(100_000));
        view.findViewById(R.id.btnQuickCredit200k).setOnClickListener(v -> setCreditPreset(200_000));
        view.findViewById(R.id.btnQuickCredit500k).setOnClickListener(v -> setCreditPreset(500_000));

        view.findViewById(R.id.btnQuickPayCanteen).setOnClickListener(v -> setDebitPreset(
                35_000, "Căng-tin Học viện Khu A", "Thanh toán hóa đơn căng-tin"));
        view.findViewById(R.id.btnQuickPayParking).setOnClickListener(v -> setDebitPreset(
                50_000, "Trạm Gửi xe Học viện KMA", "Thanh toán vé gửi xe tháng"));
        view.findViewById(R.id.btnQuickPayPrint).setOnClickListener(v -> setDebitPreset(
                15_000, "Trung tâm Thư viện - In ấn", "Thanh toán phí in tài liệu"));
        view.findViewById(R.id.btnQuickPayClub).setOnClickListener(v -> setDebitPreset(
                100_000, "Phòng Tài chính - Kế toán KMA", "Thanh toán lệ phí học vụ"));

        btnWalletCredit.setOnClickListener(v -> handleActionClick(true));
        btnWalletDebit.setOnClickListener(v -> handleActionClick(false));

        btnFilterAll.setOnClickListener(v -> setHistoryFilter(FILTER_ALL));
        btnFilterCredit.setOnClickListener(v -> setHistoryFilter(FILTER_CREDIT));
        btnFilterDebit.setOnClickListener(v -> setHistoryFilter(FILTER_DEBIT));
        btnRefreshWalletHistory.setOnClickListener(v -> loadWalletTransactions());
    }

    private void selectFundingSource(MaterialButton selectedBtn, String sourceLabel) {
        selectedFundingSource = sourceLabel;
        tvSelectedFundingSource.setText("Nguồn tiền: " + sourceLabel + " • Miễn phí");
        updateSourceButtonStyles(selectedBtn);
    }

    private void updateSourceButtonStyles(MaterialButton activeBtn) {
        MaterialButton[] all = {btnSourceVcb, btnSourceBidv, btnSourceMb, btnSourceCounter};
        for (MaterialButton btn : all) {
            boolean active = (btn == activeBtn);
            btn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                    requireContext(), active ? R.color.emerald_50 : R.color.transparent)));
            btn.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(
                    requireContext(), active ? R.color.emerald_600 : R.color.slate_200)));
            btn.setTextColor(ContextCompat.getColor(
                    requireContext(), active ? R.color.emerald_700 : R.color.slate_600));
        }
    }

    private void setCreditPreset(int amount) {
        etWalletCreditAmount.setText(String.valueOf(amount));
        etWalletCreditAmount.setSelection(etWalletCreditAmount.getText().length());
    }

    private void setDebitPreset(int amount, String merchant, String note) {
        selectedMerchant = merchant;
        tvSelectedMerchant.setText("Đơn vị nhận: " + merchant);
        etWalletDebitAmount.setText(String.valueOf(amount));
        etWalletDebitAmount.setSelection(etWalletDebitAmount.getText().length());
        etWalletDebitNote.setText(note);
    }

    private void setHistoryFilter(String filter) {
        activeFilter = filter;
        updateFilterButtonStyles();
        renderFilteredTransactions();
    }

    private void updateFilterButtonStyles() {
        applyFilterStyle(btnFilterAll, FILTER_ALL.equals(activeFilter));
        applyFilterStyle(btnFilterCredit, FILTER_CREDIT.equals(activeFilter));
        applyFilterStyle(btnFilterDebit, FILTER_DEBIT.equals(activeFilter));
    }

    private void applyFilterStyle(MaterialButton btn, boolean active) {
        btn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                requireContext(), active ? R.color.brand_600 : R.color.brand_50)));
        btn.setTextColor(ContextCompat.getColor(
                requireContext(), active ? R.color.white : R.color.brand_600));
    }

    private String getCurrentStudentId() {
        StudentCardRecord rec = encryptedCacheManager.getStudentProfile();
        return (rec != null && rec.getStudentId() != null && !rec.getStudentId().isEmpty())
                ? rec.getStudentId()
                : "CT070315";
    }

    private void bindCachedHeader() {
        StudentCardRecord rec = encryptedCacheManager.getStudentProfile();
        String id = getCurrentStudentId();
        String name = (rec != null && rec.getFullName() != null && !rec.getFullName().isEmpty())
                ? rec.getFullName()
                : "Vũ Ngọc Hải";
        currentLiveBalance = (rec != null) ? rec.getWalletBalance() : 500_000;

        tvWalletAccountNumber.setText("Số tài khoản: " + id);
        tvWalletOwnerInfo.setText("Chủ ví: " + name);
        renderBalanceText();
    }

    private void renderBalanceText() {
        tvWalletBalance.setText(balanceMasked ? "******** đ" : formatVnd(currentLiveBalance));
    }

    private void handleActionClick(boolean isCredit) {
        if (isProcessing) {
            Toast.makeText(requireContext(), "Đang xử lý giao dịch, vui lòng chờ...", Toast.LENGTH_SHORT).show();
            return;
        }
        long rawAmount = parseAmountInput(isCredit ? etWalletCreditAmount : etWalletDebitAmount);
        if (rawAmount <= 0) {
            Toast.makeText(requireContext(), "Vui lòng nhập số tiền hợp lệ.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (rawAmount % DENOMINATION_STEP_VND != 0) {
            Toast.makeText(requireContext(), "Số tiền giao dịch phải là bội số của 1.000 đ.", Toast.LENGTH_LONG).show();
            return;
        }

        if (isCredit) {
            if (rawAmount < MIN_CREDIT_VND || rawAmount > MAX_CREDIT_VND) {
                Toast.makeText(requireContext(),
                        "Hạn mức nạp từ 10.000 đ đến 20.000.000 đ cho mỗi giao dịch.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if ((long) currentLiveBalance + rawAmount > MAX_WALLET_BALANCE_VND) {
                Toast.makeText(requireContext(),
                        "Số dư sau nạp vượt hạn mức tối đa của ví (50.000.000 đ).",
                        Toast.LENGTH_LONG).show();
                return;
            }
            int amount = (int) rawAmount;
            String desc = "Nạp tiền vào ví qua " + selectedFundingSource;
            showBankConfirmationDialog(true, amount, selectedFundingSource, desc);
        } else {
            if (rawAmount < MIN_DEBIT_VND || rawAmount > MAX_DEBIT_VND) {
                Toast.makeText(requireContext(),
                        "Hạn mức thanh toán từ 1.000 đ đến 10.000.000 đ cho mỗi giao dịch.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            int amount = (int) rawAmount;
            String customNote = etWalletDebitNote.getText().toString().trim();
            String desc = customNote.isEmpty()
                    ? ("Thanh toán " + selectedMerchant)
                    : customNote;
            showBankConfirmationDialog(false, amount, selectedMerchant, desc);
        }
    }

    /**
     * Step 2 of Banking UX: Review full ledger breakdown & authenticate with 6-digit card PIN.
     */
    private void showBankConfirmationDialog(boolean isCredit, int amount, String counterparty, String description) {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        container.setPadding(pad, (int) (12 * density), pad, (int) (4 * density));

        long projectedBalance = isCredit
                ? ((long) currentLiveBalance + amount)
                : ((long) currentLiveBalance - amount);

        LinearLayout summaryBox = new LinearLayout(requireContext());
        summaryBox.setOrientation(LinearLayout.VERTICAL);
        summaryBox.setBackgroundResource(R.drawable.bg_soft_panel);
        int boxPad = (int) (12 * density);
        summaryBox.setPadding(boxPad, boxPad, boxPad, boxPad);

        addLedgerSummaryRow(summaryBox, "Tài khoản ví", getCurrentStudentId(), false);
        addLedgerSummaryRow(summaryBox, "Loại giao dịch", isCredit ? "Nạp tiền vào ví" : "Thanh toán dịch vụ", false);
        addLedgerSummaryRow(summaryBox, isCredit ? "Nguồn tiền" : "Đơn vị thụ hưởng", counterparty, false);
        addLedgerSummaryRow(summaryBox, "Số dư hiện tại", formatVnd(currentLiveBalance), false);
        addLedgerSummaryRow(summaryBox, "Số tiền giao dịch", (isCredit ? "+" : "-") + formatVnd(amount), true);
        addLedgerSummaryRow(summaryBox, "Phí giao dịch", "Miễn phí", false);
        addLedgerSummaryRow(summaryBox, "Số dư dự kiến",
                projectedBalance >= 0
                        ? formatVnd(projectedBalance)
                        : "Không đủ (" + formatVnd(currentLiveBalance) + ")",
                false);
        addLedgerSummaryRow(summaryBox, "Nội dung", description, false);
        container.addView(summaryBox);

        if (!isCredit && projectedBalance < 0) {
            TextView tvWarn = new TextView(requireContext());
            tvWarn.setText("⚠ Số dư trong ví hiện không đủ để thực hiện giao dịch này.");
            tvWarn.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_600));
            tvWarn.setTextSize(11.5f);
            LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            warnLp.topMargin = (int) (8 * density);
            tvWarn.setLayoutParams(warnLp);
            container.addView(tvWarn);
        }

        TextView tvPinPrompt = new TextView(requireContext());
        tvPinPrompt.setText("Nhập mã PIN xác nhận giao dịch");
        tvPinPrompt.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_700));
        tvPinPrompt.setTextSize(12.5f);
        tvPinPrompt.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams promptLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptLp.topMargin = (int) (12 * density);
        tvPinPrompt.setLayoutParams(promptLp);
        container.addView(tvPinPrompt);

        EditText etPin = new EditText(requireContext());
        etPin.setHint("Nhập mã PIN 6 số");
        etPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPin.setBackgroundResource(R.drawable.bg_input);
        int inputPad = (int) (12 * density);
        etPin.setPadding(inputPad, inputPad, inputPad, inputPad);
        etPin.setTextSize(14f);
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinLp.topMargin = (int) (6 * density);
        etPin.setLayoutParams(pinLp);
        container.addView(etPin);

        new AlertDialog.Builder(requireContext())
                .setTitle(isCredit ? "Xác nhận nạp tiền" : "Xác nhận thanh toán")
                .setView(container)
                .setPositiveButton("Xác nhận", (dialog, which) -> {
                    String pin = etPin.getText().toString().trim();
                    if (pin.length() < 4 || pin.length() > 8) {
                        Toast.makeText(requireContext(), "Vui lòng nhập mã PIN gồm 4–8 chữ số", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    executeWalletOperation(isCredit, amount, counterparty, description, pin);
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void addLedgerSummaryRow(LinearLayout parent, String label, String value, boolean highlight) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (5 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(lp);

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_500));
        tvLabel.setTextSize(12f);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f));

        TextView tvVal = new TextView(requireContext());
        tvVal.setText(value);
        tvVal.setGravity(Gravity.END);
        tvVal.setTextSize(highlight ? 13.5f : 12f);
        tvVal.setTypeface(null, highlight ? Typeface.BOLD : Typeface.NORMAL);
        tvVal.setTextColor(ContextCompat.getColor(
                requireContext(), highlight ? R.color.brand_700 : R.color.slate_900));
        tvVal.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f));

        row.addView(tvLabel);
        row.addView(tvVal);
        parent.addView(row);
    }

    private void executeWalletOperation(boolean isCredit, int amount, String counterparty,
                                        String description, String pin) {
        setProcessingState(true, null);

        ApduThreadPool.getInstance().execute(() -> {
            long startMs = System.currentTimeMillis();
            ApduClient client = ApduClient.getInstance();

            // 1. Verify OwnerPIN on Java Card (INS 0x10)
            CardResponse pinResp = client.sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_VERIFY_PIN,
                    (byte) 0x00,
                    (byte) 0x00,
                    pin.getBytes(StandardCharsets.UTF_8)
            );
            if (!pinResp.isSuccess()) {
                if (pinResp.isCardLocked()) {
                    return WalletOpResult.failure("Thẻ đã bị khóa do nhập sai mã PIN quá số lần quy định.");
                }
                int tries = pinResp.getRemainingPinTries();
                if (tries >= 0) {
                    return WalletOpResult.failure("Mã PIN không đúng! Bạn còn " + tries + " lần thử.");
                }
                return WalletOpResult.failure("Xác thực mã PIN thất bại.");
            }

            // 2. Capture balanceBefore right from card (INS 0x42) before mutation
            StudentCardRecord cached = encryptedCacheManager.getStudentProfile();
            int balanceBefore = cached != null ? cached.getWalletBalance() : currentLiveBalance;
            CardResponse preBalResp = client.sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_WALLET_GET_BALANCE,
                    (byte) 0x00,
                    (byte) 0x00,
                    null
            );
            if (preBalResp.isSuccess() && preBalResp.getData().length >= 4) {
                balanceBefore = decodeBalance4Bytes(preBalResp.getData());
            }

            // 3. Send APDU 0x40 / 0x41 with [amount 4B][timestamp 4B]
            long tsSec = System.currentTimeMillis() / 1000L;
            int tsInt = (int) tsSec;
            byte[] apduPayload = encodeTwoInts(amount, tsInt);
            byte ins = isCredit ? ApduCommands.INS_WALLET_CREDIT : ApduCommands.INS_WALLET_DEBIT;
            CardResponse opResp = client.sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ins,
                    (byte) 0x00,
                    (byte) 0x00,
                    apduPayload
            );
            if (opResp.getSw() == ApduCommands.SW_CONDITIONS_NOT_SATISFIED) {
                return WalletOpResult.failure("Số dư trong ví không đủ để thanh toán "
                        + formatVnd(amount) + ".");
            }
            if (opResp.getSw() == ApduCommands.SW_FILE_FULL) {
                return WalletOpResult.failure("Số dư ví vượt quá hạn mức tối đa 50.000.000 đ.");
            }
            if (!opResp.isSuccess()) {
                return WalletOpResult.failure("Giao dịch không thành công, vui lòng thử lại.");
            }

            // 4. Query authoritative post-transaction balance via INS 0x42
            CardResponse postBalResp = client.sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_WALLET_GET_BALANCE,
                    (byte) 0x00,
                    (byte) 0x00,
                    null
            );
            int balanceAfter = (postBalResp.isSuccess() && postBalResp.getData().length >= 4)
                    ? decodeBalance4Bytes(postBalResp.getData())
                    : Math.max(0, isCredit ? (balanceBefore + amount) : (balanceBefore - amount));

            // 5. Verify canonical 13-byte RSA-1024 receipt: [INS 1B][Amount 4B][BalanceAfter 4B][Timestamp 4B]
            byte[] sigBytes = opResp.getData();
            String sigHex = ApduCommands.bytesToHex(sigBytes);
            byte[] canonicalReceipt = ApduCommands.buildCanonicalWalletReceipt(ins, amount, balanceAfter, tsInt);
            boolean rsaVerified = verifyCardTransactionSignature(client, canonicalReceipt, sigBytes);

            String studentId = getCurrentStudentId();
            if (cached != null) {
                cached.setWalletBalance(balanceAfter);
                encryptedCacheManager.saveStudentProfile(cached);
            }

            String txRef = generateBankTxRef(tsSec & 0xFFFFFFFFL, amount);
            WalletTransactionRecord tx = new WalletTransactionRecord(
                    studentId,
                    isCredit ? FILTER_CREDIT : FILTER_DEBIT,
                    amount,
                    balanceBefore,
                    balanceAfter,
                    txRef,
                    counterparty,
                    description,
                    tsSec & 0xFFFFFFFFL,
                    sigHex,
                    rsaVerified
            );
            client.syncWalletTransactionToServerDb(tx);

            long elapsedMs = System.currentTimeMillis() - startMs;
            return WalletOpResult.success(tx, balanceAfter, rsaVerified, opResp.getSwHex(), elapsedMs);
        }, (result, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            setProcessingState(false, null);

            if (result == null) {
                tvWalletStatus.setVisibility(View.GONE);
                Toast.makeText(requireContext(), "Giao dịch thất bại, vui lòng thử lại.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!result.success) {
                tvWalletSyncBadge.setText("● Không thành công");
                tvWalletStatus.setVisibility(View.GONE);
                Toast.makeText(requireContext(), result.errorMessage, Toast.LENGTH_LONG).show();
                syncBalanceFromCard(false);
                return;
            }

            currentLiveBalance = result.newBalance;
            renderBalanceText();
            tvWalletSyncBadge.setText("● Đang hoạt động");
            tvWalletStatus.setVisibility(View.GONE);

            lastCompletedTx = result.tx;
            showReceiptBanner(result);

            if (result.tx.isCredit()) {
                etWalletCreditAmount.setText("");
            } else {
                etWalletDebitAmount.setText("");
                etWalletDebitNote.setText("");
            }

            addOfflineFallbackTx(result.tx);
            loadWalletTransactions();
            Toast.makeText(requireContext(),
                    (result.tx.isCredit() ? "Nạp +" : "Thanh toán -")
                            + formatVnd(result.tx.getAmount()) + " (" + result.tx.getTxRef() + ")",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private boolean verifyCardTransactionSignature(ApduClient client, byte[] canonicalReceipt, byte[] sigBytes) {
        if (sigBytes == null || sigBytes.length == 0) {
            return false;
        }
        CardResponse modResp = client.sendApdu(
                ApduCommands.CLA_SMART_STUDENT,
                ApduCommands.INS_GET_PUBLIC_KEY,
                ApduCommands.P1_GET_MODULUS,
                (byte) 0x00,
                null
        );
        CardResponse expResp = client.sendApdu(
                ApduCommands.CLA_SMART_STUDENT,
                ApduCommands.INS_GET_PUBLIC_KEY,
                ApduCommands.P1_GET_EXPONENT,
                (byte) 0x00,
                null
        );
        if (!modResp.isSuccess() || !expResp.isSuccess()
                || modResp.getData().length == 0 || expResp.getData().length == 0) {
            return false;
        }
        return new RsaCardVerifier().verifySignature(
                modResp.getData(),
                expResp.getData(),
                canonicalReceipt,
                sigBytes
        );
    }

    private void showReceiptBanner(WalletOpResult result) {
        cardWalletLastReceipt.setVisibility(View.VISIBLE);
        WalletTransactionRecord tx = result.tx;
        boolean credit = tx.isCredit();
        tvWalletLastReceiptTitle.setText(
                (credit ? "+" : "-") + formatVnd(tx.getAmount()) + " • "
                        + (credit ? "Nạp tiền thành công" : "Thanh toán thành công"));
        tvWalletLastReceiptDetail.setText("Mã GD: " + tx.getTxRef() + " • " + tx.getCounterparty()
                + "\nSố dư mới: " + formatVnd(tx.getBalanceAfter()));
        tvWalletLastReceiptSig.setText("Nhấn để xem chi tiết biên lai");
        tvWalletLastReceiptSig.setTypeface(null, Typeface.NORMAL);
        tvWalletLastReceiptSig.setVisibility(View.VISIBLE);
    }

    private void syncBalanceFromCard(boolean showToast) {
        tvWalletSyncBadge.setText("⏳ Đang cập nhật...");
        ApduThreadPool.getInstance().execute(() -> {
            CardResponse resp = ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_WALLET_GET_BALANCE,
                    (byte) 0x00,
                    (byte) 0x00,
                    null
            );
            if (resp.isSuccess() && resp.getData().length >= 4) {
                int bal = decodeBalance4Bytes(resp.getData());
                StudentCardRecord cached = encryptedCacheManager.getStudentProfile();
                if (cached != null) {
                    cached.setWalletBalance(bal);
                    encryptedCacheManager.saveStudentProfile(cached);
                }
                return bal;
            }
            return null;
        }, (balance, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            if (balance != null) {
                currentLiveBalance = balance;
                renderBalanceText();
                tvWalletSyncBadge.setText("● Đang hoạt động");
                tvWalletStatus.setVisibility(View.GONE);
                if (showToast) {
                    Toast.makeText(requireContext(),
                            "Đã cập nhật số dư mới nhất",
                            Toast.LENGTH_SHORT).show();
                }
            } else {
                tvWalletSyncBadge.setText("● Chế độ ngoại tuyến");
                if (showToast) {
                    Toast.makeText(requireContext(),
                            "Đang hiển thị số dư lưu gần nhất",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void loadWalletTransactions() {
        String studentId = getCurrentStudentId();
        ApduThreadPool.getInstance().execute(
                () -> ApduClient.getInstance().fetchWalletTransactionsByStudent(studentId),
                (serverList, mainBlockedMs, totalMs) -> {
                    if (!isAdded()) return;
                    currentDisplaySource.clear();
                    if (serverList != null && !serverList.isEmpty()) {
                        currentDisplaySource.addAll(serverList);
                    } else {
                        currentDisplaySource.addAll(getOfflineTransactionsForStudent(studentId));
                    }
                    updateCashflowTotals();
                    renderFilteredTransactions();
                }
        );
    }

    private void updateCashflowTotals() {
        long totalCredit = 0L;
        long totalDebit = 0L;
        for (WalletTransactionRecord tx : currentDisplaySource) {
            if (tx.isCredit()) {
                totalCredit += tx.getAmount();
            } else {
                totalDebit += tx.getAmount();
            }
        }
        tvWalletTotalCredit.setText("+" + formatVnd(totalCredit));
        tvWalletTotalDebit.setText("-" + formatVnd(totalDebit));
    }

    private void renderFilteredTransactions() {
        List<WalletTransactionRecord> filtered = new ArrayList<>();
        for (WalletTransactionRecord tx : currentDisplaySource) {
            if (FILTER_ALL.equals(activeFilter)) {
                filtered.add(tx);
            } else if (FILTER_CREDIT.equals(activeFilter) && tx.isCredit()) {
                filtered.add(tx);
            } else if (FILTER_DEBIT.equals(activeFilter) && !tx.isCredit()) {
                filtered.add(tx);
            }
        }
        renderTransactionHistory(filtered);
    }

    private void renderTransactionHistory(List<WalletTransactionRecord> transactions) {
        llWalletTransactions.removeAllViews();
        if (transactions == null || transactions.isEmpty()) {
            tvWalletTxEmpty.setVisibility(View.VISIBLE);
            tvWalletTxEmpty.setText("Chưa có giao dịch nào.");
            return;
        }
        tvWalletTxEmpty.setVisibility(View.GONE);
        for (int i = 0; i < transactions.size(); i++) {
            llWalletTransactions.addView(buildTransactionRow(transactions.get(i), i < transactions.size() - 1));
        }
    }

    private View buildTransactionRow(WalletTransactionRecord tx, boolean addBottomSpacing) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_soft_panel);
        row.setClickable(true);
        row.setFocusable(true);
        int pad = (int) (14 * density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (addBottomSpacing) {
            lp.bottomMargin = (int) (10 * density);
        }
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> showTransactionReceiptDialog(tx));

        boolean credit = tx.isCredit();
        LinearLayout topRow = new LinearLayout(requireContext());
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(tx.getDescription() != null && !tx.getDescription().isEmpty()
                ? tx.getDescription()
                : (credit ? "Nạp tiền ví thẻ" : "Thanh toán dịch vụ"));
        tvTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_900));
        tvTitle.setTextSize(13f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginEnd((int) (8 * density));
        tvTitle.setLayoutParams(titleLp);

        TextView tvAmount = new TextView(requireContext());
        tvAmount.setText((credit ? "+" : "-") + formatVnd(tx.getAmount()));
        tvAmount.setTextColor(ContextCompat.getColor(
                requireContext(), credit ? R.color.emerald_600 : R.color.red_600));
        tvAmount.setTextSize(14f);
        tvAmount.setTypeface(null, Typeface.BOLD);

        topRow.addView(tvTitle);
        topRow.addView(tvAmount);
        row.addView(topRow);

        String party = (tx.getCounterparty() != null && !tx.getCounterparty().isEmpty())
                ? tx.getCounterparty()
                : (credit ? "Ngân hàng liên kết" : "Điểm thanh toán");
        String ref = (tx.getTxRef() != null && !tx.getTxRef().isEmpty())
                ? tx.getTxRef()
                : generateBankTxRef(tx.getTimestampSeconds(), tx.getAmount());

        TextView tvParty = new TextView(requireContext());
        tvParty.setText(ref + " • " + party);
        tvParty.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600));
        tvParty.setTextSize(11.5f);
        LinearLayout.LayoutParams partyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        partyLp.topMargin = (int) (4 * density);
        tvParty.setLayoutParams(partyLp);
        row.addView(tvParty);

        LinearLayout bottomRow = new LinearLayout(requireContext());
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.topMargin = (int) (6 * density);
        bottomRow.setLayoutParams(bottomLp);

        TextView tvMeta = new TextView(requireContext());
        tvMeta.setText(formatTimestamp(tx.getTimestampSeconds())
                + " • " + formatVnd(tx.getBalanceBefore()) + " → " + formatVnd(tx.getBalanceAfter()));
        tvMeta.setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_500));
        tvMeta.setTextSize(11f);
        tvMeta.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvBadge = new TextView(requireContext());
        tvBadge.setText(tx.isVerifiedByServer() ? "Thành công" : "Đã ghi nhận");
        tvBadge.setBackgroundResource(tx.isVerifiedByServer() ? R.drawable.bg_pill_green : R.drawable.bg_pill_slate);
        tvBadge.setTextColor(ContextCompat.getColor(
                requireContext(), tx.isVerifiedByServer() ? R.color.emerald_700 : R.color.slate_600));
        tvBadge.setTextSize(10f);
        tvBadge.setTypeface(null, Typeface.BOLD);
        int hPad = (int) (8 * density);
        int vPad = (int) (2 * density);
        tvBadge.setPadding(hPad, vPad, hPad, vPad);

        bottomRow.addView(tvMeta);
        bottomRow.addView(tvBadge);
        row.addView(bottomRow);

        return row;
    }

    /**
     * Opens an e-Receipt modal displaying full banking audit fields & RSA-1024 signature.
     */
    private void showTransactionReceiptDialog(WalletTransactionRecord tx) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        container.setPadding(pad, (int) (14 * density), pad, (int) (8 * density));

        String ref = (tx.getTxRef() != null && !tx.getTxRef().isEmpty())
                ? tx.getTxRef()
                : generateBankTxRef(tx.getTimestampSeconds(), tx.getAmount());
        String party = (tx.getCounterparty() != null && !tx.getCounterparty().isEmpty())
                ? tx.getCounterparty()
                : (tx.isCredit() ? "Ngân hàng liên kết" : "Điểm thanh toán");

        addLedgerSummaryRow(container, "Trạng thái", "Thành công", false);
        addLedgerSummaryRow(container, "Mã tham chiếu", ref, true);
        addLedgerSummaryRow(container, "Thời gian", formatFullTimestamp(tx.getTimestampSeconds()), false);
        addLedgerSummaryRow(container, "Tài khoản ví", tx.getStudentId(), false);
        addLedgerSummaryRow(container, tx.isCredit() ? "Nguồn tiền" : "Đơn vị thụ hưởng", party, false);
        addLedgerSummaryRow(container, "Loại giao dịch", tx.isCredit() ? "Nạp tiền vào ví" : "Thanh toán dịch vụ", false);
        addLedgerSummaryRow(container, "Số tiền giao dịch", (tx.isCredit() ? "+" : "-") + formatVnd(tx.getAmount()), true);
        addLedgerSummaryRow(container, "Phí giao dịch", "Miễn phí", false);
        addLedgerSummaryRow(container, "Số dư trước GD", formatVnd(tx.getBalanceBefore()), false);
        addLedgerSummaryRow(container, "Số dư sau GD", formatVnd(tx.getBalanceAfter()), false);
        addLedgerSummaryRow(container, "Nội dung", tx.getDescription(), false);

        new AlertDialog.Builder(requireContext())
                .setTitle("Chi tiết giao dịch")
                .setView(container)
                .setPositiveButton("Đóng", null)
                .show();
    }

    private void addOfflineFallbackTx(WalletTransactionRecord tx) {
        if (tx == null) return;
        offlineSessionTransactions.add(0, tx);
    }

    private List<WalletTransactionRecord> getOfflineTransactionsForStudent(String studentId) {
        List<WalletTransactionRecord> out = new ArrayList<>();
        for (WalletTransactionRecord tx : offlineSessionTransactions) {
            if (studentId.equals(tx.getStudentId())) {
                out.add(tx);
            }
        }
        return out;
    }

    private void setProcessingState(boolean processing, @Nullable String statusMessage) {
        isProcessing = processing;
        btnWalletCredit.setEnabled(!processing);
        btnWalletDebit.setEnabled(!processing);
        btnSyncWalletBalance.setEnabled(!processing);
        if (processing) {
            tvWalletStatus.setVisibility(View.GONE);
            tvWalletSyncBadge.setText("⏳ Đang xử lý...");
        }
    }

    private long parseAmountInput(EditText editText) {
        String raw = editText.getText().toString().replaceAll("[^0-9]", "");
        if (raw.isEmpty()) return -1L;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String generateBankTxRef(long ts, int amount) {
        long safeTs = ts > 0 ? ts : (System.currentTimeMillis() / 1000L);
        long val = Math.abs((safeTs * 100L + (amount / 1000L)) % 10_000_000_000L);
        return "FT" + String.format(Locale.US, "%010d", val);
    }

    private static byte[] encodeTwoInts(int first, int second) {
        return new byte[]{
                (byte) ((first >> 24) & 0xFF),
                (byte) ((first >> 16) & 0xFF),
                (byte) ((first >> 8) & 0xFF),
                (byte) (first & 0xFF),
                (byte) ((second >> 24) & 0xFF),
                (byte) ((second >> 16) & 0xFF),
                (byte) ((second >> 8) & 0xFF),
                (byte) (second & 0xFF)
        };
    }

    private static int decodeBalance4Bytes(byte[] data) {
        return ((data[0] & 0xFF) << 24)
                | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8)
                | (data[3] & 0xFF);
    }

    private static String formatVnd(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator('.');
        DecimalFormat df = new DecimalFormat("#,###", symbols);
        return df.format(amount) + " đ";
    }

    private static String formatTimestamp(long timestampSeconds) {
        if (timestampSeconds <= 0) return "Vừa xong";
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestampSeconds * 1000L));
    }

    private static String formatFullTimestamp(long timestampSeconds) {
        if (timestampSeconds <= 0) return "Vừa xong";
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(timestampSeconds * 1000L));
    }

    private static class WalletOpResult {
        final boolean success;
        final String errorMessage;
        final WalletTransactionRecord tx;
        final int newBalance;
        final boolean rsaVerified;
        final String swHex;
        final long elapsedMs;

        private WalletOpResult(boolean success, String errorMessage, WalletTransactionRecord tx,
                               int newBalance, boolean rsaVerified, String swHex, long elapsedMs) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.tx = tx;
            this.newBalance = newBalance;
            this.rsaVerified = rsaVerified;
            this.swHex = swHex;
            this.elapsedMs = elapsedMs;
        }

        static WalletOpResult success(WalletTransactionRecord tx, int newBalance,
                                      boolean rsaVerified, String swHex, long elapsedMs) {
            return new WalletOpResult(true, null, tx, newBalance, rsaVerified, swHex, elapsedMs);
        }

        static WalletOpResult failure(String errorMessage) {
            return new WalletOpResult(false, errorMessage, null, 0, false, null, 0);
        }
    }
}
