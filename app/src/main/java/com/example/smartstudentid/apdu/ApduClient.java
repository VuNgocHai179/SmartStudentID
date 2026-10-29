package com.example.smartstudentid.apdu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
 * Client responsible for transmitting APDU frames to the PC Bridge Server (or local fallback simulator)
 * and managing reusable byte buffers to prevent memory churn (OPT-05).
 */
public class ApduClient {

    private static ApduClient instance;

    // Default Emulator host alias for connecting to PC Bridge Server on port 8080
    private String serverBaseUrl = "http://10.0.2.2:8080";

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

        try {
            URL url = new URL(serverBaseUrl + "/api/apdu");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(600);
            conn.setReadTimeout(1500);
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
            // Fall through to the local embedded card simulator if the PC server is unreachable
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

    /**
     * Uploads an avatar byte array across multiple APDU chunks (INS 0x22).
     *
     * @return Total number of APDU packets transmitted.
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
            sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_UPLOAD_AVATAR_CHUNK, p1, p2, chunk);
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
        try {
            URL url = new URL(serverBaseUrl + "/api/activate-card");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(500);
            conn.setReadTimeout(800);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            String json = String.format("{\"studentId\":\"%s\",\"studentInfo\":\"%s\",\"pin\":\"%s\"}",
                    studentId, pipeDelimitedInfo, pin);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } catch (Exception ignored) {
        }
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
    private static class LocalFallbackCardEngine {
        String activeCardId = "CT070315";
        String currentPin = "123456";
        final String adminPuk = "88888888";
        int pinTriesRemaining = 3;
        boolean pinValidated = false;

        byte[] encryptedInfo = new byte[0];
        int plainInfoLen = 0;
        final byte[] avatarBuffer = new byte[8192];
        int avatarLength = 0;

        short trainingScore = 88;
        short attendanceCount = 12;
        byte labAccess = 1;
        final short[] borrowedBooks = new short[]{0x0102, 0x0205, 0, 0, 0};
        byte borrowedCount = 2;

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
            this.activeCardId = cardId;
            this.currentPin = (pin != null && !pin.isEmpty()) ? pin : "123456";
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
                        if (pinTriesRemaining == 0) return swOnly(0x6983);
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
                        pinTriesRemaining = 3;
                        currentPin = "123456";
                        pinValidated = true;
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
