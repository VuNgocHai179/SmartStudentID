import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

/**
 * ============================================================================
 * PC BRIDGE & JCIDE CARD LOADER SERVER + SQLITE DB ENGINE (SC-07)
 * Default port: 8080 (Web UI: http://localhost:8080 | Phone USB: 127.0.0.1:8080)
 * ============================================================================
 * - Connects to JCIDE Simulator via PC/SC (javax.smartcardio) or built-in emulator.
 * - Manages relational data in SQLite (`bridge-server/data/smartcard.db`) via JDBC
 *   with 4 tables: `cards`, `attendance_logs`, `book_loans`, `card_audit_logs`.
 * - Supports loading .scr APDU script files directly from PC/JCIDE and
 *   automatically triggering the PIN popup on the connected Android phone!
 */
public class BridgeServer {

    private static final int PORT = Integer.parseInt(System.getProperty("bridge.port", "8080"));
    private static final File SCRIPTS_DIR = new File("javacard-applet/scripts");
    private static final File WEB_DIR = new File("bridge-server/web");

    private static final VirtualSmartCardEmulator virtualCard = new VirtualSmartCardEmulator();
    private static CardChannel pcscChannel = null;
    private static String activeReaderName = "Virtual-JCIDE-Fallback-Engine";

    // Increments whenever a card file (.scr) is loaded or tapped from PC / JCIDE
    private static volatile long tapEventId = 0L;
    private static volatile String lastObservedJcideInfo = "";

    public static void main(String[] args) throws Exception {
        // Initialize SQLite Relational Database (smartcard.db)
        DatabaseManager.initDatabase();
        virtualCard.reloadFromDatabase();

        tryConnectPcscReader();
        startJcideMonitorThread();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);

        // 0. Web Console at http://localhost:8080 serving static UI files from bridge-server/web
        server.createContext("/", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            serveStaticWeb(exchange);
        });

        // 1. Endpoint for checking Bridge & Card Reader connection status + tapEventId for phone auto-popup
        server.createContext("/api/status", exchange -> sendJson(exchange, 200, buildStatusJson()));

        // 2. Endpoint for executing a .scr APDU script file (by file name or raw script content)
        server.createContext("/api/load-scr", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = readBody(exchange);
            String fileName = extractJsonString(body, "fileName");
            String scriptContent = extractJsonString(body, "scriptContent");

            if (!fileName.isEmpty() && scriptContent.isEmpty()) {
                File f = new File(SCRIPTS_DIR, fileName);
                if (f.exists()) {
                    scriptContent = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                }
            }

            List<String> logs = executeScrScript(scriptContent);
            virtualCard.applyLockState(DatabaseManager.isCardLocked(virtualCard.activeCardId));
            syncActiveCardInfoToDatabase();
            tapEventId = System.currentTimeMillis();
            System.out.println("[SCR LOADER] Loaded card script -> Active Student: " + virtualCard.activeInfoUtf8 + " (tapEventId=" + tapEventId + ")");

            StringBuilder logJson = new StringBuilder("[");
            for (int i = 0; i < logs.size(); i++) {
                if (i > 0) logJson.append(",");
                logJson.append("\"").append(escapeJson(logs.get(i))).append("\"");
            }
            logJson.append("]");

            String resp = String.format(
                    "{\"loaded\":true,\"tapEventId\":%d,\"activeStudentId\":\"%s\",\"activeStudentInfo\":\"%s\",\"logs\":%s}",
                    tapEventId,
                    escapeJson(virtualCard.activeCardId),
                    escapeJson(virtualCard.activeInfoUtf8),
                    logJson
            );
            sendJson(exchange, 200, resp);
        });

        // 3. Endpoint for transmitting raw APDU commands (Hex request -> Hex response)
        server.createContext("/api/apdu", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = readBody(exchange);
            String cmdHex = extractJsonString(body, "apdu");
            byte[] cmdBytes = hexToBytes(cmdHex);

            long startNs = System.nanoTime();
            byte[] fullResp = transmitApdu(cmdBytes);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            int sw = 0x9000;
            byte[] dataBytes = new byte[0];
            if (fullResp.length >= 2) {
                sw = ((fullResp[fullResp.length - 2] & 0xFF) << 8) | (fullResp[fullResp.length - 1] & 0xFF);
                dataBytes = Arrays.copyOf(fullResp, fullResp.length - 2);
            }

            String respJson = String.format(
                    "{\"command\":\"%s\",\"data\":\"%s\",\"sw\":\"%04X\",\"timeMs\":%d,\"reader\":\"%s\"}",
                    bytesToHex(cmdBytes), bytesToHex(dataBytes), sw, elapsedMs, escapeJson(activeReaderName)
            );
            System.out.printf("[APDU] >> %s | << Data(%dB) SW=%04X (%dms)%n",
                    bytesToHex(cmdBytes), dataBytes.length, sw, elapsedMs);
            sendJson(exchange, 200, respJson);
        });

        // 4. Endpoint for retrieving or upserting Student Cards in SQLite (`cards` table)
        server.createContext("/api/cards", exchange -> {
            // Java HttpServer matches contexts by prefix: do not let unknown /api/cards/* paths
            // fall through into the POST upsert handler (which could overwrite card fields).
            if (!"/api/cards".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 200, DatabaseManager.getAllCardsJson());
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                String studentId = extractJsonString(body, "studentId");
                String fullName = extractJsonString(body, "fullName");
                String dob = extractJsonString(body, "dob");
                String className = extractJsonString(body, "className");
                String department = extractJsonString(body, "department");
                String defaultPin = extractJsonString(body, "defaultPin");
                boolean cardLocked = extractJsonBoolean(body, "cardLocked", false);
                int trainingScore = extractJsonInt(body, "trainingScore", 85);
                int attendanceCount = extractJsonInt(body, "attendanceCount", 0);
                int walletBalance = extractJsonInt(body, "walletBalance", -1);
                String modHex = extractJsonString(body, "publicKeyModulusHex");
                String expHex = extractJsonString(body, "publicKeyExponentHex");
                String cipherHex = extractJsonString(body, "rawAesCiphertextHex");

                boolean ok = DatabaseManager.upsertCard(studentId, fullName, dob, className,
                        department, defaultPin, cardLocked, trainingScore, attendanceCount,
                        modHex, expHex, cipherHex, walletBalance);
                if (ok && walletBalance >= 0 && studentId.equalsIgnoreCase(virtualCard.activeCardId)) {
                    virtualCard.walletBalance = walletBalance;
                }
                sendJson(exchange, 200, "{\"saved\":" + ok + ",\"db\":\"smartcard.db\"}");
            } else if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                String sid = extractQueryParam(exchange.getRequestURI().getQuery(), "studentId");
                boolean deleted = DatabaseManager.deleteCard(sid);
                System.out.println("[SQLITE DB] Deleted card: " + sid + " -> " + deleted);
                sendJson(exchange, 200, "{\"deleted\":" + deleted + ",\"studentId\":\"" + escapeJson(sid) + "\"}");
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        });

        // 5. Endpoint for unblocking PIN-locked cards in SQLite (`cards` table)
        server.createContext("/api/cards/unblock", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = readBody(exchange);
            String studentId = extractJsonString(body, "studentId");
            if (studentId.isEmpty()) {
                studentId = virtualCard.activeCardId;
            }
            boolean ok = DatabaseManager.unblockCardByPuk(studentId);
            sendJson(exchange, 200, "{\"unblocked\":" + ok + ",\"studentId\":\"" + escapeJson(studentId) + "\",\"db\":\"smartcard.db\"}");
        });

        // 5a. Endpoint for serving persisted WebP student portrait from SQLite (`cards.avatar_webp_base64`)
        server.createContext("/api/cards/avatar", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String sid = extractQueryParam(exchange.getRequestURI().getQuery(), "studentId");
            if (sid.isEmpty()) sid = virtualCard.activeCardId;
            String b64 = DatabaseManager.getCardAvatarBase64(sid);
            if (b64 == null || b64.isEmpty()) {
                sendJson(exchange, 404, "{\"found\":false}");
                return;
            }
            try {
                byte[] imgBytes = Base64.getDecoder().decode(b64);
                exchange.getResponseHeaders().set("Content-Type", "image/webp");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, imgBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(imgBytes);
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"found\":false}");
            }
        });

        // 5b. NOTE: lock state is persisted through POST /api/cards (full card record) and by the
        // INS 0x12 handler itself. No separate /api/cards/lock route is exposed.

        // 6. Endpoint for Attendance History in SQLite (`attendance_logs` table)
        server.createContext("/api/attendance", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String studentIdFilter = extractQueryParam(exchange.getRequestURI().getQuery(), "studentId");
                sendJson(exchange, 200, DatabaseManager.getAttendanceJsonByStudent(studentIdFilter.isEmpty() ? null : studentIdFilter));
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                String studentId = extractJsonString(body, "studentId");
                String courseCode = extractJsonString(body, "courseCode");
                String courseName = extractJsonString(body, "courseName");
                long ts = extractJsonLong(body, "timestampSeconds", System.currentTimeMillis() / 1000L);
                String sigHex = extractJsonString(body, "rsaSignatureHex");
                boolean verified = extractJsonBoolean(body, "verifiedByServer", true);

                boolean ok = DatabaseManager.insertAttendance(
                        studentId.isEmpty() ? virtualCard.activeCardId : studentId,
                        courseCode, courseName, ts, sigHex, verified);
                sendJson(exchange, 200, "{\"saved\":" + ok + ",\"db\":\"smartcard.db\"}");
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        });

        // 7. Endpoint for Library Book Loans in SQLite (`book_loans` table)
        server.createContext("/api/books", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 200, DatabaseManager.getAllBooksJson());
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                int bookCode = extractJsonInt(body, "bookCode", 0x0102);
                String studentId = extractJsonString(body, "studentId");
                String bookTitle = extractJsonString(body, "bookTitle");
                String author = extractJsonString(body, "author");
                boolean borrowed = extractJsonBoolean(body, "borrowed", false);
                long ts = extractJsonLong(body, "timestampSeconds", System.currentTimeMillis() / 1000L);
                String sigHex = extractJsonString(body, "rsaSignatureHex");

                boolean ok = DatabaseManager.updateBookLoan(
                        bookCode,
                        studentId.isEmpty() ? virtualCard.activeCardId : studentId,
                        bookTitle, author, borrowed, ts, sigHex);
                sendJson(exchange, 200, "{\"saved\":" + ok + ",\"db\":\"smartcard.db\"}");
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        });

        // 7b. Endpoint for E-Wallet Transactions in SQLite (`wallet_transactions` & `cards.wallet_balance`)
        server.createContext("/api/wallet", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String studentIdFilter = extractQueryParam(exchange.getRequestURI().getQuery(), "studentId");
                sendJson(exchange, 200, DatabaseManager.getWalletTransactionsJsonByStudent(studentIdFilter.isEmpty() ? null : studentIdFilter));
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                String studentId = extractJsonString(body, "studentId");
                String txType = extractJsonString(body, "txType");
                int amount = extractJsonInt(body, "amount", 0);
                int balanceBefore = extractJsonInt(body, "balanceBefore", 0);
                int balanceAfter = extractJsonInt(body, "balanceAfter", virtualCard.walletBalance);
                String txRef = extractJsonString(body, "txRef");
                String counterparty = extractJsonString(body, "counterparty");
                String description = extractJsonString(body, "description");
                long ts = extractJsonLong(body, "timestampSeconds", System.currentTimeMillis() / 1000L);
                String sigHex = extractJsonString(body, "rsaSignatureHex");

                boolean isCredit = !"DEBIT".equalsIgnoreCase(txType);
                boolean rsaVerified = verifyWalletReceiptOnServer(isCredit, amount, balanceAfter, ts, sigHex);

                boolean ok = DatabaseManager.recordWalletTransaction(
                        studentId.isEmpty() ? virtualCard.activeCardId : studentId,
                        txType, amount, balanceAfter, description, ts, sigHex, rsaVerified,
                        txRef, balanceBefore, counterparty);
                sendJson(exchange, 200, "{\"saved\":" + ok + ",\"verifiedByServer\":" + rsaVerified + ",\"db\":\"smartcard.db\"}");
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        });

        // 8. Endpoint for full SQLite Database Summary (`cards`, `attendance_logs`, `book_loans`, `card_audit_logs`)
        server.createContext("/api/db-summary", exchange -> {
            String dbJson = DatabaseManager.getDatabaseSummaryJson();
            String augmentedJson = "{\"activeStudentId\":\"" + escapeJson(virtualCard.activeCardId)
                    + "\",\"activeStudentInfo\":\"" + escapeJson(virtualCard.activeInfoUtf8) + "\","
                    + dbJson.substring(1);
            sendJson(exchange, 200, augmentedJson);
        });

        // 9. Endpoint for switching the active virtual card in the reader (SC-07)
        server.createContext("/api/activate-card", exchange -> {
            String body = readBody(exchange);
            String studentId = extractJsonString(body, "studentId");
            String infoPlain = extractJsonString(body, "studentInfo");
            String pin = extractJsonString(body, "pin");

            virtualCard.loadSnapshot(studentId, infoPlain, pin.isEmpty() ? "123456" : pin);
            virtualCard.applyLockState(DatabaseManager.isCardLocked(studentId));
            syncActiveCardInfoToDatabase();
            tapEventId = System.currentTimeMillis();
            System.out.println("[CARD SWITCHER] Activated virtual card for Student: " + infoPlain);
            sendJson(exchange, 200, String.format("{\"activated\":true,\"studentId\":\"%s\",\"tapEventId\":%d}",
                    escapeJson(studentId), tapEventId));
        });

        // 10. Endpoint: Admin Login Authentication against SQLite DB
        server.createContext("/api/auth/admin-login", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"status\":\"error\",\"message\":\"Method Not Allowed\"}");
                return;
            }
            try {
                String body = readBody(exchange);
                String username = extractJsonString(body, "username");
                String password = extractJsonString(body, "password");

                if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
                    sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Vui lòng nhập tên đăng nhập và mật khẩu\"}");
                    return;
                }

                DatabaseManager.AdminUser admin = DatabaseManager.authenticateAdmin(username.trim(), password);
                if (admin != null) {
                    String token = java.util.UUID.randomUUID().toString();
                    System.out.println("[ADMIN AUTH] Login SUCCESS: " + admin.username + " (" + admin.fullName + ")");
                    String resp = String.format(
                            "{\"status\":\"success\",\"message\":\"Đăng nhập quản trị viên thành công\","
                                    + "\"token\":\"%s\",\"admin\":{\"id\":%d,\"username\":\"%s\",\"fullName\":\"%s\",\"role\":\"%s\",\"lastLogin\":\"%s\"}}",
                            token, admin.id, escapeJson(admin.username), escapeJson(admin.fullName), escapeJson(admin.role), escapeJson(admin.lastLogin)
                    );
                    sendJson(exchange, 200, resp);
                } else {
                    System.out.println("[ADMIN AUTH] Login FAILED for username: '" + username + "' (Invalid credentials)");
                    sendJson(exchange, 401, "{\"status\":\"error\",\"message\":\"Tên đăng nhập hoặc mật khẩu quản trị viên không chính xác\"}");
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Lỗi xác thực máy chủ: " + escapeJson(e.getMessage()) + "\"}");
            }
        });

        // 11. Endpoint: List registered Admin accounts from SQLite DB
        server.createContext("/api/admin/accounts", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 200, DatabaseManager.getAllAdminAccountsJson());
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        });

        // 12. Endpoint: List available .scr APDU scripts in javacard-applet/scripts
        server.createContext("/api/scripts", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            StringBuilder sb = new StringBuilder("[");
            File[] files = SCRIPTS_DIR.exists() ? SCRIPTS_DIR.listFiles((dir, name) -> name.toLowerCase().endsWith(".scr")) : null;
            if (files != null) {
                Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                for (int i = 0; i < files.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("{\"fileName\":\"").append(escapeJson(files[i].getName()))
                            .append("\",\"sizeBytes\":").append(files[i].length()).append("}");
                }
            }
            sb.append("]");
            sendJson(exchange, 200, sb.toString());
        });

        // 13. Endpoint: Query security & APDU audit logs (`card_audit_logs` table)
        server.createContext("/api/audit-logs", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String studentIdFilter = extractQueryParam(query, "studentId");
            String limitRaw = extractQueryParam(query, "limit");
            int limit = 200;
            if (!limitRaw.isEmpty()) {
                try {
                    limit = Integer.parseInt(limitRaw);
                } catch (NumberFormatException ignored) {
                }
            }
            sendJson(exchange, 200, DatabaseManager.getAuditLogsJsonByStudent(
                    studentIdFilter.isEmpty() ? null : studentIdFilter, limit));
        });

        // 14. Endpoint: Force re-scan physical / JCIDE PC/SC smart card readers
        server.createContext("/api/pcsc/reconnect", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            pcscChannel = null;
            activeReaderName = "Virtual-JCIDE-Fallback-Engine";
            tryConnectPcscReader();
            sendJson(exchange, 200, buildStatusJson());
        });

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("==================================================================");
        System.out.println(" SMART STUDENT ID - PC BRIDGE & SQLITE DB SERVER ON PORT " + PORT);
        System.out.println(" Web Loader & SQLite UI : http://localhost:8080");
        System.out.println(" SQLite Database File   : bridge-server/data/smartcard.db");
        System.out.println(" Active Card Reader     : " + activeReaderName);
        System.out.println("==================================================================");
        Thread.currentThread().join();
    }

    private static void syncActiveCardInfoToDatabase() {
        String info = virtualCard.activeInfoUtf8;
        if (info != null && info.contains("|")) {
            String[] p = info.split("\\|");
            String sid = p.length > 0 ? p[0] : virtualCard.activeCardId;
            String name = p.length > 1 ? p[1] : "";
            String dob = p.length > 2 ? p[2] : "";
            String cls = p.length > 3 ? p[3] : "";
            String dept = p.length > 4 ? p[4] : "";
            DatabaseManager.upsertCard(sid, name, dob, cls, dept, virtualCard.currentPin,
                    virtualCard.adminLocked || virtualCard.pinTriesRemaining == 0,
                    virtualCard.trainingScore, virtualCard.attendanceCount,
                    "", "", bytesToHex(virtualCard.encryptedInfo));
            virtualCard.persistAvatarIfNeeded();
        }
    }

    /**
     * Parses and executes a JCIDE .scr script file (each APDU line ending with ';').
     */
    private static synchronized List<String> executeScrScript(String scriptContent) {
        List<String> logs = new ArrayList<>();
        if (scriptContent == null || scriptContent.isEmpty()) {
            return logs;
        }
        String[] lines = scriptContent.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#") || line.startsWith("/*")) {
                continue;
            }
            int commentIdx = line.indexOf("//");
            if (commentIdx >= 0) {
                line = line.substring(0, commentIdx).trim();
            }
            line = line.replace(";", "").trim();
            if (line.isEmpty()) continue;

            byte[] cmdBytes = hexToBytes(line);
            if (cmdBytes.length < 4) continue;

            byte[] resp = transmitApdu(cmdBytes);
            int sw = 0x9000;
            byte[] data = new byte[0];
            if (resp.length >= 2) {
                sw = ((resp[resp.length - 2] & 0xFF) << 8) | (resp[resp.length - 1] & 0xFF);
                data = Arrays.copyOf(resp, resp.length - 2);
            }
            logs.add(String.format(">> %s  ==>  << %s (SW=%04X)", bytesToHex(cmdBytes), bytesToHex(data), sw));
        }
        return logs;
    }

    /**
     * Monitors the PC/SC reader so if the user runs a .scr script directly inside JCIDE,
     * BridgeServer detects the new student profile on the chip and triggers tapEventId!
     */
    private static void startJcideMonitorThread() {
        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1200);
                    if (pcscChannel == null) {
                        tryConnectPcscReader();
                    }
                    if (pcscChannel != null) {
                        ResponseAPDU resp = pcscChannel.transmit(new CommandAPDU(new byte[]{(byte) 0x80, 0x21, 0x00, 0x00, 0x00}));
                        if (resp.getSW() == 0x9000 && resp.getData().length > 5) {
                            String info = new String(resp.getData(), StandardCharsets.UTF_8).trim();
                            if (!info.isEmpty() && !info.equals(lastObservedJcideInfo) && info.contains("|")) {
                                lastObservedJcideInfo = info;
                                String[] parts = info.split("\\|");
                                String sid = parts.length > 0 ? parts[0] : "CT070315";
                                virtualCard.loadSnapshot(sid, info, "123456");
                                syncActiveCardInfoToDatabase();
                                tapEventId = System.currentTimeMillis();
                                System.out.println("[JCIDE MONITOR] Detected new card loaded in JCIDE: " + info);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
    }

    private static void tryConnectPcscReader() {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            List<CardTerminal> terminals = factory.terminals().list();
            for (CardTerminal terminal : terminals) {
                if (terminal.isCardPresent()) {
                    Card card = terminal.connect("*");
                    pcscChannel = card.getBasicChannel();
                    activeReaderName = terminal.getName();
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static synchronized byte[] transmitApdu(byte[] cmdBytes) {
        byte[] virtualResp = virtualCard.processApdu(cmdBytes);
        if (pcscChannel != null) {
            try {
                ResponseAPDU resp = pcscChannel.transmit(new CommandAPDU(cmdBytes));
                return resp.getBytes();
            } catch (Exception e) {
                pcscChannel = null;
                activeReaderName = "Virtual-JCIDE-Fallback-Engine";
            }
        }
        return virtualResp;
    }

    private static String buildStatusJson() {
        boolean locked = virtualCard.adminLocked || virtualCard.pinTriesRemaining == 0;
        return String.format(
                "{\"status\":\"ONLINE\",\"apiVersion\":2,\"dbEngine\":\"SQLite (smartcard.db)\",\"reader\":\"%s\","
                        + "\"pcscConnected\":%s,\"activeStudentId\":\"%s\",\"activeStudentInfo\":\"%s\","
                        + "\"pinTries\":%d,\"cardLocked\":%s,\"walletBalance\":%d,"
                        + "\"trainingScore\":%d,\"attendanceCount\":%d,\"hasAvatar\":%s,\"tapEventId\":%d}",
                escapeJson(activeReaderName),
                (pcscChannel != null),
                escapeJson(virtualCard.activeCardId),
                escapeJson(virtualCard.activeInfoUtf8),
                virtualCard.pinTriesRemaining,
                locked,
                virtualCard.walletBalance,
                virtualCard.trainingScore,
                virtualCard.attendanceCount,
                (virtualCard.avatarLength > 0),
                tapEventId
        );
    }

    private static void serveStaticWeb(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getPath();
        if (rawPath == null || "/".equals(rawPath)) {
            rawPath = "/index.html";
        }
        String relPath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (relPath.contains("..")) {
            sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
            return;
        }
        File target = new File(WEB_DIR, relPath);
        try {
            String baseCanonical = WEB_DIR.getCanonicalPath();
            String targetCanonical = target.getCanonicalPath();
            if (!targetCanonical.startsWith(baseCanonical + File.separator)) {
                sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
        } catch (IOException e) {
            sendJson(exchange, 500, "{\"error\":\"Server Error\"}");
            return;
        }

        if (!target.exists() || !target.isFile()) {
            sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
            return;
        }

        byte[] content = Files.readAllBytes(target.toPath());
        exchange.getResponseHeaders().set("Content-Type", resolveContentType(target.getName()));
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private static String resolveContentType(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static String extractQueryParam(String query, String key) {
        if (query == null || query.isEmpty() || key == null || key.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(k)) {
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return "";
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = firstQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                if (next == 'n') sb.append('\n');
                else if (next == 'r') sb.append('\r');
                else if (next == 't') sb.append('\t');
                else sb.append(next);
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int extractJsonInt(String json, String key, int defaultVal) {
        String raw = extractJsonRawPrimitive(json, key);
        if (raw.isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static long extractJsonLong(String json, String key, long defaultVal) {
        String raw = extractJsonRawPrimitive(json, key);
        if (raw.isEmpty()) return defaultVal;
        try {
            return Long.parseLong(raw);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static boolean extractJsonBoolean(String json, String key, boolean defaultVal) {
        String raw = extractJsonRawPrimitive(json, key);
        if (raw.isEmpty()) return defaultVal;
        return "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    private static String extractJsonRawPrimitive(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return "";
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) return "";
        if (json.charAt(start) == '"') {
            return extractJsonString(json, key);
        }
        int end = start;
        while (end < json.length() && ",}\r\n\t ".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        int len = clean.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
        }
        return out;
    }

    private static byte[] buildCanonicalWalletReceipt(byte insCode, int amount, int newBalance, int tsInt) {
        return new byte[]{
                insCode,
                (byte) ((amount >> 24) & 0xFF),
                (byte) ((amount >> 16) & 0xFF),
                (byte) ((amount >> 8) & 0xFF),
                (byte) (amount & 0xFF),
                (byte) ((newBalance >> 24) & 0xFF),
                (byte) ((newBalance >> 16) & 0xFF),
                (byte) ((newBalance >> 8) & 0xFF),
                (byte) (newBalance & 0xFF),
                (byte) ((tsInt >> 24) & 0xFF),
                (byte) ((tsInt >> 16) & 0xFF),
                (byte) ((tsInt >> 8) & 0xFF),
                (byte) (tsInt & 0xFF)
        };
    }

    private static boolean verifyWalletReceiptOnServer(boolean isCredit, int amount, int balanceAfter, long ts, String sigHex) {
        if (sigHex == null || sigHex.trim().length() < 64 || virtualCard == null || virtualCard.rsaKeyPair == null) {
            return false;
        }
        try {
            byte[] sigBytes = hexToBytes(sigHex.trim());
            byte[] canonicalPayload = buildCanonicalWalletReceipt(
                    (byte) (isCredit ? 0x40 : 0x41), amount, balanceAfter, (int) ts);
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(virtualCard.rsaKeyPair.getPublic());
            verifier.update(canonicalPayload);
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    static class VirtualSmartCardEmulator {
        String activeCardId = "CT070315";
        String activeInfoUtf8 = "CT070315|Vũ Ngọc Hải|15/08/2003|CT7C|An toàn Thông tin";
        String currentPin = "123456";
        final String adminPuk = "88888888";
        int pinTriesRemaining = 3;
        boolean pinValidated = false;
        boolean adminLocked = false;

        byte[] encryptedInfo = new byte[0];
        int plainInfoLen = 0;
        byte[] avatarBuffer = new byte[8192];
        int avatarLength = 0;
        boolean avatarDirty = false;

        short trainingScore = 88;
        short attendanceCount = 12;
        byte labAccess = 1;
        short[] borrowedBooks = new short[]{0x0102, 0x0205, 0, 0, 0};
        byte borrowedCount = 2;
        int walletBalance = 500000;

        final byte[] activityLogs = new byte[70];
        byte logHead = 0;
        byte logCount = 0;

        KeyPair rsaKeyPair;
        final SecretKeySpec aesKey = new SecretKeySpec("SMART_STUDENT_ID".getBytes(StandardCharsets.UTF_8), "AES");

        VirtualSmartCardEmulator() {
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
            if (!sameCard) {
                persistAvatarIfNeeded();
            }
            this.activeCardId = cardId;
            this.activeInfoUtf8 = infoUtf8;
            this.currentPin = pin;
            this.pinTriesRemaining = 3;
            this.pinValidated = true;
            this.adminLocked = false;
            encryptAndStoreInfo(infoUtf8.getBytes(StandardCharsets.UTF_8));
            if (!sameCard || this.avatarLength == 0) {
                reloadAvatarFromDatabase();
            }
            reloadWalletBalanceFromDatabase();
            reloadStatsFromDatabase();
        }

        void reloadFromDatabase() {
            reloadAvatarFromDatabase();
            reloadWalletBalanceFromDatabase();
            reloadStatsFromDatabase();
            try {
                applyLockState(DatabaseManager.isCardLocked(activeCardId));
            } catch (Exception ignored) {
            }
        }

        void reloadStatsFromDatabase() {
            if (activeCardId == null || activeCardId.trim().isEmpty()) {
                return;
            }
            try {
                int[] stats = DatabaseManager.getCardStats(activeCardId);
                if (stats != null) {
                    this.trainingScore = (short) stats[0];
                    this.attendanceCount = (short) stats[1];
                    this.labAccess = (byte) stats[2];
                } else {
                    this.trainingScore = 85;
                    this.attendanceCount = 0;
                    this.labAccess = 1;
                }
            } catch (Exception ignored) {
            }
        }

        void reloadWalletBalanceFromDatabase() {
            if (activeCardId == null || activeCardId.trim().isEmpty()) {
                this.walletBalance = 500000;
                return;
            }
            try {
                this.walletBalance = DatabaseManager.getCardWalletBalance(activeCardId);
            } catch (Exception ignored) {
            }
        }

        void persistAvatarIfNeeded() {
            if (!avatarDirty || activeCardId == null || activeCardId.trim().isEmpty() || avatarLength <= 0) {
                return;
            }
            try {
                byte[] currentBytes = Arrays.copyOf(avatarBuffer, avatarLength);
                String b64 = Base64.getEncoder().encodeToString(currentBytes);
                boolean updated = DatabaseManager.saveCardAvatarBase64(activeCardId, b64);
                if (!updated) {
                    syncActiveCardInfoToDatabase();
                    DatabaseManager.saveCardAvatarBase64(activeCardId, b64);
                }
                avatarDirty = false;
            } catch (Exception ignored) {
            }
        }

        void reloadAvatarFromDatabase() {
            this.avatarLength = 0;
            this.avatarDirty = false;
            if (activeCardId == null || activeCardId.trim().isEmpty()) {
                return;
            }
            try {
                String b64 = DatabaseManager.getCardAvatarBase64(activeCardId);
                if (b64 != null && !b64.isEmpty()) {
                    byte[] decoded = Base64.getDecoder().decode(b64);
                    if (decoded.length > 0 && decoded.length <= avatarBuffer.length) {
                        System.arraycopy(decoded, 0, avatarBuffer, 0, decoded.length);
                        this.avatarLength = decoded.length;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        /**
         * Restores the persistent PUK lock state when a card is mounted on the virtual reader.
         */
        void applyLockState(boolean locked) {
            this.adminLocked = locked;
            this.pinValidated = !locked;
            if (locked) {
                this.pinTriesRemaining = 0;
            }
        }

        private void encryptAndStoreInfo(byte[] plain) {
            try {
                String utf8 = new String(plain, StandardCharsets.UTF_8).trim();
                if (utf8.contains("|")) {
                    this.activeInfoUtf8 = utf8;
                    String[] parts = utf8.split("\\|");
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        this.activeCardId = parts[0];
                    }
                }
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
            if (apdu == null || apdu.length < 4) {
                return swOnly(0x6F00);
            }
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
            if (cla != 0x80) {
                return swOnly(0x6E00);
            }

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
                            if (pinTriesRemaining == 0) {
                                DatabaseManager.updateCardLockState(activeCardId, true);
                            }
                            return swOnly(pinTriesRemaining == 0 ? 0x6983 : (0x63C0 | pinTriesRemaining));
                        }
                    }
                    case 0x11: {
                        if (!pinValidated) return swOnly(0x6982);
                        int oldLen = cdata[0] & 0xFF;
                        String oldPin = new String(cdata, 1, oldLen, StandardCharsets.UTF_8);
                        if (!currentPin.equals(oldPin)) {
                            pinTriesRemaining = Math.max(0, pinTriesRemaining - 1);
                            if (pinTriesRemaining == 0) {
                                DatabaseManager.updateCardLockState(activeCardId, true);
                            }
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
                            // P1=01: Admin PUK locks the card until a P1=00 unblock is sent
                            adminLocked = true;
                            pinTriesRemaining = 0;
                            pinValidated = false;
                            DatabaseManager.updateCardLockState(activeCardId, true);
                        } else {
                            // P1=00: Admin PUK unblocks and restores the default PIN
                            adminLocked = false;
                            pinTriesRemaining = 3;
                            currentPin = "123456";
                            pinValidated = true;
                            DatabaseManager.unblockCardByPuk(activeCardId);
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
                        if (!pinValidated || adminLocked) return swOnly(0x6982);
                        encryptAndStoreInfo(cdata);
                        syncActiveCardInfoToDatabase();
                        tapEventId = System.currentTimeMillis();
                        return swOnly(0x9000);
                    }
                    case 0x21: {
                        if (p1 == 0x01) {
                            return withSw(encryptedInfo, 0x9000);
                        }
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
                            avatarDirty = true;
                            if (cdata.length < 240 || offset + cdata.length >= avatarBuffer.length) {
                                persistAvatarIfNeeded();
                            }
                        }
                        return swOnly(0x9000);
                    }
                    case 0x23: {
                        int offset = (p1 << 8) | p2;
                        if (offset == 0) {
                            if (avatarDirty) {
                                persistAvatarIfNeeded();
                            }
                            if (avatarLength == 0) {
                                reloadAvatarFromDatabase();
                            }
                        }
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
                        Signature sig = Signature.getInstance("SHA1withRSA");
                        sig.initSign(rsaKeyPair.getPrivate());
                        sig.update(cdata);
                        byte[] sigBytes = sig.sign();
                        String sigHex = bytesToHex(sigBytes);

                        if (ins == 0x31 && p1 == 0x01) {
                            attendanceCount++;
                            if (trainingScore < 100) trainingScore++;
                            addLog((byte) 1, cdata);
                            DatabaseManager.recordApduTransaction(activeCardId, "APDU_ATTENDANCE", 0x0101,
                                    "Điểm danh bằng chữ ký RSA trên chip", sigHex);
                        } else if (ins == 0x31 && p1 == 0x02 && borrowedCount < 5 && cdata.length >= 2) {
                            short bookCode = (short) (((cdata[0] & 0xFF) << 8) | (cdata[1] & 0xFF));
                            borrowedBooks[borrowedCount++] = bookCode;
                            addLog((byte) 2, cdata);
                            DatabaseManager.recordApduTransaction(activeCardId, "APDU_BORROW_BOOK", bookCode & 0xFFFF,
                                    "Mượn sách thư viện trên chip", sigHex);
                        } else if (ins == 0x32 && p1 == 0x01 && borrowedCount > 0) {
                            int bookCode = cdata.length >= 2 ? (((cdata[0] & 0xFF) << 8) | (cdata[1] & 0xFF)) : 0;
                            borrowedCount--;
                            addLog((byte) 3, cdata);
                            DatabaseManager.recordApduTransaction(activeCardId, "APDU_RETURN_BOOK", bookCode,
                                    "Trả sách thư viện trên chip", sigHex);
                        } else {
                            addLog((byte) 4, cdata);
                            DatabaseManager.recordApduTransaction(activeCardId, "APDU_LAB_ACCESS", 0x0401,
                                    "Quẹt thẻ mở cửa phòng Lab ATTT", sigHex);
                        }
                        return withSw(sigBytes, 0x9000);
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
            long ts = ((long) (cdata[4] & 0xFF) << 24)
                    | ((long) (cdata[5] & 0xFF) << 16)
                    | ((long) (cdata[6] & 0xFF) << 8)
                    | (cdata[7] & 0xFF);
            if (ts <= 0) ts = System.currentTimeMillis() / 1000L;
            if (amount <= 0) return swOnly(0x6984);

            int balanceBefore = walletBalance;
            if (isCredit) {
                if ((long) walletBalance + amount > MAX_WALLET_BALANCE) return swOnly(0x6A84);
                walletBalance += amount;
            } else {
                if (walletBalance < amount) return swOnly(0x6985);
                walletBalance -= amount;
            }

            byte logCode = (byte) (isCredit ? 0x40 : 0x41);
            byte[] receiptPayload = buildCanonicalWalletReceipt(logCode, amount, walletBalance, (int) ts);
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(rsaKeyPair.getPrivate());
            sig.update(receiptPayload);
            byte[] sigBytes = sig.sign();
            String sigHex = bytesToHex(sigBytes);

            byte logType = (byte) (isCredit ? 5 : 6);
            addLog(logType, new byte[]{0x00, logCode, receiptPayload[9], receiptPayload[10], receiptPayload[11], receiptPayload[12]});
            String txRef = DatabaseManager.generateBankTxRef(ts, amount);
            DatabaseManager.recordWalletTransaction(
                    activeCardId,
                    isCredit ? "CREDIT" : "DEBIT",
                    amount,
                    walletBalance,
                    isCredit ? "Nạp tiền ví điện tử trên chip" : "Thanh toán dịch vụ khuôn viên",
                    ts,
                    sigHex,
                    true,
                    txRef,
                    balanceBefore,
                    isCredit ? "Vietcombank • *8829 (Napas 247)" : "POS Dịch vụ Smart Campus"
            );
            return withSw(sigBytes, 0x9000);
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
