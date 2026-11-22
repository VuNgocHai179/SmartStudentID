package smartstudent;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.OwnerPIN;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.KeyBuilder;
import javacard.security.KeyPair;
import javacard.security.MessageDigest;
import javacard.security.RSAPrivateKey;
import javacard.security.RSAPublicKey;
import javacard.security.Signature;
import javacardx.crypto.Cipher;

/**
 * ============================================================================
 * SMART CAMPUS APPLET - MULTI-PURPOSE SMART STUDENT ID SYSTEM
 * Author: Vu Ngoc Hai - Class CT7C
 * AID: A0 00 00 00 62 03 01 0C 06 01 | CLA: 0x80
 * ============================================================================
 * Implements all 14 APDU instructions specified in SC-02 through SC-06:
 *  - INS 0x10: VERIFY PIN (OwnerPIN with 3 max tries, returns 63CX / 6983)
 *  - INS 0x11: CHANGE PIN (Updates PIN inside an atomic transaction)
 *  - INS 0x12: UNBLOCK CARD BY PUK (Admin 8-digit PUK resets and unblocks PIN)
 *  - INS 0x13: GET / GENERATE RSA-1024 PUBLIC KEY (Modulus N, Exponent E)
 *  - INS 0x14: SIGN CHALLENGE / CHECK-IN (RSA-1024 SHA1-PKCS#1 signature)
 *  - INS 0x20: SET STUDENT INFO (AES-128 CBC encryption stored in EEPROM)
 *  - INS 0x21: GET STUDENT INFO (P1=00: Decrypted plaintext | P1=01: Raw AES ciphertext)
 *  - INS 0x22: UPLOAD AVATAR CHUNK (Receives WebP image chunks by P1-P2 offset)
 *  - INS 0x23: DOWNLOAD AVATAR CHUNK (Sends WebP image chunks by P1-P2 offset)
 *  - INS 0x30: GET ACADEMIC & LIBRARY STATUS (Training score, attendance, books)
 *  - INS 0x31: BORROW BOOK / ATTENDANCE (Atomic transaction + RSA signature)
 *  - INS 0x32: RETURN BOOK / LAB ACCESS (Atomic transaction + RSA signature)
 *  - INS 0x33: GET ON-CARD ACTIVITY LOG (Circular buffer of the 10 latest events)
 */
public class SmartCampusApplet extends Applet {

    // Standard CLA and INS constants
    private static final byte CLA_SMART_STUDENT = (byte) 0x80;

    private static final byte INS_VERIFY_PIN = (byte) 0x10;
    private static final byte INS_CHANGE_PIN = (byte) 0x11;
    private static final byte INS_UNBLOCK_CARD_PUK = (byte) 0x12;
    private static final byte INS_GET_PUBLIC_KEY = (byte) 0x13;
    private static final byte INS_SIGN_CHALLENGE = (byte) 0x14;

    private static final byte INS_SET_STUDENT_INFO = (byte) 0x20;
    private static final byte INS_GET_STUDENT_INFO = (byte) 0x21;
    private static final byte INS_UPLOAD_AVATAR_CHUNK = (byte) 0x22;
    private static final byte INS_DOWNLOAD_AVATAR_CHUNK = (byte) 0x23;

    private static final byte INS_GET_ACADEMIC_LIBRARY_STATUS = (byte) 0x30;
    private static final byte INS_BORROW_OR_ATTENDANCE = (byte) 0x31;
    private static final byte INS_RETURN_OR_LAB_ACCESS = (byte) 0x32;
    private static final byte INS_GET_ACTIVITY_LOG = (byte) 0x33;

    private static final byte INS_WALLET_CREDIT = (byte) 0x40;
    private static final byte INS_WALLET_DEBIT = (byte) 0x41;
    private static final byte INS_WALLET_GET_BALANCE = (byte) 0x42;
    private static final byte[] MAX_WALLET_BALANCE = new byte[]{
            (byte) 0x02, (byte) 0xFA, (byte) 0xF0, (byte) 0x80
    }; // 50,000,000 VND cap for identified e-wallet

    // PIN and PUK configuration
    private static final byte PIN_TRY_LIMIT = (byte) 3;
    private static final byte MAX_PIN_SIZE = (byte) 8;
    private static final byte PUK_SIZE = (byte) 8;

    // EEPROM allocation sizes
    private static final short MAX_INFO_SIZE = (short) 256; // Multiple of 16 bytes for AES-128 block size
    private static final short MAX_AVATAR_SIZE = (short) 8192; // Holds compressed 150x150 WebP avatar (~3.5KB)
    private static final byte MAX_BORROWED_BOOKS = (byte) 5;
    private static final byte MAX_LOG_ENTRIES = (byte) 10;
    private static final byte LOG_ENTRY_SIZE = (byte) 7; // 1B Type + 2B Code + 4B Timestamp

    // Custom status word prefix
    private static final short SW_PIN_TRIES_PREFIX = (short) 0x63C0;

    // ================== PERSISTENT MEMORY (EEPROM) ==================
    private final OwnerPIN pin;
    private final byte[] adminPuk;
    private final byte[] defaultPin;

    // Persistent admin PUK lock flag: blocks all PIN operations until a P1=00 PUK unblock
    private boolean adminLocked;

    // On-card cryptographic objects (AES-128 & RSA-1024 & SHA-1 PIN Digest)
    private final AESKey aesKey;
    private final Cipher aesCipher;
    private final MessageDigest pinDigest;
    private final byte[] pinHash;
    private final byte[] zeroIv;
    private KeyPair rsaKeyPair;
    private RSAPrivateKey rsaPrivateKey;
    private RSAPublicKey rsaPublicKey;
    private final Signature rsaSigner;

    // Student profile buffer (stored as AES-128 ciphertext in EEPROM)
    private final byte[] encryptedStudentInfo;
    private short encryptedInfoLength;
    private short plainInfoLength;

    // WebP avatar storage buffer
    private final byte[] avatarBuffer;
    private short avatarLength;

    // Academic, attendance, library, and lab access state
    private short trainingScore;
    private short attendanceCount;
    private byte labAccessPermission; // 1 = Lab access granted, 0 = Revoked
    private final short[] borrowedBooks; // Up to 5 borrowed book IDs
    private byte borrowedBookCount;
    private final byte[] walletBalance; // 4-byte big-endian VND balance in EEPROM

    // Circular buffer storing the 10 most recent on-card activities
    private final byte[] activityLogBuffer;
    private byte logHeadIndex;
    private byte logCount;

    // ================== TRANSIENT MEMORY (RAM) ==================
    private final byte[] transientBuffer;
    private final byte[] transientDigest;

    /**
     * Initializes the Applet and pre-allocates all EEPROM and transient RAM buffers
     * during installation in accordance with Java Card memory best practices.
     */
    protected SmartCampusApplet() {
        // 1. Allocate transient RAM working buffers (cleared on applet deselection)
        transientBuffer = JCSystem.makeTransientByteArray((short) 256, JCSystem.CLEAR_ON_DESELECT);
        transientDigest = JCSystem.makeTransientByteArray((short) 20, JCSystem.CLEAR_ON_DESELECT);

        // 2. Initialize default PIN ("123456") and Admin PUK ("88888888")
        defaultPin = new byte[]{'1', '2', '3', '4', '5', '6'};
        adminPuk = new byte[]{'8', '8', '8', '8', '8', '8', '8', '8'};
        adminLocked = false;
        pin = new OwnerPIN(PIN_TRY_LIMIT, MAX_PIN_SIZE);
        pin.update(defaultPin, (short) 0, (byte) defaultPin.length);

        // 3. Initialize PIN-derived AES-128 key, SHA-1 digest, and CBC cipher (zero IV)
        pinDigest = MessageDigest.getInstance(MessageDigest.ALG_SHA, false);
        pinHash = new byte[20];
        zeroIv = new byte[16];
        aesKey = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
        aesCipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
        deriveAesKeyFromPin(defaultPin, (short) 0, (byte) defaultPin.length, true);

        // 4. Initialize RSA-1024 key pair and SHA-1 with RSA PKCS#1 signature engine
        rsaKeyPair = new KeyPair(KeyPair.ALG_RSA, KeyBuilder.LENGTH_RSA_1024);
        rsaKeyPair.genKeyPair();
        rsaPrivateKey = (RSAPrivateKey) rsaKeyPair.getPrivate();
        rsaPublicKey = (RSAPublicKey) rsaKeyPair.getPublic();
        rsaSigner = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);

        // 5. Allocate EEPROM buffers for student profile and WebP avatar
        encryptedStudentInfo = new byte[MAX_INFO_SIZE];
        encryptedInfoLength = 0;
        plainInfoLength = 0;

        avatarBuffer = new byte[MAX_AVATAR_SIZE];
        avatarLength = 0;

        // 6. Initialize default academic, library and e-wallet state (500,000 VND)
        trainingScore = (short) 85;
        attendanceCount = (short) 0;
        labAccessPermission = (byte) 1;
        borrowedBooks = new short[MAX_BORROWED_BOOKS];
        borrowedBookCount = (byte) 0;
        walletBalance = new byte[]{(byte) 0x00, (byte) 0x07, (byte) 0xA1, (byte) 0x20};

        // 7. Allocate circular buffer for the 10 latest activities
        activityLogBuffer = new byte[(short) (MAX_LOG_ENTRIES * LOG_ENTRY_SIZE)];
        logHeadIndex = (byte) 0;
        logCount = (byte) 0;

        register();
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new SmartCampusApplet();
    }

    public boolean select() {
        return true;
    }

    public void deselect() {
        pin.reset();
    }

    public void process(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        if (selectingApplet()) {
            // Return card status upon SELECT AID: [0]=Remaining PIN tries, [1]=Initialized flag
            buffer[0] = adminLocked ? (byte) 0 : pin.getTriesRemaining();
            buffer[1] = (plainInfoLength > 0) ? (byte) 1 : (byte) 0;
            apdu.setOutgoingAndSend((short) 0, (short) 2);
            return;
        }

        if (buffer[ISO7816.OFFSET_CLA] != CLA_SMART_STUDENT) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        byte ins = buffer[ISO7816.OFFSET_INS];
        switch (ins) {
            case INS_VERIFY_PIN:
                handleVerifyPin(apdu);
                break;
            case INS_CHANGE_PIN:
                handleChangePin(apdu);
                break;
            case INS_UNBLOCK_CARD_PUK:
                handleUnblockByPuk(apdu);
                break;
            case INS_GET_PUBLIC_KEY:
                handleGetOrGenPublicKey(apdu);
                break;
            case INS_SIGN_CHALLENGE:
                handleSignChallenge(apdu);
                break;
            case INS_SET_STUDENT_INFO:
                handleSetStudentInfo(apdu);
                break;
            case INS_GET_STUDENT_INFO:
                handleGetStudentInfo(apdu);
                break;
            case INS_UPLOAD_AVATAR_CHUNK:
                handleUploadAvatarChunk(apdu);
                break;
            case INS_DOWNLOAD_AVATAR_CHUNK:
                handleDownloadAvatarChunk(apdu);
                break;
            case INS_GET_ACADEMIC_LIBRARY_STATUS:
                handleGetAcademicAndLibraryStatus(apdu);
                break;
            case INS_BORROW_OR_ATTENDANCE:
                handleBorrowOrAttendance(apdu);
                break;
            case INS_RETURN_OR_LAB_ACCESS:
                handleReturnOrLabAccess(apdu);
                break;
            case INS_GET_ACTIVITY_LOG:
                handleGetActivityLog(apdu);
                break;
            case INS_WALLET_CREDIT:
                processWalletCredit(apdu);
                break;
            case INS_WALLET_DEBIT:
                processWalletDebit(apdu);
                break;
            case INS_WALLET_GET_BALANCE:
                processWalletGetBalance(apdu);
                break;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    // =========================================================================
    // GROUP 1: PIN & PUK AUTHENTICATION (SC-03)
    // =========================================================================

    private void handleVerifyPin(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        byte len = (byte) apdu.setIncomingAndReceive();

        if (adminLocked || pin.getTriesRemaining() == 0) {
            ISOException.throwIt(ISO7816.SW_FILE_INVALID); // 0x6983: Card is locked
        }

        if (!pin.check(buffer, ISO7816.OFFSET_CDATA, len)) {
            byte triesLeft = pin.getTriesRemaining();
            if (triesLeft == 0) {
                ISOException.throwIt((short) 0x6983);
            }
            ISOException.throwIt((short) (SW_PIN_TRIES_PREFIX | triesLeft));
        }

        deriveAesKeyFromPin(buffer, ISO7816.OFFSET_CDATA, len, false);
    }

    private void handleChangePin(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        apdu.setIncomingAndReceive();

        short offset = ISO7816.OFFSET_CDATA;
        byte oldPinLen = buffer[offset++];
        if (oldPinLen < 4 || oldPinLen > MAX_PIN_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        if (!pin.check(buffer, offset, oldPinLen)) {
            byte triesLeft = pin.getTriesRemaining();
            if (triesLeft == 0) {
                ISOException.throwIt((short) 0x6983);
            }
            ISOException.throwIt((short) (SW_PIN_TRIES_PREFIX | triesLeft));
        }
        offset += oldPinLen;

        byte newPinLen = buffer[offset++];
        if (newPinLen < 4 || newPinLen > MAX_PIN_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        updatePinAndReencrypt(buffer, offset, newPinLen, false);
        pin.check(buffer, offset, newPinLen);
    }

    private void handleUnblockByPuk(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        byte p1 = buffer[ISO7816.OFFSET_P1];
        short len = apdu.setIncomingAndReceive();
        if (len != PUK_SIZE) {
            ISOException.throwIt((short) 0x6300);
        }

        if (Util.arrayCompare(buffer, ISO7816.OFFSET_CDATA, adminPuk, (short) 0, PUK_SIZE) != 0) {
            ISOException.throwIt((short) 0x6300);
        }

        if (p1 == (byte) 0x01) {
            JCSystem.beginTransaction();
            adminLocked = true;
            JCSystem.commitTransaction();
        } else {
            updatePinAndReencrypt(defaultPin, (short) 0, (byte) defaultPin.length, true);
        }
    }

    /**
     * Derives a 128-bit AES key from the card PIN via on-chip SHA-1 digest (first 16 bytes).
     */
    private void deriveAesKeyFromPin(byte[] pinBuf, short pinOff, byte pinLen, boolean persistPinHash) {
        pinDigest.reset();
        pinDigest.doFinal(pinBuf, pinOff, pinLen, transientDigest, (short) 0);
        aesKey.setKey(transientDigest, (short) 0);
        if (persistPinHash) {
            Util.arrayCopyNonAtomic(transientDigest, (short) 0, pinHash, (short) 0, (short) 20);
        }
        Util.arrayFillNonAtomic(transientDigest, (short) 0, (short) transientDigest.length, (byte) 0);
    }

    /**
     * Atomically updates the card PIN + SHA-1 PIN hash, derives the new AES-128 key,
     * and re-encrypts EEPROM student profile data from the previous PIN key to the new PIN key.
     */
    private void updatePinAndReencrypt(byte[] newPinBuf, short newPinOff, byte newPinLen, boolean clearAdminLock) {
        short infoCipherLen = encryptedInfoLength;
        if (infoCipherLen > 0) {
            aesCipher.init(aesKey, Cipher.MODE_DECRYPT, zeroIv, (short) 0, (short) 16);
            aesCipher.doFinal(encryptedStudentInfo, (short) 0, infoCipherLen, transientBuffer, (short) 0);
        }

        pinDigest.reset();
        pinDigest.doFinal(newPinBuf, newPinOff, newPinLen, transientDigest, (short) 0);

        JCSystem.beginTransaction();
        if (clearAdminLock) {
            adminLocked = false;
            pin.resetAndUnblock();
        }
        pin.update(newPinBuf, newPinOff, newPinLen);
        Util.arrayCopy(transientDigest, (short) 0, pinHash, (short) 0, (short) 20);
        aesKey.setKey(transientDigest, (short) 0);
        if (infoCipherLen > 0) {
            aesCipher.init(aesKey, Cipher.MODE_ENCRYPT, zeroIv, (short) 0, (short) 16);
            aesCipher.doFinal(transientBuffer, (short) 0, infoCipherLen, encryptedStudentInfo, (short) 0);
        }
        JCSystem.commitTransaction();

        Util.arrayFillNonAtomic(transientBuffer, (short) 0, (short) transientBuffer.length, (byte) 0);
        Util.arrayFillNonAtomic(transientDigest, (short) 0, (short) transientDigest.length, (byte) 0);
    }

    // =========================================================================
    // GROUP 2: RSA-1024 ASYMMETRIC CRYPTOGRAPHY (SC-04 & SEC-03)
    // =========================================================================

    private void handleGetOrGenPublicKey(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        byte p1 = buffer[ISO7816.OFFSET_P1];

        if (p1 == (byte) 0x00) {
            // Generate a fresh RSA-1024 key pair during card issuance
            rsaKeyPair.genKeyPair();
            rsaPrivateKey = (RSAPrivateKey) rsaKeyPair.getPrivate();
            rsaPublicKey = (RSAPublicKey) rsaKeyPair.getPublic();
            short modLen = rsaPublicKey.getModulus(buffer, (short) 0);
            apdu.setOutgoingAndSend((short) 0, modLen);
        } else if (p1 == (byte) 0x01) {
            short modLen = rsaPublicKey.getModulus(buffer, (short) 0);
            apdu.setOutgoingAndSend((short) 0, modLen);
        } else if (p1 == (byte) 0x02) {
            short expLen = rsaPublicKey.getExponent(buffer, (short) 0);
            apdu.setOutgoingAndSend((short) 0, expLen);
        } else {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
    }

    private void handleSignChallenge(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();

        rsaSigner.init(rsaPrivateKey, Signature.MODE_SIGN);
        short sigLen = rsaSigner.sign(buffer, ISO7816.OFFSET_CDATA, dataLen, buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    // =========================================================================
    // GROUP 3: AES-128 CBC ENCRYPTED STUDENT PROFILE (SC-04 & SEC-01)
    // =========================================================================

    private void handleSetStudentInfo(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len <= 0 || len > (short) (MAX_INFO_SIZE - 16)) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // Apply PKCS#7 padding to align with 16-byte AES blocks
        short paddedLen = (short) (((short) (len + 16) / 16) * 16);
        byte padValue = (byte) (paddedLen - len);

        Util.arrayCopyNonAtomic(buffer, ISO7816.OFFSET_CDATA, transientBuffer, (short) 0, len);
        Util.arrayFillNonAtomic(transientBuffer, len, padValue, padValue);

        aesCipher.init(aesKey, Cipher.MODE_ENCRYPT, zeroIv, (short) 0, (short) 16);
        JCSystem.beginTransaction();
        aesCipher.doFinal(transientBuffer, (short) 0, paddedLen, encryptedStudentInfo, (short) 0);
        encryptedInfoLength = paddedLen;
        plainInfoLength = len;
        JCSystem.commitTransaction();

        Util.arrayFillNonAtomic(transientBuffer, (short) 0, paddedLen, (byte) 0);
    }

    private void handleGetStudentInfo(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        byte p1 = buffer[ISO7816.OFFSET_P1];

        if (encryptedInfoLength == 0) {
            ISOException.throwIt(ISO7816.SW_RECORD_NOT_FOUND);
        }

        if (p1 == (byte) 0x01) {
            // Return raw AES-128 ciphertext stored in EEPROM for Security Lab inspection
            Util.arrayCopyNonAtomic(encryptedStudentInfo, (short) 0, buffer, (short) 0, encryptedInfoLength);
            apdu.setOutgoingAndSend((short) 0, encryptedInfoLength);
            return;
        }

        requirePinValidated();
        aesCipher.init(aesKey, Cipher.MODE_DECRYPT, zeroIv, (short) 0, (short) 16);
        aesCipher.doFinal(encryptedStudentInfo, (short) 0, encryptedInfoLength, buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, plainInfoLength);
    }

    // =========================================================================
    // GROUP 4: WEBP AVATAR CHUNK TRANSFER (SC-05 & OPT-02)
    // =========================================================================

    private void handleUploadAvatarChunk(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        short chunkLen = apdu.setIncomingAndReceive();
        short offset = Util.getShort(buffer, ISO7816.OFFSET_P1);

        if ((short) (offset + chunkLen) > MAX_AVATAR_SIZE) {
            ISOException.throwIt(ISO7816.SW_FILE_FULL);
        }

        Util.arrayCopyNonAtomic(buffer, ISO7816.OFFSET_CDATA, avatarBuffer, offset, chunkLen);
        short newEnd = (short) (offset + chunkLen);
        if (newEnd > avatarLength || offset == 0) {
            avatarLength = newEnd;
        }
    }

    private void handleDownloadAvatarChunk(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short offset = Util.getShort(buffer, ISO7816.OFFSET_P1);
        if (offset >= avatarLength) {
            apdu.setOutgoingAndSend((short) 0, (short) 0);
            return;
        }

        short remaining = (short) (avatarLength - offset);
        short maxChunk = (short) 240;
        short toSend = (remaining < maxChunk) ? remaining : maxChunk;

        apdu.setOutgoing();
        apdu.setOutgoingLength(toSend);
        apdu.sendBytesLong(avatarBuffer, offset, toSend);
    }

    // =========================================================================
    // GROUP 5: ATTENDANCE, LIBRARY (ATOMIC) & CIRCULAR LOG (SC-06)
    // =========================================================================

    private void handleGetAcademicAndLibraryStatus(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        short pos = 0;

        Util.setShort(buffer, pos, trainingScore);
        pos += 2;
        Util.setShort(buffer, pos, attendanceCount);
        pos += 2;
        buffer[pos++] = labAccessPermission;
        buffer[pos++] = borrowedBookCount;

        for (byte i = 0; i < borrowedBookCount; i++) {
            Util.setShort(buffer, pos, borrowedBooks[i]);
            pos += 2;
        }

        Util.arrayCopyNonAtomic(walletBalance, (short) 0, buffer, pos, (short) 4);
        pos += 4;

        apdu.setOutgoingAndSend((short) 0, pos);
    }

    private void handleBorrowOrAttendance(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();
        if (dataLen < 6) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        byte actionType = buffer[ISO7816.OFFSET_P1];
        short itemCode = Util.getShort(buffer, ISO7816.OFFSET_CDATA);

        JCSystem.beginTransaction();
        if (actionType == (byte) 0x01) {
            // Class attendance check-in
            attendanceCount++;
            if (trainingScore < 100) {
                trainingScore++;
            }
        } else if (actionType == (byte) 0x02) {
            // Borrow library book
            if (borrowedBookCount >= MAX_BORROWED_BOOKS) {
                JCSystem.abortTransaction();
                ISOException.throwIt(ISO7816.SW_FILE_FULL);
            }
            borrowedBooks[borrowedBookCount++] = itemCode;
        } else {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        appendActivityLog(actionType, buffer, ISO7816.OFFSET_CDATA);
        JCSystem.commitTransaction();

        // Sign the transaction payload with RSA-1024 for server-side non-repudiation
        rsaSigner.init(rsaPrivateKey, Signature.MODE_SIGN);
        short sigLen = rsaSigner.sign(buffer, ISO7816.OFFSET_CDATA, dataLen, buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    private void handleReturnOrLabAccess(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();
        if (dataLen < 6) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        byte p1 = buffer[ISO7816.OFFSET_P1];
        short itemCode = Util.getShort(buffer, ISO7816.OFFSET_CDATA);

        JCSystem.beginTransaction();
        if (p1 == (byte) 0x01) {
            // Return borrowed book
            byte foundIndex = -1;
            for (byte i = 0; i < borrowedBookCount; i++) {
                if (borrowedBooks[i] == itemCode) {
                    foundIndex = i;
                    break;
                }
            }
            if (foundIndex >= 0) {
                for (byte i = foundIndex; i < (byte) (borrowedBookCount - 1); i++) {
                    borrowedBooks[i] = borrowedBooks[(byte) (i + 1)];
                }
                borrowedBookCount--;
            }
            appendActivityLog((byte) 0x03, buffer, ISO7816.OFFSET_CDATA);
        } else if (p1 == (byte) 0x02) {
            // Verify Lab room access permission
            if (labAccessPermission == 0) {
                JCSystem.abortTransaction();
                ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
            }
            appendActivityLog((byte) 0x04, buffer, ISO7816.OFFSET_CDATA);
        } else {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        JCSystem.commitTransaction();

        rsaSigner.init(rsaPrivateKey, Signature.MODE_SIGN);
        short sigLen = rsaSigner.sign(buffer, ISO7816.OFFSET_CDATA, dataLen, buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    private void handleGetActivityLog(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        short outOffset = 0;
        buffer[outOffset++] = logCount;

        // Read from newest entry to oldest entry in the circular buffer
        for (byte i = 0; i < logCount; i++) {
            short idx = (short) ((logHeadIndex - 1 - i + MAX_LOG_ENTRIES) % MAX_LOG_ENTRIES);
            short srcPos = (short) (idx * LOG_ENTRY_SIZE);
            Util.arrayCopyNonAtomic(activityLogBuffer, srcPos, buffer, outOffset, LOG_ENTRY_SIZE);
            outOffset += LOG_ENTRY_SIZE;
        }

        apdu.setOutgoingAndSend((short) 0, outOffset);
    }

    // =========================================================================
    // GROUP 6: E-WALLET SUBSYSTEM (ATOMIC BALANCE + RSA-1024 SIGNATURE)
    // =========================================================================

    private void processWalletCredit(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();
        if (dataLen < 8) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        if (isNonPositiveAmount(buffer, ISO7816.OFFSET_CDATA)) {
            ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        }

        short carry = 0;
        for (byte i = 3; i >= 0; i--) {
            short sum = (short) ((walletBalance[i] & 0xFF)
                    + (buffer[(short) (ISO7816.OFFSET_CDATA + i)] & 0xFF)
                    + carry);
            transientBuffer[i] = (byte) (sum & 0xFF);
            carry = (short) ((sum >> 8) & 0x01);
        }
        if (carry != 0 || (transientBuffer[0] & (byte) 0x80) != 0 || exceedsMaxWalletBalance(transientBuffer)) {
            ISOException.throwIt(ISO7816.SW_FILE_FULL);
        }

        transientBuffer[16] = (byte) 0x00;
        transientBuffer[17] = (byte) 0x40;
        Util.arrayCopyNonAtomic(buffer, (short) (ISO7816.OFFSET_CDATA + 4), transientBuffer, (short) 18, (short) 4);

        JCSystem.beginTransaction();
        Util.arrayCopy(transientBuffer, (short) 0, walletBalance, (short) 0, (short) 4);
        appendActivityLog((byte) 0x05, transientBuffer, (short) 16);
        JCSystem.commitTransaction();

        signCanonicalWalletReceipt(apdu, INS_WALLET_CREDIT, buffer);
    }

    private void processWalletDebit(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();
        if (dataLen < 8) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        if (isNonPositiveAmount(buffer, ISO7816.OFFSET_CDATA)) {
            ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        }

        for (byte i = 0; i < 4; i++) {
            short balByte = (short) (walletBalance[i] & 0xFF);
            short amtByte = (short) (buffer[(short) (ISO7816.OFFSET_CDATA + i)] & 0xFF);
            if (balByte < amtByte) {
                ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
            } else if (balByte > amtByte) {
                break;
            }
        }

        short borrow = 0;
        for (byte i = 3; i >= 0; i--) {
            short diff = (short) ((walletBalance[i] & 0xFF)
                    - (buffer[(short) (ISO7816.OFFSET_CDATA + i)] & 0xFF)
                    - borrow);
            if (diff < 0) {
                diff += (short) 256;
                borrow = 1;
            } else {
                borrow = 0;
            }
            transientBuffer[i] = (byte) (diff & 0xFF);
        }

        transientBuffer[16] = (byte) 0x00;
        transientBuffer[17] = (byte) 0x41;
        Util.arrayCopyNonAtomic(buffer, (short) (ISO7816.OFFSET_CDATA + 4), transientBuffer, (short) 18, (short) 4);

        JCSystem.beginTransaction();
        Util.arrayCopy(transientBuffer, (short) 0, walletBalance, (short) 0, (short) 4);
        appendActivityLog((byte) 0x06, transientBuffer, (short) 16);
        JCSystem.commitTransaction();

        signCanonicalWalletReceipt(apdu, INS_WALLET_DEBIT, buffer);
    }

    private void signCanonicalWalletReceipt(APDU apdu, byte insCode, byte[] apduBuffer) {
        // Canonical 13-byte receipt: [INS 1B][Amount 4B][NewBalance 4B][Timestamp 4B]
        transientBuffer[32] = insCode;
        Util.arrayCopyNonAtomic(apduBuffer, ISO7816.OFFSET_CDATA, transientBuffer, (short) 33, (short) 4);
        Util.arrayCopyNonAtomic(walletBalance, (short) 0, transientBuffer, (short) 37, (short) 4);
        Util.arrayCopyNonAtomic(apduBuffer, (short) (ISO7816.OFFSET_CDATA + 4), transientBuffer, (short) 41, (short) 4);

        rsaSigner.init(rsaPrivateKey, Signature.MODE_SIGN);
        short sigLen = rsaSigner.sign(transientBuffer, (short) 32, (short) 13, apduBuffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    private boolean exceedsMaxWalletBalance(byte[] balBuf) {
        for (byte i = 0; i < 4; i++) {
            short val = (short) (balBuf[i] & 0xFF);
            short cap = (short) (MAX_WALLET_BALANCE[i] & 0xFF);
            if (val > cap) {
                return true;
            }
            if (val < cap) {
                return false;
            }
        }
        return false;
    }

    private void processWalletGetBalance(APDU apdu) {
        requirePinValidated();
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopyNonAtomic(walletBalance, (short) 0, buffer, (short) 0, (short) 4);
        apdu.setOutgoingAndSend((short) 0, (short) 4);
    }

    private boolean isNonPositiveAmount(byte[] buf, short offset) {
        if ((buf[offset] & (byte) 0x80) != 0) {
            return true;
        }
        return buf[offset] == 0
                && buf[(short) (offset + 1)] == 0
                && buf[(short) (offset + 2)] == 0
                && buf[(short) (offset + 3)] == 0;
    }

    private void appendActivityLog(byte actionType, byte[] dataBuf, short dataOffset) {
        short destPos = (short) (logHeadIndex * LOG_ENTRY_SIZE);
        activityLogBuffer[destPos] = actionType;
        Util.arrayCopyNonAtomic(dataBuf, dataOffset, activityLogBuffer, (short) (destPos + 1), (short) 6);

        logHeadIndex = (byte) ((logHeadIndex + 1) % MAX_LOG_ENTRIES);
        if (logCount < MAX_LOG_ENTRIES) {
            logCount++;
        }
    }

    private void requirePinValidated() {
        if (adminLocked || !pin.isValidated()) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
    }
}
