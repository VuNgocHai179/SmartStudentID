package com.example.smartstudentid.model;

/**
 * Represents a class attendance check-in record signed by the smart card's RSA-1024 private key.
 */
public class AttendanceRecord {
    private String studentId;
    private String courseCode;
    private String courseName;
    private long timestampSeconds;
    private String rsaSignatureHex;
    private boolean verifiedByServer;

    public AttendanceRecord() {
    }

    public AttendanceRecord(String studentId, String courseCode, String courseName,
                            long timestampSeconds, String rsaSignatureHex, boolean verifiedByServer) {
        this.studentId = studentId;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.timestampSeconds = timestampSeconds;
        this.rsaSignatureHex = rsaSignatureHex;
        this.verifiedByServer = verifiedByServer;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
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

    public long getTimestampSeconds() {
        return timestampSeconds;
    }

    public void setTimestampSeconds(long timestampSeconds) {
        this.timestampSeconds = timestampSeconds;
    }

    public String getRsaSignatureHex() {
        return rsaSignatureHex;
    }

    public void setRsaSignatureHex(String rsaSignatureHex) {
        this.rsaSignatureHex = rsaSignatureHex;
    }

    public boolean isVerifiedByServer() {
        return verifiedByServer;
    }

    public void setVerifiedByServer(boolean verifiedByServer) {
        this.verifiedByServer = verifiedByServer;
    }
}
