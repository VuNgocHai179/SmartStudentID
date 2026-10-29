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
 * PC BRIDGE & CARD MANAGEMENT SERVER (SC-07)
 * Standalone execution: java bridge-server/src/BridgeServer.java
 * Default port: 8080 (Android Emulator connects via http://10.0.2.2:8080)
 * ============================================================================
 * Key responsibilities:
 * 1. Connects directly to the JCIDE Simulator via PC/SC (javax.smartcardio).
 * 2. Automatically falls back to the built-in Virtual SmartCard Engine if
 *    JCIDE is not running, allowing full testing of all 14 APDU commands.
 * 3. Manages the student card database (cards_database.json) and supports
 *    instant Virtual Card Switching (< 50ms) for Admin testing.
 */
public class BridgeServer {

    private static final int PORT = 8080;
    private static final File DB_FILE = new File("bridge-server/data/cards_database.json");

    // Fallback virtual card emulator matching 100% of SmartCampusApplet's APDU specification
    private static final VirtualSmartCardEmulator virtualCard = new VirtualSmartCardEmulator();
    private static CardChannel pcscChannel = null;
    private static String activeReaderName = "Virtual-JCIDE-Fallback-Engine";

    public static void main(String[] args) throws Exception {
        tryConnectPcscReader();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);

        // 1. Endpoint for checking Bridge & Card Reader connection status
        server.createContext("/api/status", exchange -> {
            String json = String.format(
                    "{\"status\":\"ONLINE\",\"reader\":\"%s\",\"activeStudentId\":\"%s\",\"pinTries\":%d}",
                    activeReaderName, virtualCard.activeCardId, virtualCard.pinTriesRemaining
            );
            sendJson(exchange, 200, json);
        });

        // 2. Endpoint for transmitting raw APDU commands (Hex request -> Hex response)
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
                    bytesToHex(cmdBytes), bytesToHex(dataBytes), sw, elapsedMs, activeReaderName
            );
            System.out.printf("[APDU] >> %s | << Data(%dB) SW=%04X (%dms)%n",
                    bytesToHex(cmdBytes), dataBytes.length, sw, elapsedMs);
            sendJson(exchange, 200, respJson);
        });

        // 3. Endpoint for retrieving or updating the Student Card list (Admin Role)
        server.createContext("/api/cards", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (DB_FILE.exists()) {
                    String content = Files.readString(DB_FILE.toPath(), StandardCharsets.UTF_8);
                    sendJson(exchange, 200, content);
                } else {
                    sendJson(exchange, 200, "[]");
                }
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                DB_FILE.getParentFile().mkdirs();
                Files.writeString(DB_FILE.toPath(), body, StandardCharsets.UTF_8);
                sendJson(exchange, 200, "{\"saved\":true}");
            }
        });

        // 4. Endpoint for switching the active virtual card in the reader (SC-07)
        server.createContext("/api/activate-card", exchange -> {
            String body = readBody(exchange);
            String studentId = extractJsonString(body, "studentId");
            String infoPlain = extractJsonString(body, "studentInfo");
            String pin = extractJsonString(body, "pin");

            virtualCard.loadSnapshot(studentId, infoPlain, pin.isEmpty() ? "123456" : pin);
            System.out.println("[CARD SWITCHER] Activated virtual card for Student: " + studentId);
            sendJson(exchange, 200, String.format("{\"activated\":true,\"studentId\":\"%s\"}", studentId));
        });

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("==================================================================");
        System.out.println(" SMART STUDENT ID - PC BRIDGE & CARD SERVER STARTED ON PORT " + PORT);
        System.out.println(" Active Reader : " + activeReaderName);
        System.out.println(" Endpoints     : http://localhost:8080/api/status");
        System.out.println("                 http://localhost:8080/api/apdu");
        System.out.println("                 http://localhost:8080/api/cards");
        System.out.println("                 http://localhost:8080/api/activate-card");
        System.out.println("==================================================================");
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
            // Fall back to VirtualSmartCardEmulator when JCIDE is not active
        }
    }

    private static synchronized byte[] transmitApdu(byte[] cmdBytes) {
        if (pcscChannel != null) {
            try {
                ResponseAPDU resp = pcscChannel.transmit(new CommandAPDU(cmdBytes));
                return resp.getBytes();
            } catch (Exception e) {
                pcscChannel = null;
                activeReaderName = "Virtual-JCIDE-Fallback-Engine";
            }
        }
        return virtualCard.processApdu(cmdBytes);
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

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return "";
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return "";
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return "";
        return json.substring(firstQuote + 1, secondQuote);
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

    /**
     * In-memory smart card emulator matching 100% of SmartCampusApplet.java
     * so all 14 APDU commands can be tested even when JCIDE is offline.
     */
    static class VirtualSmartCardEmulator {
        String activeCardId = "CT070315";
        String currentPin = "123456";
        final String adminPuk = "88888888";
        int pinTriesRemaining = 3;
        boolean pinValidated = false;

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
                loadSnapshot("CT070315", "CT070315|Vu Ngoc Hai|15/08/2003|CT7C|An toan Thong tin", "123456");
            } catch (Exception ignored) {
            }
        }

        void loadSnapshot(String cardId, String infoUtf8, String pin) {
            this.activeCardId = cardId;
            this.currentPin = pin;
            this.pinTriesRemaining = 3;
            this.pinValidated = true;
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

            // 1. SELECT APPLET (00 A4 04 00)
            if (cla == 0x00 && ins == 0xA4) {
                return withSw(new byte[]{(byte) pinTriesRemaining, (byte) (plainInfoLen > 0 ? 1 : 0)}, 0x9000);
            }
            if (cla != 0x80) {
                return swOnly(0x6E00);
            }

            try {
                switch (ins) {
                    case 0x10: { // VERIFY PIN
                        if (pinTriesRemaining == 0) return swOnly(0x6983);
                        String inputPin = new String(cdata, StandardCharsets.UTF_8);
                        if (currentPin.equals(inputPin)) {
                            pinTriesRemaining = 3;
                            pinValidated = true;
                            return swOnly(0x9000);
                        } else {
                            pinTriesRemaining--;
                            pinValidated = false;
                            if (pinTriesRemaining == 0) return swOnly(0x6983);
                            return swOnly(0x63C0 | pinTriesRemaining);
                        }
                    }
                    case 0x11: { // CHANGE PIN
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
                    case 0x12: { // UNBLOCK CARD BY PUK
                        String puk = new String(cdata, StandardCharsets.UTF_8);
                        if (!adminPuk.equals(puk)) return swOnly(0x6300);
                        pinTriesRemaining = 3;
                        currentPin = "123456";
                        pinValidated = true;
                        return swOnly(0x9000);
                    }
                    case 0x13: { // GET / GEN RSA PUBLIC KEY
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
                    case 0x14: { // SIGN CHALLENGE / CHECK-IN
                        Signature sig = Signature.getInstance("SHA1withRSA");
                        sig.initSign(rsaKeyPair.getPrivate());
                        sig.update(cdata);
                        return withSw(sig.sign(), 0x9000);
                    }
                    case 0x20: { // SET STUDENT INFO (AES-128)
                        if (!pinValidated) return swOnly(0x6982);
                        encryptAndStoreInfo(cdata);
                        return swOnly(0x9000);
                    }
                    case 0x21: { // GET STUDENT INFO
                        if (p1 == 0x01) {
                            return withSw(encryptedInfo, 0x9000);
                        }
                        if (!pinValidated) return swOnly(0x6982);
                        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, aesKey);
                        byte[] dec = cipher.doFinal(encryptedInfo);
                        return withSw(Arrays.copyOf(dec, plainInfoLen), 0x9000);
                    }
                    case 0x22: { // UPLOAD AVATAR CHUNK
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
                    case 0x23: { // DOWNLOAD AVATAR CHUNK
                        int offset = (p1 << 8) | p2;
                        if (offset >= avatarLength) return withSw(new byte[0], 0x9000);
                        int len = Math.min(240, avatarLength - offset);
                        return withSw(Arrays.copyOfRange(avatarBuffer, offset, offset + len), 0x9000);
                    }
                    case 0x30: { // GET ACADEMIC & LIBRARY STATUS
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
                    case 0x31: // BORROW BOOK / ATTENDANCE
                    case 0x32: { // RETURN BOOK / LAB ACCESS
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
                    case 0x33: { // GET ON-CARD ACTIVITY LOG
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
