package com.example.smartstudentid.model;

/**
 * Represents an active class attendance session opened by a lecturer on BridgeServer.
 */
public class AttendanceSessionRecord {
    private int sessionId;
    private String courseCode;
    private String courseName;
    private String room;
    private String lecturerName;
    private long openedAt;
    private long expiresAt;
    private long remainingSessionSec;
    private int rotationSeconds;
    private boolean alreadyCheckedIn;
    private long checkedInAt;
    private String checkinMethod;

    public AttendanceSessionRecord() {
    }

    public int getSessionId() {
        return sessionId;
    }

    public void setSessionId(int sessionId) {
        this.sessionId = sessionId;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public String getLecturerName() {
        return lecturerName;
    }

    public void setLecturerName(String lecturerName) {
        this.lecturerName = lecturerName;
    }

    public long getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(long openedAt) {
        this.openedAt = openedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getRemainingSessionSec() {
        return remainingSessionSec;
    }

    public void setRemainingSessionSec(long remainingSessionSec) {
        this.remainingSessionSec = remainingSessionSec;
    }

    public int getRotationSeconds() {
        return rotationSeconds;
    }

    public void setRotationSeconds(int rotationSeconds) {
        this.rotationSeconds = rotationSeconds;
    }

    public boolean isAlreadyCheckedIn() {
        return alreadyCheckedIn;
    }

    public void setAlreadyCheckedIn(boolean alreadyCheckedIn) {
        this.alreadyCheckedIn = alreadyCheckedIn;
    }

    public long getCheckedInAt() {
        return checkedInAt;
    }

    public void setCheckedInAt(long checkedInAt) {
        this.checkedInAt = checkedInAt;
    }

    public String getCheckinMethod() {
        return checkinMethod;
    }

    public void setCheckinMethod(String checkinMethod) {
        this.checkinMethod = checkinMethod;
    }
}
