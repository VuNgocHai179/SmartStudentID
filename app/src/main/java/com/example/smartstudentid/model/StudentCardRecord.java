package com.example.smartstudentid.model;

/**
 * Represents a student's smart card record stored on the card and synchronized with the server.
 */
public class StudentCardRecord {
    private String studentId;
    private String fullName;
    private String dob;
    private String className;
    private String department;
    private String defaultPin;
    private boolean cardLocked;
    private int trainingScore;
    private int attendanceCount;
    private boolean labAccessGranted;
    private String publicKeyModulusHex;
    private String publicKeyExponentHex;
    private String rawAesCiphertextHex;

    public StudentCardRecord() {
    }

    public StudentCardRecord(String studentId, String fullName, String dob, String className,
                             String department, String defaultPin, boolean cardLocked,
                             int trainingScore, int attendanceCount) {
        this.studentId = studentId;
        this.fullName = fullName;
        this.dob = dob;
        this.className = className;
        this.department = department;
        this.defaultPin = defaultPin;
        this.cardLocked = cardLocked;
        this.trainingScore = trainingScore;
        this.attendanceCount = attendanceCount;
        this.labAccessGranted = true;
    }

    /**
     * Parses a pipe-delimited UTF-8 string returned by INS 0x21 (MSSV|HoTen|NgaySinh|Lop|Khoa).
     */
    public static StudentCardRecord fromPipeDelimitedString(String raw) {
        StudentCardRecord record = new StudentCardRecord();
        if (raw == null || raw.isEmpty()) {
            return record;
        }
        String[] parts = raw.split("\\|");
        if (parts.length > 0) record.setStudentId(parts[0]);
        if (parts.length > 1) record.setFullName(parts[1]);
        if (parts.length > 2) record.setDob(parts[2]);
        if (parts.length > 3) record.setClassName(parts[3]);
        if (parts.length > 4) record.setDepartment(parts[4]);
        record.setTrainingScore(88);
        record.setAttendanceCount(12);
        record.setLabAccessGranted(true);
        return record;
    }

    /**
     * Formats the profile into a pipe-delimited string for INS 0x20 encryption on the card.
     */
    public String toPipeDelimitedString() {
        return safe(studentId) + "|" + safe(fullName) + "|" + safe(dob) + "|"
                + safe(className) + "|" + safe(department);
    }

    private String safe(String val) {
        return val != null ? val.replace("|", " ").trim() : "";
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getDob() {
        return dob;
    }

    public void setDob(String dob) {
        this.dob = dob;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getDefaultPin() {
        return defaultPin;
    }

    public void setDefaultPin(String defaultPin) {
        this.defaultPin = defaultPin;
    }

    public boolean isCardLocked() {
        return cardLocked;
    }

    public void setCardLocked(boolean cardLocked) {
        this.cardLocked = cardLocked;
    }

    public int getTrainingScore() {
        return trainingScore;
    }

    public void setTrainingScore(int trainingScore) {
        this.trainingScore = trainingScore;
    }

    public int getAttendanceCount() {
        return attendanceCount;
    }

    public void setAttendanceCount(int attendanceCount) {
        this.attendanceCount = attendanceCount;
    }

    public boolean isLabAccessGranted() {
        return labAccessGranted;
    }

    public void setLabAccessGranted(boolean labAccessGranted) {
        this.labAccessGranted = labAccessGranted;
    }

    public String getPublicKeyModulusHex() {
        return publicKeyModulusHex;
    }

    public void setPublicKeyModulusHex(String publicKeyModulusHex) {
        this.publicKeyModulusHex = publicKeyModulusHex;
    }

    public String getPublicKeyExponentHex() {
        return publicKeyExponentHex;
    }

    public void setPublicKeyExponentHex(String publicKeyExponentHex) {
        this.publicKeyExponentHex = publicKeyExponentHex;
    }

    public String getRawAesCiphertextHex() {
        return rawAesCiphertextHex;
    }

    public void setRawAesCiphertextHex(String rawAesCiphertextHex) {
        this.rawAesCiphertextHex = rawAesCiphertextHex;
    }
}
