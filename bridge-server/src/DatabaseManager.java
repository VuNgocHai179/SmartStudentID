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
import java.util.ArrayList;
import java.util.List;

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
                        + "avatar_webp_base64 TEXT, "
                        + "wallet_balance INTEGER DEFAULT 500000, "
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

                // 6. Table: wallet_transactions (RSA-1024 signed E-Wallet top-ups and payments)
                stmt.execute("CREATE TABLE IF NOT EXISTS wallet_transactions ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "student_id VARCHAR(20) NOT NULL, "
                        + "tx_type VARCHAR(20) NOT NULL, "
                        + "amount INTEGER NOT NULL, "
                        + "balance_before INTEGER DEFAULT 0, "
                        + "balance_after INTEGER NOT NULL, "
                        + "tx_ref VARCHAR(40) DEFAULT '', "
                        + "counterparty VARCHAR(120) DEFAULT '', "
                        + "description VARCHAR(200) NOT NULL, "
                        + "timestamp_seconds INTEGER NOT NULL, "
                        + "rsa_signature_hex TEXT NOT NULL, "
                        + "verified_by_server INTEGER DEFAULT 1, "
                        + "FOREIGN KEY (student_id) REFERENCES cards(student_id) ON DELETE CASCADE"
                        + ");");

                // 7. Table: courses (Semester courses catalog)
                stmt.execute("CREATE TABLE IF NOT EXISTS courses ("
                        + "course_code VARCHAR(30) PRIMARY KEY, "
                        + "course_name VARCHAR(150) NOT NULL, "
                        + "lecturer_name VARCHAR(100) NOT NULL, "
                        + "room VARCHAR(50) NOT NULL, "
                        + "semester VARCHAR(30) DEFAULT 'HK1 2026-2027', "
                        + "schedule_text VARCHAR(100) DEFAULT '', "
                        + "total_sessions INTEGER DEFAULT 15"
                        + ");");

                // 8. Table: enrollments (Student semester course registrations)
                stmt.execute("CREATE TABLE IF NOT EXISTS enrollments ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "student_id VARCHAR(20) NOT NULL, "
                        + "course_code VARCHAR(30) NOT NULL, "
                        + "semester VARCHAR(30) DEFAULT 'HK1 2026-2027', "
                        + "UNIQUE(student_id, course_code, semester), "
                        + "FOREIGN KEY (student_id) REFERENCES cards(student_id) ON DELETE CASCADE, "
                        + "FOREIGN KEY (course_code) REFERENCES courses(course_code) ON DELETE CASCADE"
                        + ");");

                // 9. Table: attendance_sessions (Lecturer-opened class check-in sessions)
                stmt.execute("CREATE TABLE IF NOT EXISTS attendance_sessions ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "course_code VARCHAR(30) NOT NULL, "
                        + "course_name VARCHAR(150) NOT NULL, "
                        + "room VARCHAR(50) DEFAULT '', "
                        + "lecturer_name VARCHAR(100) DEFAULT '', "
                        + "opened_at INTEGER NOT NULL, "
                        + "expires_at INTEGER NOT NULL, "
                        + "rotation_seconds INTEGER DEFAULT 20, "
                        + "session_secret VARCHAR(64) NOT NULL, "
                        + "status VARCHAR(16) DEFAULT 'OPEN', "
                        + "FOREIGN KEY (course_code) REFERENCES courses(course_code) ON DELETE CASCADE"
                        + ");");

                // Indexes for fast relational lookup
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_attendance_student ON attendance_logs(student_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_books_student ON book_loans(student_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_student ON card_audit_logs(student_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_admin_username ON admin_accounts(username);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_wallet_student ON wallet_transactions(student_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_enrollments_student ON enrollments(student_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_enrollments_course ON enrollments(course_code);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_course_status ON attendance_sessions(course_code, status);");
            }

            ensureAvatarColumnExists(conn);
            ensureWalletColumnExists(conn);
            ensureWalletTxColumnsExists(conn);
            ensureAttendanceSessionColumnsExists(conn);
            ensureCourseTotalSessionsColumnExists(conn);
            seedDefaultDataIfEmpty(conn);
            seedCoursesAndEnrollmentsIfEmpty(conn);
            conn.commit();
            System.out.println("[SQLite DB] Initialized relational database at: " + DB_FILE.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[SQLite DB] Initialization error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void ensureAvatarColumnExists(Connection conn) throws SQLException {
        boolean hasColumn = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(cards)")) {
            while (rs.next()) {
                if ("avatar_webp_base64".equalsIgnoreCase(rs.getString("name"))) {
                    hasColumn = true;
                    break;
                }
            }
        }
        if (!hasColumn) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE cards ADD COLUMN avatar_webp_base64 TEXT");
                System.out.println("[SQLite DB] Migrated `cards` table: added column `avatar_webp_base64`.");
            }
        }
    }

    private static void ensureWalletColumnExists(Connection conn) throws SQLException {
        boolean hasColumn = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(cards)")) {
            while (rs.next()) {
                if ("wallet_balance".equalsIgnoreCase(rs.getString("name"))) {
                    hasColumn = true;
                    break;
                }
            }
        }
        if (!hasColumn) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE cards ADD COLUMN wallet_balance INTEGER DEFAULT 500000");
                System.out.println("[SQLite DB] Migrated `cards` table: added column `wallet_balance`.");
            }
        }
    }

    private static void ensureWalletTxColumnsExists(Connection conn) throws SQLException {
        boolean hasTxRef = false;
        boolean hasBalanceBefore = false;
        boolean hasCounterparty = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(wallet_transactions)")) {
            while (rs.next()) {
                String col = rs.getString("name");
                if ("tx_ref".equalsIgnoreCase(col)) hasTxRef = true;
                else if ("balance_before".equalsIgnoreCase(col)) hasBalanceBefore = true;
                else if ("counterparty".equalsIgnoreCase(col)) hasCounterparty = true;
            }
        }
        try (Statement stmt = conn.createStatement()) {
            if (!hasTxRef) {
                stmt.execute("ALTER TABLE wallet_transactions ADD COLUMN tx_ref VARCHAR(40) DEFAULT ''");
                System.out.println("[SQLite DB] Migrated `wallet_transactions`: added column `tx_ref`.");
            }
            if (!hasBalanceBefore) {
                stmt.execute("ALTER TABLE wallet_transactions ADD COLUMN balance_before INTEGER DEFAULT 0");
                System.out.println("[SQLite DB] Migrated `wallet_transactions`: added column `balance_before`.");
            }
            if (!hasCounterparty) {
                stmt.execute("ALTER TABLE wallet_transactions ADD COLUMN counterparty VARCHAR(120) DEFAULT ''");
                System.out.println("[SQLite DB] Migrated `wallet_transactions`: added column `counterparty`.");
            }
            // Backfill legacy/seed rows that lack banking ledger metadata
            stmt.executeUpdate("UPDATE wallet_transactions SET "
                    + "tx_ref = 'FT2631800001', balance_before = 0, "
                    + "counterparty = 'Vietcombank • *8829 (Napas 247)' "
                    + "WHERE id = 1 AND (tx_ref IS NULL OR tx_ref = '')");
            stmt.executeUpdate("UPDATE wallet_transactions SET "
                    + "tx_ref = 'FT2631850002', balance_before = 550000, "
                    + "counterparty = 'POS Căng-tin Học viện Khu A' "
                    + "WHERE id = 2 AND (tx_ref IS NULL OR tx_ref = '')");
            stmt.executeUpdate("UPDATE wallet_transactions SET "
                    + "balance_before = CASE WHEN tx_type = 'CREDIT' THEN MAX(0, balance_after - amount) ELSE balance_after + amount END, "
                    + "tx_ref = 'FT' || printf('%010d', ABS((timestamp_seconds * 100 + (amount / 1000)) % 10000000000)), "
                    + "counterparty = CASE WHEN tx_type = 'CREDIT' THEN 'Vietcombank • *8829 (Napas 247)' ELSE 'POS Dịch vụ Smart Campus' END "
                    + "WHERE tx_ref IS NULL OR tx_ref = ''");
        }
    }

    private static void ensureAttendanceSessionColumnsExists(Connection conn) throws SQLException {
        boolean hasSessionId = false;
        boolean hasCheckinMethod = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(attendance_logs)")) {
            while (rs.next()) {
                String col = rs.getString("name");
                if ("session_id".equalsIgnoreCase(col)) hasSessionId = true;
                else if ("checkin_method".equalsIgnoreCase(col)) hasCheckinMethod = true;
            }
        }
        try (Statement stmt = conn.createStatement()) {
            if (!hasSessionId) {
                stmt.execute("ALTER TABLE attendance_logs ADD COLUMN session_id INTEGER DEFAULT 0");
                System.out.println("[SQLite DB] Migrated `attendance_logs`: added column `session_id`.");
            }
            if (!hasCheckinMethod) {
                stmt.execute("ALTER TABLE attendance_logs ADD COLUMN checkin_method VARCHAR(20) DEFAULT 'CARD_READER'");
                System.out.println("[SQLite DB] Migrated `attendance_logs`: added column `checkin_method`.");
            }
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_attendance_unique_session_student "
                    + "ON attendance_logs(session_id, student_id) WHERE session_id > 0");
        }
    }

    private static void ensureCourseTotalSessionsColumnExists(Connection conn) throws SQLException {
        boolean hasTotalSessions = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(courses)")) {
            while (rs.next()) {
                if ("total_sessions".equalsIgnoreCase(rs.getString("name"))) {
                    hasTotalSessions = true;
                    break;
                }
            }
        }
        if (!hasTotalSessions) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE courses ADD COLUMN total_sessions INTEGER DEFAULT 15");
                stmt.executeUpdate("UPDATE courses SET total_sessions = 15 WHERE total_sessions IS NULL OR total_sessions <= 0");
                stmt.executeUpdate("UPDATE courses SET total_sessions = 12 WHERE course_code = 'AT19N03' AND total_sessions = 15");
                System.out.println("[SQLite DB] Migrated `courses`: added column `total_sessions`.");
            }
        }
    }

    public static String generateBankTxRef(long ts, int amount) {
        long safeTs = ts > 0 ? ts : (System.currentTimeMillis() / 1000L);
        long val = Math.abs((safeTs * 100L + (amount / 1000L)) % 10_000_000_000L);
        return "FT" + String.format(java.util.Locale.US, "%010d", val);
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

        int walletTxCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM wallet_transactions")) {
            if (rs.next()) {
                walletTxCount = rs.getInt(1);
            }
        }

        if (walletTxCount == 0) {
            long now = System.currentTimeMillis() / 1000L;
            String insertWalletSql = "INSERT INTO wallet_transactions (student_id, tx_type, amount, "
                    + "balance_before, balance_after, tx_ref, counterparty, description, "
                    + "timestamp_seconds, rsa_signature_hex, verified_by_server) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertWalletSql)) {
                ps.setString(1, "CT070315");
                ps.setString(2, "CREDIT");
                ps.setInt(3, 550000);
                ps.setInt(4, 0);
                ps.setInt(5, 550000);
                ps.setString(6, "FT2631800001");
                ps.setString(7, "Vietcombank • *8829 (Napas 247)");
                ps.setString(8, "Nạp tiền khởi tạo ví sinh viên");
                ps.setLong(9, now - 172800);
                ps.setString(10, "8B21F04C91A355E07C12D8B4...");
                ps.setInt(11, 1);
                ps.executeUpdate();

                ps.setString(1, "CT070315");
                ps.setString(2, "DEBIT");
                ps.setInt(3, 50000);
                ps.setInt(4, 550000);
                ps.setInt(5, 500000);
                ps.setString(6, "FT2631850002");
                ps.setString(7, "POS Căng-tin Học viện Khu A");
                ps.setString(8, "Thanh toán cơm trưa Căng-tin");
                ps.setLong(9, now - 43200);
                ps.setString(10, "5D94A1E20B7C3891F46A120E...");
                ps.setInt(11, 1);
                ps.executeUpdate();
            }
        }
    }

    private static void seedCoursesAndEnrollmentsIfEmpty(Connection conn) throws SQLException {
        int courseCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM courses")) {
            if (rs.next()) {
                courseCount = rs.getInt(1);
            }
        }
        if (courseCount == 0) {
            String insertCourseSql = "INSERT INTO courses (course_code, course_name, lecturer_name, room, semester, schedule_text, total_sessions) "
                    + "VALUES (?, ?, ?, ?, 'HK1 2026-2027', ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertCourseSql)) {
                insertSeedCourse(ps, "AT19N01", "Phát triển PM Thẻ thông minh", "TS. Nguyễn Văn An", "Phòng Lab 302-TA1", "Thứ 3, Tiết 1-3 (07:00 - 09:30)", 15);
                insertSeedCourse(ps, "AT19N02", "An toàn Hệ điều hành Di động", "ThS. Lê Thị Bình", "Phòng Lab 405-TA1", "Thứ 4, Tiết 4-6 (09:40 - 12:10)", 15);
                insertSeedCourse(ps, "AT19N03", "Tối ưu Phần mềm Di động", "TS. Phạm Minh Cường", "Phòng Máy 201-TA2", "Thứ 5, Tiết 7-9 (12:30 - 15:00)", 12);
                insertSeedCourse(ps, "CN19N04", "Kiến trúc Hướng Dịch vụ", "TS. Trần Quang Dũng", "Giảng đường 102-TA2", "Thứ 6, Tiết 1-3 (07:00 - 09:30)", 15);
            }
        }

        int enrollCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM enrollments")) {
            if (rs.next()) {
                enrollCount = rs.getInt(1);
            }
        }
        if (enrollCount == 0) {
            String insertEnrollSql = "INSERT OR IGNORE INTO enrollments (student_id, course_code, semester) "
                    + "SELECT ?, ?, 'HK1 2026-2027' "
                    + "WHERE EXISTS (SELECT 1 FROM cards WHERE student_id = ?) "
                    + "AND EXISTS (SELECT 1 FROM courses WHERE course_code = ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertEnrollSql)) {
                String[][] pairs = {
                        {"CT070315", "AT19N01"},
                        {"CT070315", "AT19N02"},
                        {"CT070315", "AT19N03"},
                        {"CT070301", "AT19N01"},
                        {"CT070301", "AT19N02"},
                        {"CT070301", "AT19N03"},
                        {"CT070322", "AT19N01"},
                        {"CT070322", "CN19N04"}
                };
                for (String[] pair : pairs) {
                    ps.setString(1, pair[0]);
                    ps.setString(2, pair[1]);
                    ps.setString(3, pair[0]);
                    ps.setString(4, pair[1]);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static void insertSeedCourse(PreparedStatement ps, String code, String name, String lecturer,
                                         String room, String schedule, int totalSessions) throws SQLException {
        ps.setString(1, code);
        ps.setString(2, name);
        ps.setString(3, lecturer);
        ps.setString(4, room);
        ps.setString(5, schedule);
        ps.setInt(6, totalSessions);
        ps.executeUpdate();
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
                + "public_key_modulus_hex, public_key_exponent_hex, raw_aes_ciphertext_hex, "
                + "COALESCE(wallet_balance, 500000) AS wallet_balance "
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
                        .append("\"rawAesCiphertextHex\":\"").append(esc(rs.getString("raw_aes_ciphertext_hex"))).append("\",")
                        .append("\"walletBalance\":").append(rs.getInt("wallet_balance"))
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
                + "full_name = CASE WHEN excluded.full_name != '' THEN excluded.full_name ELSE cards.full_name END, "
                + "dob = CASE WHEN excluded.dob != '' THEN excluded.dob ELSE cards.dob END, "
                + "class_name = CASE WHEN excluded.class_name != '' THEN excluded.class_name ELSE cards.class_name END, "
                + "department = CASE WHEN excluded.department != '' THEN excluded.department ELSE cards.department END, "
                + "default_pin = CASE WHEN excluded.default_pin != '' THEN excluded.default_pin ELSE cards.default_pin END, "
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

            int existingEnrollments = 0;
            try (PreparedStatement eps = conn.prepareStatement("SELECT COUNT(*) FROM enrollments WHERE student_id = ?")) {
                eps.setString(1, studentId.trim());
                try (ResultSet rs = eps.executeQuery()) {
                    if (rs.next()) existingEnrollments = rs.getInt(1);
                }
            }
            if (existingEnrollments == 0) {
                boolean isSecurityDept = department != null && department.toLowerCase(java.util.Locale.ROOT).contains("an toàn");
                String[] defaultCourses = isSecurityDept
                        ? new String[]{"AT19N01", "AT19N02", "AT19N03"}
                        : new String[]{"AT19N01", "CN19N04"};
                String enrollSql = "INSERT OR IGNORE INTO enrollments (student_id, course_code, semester) "
                        + "SELECT ?, ?, 'HK1 2026-2027' WHERE EXISTS (SELECT 1 FROM courses WHERE course_code = ?)";
                try (PreparedStatement eps = conn.prepareStatement(enrollSql)) {
                    for (String cCode : defaultCourses) {
                        eps.setString(1, studentId.trim());
                        eps.setString(2, cCode);
                        eps.setString(3, cCode);
                        eps.executeUpdate();
                    }
                }
            }

            conn.commit();
            return true;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error upserting card: " + e.getMessage());
            return false;
        }
    }

    /**
     * Persists a card's WebP avatar (Base64-encoded) in `cards.avatar_webp_base64`.
     */
    public static synchronized boolean saveCardAvatarBase64(String studentId, String avatarBase64) {
        if (studentId == null || studentId.trim().isEmpty()
                || avatarBase64 == null || avatarBase64.isEmpty()) {
            return false;
        }
        String sql = "UPDATE cards SET avatar_webp_base64 = ?, updated_at = CURRENT_TIMESTAMP WHERE student_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, avatarBase64);
            ps.setString(2, studentId.trim());
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error saving card avatar: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reads a card's persisted WebP avatar (Base64-encoded) from `cards.avatar_webp_base64`.
     */
    public static synchronized String getCardAvatarBase64(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            return null;
        }
        String sql = "SELECT avatar_webp_base64 FROM cards WHERE student_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String b64 = rs.getString("avatar_webp_base64");
                    if (b64 != null && !b64.trim().isEmpty()) {
                        return b64.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Checks whether a card is currently locked by the admin PUK or by 3 failed PIN attempts.
     */
    public static synchronized boolean isCardLocked(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) return false;
        String sql = "SELECT card_locked FROM cards WHERE student_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("card_locked") != 0;
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error reading card lock state: " + e.getMessage());
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
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM wallet_transactions WHERE student_id = ?")) {
                    ps.setString(1, studentId.trim());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM card_audit_logs WHERE student_id = ?")) {
                    ps.setString(1, studentId.trim());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM enrollments WHERE student_id = ?")) {
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
                + "rsa_signature_hex, verified_by_server, "
                + "COALESCE(session_id, 0) AS session_id, "
                + "COALESCE(checkin_method, 'CARD_READER') AS checkin_method "
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
                            .append("\"verifiedByServer\":").append(rs.getInt("verified_by_server") != 0).append(",")
                            .append("\"sessionId\":").append(rs.getInt("session_id")).append(",")
                            .append("\"checkinMethod\":\"").append(esc(rs.getString("checkin_method"))).append("\"")
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
     * Reads a card's persisted wallet balance from `cards.wallet_balance` (defaults to 500000).
     */
    public static synchronized int getCardWalletBalance(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            return 500000;
        }
        String sql = "SELECT wallet_balance FROM cards WHERE student_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getObject("wallet_balance") != null) {
                    return rs.getInt("wallet_balance");
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error reading wallet balance: " + e.getMessage());
        }
        return 500000;
    }

    /**
     * Records an RSA-signed E-Wallet transaction (CREDIT / DEBIT) and atomically updates `cards.wallet_balance`.
     * If a matching record with the same (student_id, tx_type, amount, timestamp_seconds) was already created
     * by the APDU handler, updates its description and verification status instead of inserting a duplicate.
     */
    public static synchronized boolean recordWalletTransaction(String studentId, String txType, int amount,
                                                                 int balanceAfter, String description,
                                                                 long timestampSeconds, String rsaSignatureHex,
                                                                 boolean verified) {
        return recordWalletTransaction(studentId, txType, amount, balanceAfter, description,
                timestampSeconds, rsaSignatureHex, verified, "", 0, "");
    }

    /**
     * Records an RSA-signed E-Wallet banking ledger entry (CREDIT / DEBIT) and atomically updates `cards.wallet_balance`.
     * Enforces accounting consistency (balance_before +/- amount == balance_after) and deduplicates matching APDU/POST pairs.
     */
    public static synchronized boolean recordWalletTransaction(String studentId, String txType, int amount,
                                                                 int balanceAfter, String description,
                                                                 long timestampSeconds, String rsaSignatureHex,
                                                                 boolean verified, String txRef,
                                                                 int balanceBefore, String counterparty) {
        if (studentId == null || studentId.trim().isEmpty()) return false;
        String sid = studentId.trim();
        String type = (txType != null && !txType.trim().isEmpty()) ? txType.trim().toUpperCase() : "CREDIT";
        boolean isCredit = "CREDIT".equals(type);
        String desc = (description != null && !description.trim().isEmpty())
                ? description.trim()
                : (isCredit ? "Nạp tiền ví điện tử" : "Thanh toán ví điện tử");
        long ts = timestampSeconds > 0 ? timestampSeconds : (System.currentTimeMillis() / 1000L);
        String sig = rsaSignatureHex != null ? rsaSignatureHex : "";
        String ref = (txRef != null && !txRef.trim().isEmpty()) ? txRef.trim() : generateBankTxRef(ts, amount);
        String party = (counterparty != null && !counterparty.trim().isEmpty())
                ? counterparty.trim()
                : (isCredit ? "Vietcombank • *8829 (Napas 247)" : "POS Dịch vụ Smart Campus");

        int resolvedBalanceBefore = balanceBefore;
        if (resolvedBalanceBefore <= 0) {
            resolvedBalanceBefore = Math.max(0, isCredit ? (balanceAfter - amount) : (balanceAfter + amount));
        } else {
            int expectedAfter = isCredit ? (resolvedBalanceBefore + amount) : (resolvedBalanceBefore - amount);
            if (expectedAfter != balanceAfter) {
                resolvedBalanceBefore = Math.max(0, isCredit ? (balanceAfter - amount) : (balanceAfter + amount));
            }
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            ensureCardExists(conn, sid);

            long existingId = -1L;
            String findSql = "SELECT id FROM wallet_transactions WHERE student_id = ? AND tx_type = ? "
                    + "AND amount = ? AND timestamp_seconds = ? ORDER BY id DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                ps.setString(1, sid);
                ps.setString(2, type);
                ps.setInt(3, amount);
                ps.setLong(4, ts);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingId = rs.getLong("id");
                    }
                }
            }

            if (existingId > 0) {
                String updTxSql = "UPDATE wallet_transactions SET description = ?, balance_before = ?, balance_after = ?, "
                        + "tx_ref = CASE WHEN ? != '' THEN ? ELSE tx_ref END, "
                        + "counterparty = CASE WHEN ? != '' THEN ? ELSE counterparty END, "
                        + "rsa_signature_hex = CASE WHEN ? != '' THEN ? ELSE rsa_signature_hex END, "
                        + "verified_by_server = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(updTxSql)) {
                    ps.setString(1, desc);
                    ps.setInt(2, resolvedBalanceBefore);
                    ps.setInt(3, balanceAfter);
                    ps.setString(4, ref);
                    ps.setString(5, ref);
                    ps.setString(6, party);
                    ps.setString(7, party);
                    ps.setString(8, sig);
                    ps.setString(9, sig);
                    ps.setInt(10, verified ? 1 : 0);
                    ps.setLong(11, existingId);
                    ps.executeUpdate();
                }
            } else {
                String insertSql = "INSERT INTO wallet_transactions (student_id, tx_type, amount, "
                        + "balance_before, balance_after, tx_ref, counterparty, description, "
                        + "timestamp_seconds, rsa_signature_hex, verified_by_server) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, sid);
                    ps.setString(2, type);
                    ps.setInt(3, amount);
                    ps.setInt(4, resolvedBalanceBefore);
                    ps.setInt(5, balanceAfter);
                    ps.setString(6, ref);
                    ps.setString(7, party);
                    ps.setString(8, desc);
                    ps.setLong(9, ts);
                    ps.setString(10, sig);
                    ps.setInt(11, verified ? 1 : 0);
                    ps.executeUpdate();
                }

                int itemCode = isCredit ? 0x0040 : 0x0041;
                insertAuditLogInternal(conn, sid, "WALLET_" + type, itemCode,
                        "[" + ref + "] " + desc + " (" + amount + " VND)", sig);
            }

            String updateCardSql = "UPDATE cards SET wallet_balance = ?, updated_at = CURRENT_TIMESTAMP WHERE student_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateCardSql)) {
                ps.setInt(1, balanceAfter);
                ps.setString(2, sid);
                ps.executeUpdate();
            }

            conn.commit();
            return true;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error recording wallet transaction: " + e.getMessage());
            return false;
        }
    }

    /**
     * Queries E-Wallet transactions from `wallet_transactions` (optionally filtered by student_id).
     */
    public static synchronized String getWalletTransactionsJsonByStudent(String studentIdFilter) {
        StringBuilder sb = new StringBuilder("[");
        boolean hasFilter = studentIdFilter != null && !studentIdFilter.trim().isEmpty();
        String sql = "SELECT id, student_id, tx_type, amount, balance_before, balance_after, "
                + "tx_ref, counterparty, description, timestamp_seconds, rsa_signature_hex, verified_by_server "
                + "FROM wallet_transactions "
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
                            .append("\"id\":").append(rs.getLong("id")).append(",")
                            .append("\"studentId\":\"").append(esc(rs.getString("student_id"))).append("\",")
                            .append("\"txType\":\"").append(esc(rs.getString("tx_type"))).append("\",")
                            .append("\"amount\":").append(rs.getInt("amount")).append(",")
                            .append("\"balanceBefore\":").append(rs.getInt("balance_before")).append(",")
                            .append("\"balanceAfter\":").append(rs.getInt("balance_after")).append(",")
                            .append("\"txRef\":\"").append(esc(rs.getString("tx_ref"))).append("\",")
                            .append("\"counterparty\":\"").append(esc(rs.getString("counterparty"))).append("\",")
                            .append("\"description\":\"").append(esc(rs.getString("description"))).append("\",")
                            .append("\"timestampSeconds\":").append(rs.getLong("timestamp_seconds")).append(",")
                            .append("\"rsaSignatureHex\":\"").append(esc(rs.getString("rsa_signature_hex"))).append("\",")
                            .append("\"verifiedByServer\":").append(rs.getInt("verified_by_server") != 0)
                            .append("}");
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error querying wallet_transactions: " + e.getMessage());
        }
        sb.append("]");
        return sb.toString();
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

    // =========================================================================
    // REAL-WORLD ATTENDANCE SESSIONS, COURSES, ENROLLMENTS & RSA KEY LOOKUP
    // =========================================================================

    public static class AttendanceSession {
        public int id;
        public String courseCode;
        public String courseName;
        public String room;
        public String lecturerName;
        public long openedAt;
        public long expiresAt;
        public int rotationSeconds;
        public String sessionSecret;
        public String status;
    }

    public static class CardPublicKeyRecord {
        public String modulusHex;
        public String exponentHex;

        public CardPublicKeyRecord(String modulusHex, String exponentHex) {
            this.modulusHex = modulusHex != null ? modulusHex : "";
            this.exponentHex = exponentHex != null ? exponentHex : "";
        }
    }

    public static class SessionCheckInInfo {
        public long timestampSeconds;
        public String checkinMethod;

        public SessionCheckInInfo(long timestampSeconds, String checkinMethod) {
            this.timestampSeconds = timestampSeconds;
            this.checkinMethod = checkinMethod != null ? checkinMethod : "CARD_READER";
        }
    }

    public static class CheckInCommitResult {
        public boolean saved;
        public boolean duplicate;
        public int attendanceCount;
        public int trainingScore;
        public String errorMessage;

        public CheckInCommitResult(boolean saved, boolean duplicate, int attendanceCount,
                                   int trainingScore, String errorMessage) {
            this.saved = saved;
            this.duplicate = duplicate;
            this.attendanceCount = attendanceCount;
            this.trainingScore = trainingScore;
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }
    }

    public static synchronized CardPublicKeyRecord getCardPublicKey(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) return null;
        String sql = "SELECT public_key_modulus_hex, public_key_exponent_hex FROM cards WHERE student_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new CardPublicKeyRecord(
                            rs.getString("public_key_modulus_hex"),
                            rs.getString("public_key_exponent_hex")
                    );
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error reading card public key: " + e.getMessage());
        }
        return null;
    }

    public static synchronized boolean updateCardPublicKey(String studentId, String modulusHex, String exponentHex) {
        if (studentId == null || studentId.trim().isEmpty()
                || modulusHex == null || modulusHex.trim().isEmpty()
                || exponentHex == null || exponentHex.trim().isEmpty()) {
            return false;
        }
        String sql = "UPDATE cards SET public_key_modulus_hex = ?, public_key_exponent_hex = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE student_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modulusHex.trim());
            ps.setString(2, exponentHex.trim());
            ps.setString(3, studentId.trim());
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error updating card public key: " + e.getMessage());
            return false;
        }
    }

    public static synchronized String getAllCoursesJson() {
        StringBuilder sb = new StringBuilder("[");
        String sql = "SELECT c.course_code, c.course_name, c.lecturer_name, c.room, c.semester, c.schedule_text, "
                + "COALESCE(c.total_sessions, 15) AS total_sessions, "
                + "(SELECT COUNT(*) FROM enrollments e WHERE e.course_code = c.course_code) AS enrolled_count "
                + "FROM courses c ORDER BY c.course_code ASC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                        .append("\"courseCode\":\"").append(esc(rs.getString("course_code"))).append("\",")
                        .append("\"courseName\":\"").append(esc(rs.getString("course_name"))).append("\",")
                        .append("\"lecturerName\":\"").append(esc(rs.getString("lecturer_name"))).append("\",")
                        .append("\"room\":\"").append(esc(rs.getString("room"))).append("\",")
                        .append("\"semester\":\"").append(esc(rs.getString("semester"))).append("\",")
                        .append("\"scheduleText\":\"").append(esc(rs.getString("schedule_text"))).append("\",")
                        .append("\"totalSessions\":").append(rs.getInt("total_sessions")).append(",")
                        .append("\"enrolledCount\":").append(rs.getInt("enrolled_count"))
                        .append("}");
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error querying courses: " + e.getMessage());
        }
        sb.append("]");
        return sb.toString();
    }

    public static synchronized String getEnrolledCoursesJson(String studentId) {
        StringBuilder sb = new StringBuilder("[");
        if (studentId == null || studentId.trim().isEmpty()) {
            sb.append("]");
            return sb.toString();
        }
        String sql = "SELECT c.course_code, c.course_name, c.lecturer_name, c.room, c.semester, c.schedule_text, "
                + "COALESCE(c.total_sessions, 15) AS total_sessions, "
                + "(SELECT COUNT(*) FROM attendance_logs a "
                + " WHERE a.student_id = e.student_id AND a.course_code = c.course_code) AS attended_sessions, "
                + "(SELECT COALESCE(MAX(a.timestamp_seconds), 0) FROM attendance_logs a "
                + " WHERE a.student_id = e.student_id AND a.course_code = c.course_code) AS last_attended_at "
                + "FROM enrollments e "
                + "JOIN courses c ON c.course_code = e.course_code "
                + "WHERE e.student_id = ? "
                + "ORDER BY c.course_code ASC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{")
                            .append("\"courseCode\":\"").append(esc(rs.getString("course_code"))).append("\",")
                            .append("\"courseName\":\"").append(esc(rs.getString("course_name"))).append("\",")
                            .append("\"lecturerName\":\"").append(esc(rs.getString("lecturer_name"))).append("\",")
                            .append("\"room\":\"").append(esc(rs.getString("room"))).append("\",")
                            .append("\"semester\":\"").append(esc(rs.getString("semester"))).append("\",")
                            .append("\"scheduleText\":\"").append(esc(rs.getString("schedule_text"))).append("\",")
                            .append("\"totalSessions\":").append(rs.getInt("total_sessions")).append(",")
                            .append("\"attendedSessions\":").append(rs.getInt("attended_sessions")).append(",")
                            .append("\"lastAttendedAt\":").append(rs.getLong("last_attended_at"))
                            .append("}");
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error querying enrolled courses: " + e.getMessage());
        }
        sb.append("]");
        return sb.toString();
    }

    public static synchronized boolean isStudentEnrolled(String studentId, String courseCode) {
        if (studentId == null || studentId.trim().isEmpty()
                || courseCode == null || courseCode.trim().isEmpty()) {
            return false;
        }
        String sql = "SELECT 1 FROM enrollments WHERE student_id = ? AND course_code = ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            ps.setString(2, courseCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error checking enrollment: " + e.getMessage());
            return false;
        }
    }

    public static synchronized void closeExpiredSessions(long nowSec) {
        String sql = "UPDATE attendance_sessions SET status = 'CLOSED' WHERE status = 'OPEN' AND expires_at <= ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, nowSec);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error closing expired sessions: " + e.getMessage());
        }
    }

    public static synchronized AttendanceSession openAttendanceSession(String courseCode, int durationMinutes, int rotationSeconds) {
        if (courseCode == null || courseCode.trim().isEmpty()) return null;
        String code = courseCode.trim();
        int safeDuration = Math.max(1, Math.min(180, durationMinutes));
        int safeRotation = Math.max(10, Math.min(60, rotationSeconds));
        long nowSec = System.currentTimeMillis() / 1000L;
        long expiresAt = nowSec + safeDuration * 60L;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            String courseName = "";
            String room = "";
            String lecturerName = "";
            String findCourseSql = "SELECT course_name, room, lecturer_name FROM courses WHERE course_code = ?";
            try (PreparedStatement ps = conn.prepareStatement(findCourseSql)) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return null;
                    }
                    courseName = rs.getString("course_name");
                    room = rs.getString("room");
                    lecturerName = rs.getString("lecturer_name");
                }
            }

            try (PreparedStatement closeOld = conn.prepareStatement(
                    "UPDATE attendance_sessions SET status = 'CLOSED' WHERE course_code = ? AND status = 'OPEN'")) {
                closeOld.setString(1, code);
                closeOld.executeUpdate();
            }

            byte[] secretBytes = new byte[32];
            new SecureRandom().nextBytes(secretBytes);
            StringBuilder secretHex = new StringBuilder(64);
            for (byte b : secretBytes) {
                secretHex.append(String.format("%02x", b));
            }

            String insertSql = "INSERT INTO attendance_sessions "
                    + "(course_code, course_name, room, lecturer_name, opened_at, expires_at, rotation_seconds, session_secret, status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')";
            int newId = 0;
            try (PreparedStatement ins = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, code);
                ins.setString(2, courseName);
                ins.setString(3, room);
                ins.setString(4, lecturerName);
                ins.setLong(5, nowSec);
                ins.setLong(6, expiresAt);
                ins.setInt(7, safeRotation);
                ins.setString(8, secretHex.toString());
                ins.executeUpdate();
                try (ResultSet gk = ins.getGeneratedKeys()) {
                    if (gk.next()) newId = gk.getInt(1);
                }
            }
            if (newId <= 0) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    if (rs.next()) newId = rs.getInt(1);
                }
            }

            conn.commit();

            AttendanceSession session = new AttendanceSession();
            session.id = newId;
            session.courseCode = code;
            session.courseName = courseName;
            session.room = room;
            session.lecturerName = lecturerName;
            session.openedAt = nowSec;
            session.expiresAt = expiresAt;
            session.rotationSeconds = safeRotation;
            session.sessionSecret = secretHex.toString();
            session.status = "OPEN";
            return session;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error opening attendance session: " + e.getMessage());
            return null;
        }
    }

    public static synchronized boolean closeAttendanceSession(int sessionId) {
        if (sessionId <= 0) return false;
        String sql = "UPDATE attendance_sessions SET status = 'CLOSED' WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error closing attendance session: " + e.getMessage());
            return false;
        }
    }

    private static AttendanceSession mapSessionRow(ResultSet rs) throws SQLException {
        AttendanceSession s = new AttendanceSession();
        s.id = rs.getInt("id");
        s.courseCode = rs.getString("course_code");
        s.courseName = rs.getString("course_name");
        s.room = rs.getString("room");
        s.lecturerName = rs.getString("lecturer_name");
        s.openedAt = rs.getLong("opened_at");
        s.expiresAt = rs.getLong("expires_at");
        s.rotationSeconds = rs.getInt("rotation_seconds");
        s.sessionSecret = rs.getString("session_secret");
        s.status = rs.getString("status");
        return s;
    }

    public static synchronized AttendanceSession getAttendanceSessionById(int sessionId) {
        if (sessionId <= 0) return null;
        long nowSec = System.currentTimeMillis() / 1000L;
        closeExpiredSessions(nowSec);
        String sql = "SELECT id, course_code, course_name, room, lecturer_name, opened_at, expires_at, "
                + "rotation_seconds, session_secret, status FROM attendance_sessions WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSessionRow(rs);
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error reading attendance session: " + e.getMessage());
        }
        return null;
    }

    public static synchronized List<AttendanceSession> getAllAttendanceSessions() {
        long nowSec = System.currentTimeMillis() / 1000L;
        closeExpiredSessions(nowSec);
        List<AttendanceSession> list = new ArrayList<>();
        String sql = "SELECT id, course_code, course_name, room, lecturer_name, opened_at, expires_at, "
                + "rotation_seconds, session_secret, status FROM attendance_sessions ORDER BY id DESC LIMIT 20";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapSessionRow(rs));
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error listing attendance sessions: " + e.getMessage());
        }
        return list;
    }

    public static synchronized List<AttendanceSession> getActiveSessionsForStudent(String studentId) {
        long nowSec = System.currentTimeMillis() / 1000L;
        closeExpiredSessions(nowSec);
        List<AttendanceSession> list = new ArrayList<>();
        if (studentId == null || studentId.trim().isEmpty()) return list;
        String sql = "SELECT s.id, s.course_code, s.course_name, s.room, s.lecturer_name, "
                + "s.opened_at, s.expires_at, s.rotation_seconds, s.session_secret, s.status "
                + "FROM attendance_sessions s "
                + "JOIN enrollments e ON e.course_code = s.course_code "
                + "WHERE e.student_id = ? AND s.status = 'OPEN' AND s.expires_at > ? "
                + "ORDER BY s.id DESC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            ps.setLong(2, nowSec);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapSessionRow(rs));
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error querying active sessions for student: " + e.getMessage());
        }
        return list;
    }

    public static synchronized boolean hasStudentCheckedIn(int sessionId, String studentId) {
        return getStudentSessionCheckInInfo(sessionId, studentId) != null;
    }

    public static synchronized SessionCheckInInfo getStudentSessionCheckInInfo(int sessionId, String studentId) {
        if (sessionId <= 0 || studentId == null || studentId.trim().isEmpty()) return null;
        String sql = "SELECT timestamp_seconds, COALESCE(checkin_method, 'CARD_READER') AS checkin_method "
                + "FROM attendance_logs WHERE session_id = ? AND student_id = ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            ps.setString(2, studentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SessionCheckInInfo(rs.getLong("timestamp_seconds"), rs.getString("checkin_method"));
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error checking session check-in: " + e.getMessage());
        }
        return null;
    }

    public static synchronized int countCheckedInForSession(int sessionId) {
        if (sessionId <= 0) return 0;
        String sql = "SELECT COUNT(*) FROM attendance_logs WHERE session_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error counting checked-in students: " + e.getMessage());
        }
        return 0;
    }

    public static synchronized int countEnrolledForCourse(String courseCode) {
        if (courseCode == null || courseCode.trim().isEmpty()) return 0;
        String sql = "SELECT COUNT(*) FROM enrollments WHERE course_code = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, courseCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error counting enrolled students: " + e.getMessage());
        }
        return 0;
    }

    public static synchronized String getSessionRosterJson(int sessionId) {
        AttendanceSession session = getAttendanceSessionById(sessionId);
        if (session == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        String sql = "SELECT e.student_id, c.full_name, c.class_name, c.department, "
                + "a.timestamp_seconds, COALESCE(a.checkin_method, '') AS checkin_method, "
                + "COALESCE(a.verified_by_server, 0) AS verified_by_server "
                + "FROM enrollments e "
                + "JOIN cards c ON c.student_id = e.student_id "
                + "LEFT JOIN attendance_logs a ON a.student_id = e.student_id AND a.session_id = ? "
                + "WHERE e.course_code = ? "
                + "ORDER BY (CASE WHEN a.id IS NOT NULL THEN 0 ELSE 1 END) ASC, e.student_id ASC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            ps.setString(2, session.courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(",");
                    first = false;
                    long ts = rs.getLong("timestamp_seconds");
                    boolean checkedIn = !rs.wasNull() && ts > 0;
                    sb.append("{")
                            .append("\"studentId\":\"").append(esc(rs.getString("student_id"))).append("\",")
                            .append("\"fullName\":\"").append(esc(rs.getString("full_name"))).append("\",")
                            .append("\"className\":\"").append(esc(rs.getString("class_name"))).append("\",")
                            .append("\"department\":\"").append(esc(rs.getString("department"))).append("\",")
                            .append("\"checkedIn\":").append(checkedIn).append(",")
                            .append("\"timestampSeconds\":").append(checkedIn ? ts : 0L).append(",")
                            .append("\"checkinMethod\":\"").append(esc(rs.getString("checkin_method"))).append("\",")
                            .append("\"verifiedByServer\":").append(rs.getInt("verified_by_server") != 0)
                            .append("}");
                }
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error querying session roster: " + e.getMessage());
        }
        sb.append("]");
        return sb.toString();
    }

    public static synchronized CheckInCommitResult insertVerifiedSessionAttendance(
            int sessionId, String studentId, String courseCode, String courseName,
            long timestampSeconds, String rsaSignatureHex, String checkinMethod) {
        if (sessionId <= 0 || studentId == null || studentId.trim().isEmpty()) {
            return new CheckInCommitResult(false, false, 0, 0, "Thông tin điểm danh không hợp lệ.");
        }
        String sid = studentId.trim();
        String methodNorm = (checkinMethod != null && !checkinMethod.trim().isEmpty())
                ? checkinMethod.trim().toUpperCase(java.util.Locale.ROOT)
                : "QR_SCAN";
        long ts = timestampSeconds > 0 ? timestampSeconds : (System.currentTimeMillis() / 1000L);
        String sigHex = rsaSignatureHex != null ? rsaSignatureHex : "";
        String fullCourseDisplay = (courseName != null && !courseName.trim().isEmpty())
                ? (courseName.startsWith(courseCode) ? courseName : (courseCode + " - " + courseName))
                : courseCode;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String insertSql = "INSERT INTO attendance_logs "
                        + "(student_id, course_code, course_name, timestamp_seconds, rsa_signature_hex, "
                        + "verified_by_server, session_id, checkin_method) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, sid);
                    ps.setString(2, courseCode);
                    ps.setString(3, fullCourseDisplay);
                    ps.setLong(4, ts);
                    ps.setString(5, sigHex);
                    ps.setInt(6, sessionId);
                    ps.setString(7, methodNorm);
                    ps.executeUpdate();
                }

                String updateCardSql = "UPDATE cards SET attendance_count = attendance_count + 1, "
                        + "training_score = MIN(100, training_score + 1), updated_at = CURRENT_TIMESTAMP "
                        + "WHERE student_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateCardSql)) {
                    ps.setString(1, sid);
                    ps.executeUpdate();
                }

                int attCount = 0;
                int score = 85;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT attendance_count, training_score FROM cards WHERE student_id = ?")) {
                    ps.setString(1, sid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            attCount = rs.getInt("attendance_count");
                            score = rs.getInt("training_score");
                        }
                    }
                }

                insertAuditLogInternal(conn, sid, "ATTENDANCE_" + methodNorm, sessionId,
                        "Điểm danh [" + methodNorm + "] môn " + courseCode + " (Phiên #" + sessionId + ")", sigHex);

                conn.commit();
                return new CheckInCommitResult(true, false, attCount, score, "");
            } catch (SQLException sqle) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                String msg = sqle.getMessage() != null ? sqle.getMessage().toUpperCase(java.util.Locale.ROOT) : "";
                if (msg.contains("UNIQUE") || msg.contains("CONSTRAINT")) {
                    return new CheckInCommitResult(false, true, 0, 0, "Bạn đã điểm danh buổi học này rồi.");
                }
                System.err.println("[SQLite DB] SQL error inserting session attendance: " + sqle.getMessage());
                return new CheckInCommitResult(false, false, 0, 0, "Lỗi ghi nhận điểm danh: " + sqle.getMessage());
            }
        } catch (Exception e) {
            System.err.println("[SQLite DB] Error inserting verified session attendance: " + e.getMessage());
            return new CheckInCommitResult(false, false, 0, 0, "Lỗi kết nối cơ sở dữ liệu.");
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
