import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

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
    private static volatile boolean dbInitialized = false;

    public static void main(String[] args) throws Exception {
        // Initialize SQLite Relational Database (smartcard.db)
        DatabaseManager.initDatabase();
        dbInitialized = true;
        virtualCard.reloadAvatarFromDatabase();
        virtualCard.reloadWalletBalanceFromDatabase();
        syncVirtualCardPublicKeyToDb(false);

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
                    "{\"status\":\"ONLINE\",\"apiVersion\":3,\"dbEngine\":\"SQLite (smartcard.db)\",\"reader\":\"%s\","
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
            if (!"/api/attendance".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String studentIdFilter = getQueryParam(exchange.getRequestURI().getQuery(), "studentId");
                sendJson(exchange, 200, DatabaseManager.getAttendanceJsonByStudent(studentIdFilter.isEmpty() ? null : studentIdFilter));
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 400, "{\"ok\":false,\"errorCode\":\"USE_VERIFIED_CHECKIN\",\"message\":\"Vui lòng sử dụng /api/attendance/checkin gắn với phiên điểm danh đang mở.\"}");
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        });

        // 6a. Endpoint: List semester courses (`courses` table)
        server.createContext("/api/courses", exchange -> {
            if (!"/api/courses".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 200, DatabaseManager.getAllCoursesJson());
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        });

        // 6b. Endpoint: Open or list Lecturer Attendance Sessions
        server.createContext("/api/attendance/sessions", exchange -> {
            if (!"/api/attendance/sessions".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            long nowSec = System.currentTimeMillis() / 1000L;
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                List<DatabaseManager.AttendanceSession> sessions = DatabaseManager.getAllAttendanceSessions();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"serverNowSec\":").append(nowSec).append(",\"sessions\":[");
                for (int i = 0; i < sessions.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(formatSessionJson(sessions.get(i), nowSec));
                }
                sb.append("]}");
                sendJson(exchange, 200, sb.toString());
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                String courseCode = extractJsonString(body, "courseCode");
                int durationMinutes = extractJsonInt(body, "durationMinutes", 15);
                int rotationSeconds = extractJsonInt(body, "rotationSeconds", 20);
                if (courseCode.isEmpty()) {
                    sendJson(exchange, 400, "{\"opened\":false,\"message\":\"Vui lòng chọn mã học phần.\"}");
                    return;
                }
                DatabaseManager.AttendanceSession created = DatabaseManager.openAttendanceSession(
                        courseCode, durationMinutes, rotationSeconds);
                if (created == null) {
                    sendJson(exchange, 404, "{\"opened\":false,\"message\":\"Không tìm thấy học phần " + escapeJson(courseCode) + ".\"}");
                    return;
                }
                nowSec = System.currentTimeMillis() / 1000L;
                System.out.println("[ATTENDANCE] Opened session #" + created.id + " for " + created.courseCode
                        + " (" + durationMinutes + "m, QR every " + rotationSeconds + "s)");
                sendJson(exchange, 200, "{\"opened\":true,\"serverNowSec\":" + nowSec
                        + ",\"session\":" + formatSessionJson(created, nowSec) + "}");
            } else {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        });

        // 6c. Endpoint: Close an Attendance Session
        server.createContext("/api/attendance/sessions/close", exchange -> {
            if (!"/api/attendance/sessions/close".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = readBody(exchange);
            int sessionId = extractJsonInt(body, "sessionId", 0);
            boolean closed = DatabaseManager.closeAttendanceSession(sessionId);
            System.out.println("[ATTENDANCE] Closed session #" + sessionId + " -> " + closed);
            sendJson(exchange, 200, "{\"closed\":" + closed + ",\"sessionId\":" + sessionId + "}");
        });

        // 6d. Endpoint: Live Session Roster for Lecturer Dashboard
        server.createContext("/api/attendance/sessions/roster", exchange -> {
            if (!"/api/attendance/sessions/roster".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String sidParam = getQueryParam(exchange.getRequestURI().getQuery(), "sessionId");
            int sessionId = parseIntSafe(sidParam, 0);
            sendJson(exchange, 200, DatabaseManager.getSessionRosterJson(sessionId));
        });

        // 6e. Endpoint: Render rotating HMAC-SHA256 QR as crisp inline SVG (offline, zero CDN)
        server.createContext("/api/attendance/session-qr", exchange -> {
            if (!"/api/attendance/session-qr".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String sidParam = getQueryParam(exchange.getRequestURI().getQuery(), "sessionId");
            int sessionId = parseIntSafe(sidParam, 0);
            DatabaseManager.AttendanceSession session = DatabaseManager.getAttendanceSessionById(sessionId);
            long nowSec = System.currentTimeMillis() / 1000L;
            if (session == null || !"OPEN".equalsIgnoreCase(session.status) || nowSec >= session.expiresAt) {
                sendJson(exchange, 404, "{\"error\":\"SESSION_CLOSED\",\"message\":\"Phiên điểm danh không tồn tại hoặc đã đóng.\"}");
                return;
            }
            int rot = Math.max(10, session.rotationSeconds);
            long slot = nowSec / rot;
            String qrContent = buildSessionQrPayload(session, slot);
            String svg = renderQrSvg(qrContent, 280);
            byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 6f. Endpoint: Student active open sessions filtered by `enrollments`
        server.createContext("/api/attendance/active-sessions", exchange -> {
            if (!"/api/attendance/active-sessions".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String studentId = getQueryParam(exchange.getRequestURI().getQuery(), "studentId");
            if (studentId.isEmpty()) {
                studentId = virtualCard.activeCardId;
            }
            long nowSec = System.currentTimeMillis() / 1000L;
            List<DatabaseManager.AttendanceSession> active = DatabaseManager.getActiveSessionsForStudent(studentId);
            String enrolledCoursesJson = DatabaseManager.getEnrolledCoursesJson(studentId);

            StringBuilder sb = new StringBuilder();
            sb.append("{")
                    .append("\"studentId\":\"").append(escapeJson(studentId)).append("\",")
                    .append("\"serverNowSec\":").append(nowSec).append(",")
                    .append("\"enrolledCourses\":").append(enrolledCoursesJson).append(",")
                    .append("\"activeSessions\":[");
            for (int i = 0; i < active.size(); i++) {
                if (i > 0) sb.append(",");
                DatabaseManager.AttendanceSession s = active.get(i);
                DatabaseManager.SessionCheckInInfo info = DatabaseManager.getStudentSessionCheckInInfo(s.id, studentId);
                boolean checkedIn = info != null;
                long remSession = Math.max(0L, s.expiresAt - nowSec);
                sb.append("{")
                        .append("\"sessionId\":").append(s.id).append(",")
                        .append("\"courseCode\":\"").append(escapeJson(s.courseCode)).append("\",")
                        .append("\"courseName\":\"").append(escapeJson(s.courseName)).append("\",")
                        .append("\"room\":\"").append(escapeJson(s.room)).append("\",")
                        .append("\"lecturerName\":\"").append(escapeJson(s.lecturerName)).append("\",")
                        .append("\"openedAt\":").append(s.openedAt).append(",")
                        .append("\"expiresAt\":").append(s.expiresAt).append(",")
                        .append("\"remainingSessionSec\":").append(remSession).append(",")
                        .append("\"rotationSeconds\":").append(s.rotationSeconds).append(",")
                        .append("\"alreadyCheckedIn\":").append(checkedIn).append(",")
                        .append("\"checkedInAt\":").append(checkedIn ? info.timestampSeconds : 0L).append(",")
                        .append("\"checkinMethod\":\"").append(escapeJson(checkedIn ? info.checkinMethod : "")).append("\"")
                        .append("}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        });

        // 6g. Endpoint: 4-Layer Pre-check before sending INS 0x31 APDU to smart card
        server.createContext("/api/attendance/precheck", exchange -> {
            if (!"/api/attendance/precheck".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = readBody(exchange);
            String studentId = extractJsonString(body, "studentId");
            int sessionId = extractJsonInt(body, "sessionId", 0);
            String method = extractJsonString(body, "method");
            String qrPayload = extractJsonString(body, "qrPayload");
            String otpCode = extractJsonString(body, "otpCode");
            if (studentId.isEmpty()) {
                studentId = virtualCard.activeCardId;
            }

            long nowSec = System.currentTimeMillis() / 1000L;
            AttendancePrecheckResult v = validateAttendancePreconditions(
                    studentId, sessionId, method, qrPayload, otpCode, nowSec);
            if (!v.ok) {
                String errJson = String.format(
                        "{\"ok\":false,\"errorCode\":\"%s\",\"message\":\"%s\",\"serverNowSec\":%d}",
                        escapeJson(v.errorCode), escapeJson(v.message), nowSec);
                sendJson(exchange, v.httpStatus, errJson);
                return;
            }
            String okJson = String.format(
                    "{\"ok\":true,\"sessionId\":%d,\"courseCode\":\"%s\",\"courseName\":\"%s\","
                            + "\"timeSlot\":%d,\"serverNowSec\":%d}",
                    v.session.id, escapeJson(v.session.courseCode), escapeJson(v.session.courseName),
                    v.resolvedTimeSlot, nowSec);
            sendJson(exchange, 200, okJson);
        });

        // 6h. Endpoint: 5-Layer Verified Attendance Check-In (Session + Enrollment + Anti-Replay + Unique + RSA-1024)
        server.createContext("/api/attendance/checkin", exchange -> {
            if (!"/api/attendance/checkin".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String body = readBody(exchange);
            String studentId = extractJsonString(body, "studentId");
            int sessionId = extractJsonInt(body, "sessionId", 0);
            String method = extractJsonString(body, "method");
            String qrPayload = extractJsonString(body, "qrPayload");
            String otpCode = extractJsonString(body, "otpCode");
            long clientTimeSlot = extractJsonLong(body, "timeSlot", 0L);
            long timestampSeconds = extractJsonLong(body, "timestampSeconds", 0L);
            String sigHex = extractJsonString(body, "rsaSignatureHex");

            long nowSec = System.currentTimeMillis() / 1000L;
            if (studentId.isEmpty()) {
                sendJson(exchange, 400, "{\"ok\":false,\"errorCode\":\"MISSING_STUDENT_ID\",\"message\":\"Thiếu mã sinh viên.\"}");
                return;
            }

            AttendancePrecheckResult v = validateAttendancePreconditions(
                    studentId, sessionId, method, qrPayload, otpCode, nowSec);
            if (!v.ok) {
                String errJson = String.format(
                        "{\"ok\":false,\"errorCode\":\"%s\",\"message\":\"%s\",\"serverNowSec\":%d}",
                        escapeJson(v.errorCode), escapeJson(v.message), nowSec);
                sendJson(exchange, v.httpStatus, errJson);
                return;
            }

            if (timestampSeconds <= 0 || Math.abs(nowSec - timestampSeconds) > 300) {
                sendJson(exchange, 400, "{\"ok\":false,\"errorCode\":\"TIMESTAMP_EXPIRED\",\"message\":\"Thời gian giao dịch không hợp lệ hoặc đã quá hạn.\"}");
                return;
            }

            String normMethod = method != null ? method.trim().toUpperCase(Locale.ROOT) : "";
            if ("QR_SCAN".equals(normMethod) && clientTimeSlot != v.resolvedTimeSlot) {
                sendJson(exchange, 400, "{\"ok\":false,\"errorCode\":\"QR_SLOT_MISMATCH\",\"message\":\"Khung thời gian QR không khớp với chữ ký.\"}");
                return;
            }

            byte[] canonicalPayload = buildCanonicalAttendancePayload(
                    v.session.id, (int) timestampSeconds, (int) v.resolvedTimeSlot);
            boolean rsaVerified = verifyAttendanceSignatureOnServer(studentId, canonicalPayload, sigHex);
            if (!rsaVerified) {
                System.out.println("[ATTENDANCE REJECTED] Invalid RSA-1024 signature for " + studentId + " session #" + v.session.id);
                sendJson(exchange, 403, "{\"ok\":false,\"errorCode\":\"INVALID_RSA_SIGNATURE\",\"message\":\"Chữ ký RSA-1024 từ thẻ sinh viên không hợp lệ.\"}");
                return;
            }

            DatabaseManager.CheckInCommitResult commit = DatabaseManager.insertVerifiedSessionAttendance(
                    v.session.id, studentId, v.session.courseCode, v.session.courseName,
                    timestampSeconds, sigHex.replaceAll("\\s+", ""), normMethod);

            if (commit.duplicate) {
                sendJson(exchange, 409, "{\"ok\":false,\"errorCode\":\"ALREADY_CHECKED_IN\",\"message\":\"Bạn đã điểm danh buổi học này rồi.\"}");
                return;
            }
            if (!commit.saved) {
                sendJson(exchange, 500, "{\"ok\":false,\"errorCode\":\"DB_ERROR\",\"message\":\"" + escapeJson(commit.errorMessage) + "\"}");
                return;
            }

            System.out.println("[ATTENDANCE VERIFIED] " + studentId + " checked in " + v.session.courseCode
                    + " (session #" + v.session.id + ", method=" + normMethod + ", RSA-1024=OK)");
            String okResp = String.format(
                    "{\"ok\":true,\"saved\":true,\"verifiedByServer\":true,\"sessionId\":%d,"
                            + "\"courseCode\":\"%s\",\"courseName\":\"%s\",\"method\":\"%s\","
                            + "\"attendanceCount\":%d,\"trainingScore\":%d,"
                            + "\"message\":\"Điểm danh thành công!\"}",
                    v.session.id,
                    escapeJson(v.session.courseCode),
                    escapeJson(v.session.courseName),
                    escapeJson(normMethod),
                    commit.attendanceCount,
                    commit.trainingScore);
            sendJson(exchange, 200, okResp);
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
                String query = exchange.getRequestURI().getQuery();
                String studentIdFilter = null;
                if (query != null && query.startsWith("studentId=")) {
                    studentIdFilter = query.substring("studentId=".length());
                }
                sendJson(exchange, 200, DatabaseManager.getWalletTransactionsJsonByStudent(studentIdFilter));
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
            syncVirtualCardPublicKeyToDb(false);
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

    private static String buildPcLoaderHtml() {
        return "<!DOCTYPE html><html lang='vi'><head><meta charset='UTF-8'>"
                + "<title>PC Bridge & Giảng Viên Điểm Danh QR (smartcard.db)</title>"
                + "<style>"
                + "body{font-family:'Segoe UI',system-ui,sans-serif;background:#f8fafc;color:#0f172a;margin:0;padding:28px;}"
                + ".wrap{max-width:1100px;margin:0 auto;background:#fff;border-radius:20px;padding:28px;box-shadow:0 8px 30px rgba(15,23,42,0.07);border:1px solid #e2e8f0;}"
                + "h1{margin:0 0 8px;font-size:22px;color:#0f172a;} h2{margin:28px 0 12px;font-size:18px;color:#1e293b;} p{margin:0 0 16px;color:#64748b;font-size:14px;}"
                + ".att-panel{background:#f8fafc;border:1px solid #cbd5e1;border-radius:16px;padding:20px;margin:16px 0 24px;}"
                + ".att-toolbar{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin-bottom:16px;}"
                + ".att-toolbar select,.att-toolbar button{padding:10px 14px;border-radius:10px;font-size:13px;font-family:inherit;}"
                + ".att-toolbar select{border:1px solid #cbd5e1;background:#fff;color:#0f172a;font-weight:600;}"
                + ".btn-primary{background:#2563eb;color:#fff;border:none;font-weight:700;cursor:pointer;}"
                + ".btn-primary:hover{background:#1d4ed8;}"
                + ".btn-danger{background:#dc2626;color:#fff;border:none;font-weight:700;cursor:pointer;}"
                + ".btn-soft{background:#eff6ff;color:#1d4ed8;border:1px solid #bfdbfe;font-weight:700;cursor:pointer;}"
                + ".proj-grid{display:grid;grid-template-columns:320px 1fr;gap:20px;align-items:start;}"
                + "@media(max-width:860px){.proj-grid{grid-template-columns:1fr;}}"
                + ".qr-card{background:#fff;border:1px solid #cbd5e1;border-radius:16px;padding:18px;text-align:center;}"
                + ".qr-img{width:250px;height:250px;display:block;margin:10px auto;border:1px solid #e2e8f0;border-radius:12px;background:#fff;}"
                + ".otp-box{background:#eff6ff;border:1px solid #bfdbfe;border-radius:12px;padding:10px 14px;margin-top:12px;}"
                + ".otp-val{font-size:28px;font-weight:800;letter-spacing:5px;color:#1d4ed8;font-family:Consolas,monospace;}"
                + ".bar-bg{height:8px;background:#e2e8f0;border-radius:99px;overflow:hidden;margin:10px 0 6px;}"
                + ".bar-fill{height:100%;background:#2563eb;width:100%;transition:width 0.25s linear;}"
                + ".roster-card{background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:16px;}"
                + ".pill-ok{background:#ecfdf5;color:#047857;padding:3px 9px;border-radius:99px;font-weight:700;font-size:11px;display:inline-block;}"
                + ".pill-wait{background:#f1f5f9;color:#64748b;padding:3px 9px;border-radius:99px;font-weight:700;font-size:11px;display:inline-block;}"
                + ".fs-modal{display:none;position:fixed;inset:0;background:#ffffff;z-index:9999;padding:32px;flex-direction:column;align-items:center;justify-content:center;text-align:center;}"
                + ".fs-qr{width:min(58vh,420px);height:min(58vh,420px);border:2px solid #cbd5e1;border-radius:20px;padding:12px;background:#fff;}"
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
                + "<h2>🎓 Điểm Danh Lớp Học & Chiếu Mã QR Động (Giảng viên)</h2>"
                + "<div class='att-panel'>"
                + "  <div class='att-toolbar'>"
                + "    <select id='attCourseSelect' style='flex:1;min-width:260px;'></select>"
                + "    <select id='attDurationSelect'><option value='5'>5 phút</option><option value='10'>10 phút</option><option value='15' selected>15 phút</option><option value='30'>30 phút</option></select>"
                + "    <select id='attRotationSelect'><option value='15'>Đổi QR mỗi 15s</option><option value='20' selected>Đổi QR mỗi 20s</option><option value='30'>Đổi QR mỗi 30s</option></select>"
                + "    <button class='btn-primary' onclick='openAttSession()'>Mở phiên & Chiếu QR</button>"
                + "    <button class='btn-danger' id='btnCloseSession' style='display:none;' onclick='closeAttSession()'>Đóng phiên</button>"
                + "    <button class='btn-soft' id='btnFullscreenProj' style='display:none;' onclick='openFullscreenProjector()'>Phóng to máy chiếu</button>"
                + "  </div>"
                + "  <div id='noSessionBanner' style='padding:18px;background:#fff;border:1px dashed #94a3b8;border-radius:12px;color:#475569;font-size:13px;'>Chưa có phiên điểm danh nào đang mở. Chọn môn học và nhấn <strong>Mở phiên & Chiếu QR</strong> để sinh viên toàn lớp quét đồng thời.</div>"
                + "  <div id='projectorBox' class='proj-grid' style='display:none;'>"
                + "    <div class='qr-card'>"
                + "      <div id='projCourseTitle' style='font-weight:800;font-size:15px;color:#0f172a;'></div>"
                + "      <div id='projCourseMeta' style='font-size:12px;color:#64748b;margin-top:3px;'></div>"
                + "      <div id='projSessionRemaining' style='display:inline-block;margin-top:8px;padding:4px 10px;border-radius:99px;background:#ecfdf5;color:#047857;font-size:12px;font-weight:700;'></div>"
                + "      <img id='projectorQrImg' class='qr-img' alt='Mã QR điểm danh lớp học'/>"
                + "      <div class='bar-bg'><div id='projQrBar' class='bar-fill'></div></div>"
                + "      <div id='projQrCountdown' style='font-size:12px;font-weight:700;color:#2563eb;'></div>"
                + "      <div class='otp-box'><div style='font-size:11px;color:#475569;font-weight:600;'>MÃ DỰ PHÒNG 6 SỐ (NẾU CAMERA MỜ)</div><div id='projOtpCode' class='otp-val'>------</div></div>"
                + "    </div>"
                + "    <div class='roster-card'>"
                + "      <div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;'>"
                + "        <strong id='projCountText' style='font-size:15px;color:#0f172a;'>Đã điểm danh: 0 / 0 sinh viên</strong>"
                + "        <button class='btn-soft' style='padding:6px 12px;border-radius:8px;font-size:12px;' onclick='refreshSessions()'>Làm mới</button>"
                + "      </div>"
                + "      <table><thead><tr><th>MSSV</th><th>Họ tên</th><th>Lớp</th><th>Trạng thái</th><th>Phương thức</th><th>Thời gian</th><th>RSA-1024</th></tr></thead>"
                + "      <tbody id='projRosterBody'></tbody></table>"
                + "    </div>"
                + "  </div>"
                + "</div>"
                + "<div id='fsProjectorModal' class='fs-modal'>"
                + "  <div id='fsCourseTitle' style='font-size:28px;font-weight:800;color:#0f172a;'></div>"
                + "  <div id='fsCourseMeta' style='font-size:16px;color:#475569;margin-top:6px;'></div>"
                + "  <img id='fsQrImg' class='fs-qr' style='margin-top:18px;' alt='QR Máy chiếu'/>"
                + "  <div style='width:340px;margin-top:12px;'><div class='bar-bg'><div id='fsQrBar' class='bar-fill'></div></div><div id='fsQrCountdown' style='font-size:15px;font-weight:700;color:#2563eb;'></div></div>"
                + "  <div class='otp-box' style='min-width:280px;margin-top:14px;'><div style='font-size:13px;color:#475569;font-weight:700;'>MÃ DỰ PHÒNG 6 SỐ</div><div id='fsOtpCode' class='otp-val' style='font-size:38px;'>------</div></div>"
                + "  <div id='fsCountText' style='font-size:20px;font-weight:800;color:#047857;margin-top:14px;'></div>"
                + "  <button class='btn-soft' style='margin-top:18px;padding:10px 24px;border-radius:10px;font-size:14px;' onclick='closeFullscreenProjector()'>Thu nhỏ</button>"
                + "</div>"
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
                + "<table><thead><tr><th>MSSV (PK)</th><th>Họ và Tên</th><th>Ngày sinh</th><th>Lớp</th><th>Khoa</th><th>Số dư Ví</th><th>Điểm RL</th><th>Điểm danh</th><th>Trạng thái</th><th>Thao tác</th></tr></thead>"
                + "<tbody id='cardsTableBody'></tbody></table>"
                + "</div><script>"
                + "let activeSession=null; let lastRenderedSlot=-1; let lastRenderedSid=-1; let serverClockOffsetSec=0;"
                + "async function loadCourses(){"
                + "  try{"
                + "    const r=await fetch('/api/courses'); const list=await r.json();"
                + "    const sel=document.getElementById('attCourseSelect'); sel.innerHTML='';"
                + "    (list||[]).forEach(c=>{"
                + "      const o=document.createElement('option'); o.value=c.courseCode;"
                + "      o.textContent=`${c.courseCode} - ${c.courseName} (${c.room} • ${c.enrolledCount} SV)`;"
                + "      sel.appendChild(o);"
                + "    });"
                + "  }catch(e){}"
                + "}"
                + "async function openAttSession(){"
                + "  const courseCode=document.getElementById('attCourseSelect').value;"
                + "  const durationMinutes=parseInt(document.getElementById('attDurationSelect').value,10)||15;"
                + "  const rotationSeconds=parseInt(document.getElementById('attRotationSelect').value,10)||20;"
                + "  const r=await fetch('/api/attendance/sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({courseCode,durationMinutes,rotationSeconds})});"
                + "  const d=await r.json();"
                + "  if(!d.opened){alert(d.message||'Không mở được phiên điểm danh');return;}"
                + "  lastRenderedSlot=-1; lastRenderedSid=-1;"
                + "  await refreshSessions();"
                + "}"
                + "async function closeAttSession(){"
                + "  if(!activeSession)return;"
                + "  await fetch('/api/attendance/sessions/close',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sessionId:activeSession.sessionId})});"
                + "  activeSession=null; closeFullscreenProjector(); await refreshSessions();"
                + "}"
                + "function formatOtp(code){if(!code||code.length!==6)return code||'------';return code.slice(0,3)+' '+code.slice(3);}"
                + "function fmtTimeSec(ts){if(!ts)return '-';const dt=new Date(ts*1000);return dt.toLocaleTimeString('vi-VN',{hour12:false});}"
                + "function methodLabel(m){if(m==='QR_SCAN')return 'QR Camera';if(m==='OTP_CODE')return 'Mã 6 số';if(m==='CARD_READER')return 'Đầu đọc thẻ';return m||'-';}"
                + "async function refreshSessions(){"
                + "  try{"
                + "    const r=await fetch('/api/attendance/sessions'); const d=await r.json();"
                + "    if(d.serverNowSec){serverClockOffsetSec=d.serverNowSec-Math.floor(Date.now()/1000);}"
                + "    const open=(d.sessions||[]).find(s=>s.status==='OPEN'&&s.remainingSec>0);"
                + "    activeSession=open||null;"
                + "    renderProjectorState();"
                + "    if(activeSession){await refreshRoster(activeSession.sessionId);}"
                + "  }catch(e){}"
                + "}"
                + "function renderProjectorState(){"
                + "  const box=document.getElementById('projectorBox'); const none=document.getElementById('noSessionBanner');"
                + "  const btnClose=document.getElementById('btnCloseSession'); const btnFs=document.getElementById('btnFullscreenProj');"
                + "  if(!activeSession){"
                + "    box.style.display='none'; none.style.display='block'; btnClose.style.display='none'; btnFs.style.display='none'; return;"
                + "  }"
                + "  box.style.display='grid'; none.style.display='none'; btnClose.style.display='inline-block'; btnFs.style.display='inline-block';"
                + "  const title=`${activeSession.courseCode} - ${activeSession.courseName}`;"
                + "  const meta=`${activeSession.room} • ${activeSession.lecturerName} • Phiên #${activeSession.sessionId}`;"
                + "  document.getElementById('projCourseTitle').textContent=title;"
                + "  document.getElementById('projCourseMeta').textContent=meta;"
                + "  document.getElementById('fsCourseTitle').textContent=title;"
                + "  document.getElementById('fsCourseMeta').textContent=meta;"
                + "  document.getElementById('projOtpCode').textContent=formatOtp(activeSession.otpCode);"
                + "  document.getElementById('fsOtpCode').textContent=formatOtp(activeSession.otpCode);"
                + "  const cntTxt=`Đã điểm danh: ${activeSession.checkedInCount} / ${activeSession.enrolledCount} sinh viên`;"
                + "  document.getElementById('projCountText').textContent=cntTxt;"
                + "  document.getElementById('fsCountText').textContent=cntTxt;"
                + "  if(lastRenderedSid!==activeSession.sessionId||lastRenderedSlot!==activeSession.timeSlot){"
                + "    lastRenderedSid=activeSession.sessionId; lastRenderedSlot=activeSession.timeSlot;"
                + "    const qrUrl=`/api/attendance/session-qr?sessionId=${activeSession.sessionId}&slot=${activeSession.timeSlot}&t=${Date.now()}`;"
                + "    document.getElementById('projectorQrImg').src=qrUrl;"
                + "    document.getElementById('fsQrImg').src=qrUrl;"
                + "  }"
                + "  tickAttendanceCountdowns();"
                + "}"
                + "function tickAttendanceCountdowns(){"
                + "  if(!activeSession)return;"
                + "  const nowSec=Math.floor(Date.now()/1000)+serverClockOffsetSec;"
                + "  const sessRem=Math.max(0,activeSession.expiresAt-nowSec);"
                + "  const mins=Math.floor(sessRem/60); const secs=sessRem%60;"
                + "  document.getElementById('projSessionRemaining').textContent=`Phiên còn lại: ${String(mins).padStart(2,'0')}:${String(secs).padStart(2,'0')}`;"
                + "  const rot=Math.max(10,activeSession.rotationSeconds||20);"
                + "  const curSlot=Math.floor(nowSec/rot);"
                + "  const slotRem=rot-(nowSec%rot);"
                + "  const pct=Math.max(0,Math.min(100,(slotRem*100)/rot));"
                + "  document.getElementById('projQrBar').style.width=pct+'%';"
                + "  document.getElementById('fsQrBar').style.width=pct+'%';"
                + "  const txt=`Đổi mã QR sau ${slotRem}s (Chu kỳ ${rot}s)`;"
                + "  document.getElementById('projQrCountdown').textContent=txt;"
                + "  document.getElementById('fsQrCountdown').textContent=txt;"
                + "  if(curSlot!==activeSession.timeSlot||sessRem===0){refreshSessions();}"
                + "}"
                + "async function refreshRoster(sessionId){"
                + "  try{"
                + "    const r=await fetch('/api/attendance/sessions/roster?sessionId='+sessionId);"
                + "    const rows=await r.json(); const tb=document.getElementById('projRosterBody'); tb.innerHTML='';"
                + "    let checked=0;"
                + "    (rows||[]).forEach(item=>{"
                + "      if(item.checkedIn) checked++;"
                + "      const tr=document.createElement('tr');"
                + "      tr.innerHTML=`<td><b>${item.studentId}</b></td><td>${item.fullName}</td><td>${item.className}</td><td>${item.checkedIn?'<span class=\"pill-ok\">✓ Có mặt</span>':'<span class=\"pill-wait\">Chờ điểm danh</span>'}</td><td>${item.checkedIn?methodLabel(item.checkinMethod):'-'}</td><td>${item.checkedIn?fmtTimeSec(item.timestampSeconds):'-'}</td><td>${item.checkedIn?(item.verifiedByServer?'✅ Hợp lệ':'❌ Lỗi'):'-'}</td>`;"
                + "      tb.appendChild(tr);"
                + "    });"
                + "    const cntTxt=`Đã điểm danh: ${checked} / ${(rows||[]).length} sinh viên`;"
                + "    document.getElementById('projCountText').textContent=cntTxt;"
                + "    document.getElementById('fsCountText').textContent=cntTxt;"
                + "  }catch(e){}"
                + "}"
                + "function openFullscreenProjector(){if(activeSession)document.getElementById('fsProjectorModal').style.display='flex';}"
                + "function closeFullscreenProjector(){document.getElementById('fsProjectorModal').style.display='none';}"
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
                + "    div.innerHTML=`<div><div class='name'>${idx+1}. ${c.fullName} • ${c.studentId} ${isCur?'<span style=\"color:#059669;font-size:12px;margin-left:8px;\">● ĐANG GẮN TRÊN ĐẦU ĐỌC</span>':''}</div><div class='sub'>Lớp: ${c.className} • Ngành: ${c.department} • Số dư ví: ${(c.walletBalance??500000).toLocaleString('vi-VN')} đ</div></div><button class='badge' style='cursor:pointer;border:none;background:${isCur?'#059669':'#2563eb'}'>${isCur?'Đang hoạt động':'Nạp thẻ này ➔'}</button>`;"
                + "    cont.appendChild(div);"
                + "  });"
                + "  const tb=document.getElementById('cardsTableBody'); tb.innerHTML='';"
                + "  (d.cards||[]).forEach(c=>{"
                + "    const tr=document.createElement('tr');"
                + "    tr.innerHTML=`<td><b>${c.studentId}</b></td><td>${c.fullName}</td><td>${c.dob}</td><td>${c.className}</td><td>${c.department}</td><td><b>${(c.walletBalance??500000).toLocaleString('vi-VN')} đ</b></td><td>${c.trainingScore}</td><td>${c.attendanceCount}</td><td>${c.cardLocked?'🔒 Khóa PIN':'✅ Sẵn sàng'}</td><td><button class='badge' style='cursor:pointer;border:none;padding:5px 10px;font-size:11px;' onclick=\"activateCard('${c.studentId}','${c.studentId}|${c.fullName}|${c.dob}|${c.className}|${c.department}','${c.defaultPin||'123456'}')\">Nạp</button> <button class='badge' style='cursor:pointer;border:none;background:#dc2626;padding:5px 10px;font-size:11px;margin-left:4px;' onclick=\"deleteCard('${c.studentId}')\">Xóa</button></td>`;"
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
                + "loadCourses(); refreshSessions(); refreshDb();"
                + "setInterval(tickAttendanceCountdowns, 1000);"
                + "setInterval(refreshSessions, 2000);"
                + "setInterval(refreshDb, 3000);"
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
        if (json == null || key == null) return "";
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return "";
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
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

    private static String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty() || key == null) return "";
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8).trim();
                }
            }
        }
        return "";
    }

    private static int parseIntSafe(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLongSafe(String raw, long fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static byte[] buildCanonicalAttendancePayload(int sessionId, int tsInt, int timeSlotInt) {
        return new byte[]{
                (byte) ((sessionId >> 8) & 0xFF),
                (byte) (sessionId & 0xFF),
                (byte) ((tsInt >> 24) & 0xFF),
                (byte) ((tsInt >> 16) & 0xFF),
                (byte) ((tsInt >> 8) & 0xFF),
                (byte) (tsInt & 0xFF),
                (byte) ((timeSlotInt >> 24) & 0xFF),
                (byte) ((timeSlotInt >> 16) & 0xFF),
                (byte) ((timeSlotInt >> 8) & 0xFF),
                (byte) (timeSlotInt & 0xFF)
        };
    }

    private static byte[] extractRsaModulusBytes(RSAPublicKey pub) {
        byte[] mod = pub.getModulus().toByteArray();
        if (mod.length == 129 && mod[0] == 0) {
            mod = Arrays.copyOfRange(mod, 1, 129);
        }
        return mod;
    }

    private static void syncVirtualCardPublicKeyToDb(boolean forceOverwrite) {
        if (!dbInitialized || virtualCard == null || virtualCard.rsaKeyPair == null) return;
        String sid = virtualCard.activeCardId;
        if (sid == null || sid.trim().isEmpty()) return;
        try {
            if (!forceOverwrite && pcscChannel != null) {
                return;
            }
            if (!forceOverwrite) {
                DatabaseManager.CardPublicKeyRecord existing = DatabaseManager.getCardPublicKey(sid);
                if (existing != null) {
                    String modClean = existing.modulusHex.replaceAll("[^0-9A-Fa-f]", "");
                    if (!existing.modulusHex.contains("...") && modClean.length() >= 256
                            && !existing.exponentHex.trim().isEmpty()) {
                        return;
                    }
                }
            }
            RSAPublicKey pub = (RSAPublicKey) virtualCard.rsaKeyPair.getPublic();
            String modHex = bytesToHex(extractRsaModulusBytes(pub));
            String expHex = bytesToHex(pub.getPublicExponent().toByteArray());
            DatabaseManager.updateCardPublicKey(sid, modHex, expHex);
        } catch (Exception ignored) {
        }
    }

    private static boolean verifyWithHexKey(String modHex, String expHex, byte[] payload, byte[] sigBytes) {
        if (modHex == null || expHex == null) return false;
        String cleanMod = modHex.replaceAll("[^0-9A-Fa-f]", "");
        String cleanExp = expHex.replaceAll("[^0-9A-Fa-f]", "");
        if (cleanMod.length() < 64 || cleanExp.isEmpty()) return false;
        try {
            BigInteger mod = new BigInteger(1, hexToBytes(cleanMod));
            BigInteger exp = new BigInteger(1, hexToBytes(cleanExp));
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(mod, exp));
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(pub);
            verifier.update(payload);
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verifyAttendanceSignatureOnServer(String studentId, byte[] canonicalPayload, String sigHex) {
        if (sigHex == null || canonicalPayload == null) return false;
        String cleanSig = sigHex.replaceAll("[^0-9A-Fa-f]", "");
        if (cleanSig.length() != 256) return false;
        byte[] sigBytes = hexToBytes(cleanSig);
        if (sigBytes.length != 128) return false;

        try {
            DatabaseManager.CardPublicKeyRecord dbKey = DatabaseManager.getCardPublicKey(studentId);
            if (dbKey != null && verifyWithHexKey(dbKey.modulusHex, dbKey.exponentHex, canonicalPayload, sigBytes)) {
                return true;
            }
        } catch (Exception ignored) {
        }

        if (pcscChannel != null) {
            try {
                ResponseAPDU modResp = pcscChannel.transmit(new CommandAPDU(new byte[]{(byte) 0x80, 0x13, 0x01, 0x00, 0x00}));
                ResponseAPDU expResp = pcscChannel.transmit(new CommandAPDU(new byte[]{(byte) 0x80, 0x13, 0x02, 0x00, 0x00}));
                if (modResp.getSW() == 0x9000 && expResp.getSW() == 0x9000) {
                    String modHex = bytesToHex(modResp.getData());
                    String expHex = bytesToHex(expResp.getData());
                    if (verifyWithHexKey(modHex, expHex, canonicalPayload, sigBytes)) {
                        DatabaseManager.updateCardPublicKey(studentId, modHex, expHex);
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (virtualCard != null && virtualCard.rsaKeyPair != null
                && studentId.equalsIgnoreCase(virtualCard.activeCardId)) {
            try {
                Signature verifier = Signature.getInstance("SHA1withRSA");
                verifier.initVerify(virtualCard.rsaKeyPair.getPublic());
                verifier.update(canonicalPayload);
                if (verifier.verify(sigBytes)) {
                    RSAPublicKey pub = (RSAPublicKey) virtualCard.rsaKeyPair.getPublic();
                    DatabaseManager.updateCardPublicKey(
                            studentId,
                            bytesToHex(extractRsaModulusBytes(pub)),
                            bytesToHex(pub.getPublicExponent().toByteArray()));
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private static byte[] computeAttendanceHmacBytes(String secretHex, int sessionId, String courseCode, long timeSlot) {
        try {
            byte[] keyBytes = hexToBytes(secretHex != null ? secretHex : "");
            if (keyBytes.length == 0) {
                keyBytes = "SMART_STUDENT_ATTENDANCE".getBytes(StandardCharsets.UTF_8);
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            String msg = sessionId + "|" + (courseCode != null ? courseCode : "") + "|" + timeSlot;
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[32];
        }
    }

    private static String computeQrTokenHex(DatabaseManager.AttendanceSession s, long timeSlot) {
        byte[] hmac = computeAttendanceHmacBytes(s.sessionSecret, s.id, s.courseCode, timeSlot);
        byte[] prefix = Arrays.copyOf(hmac, 8);
        return bytesToHex(prefix);
    }

    private static String buildSessionQrPayload(DatabaseManager.AttendanceSession s, long timeSlot) {
        return "ATT|" + s.id + "|" + s.courseCode + "|" + timeSlot + "|" + computeQrTokenHex(s, timeSlot);
    }

    private static String computeSessionOtp6Digits(DatabaseManager.AttendanceSession s, long timeSlot) {
        byte[] hmac = computeAttendanceHmacBytes(s.sessionSecret, s.id, s.courseCode, timeSlot);
        int offset = hmac[hmac.length - 1] & 0x0F;
        int binary = ((hmac[offset] & 0x7F) << 24)
                | ((hmac[offset + 1] & 0xFF) << 16)
                | ((hmac[offset + 2] & 0xFF) << 8)
                | (hmac[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format(Locale.US, "%06d", otp);
    }

    private static String renderQrSvg(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new LinkedHashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (matrix.get(x, y)) {
                        path.append('M').append(x).append(',').append(y).append("h1v1h-1z");
                    }
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h
                    + "\" width=\"" + sizePx + "\" height=\"" + sizePx + "\" shape-rendering=\"crispEdges\">"
                    + "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                    + "<path fill=\"#0f172a\" d=\"" + path + "\"/></svg>";
        } catch (Exception e) {
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + sizePx + "\" height=\"" + sizePx + "\"></svg>";
        }
    }

    private static String formatSessionJson(DatabaseManager.AttendanceSession s, long nowSec) {
        int rot = Math.max(10, s.rotationSeconds);
        long slot = nowSec / rot;
        long slotRem = rot - (nowSec % rot);
        long sessRem = Math.max(0L, s.expiresAt - nowSec);
        String qrPayload = "OPEN".equalsIgnoreCase(s.status) && sessRem > 0 ? buildSessionQrPayload(s, slot) : "";
        String otpCode = "OPEN".equalsIgnoreCase(s.status) && sessRem > 0 ? computeSessionOtp6Digits(s, slot) : "";
        int checkedIn = DatabaseManager.countCheckedInForSession(s.id);
        int enrolled = DatabaseManager.countEnrolledForCourse(s.courseCode);
        return "{"
                + "\"sessionId\":" + s.id + ","
                + "\"courseCode\":\"" + escapeJson(s.courseCode) + "\","
                + "\"courseName\":\"" + escapeJson(s.courseName) + "\","
                + "\"room\":\"" + escapeJson(s.room) + "\","
                + "\"lecturerName\":\"" + escapeJson(s.lecturerName) + "\","
                + "\"openedAt\":" + s.openedAt + ","
                + "\"expiresAt\":" + s.expiresAt + ","
                + "\"remainingSec\":" + sessRem + ","
                + "\"rotationSeconds\":" + rot + ","
                + "\"status\":\"" + escapeJson(s.status) + "\","
                + "\"timeSlot\":" + slot + ","
                + "\"slotRemainingSec\":" + slotRem + ","
                + "\"qrPayload\":\"" + escapeJson(qrPayload) + "\","
                + "\"otpCode\":\"" + escapeJson(otpCode) + "\","
                + "\"checkedInCount\":" + checkedIn + ","
                + "\"enrolledCount\":" + enrolled
                + "}";
    }

    private static class AttendancePrecheckResult {
        final boolean ok;
        final int httpStatus;
        final String errorCode;
        final String message;
        final DatabaseManager.AttendanceSession session;
        final long resolvedTimeSlot;

        AttendancePrecheckResult(boolean ok, int httpStatus, String errorCode, String message,
                                 DatabaseManager.AttendanceSession session, long resolvedTimeSlot) {
            this.ok = ok;
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
            this.message = message;
            this.session = session;
            this.resolvedTimeSlot = resolvedTimeSlot;
        }

        static AttendancePrecheckResult fail(int status, String code, String msg) {
            return new AttendancePrecheckResult(false, status, code, msg, null, 0L);
        }

        static AttendancePrecheckResult pass(DatabaseManager.AttendanceSession session, long resolvedTimeSlot) {
            return new AttendancePrecheckResult(true, 200, "", "", session, resolvedTimeSlot);
        }
    }

    private static AttendancePrecheckResult validateAttendancePreconditions(
            String studentId, int sessionId, String method, String qrPayload, String otpCode, long nowSec) {
        String normMethod = method != null ? method.trim().toUpperCase(Locale.ROOT) : "";
        if (!"QR_SCAN".equals(normMethod) && !"OTP_CODE".equals(normMethod) && !"CARD_READER".equals(normMethod)) {
            return AttendancePrecheckResult.fail(400, "INVALID_METHOD", "Phương thức điểm danh không hợp lệ.");
        }

        String[] qrParts = null;
        int effectiveSessionId = sessionId;
        long parsedSlot = 0L;
        if ("QR_SCAN".equals(normMethod)) {
            if (qrPayload == null || qrPayload.trim().isEmpty()) {
                return AttendancePrecheckResult.fail(400, "QR_INVALID", "Thiếu dữ liệu mã QR điểm danh.");
            }
            qrParts = qrPayload.trim().split("\\|", -1);
            if (qrParts.length != 5 || !"ATT".equals(qrParts[0])) {
                return AttendancePrecheckResult.fail(400, "QR_INVALID", "Mã QR không đúng định dạng điểm danh lớp học.");
            }
            int qrSid = parseIntSafe(qrParts[1], -1);
            parsedSlot = parseLongSafe(qrParts[3], -1L);
            if (qrSid <= 0 || parsedSlot < 0) {
                return AttendancePrecheckResult.fail(400, "QR_INVALID", "Nội dung mã QR điểm danh không hợp lệ.");
            }
            if (sessionId > 0 && qrSid != sessionId) {
                return AttendancePrecheckResult.fail(400, "QR_SESSION_MISMATCH", "Mã QR không thuộc ca học đã chọn.");
            }
            if (effectiveSessionId <= 0) {
                effectiveSessionId = qrSid;
            }
        }

        DatabaseManager.AttendanceSession session = DatabaseManager.getAttendanceSessionById(effectiveSessionId);
        if (session == null || !"OPEN".equalsIgnoreCase(session.status) || nowSec >= session.expiresAt) {
            return AttendancePrecheckResult.fail(400, "SESSION_CLOSED", "Phiên điểm danh đã đóng hoặc đã hết thời gian.");
        }

        if (!DatabaseManager.isStudentEnrolled(studentId, session.courseCode)) {
            return AttendancePrecheckResult.fail(403, "NOT_ENROLLED",
                    "Sinh viên " + studentId + " chưa đăng ký học phần " + session.courseCode + ".");
        }

        long resolvedSlot = 0L;
        int rot = Math.max(10, session.rotationSeconds);
        long currentSlot = nowSec / rot;

        if ("QR_SCAN".equals(normMethod)) {
            if (!qrParts[2].equalsIgnoreCase(session.courseCode)) {
                return AttendancePrecheckResult.fail(400, "QR_INVALID", "Mã học phần trên QR không khớp phiên điểm danh.");
            }
            if (parsedSlot < currentSlot - 1 || parsedSlot > currentSlot + 1) {
                return AttendancePrecheckResult.fail(400, "QR_EXPIRED",
                        "Mã QR điểm danh đã hết hạn (chống chụp ảnh gửi hộ). Vui lòng quét lại mã mới trên màn chiếu.");
            }
            String expectedToken = computeQrTokenHex(session, parsedSlot);
            String providedToken = qrParts[4].trim().toUpperCase(Locale.ROOT);
            if (!MessageDigest.isEqual(
                    expectedToken.getBytes(StandardCharsets.UTF_8),
                    providedToken.getBytes(StandardCharsets.UTF_8))) {
                return AttendancePrecheckResult.fail(400, "QR_INVALID", "Mã xác thực HMAC trên QR không hợp lệ.");
            }
            resolvedSlot = parsedSlot;
        } else if ("OTP_CODE".equals(normMethod)) {
            String cleanOtp = otpCode != null ? otpCode.replaceAll("\\s+", "") : "";
            if (!cleanOtp.matches("\\d{6}")) {
                return AttendancePrecheckResult.fail(400, "OTP_INVALID", "Mã điểm danh dự phòng phải gồm đúng 6 chữ số.");
            }
            String otpCur = computeSessionOtp6Digits(session, currentSlot);
            String otpPrev = computeSessionOtp6Digits(session, currentSlot - 1);
            byte[] providedBytes = cleanOtp.getBytes(StandardCharsets.UTF_8);
            boolean matchCur = MessageDigest.isEqual(otpCur.getBytes(StandardCharsets.UTF_8), providedBytes);
            boolean matchPrev = MessageDigest.isEqual(otpPrev.getBytes(StandardCharsets.UTF_8), providedBytes);
            if (!matchCur && !matchPrev) {
                return AttendancePrecheckResult.fail(400, "OTP_INVALID", "Mã điểm danh 6 số không đúng hoặc đã hết hạn.");
            }
            resolvedSlot = 0L;
        } else {
            resolvedSlot = 0L;
        }

        if (DatabaseManager.hasStudentCheckedIn(session.id, studentId)) {
            return AttendancePrecheckResult.fail(409, "ALREADY_CHECKED_IN", "Bạn đã điểm danh buổi học này rồi.");
        }

        return AttendancePrecheckResult.pass(session, resolvedSlot);
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
            syncVirtualCardPublicKeyToDb(false);
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
                            syncVirtualCardPublicKeyToDb(true);
                        } else {
                            syncVirtualCardPublicKeyToDb(false);
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
