package com.example.smartstudentid.model;

/**
 * Response model for /api/attendance/precheck and /api/attendance/checkin.
 */
public class AttendanceCheckInResult {
    private boolean ok;
    private boolean saved;
    private boolean verifiedByServer;
    private String errorCode;
    private String message;
    private int sessionId;
    private String courseCode;
    private String courseName;
    private String method;
    private long timeSlot;
    private long serverNowSec;
    private int attendanceCount;
    private int trainingScore;

    public AttendanceCheckInResult() {
    }

    public static AttendanceCheckInResult error(String code, String msg) {
        AttendanceCheckInResult res = new AttendanceCheckInResult();
        res.ok = false;
        res.saved = false;
        res.verifiedByServer = false;
        res.errorCode = code != null ? code : "ERROR";
        res.message = msg != null ? msg : "Điểm danh thất bại.";
        return res;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public boolean isSaved() {
        return saved;
    }

    public void setSaved(boolean saved) {
        this.saved = saved;
    }

    public boolean isVerifiedByServer() {
        return verifiedByServer;
    }

    public void setVerifiedByServer(boolean verifiedByServer) {
        this.verifiedByServer = verifiedByServer;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public long getTimeSlot() {
        return timeSlot;
    }

    public void setTimeSlot(long timeSlot) {
        this.timeSlot = timeSlot;
    }

    public long getServerNowSec() {
        return serverNowSec;
    }

    public void setServerNowSec(long serverNowSec) {
        this.serverNowSec = serverNowSec;
    }

    public int getAttendanceCount() {
        return attendanceCount;
    }

    public void setAttendanceCount(int attendanceCount) {
        this.attendanceCount = attendanceCount;
    }

    public int getTrainingScore() {
        return trainingScore;
    }

    public void setTrainingScore(int trainingScore) {
        this.trainingScore = trainingScore;
    }
}
