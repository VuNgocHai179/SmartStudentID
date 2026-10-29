package com.example.smartstudentid.model;

/**
 * Represents one of the 10 latest entries stored in the smart card's on-chip circular buffer (INS 0x33).
 */
public class OnCardActivityLog {
    private final int actionType;
    private final int itemCode;
    private final long timestampSeconds;

    public OnCardActivityLog(int actionType, int itemCode, long timestampSeconds) {
        this.actionType = actionType;
        this.itemCode = itemCode;
        this.timestampSeconds = timestampSeconds;
    }

    public int getActionType() {
        return actionType;
    }

    public int getItemCode() {
        return itemCode;
    }

    public long getTimestampSeconds() {
        return timestampSeconds;
    }

    public String getActionTitle() {
        switch (actionType) {
            case 1:
                return "Class Attendance Check-In";
            case 2:
                return "Library Book Borrowed";
            case 3:
                return "Library Book Returned";
            case 4:
                return "Lab Room Door Access";
            default:
                return "Card Transaction";
        }
    }
}
