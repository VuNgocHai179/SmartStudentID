package com.example.smartstudentid.model;

/**
 * Represents a banking-grade RSA-1024 signed E-Wallet ledger entry (CREDIT / DEBIT)
 * synchronized with `wallet_transactions` in SQLite.
 */
public class WalletTransactionRecord {
    private long id;
    private String studentId;
    private String txType;
    private int amount;
    private int balanceBefore;
    private int balanceAfter;
    private String txRef;
    private String counterparty;
    private String description;
    private long timestampSeconds;
    private String rsaSignatureHex;
    private boolean verifiedByServer;

    public WalletTransactionRecord() {
    }

    public WalletTransactionRecord(String studentId, String txType, int amount, int balanceAfter,
                                   String description, long timestampSeconds,
                                   String rsaSignatureHex, boolean verifiedByServer) {
        this(studentId, txType, amount,
                "CREDIT".equalsIgnoreCase(txType) ? Math.max(0, balanceAfter - amount) : balanceAfter + amount,
                balanceAfter, "", "", description, timestampSeconds, rsaSignatureHex, verifiedByServer);
    }

    public WalletTransactionRecord(String studentId, String txType, int amount, int balanceBefore,
                                   int balanceAfter, String txRef, String counterparty,
                                   String description, long timestampSeconds,
                                   String rsaSignatureHex, boolean verifiedByServer) {
        this.studentId = studentId;
        this.txType = txType;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.txRef = txRef;
        this.counterparty = counterparty;
        this.description = description;
        this.timestampSeconds = timestampSeconds;
        this.rsaSignatureHex = rsaSignatureHex;
        this.verifiedByServer = verifiedByServer;
    }

    public boolean isCredit() {
        return "CREDIT".equalsIgnoreCase(txType);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getTxType() {
        return txType;
    }

    public void setTxType(String txType) {
        this.txType = txType;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public int getBalanceBefore() {
        return balanceBefore;
    }

    public void setBalanceBefore(int balanceBefore) {
        this.balanceBefore = balanceBefore;
    }

    public int getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(int balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public String getTxRef() {
        return txRef;
    }

    public void setTxRef(String txRef) {
        this.txRef = txRef;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(String counterparty) {
        this.counterparty = counterparty;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
