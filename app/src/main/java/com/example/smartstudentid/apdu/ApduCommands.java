package com.example.smartstudentid.apdu;

/**
 * ISO/IEC 7816-4 APDU command specification for the Smart Student ID system.
 * Matches the 14-command specification in Sheet 2_TapLenh_APDU (SC-02).
 *
 * AID: A0 00 00 00 62 03 01 0C 06 01
 * CLA: 0x80
 */
public final class ApduCommands {

    private ApduCommands() {
    }

    // SmartCampusApplet AID on JCIDE (10 bytes)
    public static final byte[] APPLET_AID = new byte[]{
            (byte) 0xA0, 0x00, 0x00, 0x00, 0x62, 0x03, 0x01, 0x0C, 0x06, 0x01
    };
    public static final String APPLET_AID_HEX = "A00000006203010C0601";

    // Standard CLA bytes
    public static final byte CLA_ISO = (byte) 0x00;
    public static final byte CLA_SMART_STUDENT = (byte) 0x80;

    // 1. Applet Initialization & Selection
    public static final byte INS_SELECT = (byte) 0xA4;

    // 2. Authentication & Card Lock Management (PIN / PUK) - SC-03
    public static final byte INS_VERIFY_PIN = (byte) 0x10;
    public static final byte INS_CHANGE_PIN = (byte) 0x11;
    public static final byte INS_UNBLOCK_CARD_PUK = (byte) 0x12;
    public static final byte P1_PUK_UNBLOCK = (byte) 0x00;
    public static final byte P1_PUK_LOCK = (byte) 0x01;

    // 3. RSA-1024 Asymmetric Cryptography - SC-04 & SEC-03
    public static final byte INS_GET_PUBLIC_KEY = (byte) 0x13;
    public static final byte P1_GEN_RSA_KEYPAIR = (byte) 0x00;
    public static final byte P1_GET_MODULUS = (byte) 0x01;
    public static final byte P1_GET_EXPONENT = (byte) 0x02;

    public static final byte INS_SIGN_CHALLENGE = (byte) 0x14;

    // 4. Student Profile Storage (AES-128 CBC) - SC-04 & SEC-01
    public static final byte INS_SET_STUDENT_INFO = (byte) 0x20;
    public static final byte INS_GET_STUDENT_INFO = (byte) 0x21;
    public static final byte P1_INFO_DECRYPTED = (byte) 0x00;
    public static final byte P1_INFO_RAW_CIPHERTEXT = (byte) 0x01;

    // 5. Avatar Transfer (WebP Chunking) - SC-05 & OPT-02
    public static final byte INS_UPLOAD_AVATAR_CHUNK = (byte) 0x22;
    public static final byte INS_DOWNLOAD_AVATAR_CHUNK = (byte) 0x23;
    public static final int MAX_AVATAR_CHUNK_SIZE = 240;

    // 6. Academic, Attendance, Library & Lab Access - SC-06
    public static final byte INS_GET_ACADEMIC_LIBRARY_STATUS = (byte) 0x30;

    public static final byte INS_BORROW_OR_ATTENDANCE = (byte) 0x31;
    public static final byte P1_ACTION_ATTENDANCE = (byte) 0x01;
    public static final byte P1_ACTION_BORROW_BOOK = (byte) 0x02;

    public static final byte INS_RETURN_OR_LAB_ACCESS = (byte) 0x32;
    public static final byte P1_ACTION_RETURN_BOOK = (byte) 0x01;
    public static final byte P1_ACTION_LAB_ACCESS = (byte) 0x02;

    public static final byte INS_GET_ACTIVITY_LOG = (byte) 0x33;

    // Status Words (SW1-SW2)
    public static final int SW_NO_ERROR = 0x9000;
    public static final int SW_SECURITY_STATUS_NOT_SATISFIED = 0x6982;
    public static final int SW_AUTHENTICATION_METHOD_BLOCKED = 0x6983; // Card blocked after 3 failed PIN attempts
    public static final int SW_WRONG_PUK = 0x6300;
    public static final int SW_PIN_TRIES_PREFIX = 0x63C0; // 63CX: X remaining PIN attempts

    /**
     * Converts a byte array into a space-separated uppercase hex string.
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("%02X", bytes[i] & 0xFF));
            if (i < bytes.length - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /**
     * Converts a hexadecimal string into a byte array.
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        int len = clean.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
        }
        return out;
    }
}
