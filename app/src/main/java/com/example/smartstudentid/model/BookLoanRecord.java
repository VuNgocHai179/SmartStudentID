package com.example.smartstudentid.model;

/**
 * Represents a library book loan transaction stored atomically on the smart card.
 */
public class BookLoanRecord {
    private int bookCode;
    private String bookTitle;
    private String author;
    private boolean borrowed;
    private long timestampSeconds;
    private String rsaSignatureHex;

    public BookLoanRecord() {
    }

    public BookLoanRecord(int bookCode, String bookTitle, String author, boolean borrowed,
                          long timestampSeconds, String rsaSignatureHex) {
        this.bookCode = bookCode;
        this.bookTitle = bookTitle;
        this.author = author;
        this.borrowed = borrowed;
        this.timestampSeconds = timestampSeconds;
        this.rsaSignatureHex = rsaSignatureHex;
    }

    public int getBookCode() {
        return bookCode;
    }

    public void setBookCode(int bookCode) {
        this.bookCode = bookCode;
    }

    public String getBookTitle() {
        return bookTitle;
    }

    public void setBookTitle(String bookTitle) {
        this.bookTitle = bookTitle;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public boolean isBorrowed() {
        return borrowed;
    }

    public void setBorrowed(boolean borrowed) {
        this.borrowed = borrowed;
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
}
