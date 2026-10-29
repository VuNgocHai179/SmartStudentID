package com.example.smartstudentid.apdu;

/**
 * Encapsulates the response of an APDU command execution.
 */
public class CardResponse {
    private final byte[] data;
    private final int sw;
    private final long executionTimeMs;
    private final String rawCommandHex;
    private final String rawResponseHex;

    public CardResponse(byte[] data, int sw, long executionTimeMs, String rawCommandHex, String rawResponseHex) {
        this.data = data != null ? data : new byte[0];
        this.sw = sw;
        this.executionTimeMs = executionTimeMs;
        this.rawCommandHex = rawCommandHex;
        this.rawResponseHex = rawResponseHex;
    }

    public byte[] getData() {
        return data;
    }

    public int getSw() {
        return sw;
    }

    public boolean isSuccess() {
        return sw == ApduCommands.SW_NO_ERROR;
    }

    public boolean isCardLocked() {
        return sw == ApduCommands.SW_AUTHENTICATION_METHOD_BLOCKED;
    }

    /**
     * Returns the number of remaining PIN attempts if the status word matches 63CX, or -1 otherwise.
     */
    public int getRemainingPinTries() {
        if ((sw & 0xFFF0) == ApduCommands.SW_PIN_TRIES_PREFIX) {
            return sw & 0x000F;
        }
        if (isCardLocked()) {
            return 0;
        }
        return -1;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public String getRawCommandHex() {
        return rawCommandHex;
    }

    public String getRawResponseHex() {
        return rawResponseHex;
    }

    public String getSwHex() {
        return String.format("%04X", sw & 0xFFFF);
    }
}
