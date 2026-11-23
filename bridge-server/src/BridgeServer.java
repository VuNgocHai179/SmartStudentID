import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final int PORT = 8080;
    private static final File SCRIPTS_DIR = new File("javacard-applet/scripts");

    private static final VirtualSmartCardEmulator virtualCard = new VirtualSmartCardEmulator();
    private static CardChannel pcscChannel = null;
    private static String activeReaderName = "Virtual-JCIDE-Fallback-Engine";

    // Increments whenever a card file (.scr) is loaded or tapped from PC / JCIDE
    private static volatile long tapEventId = 0L;
    private static volatile String lastObservedJcideInfo = "";

    public static void main(String[] args) throws Exception {
        // Initialize SQLite Relational Database (smartcard.db)
        DatabaseManager.initDatabase();

        tryConnectPcscReader();
        startJcideMonitorThread();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);

        // 0. Web Console at http://localhost:8080 for loading .scr files & inspecting SQLite DB
        server.createContext("/", exchange -> {
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
                return;
            }
            byte[] html = buildPcLoaderHtml().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        });

        // 1. Endpoint for checking Bridge & Card Reader connection status + tapEventId for phone auto-popup
        server.createContext("/api/status", exchange -> {
            String json = String.format(
                    "{\"status\":\"ONLINE\",\"apiVersion\":2,\"dbEngine\":\"SQLite (smartcard.db)\",\"reader\":\"%s\","
                            + "\"activeStudentId\":\"%s\",\"activeStudentInfo\":\"%s\",\"pinTries\":%d,\"tapEventId\":%d}",
                    escapeJson(activeReaderName),
                    escapeJson(virtualCard.activeCardId),
                    escapeJson(virtualCard.activeInfoUtf8),
                    virtualCard.pinTriesRemaining,
                    tapEventId
            );
            sendJson(exchange, 200, json);
        });

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
                String modHex = extractJsonString(body, "publicKeyModulusHex");
                String expHex = extractJsonString(body, "publicKeyExponentHex");
                String cipherHex = extractJsonString(body, "rawAesCiphertextHex");

                boolean ok = DatabaseManager.upsertCard(studentId, fullName, dob, className,
                        department, defaultPin, cardLocked, trainingScore, attendanceCount,
                        modHex, expHex, cipherHex);
                sendJson(exchange, 200, "{\"saved\":" + ok + ",\"db\":\"smartcard.db\"}");
            } else if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String sid = "";
                if (query != null && query.contains("studentId=")) {
                    for (String part : query.split("&")) {
                        if (part.startsWith("studentId=")) {
                            sid = part.substring("studentId=".length());
                            break;
                        }
                    }
                }
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

        // 5b. NOTE: lock state is persisted through POST /api/cards (full card record) and by the
        // INS 0x12 handler itself. No separate /api/cards/lock route is exposed.

        // 6. Endpoint for Attendance History in SQLite (`attendance_logs` table)
        server.createContext("/api/attendance", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String studentIdFilter = null;
                if (query != null && query.startsWith("studentId=")) {
                    studentIdFilter = query.substring("studentId=".length());
                }
                sendJson(exchange, 200, DatabaseManager.getAttendanceJsonByStudent(studentIdFilter));
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
                                String sid = parts.length > 0 ? parts[0] : "CT070318";
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

    private static String buildPcLoaderHtml() {
        return "<!DOCTYPE html><html lang='vi'><head><meta charset='UTF-8'>"
                + "<title>PC Bridge & Quản Trị CSDL SQLite (smartcard.db)</title>"
                + "<style>"
                + "body{font-family:'Segoe UI',system-ui,sans-serif;background:#f8fafc;color:#0f172a;margin:0;padding:32px;}"
                + ".wrap{max-width:960px;margin:0 auto;background:#fff;border-radius:20px;padding:28px;box-shadow:0 8px 30px rgba(15,23,42,0.07);border:1px solid #e2e8f0;}"
                + "h1{margin:0 0 8px;font-size:22px;color:#0f172a;} h2{margin:28px 0 12px;font-size:18px;color:#1e293b;} p{margin:0 0 20px;color:#64748b;font-size:14px;}"
                + ".card-btn{display:flex;align-items:center;justify-content:space-between;width:100%;padding:16px 20px;margin-bottom:12px;border-radius:14px;border:1px solid #cbd5e1;background:#f8fafc;cursor:pointer;text-align:left;transition:0.15s;}"
                + ".card-btn:hover{background:#eff6ff;border-color:#2563eb;}"
                + ".name{font-weight:700;font-size:16px;color:#0f172a;} .sub{font-size:13px;color:#475569;margin-top:3px;}"
                + ".badge{background:#2563eb;color:#fff;padding:8px 14px;border-radius:10px;font-weight:700;font-size:13px;}"
                + ".upload-box{margin-top:16px;padding:18px;border:2px dashed #94a3b8;border-radius:14px;background:#f8fafc;text-align:center;}"
                + "pre{background:#0f172a;color:#e2e8f0;padding:14px;border-radius:12px;font-size:12px;overflow-x:auto;margin-top:18px;min-height:80px;}"
                + "table{width:100%;border-collapse:collapse;font-size:13px;margin-top:10px;}"
                + "th,td{border:1px solid #e2e8f0;padding:10px 12px;text-align:left;}"
                + "th{background:#f1f5f9;font-weight:700;color:#334155;}"
                + ".stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:14px;}"
                + ".stat-card{background:#f8fafc;border:1px solid #e2e8f0;padding:14px;border-radius:12px;}"
                + ".stat-val{font-size:20px;font-weight:800;color:#2563eb;}"
                + "</style></head><body><div class='wrap'>"
                + "<h1>💳 Bộ Nạp Thẻ JCIDE (.scr) & Hệ Quản Trị CSDL SQLite (smartcard.db)</h1>"
                + "<p>Hệ thống quản lý dữ liệu tập trung bằng <strong>SQLite Relational Database (3NF)</strong> tại <code>bridge-server/data/smartcard.db</code>.</p>"
                + "<h2>⚡ Danh Sách Thẻ Trong CSDL (Chạm vào thẻ bất kỳ để nạp vào đầu đọc ảo/điện thoại):</h2>"
                + "<div id='cardsListContainer'>Đang tải danh sách thẻ từ SQLite...</div>"
                + "<div class='upload-box'><strong>Hoặc chọn bất kỳ file kịch bản <code>.scr</code> nào từ máy tính của bạn:</strong><br><br>"
                + "<input type='file' id='scrFile' accept='.scr,.txt' onchange='uploadCustomScr(this)'/></div>"
                + "<pre id='logBox'>Sẵn sàng nạp thẻ...</pre>"
                + "<h2>🗄️ Trạng Thái CSDL Quan Hệ SQLite (smartcard.db)</h2>"
                + "<div class='stats'>"
                + "<div class='stat-card'><div>Tổng thẻ (Bảng cards)</div><div class='stat-val' id='stCards'>-</div></div>"
                + "<div class='stat-card'><div>Lượt điểm danh (attendance_logs)</div><div class='stat-val' id='stAtt'>-</div></div>"
                + "<div class='stat-card'><div>Sách đang mượn (book_loans)</div><div class='stat-val' id='stBooks'>-</div></div>"
                + "<div class='stat-card'><div>Nhật ký bảo mật (card_audit_logs)</div><div class='stat-val' id='stAudit'>-</div></div>"
                + "</div>"
                + "<table><thead><tr><th>MSSV (PK)</th><th>Họ và Tên</th><th>Ngày sinh</th><th>Lớp</th><th>Khoa</th><th>Điểm RL</th><th>Điểm danh</th><th>Trạng thái</th><th>Thao tác</th></tr></thead>"
                + "<tbody id='cardsTableBody'></tbody></table>"
                + "</div><script>"
                + "async function refreshDb(){"
                + "  const r=await fetch('/api/db-summary');"
                + "  const d=await r.json();"
                + "  document.getElementById('stCards').textContent=d.totalCards+' thẻ';"
                + "  document.getElementById('stAtt').textContent=d.totalAttendance+' lượt';"
                + "  document.getElementById('stBooks').textContent=d.borrowedBooks+' cuốn';"
                + "  document.getElementById('stAudit').textContent=d.totalAuditLogs+' bản ghi';"
                + "  const cont=document.getElementById('cardsListContainer'); cont.innerHTML='';"
                + "  (d.cards||[]).forEach((c,idx)=>{"
                + "    const isCur=(d.activeStudentId===c.studentId);"
                + "    const div=document.createElement('div');"
                + "    div.className='card-btn'+(isCur?' active-card':'');"
                + "    if(isCur) div.style='border-color:#10b981;background:#ecfdf5;';"
                + "    div.onclick=()=>activateCard(c.studentId,`${c.studentId}|${c.fullName}|${c.dob}|${c.className}|${c.department}`,c.defaultPin||'123456');"
                + "    div.innerHTML=`<div><div class='name'>${idx+1}. ${c.fullName} • ${c.studentId} ${isCur?'<span style=\"color:#059669;font-size:12px;margin-left:8px;\">● ĐANG GẮN TRÊN ĐẦU ĐỌC</span>':''}</div><div class='sub'>Lớp: ${c.className} • Ngành: ${c.department} • Ngày sinh: ${c.dob}</div></div><button class='badge' style='cursor:pointer;border:none;background:${isCur?'#059669':'#2563eb'}'>${isCur?'Đang hoạt động':'Nạp thẻ này ➔'}</button>`;"
                + "    cont.appendChild(div);"
                + "  });"
                + "  const tb=document.getElementById('cardsTableBody'); tb.innerHTML='';"
                + "  (d.cards||[]).forEach(c=>{"
                + "    const tr=document.createElement('tr');"
                + "    tr.innerHTML=`<td><b>${c.studentId}</b></td><td>${c.fullName}</td><td>${c.dob}</td><td>${c.className}</td><td>${c.department}</td><td>${c.trainingScore}</td><td>${c.attendanceCount}</td><td>${c.cardLocked?'🔒 Khóa PIN':'✅ Sẵn sàng'}</td><td><button class='badge' style='cursor:pointer;border:none;padding:5px 10px;font-size:11px;' onclick=\"activateCard('${c.studentId}','${c.studentId}|${c.fullName}|${c.dob}|${c.className}|${c.department}','${c.defaultPin||'123456'}')\">Nạp</button> <button class='badge' style='cursor:pointer;border:none;background:#dc2626;padding:5px 10px;font-size:11px;margin-left:4px;' onclick=\"deleteCard('${c.studentId}')\">Xóa</button></td>`;"
                + "    tb.appendChild(tr);"
                + "  });"
                + "}"
                + "async function activateCard(id,info,pin){"
                + "  document.getElementById('logBox').textContent='Đang kích hoạt nạp thẻ '+id+' vào đầu đọc ảo/điện thoại...';"
                + "  const r=await fetch('/api/activate-card',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({studentId:id,studentInfo:info,pin:pin})});"
                + "  const d=await r.json();"
                + "  document.getElementById('logBox').textContent='✓ ĐÃ NẠP THẺ VÀO ĐẦU ĐỌC: '+id+'\\nĐiện thoại có thể quét thẻ và đăng nhập vào sinh viên này ngay lập tức!';"
                + "  refreshDb();"
                + "}"
                + "async function deleteCard(id){"
                + "  if(!confirm('Bạn có chắc chắn muốn xóa thẻ '+id+' khỏi CSDL SQLite?'))return;"
                + "  const r=await fetch('/api/cards?studentId='+encodeURIComponent(id),{method:'DELETE'});"
                + "  const d=await r.json();"
                + "  alert('Đã xóa thẻ '+id+' khỏi CSDL SQLite!');"
                + "  refreshDb();"
                + "}"
                + "function uploadCustomScr(input){"
                + "  if(!input.files||!input.files[0])return;"
                + "  const reader=new FileReader();"
                + "  reader.onload=async(e)=>{"
                + "    const text=e.target.result;"
                + "    const r=await fetch('/api/load-scr',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({scriptContent:text})});"
                + "    const d=await r.json();"
                + "    document.getElementById('logBox').textContent='✓ ĐÃ NẠP FILE .SCR & CẬP NHẬT SQLITE:\\nSinh viên: '+d.activeStudentInfo+'\\n\\n'+d.logs.join('\\n');"
                + "    refreshDb();"
                + "  };"
                + "  reader.readAsText(input.files[0],'UTF-8');"
                + "}"
                + "refreshDb(); setInterval(refreshDb, 3000);"
                + "</script></body></html>";
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

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    static class VirtualSmartCardEmulator {
        String activeCardId = "CT070318";
        String activeInfoUtf8 = "CT070318|Vũ Ngọc Hải|15/08/2003|CT7C|Công nghệ thông tin";
        String currentPin = "123456";
        final String adminPuk = "88888888";
        int pinTriesRemaining = 3;
        boolean pinValidated = false;
        boolean adminLocked = false;

        byte[] encryptedInfo = new byte[0];
        int plainInfoLen = 0;
        byte[] avatarBuffer = new byte[8192];
        int avatarLength = 0;

        short trainingScore = 88;
        short attendanceCount = 12;
        byte labAccess = 1;
        short[] borrowedBooks = new short[]{0x0102, 0x0205, 0, 0, 0};
        byte borrowedCount = 2;

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
                loadSnapshot("CT070318", "CT070318|Vũ Ngọc Hải|15/08/2003|CT7C|Công nghệ thông tin", "123456");
            } catch (Exception ignored) {
            }
        }

        void loadSnapshot(String cardId, String infoUtf8, String pin) {
            boolean sameCard = cardId != null && cardId.equalsIgnoreCase(this.activeCardId);
            this.activeCardId = cardId;
            this.activeInfoUtf8 = infoUtf8;
            this.currentPin = pin;
            this.pinTriesRemaining = 3;
            this.pinValidated = true;
            this.adminLocked = false;
            if (!sameCard) {
                this.avatarLength = 0;
            }
            encryptAndStoreInfo(infoUtf8.getBytes(StandardCharsets.UTF_8));
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
                    default:
                        return swOnly(0x6D00);
                }
            } catch (Exception e) {
                return swOnly(0x6F00);
            }
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
