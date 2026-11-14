package com.example.smartstudentid.security;

import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.apdu.ApduCommands;
import com.example.smartstudentid.apdu.CardResponse;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;

/**
 * Implements RSA-1024 Challenge-Response card authentication and digital signature verification
 * to prevent card cloning and proxy attendance fraud (SEC-03 / MASVS-AUTH & MASVS-CRYPTO).
 */
public class RsaCardVerifier {

    private final ApduClient apduClient;

    public RsaCardVerifier() {
        this.apduClient = ApduClient.getInstance();
    }

    /**
     * Executes a full 16-byte random Nonce Challenge-Response against the connected smart card:
     * 1. Reads Modulus N (INS 0x13, P1=01) and Exponent E (INS 0x13, P1=02) from the card.
     * 2. Generates a 16-byte random challenge nonce.
     * 3. Requests the card to sign the nonce with its on-chip RSA-1024 Private Key (INS 0x14).
     * 4. Verifies the returned signature using SHA1withRSA.
     */
    public VerificationResult performChallengeResponse() {
        // Fetch Modulus N (128 bytes)
        CardResponse modResp = apduClient.sendApdu(
                ApduCommands.CLA_SMART_STUDENT,
                ApduCommands.INS_GET_PUBLIC_KEY,
                ApduCommands.P1_GET_MODULUS,
                (byte) 0x00,
                null
        );
        // Fetch Exponent E (3 bytes: 01 00 01)
        CardResponse expResp = apduClient.sendApdu(
                ApduCommands.CLA_SMART_STUDENT,
                ApduCommands.INS_GET_PUBLIC_KEY,
                ApduCommands.P1_GET_EXPONENT,
                (byte) 0x00,
                null
        );

        if (!modResp.isSuccess() || !expResp.isSuccess()
                || modResp.getData().length == 0 || expResp.getData().length == 0) {
            return new VerificationResult(false, "", "", "", "Failed to retrieve RSA Public Key from card");
        }

        byte[] nonce = CryptoManager.generateRandomBytes(16);
        CardResponse sigResp = apduClient.sendApdu(
                ApduCommands.CLA_SMART_STUDENT,
                ApduCommands.INS_SIGN_CHALLENGE,
                (byte) 0x00,
                (byte) 0x00,
                nonce
        );

        if (!sigResp.isSuccess() || sigResp.getData().length == 0) {
            return new VerificationResult(false, "", "", "", "Card failed to sign challenge nonce");
        }

        long verifyStartNs = System.nanoTime();
        boolean valid = verifySignature(modResp.getData(), expResp.getData(), nonce, sigResp.getData());
        long verifyMicros = Math.max(1, (System.nanoTime() - verifyStartNs) / 1_000);
        return new VerificationResult(
                valid,
                ApduCommands.bytesToHex(modResp.getData()),
                ApduCommands.bytesToHex(expResp.getData()),
                ApduCommands.bytesToHex(sigResp.getData()),
                valid ? "RSA-1024 Challenge-Response Authenticated" : "Signature Verification Failed",
                verifyMicros
        );
    }

    /**
     * Verifies an RSA-1024 SHA-1 PKCS#1 v1.5 signature produced by the Java Card Applet.
     */
    public boolean verifySignature(byte[] modulusBytes, byte[] exponentBytes,
                                   byte[] payload, byte[] signatureBytes) {
        try {
            BigInteger modulus = new BigInteger(1, modulusBytes);
            BigInteger exponent = new BigInteger(1, exponentBytes);
            RSAPublicKeySpec keySpec = new RSAPublicKeySpec(modulus, exponent);
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);

            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    public static class VerificationResult {
        private final boolean verified;
        private final String modulusHex;
        private final String exponentHex;
        private final String signatureHex;
        private final String message;
        private final long verifyMicros;

        public VerificationResult(boolean verified, String modulusHex, String exponentHex,
                                  String signatureHex, String message) {
            this(verified, modulusHex, exponentHex, signatureHex, message, 0);
        }

        public VerificationResult(boolean verified, String modulusHex, String exponentHex,
                                  String signatureHex, String message, long verifyMicros) {
            this.verified = verified;
            this.modulusHex = modulusHex;
            this.exponentHex = exponentHex;
            this.signatureHex = signatureHex;
            this.message = message;
            this.verifyMicros = verifyMicros;
        }

        public boolean isVerified() {
            return verified;
        }

        public String getModulusHex() {
            return modulusHex;
        }

        public String getExponentHex() {
            return exponentHex;
        }

        public String getSignatureHex() {
            return signatureHex;
        }

        public String getMessage() {
            return message;
        }

        /** Time spent in the on-device SHA1withRSA verification (microseconds). */
        public long getVerifyMicros() {
            return verifyMicros;
        }
    }
}
