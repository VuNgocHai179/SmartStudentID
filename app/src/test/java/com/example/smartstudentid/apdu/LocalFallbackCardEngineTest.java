package com.example.smartstudentid.apdu;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Unit tests verifying LocalFallbackCardEngine matches BridgeServer & SmartCampusApplet
 * on PIN-derived AES-128-CBC re-encryption and RSA-1024 signature verification.
 */
public class LocalFallbackCardEngineTest {

    private static int extractSw(byte[] resp) {
        assertTrue("Response shorter than 2 bytes", resp != null && resp.length >= 2);
        return ((resp[resp.length - 2] & 0xFF) << 8) | (resp[resp.length - 1] & 0xFF);
    }

    private static byte[] extractData(byte[] resp) {
        return Arrays.copyOf(resp, resp.length - 2);
    }

    private static byte[] buildApdu(byte cla, byte ins, byte p1, byte p2, byte[] data) {
        int lc = data != null ? data.length : 0;
        byte[] cmd = new byte[5 + lc];
        cmd[0] = cla;
        cmd[1] = ins;
        cmd[2] = p1;
        cmd[3] = p2;
        cmd[4] = (byte) lc;
        if (lc > 0) {
            System.arraycopy(data, 0, cmd, 5, lc);
        }
        return cmd;
    }

    @Test
    public void pinChangeAndPuk_reencryptsAes128CbcDeterministically() {
        ApduClient.LocalFallbackCardEngine engine = new ApduClient.LocalFallbackCardEngine();

        byte[] verifyDefault = engine.processApdu(buildApdu((byte) 0x80, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                "123456".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0x9000, extractSw(verifyDefault));

        byte[] plainBefore = extractData(engine.processApdu(buildApdu((byte) 0x80, (byte) 0x21, (byte) 0x00, (byte) 0x00, null)));
        byte[] cipherBefore = extractData(engine.processApdu(buildApdu((byte) 0x80, (byte) 0x21, (byte) 0x01, (byte) 0x00, null)));
        assertTrue(plainBefore.length > 0);
        assertEquals(0, cipherBefore.length % 16);

        // Change PIN: 123456 -> 654321
        byte[] oldPin = "123456".getBytes(StandardCharsets.UTF_8);
        byte[] newPin = "654321".getBytes(StandardCharsets.UTF_8);
        byte[] changePayload = new byte[2 + oldPin.length + newPin.length];
        changePayload[0] = (byte) oldPin.length;
        System.arraycopy(oldPin, 0, changePayload, 1, oldPin.length);
        changePayload[1 + oldPin.length] = (byte) newPin.length;
        System.arraycopy(newPin, 0, changePayload, 2 + oldPin.length, newPin.length);

        assertEquals(0x9000, extractSw(engine.processApdu(
                buildApdu((byte) 0x80, (byte) 0x11, (byte) 0x00, (byte) 0x00, changePayload))));

        // Old PIN fails; new PIN passes
        assertEquals(0x63C2, extractSw(engine.processApdu(
                buildApdu((byte) 0x80, (byte) 0x10, (byte) 0x00, (byte) 0x00, oldPin))));
        assertEquals(0x9000, extractSw(engine.processApdu(
                buildApdu((byte) 0x80, (byte) 0x10, (byte) 0x00, (byte) 0x00, newPin))));

        byte[] cipherAfter = extractData(engine.processApdu(buildApdu((byte) 0x80, (byte) 0x21, (byte) 0x01, (byte) 0x00, null)));
        byte[] plainAfter = extractData(engine.processApdu(buildApdu((byte) 0x80, (byte) 0x21, (byte) 0x00, (byte) 0x00, null)));
        assertFalse("Ciphertext must change when PIN changes", Arrays.equals(cipherBefore, cipherAfter));
        assertArrayEquals("Plaintext must survive PIN re-encryption intact", plainBefore, plainAfter);

        // Unblock via PUK restores PIN "123456" and exact original ciphertext
        assertEquals(0x9000, extractSw(engine.processApdu(
                buildApdu((byte) 0x80, (byte) 0x12, (byte) 0x00, (byte) 0x00,
                        "88888888".getBytes(StandardCharsets.UTF_8)))));
        byte[] cipherRestored = extractData(engine.processApdu(buildApdu((byte) 0x80, (byte) 0x21, (byte) 0x01, (byte) 0x00, null)));
        assertArrayEquals(cipherBefore, cipherRestored);
    }

    @Test
    public void attendanceAction_producesValidRsa1024Signature() throws Exception {
        ApduClient.LocalFallbackCardEngine engine = new ApduClient.LocalFallbackCardEngine();
        assertEquals(0x9000, extractSw(engine.processApdu(buildApdu(
                (byte) 0x80, (byte) 0x10, (byte) 0x00, (byte) 0x00, "123456".getBytes(StandardCharsets.UTF_8)))));

        byte[] modBytes = extractData(engine.processApdu(buildApdu((byte) 0x80, (byte) 0x13, (byte) 0x01, (byte) 0x00, null)));
        byte[] expBytes = extractData(engine.processApdu(buildApdu((byte) 0x80, (byte) 0x13, (byte) 0x02, (byte) 0x00, null)));

        byte[] payload = new byte[]{0x01, 0x01, 0x67, 0x3F, 0x12, 0x34};
        byte[] sigResp = engine.processApdu(buildApdu((byte) 0x80, (byte) 0x31, (byte) 0x01, (byte) 0x00, payload));
        assertEquals(0x9000, extractSw(sigResp));
        byte[] sigBytes = extractData(sigResp);
        assertEquals(128, sigBytes.length);

        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(new BigInteger(1, modBytes), new BigInteger(1, expBytes)));
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(pub);
        verifier.update(payload);
        assertTrue(verifier.verify(sigBytes));
    }
}
