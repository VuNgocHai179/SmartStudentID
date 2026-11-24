package com.example.smartstudentid.model;

/**
 * Represents a student's enrolled course and cumulative attendance progress for the semester.
 */
public class CourseAttendanceProgress {
    private String courseCode;
    private String courseName;
    private String lecturerName;
    private String room;
    private String semester;
    private String scheduleText;
    private int attendedSessions;
    private int totalSessions;
    private long lastAttendedAt;

    public CourseAttendanceProgress() {
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

    public String getLecturerName() {
        return lecturerName;
    }

    public void setLecturerName(String lecturerName) {
        this.lecturerName = lecturerName;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public String getSemester() {
        return semester;
    }

    public void setSemester(String semester) {
        this.semester = semester;
    }

    public String getScheduleText() {
        return scheduleText;
    }

    public void setScheduleText(String scheduleText) {
        this.scheduleText = scheduleText;
    }

    public int getAttendedSessions() {
        return attendedSessions;
    }

    public void setAttendedSessions(int attendedSessions) {
        this.attendedSessions = attendedSessions;
    }

    public int getTotalSessions() {
        return totalSessions;
    }

    public void setTotalSessions(int totalSessions) {
        this.totalSessions = totalSessions;
    }

    public long getLastAttendedAt() {
        return lastAttendedAt;
    }

    public void setLastAttendedAt(long lastAttendedAt) {
        this.lastAttendedAt = lastAttendedAt;
    }
}
