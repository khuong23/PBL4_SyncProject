package com.pbl4.syncproject.client.services;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * Quản lý CSDL SQLite cục bộ (client-side cache).
 * Sử dụng Singleton pattern giống ClientConnectionManager.
 */
public class LocalDatabaseManager {

    private static LocalDatabaseManager instance;
    private static final String DB_URL = "jdbc:sqlite:client_cache.db";

    private LocalDatabaseManager() {
        // Tải driver
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("Không tìm thấy driver SQLite. Hãy kiểm tra file pom.xml.");
            e.printStackTrace();
        }
    }

    /**
     * Lấy instance duy nhất của ConnectionManager
     */
    public static synchronized LocalDatabaseManager getInstance() {
        if (instance == null) {
            instance = new LocalDatabaseManager();
        }
        return instance;
    }

    /**
     * Lấy một kết nối mới đến CSDL SQLite.
     * (Với SQLite, việc tạo kết nối rất nhẹ, không cần pool)
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    /**
     * Khởi tạo CSDL: Tạo các bảng nếu chúng chưa tồn tại.
     * Đây là nơi định nghĩa cấu trúc cache offline.
     */
    public void initializeDatabase() {
        String sqlCreateFolders = "CREATE TABLE IF NOT EXISTS Folders ("
                + "FolderID INTEGER PRIMARY KEY, " // ID của thư mục trên server
                + "ParentFolderID INTEGER, "
                + "FolderName TEXT NOT NULL, "
                + "SyncStatus TEXT NOT NULL DEFAULT 'SYNCED' " // SYNCED, LOCAL_CREATED, LOCAL_DELETED
                + ");";

        String sqlCreateFiles = "CREATE TABLE IF NOT EXISTS Files ("
                + "FileID INTEGER PRIMARY KEY, " // ID của file trên server
                + "FolderID INTEGER NOT NULL, "
                + "FileName TEXT NOT NULL, "
                + "FileSize BIGINT, "
                + "LocalPath TEXT NOT NULL UNIQUE, " // Đường dẫn file trên máy client
                + "LastKnownHash CHAR(64), "       // Hash khi đồng bộ lần cuối
                + "SyncStatus TEXT NOT NULL DEFAULT 'SYNCED' " // SYNCED, LOCAL_MODIFIED, LOCAL_DELETED, CONFLICT
                + ");";

        // --- THAY THẾ ĐỊNH NGHĨA BẢNG NÀY ---
        String sqlCreateSyncQueue = "CREATE TABLE IF NOT EXISTS SyncQueue ("
                + "QueueID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "Action TEXT NOT NULL, " // UPLOAD, DELETE_FILE, CREATE_FOLDER, DELETE_FOLDER
                
                // Dùng cho File (UPLOAD, DELETE_FILE)
                + "LocalPath TEXT, " 
                
                // Dùng cho Folder (CREATE_FOLDER, DELETE_FOLDER)
                + "TargetFolderID INTEGER, "   // ID của folder (để DELETE_FOLDER)
                + "TargetParentID INTEGER, " // ID cha (để CREATE_FOLDER)
                + "TargetName TEXT, "        // Tên thư mục mới (để CREATE_FOLDER)
                
                + "RetryCount INTEGER DEFAULT 0"
                + ");";
        // -------------------------------------

        // --- THÊM BẢNG NÀY (BƯỚC 6.1) ---
        String sqlCreateSettings = "CREATE TABLE IF NOT EXISTS Settings ("
                + "Key TEXT PRIMARY KEY, "
                + "Value TEXT "
                + ");";
        // ---------------------

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Thực thi tạo các bảng
            stmt.execute(sqlCreateFolders);
            stmt.execute(sqlCreateFiles);
            stmt.execute(sqlCreateSyncQueue);
            
            // --- THỰC THI TẠO BẢNG MỚI ---
            stmt.execute(sqlCreateSettings);
            // ---------------------------
            
            System.out.println("✅ CSDL cục bộ (SQLite) đã được khởi tạo/sẵn sàng.");

        } catch (SQLException e) {
            System.err.println("❌ Lỗi nghiêm trọng khi khởi tạo CSDL cục bộ:");
            e.printStackTrace();
        }
    }

    // --- THÊM 2 HÀM MỚI SAU VÀO CUỐI FILE (BƯỚC 6.1) ---

    /**
     * Lấy mốc thời gian đồng bộ cuối cùng từ CSDL.
     * Mặc định là '0' (đầu mốc thời gian Unix) nếu chưa từng đồng bộ.
     */
    public Timestamp getLastSyncTime() {
        String sql = "SELECT Value FROM Settings WHERE Key = 'last_sync_timestamp'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new Timestamp(Long.parseLong(rs.getString("Value")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        // Mặc định: 1970-01-01 (Lấy tất cả mọi thứ)
        return new Timestamp(0L); 
    }

    /**
     * Cập nhật mốc thời gian đồng bộ cuối cùng.
     */
    public void setLastSyncTime(Timestamp timestamp) {
        String sql = "INSERT OR REPLACE INTO Settings (Key, Value) VALUES ('last_sync_timestamp', ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(timestamp.getTime()));
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    // ----------------------------------------
}
