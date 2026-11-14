package com.example.smartstudentid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.example.smartstudentid.security.CryptoManager;

import org.junit.Test;

import javax.crypto.SecretKey;

/**
 * §5.2 zeroization: the PBKDF2 path must wipe the PIN char[] and stay deterministic per salt.
 */
public class CryptoZeroizationTest {

    @Test
    public void deriveKeyFromPinZeroizesPinChars() throws Exception {
        char[] pin = "123456".toCharArray();
        SecretKey key = CryptoManager.deriveKeyFromPin(pin, new byte[16]);

        assertEquals(32, key.getEncoded().length);
        assertArrayEquals(new char[pin.length], pin);
    }

    @Test
    public void deriveKeyFromPinIsDeterministicForSameSalt() throws Exception {
        byte[] salt = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] first = CryptoManager.deriveKeyFromPin("123456".toCharArray(), salt).getEncoded();
        byte[] second = CryptoManager.deriveKeyFromPin("123456".toCharArray(), salt).getEncoded();

        assertArrayEquals(first, second);
        assertNotEquals(0, first.length);
    }
}
