package com.example.smartstudentid.apdu;

import com.example.smartstudentid.model.AttendanceRecord;
import com.example.smartstudentid.model.BookLoanRecord;
import com.example.smartstudentid.model.StudentCardRecord;
import com.example.smartstudentid.model.WalletTransactionRecord;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client responsible for transmitting APDU frames to the PC Bridge Server (or local fallback simulator),
 * synchronizing relational records with the PC SQLite Database (`smartcard.db`),
 * auto-discovering the active bridge server endpoint (USB cable `127.0.0.1` vs Wi-Fi LAN `192.168.0.101`),
 * and managing reusable byte buffers to prevent memory churn (OPT-05).
 */
public class ApduClient {

    private static ApduClient instance;

    // Supported Bridge Server endpoints:
    // 1. USB cable with ADB reverse (127.0.0.1:8080)
    // 2. Wi-Fi Local Area Network (192.168.0.101:8080)
    // 3. Android Studio Emulator loopback (10.0.2.2:8080)
    private static final String[] CANDIDATE_BASE_URLS = new String[]{
            "http://127.0.0.1:8080",
            "http://192.168.0.101:8080",
            "http://10.0.2.2:8080"
    };

    private volatile String serverBaseUrl = "http://127.0.0.1:8080";
    private final Gson gson = new Gson();

    // Reusable buffer for chunked avatar transfers to prevent memory churn (OPT-05)
    private final ByteArrayOutputStream reusableAvatarStream = new ByteArrayOutputStream(8192);

    // Circular log of recent raw APDU commands/responses for the Security Lab viewer (SEC-01)
    private final List<String> recentApduLogs = new ArrayList<>();
    private static final int MAX_APDU_LOG_LINES = 30;

    // Local fallback simulator used when the Android device/emulator is offline from the PC Bridge
    private final LocalFallbackCardEngine localFallback = new LocalFallbackCardEngine();

    private ApduClient() {
    }

    public static synchronized ApduClient getInstance() {
        if (instance == null) {
            instance = new ApduClient();
        }
        return instance;
    }

    public void setServerBaseUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            this.serverBaseUrl = url.trim();
        }
    }

    public String getServerBaseUrl() {
        return serverBaseUrl;
    }

    /**
     * Builds and transmits a standard APDU command frame.
     */
    public CardResponse sendApdu(byte cla, byte ins, byte p1, byte p2, byte[] data) {
        int lc = (data != null) ? data.length : 0;
        byte[] frame = new byte[4 + (lc > 0 ? 1 + lc : 1)];
        frame[0] = cla;
        frame[1] = ins;
        frame[2] = p1;
        frame[3] = p2;
        if (lc > 0) {
            frame[4] = (byte) (lc & 0xFF);
            System.arraycopy(data, 0, frame, 5, lc);
        } else {
            frame[4] = 0x00;
        }
        return transmitRawFrame(frame);
    }

    /**
     * Transmits a raw APDU byte frame over HTTP to the Bridge Server, or falls back to the local card engine.
     */
    public synchronized CardResponse transmitRawFrame(byte[] frame) {
        long startNs = System.nanoTime();
        String cmdHex = ApduCommands.bytesToHex(frame);

        CardResponse remoteResp = tryTransmitApdu(serverBaseUrl, cmdHex, frame, startNs);
        if (remoteResp != null) {
            return remoteResp;
        }

        for (String candidate : CANDIDATE_BASE_URLS) {
            if (candidate.equalsIgnoreCase(serverBaseUrl)) continue;
            remoteResp = tryTransmitApdu(candidate, cmdHex, frame, startNs);
            if (remoteResp != null) {
                this.serverBaseUrl = candidate;
                return remoteResp;
            }
        }

        byte[] fullResp = localFallback.processApdu(frame);
        long elapsedMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
        int sw = ApduCommands.SW_NO_ERROR;
        byte[] dataBytes = new byte[0];
        if (fullResp.length >= 2) {
            sw = ((fullResp[fullResp.length - 2] & 0xFF) << 8) | (fullResp[fullResp.length - 1] & 0xFF);
            dataBytes = Arrays.copyOf(fullResp, fullResp.length - 2);
        }
        String rawRespHex = ApduCommands.bytesToHex(dataBytes)
                + (dataBytes.length > 0 ? " " : "") + String.format("%04X", sw);
        recordApduLog(cmdHex, rawRespHex, elapsedMs);
        return new CardResponse(dataBytes, sw, elapsedMs, cmdHex, rawRespHex);
    }

    private CardResponse tryTransmitApdu(String baseUrl, String cmdHex, byte[] frame, long startNs) {
        try {
            URL url = new URL(baseUrl + "/api/apdu");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1800);
            conn.setReadTimeout(3500);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);

            String payload = "{\"apdu\":\"" + cmdHex.replace(" ", "") + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    String respText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject obj = JsonParser.parseString(respText).getAsJsonObject();
                    byte[] dataBytes = ApduCommands.hexToBytes(obj.get("data").getAsString());
                    int sw = Integer.parseInt(obj.get("sw").getAsString(), 16);
                    long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                    String rawRespHex = ApduCommands.bytesToHex(dataBytes)
                            + (dataBytes.length > 0 ? " " : "") + String.format("%04X", sw);
                    recordApduLog(cmdHex, rawRespHex, elapsedMs);
                    return new CardResponse(dataBytes, sw, elapsedMs, cmdHex, rawRespHex);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Uploads an avatar byte array across multiple APDU chunks (INS 0x22).
     *
     * @return the number of APDU packets sent, or -1 when the card rejects a chunk.
     */
    public int uploadAvatarInChunks(byte[] avatarBytes) {
        if (avatarBytes == null || avatarBytes.length == 0) {
            return 0;
        }
        int offset = 0;
        int packetCount = 0;
        while (offset < avatarBytes.length) {
            int len = Math.min(ApduCommands.MAX_AVATAR_CHUNK_SIZE, avatarBytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(avatarBytes, offset, offset + len);
            byte p1 = (byte) ((offset >> 8) & 0xFF);
            byte p2 = (byte) (offset & 0xFF);
            CardResponse resp = sendApdu(ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_UPLOAD_AVATAR_CHUNK, p1, p2, chunk);
            if (!resp.isSuccess()) {
                return -1;
            }
            offset += len;
            packetCount++;
        }
        return packetCount;
    }

    /**
     * Downloads an avatar from the card in chunks (INS 0x23), optionally reusing the internal buffer (OPT-05).
     */
    public synchronized byte[] downloadAvatarInChunks(boolean reuseBuffer) {
        ByteArrayOutputStream out = reuseBuffer ? reusableAvatarStream : new ByteArrayOutputStream();
        if (reuseBuffer) {
            reusableAvatarStream.reset();
        }
        int offset = 0;
        while (offset < 8192) {
            byte p1 = (byte) ((offset >> 8) & 0xFF);
            byte p2 = (byte) (offset & 0xFF);
            CardResponse resp = sendApdu(ApduCommands.CLA_SMART_STUDENT,
                    ApduCommands.INS_DOWNLOAD_AVATAR_CHUNK, p1, p2, new byte[]{(byte) 240});
            byte[] chunk = resp.getData();
            if (!resp.isSuccess() || chunk.length == 0) {
                break;
            }
            out.write(chunk, 0, chunk.length);
            offset += chunk.length;
            if (chunk.length < ApduCommands.MAX_AVATAR_CHUNK_SIZE) {
                break;
            }
        }
        return out.toByteArray();
    }

    /**
     * Switches the active virtual card on both the PC Bridge Server and local fallback engine.
     */
    public void activateVirtualCard(String studentId, String pipeDelimitedInfo, String pin) {
        localFallback.loadSnapshot(studentId, pipeDelimitedInfo, pin);
        String json = String.format("{\"studentId\":\"%s\",\"studentInfo\":\"%s\",\"pin\":\"%s\"}",
                escapeJsonString(studentId), escapeJsonString(pipeDelimitedInfo), escapeJsonString(pin));
        postJsonSilently("/api/activate-card", json);
    }

    // =========================================================================
    // RELATIONAL SQLITE SERVER SYNC API (`smartcard.db` on BridgeServer)
    // =========================================================================

    public void syncCardToServerDb(StudentCardRecord card) {
        if (card == null) return;
        postJsonSilently("/api/cards", gson.toJson(card));
    }

    public List<StudentCardRecord> fetchCardsFromServerDb() {
        String resp = getJsonSilently("/api/cards");
        if (resp == null || resp.isEmpty()) return null;
        try {
            Type type = new TypeToken<ArrayList<StudentCardRecord>>() {}.getType();
            return gson.fromJson(resp, type);
        } catch (Exception e) {
            return null;
        }
    }

    public void syncUnblockCardOnServerDb(String studentId) {
        String json = String.format("{\"studentId\":\"%s\"}", escapeJsonString(studentId));
        postJsonSilently("/api/cards/unblock", json);
    }

    /**
     * Sends the admin PUK to lock (P1=01) or unblock/reset (P1=00) the active card (INS 0x12).
     */
    public CardResponse sendPukAdminAction(String puk, boolean lock) {
        return sendApdu(ApduCommands.CLA_SMART_STUDENT,
                ApduCommands.INS_UNBLOCK_CARD_PUK,
                lock ? ApduCommands.P1_PUK_LOCK : ApduCommands.P1_PUK_UNBLOCK,
                (byte) 0x00,
                puk != null ? puk.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    public void syncUnblockAllCardsOnServerDb() {
        syncUnblockCardOnServerDb("");
    }

    public void syncAttendanceToServerDb(AttendanceRecord record) {
        if (record == null) return;
        postJsonSilently("/api/attendance", gson.toJson(record));
    }

    public List<AttendanceRecord> fetchAttendanceFromServerDb() {
        return fetchAttendanceByStudentFromServerDb(null);
    }

    public List<AttendanceRecord> fetchAttendanceByStudentFromServerDb(String studentId) {
        String path = (studentId != null && !studentId.trim().isEmpty())
                ? "/api/attendance?studentId=" + studentId.trim()
                : "/api/attendance";
        String resp = getJsonSilently(path);
        if (resp == null || resp.isEmpty()) return null;
        try {
            Type type = new TypeToken<ArrayList<AttendanceRecord>>() {}.getType();
            return gson.fromJson(resp, type);
        } catch (Exception e) {
            return null;
        }
    }

    public void syncBookLoanToServerDb(BookLoanRecord book) {
        if (book == null) return;
        postJsonSilently("/api/books", gson.toJson(book));
    }

    public List<BookLoanRecord> fetchBooksFromServerDb() {
        String resp = getJsonSilently("/api/books");
        if (resp == null || resp.isEmpty()) return null;
        try {
            Type type = new TypeToken<ArrayList<BookLoanRecord>>() {}.getType();
            return gson.fromJson(resp, type);
        } catch (Exception e) {
            return null;
        }
    }

    public void syncWalletTransactionToServerDb(WalletTransactionRecord tx) {
        if (tx == null) return;
        postJsonSilently("/api/wallet", gson.toJson(tx));
    }

    public List<WalletTransactionRecord> fetchWalletTransactionsByStudent(String studentId) {
        String path = (studentId != null && !studentId.trim().isEmpty())
                ? "/api/wallet?studentId=" + studentId.trim()
                : "/api/wallet";
        String resp = getJsonSilently(path);
        if (resp == null || resp.isEmpty()) return null;
        try {
            Type type = new TypeToken<ArrayList<WalletTransactionRecord>>() {}.getType();
            return gson.fromJson(resp, type);
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // ADMIN AUTHENTICATION API (`admin_accounts` table in `smartcard.db`)
    // =========================================================================

    public static class AdminAuthResult {
        public boolean success;
        public String message;
        public String token;
        public String username;
        public String fullName;
        public String role;
        public String lastLogin;

        public AdminAuthResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * Authenticates an administrator directly against the relational database on BridgeServer.
     * The server compares salted SHA-256 hashes stored in `admin_accounts`.
     */
    public AdminAuthResult authenticateAdmin(String username, String password) {
        String jsonBody = String.format("{\"username\":\"%s\",\"password\":\"%s\"}",
                escapeJsonString(username), escapeJsonString(password));

        AdminAuthResult res = tryPostAdminAuth(serverBaseUrl + "/api/auth/admin-login", jsonBody);
        if (res != null) return res;

        for (String candidate : CANDIDATE_BASE_URLS) {
            if (candidate.equalsIgnoreCase(serverBaseUrl)) continue;
            res = tryPostAdminAuth(candidate + "/api/auth/admin-login", jsonBody);
            if (res != null) {
                this.serverBaseUrl = candidate;
                return res;
            }
        }

        return new AdminAuthResult(false, "Không thể kết nối đến Máy chủ Trung tâm (BridgeServer). Vui lòng kiểm tra lại cáp kết nối hoặc mạng LAN.");
    }

    private AdminAuthResult tryPostAdminAuth(String fullUrl, String jsonBody) {
        try {
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (is == null) return null;
            String respStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            JsonObject root = JsonParser.parseString(respStr).getAsJsonObject();
            String status = root.has("status") ? root.get("status").getAsString() : "";
            String message = root.has("message") ? root.get("message").getAsString() : "";

            if (responseCode == 200 && "success".equalsIgnoreCase(status)) {
                AdminAuthResult ok = new AdminAuthResult(true, message);
                if (root.has("token")) ok.token = root.get("token").getAsString();
                if (root.has("admin") && root.get("admin").isJsonObject()) {
                    JsonObject adm = root.getAsJsonObject("admin");
                    if (adm.has("username")) ok.username = adm.get("username").getAsString();
                    if (adm.has("fullName")) ok.fullName = adm.get("fullName").getAsString();
                    if (adm.has("role")) ok.role = adm.get("role").getAsString();
                    if (adm.has("lastLogin")) ok.lastLogin = adm.get("lastLogin").getAsString();
                }
                return ok;
            } else {
                return new AdminAuthResult(false, !message.isEmpty() ? message : "Tài khoản hoặc mật khẩu không đúng (Mã " + responseCode + ")");
            }
        } catch (Exception e) {
            return null; // Network unreachable on this candidate URL, try next candidate
        }
    }

    private String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private void postJsonSilently(String path, String jsonBody) {
        if (tryPost(serverBaseUrl + path, jsonBody)) return;

        for (String candidate : CANDIDATE_BASE_URLS) {
            if (candidate.equalsIgnoreCase(serverBaseUrl)) continue;
            if (tryPost(candidate + path, jsonBody)) {
                this.serverBaseUrl = candidate;
                return;
            }
        }
    }

    private boolean tryPost(String fullUrl, String jsonBody) {
        try {
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3500);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            return conn.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String getJsonSilently(String path) {
        String res = tryGet(serverBaseUrl + path);
        if (res != null) return res;

        for (String candidate : CANDIDATE_BASE_URLS) {
            if (candidate.equalsIgnoreCase(serverBaseUrl)) continue;
            res = tryGet(candidate + path);
            if (res != null) {
                this.serverBaseUrl = candidate;
                return res;
            }
        }
        return null;
    }

    private String tryGet(String fullUrl) {
        try {
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3500);
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private synchronized void recordApduLog(String cmdHex, String respHex, long timeMs) {
        if (recentApduLogs.size() >= MAX_APDU_LOG_LINES) {
            recentApduLogs.remove(0);
        }
        recentApduLogs.add(">> " + cmdHex + "\n<< " + respHex + " (" + timeMs + " ms)");
    }

    public synchronized List<String> getRecentApduLogs() {
        return Collections.unmodifiableList(new ArrayList<>(recentApduLogs));
    }

    /**
     * Embedded fallback smart card simulator mirroring SmartCampusApplet for offline emulator testing.
     */
    public String getActiveCardId() { return localFallback.activeCardId; }
    public String getActiveCardInfo() { return localFallback.activeInfoUtf8; }

    /**
     * Returns the BridgeServer API version (2+ supports admin PUK lock), 1 for an outdated
     * server that predates the lock endpoint, or -1 when no server is reachable.
     */
    public int fetchBridgeApiVersion() {
        String respText = getJsonSilently("/api/status");
        if (respText == null || respText.isEmpty()) return -1;
        try {
            JsonObject obj = JsonParser.parseString(respText).getAsJsonObject();
            return obj.has("apiVersion") ? obj.get("apiVersion").getAsInt() : 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private long lastSeenTapEventId = -1L;

    /**
     * Polls the PC Bridge Server (/api/status) to check if a new .scr card file was just loaded on PC/JCIDE.
     * Returns true when a new card load event is detected.
     */
    public boolean pollBridgeCardTapEvent() {
        String respText = getJsonSilently("/api/status");
        if (respText == null || respText.isEmpty()) return false;
        try {
            JsonObject obj = JsonParser.parseString(respText).getAsJsonObject();
            long remoteTapId = obj.has("tapEventId") ? obj.get("tapEventId").getAsLong() : 0L;
            String studentId = obj.has("activeStudentId") ? obj.get("activeStudentId").getAsString() : "";
            String studentInfo = obj.has("activeStudentInfo") ? obj.get("activeStudentInfo").getAsString() : "";

            if (!studentInfo.isEmpty() && studentInfo.contains("|")) {
                localFallback.loadSnapshot(studentId, studentInfo, "123456");
            }

            if (lastSeenTapEventId == -1L) {
                lastSeenTapEventId = remoteTapId;
                return false;
            }
            if (remoteTapId > 0L && remoteTapId != lastSeenTapEventId) {
                lastSeenTapEventId = remoteTapId;
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static class LocalFallbackCardEngine {
        String activeInfoUtf8 = "CT070315|Vũ Ngọc Hải|15/08/2003|CT7C|An toàn Thông tin";
        String activeCardId = "CT070315";
        String currentPin = "123456";
        final String adminPuk = "88888888";
        int pinTriesRemaining = 3;
        boolean pinValidated = false;
        boolean adminLocked = false;

        byte[] encryptedInfo = new byte[0];
        int plainInfoLen = 0;
        final byte[] avatarBuffer = new byte[8192];
        int avatarLength = 0;
        private final java.util.Map<String, byte[]> perCardAvatars = new java.util.HashMap<>();

        short trainingScore = 88;
        short attendanceCount = 12;
        byte labAccess = 1;
        final short[] borrowedBooks = new short[]{0x0102, 0x0205, 0, 0, 0};
        byte borrowedCount = 2;
        int walletBalance = 500000;
        private final java.util.Map<String, Integer> perCardBalances = new java.util.HashMap<>();

        final byte[] activityLogs = new byte[70];
        byte logHead = 0;
        byte logCount = 0;

        KeyPair rsaKeyPair;
        final SecretKeySpec aesKey = new SecretKeySpec("SMART_STUDENT_ID".getBytes(StandardCharsets.UTF_8), "AES");

        LocalFallbackCardEngine() {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(1024);
                rsaKeyPair = kpg.generateKeyPair();
                loadSnapshot("CT070315", "CT070315|Vũ Ngọc Hải|15/08/2003|CT7C|An toàn Thông tin", "123456");
            } catch (Exception ignored) {
            }
        }

        void loadSnapshot(String cardId, String infoUtf8, String pin) {
            boolean sameCard = cardId != null && cardId.equalsIgnoreCase(this.activeCardId);
            if (!sameCard && this.activeCardId != null) {
                perCardBalances.put(this.activeCardId.toUpperCase(java.util.Locale.US), this.walletBalance);
                if (this.avatarLength > 0) {
                    perCardAvatars.put(this.activeCardId.toUpperCase(java.util.Locale.US),
                            Arrays.copyOf(avatarBuffer, avatarLength));
                }
            }
            this.activeCardId = cardId;
            this.activeInfoUtf8 = infoUtf8;
            if (!sameCard) {
                Integer savedBalance = cardId != null
                        ? perCardBalances.get(cardId.toUpperCase(java.util.Locale.US))
                        : null;
                this.walletBalance = savedBalance != null ? savedBalance : 500000;
                byte[] saved = cardId != null ? perCardAvatars.get(cardId.toUpperCase(java.util.Locale.US)) : null;
                if (saved != null && saved.length <= avatarBuffer.length) {
                    System.arraycopy(saved, 0, avatarBuffer, 0, saved.length);
                    this.avatarLength = saved.length;
                } else {
                    this.avatarLength = 0;
                }
            }
            this.currentPin = (pin != null && !pin.isEmpty()) ? pin : "123456";
            this.pinTriesRemaining = 3;
            this.pinValidated = true;
            this.adminLocked = false;
            encryptAndStoreInfo(infoUtf8.getBytes(StandardCharsets.UTF_8));
        }

        private void encryptAndStoreInfo(byte[] plain) {
            try {
                plainInfoLen = plain.length;
                int paddedLen = ((plain.length + 16) / 16) * 16;
                byte padVal = (byte) (paddedLen - plain.length);
                byte[] padded = Arrays.copyOf(plain, paddedLen);
                Arrays.fill(padded, plain.length, paddedLen, padVal);

                Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, aesKey);
                encryptedInfo = cipher.doFinal(padded);
            } catch (Exception ignored) {
            }
        }

        byte[] processApdu(byte[] apdu) {
            if (apdu == null || apdu.length < 4) return swOnly(0x6F00);
            int cla = apdu[0] & 0xFF;
            int ins = apdu[1] & 0xFF;
            int p1 = apdu[2] & 0xFF;
            int p2 = apdu[3] & 0xFF;
            int lc = (apdu.length > 4) ? (apdu[4] & 0xFF) : 0;
            byte[] cdata = (apdu.length >= 5 + lc && lc > 0)
                    ? Arrays.copyOfRange(apdu, 5, 5 + lc)
                    : new byte[0];

            if (cla == 0x00 && ins == 0xA4) {
                return withSw(new byte[]{(byte) pinTriesRemaining, (byte) (plainInfoLen > 0 ? 1 : 0)}, 0x9000);
            }
            if (cla != 0x80) return swOnly(0x6E00);

            try {
                switch (ins) {
                    case 0x10: {
                        if (adminLocked || pinTriesRemaining == 0) return swOnly(0x6983);
                        String inputPin = new String(cdata, StandardCharsets.UTF_8);
                        if (currentPin.equals(inputPin)) {
                            pinTriesRemaining = 3;
                            pinValidated = true;
                            return swOnly(0x9000);
                        } else {
                            pinTriesRemaining--;
                            pinValidated = false;
                            return swOnly(pinTriesRemaining == 0 ? 0x6983 : (0x63C0 | pinTriesRemaining));
                        }
                    }
                    case 0x11: {
                        if (!pinValidated) return swOnly(0x6982);
                        int oldLen = cdata[0] & 0xFF;
                        String oldPin = new String(cdata, 1, oldLen, StandardCharsets.UTF_8);
                        if (!currentPin.equals(oldPin)) {
                            pinTriesRemaining = Math.max(0, pinTriesRemaining - 1);
                            return swOnly(pinTriesRemaining == 0 ? 0x6983 : (0x63C0 | pinTriesRemaining));
                        }
                        int newLen = cdata[1 + oldLen] & 0xFF;
                        currentPin = new String(cdata, 2 + oldLen, newLen, StandardCharsets.UTF_8);
                        return swOnly(0x9000);
                    }
                    case 0x12: {
                        String puk = new String(cdata, StandardCharsets.UTF_8);
                        if (!adminPuk.equals(puk)) return swOnly(0x6300);
                        if (p1 == 0x01) {
                            // P1=01: Admin PUK locks the card (all PIN operations rejected)
                            adminLocked = true;
                            pinTriesRemaining = 0;
                            pinValidated = false;
                        } else {
                            // P1=00: Admin PUK unblocks and restores the default PIN
                            adminLocked = false;
                            pinTriesRemaining = 3;
                            currentPin = "123456";
                            pinValidated = true;
                        }
                        return swOnly(0x9000);
                    }
                    case 0x13: {
                        if (p1 == 0x00) {
                            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                            kpg.initialize(1024);
                            rsaKeyPair = kpg.generateKeyPair();
                        }
                        RSAPublicKey pub = (RSAPublicKey) rsaKeyPair.getPublic();
                        if (p1 == 0x02) {
                            return withSw(pub.getPublicExponent().toByteArray(), 0x9000);
                        }
                        byte[] mod = pub.getModulus().toByteArray();
                        if (mod.length == 129 && mod[0] == 0) {
                            mod = Arrays.copyOfRange(mod, 1, 129);
                        }
                        return withSw(mod, 0x9000);
                    }
                    case 0x14: {
                        Signature sig = Signature.getInstance("SHA1withRSA");
                        sig.initSign(rsaKeyPair.getPrivate());
                        sig.update(cdata);
                        return withSw(sig.sign(), 0x9000);
                    }
                    case 0x20: {
                        if (!pinValidated) return swOnly(0x6982);
                        encryptAndStoreInfo(cdata);
                        return swOnly(0x9000);
                    }
                    case 0x21: {
                        if (p1 == 0x01) return withSw(encryptedInfo, 0x9000);
                        if (!pinValidated) return swOnly(0x6982);
                        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, aesKey);
                        byte[] dec = cipher.doFinal(encryptedInfo);
                        return withSw(Arrays.copyOf(dec, plainInfoLen), 0x9000);
                    }
                    case 0x22: {
                        if (!pinValidated) return swOnly(0x6982);
                        int offset = (p1 << 8) | p2;
                        if (offset + cdata.length <= avatarBuffer.length) {
                            System.arraycopy(cdata, 0, avatarBuffer, offset, cdata.length);
                            if (offset == 0 || offset + cdata.length > avatarLength) {
                                avatarLength = offset + cdata.length;
                            }
                            if (activeCardId != null) {
                                perCardAvatars.put(activeCardId.toUpperCase(java.util.Locale.US),
                                        Arrays.copyOf(avatarBuffer, avatarLength));
                            }
                        }
                        return swOnly(0x9000);
                    }
                    case 0x23: {
                        int offset = (p1 << 8) | p2;
                        if (offset >= avatarLength) return withSw(new byte[0], 0x9000);
                        int len = Math.min(240, avatarLength - offset);
                        return withSw(Arrays.copyOfRange(avatarBuffer, offset, offset + len), 0x9000);
                    }
                    case 0x30: {
                        if (!pinValidated) return swOnly(0x6982);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bos.write((trainingScore >> 8) & 0xFF);
                        bos.write(trainingScore & 0xFF);
                        bos.write((attendanceCount >> 8) & 0xFF);
                        bos.write(attendanceCount & 0xFF);
                        bos.write(labAccess);
                        bos.write(borrowedCount);
                        for (int i = 0; i < borrowedCount; i++) {
                            bos.write((borrowedBooks[i] >> 8) & 0xFF);
                            bos.write(borrowedBooks[i] & 0xFF);
                        }
                        bos.write((walletBalance >> 24) & 0xFF);
                        bos.write((walletBalance >> 16) & 0xFF);
                        bos.write((walletBalance >> 8) & 0xFF);
                        bos.write(walletBalance & 0xFF);
                        return withSw(bos.toByteArray(), 0x9000);
                    }
                    case 0x31:
                    case 0x32: {
                        if (!pinValidated) return swOnly(0x6982);
                        if (ins == 0x31 && p1 == 0x01) {
                            attendanceCount++;
                            if (trainingScore < 100) trainingScore++;
                            addLog((byte) 1, cdata);
                        } else if (ins == 0x31 && p1 == 0x02 && borrowedCount < 5 && cdata.length >= 2) {
                            short bookCode = (short) (((cdata[0] & 0xFF) << 8) | (cdata[1] & 0xFF));
                            borrowedBooks[borrowedCount++] = bookCode;
                            addLog((byte) 2, cdata);
                        } else if (ins == 0x32 && p1 == 0x01 && borrowedCount > 0) {
                            borrowedCount--;
                            addLog((byte) 3, cdata);
                        } else {
                            addLog((byte) 4, cdata);
                        }
                        Signature sig = Signature.getInstance("SHA1withRSA");
                        sig.initSign(rsaKeyPair.getPrivate());
                        sig.update(cdata);
                        return withSw(sig.sign(), 0x9000);
                    }
                    case 0x33: {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bos.write(logCount);
                        for (int i = 0; i < logCount; i++) {
                            int idx = (logHead - 1 - i + 10) % 10;
                            bos.write(activityLogs, idx * 7, 7);
                        }
                        return withSw(bos.toByteArray(), 0x9000);
                    }
                    case 0x40:
                        return handleWalletMutation(true, cdata);
                    case 0x41:
                        return handleWalletMutation(false, cdata);
                    case 0x42: {
                        if (!pinValidated) return swOnly(0x6982);
                        return withSw(new byte[]{
                                (byte) ((walletBalance >> 24) & 0xFF),
                                (byte) ((walletBalance >> 16) & 0xFF),
                                (byte) ((walletBalance >> 8) & 0xFF),
                                (byte) (walletBalance & 0xFF)
                        }, 0x9000);
                    }
                    default:
                        return swOnly(0x6D00);
                }
            } catch (Exception e) {
                return swOnly(0x6F00);
            }
        }

        private static final int MAX_WALLET_BALANCE = 50_000_000;

        private byte[] handleWalletMutation(boolean isCredit, byte[] cdata) throws Exception {
            if (!pinValidated) return swOnly(0x6982);
            if (cdata.length < 8) return swOnly(0x6700);
            int amount = ((cdata[0] & 0xFF) << 24)
                    | ((cdata[1] & 0xFF) << 16)
                    | ((cdata[2] & 0xFF) << 8)
                    | (cdata[3] & 0xFF);
            int tsInt = ((cdata[4] & 0xFF) << 24)
                    | ((cdata[5] & 0xFF) << 16)
                    | ((cdata[6] & 0xFF) << 8)
                    | (cdata[7] & 0xFF);
            if (amount <= 0) return swOnly(0x6984);

            if (isCredit) {
                if ((long) walletBalance + amount > MAX_WALLET_BALANCE) {
                    return swOnly(ApduCommands.SW_FILE_FULL);
                }
                walletBalance += amount;
            } else {
                if (walletBalance < amount) return swOnly(ApduCommands.SW_CONDITIONS_NOT_SATISFIED);
                walletBalance -= amount;
            }
            if (activeCardId != null) {
                perCardBalances.put(activeCardId.toUpperCase(java.util.Locale.US), walletBalance);
            }

            byte logType = (byte) (isCredit ? 5 : 6);
            byte logCode = (byte) (isCredit ? ApduCommands.INS_WALLET_CREDIT : ApduCommands.INS_WALLET_DEBIT);
            addLog(logType, new byte[]{0x00, logCode, cdata[4], cdata[5], cdata[6], cdata[7]});

            byte[] canonicalPayload = ApduCommands.buildCanonicalWalletReceipt(logCode, amount, walletBalance, tsInt);
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(rsaKeyPair.getPrivate());
            sig.update(canonicalPayload);
            return withSw(sig.sign(), 0x9000);
        }

        private void addLog(byte type, byte[] cdata) {
            int pos = logHead * 7;
            activityLogs[pos] = type;
            int copyLen = Math.min(6, cdata.length);
            System.arraycopy(cdata, 0, activityLogs, pos + 1, copyLen);
            logHead = (byte) ((logHead + 1) % 10);
            if (logCount < 10) logCount++;
        }

        private byte[] swOnly(int sw) {
            return new byte[]{(byte) ((sw >> 8) & 0xFF), (byte) (sw & 0xFF)};
        }

        private byte[] withSw(byte[] data, int sw) {
            byte[] res = Arrays.copyOf(data, data.length + 2);
            res[data.length] = (byte) ((sw >> 8) & 0xFF);
            res[data.length + 1] = (byte) (sw & 0xFF);
            return res;
        }
    }
}
