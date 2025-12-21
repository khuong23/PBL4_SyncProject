package com.pbl4.syncproject.client.services;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
// --- FIX RACE CONDITION: Thêm HikariCP ---
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
// -----------------------------------------

/**
 * Dùng initializeDatabase(true) để reset sạch và tạo schema chuẩn.
 * FIX: Đã chuyển sang dùng HikariCP (Connection Pool) với maxPoolSize=1
 * để giải quyết triệt để lỗi race condition (tranh chấp) của SQLite
 * khi nhiều luồng (FileWatcher, SyncQueue) truy cập cùng lúc.
 */
public class LocalDatabaseManager {

    private static LocalDatabaseManager instance;
    public static synchronized LocalDatabaseManager getInstance() {
        if (instance == null) instance = new LocalDatabaseManager();
        return instance;
    }

    private static final String DB_URL = "jdbc:sqlite:client_cache.db";
    
    // --- FIX: Thêm HikariCP DataSource ---
    private final HikariDataSource ds;
    // -------------------------------------

    // ===== Trạng thái đồng bộ =====
    public static final String STATUS_SYNCED        = "SYNCED";
    public static final String STATUS_LOCAL_NEW     = "LOCAL_NEW";
    public static final String STATUS_LOCAL_STALE   = "LOCAL_STALE";
    public static final String STATUS_SERVER_NEW    = "SERVER_NEW";
    public static final String STATUS_CONFLICT      = "CONFLICT";
    public static final String STATUS_LOCAL_DELETED = "LOCAL_DELETED";

    private LocalDatabaseManager() {
        try {
            Class.forName("org.sqlite.JDBC");
            ensureDbDirExists();

            // --- FIX: CẤU HÌNH HIKARI POOL ---
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(DB_URL);
            
            // QUAN TRỌNG: Buộc SQLite chạy tuần tự (từng luồng một)
            // Bằng cách chỉ cho phép 1 kết nối duy nhất trong pool.
            config.setMaximumPoolSize(1); 
            
            config.setConnectionTimeout(10000); // Tăng thời gian chờ kết nối
            config.setValidationTimeout(3000);
            
            // Đặt các PRAGMA 1 lần duy nhất khi kết nối được tạo
            config.setConnectionInitSql(
                "PRAGMA foreign_keys = ON; " +
                "PRAGMA journal_mode = WAL; " +
                "PRAGMA synchronous = NORMAL; " +
                "PRAGMA busy_timeout = 5000;"
            );
        
            this.ds = new HikariDataSource(config);
            System.out.println("✅ HikariCP initialized for SQLite with maxPoolSize=1");
            // ---------------------------------

        } catch (Exception e) {
            System.err.println("Không tìm thấy driver SQLite hoặc lỗi pool: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Không thể khởi tạo CSDL local", e);
        }
    }

    private void ensureDbDirExists() {
        try {
            Path p = Path.of(DB_URL.replace("jdbc:sqlite:", ""));
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception ignored) {}
    }
    
    /**
     * FIX: Lấy kết nối TỪ POOL thay vì tạo mới
     */
    public Connection getConnection() throws SQLException {
        // --- SỬA LẠI: Lấy kết nối từ pool ---
        return ds.getConnection();
        // Không cần chạy PRAGMA nữa vì pool đã tự làm (ConnectionInitSql)
        // ------------------------------------
    }
    /**
     * Khởi tạo CSDL.
     * fresh true = drop sạch & tạo chuẩn; false = chỉ tạo nếu chưa có.
     * An toàn bằng transaction; không dùng writable_schema.
     */
    public void initializeDatabase(boolean fresh) {
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    if (fresh) {
                        st.executeUpdate("DROP TABLE IF EXISTS Files;");
                        st.executeUpdate("DROP TABLE IF EXISTS Folders;");
                        st.executeUpdate("DROP TABLE IF EXISTS SyncQueue;");
                        st.executeUpdate("DROP TABLE IF EXISTS Settings;");
                    } else {
                        // Kiểm tra xem các bảng quan trọng đã tồn tại chưa
                        if (!tableExists(c, "Folders") || !tableExists(c, "Files") || !tableExists(c, "SyncQueue")) {
                            System.out.println("⚠️ Phát hiện CSDL thiếu bảng, đang tạo lại schema...");
                            // Drop tất cả để tạo sạch
                            st.executeUpdate("DROP TABLE IF EXISTS Files;");
                            st.executeUpdate("DROP TABLE IF EXISTS Folders;");
                            st.executeUpdate("DROP TABLE IF EXISTS SyncQueue;");
                            st.executeUpdate("DROP TABLE IF EXISTS Settings;");
                        }
                    }
                    createSchema(c);
                    st.execute("PRAGMA user_version = 1;");
                }
                c.commit();
                System.out.println("✅ SQLite cache sẵn sàng (schema chuẩn, " + (fresh ? "fresh" : "warm") + ").");
            } catch (SQLException e) {
                c.rollback();
                System.err.println("❌ Khởi tạo CSDL cục bộ lỗi: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Không thể khởi tạo CSDL local", e);
            }
        } catch (SQLException e) {
            System.err.println("❌ NGHIÊM TRỌNG: Không thể kết nối CSDL local: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Không thể khởi tạo CSDL local", e);
        }
    }
    
    /**
     * Kiểm tra xem một bảng có tồn tại trong CSDL không
     */
    private boolean tableExists(Connection c, String tableName) throws SQLException {
        try (var rs = c.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private void createSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            // Folders: ánh xạ 1-1 với server folder, cascade con khi xóa
            st.execute("""
                CREATE TABLE IF NOT EXISTS Folders (
                  FolderID         INTEGER PRIMARY KEY AUTOINCREMENT,
                  ServerFolderID   INTEGER UNIQUE,
                  ParentFolderID   INTEGER,
                  FolderName       TEXT NOT NULL,
                  LocalPath        TEXT,
                  ServerVersion    INTEGER NOT NULL DEFAULT 1,
                  SyncStatus       TEXT NOT NULL DEFAULT 'SYNCED',
                  FOREIGN KEY(ParentFolderID) REFERENCES Folders(FolderID) ON DELETE CASCADE
                );
            """);
            // Files: unique theo ServerFileID + unique LocalPath; chặn trùng tên trong cùng Folder
            st.execute("""
                CREATE TABLE IF NOT EXISTS Files (
                  FileID            INTEGER PRIMARY KEY AUTOINCREMENT,
                  ServerFileID      INTEGER UNIQUE,
                  FolderID          INTEGER NOT NULL,
                  FileName          TEXT NOT NULL,
                  FileSize          INTEGER,
                  LocalPath         TEXT NOT NULL UNIQUE,
                  LastKnownHash     TEXT,
                  LastKnownVersion  INTEGER NOT NULL DEFAULT 1,
                  SyncStatus        TEXT NOT NULL DEFAULT 'SYNCED',
                  FOREIGN KEY(FolderID) REFERENCES Folders(FolderID) ON DELETE CASCADE
                );
            """);
            // Hàng đợi đồng bộ
            st.execute("""
                CREATE TABLE IF NOT EXISTS SyncQueue (
                  QueueID        INTEGER PRIMARY KEY AUTOINCREMENT,
                  Action         TEXT NOT NULL,      -- UPLOAD, DELETE_FILE, CREATE_FOLDER, DELETE_FOLDER
                  LocalPath      TEXT,
                  TargetFolderID INTEGER,
                  TargetParentID INTEGER,
                  TargetName     TEXT,
                  RetryCount     INTEGER DEFAULT 0
                );
            """);
            // Settings: since_seq…
            st.execute("""
                CREATE TABLE IF NOT EXISTS Settings (
                  Key   TEXT PRIMARY KEY,
                  Value TEXT
                );
            """);
            // Indexes
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_folders_localpath ON Folders(LocalPath);");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_serverid    ON Files(ServerFileID);");
            st.execute("CREATE        INDEX IF NOT EXISTS idx_files_folder      ON Files(FolderID);");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_folder_name ON Files(FolderID, FileName);");
            
            // GIẢI PHÁP B: LUÔN TẠO LẠI ROOT FOLDER SAU KHI RESET
            // Đảm bảo root folder (ServerFolderID=1) luôn tồn tại
            st.execute("""
                INSERT OR IGNORE INTO Folders (FolderID, ServerFolderID, ParentFolderID, FolderName, LocalPath, SyncStatus)
                VALUES (1, 1, NULL, 'Root', '', 'SYNCED')
            """);
        }
    }
    // ===== since_seq (change feed) =====
    public long getSinceSeq() {
        final String sql = "SELECT Value FROM Settings WHERE Key='since_seq'";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return Long.parseLong(rs.getString(1));
        } catch (Exception ignored) {}
        return 0L;
    }

    public void setSinceSeq(long seq) {
        final String sql = """
            INSERT INTO Settings(Key, Value) VALUES('since_seq', ?)
            ON CONFLICT(Key) DO UPDATE SET Value = excluded.Value
        """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(seq));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("❌ setSinceSeq lỗi: " + e.getMessage());
        }
    }
    // ===== Cập nhật trạng thái file =====
    public boolean updateFileStatus(String localPath, String newStatus) {
        final String sql = "UPDATE Files SET SyncStatus=? WHERE LocalPath=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, localPath);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ updateFileStatus lỗi: " + e.getMessage());
            return false;
        }
    }
    public boolean updateFileStatusAndHash(String localPath, String newStatus, String newHash) {
        final String sql = "UPDATE Files SET SyncStatus=?, LastKnownHash=? WHERE LocalPath=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, newHash);
            ps.setString(3, localPath);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ updateFileStatusAndHash lỗi: " + e.getMessage());
            return false;
        }
    }
    public boolean updateFileStatusHashAndVersion(String localPath, String newStatus, String newHash, int newVersion) {
        final String sql = "UPDATE Files SET SyncStatus=?, LastKnownHash=?, LastKnownVersion=? WHERE LocalPath=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, newHash);
            ps.setInt(3, newVersion);
            ps.setString(4, localPath);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ updateFileStatusHashAndVersion lỗi: " + e.getMessage());
            return false;
        }
    }
    // ===== Upsert khi down-sync =====
    public void upsertDownloadedFile(int serverFileId,
                                     int localFolderId,
                                     String fileName,
                                     long fileSize,
                                     String localPath,
                                     String fileHash,
                                     int serverVersion) throws SQLException {
        // Xử lý cả 2 UNIQUE constraints: ServerFileID và LocalPath
        // Nếu file đã tồn tại (theo LocalPath hoặc ServerFileID), update nó
        final String sql = """
            INSERT INTO Files (ServerFileID, FolderID, FileName, FileSize, LocalPath, LastKnownHash, LastKnownVersion, SyncStatus)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'SYNCED')
            ON CONFLICT(ServerFileID) DO UPDATE SET
              FolderID         = excluded.FolderID,
              FileName         = excluded.FileName,
              FileSize         = excluded.FileSize,
              LocalPath        = excluded.LocalPath,
              LastKnownHash    = excluded.LastKnownHash,
              LastKnownVersion = excluded.LastKnownVersion,
              SyncStatus       = 'SYNCED'
        """;
        
        try (Connection c = getConnection()) {
            // BƯỚC 1: Xóa conflict trên LocalPath
            // Xóa bất kỳ file nào khác đang dùng LocalPath này
            try (PreparedStatement psCheck1 = c.prepareStatement(
                    "DELETE FROM Files WHERE LocalPath = ? AND (ServerFileID IS NULL OR ServerFileID != ?)")) {
                psCheck1.setString(1, localPath);
                psCheck1.setInt(2, serverFileId);
                int deleted1 = psCheck1.executeUpdate();
                if (deleted1 > 0) {
                    System.out.println("🔄 Đã xóa conflict LocalPath tại " + localPath);
                }
            }
            
            // BƯỚC 2: Xóa conflict trên (FolderID, FileName)
            // Xóa bất kỳ file nào khác trong cùng thư mục có cùng tên
            try (PreparedStatement psCheck2 = c.prepareStatement(
                    "DELETE FROM Files WHERE FolderID = ? AND FileName = ? AND (ServerFileID IS NULL OR ServerFileID != ?)")) {
                psCheck2.setInt(1, localFolderId);
                psCheck2.setString(2, fileName);
                psCheck2.setInt(3, serverFileId);
                int deleted2 = psCheck2.executeUpdate();
                if (deleted2 > 0) {
                    System.out.println("🔄 Đã xóa conflict (FolderID, FileName) cho: " + fileName);
                }
            }
            
            // BƯỚC 3: Bây giờ INSERT hoặc UPDATE (ON CONFLICT(ServerFileID))
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, serverFileId);
                ps.setInt(2, localFolderId);
                ps.setString(3, fileName);
                ps.setLong(4, fileSize);
                ps.setString(5, localPath);
                ps.setString(6, fileHash);
                ps.setInt(7, serverVersion);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("❌ upsertDownloadedFile lỗi: " + e.getMessage());
            e.printStackTrace();
            throw e; // Ném lại exception để caller biết lỗi xảy ra
        }
    }
    public void upsertDownloadedFile(int serverFileId,
                                     int localFolderId,
                                     String fileName,
                                     long fileSize,
                                     String localPath,
                                     String fileHash) throws SQLException {
        upsertDownloadedFile(serverFileId, localFolderId, fileName, fileSize, localPath, fileHash, 0);
    }
    
    // ===== Query files from local DB =====
    /** Lấy danh sách files trong một folder từ local DB (để hiển thị UI) */
    public java.util.List<java.util.Map<String, Object>> getFilesInFolder(int localFolderId) {
        final String sql = """
            SELECT FileID, ServerFileID, FolderID, FileName, FileSize, LocalPath, 
                   LastKnownHash, LastKnownVersion, SyncStatus
            FROM Files
            WHERE FolderID = ?
            ORDER BY FileName
        """;
        
        java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, localFolderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> row = new java.util.HashMap<>();
                    row.put("fileId", rs.getInt("FileID"));
                    row.put("serverFileId", rs.getObject("ServerFileID")); // Có thể null
                    row.put("folderId", rs.getInt("FolderID"));
                    row.put("fileName", rs.getString("FileName"));
                    row.put("fileSize", rs.getLong("FileSize"));
                    row.put("localPath", rs.getString("LocalPath"));
                    row.put("hash", rs.getString("LastKnownHash"));
                    row.put("version", rs.getInt("LastKnownVersion"));
                    row.put("syncStatus", rs.getString("SyncStatus"));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ getFilesInFolder lỗi: " + e.getMessage());
        }
        return results;
    }
    
    // ===== Tiện ích thêm =====
    /** Dựng đường dẫn tương đối từ FolderID về gốc. */
    public String buildRelativePath(int folderId) {
        final String sql = """
            WITH RECURSIVE chain(id, name, parent, depth) AS (
              SELECT FolderID, FolderName, ParentFolderID, 0 FROM Folders WHERE FolderID = ?
              UNION ALL
              SELECT f.FolderID, f.FolderName, f.ParentFolderID, chain.depth + 1
              FROM Folders f JOIN chain ON f.FolderID = chain.parent
            )
            SELECT GROUP_CONCAT(name, '/') AS rel
            FROM (SELECT name FROM chain ORDER BY depth DESC);
        """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException ignored) {}
        return "";
    }

    // ==== Transaction helpers / batch apply =====
    @FunctionalInterface
    public interface SQLCallable<T> { T call(Connection c) throws Exception; }

    public <T> T withTransaction(SQLCallable<T> work) {
        try (Connection c = getConnection()) {
            boolean old = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T res = work.call(c);
                c.commit();
                c.setAutoCommit(old);
                return res;
            } catch (Exception ex) {
                c.rollback();
                c.setAutoCommit(old);
                throw ex;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Áp nhiều thay đổi server rồi cập nhật since_seq atomically. */
    public boolean applyServerChangeBatch(long newSinceSeq, SQLCallable<Void> applier) {
        try {
            return withTransaction(c -> {
                applier.call(c);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO Settings(Key,Value) VALUES('since_seq',?) " +
                                "ON CONFLICT(Key) DO UPDATE SET Value=excluded.Value")) {
                    ps.setString(1, String.valueOf(newSinceSeq));
                    ps.executeUpdate();
                }
                return true;
            });
        } catch (RuntimeException re) {
            System.err.println("❌ applyServerChangeBatch rollback: " + re.getMessage());
            return false;
        }
    }
    // ==== Folder mapping / upsert ====
    public Integer getLocalFolderIdByServerId(int serverFolderId) {
        final String sql = "SELECT FolderID FROM Folders WHERE ServerFolderID=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, serverFolderId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        } catch (SQLException ignored) {}
        return null;
    }

    public Integer getLocalFolderIdByLocalPath(String localPath) {
        final String sql = "SELECT FolderID FROM Folders WHERE LocalPath=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, localPath);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        } catch (SQLException ignored) {}
        return null;
    }

    private Integer findLocalFolderIdByServerId(Connection c, int serverFolderId) throws SQLException {
        final String sql = "SELECT FolderID FROM Folders WHERE ServerFolderID=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, serverFolderId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        }
        return null;
    }

    /** Upsert folder theo ServerID, tự resolve parent theo ServerParentID, trả về FolderID local. */
    public int upsertFolderByServerIds(int serverFolderId,
                                       Integer parentServerFolderId,
                                       String folderName,
                                       String localPath,
                                       int serverVersion) {
        return withTransaction(c -> {
            Integer parentLocalId = null;
            if (parentServerFolderId != null) {
                parentLocalId = findLocalFolderIdByServerId(c, parentServerFolderId);
                if (parentLocalId == null) {
                    // tạo placeholder parent (tối thiểu)
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO Folders(ServerFolderID, FolderName, ServerVersion, SyncStatus) " +
                                    "VALUES(?, ?, 0, 'SYNCED')",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, parentServerFolderId);
                        ps.setString(2, "folder-" + parentServerFolderId);
                        ps.executeUpdate();
                        try (ResultSet g = ps.getGeneratedKeys()) { if (g.next()) parentLocalId = g.getInt(1); }
                    }
                }
            }

            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO Folders(ServerFolderID, ParentFolderID, FolderName, LocalPath, ServerVersion, SyncStatus)
                    VALUES(?, ?, ?, ?, ?, 'SYNCED')
                    ON CONFLICT(ServerFolderID) DO UPDATE SET
                      ParentFolderID = excluded.ParentFolderID,
                      FolderName     = excluded.FolderName,
                      LocalPath      = excluded.LocalPath,
                      ServerVersion  = excluded.ServerVersion,
                      SyncStatus     = 'SYNCED'
                    """)) {
                ps.setInt(1, serverFolderId);
                if (parentLocalId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, parentLocalId);
                ps.setString(3, folderName);
                ps.setString(4, localPath);
                ps.setInt(5, serverVersion);
                ps.executeUpdate();
            }

            Integer localId = findLocalFolderIdByServerId(c, serverFolderId);
            return localId != null ? localId : -1;
        });
    }

    // ==== Folder rename/move & cascade path ====
    public boolean renameFolder(int folderId, String newName) {
        final String sql = "UPDATE Folders SET FolderName=? WHERE FolderID=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setInt(2, folderId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ renameFolder lỗi: " + e.getMessage());
            return false;
        }
    }

    public boolean moveFolder(int folderId, Integer newParentFolderId) {
        final String sql = "UPDATE Folders SET ParentFolderID=? WHERE FolderID=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (newParentFolderId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, newParentFolderId);
            ps.setInt(2, folderId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ moveFolder lỗi: " + e.getMessage());
            return false;
        }
    }

    /** Cập nhật LocalPath hàng loạt khi thư mục đổi tên/đổi vị trí. */
    public int cascadeUpdateLocalPathsForFolder(int folderId, String oldPrefix, String newPrefix) {
        int total = 0;
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps1 = c.prepareStatement(
                    "UPDATE Folders SET LocalPath = REPLACE(LocalPath, ?, ?) WHERE LocalPath LIKE ?")) {
                ps1.setString(1, oldPrefix);
                ps1.setString(2, newPrefix);
                ps1.setString(3, oldPrefix + "/%");
                total += ps1.executeUpdate();
            }
            try (PreparedStatement ps2 = c.prepareStatement(
                    "UPDATE Files SET LocalPath = REPLACE(LocalPath, ?, ?) WHERE LocalPath LIKE ?")) {
                ps2.setString(1, oldPrefix);
                ps2.setString(2, newPrefix);
                ps2.setString(3, oldPrefix + "/%");
                total += ps2.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            System.err.println("❌ cascadeUpdateLocalPathsForFolder lỗi: " + e.getMessage());
        }
        return total;
        // Lưu ý: vì UNIQUE(LocalPath), hãy đảm bảo newPrefix không gây va chạm trước khi gọi hàm này.
    }

    // ==== File move/rename / attach ServerFileID ====
    public boolean moveOrRenameFileByServerId(int serverFileId, int newFolderId, String newFileName, String newLocalPath) {
        final String sql = "UPDATE Files SET FolderID=?, FileName=?, LocalPath=?, SyncStatus='SYNCED' WHERE ServerFileID=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, newFolderId);
            ps.setString(2, newFileName);
            ps.setString(3, newLocalPath);
            ps.setInt(4, serverFileId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ moveOrRenameFileByServerId lỗi: " + e.getMessage());
            return false;
        }
    }

    /** Sau khi upload thành công, gắn ServerFileID + cập nhật hash/version. */
    public boolean attachServerFileId(String localPath, int serverFileId, String newHash, int newVersion) {
        final String sql = "UPDATE Files SET ServerFileID=?, LastKnownHash=?, LastKnownVersion=?, SyncStatus='SYNCED' WHERE LocalPath=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, serverFileId);
            ps.setString(2, newHash);
            ps.setInt(3, newVersion);
            ps.setString(4, localPath);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ attachServerFileId lỗi: " + e.getMessage());
            return false;
        }
    }
    // ==== SyncQueue tiện ích ====
    public static class QueueItem {
        public long id;
        public String action;
        public String localPath;
        public Integer targetFolderId;
        public Integer targetParentId;
        public String targetName;
        public int retryCount;
    }

    public Long enqueue(String action, String localPath, Integer targetFolderId, Integer targetParentId, String targetName) {
        final String sql = "INSERT INTO SyncQueue(Action, LocalPath, TargetFolderID, TargetParentID, TargetName) VALUES(?,?,?,?,?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, action);
            if (localPath == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, localPath);
            if (targetFolderId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, targetFolderId);
            if (targetParentId == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, targetParentId);
            if (targetName == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, targetName);
            ps.executeUpdate();
            try (ResultSet g = ps.getGeneratedKeys()) { if (g.next()) return g.getLong(1); }
        } catch (SQLException e) {
            System.err.println("❌ enqueue lỗi: " + e.getMessage());
        }
        return null;
    }

    public QueueItem fetchNextQueueItem() {
        final String sql = "SELECT QueueID, Action, LocalPath, TargetFolderID, TargetParentID, TargetName, RetryCount " +
                "FROM SyncQueue ORDER BY QueueID ASC LIMIT 1";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                QueueItem q = new QueueItem();
                q.id = rs.getLong(1);
                q.action = rs.getString(2);
                q.localPath = rs.getString(3);
                int tf = rs.getInt(4); q.targetFolderId = rs.wasNull() ? null : tf;
                int tp = rs.getInt(5); q.targetParentId = rs.wasNull() ? null : tp;
                q.targetName = rs.getString(6);
                q.retryCount = rs.getInt(7);
                return q;
            }
        } catch (SQLException e) {
            System.err.println("❌ fetchNextQueueItem lỗi: " + e.getMessage());
        }
        return null;
    }

    public boolean incrementRetry(long queueId) {
        final String sql = "UPDATE SyncQueue SET RetryCount=RetryCount+1 WHERE QueueID=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, queueId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ incrementRetry lỗi: " + e.getMessage());
            return false;
        }
    }

    public boolean removeQueueItem(long queueId) {
        final String sql = "DELETE FROM SyncQueue WHERE QueueID=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, queueId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ removeQueueItem lỗi: " + e.getMessage());
            return false;
        }
    }
}