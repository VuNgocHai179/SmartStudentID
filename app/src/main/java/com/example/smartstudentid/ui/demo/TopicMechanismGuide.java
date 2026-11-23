package com.example.smartstudentid.ui.demo;

/**
 * Structured, human-readable explanation of what each demo topic (§1–§5) actually executes
 * under the hood in Before (bottleneck/fault) vs After (optimized) mode, and why that affects
 * FPS, latency, RAM, or security posture.
 */
public final class TopicMechanismGuide {

    public static final int TOPIC_BOTTLENECK = 0;
    public static final int TOPIC_THREADING = 1;
    public static final int TOPIC_CACHE = 2;
    public static final int TOPIC_IMAGE = 3;
    public static final int TOPIC_STARTUP = 4;

    public static final class MechanismInfo {
        public final String headline;
        public final String whatItDoes;
        public final String whyItImpacts;
        public final String primaryBadge;
        public final String secondaryBadge;
        public final String comparisonMechanismLabel;

        public MechanismInfo(String headline,
                             String whatItDoes,
                             String whyItImpacts,
                             String primaryBadge,
                             String secondaryBadge,
                             String comparisonMechanismLabel) {
            this.headline = headline;
            this.whatItDoes = whatItDoes;
            this.whyItImpacts = whyItImpacts;
            this.primaryBadge = primaryBadge;
            this.secondaryBadge = secondaryBadge;
            this.comparisonMechanismLabel = comparisonMechanismLabel;
        }
    }

    private TopicMechanismGuide() {
    }

    public static MechanismInfo forTopic(int topic, boolean afterMode) {
        switch (topic) {
            case TOPIC_THREADING:
                return afterMode
                        ? new MechanismInfo(
                                "Tối ưu đa luồng: ApduThreadPool (nền) giữ 60 FPS",
                                "• Hủy jankRunnable trên MainLooper.\n"
                                        + "• Đẩy toàn bộ lệnh đọc thẻ fetchFromCard() sang ApduThreadPool (Worker Thread nền).\n"
                                        + "• Main Thread chỉ nhận callback cập nhật UI trong < 1 ms.",
                                "Choreographer vẽ đều mỗi ~16.6 ms, không rớt khung hình → nhịp UI mượt 60 FPS.",
                                "Thread: apdu-worker",
                                "Main: Non-blocking",
                                "ApduThreadPool (Async)")
                        : new MechanismInfo(
                                "Đang gây nghẽn: Chặn trực tiếp Main Thread",
                                "• Gọi fetchFromCard() đồng bộ ngay trên Main Thread (khóa UI Looper).\n"
                                        + "• Chạy 18 nhịp Choreographer liên tiếp: mỗi nhịp gửi 8 APDU ảnh + giữ Main Thread ~148 ms.\n"
                                        + "• Sau đó bật jankRunnable lặp mỗi 135 ms (APDU + sleep 55 ms) trên Main Thread.",
                                "Mỗi frame bị giữ ~148 ms vượt xa ngưỡng 16.67 ms (60 FPS) → bỏ qua ~9 khung/nhịp → FPS tụt mạnh & đứng hình.",
                                "Thread: main (BLOCKED)",
                                "148 ms/frame • Jank lặp",
                                "Main Thread (Block)");

            case TOPIC_CACHE:
                return afterMode
                        ? new MechanismInfo(
                                "Lưu đệm 2 tầng: L1 RAM + L2 AndroidKeyStore TEE",
                                "• Xóa sạch SharedPreferences(\"insecure_plaintext_leak\") chứa bản rõ.\n"
                                        + "• Tầng L1 (RAM) đọc hồ sơ < 1 ms; tầng L2 (AndroidKeyStore TEE AES-256-GCM) lưu mã hóa chuẩn OWASP.\n"
                                        + "• Tự hủy cả L1 RAM + L2 TEE ngay lập tức khi sai PIN 3 lần liên tiếp.",
                                "Giảm 100% I/O thừa xuống thẻ (36 → 0 APDU), đạt chuẩn OWASP MASVS-STORAGE & chống dò PIN.",
                                "L1 RAM + L2 TEE",
                                "0 APDU • Auto-Wipe 3x",
                                "2 tầng L1 RAM + L2 TEE")
                        : new MechanismInfo(
                                "Đang gây nghẽn: Bỏ qua Cache 2 tầng & Lộ bản rõ",
                                "• Bật cờ bypass cả L1 RAM và L2 TEE, ghi bản rõ (MSSV|PIN=123456) vào SharedPreferences không mã hóa.\n"
                                        + "• Mỗi lần mở hồ sơ đều gọi trực tiếp xuống thẻ: đọc thông tin + ký RSA-1024 + 30 gói ảnh 240B (36 APDU).",
                                "Chờ 36 gói Short APDU tuần tự gây trễ ~2.65s mỗi lần mở, đồng thời làm lộ dữ liệu nhạy cảm trên bộ nhớ.",
                                "Bypass • 36 APDU",
                                "Plaintext Leak",
                                "Bypass + 36 APDU");

            case TOPIC_IMAGE:
                return afterMode
                        ? new MechanismInfo(
                                "Tối ưu đồ họa: WebP 75% + GPU VRAM + Buffer 8KB",
                                "• Dừng churnExecutor, giải phóng ~11 MB buffer ghim và triệt tiêu GC Pause.\n"
                                        + "• Giảm mẫu Two-pass (inSampleSize) → 150×150, nén WebP 75% bằng bộ đệm 8 KB tái sử dụng (0 cấp phát rác).\n"
                                        + "• Decode hiển thị bằng Bitmap.Config.HARDWARE đưa thẳng ảnh vào GPU VRAM (0 MB Java Heap).",
                                "Giảm >95% dung lượng vừa trần 8 KB của thẻ, đưa ảnh lên GPU VRAM và triệt tiêu Memory Churn & GC Pause.",
                                "WebP + GPU VRAM",
                                "Buffer 8KB tái sử dụng",
                                "WebP + VRAM + 8KB Reuse")
                        : new MechanismInfo(
                                "Đang gây nghẽn: Full PNG + RAM thường & GC Pause",
                                "• ChaosInjectionManager ghim ~11 MB trên Java Heap + churnExecutor cấp phát rác 64×16 KB mỗi 250 ms gây Memory Churn & GC Pause.\n"
                                        + "• Giải mã Full Bitmap (ARGB_8888 trên RAM) + nén PNG (~180 KB, ~750 gói) và cấp phát mảng byte[240] mới liên tục.",
                                "Java Heap tăng vọt + ART GC Pause liên tục, file PNG quá lớn tràn bộ đệm 8 KB nên thẻ từ chối ghi.",
                                "PNG (~750 gói • >8KB)",
                                "Heap Churn & GC Pause",
                                "Full PNG + Heap Churn");

            case TOPIC_STARTUP:
                return afterMode
                        ? new MechanismInfo(
                                "Tối ưu Khởi động Lazy & Zeroize PIN char[]",
                                "• Bỏ khởi tạo đồng bộ nặng, chạy PBKDF2 với mảng char[] thay vì String.\n"
                                        + "• Ghi đè Arrays.fill(pin, '\\0') ngay trong khối finally sau khi băm.\n"
                                        + "• Bật lại xác thực RSA-1024 và kiểm tra môi trường Root/Debugger.",
                                "Đăng nhập nhanh hơn, PIN không còn sót trong RAM (0% phơi nhiễm) và điểm kiểm toán MASVS đạt 5/5.",
                                "PIN: char[] (0% lộ)",
                                "MASVS 5/5 • Lazy Init",
                                "char[] Zeroize + Lazy")
                        : new MechanismInfo(
                                "Đang gây nghẽn: Khởi tạo đồng bộ & Lộ String PIN",
                                "• Mô phỏng khởi tạo KeyStore đồng bộ gây trễ (~1.95s) + chạy PBKDF2 không gọi spec.clearPassword().\n"
                                        + "• Lưu PIN dưới dạng String bất biến trên Heap (không thể xóa chủ động).\n"
                                        + "• Bật cờ giả lập Root/Debugger và bỏ qua xác thực RSA-1024.",
                                "Khởi động/đăng nhập chậm, PIN phơi nhiễm 100% trong Heap Dump và điểm bảo mật MASVS tụt xuống 1/5.",
                                "PIN: String (100% lộ)",
                                "MASVS 1/5 • Sync Init",
                                "String PIN + Sync Init");

            default:
                return afterMode
                        ? new MechanismInfo(
                                "Tối ưu tổng hợp: Triệt tiêu Chatty I/O & Hao pin",
                                "• Đọc hồ sơ & ảnh WebP trực tiếp từ Cache 2 tầng (L1 RAM + L2 AndroidKeyStore TEE) thay vì 41 lệnh APDU.\n"
                                        + "• Không đánh thức bus I/O / radio lặp lại, giữ pin và tốc độ phản hồi < 10 ms.",
                                "Cắt bỏ toàn bộ nút thắt APDU, RSA trên chip và hiện tượng hao pin do Chatty I/O trên Android.",
                                "0 APDU • Tiết kiệm pin",
                                "Không Chatty I/O",
                                "Cache 2 tầng (0 I/O)")
                        : new MechanismInfo(
                                "Đang gây nghẽn: 41 APDU tuần tự & Hao pin Chatty I/O",
                                "• Thực thi nối tiếp 5 giai đoạn trên chip thẻ (41 gói Chatty I/O):\n"
                                        + "  1) SELECT (0xA4) → 2) VERIFY PIN (0x10) → 3) AES-128 EEPROM (0x21) → 4) Ký RSA-1024 (0x40) → 5) Học vụ + 35 gói ảnh 240B.",
                                "Mỗi gói Short APDU đánh thức bus/CPU liên tục (Chatty I/O) gây trễ ~3.15s và tăng tiêu hao năng lượng pin đáng kể.",
                                "41 Chatty I/O • Hao pin",
                                "AES-128 + RSA-1024",
                                "41 Chatty I/O tuần tự");
        }
    }
}
