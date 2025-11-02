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
    
    // --- ĐỊNH NGHĨA CÁC TRẠNG THÁI ĐỒNG BỘ ---
    public static final String STATUS_SYNCED = "SYNCED";           // Đã đồng bộ (trùng khớp server)
    public static final String STATUS_LOCAL_NEW = "LOCAL_NEW";     // File mới chỉ có ở client (màu XANH)
    public static final String STATUS_LOCAL_STALE = "LOCAL_STALE"; // File đã sửa ở client (màu VÀNG)
    public static final String STATUS_SERVER_NEW = "SERVER_NEW";   // File mới chỉ có ở server
    public static final String STATUS_CONFLICT = "CONFLICT";       // Xung đột (màu ĐỎ)
    public static final String STATUS_LOCAL_DELETED = "LOCAL_DELETED"; // File đã xóa ở client
    // ------------------------------------------

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
                + "FolderID INTEGER PRIMARY KEY AUTOINCREMENT, " // Local ID (stable, auto-increment)
                + "ServerFolderID INTEGER UNIQUE, " // Server ID (from server response)
                + "ParentFolderID INTEGER, " // Local FolderID of parent
                + "FolderName TEXT NOT NULL, "
                + "LocalPath TEXT, " // Đường dẫn thư mục trên máy client (UNIQUE qua index)
                + "SyncStatus TEXT NOT NULL DEFAULT 'SYNCED', " // SYNCED, LOCAL_CREATED, LOCAL_DELETED
                + "FOREIGN KEY(ParentFolderID) REFERENCES Folders(FolderID)"
                + ");";

        String sqlCreateFiles = "CREATE TABLE IF NOT EXISTS Files ("
                + "FileID INTEGER PRIMARY KEY AUTOINCREMENT, " // Local ID (stable, auto-increment)
                + "ServerFileID INTEGER UNIQUE, " // Server ID (from server response)
                + "FolderID INTEGER NOT NULL, " // Local FolderID (FK to Folders)
                + "FileName TEXT NOT NULL, "
                + "FileSize BIGINT, "
                + "LocalPath TEXT NOT NULL UNIQUE, " // Đường dẫn file trên máy client
                + "LastKnownHash CHAR(64), "       // Hash khi đồng bộ lần cuối
                + "SyncStatus TEXT NOT NULL DEFAULT 'SYNCED', " // SYNCED, LOCAL_MODIFIED, LOCAL_DELETED, CONFLICT
                + "FOREIGN KEY(FolderID) REFERENCES Folders(FolderID)"
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
            
            // --- MIGRATION: Thêm các cột mới nếu chưa có ---
            
            // Migration 1: LocalPath
            try {
                stmt.execute("ALTER TABLE Folders ADD COLUMN LocalPath TEXT;");
                System.out.println("✅ Migration: Đã thêm cột LocalPath vào bảng Folders.");
            } catch (SQLException e) {
                if (!e.getMessage().contains("duplicate column name")) {
                    System.err.println("⚠️ Migration warning (LocalPath): " + e.getMessage());
                }
            }
            
            // Migration 2: ServerFolderID
            try {
                stmt.execute("ALTER TABLE Folders ADD COLUMN ServerFolderID INTEGER UNIQUE;");
                System.out.println("✅ Migration: Đã thêm cột ServerFolderID vào bảng Folders.");
            } catch (SQLException e) {
                if (!e.getMessage().contains("duplicate column name")) {
                    System.err.println("⚠️ Migration warning (ServerFolderID): " + e.getMessage());
                }
            }
            
            // Migration 3: ServerFileID
            try {
                stmt.execute("ALTER TABLE Files ADD COLUMN ServerFileID INTEGER UNIQUE;");
                System.out.println("✅ Migration: Đã thêm cột ServerFileID vào bảng Files.");
            } catch (SQLException e) {
                if (!e.getMessage().contains("duplicate column name")) {
                    System.err.println("⚠️ Migration warning (ServerFileID): " + e.getMessage());
                }
            }
            
            // --- Tạo indexes (SAU KHI migration hoàn tất) ---
            try {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_folders_localpath ON Folders(LocalPath);");
                System.out.println("✅ Đã tạo/kiểm tra unique index cho Folders.LocalPath.");
            } catch (SQLException e) {
                if (!e.getMessage().contains("already exists")) {
                    System.err.println("⚠️ Index warning: " + e.getMessage());
                }
            }
            // -----------------------------------------------------------------
            
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

    /**
     * Cập nhật trạng thái đồng bộ của file trong cache.
     * @param localPath Đường dẫn tương đối của file (relativePath)
     * @param newStatus Trạng thái mới (STATUS_SYNCED, STATUS_LOCAL_NEW, etc.)
     */
    public void updateFileStatus(String localPath, String newStatus) {
        String sql = "UPDATE Files SET SyncStatus = ? WHERE LocalPath = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, localPath);
            int rowsAffected = ps.executeUpdate();
            
            if (rowsAffected > 0) {
                System.out.println("✅ Đã cập nhật trạng thái file: " + localPath + " -> " + newStatus);
            } else {
                System.out.println("⚠️ Không tìm thấy file trong cache: " + localPath);
            }
        } catch (SQLException e) {
            System.err.println("❌ Lỗi khi cập nhật trạng thái file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Cập nhật cả trạng thái và hash của file (dùng sau khi upload thành công).
     * @param localPath Đường dẫn tương đối của file
     * @param newStatus Trạng thái mới (thường là SYNCED)
     * @param newHash Hash mới nhất của file (hash đã được upload)
     */
    public void updateFileStatusAndHash(String localPath, String newStatus, String newHash) {
        String sql = "UPDATE Files SET SyncStatus = ?, LastKnownHash = ? WHERE LocalPath = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, newHash);
            ps.setString(3, localPath);
            int rowsAffected = ps.executeUpdate();
            
            if (rowsAffected > 0) {
                System.out.println("✅ Đã cập nhật (Sync + Hash): " + localPath + " -> " + newStatus);
            } else {
                System.out.println("⚠️ Không tìm thấy file trong cache: " + localPath);
            }
        } catch (SQLException e) {
            System.err.println("❌ Lỗi khi cập nhật trạng thái và hash: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Ghi đè (Upsert) thông tin file vào CSDL cache sau khi tải về.
     * Đảm bảo file được đánh dấu là SYNCED và có hash chính xác.
     * @param serverFileId ID của file trên server
     * @param localFolderId ID của folder local trong cache
     * @param fileName Tên file
     * @param fileSize Kích thước file (bytes)
     * @param localPath Đường dẫn tương đối (relativePath)
     * @param fileHash Hash SHA-256 của file vừa tải
     */
    public void upsertDownloadedFile(int serverFileId, int localFolderId, String fileName, 
                                     long fileSize, String localPath, String fileHash) {
                                         
        // Câu lệnh này sẽ TẠO MỚI nếu file chưa có, hoặc CẬP NHẬT nếu đã có
        String sql = "INSERT OR REPLACE INTO Files "
                   + "(ServerFileID, FolderID, FileName, FileSize, LocalPath, LastKnownHash, SyncStatus) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, serverFileId);
            ps.setInt(2, localFolderId);
            ps.setString(3, fileName);
            ps.setLong(4, fileSize);
            ps.setString(5, localPath);
            ps.setString(6, fileHash);
            ps.setString(7, STATUS_SYNCED); // Đánh dấu là đã đồng bộ
            
            ps.executeUpdate();
            System.out.println("✅ Đã Upsert (Download): " + localPath + " -> SYNCED");

        } catch (SQLException e) {
            System.err.println("❌ Lỗi khi upsert file đã tải: " + e.getMessage());
            e.printStackTrace();
        }
    }
    // ----------------------------------------
}
