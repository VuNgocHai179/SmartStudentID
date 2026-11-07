# TÀI LIỆU ĐỌC HIỂU LÝ THUYẾT & KỊCH BẢN THUYẾT TRÌNH THẢO LUẬN GIỮA KỲ
**Đề tài:** Tối ưu hiệu năng và bảo mật cho ứng dụng thẻ sinh viên thông minh trên Android  
**Phạm vi:** 4 gạch đầu dòng đã đăng ký với lớp trưởng + Kịch bản Live Demo 3–5 phút & Lời thoại cho giảng viên

---

## 0. BỨC TRANH TỔNG THỂ: SỢI DÂY LIÊN KẾT 4 NỘI DUNG ĐÃ ĐĂNG KÝ

Thực chất, 4 gạch đầu dòng trong đề tài không phải là 4 mảnh ghép rời rạc, mà là **1 bài toán gốc (Phần 1)** kéo theo **3 giải pháp tối ưu triệt để (Phần 2, 3, 4)**:

1. **Phần 1 (Bài toán gốc - Điểm nghẽn):** Khi ứng dụng Android giao tiếp với thẻ Java Card, dữ liệu phải đi qua các gói tin nhỏ **APDU** và phải thực thi **Mật mã (AES-128, RSA-1024)** để bảo mật. Quá trình này chậm (từ vài trăm mili-giây đến gần 3 giây) $\rightarrow$ Điểm nghẽn hiệu năng APDU & Mật mã.
2. **Phần 2 (Chống đơ màn hình):** Vì thẻ phản hồi chậm (210ms – 2800ms), nếu chạy trên `Main Thread` thì màn hình sẽ bị đơ cứng. $\rightarrow$ **Giải pháp:** Xây dựng mô hình Đa luồng tách biệt I/O thẻ và mật mã khỏi Main Thread để giữ độ mượt 60 FPS (`ApduThreadPool.java`).
3. **Phần 3 (Chống hỏi thẻ lặp đi lặp lại):** Dù đã chạy ngầm, nhưng nếu mỗi lần chuyển tab lại gửi 5 lệnh APDU xuống thẻ đọc lại thông tin thì rất lãng phí. $\rightarrow$ **Giải pháp:** Thiết kế bộ nhớ đệm bảo mật **Offline-First** (`LocalCacheManager.java` & `EncryptedCacheManager.java`) đọc 1 lần rồi mã hóa lưu trên máy, các lần sau mở tức thì trong ~9ms.
4. **Phần 4 (Chống nghẽn băng thông & tràn RAM khi truyền ảnh):** Ảnh chân dung gốc 180 KB phải cắt thành 750 gói APDU và gây xáo trộn bộ nhớ (*Memory Churn*). $\rightarrow$ **Giải pháp:** Thu nhỏ + Nén ảnh chuẩn **WebP 75% (~3.5 KB, chỉ còn 15 gói APDU)** kết hợp **Tái sử dụng bộ đệm cố định 8KB** (`ImageOptimizer.java` & `ApduClient.java`).

---

## 1. PHẦN 1: ĐIỂM NGHẼN APDU & THỰC THI MẬT MÃ TRÊN ANDROID

### 1.1. Gói tin APDU và tại sao băng thông rất hẹp?
- **Phần cứng thẻ thông minh (Java Card):** Không có pin riêng, chỉ nhận nguồn điện cảm ứng từ đầu đọc; RAM siêu nhỏ (2 KB – 8 KB), EEPROM hạn chế (32 KB – 144 KB).
- **Chuẩn ISO/IEC 7816-4:** Mọi giao tiếp bắt buộc đóng gói thành các khung lệnh APDU gồm 2 loại:
  - `Command APDU`: `[CLA] [INS] [P1] [P2] [Lc] [Data] [Le]`
  - `Response APDU`: `[Data] [SW1] [SW2]` (ví dụ `90 00` = thành công, `69 83` = khóa thẻ).
- **3 Nguyên nhân gây nghẽn truyền thông APDU (I/O Bottleneck):**
  1. *Giới hạn gói Short APDU:* Trường `Lc` chỉ có 1 byte $\rightarrow$ mỗi gói chở tối đa 240 bytes dữ liệu. File lớn bắt buộc phải cắt mảnh (*Chunking*).
  2. *Giao tiếp Bán song công Tuần tự (Half-Duplex):* Gửi lệnh 1 xong phải đứng chờ thẻ xử lý và trả về `90 00` mới được gửi lệnh 2. Độ trễ cộng dồn: $T_{\text{tổng}} = \sum (T_{\text{truyền}} + T_{\text{xử lý}})$.
  3. *Tốc độ ghi EEPROM rất chậm:* Mất từ 2 – 5 ms cho mỗi chu kỳ ghi trang nhớ trên chip thẻ.

### 1.2. Tại sao thực thi Mật mã (Cryptography) làm điểm nghẽn nặng thêm?
| Thuật toán Mật mã | Chạy ở đâu? | Mục đích Bảo mật | Chi phí Hiệu năng |
| :--- | :--- | :--- | :--- |
| **AES-128 CBC + PKCS#7** | Trên chip thẻ (`SmartCampusApplet`) | Mã hóa chuỗi hồ sơ sinh viên trong EEPROM bằng khóa sinh từ PIN | **~35 – 55 ms / lệnh** (Đọc EEPROM + giải mã khối 16-byte) |
| **RSA-1024 Signature** | Thẻ ký (Private Key) - Android xác minh (Public Key) | Xác thực Challenge-Response chống làm giả thẻ thật | **~80 – 110 ms / lệnh** (Lũy thừa modulo $S = M^d \bmod N$ trên thẻ) |
| **PBKDF2-HMAC-SHA256** | Trên Android (`CryptoManager`) | Băm mã PIN kèm Salt ngẫu nhiên 10.000 vòng lặp | **~25 – 45 ms CPU** (Chống tấn công Brute-force dò PIN) |

### 1.3. Tổng hợp số liệu điểm nghẽn khi chưa tối ưu
- Đọc 5 lệnh APDU cơ bản + giải mã AES + ký RSA: **~180 – 220 ms**.
- Tải ảnh chân dung PNG gốc (180 KB = 750 gói APDU): **~2.800 ms**.
- **Tổng cộng: ~3.000 ms (3 giây)** cho một lần đọc thẻ!

---

## 2. PHẦN 2: MÔ HÌNH ĐA LUỒNG 60 FPS (TÁCH BIỆT I/O THẺ & MẬT MÃ)

### 2.1. Ngưỡng 16.67 ms của Android UI và hiện tượng giật khung hình (Jank)
- `Main Thread (UI Thread)` đảm nhận 2 việc: Nhận sự kiện chạm/bấm và Vẽ lại màn hình 60 khung hình/giây (60 FPS).
- Công thức: $1000\text{ ms} / 60\text{ frames} = \mathbf{16.67\text{ ms / khung hình}}$.
- Nếu chạy I/O APDU (210ms – 2800ms) trên Main Thread:
  - Main Thread bị khóa cứng (Blocked), bỏ lỡ từ 13 đến 170 nhịp vẽ màn hình (*Skipped Frames*).
  - Tốc độ khung hình tụt từ 60 FPS xuống dưới 15 FPS, hiệu ứng đứng hình, nguy cơ lỗi ANR (Application Not Responding).

### 2.2. Kiến trúc ApduThreadPool (FixedThreadPool + MainLooper Handler)
- **Hồ luồng ngầm (`Executors.newFixedThreadPool(4)`):** 4 worker threads túc trực nhận việc nặng.
- **Giao việc siêu tốc từ Main Thread (< 1 ms):** Đóng gói tác vụ vào `Callable<T>`, ném vào `backgroundExecutor.submit(...)` chỉ mất ~0.7 ms rồi Main Thread quay lại vẽ UI 60 FPS ngay.
- **Trả kết quả an toàn (`mainThreadHandler.post(...)`):** Khi xong, gửi kết quả về Main Thread cập nhật giao diện mà không vi phạm nguyên tắc truy cập UI.
- **Đồng bộ tuần tự (`synchronized`):** Hàm `transmitRawFrame()` trong `ApduClient.java` được đặt `synchronized` để khi 4 luồng chạy song song thì các lệnh APDU gửi xuống thẻ vẫn xếp hàng tuần tự, chống xung đột bộ đệm APDU vật lý.

---

## 3. PHẦN 3: LƯU ĐỆM BẢO MẬT THEO KIẾN TRÚC OFFLINE-FIRST

### 3.1. Nguyên lý Offline-First: Giảm 100% lệnh APDU dư thừa
- **Vấn đề:** Mỗi lần chuyển tab lại gửi 5 lệnh APDU đọc lại cùng một thông tin cá nhân (Họ tên, MSSV, Lớp) là hoàn toàn dư thừa.
- **Giải pháp trong `LocalCacheManager.java`:**
  - *Cache HIT (Đã có trong đệm):* Đọc ngay từ bộ đệm máy trong **~8 – 12 ms**, số lệnh APDU gửi xuống thẻ là **0 lệnh** (tiết kiệm 100% lệnh dư thừa).
  - *Cache MISS (Đệm trống hoặc bấm Làm mới):* Gửi 5 lệnh APDU xuống thẻ đọc dữ liệu mới nhất, đồng thời lưu đè vào Cache mã hóa.

### 3.2. Bảo mật Bộ đệm 2 Lớp (Chuẩn OWASP MASVS-STORAGE)
- **Tầng 1 — Phần cứng AndroidKeyStore (TEE / StrongBox):** Giữ khóa chính `MasterKey (AES256_GCM)` trong chip bảo mật phần cứng, không bao giờ lộ ra RAM.
- **Tầng 2 — EncryptedSharedPreferences (Mã hóa kép):**
  - Tên biến mã hóa bằng `AES-256-SIV` (Synthetic IV).
  - Nội dung hồ sơ & ảnh mã hóa bằng `AES-256-GCM` kèm thẻ xác thực toàn vẹn 128-bit (`GMAC Authentication Tag`).
- **Cơ chế tự hủy (Auto-Wipe):** Nhập sai mã PIN quá 3 lần liên tiếp $\rightarrow$ kích hoạt `wipeStudentSessionCache()` xóa sạch toàn bộ hồ sơ trong đệm.

---

## 4. PHẦN 4: NÉN ẢNH WEBP & TÁI SỬ DỤNG BỘ ĐỆM (CHỐNG MEMORY CHURN)

### 4.1. Downsampling & Nén ảnh WebP 75%
- Khung ảnh thẻ trên màn hình chỉ cần `150 × 150 px`.
- Ảnh PNG gốc `600 × 600 px` nặng ~180 KB $\rightarrow$ chia thành **750 gói APDU** (mất ~2.830 ms).
- **Xử lý 2 pha trong `ImageOptimizer.java`:**
  - *Pha 1 (Đọc kích thước ảo):* `options.inJustDecodeBounds = true`, tính hệ số giảm mẫu lũy thừa 2 `inSampleSize` đưa về 150x150 px mà không tốn RAM.
  - *Pha 2 (Nén WebP Lossy 75%):* Dung lượng giảm từ **180 KB xuống ~3.5 KB**, số gói APDU giảm từ **750 gói xuống 15 gói** (truyền trong ~50 ms, tiết kiệm **98% băng thông**).
- **Truyền Offset 16-bit qua (P1, P2):** Dùng `P1 = offset >> 8` và `P2 = offset & 0xFF` làm con trỏ vị trí byte để thẻ ghép chính xác từng mảnh 240B vào EEPROM.

### 4.2. Tái sử dụng Bộ đệm cố định (Buffer Reuse) chống Memory Churn
- **Memory Churn:** Hiện tượng tạo mới và vứt bỏ liên tục các mảng byte ngắn hạn (`new byte[]`, `new ByteArrayOutputStream()`) trong vòng lặp 15-750 gói APDU $\rightarrow$ Kích hoạt bộ dọn rác GC Pause liên tục làm giật màn hình (biểu đồ RAM hình răng cưa).
- **Giải pháp trong `ApduClient.java`:** Cấp phát sẵn duy nhất 1 bộ đệm tĩnh 8KB:  
  `private final ByteArrayOutputStream reusableAvatarStream = new ByteArrayOutputStream(8192);`  
  Mỗi lần tải ảnh chỉ gọi `reusableAvatarStream.reset()` để đặt lại con trỏ về 0 và ghi đè, **không cấp phát lại Heap**.
- **Giải phóng Bitmap:** Gọi `sampled.recycle()` ngay sau khi scale ảnh để trả lại bộ nhớ đồ họa, chống rò rỉ bộ nhớ (*Memory Leak*).

---

## 5. KỊCH BẢN LIVE DEMO TRỰC QUAN (3–5 PHÚT TRÊN BỤC GIẢNG)

1. **Thao tác 1 (Tab Thẻ Sinh Viên - Phút 1):**
   - Đăng nhập PIN `123456` $\rightarrow$ Bấm *"Làm mới từ Thẻ (APDU)"*.
   - Cho thầy thấy: UI vẫn cuộn mượt 60 FPS, ảnh WebP tải cực nhanh, lưu vào Offline-First Cache. Chuyển sang Tab 2 rồi quay lại Tab 1: hiển thị tức thì (~9ms, 0 lệnh APDU).
2. **Thao tác 2 (Tab Optimization Lab - Phút 2–3):**
   - Gạt công tắc bật/tắt tối ưu $\rightarrow$ Bấm *"Chạy Benchmark Toàn diện"*.
   - Chiếu kết quả đo trực tiếp: WebP 3.5KB tiết kiệm **98%**; Cache ~9ms nhanh hơn **21 lần**; UI giữ vững **60 FPS**.
3. **Thao tác 3 (Tab Security Lab - Phút 4):**
   - Bấm *"Xem Bản mã AES-128 & Gói tin APDU Hex"*.
   - Chiếu đối chiếu: Bản rõ sau giải mã vs Bản mã AES-128 thô trong EEPROM thẻ, kèm log APDU hex và thời gian từng lệnh.

---

## 6. LỜI THOẠI MẪU CHO GIẢNG VIÊN & CÁCH TRẢ LỜI PHẢN BIỆN

### 6.1. Lời thoại trình bày 4 nội dung (Nói trong 3 phút)
- **Mở đầu:** *"Em chào thầy và các bạn. Đề tài của nhóm em giải quyết bài toán cân bằng giữa bảo mật mật mã chặt chẽ trên thẻ thông minh và trải nghiệm người dùng mượt mà 60 FPS trên Android qua 4 nội dung cốt lõi..."*
- **Ý 1:** *"Thứ nhất, điểm nghẽn nằm ở giới hạn 240 bytes của gói Short APDU tuần tự và chi phí giải mã AES-128 (~45ms), ký RSA-1024 (~90ms). Khi tải thêm ảnh PNG 180KB tốn 750 gói APDU mất gần 3 giây..."*
- **Ý 2:** *"Thứ hai, để tránh đơ Main Thread vượt ngưỡng 16.67ms của chuẩn 60 FPS, nhóm xây dựng `ApduThreadPool` 4 worker threads. Khi bấm nút, Main Thread chỉ mất <1ms giao việc rồi tiếp tục vẽ UI 60 FPS; đồng thời hàm gửi APDU dùng `synchronized` để bảo đảm các gói tin vào thẻ luôn an toàn luồng..."*
- **Ý 3:** *"Thứ ba, để không phải hỏi lại thẻ những thông tin đã đọc, nhóm thiết kế bộ nhớ đệm Offline-First hiển thị trong ~9ms với 0 lệnh APDU. Bộ đệm này được mã hóa 2 lớp bằng `AndroidKeyStore` và `EncryptedSharedPreferences (AES-256-GCM/SIV)`, kèm cơ chế Auto-Wipe tự xóa sạch khi nhập sai PIN 3 lần..."*
- **Ý 4:** *"Thứ tư, nhóm downsampling ảnh về 150x150px và nén WebP 75% giúp giảm từ 180KB xuống 3.5KB, số gói APDU giảm từ 750 xuống 15 gói (tiết kiệm 98% băng thông); đồng thời tái sử dụng bộ đệm 8KB cố định bằng lệnh reset() để loại bỏ hoàn toàn hiện tượng Memory Churn và GC Pause..."*

### 6.2. 4 Câu hỏi phản biện thường gặp
1. *Hỏi:* Làm sao thẻ biết thứ tự 15 gói ảnh để ghép lại?  
   $\rightarrow$ *Đáp:* Dùng 2 byte tham số Header `P1, P2` làm con trỏ offset 16-bit (`offset >> 8` và `offset & 0xFF`).
2. *Hỏi:* 4 luồng ngầm gửi APDU cùng lúc có xung đột thẻ không?  
   $\rightarrow$ *Đáp:* Không, vì hàm gửi vật lý `transmitRawFrame()` dùng từ khóa `synchronized` để xếp hàng tuần tự.
3. *Hỏi:* Đổi thông tin thẻ thì Cache trên máy có bị cũ không?  
   $\rightarrow$ *Đáp:* Không, Cache được ghi đè ngay khi cập nhật, có nút làm mới trực tiếp và tự động xóa sạch khi đổi thẻ/sai PIN.
4. *Hỏi:* Tại sao dùng WebP 75% mà không dùng JPEG?  
   $\rightarrow$ *Đáp:* WebP 75% nén tốt hơn JPEG 30%, giảm 98% so với PNG, vừa vặn trong mảng 8KB của thẻ mà mắt thường nhìn khung 150x150px không thấy giảm chất lượng.
