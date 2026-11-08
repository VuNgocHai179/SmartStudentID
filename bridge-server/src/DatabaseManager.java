import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * ============================================================================
 * RELATIONAL SQLITE DATABASE MANAGER (smartcard.db)
 * ============================================================================
 * Manages persistent relational storage for the Smart Student ID system using
 * SQLite (JDBC) in 3NF schema with Foreign Key constraints and ACID transactions:
 *  1. cards             - Issued student smart cards, RSA-1024 public keys & status
 *  2. attendance_logs   - Class attendance records signed by on-card RSA private key
 *  3. book_loans        - Library book borrowing/returning records
 *  4. card_audit_logs   - System security & transaction audit trail
 */
public class DatabaseManager {

    private static final File DB_FILE = new File("bridge-server/data/smartcard.db");
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_FILE.getPath();

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("[SQLite] Warning: org.sqlite.JDBC driver not found in classpath: " + e.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        if (DB_FILE.getParentFile() != null && !DB_FILE.getParentFile().exists()) {
            DB_FILE.getParentFile().mkdirs();
        }
        Connection conn = DriverManager.getConnection(JDBC_URL);
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = ON;");
            pragma.execute("PRAGMA journal_mode = WAL;");
        }
        return conn;
    }

    /**
     * Creates all relational tables and seeds default records inside an ACID transaction.
     */
    public static synchronized void initDatabase() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                // 1. Table: cards (Primary entity for issued student smart cards)
                stmt.execute("CREATE TABLE IF NOT EXISTS cards ("
                        + "student_id VARCHAR(20) PRIMARY KEY, "
                        + "full_name VARCHAR(100) NOT NULL, "
                        + "dob VARCHAR(20) NOT NULL, "
                        + "class_name VARCHAR(50) NOT NULL, "
                        + "department VARCHAR(100) NOT NULL, "
                        + "default_pin VARCHAR(8) DEFAULT '123456', "
                        + "card_locked INTEGER DEFAULT 0, "
                        + "training_score INTEGER DEFAULT 85, "
                        + "attendance_count INTEGER DEFAULT 0, "
                        + "lab_access_granted INTEGER DEFAULT 1, "
                        + "public_key_modulus_hex TEXT, "
                        + "public_key_exponent_hex TEXT, "
                        + "raw_aes_ciphertext_hex TEXT, "
                        + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP"
                        + ");");

                // 2. Table: attendance_logs (RSA-1024 signed attendance history)
                stmt.execute("CREATE TABLE IF NOT EXISTS attendance_logs ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "student_id VARCHAR(20) NOT NULL, "
                        + "course_code VARCHAR(50) NOT NULL, "
                        + "course_name VARCHAR(150) NOT NULL, "
                        + "timestamp_seconds INTEGER NOT NULL, "
                        + "rsa_signature_hex TEXT NOT NULL, "
                        + "verified_by_server INTEGER DEFAULT 1, "
                        + "FOREIGN KEY (student_id) REFERENCES cards(student_id) ON DELETE CASCADE"
                        + ");");

                // 3. Table: book_loans (Library book inventory & borrowing state)
                stmt.execute("CREATE TABLE IF NOT EXISTS book_loans ("
                        + "book_code INTEGER PRIMARY KEY, "
                        + "student_id VARCHAR(20), "
                        + "book_title VARCHAR(200) NOT NULL, "
                        + "author VARCHAR(100) NOT NULL, "
                        + "borrowed INTEGER DEFAULT 0, "
                        + "timestamp_seconds INTEGER DEFAULT 0, "
                        + "rsa_signature_hex TEXT, "
                        + "FOREIGN KEY (student_id) REFERENCES cards(student_id) ON DELETE SET NULL"
                        + ");");

                // 4. Table: card_audit_logs (Security & APDU operation audit trail)
                stmt.execute("CREATE TABLE IF NOT EXISTS card_audit_logs ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "student_id VARCHAR(20) NOT NULL, "
                        + "action_type VARCHAR(50) NOT NULL, "
                        + "item_code INTEGER DEFAULT 0, "
                        + "description TEXT, "
                        + "rsa_signature_hex TEXT, "
                        + "timestamp_seconds INTEGER NOT NULL, "
                        + "FOREIGN KEY (student_id) REFERENCES cards(student_id) ON DELETE CASCADE"
                        + ");");

                // 5. Table: admin_accounts (Administrator accounts with salted SHA-256 hashes)
                stmt.execute("CREATE TABLE IF NOT EXISTS admin_accounts ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "username VARCHAR(50) UNIQUE NOT NULL, "
                        + "password_hash VARCHAR(128) NOT NULL, "
                        + "salt VARCHAR(64) NOT NULL, "
                        + "full_name VARCHAR(100) NOT NULL, "
                        + "role VARCHAR(50) DEFAULT 'SUPER_ADMIN', "
                        + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                        + "last_login DATETIME"
                        + ");");

                // Indexes for fast relational lookup
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_attendance_student ON attendance_logs(student_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_books_student ON book_loans(student_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_student ON card_audit_logs(student_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_admin_username ON admin_accounts(username);");
            }

            seedDefaultDataIfEmpty(conn);
            conn.commit();
            System.out.println("[SQLite DB] Initialized relational database at: " + DB_FILE.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[SQLite DB] Initialization error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void seedDefaultDataIfEmpty(Connection conn) throws SQLException {
        int cardCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM cards")) {
            if (rs.next()) {
                cardCount = rs.getInt(1);
            }
        }

        if (cardCount == 0) {
            String insertCardSql = "INSERT INTO cards (student_id, full_name, dob, class_name, department, "
                    + "default_pin, card_locked, training_score, attendance_count, lab_access_granted, "
                    + "public_key_modulus_hex, public_key_exponent_hex) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertCardSql)) {
                insertSeedCard(ps, "CT070315", "Vũ Ngọc Hải", "15/08/2003", "CT7C", "An toàn Thông tin",
                        "123456", 0, 88, 12, 1, "B4F29C01A8E3...", "010001");
                insertSeedCard(ps, "CT070301", "Nguyễn Minh Quân", "20/11/2003", "CT7C", "An toàn Thông tin",
                        "123456", 0, 85, 9, 1, "C9A14E77D20B...", "010001");
                insertSeedCard(ps, "CT070322", "Trần Thu Hà", "05/03/2003", "CT7C", "Công nghệ Thông tin",
                        "123456", 1, 92, 15, 1, "E1883B54F912...", "010001");
            }
        }

        int bookCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM book_loans")) {
            if (rs.next()) {
                bookCount = rs.getInt(1);
            }
        }

        if (bookCount == 0) {
            long now = System.currentTimeMillis() / 1000L;
            String insertBookSql = "INSERT INTO book_loans (book_code, student_id, book_title, author, "
                    + "borrowed, timestamp_seconds, rsa_signature_hex) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertBookSql)) {
                insertSeedBook(ps, 0x0102, "CT070315", "Java Card Technology for Smart Cards", "Zhiqun Chen",
                        1, now - 86400, "9A4F2C81E03B7712D4A9...");
                insertSeedBook(ps, 0x0205, "CT070315", "OWASP Mobile Security Testing Guide", "Sven Schleier",
                        1, now - 43200, "3E81B509C2A74490F1E2...");
                insertSeedBook(ps, 0x0309, null, "High Performance Android Apps", "Doug Sillars",
                        0, 0, "");
                insertSeedBook(ps, 0x0412, null, "Applied Cryptography & PKI Systems", "Bruce Schneier",
                        0, 0, "");
            }
        }

        int attCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM attendance_logs")) {
            if (rs.next()) {
                attCount = rs.getInt(1);
            }
        }

        if (attCount == 0) {
            long now = System.currentTimeMillis() / 1000L;
            String insertAttSql = "INSERT INTO attendance_logs (student_id, course_code, course_name, "
                    + "timestamp_seconds, rsa_signature_hex, verified_by_server) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertAttSql)) {
                ps.setString(1, "CT070315");
                ps.setString(2, "AT19N01");
                ps.setString(3, "AT19N01 - Phát triển PM Thẻ thông minh");
                ps.setLong(4, now - 7200);
                ps.setString(5, "7F3A9C12E8B04152D6F188A3...");
                ps.setInt(6, 1);
                ps.executeUpdate();

                ps.setString(1, "CT070301");
                ps.setString(2, "AT19N02");
                ps.setString(3, "AT19N02 - An toàn Hệ điều hành Di động");
                ps.setLong(4, now - 86400);
                ps.setString(5, "4C81E52A90F311B76D20A9C4...");
                ps.setInt(6, 1);
                ps.executeUpdate();
            }
        }

        int adminCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM admin_accounts")) {
            if (rs.next()) {
                adminCount = rs.getInt(1);
            }
        }

        if (adminCount == 0) {
            String insertAdminSql = "INSERT INTO admin_accounts (username, password_hash, salt, full_name, role) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertAdminSql)) {
                // 1. Super Admin: admin / admin123
                String saltAdmin = generateSalt();
                ps.setString(1, "admin");
                ps.setString(2, hashPassword("admin123", saltAdmin));
                ps.setString(3, saltAdmin);
                ps.setString(4, "Quản trị viên Hệ thống");
                ps.setString(5, "SUPER_ADMIN");
                ps.executeUpdate();

                // 2. Card Admin: daotao / daotao123
                String saltDaoTao = generateSalt();
                ps.setString(1, "daotao");
                ps.setString(2, hashPassword("daotao123", saltDaoTao));
                ps.setString(3, saltDaoTao);
                ps.setString(4, "Phòng Đào tạo & Khảo thí");
                ps.setString(5, "CARD_ADMIN");
                ps.executeUpdate();

                System.out.println("[SQLite DB] Seeded 2 relational admin accounts (admin/admin123, daotao/daotao123) with salted SHA-256");
            }
        }
    }

    private static void insertSeedCard(PreparedStatement ps, String id, String name, String dob,
                                       String cls, String dept, String pin, int locked,
                                       int score, int attCount, int labOk,
                                       String modHex, String expHex) throws SQLException {
        ps.setString(1, id);
        ps.setString(2, name);
        ps.setString(3, dob);
        ps.setString(4, cls);
        ps.setString(5, dept);
        ps.setString(6, pin);
        ps.setInt(7, locked);
        ps.setInt(8, score);
        ps.setInt(9, attCount);
        ps.setInt(10, labOk);
        ps.setString(11, modHex);
        ps.setString(12, expHex);
        ps.executeUpdate();
    }

    private static void insertSeedBook(PreparedStatement ps, int code, String studentId,
                                       String title, String author, int borrowed,
                                       long ts, String sig) throws SQLException {
        ps.setInt(1, code);
        ps.setString(2, studentId);
        ps.setString(3, title);
        ps.setString(4, author);
        ps.setInt(5, borrowed);
        ps.setLong(6, ts);
        ps.setString(7, sig);
        ps.executeUpdate();
    }

    /**
     * Queries all student cards from the `cards` table in SQLite.
     */
    public static synchronized String getAllCardsJson() {
        StringBuilder sb = new StringBuilder("[");
        String sql = "SELECT student_id, full_name, dob, class_name, department, default_pin, "
                + "card_locked, training_score, attendance_count, lab_access_granted, "
                + "public_key_modulus_hex, public_key_exponent_hex, raw_aes_ciphertext_hex "
                + "FROM cards ORDER BY created_at DESC, student_id ASC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                        .append("\"studentId\":\"").append(esc(rs.getString("student_id"))).append("\",")
                        .append("\"fullName\":\"").append(esc(rs.getString("full_name"))).append("\",")
                        .append("\"dob\":\"").append(esc(rs.getString("dob"))).append("\",")
                        .append("\"className\":\"").append(esc(rs.getString("class_name"))).append("\",")
                        .append("\"department\":\"").append(esc(rs.getString("department"))).append("\",")
                        .append("\"defaultPin\":\"").append(esc(rs.getString("default_pin"))).append("\",")
                        .append("\"cardLocked\":").append(rs.getInt("card_locked") != 0).append(",")
                        .append("\"trainingScore\":").append(rs.getInt("training_score")).append(",")
                        .append("\"attendanceCount\":").append(rs.getInt("attendance_count")).append(",")
                        .append("\"labAccessGranted\":").append(rs.getInt("lab_access_granted") != 0).append(",")
                        .append("\"publicKeyModulusHex\":\"").append(esc(rs.getString("public_key_modulus_hex"))).append("\",")
                        .append("\"publicKeyExponentHex\":\"").append(esc(rs.getString("public_key_exponent_hex"))).append("\",")
                        .append("\"rawAesCiphertextHex\":\"").append(esc(rs.getString("raw_aes_ciphertext_hex"))).append("\"")
                        .append("}");
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error querying cards: " + e.getMessage());
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Upserts a student card into the `cards` table and writes an audit entry inside an ACID transaction.
     */
    public static synchronized boolean upsertCard(String studentId, String fullName, String dob,
                                                  String className, String department, String defaultPin,
                                                  boolean cardLocked, int trainingScore, int attendanceCount,
                                                  String modulusHex, String exponentHex, String cipherHex) {
        if (studentId == null || studentId.trim().isEmpty()) return false;
        String sql = "INSERT INTO cards (student_id, full_name, dob, class_name, department, default_pin, "
                + "card_locked, training_score, attendance_count, lab_access_granted, "
                + "public_key_modulus_hex, public_key_exponent_hex, raw_aes_ciphertext_hex, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP) "
                + "ON CONFLICT(student_id) DO UPDATE SET "
                + "full_name = excluded.full_name, "
                + "dob = excluded.dob, "
                + "class_name = excluded.class_name, "
                + "department = excluded.department, "
                + "default_pin = excluded.default_pin, "
                + "card_locked = excluded.card_locked, "
                + "training_score = excluded.training_score, "
                + "attendance_count = excluded.attendance_count, "
                + "public_key_modulus_hex = CASE WHEN excluded.public_key_modulus_hex != '' THEN excluded.public_key_modulus_hex ELSE cards.public_key_modulus_hex END, "
                + "public_key_exponent_hex = CASE WHEN excluded.public_key_exponent_hex != '' THEN excluded.public_key_exponent_hex ELSE cards.public_key_exponent_hex END, "
                + "raw_aes_ciphertext_hex = CASE WHEN excluded.raw_aes_ciphertext_hex != '' THEN excluded.raw_aes_ciphertext_hex ELSE cards.raw_aes_ciphertext_hex END, "
                + "updated_at = CURRENT_TIMESTAMP";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, studentId.trim());
                ps.setString(2, fullName != null ? fullName.trim() : "");
                ps.setString(3, dob != null ? dob.trim() : "");
                ps.setString(4, className != null ? className.trim() : "");
                ps.setString(5, department != null ? department.trim() : "");
                ps.setString(6, (defaultPin != null && !defaultPin.isEmpty()) ? defaultPin : "123456");
                ps.setInt(7, cardLocked ? 1 : 0);
                ps.setInt(8, trainingScore > 0 ? trainingScore : 85);
                ps.setInt(9, Math.max(0, attendanceCount));
                ps.setString(10, modulusHex != null ? modulusHex : "");
                ps.setString(11, exponentHex != null ? exponentHex : "");
                ps.setString(12, cipherHex != null ? cipherHex : "");
                ps.executeUpdate();
            }

            insertAuditLogInternal(conn, studentId.trim(), "ISSUE_OR_SYNC_CARD", 0x0020,
                    "Phát hành / cập nhật hồ sơ thẻ sinh viên: " + fullName, "");
            conn.commit();
            return true;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error upserting card: " + e.getMessage());
            return false;
        }
    }

    /**
     * Updates the PIN lock status of a card in SQLite.
     */
    public static synchronized void updateCardLockState(String studentId, boolean locked) {
        if (studentId == null || studentId.isEmpty()) return;
        String sql = "UPDATE cards SET card_locked = ?, updated_at = CURRENT_TIMESTAMP WHERE student_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, locked ? 1 : 0);
            ps.setString(2, studentId);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error updating card lock state: " + e.getMessage());
        }
    }

    /**
     * Unblocks a specific PIN-locked card via Admin PUK in SQLite inside a transaction.
     * Updates ONLY the target student's card record, keeping other cards intact.
     */
    public static synchronized boolean unblockCardByPuk(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            return false;
        }
        String sql = "UPDATE cards SET card_locked = 0, default_pin = '123456', updated_at = CURRENT_TIMESTAMP WHERE student_id = ?";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, studentId.trim());
                ps.executeUpdate();
            }
            insertAuditLogInternal(conn, studentId.trim(), "UNBLOCK_BY_PUK", 0x0012,
                    "Admin mở khóa thẻ sinh viên bằng mã PUK: " + studentId.trim(), "");
            conn.commit();
            return true;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error unblocking card " + studentId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Deletes a student card record from `cards` (cascades to logs via foreign key).
     */
    public static synchronized boolean deleteCard(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) return false;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Delete related records first
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attendance_logs WHERE student_id = ?")) {
                    ps.setString(1, studentId.trim());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM card_audit_logs WHERE student_id = ?")) {
                    ps.setString(1, studentId.trim());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE book_loans SET student_id = NULL, borrowed = 0 WHERE student_id = ?")) {
                    ps.setString(1, studentId.trim());
                    ps.executeUpdate();
                }

                int affected = 0;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cards WHERE student_id = ?")) {
                    ps.setString(1, studentId.trim());
                    affected = ps.executeUpdate();
                }

                conn.commit();
                return affected > 0;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error deleting card " + studentId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Queries attendance check-in records from `attendance_logs` (optionally filtered by student_id).
     */
    public static synchronized String getAllAttendanceJson() {
        return getAttendanceJsonByStudent(null);
    }

    public static synchronized String getAttendanceJsonByStudent(String studentIdFilter) {
        StringBuilder sb = new StringBuilder("[");
        boolean hasFilter = studentIdFilter != null && !studentIdFilter.trim().isEmpty();
        String sql = "SELECT student_id, course_code, course_name, timestamp_seconds, "
                + "rsa_signature_hex, verified_by_server "
                + "FROM attendance_logs "
                + (hasFilter ? "WHERE student_id = ? " : "")
                + "ORDER BY timestamp_seconds DESC, id DESC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (hasFilter) {
                ps.setString(1, studentIdFilter.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{")
                            .append("\"studentId\":\"").append(esc(rs.getString("student_id"))).append("\",")
                            .append("\"courseCode\":\"").append(esc(rs.getString("course_code"))).append("\",")
                            .append("\"courseName\":\"").append(esc(rs.getString("course_name"))).append("\",")
                            .append("\"timestampSeconds\":").append(rs.getLong("timestamp_seconds")).append(",")
                            .append("\"rsaSignatureHex\":\"").append(esc(rs.getString("rsa_signature_hex"))).append("\",")
                            .append("\"verifiedByServer\":").append(rs.getInt("verified_by_server") != 0)
                            .append("}");
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error querying attendance_logs: " + e.getMessage());
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Inserts a new RSA-signed attendance record and atomically increments the card's attendance counter.
     */
    public static synchronized boolean insertAttendance(String studentId, String courseCode,
                                                        String courseName, long timestampSeconds,
                                                        String rsaSignatureHex, boolean verified) {
        if (studentId == null || studentId.isEmpty()) return false;
        long ts = timestampSeconds > 0 ? timestampSeconds : (System.currentTimeMillis() / 1000L);
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            ensureCardExists(conn, studentId);

            String insertSql = "INSERT INTO attendance_logs (student_id, course_code, course_name, "
                    + "timestamp_seconds, rsa_signature_hex, verified_by_server) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, studentId);
                ps.setString(2, (courseCode != null && !courseCode.isEmpty()) ? courseCode : "AT19N01");
                ps.setString(3, (courseName != null && !courseName.isEmpty()) ? courseName : "AT19N01 - Phát triển PM Thẻ thông minh");
                ps.setLong(4, ts);
                ps.setString(5, rsaSignatureHex != null ? rsaSignatureHex : "");
                ps.setInt(6, verified ? 1 : 0);
                ps.executeUpdate();
            }

            String updateCardSql = "UPDATE cards SET attendance_count = attendance_count + 1, "
                    + "training_score = MIN(100, training_score + 1), updated_at = CURRENT_TIMESTAMP "
                    + "WHERE student_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateCardSql)) {
                ps.setString(1, studentId);
                ps.executeUpdate();
            }

            insertAuditLogInternal(conn, studentId, "ATTENDANCE_CHECKIN", 0x0101,
                    "Điểm danh môn học: " + courseName, rsaSignatureHex);

            conn.commit();
            return true;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error inserting attendance: " + e.getMessage());
            return false;
        }
    }

    /**
     * Queries all library books and their borrowing status from `book_loans`.
     */
    public static synchronized String getAllBooksJson() {
        StringBuilder sb = new StringBuilder("[");
        String sql = "SELECT book_code, student_id, book_title, author, borrowed, "
                + "timestamp_seconds, rsa_signature_hex FROM book_loans ORDER BY book_code ASC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                        .append("\"bookCode\":").append(rs.getInt("book_code")).append(",")
                        .append("\"studentId\":\"").append(esc(rs.getString("student_id"))).append("\",")
                        .append("\"bookTitle\":\"").append(esc(rs.getString("book_title"))).append("\",")
                        .append("\"author\":\"").append(esc(rs.getString("author"))).append("\",")
                        .append("\"borrowed\":").append(rs.getInt("borrowed") != 0).append(",")
                        .append("\"timestampSeconds\":").append(rs.getLong("timestamp_seconds")).append(",")
                        .append("\"rsaSignatureHex\":\"").append(esc(rs.getString("rsa_signature_hex"))).append("\"")
                        .append("}");
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error querying book_loans: " + e.getMessage());
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Updates a library book loan transaction in `book_loans` and logs the audit trail atomically.
     */
    public static synchronized boolean updateBookLoan(int bookCode, String studentId, String bookTitle,
                                                      String author, boolean borrowed,
                                                      long timestampSeconds, String rsaSignatureHex) {
        long ts = timestampSeconds > 0 ? timestampSeconds : (System.currentTimeMillis() / 1000L);
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            if (studentId != null && !studentId.isEmpty()) {
                ensureCardExists(conn, studentId);
            }

            String sql = "INSERT INTO book_loans (book_code, student_id, book_title, author, "
                    + "borrowed, timestamp_seconds, rsa_signature_hex) VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(book_code) DO UPDATE SET "
                    + "student_id = excluded.student_id, "
                    + "borrowed = excluded.borrowed, "
                    + "timestamp_seconds = excluded.timestamp_seconds, "
                    + "rsa_signature_hex = excluded.rsa_signature_hex";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, bookCode);
                ps.setString(2, borrowed ? studentId : null);
                ps.setString(3, (bookTitle != null && !bookTitle.isEmpty()) ? bookTitle : ("Sách mã 0x" + String.format("%04X", bookCode)));
                ps.setString(4, (author != null && !author.isEmpty()) ? author : "Thư viện Học viện");
                ps.setInt(5, borrowed ? 1 : 0);
                ps.setLong(6, ts);
                ps.setString(7, rsaSignatureHex != null ? rsaSignatureHex : "");
                ps.executeUpdate();
            }

            if (studentId != null && !studentId.isEmpty()) {
                insertAuditLogInternal(conn, studentId, borrowed ? "BORROW_BOOK" : "RETURN_BOOK",
                        bookCode, (borrowed ? "Mượn sách: " : "Trả sách: ") + bookTitle, rsaSignatureHex);
            }

            conn.commit();
            return true;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error updating book loan: " + e.getMessage());
            return false;
        }
    }

    public static class AdminUser {
        public int id;
        public String username;
        public String fullName;
        public String role;
        public String lastLogin;

        public AdminUser(int id, String username, String fullName, String role, String lastLogin) {
            this.id = id;
            this.username = username;
            this.fullName = fullName;
            this.role = role;
            this.lastLogin = lastLogin != null ? lastLogin : "";
        }
    }

    public static String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 error: " + e.getMessage(), e);
        }
    }

    public static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        StringBuilder sb = new StringBuilder();
        for (byte b : salt) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Authenticates an administrator directly against the relational `admin_accounts` table.
     * Compares the salted SHA-256 hash of the input password with the stored hash.
     * Updates `last_login` timestamp and logs audit entry on success.
     */
    public static synchronized AdminUser authenticateAdmin(String username, String password) {
        if (username == null || password == null) return null;
        String sql = "SELECT id, username, password_hash, salt, full_name, role, last_login FROM admin_accounts WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    String salt = rs.getString("salt");
                    String computedHash = hashPassword(password, salt);
                    if (storedHash.equalsIgnoreCase(computedHash)) {
                        AdminUser user = new AdminUser(
                                rs.getInt("id"),
                                rs.getString("username"),
                                rs.getString("full_name"),
                                rs.getString("role"),
                                rs.getString("last_login")
                        );

                        // Update last_login timestamp in database
                        try (PreparedStatement updatePs = conn.prepareStatement("UPDATE admin_accounts SET last_login = CURRENT_TIMESTAMP WHERE id = ?")) {
                            updatePs.setInt(1, user.id);
                            updatePs.executeUpdate();
                        }

                        // Audit log for admin login
                        try {
                            insertAuditLogInternal(conn, "ADMIN", "ADMIN_LOGIN", user.id,
                                    "Quản trị viên đăng nhập thành công: " + user.username + " (" + user.fullName + ")", "");
                        } catch (Exception ignored) {
                        }

                        return user;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[SQLite DB] Admin authentication error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns JSON list of all registered administrator accounts (excluding hashes and salts).
     */
    public static synchronized String getAllAdminAccountsJson() {
        StringBuilder sb = new StringBuilder("[");
        String sql = "SELECT id, username, full_name, role, created_at, last_login FROM admin_accounts ORDER BY id ASC";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                sb.append("{")
                  .append("\"id\":").append(rs.getInt("id")).append(",")
                  .append("\"username\":\"").append(esc(rs.getString("username"))).append("\",")
                  .append("\"fullName\":\"").append(esc(rs.getString("full_name"))).append("\",")
                  .append("\"role\":\"").append(esc(rs.getString("role"))).append("\",")
                  .append("\"createdAt\":\"").append(esc(rs.getString("created_at"))).append("\",")
                  .append("\"lastLogin\":\"").append(esc(rs.getString("last_login"))).append("\"")
                  .append("}");
                first = false;
            }
        } catch (Exception ignored) {
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Records an on-card APDU action (attendance, borrow, return, lab door access) directly into SQLite.
     */
    public static synchronized void recordApduTransaction(String studentId, String actionType,
                                                          int itemCode, String description, String sigHex) {
        if (studentId == null || studentId.isEmpty()) return;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            ensureCardExists(conn, studentId);
            insertAuditLogInternal(conn, studentId, actionType, itemCode, description, sigHex);
            conn.commit();
        } catch (Exception ignored) {
        }
    }

    /**
     * Returns a comprehensive JSON summary of all relational tables in `smartcard.db`.
     */
    public static synchronized String getDatabaseSummaryJson() {
        int totalCards = 0;
        int lockedCards = 0;
        int totalAttendance = 0;
        int borrowedBooks = 0;
        int totalAuditLogs = 0;
        int totalAdmins = 0;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*), SUM(CASE WHEN card_locked=1 THEN 1 ELSE 0 END) FROM cards")) {
                if (rs.next()) {
                    totalCards = rs.getInt(1);
                    lockedCards = rs.getInt(2);
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM attendance_logs")) {
                if (rs.next()) totalAttendance = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM book_loans WHERE borrowed = 1")) {
                if (rs.next()) borrowedBooks = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM card_audit_logs")) {
                if (rs.next()) totalAuditLogs = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM admin_accounts")) {
                if (rs.next()) totalAdmins = rs.getInt(1);
            }
        } catch (Exception ignored) {
        }

        return String.format(
                "{\"dbEngine\":\"SQLite 3 (JDBC)\",\"dbFile\":\"%s\",\"totalCards\":%d,\"lockedCards\":%d,"
                        + "\"totalAttendance\":%d,\"borrowedBooks\":%d,\"totalAuditLogs\":%d,\"totalAdmins\":%d,"
                        + "\"cards\":%s,\"attendance\":%s,\"books\":%s,\"admins\":%s}",
                esc(DB_FILE.getPath()), totalCards, lockedCards, totalAttendance, borrowedBooks, totalAuditLogs, totalAdmins,
                getAllCardsJson(), getAllAttendanceJson(), getAllBooksJson(), getAllAdminAccountsJson()
        );
    }

    private static void ensureCardExists(Connection conn, String studentId) throws SQLException {
        String sql = "INSERT OR IGNORE INTO cards (student_id, full_name, dob, class_name, department) "
                + "VALUES (?, ?, '01/01/2003', 'CT7C', 'An toàn Thông tin')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId);
            ps.setString(2, "Sinh viên " + studentId);
            ps.executeUpdate();
        }
    }

    private static void insertAuditLogInternal(Connection conn, String studentId, String actionType,
                                               int itemCode, String description, String sigHex) throws SQLException {
        String sql = "INSERT INTO card_audit_logs (student_id, action_type, item_code, description, "
                + "rsa_signature_hex, timestamp_seconds) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId);
            ps.setString(2, actionType);
            ps.setInt(3, itemCode);
            ps.setString(4, description != null ? description : "");
            ps.setString(5, sigHex != null ? sigHex : "");
            ps.setLong(6, System.currentTimeMillis() / 1000L);
            ps.executeUpdate();
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    public static void main(String[] args) {
        initDatabase();
        System.out.println("[SQLite DB Summary] " + getDatabaseSummaryJson());
    }
}
