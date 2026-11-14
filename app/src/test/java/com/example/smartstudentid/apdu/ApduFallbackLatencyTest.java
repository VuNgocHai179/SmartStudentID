package com.example.smartstudentid.apdu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies ApduClient offline cooldown, hardware delay simulation, and TelemetryListener callbacks.
 */
public class ApduFallbackLatencyTest {

    @Test
    public void sendApduNotifiesTelemetryAndAvoidsMultiSecondTimeoutLoop() {
        List<Integer> seenIns = new ArrayList<>();
        ApduClient.TelemetryListener listener = (ins, elapsedMs) -> seenIns.add(ins);
        ApduClient client = ApduClient.getInstance();
        client.setTelemetryListener(listener);
        try {
            CardResponse select = client.sendApdu(ApduCommands.CLA_ISO, ApduCommands.INS_SELECT,
                    (byte) 0x04, (byte) 0x00, ApduCommands.APPLET_AID);
            CardResponse verify = client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_VERIFY_PIN,
                    (byte) 0x00, (byte) 0x00, "123456".getBytes(StandardCharsets.UTF_8));
            CardResponse info = client.sendApdu(ApduCommands.CLA_SMART_STUDENT, ApduCommands.INS_GET_STUDENT_INFO,
                    ApduCommands.P1_INFO_DECRYPTED, (byte) 0x00, null);

            assertTrue(select.isSuccess());
            assertTrue(verify.isSuccess());
            assertTrue(info.isSuccess());
            assertEquals(3, seenIns.size());
            assertEquals(0xA4, (int) seenIns.get(0));
            assertEquals(0x10, (int) seenIns.get(1));
            assertEquals(0x21, (int) seenIns.get(2));
            assertTrue("VERIFY PIN should not stall on network timeouts", verify.getExecutionTimeMs() < 500);
            assertTrue("GET INFO should not stall on network timeouts", info.getExecutionTimeMs() < 500);
        } finally {
            client.clearTelemetryListener(listener);
        }
    }
}
