package com.example.smartstudentid.ui.student;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;
import com.example.smartstudentid.model.BookLoanRecord;
import com.example.smartstudentid.model.OnCardActivityLog;
import com.example.smartstudentid.optimization.ApduThreadPool;
import com.example.smartstudentid.security.EncryptedCacheManager;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tab 3 Fragment: Implements Atomic Library Book Borrowing/Returning (INS 0x31 / 0x32),
 * Lab Room Door Access Control, and reading the 10-entry On-Card Circular Activity Log (INS 0x33).
 */
public class LibraryLabFragment extends Fragment {

    private TextView tvBorrowedQuota;
    private TextView tvBookCount;
    private TextView tvLabStatusPill;
    private ProgressBar pbBorrowQuota;
    private RecyclerView rvLibraryBooks;
    private TextView tvOnCardActivityLogs;

    private EncryptedCacheManager cacheManager;
    private final List<BookLoanRecord> bookList = new ArrayList<>();
    private BookLoanAdapter bookAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library_lab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cacheManager = EncryptedCacheManager.getInstance(requireContext());
        view.setVerticalScrollBarEnabled(false);

        tvBorrowedQuota = view.findViewById(R.id.tvBorrowedQuota);
        tvBookCount = view.findViewById(R.id.tvBookCount);
        tvLabStatusPill = view.findViewById(R.id.tvLabStatusPill);
        pbBorrowQuota = view.findViewById(R.id.pbBorrowQuota);
        rvLibraryBooks = view.findViewById(R.id.rvLibraryBooks);
        tvOnCardActivityLogs = view.findViewById(R.id.tvOnCardActivityLogs);

        MaterialButton btnSwipeLabDoor = view.findViewById(R.id.btnSwipeLabDoor);
        MaterialButton btnReadOnCardLogs = view.findViewById(R.id.btnReadOnCardLogs);

        rvLibraryBooks.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        rvLibraryBooks.setHasFixedSize(true);
        rvLibraryBooks.setVerticalScrollBarEnabled(false);
        bookAdapter = new BookLoanAdapter();
        rvLibraryBooks.setAdapter(bookAdapter);

        btnSwipeLabDoor.setOnClickListener(v -> handleLabDoorSwipe());
        btnReadOnCardLogs.setOnClickListener(v -> readCircularLogsFromChip());

        loadBooksFromServerSqlite();
    }

    private void loadBooksFromServerSqlite() {
        ApduThreadPool.getInstance().execute(
                () -> ApduClient.getInstance().fetchBooksFromServerDb(),
                (remoteBooks, mainBlockedMs, totalMs) -> {
                    if (!isAdded()) return;
                    bookList.clear();
                    if (remoteBooks != null) {
                        bookList.addAll(remoteBooks);
                    }
                    bookAdapter.notifyDataSetChanged();
                    updateBorrowedQuotaHeader();
                }
        );
    }

    private void updateBorrowedQuotaHeader() {
        int borrowed = 0;
        for (BookLoanRecord b : bookList) {
            if (b.isBorrowed()) borrowed++;
        }
        tvBorrowedQuota.setText(borrowed + " / 5 cuốn");
        pbBorrowQuota.setProgress(borrowed);
        tvBookCount.setText(bookList.size() + " đầu sách");
    }

    /**
     * Executes atomic book borrow (INS 0x31, P1=0x02) or return (INS 0x32, P1=0x01) on the Java Card chip.
     */
    private void handleBookTransaction(BookLoanRecord book, int position) {
        boolean isReturning = book.isBorrowed();
        byte ins = isReturning ? ApduCommands.INS_RETURN_OR_LAB_ACCESS : ApduCommands.INS_BORROW_OR_ATTENDANCE;
        byte p1 = isReturning ? ApduCommands.P1_ACTION_RETURN_BOOK : ApduCommands.P1_ACTION_BORROW_BOOK;

        ApduThreadPool.getInstance().execute(() -> {
            long ts = System.currentTimeMillis() / 1000L;
            int code = book.getBookCode();
            byte[] payload = new byte[]{
                    (byte) ((code >> 8) & 0xFF),
                    (byte) (code & 0xFF),
                    (byte) ((ts >> 24) & 0xFF),
                    (byte) ((ts >> 16) & 0xFF),
                    (byte) ((ts >> 8) & 0xFF),
                    (byte) (ts & 0xFF)
            };
            return ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ins,
                    p1,
                    (byte) 0x00,
                    payload
            );
        }, (resp, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            if (resp.isSuccess()) {
                book.setBorrowed(!isReturning);
                book.setTimestampSeconds(System.currentTimeMillis() / 1000L);
                String sig = ApduCommands.bytesToHex(resp.getData());
                book.setRsaSignatureHex(sig.length() > 24 ? sig.substring(0, 24) + "..." : sig);

                ApduThreadPool.getInstance().execute(() -> {
                    ApduClient.getInstance().syncBookLoanToServerDb(book);
                    return true;
                }, null);
                bookAdapter.notifyItemChanged(position);
                updateBorrowedQuotaHeader();

                String actionText = isReturning ? "Đã trả sách" : "Đã mượn sách";
                Toast.makeText(requireContext(),
                        actionText + ": " + book.getBookTitle(),
                        Toast.LENGTH_SHORT).show();
                readCircularLogsFromChip();
            } else {
                Toast.makeText(requireContext(),
                        "Không thể thực hiện giao dịch, vui lòng thử lại.",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Sends INS 0x32 (P1=0x02) to verify Lab Room access permission and sign door entry with RSA-1024.
     */
    private void handleLabDoorSwipe() {
        ApduThreadPool.getInstance().execute(() -> {
            long ts = System.currentTimeMillis() / 1000L;
            byte[] payload = new byte[]{
                    0x04, 0x01,
                    (byte) ((ts >> 24) & 0xFF),
                    (byte) ((ts >> 16) & 0xFF),
                    (byte) ((ts >> 8) & 0xFF),
                    (byte) (ts & 0xFF)
            };
            return ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_RETURN_OR_LAB_ACCESS,
                    ApduCommands.P1_ACTION_LAB_ACCESS,
                    (byte) 0x00,
                    payload
            );
        }, (resp, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            if (resp.isSuccess()) {
                tvLabStatusPill.setText("✓ Đã mở cửa");
                Toast.makeText(requireContext(),
                        "Mở cửa Phòng Lab ATTT thành công!",
                        Toast.LENGTH_SHORT).show();
                readCircularLogsFromChip();
            } else {
                Toast.makeText(requireContext(),
                        "Thẻ chưa được cấp quyền mở cửa Lab.",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Queries INS 0x33 to read the 10-entry circular activity buffer stored inside the smart card EEPROM.
     */
    private void readCircularLogsFromChip() {
        ApduThreadPool.getInstance().execute(() -> {
            CardResponse resp = ApduClient.getInstance().sendApdu(
                    ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_GET_ACTIVITY_LOG,
                    (byte) 0x00,
                    (byte) 0x00,
                    null
            );
            byte[] raw = resp.getData();
            List<OnCardActivityLog> parsed = new ArrayList<>();
            if (raw.length >= 1) {
                int count = raw[0] & 0xFF;
                int offset = 1;
                for (int i = 0; i < count && offset + 7 <= raw.length; i++) {
                    int type = raw[offset] & 0xFF;
                    int code = ((raw[offset + 1] & 0xFF) << 8) | (raw[offset + 2] & 0xFF);
                    long ts = ((long) (raw[offset + 3] & 0xFF) << 24)
                            | ((long) (raw[offset + 4] & 0xFF) << 16)
                            | ((long) (raw[offset + 5] & 0xFF) << 8)
                            | ((long) (raw[offset + 6] & 0xFF));
                    parsed.add(new OnCardActivityLog(type, code, ts));
                    offset += 7;
                }
            }
            return parsed;
        }, (logs, mainBlockedMs, totalMs) -> {
            if (!isAdded()) return;
            if (logs.isEmpty()) {
                tvOnCardActivityLogs.setText("Chưa có giao dịch.");
                return;
            }
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < logs.size(); i++) {
                OnCardActivityLog log = logs.get(i);
                String timeStr = log.getTimestampSeconds() > 1_000_000_000L
                        ? sdf.format(new Date(log.getTimestampSeconds() * 1000L))
                        : "Gần đây";
                sb.append(i + 1).append(". [").append(timeStr).append("] ")
                        .append(log.getActionTitle()).append("\n");
            }
            tvOnCardActivityLogs.setText(sb.toString().trim());
        });
    }

    private class BookLoanAdapter extends RecyclerView.Adapter<BookLoanAdapter.BookViewHolder> {

        private final int[] coverColors = {
                0xFF2563EB, 0xFF059669, 0xFFD97706, 0xFF7C3AED
        };

        @NonNull
        @Override
        public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_book_loan, parent, false);
            return new BookViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
            BookLoanRecord item = bookList.get(position);
            holder.tvTitle.setText(item.getBookTitle());
            holder.tvSubtitle.setText(item.getAuthor());
            holder.bookCover.setBackgroundTintList(
                    ColorStateList.valueOf(coverColors[Math.abs(position) % coverColors.length]));

            if (item.isBorrowed()) {
                holder.tvStatus.setText("Đang mượn");
                holder.tvStatus.setTextColor(Color.parseColor("#2563EB"));
                holder.tvStatus.setBackgroundResource(R.drawable.bg_pill_blue);
                holder.btnAction.setText("Trả sách");
                holder.btnAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#EFF6FF")));
                holder.btnAction.setTextColor(Color.parseColor("#2563EB"));
            } else {
                holder.tvStatus.setText("Có sẵn");
                holder.tvStatus.setTextColor(Color.parseColor("#059669"));
                holder.tvStatus.setBackgroundResource(R.drawable.bg_pill_green);
                holder.btnAction.setText("Mượn sách");
                holder.btnAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2563EB")));
                holder.btnAction.setTextColor(Color.WHITE);
            }

            holder.btnAction.setOnClickListener(v -> handleBookTransaction(item, holder.getAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return bookList.size();
        }

        class BookViewHolder extends RecyclerView.ViewHolder {
            final View bookCover;
            final TextView tvTitle;
            final TextView tvSubtitle;
            final TextView tvStatus;
            final MaterialButton btnAction;

            BookViewHolder(@NonNull View itemView) {
                super(itemView);
                bookCover = itemView.findViewById(R.id.bookCover);
                tvTitle = itemView.findViewById(R.id.tvBookTitle);
                tvSubtitle = itemView.findViewById(R.id.tvBookSubtitle);
                tvStatus = itemView.findViewById(R.id.tvBookStatusBadge);
                btnAction = itemView.findViewById(R.id.btnBookAction);
            }
        }
    }
}
