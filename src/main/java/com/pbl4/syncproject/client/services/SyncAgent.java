package com.pbl4.syncproject.client.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

/**
 * SyncAgent:
 * - MIRROR ghi đè local ở lần chạy đầu (khi since_seq==0)
 * - Up-sync: xử lý từ bảng SyncQueue
 * - Down-sync: Change Feed theo sequence (seq)
 * - Có cơ chế "mute watcher" để tránh vòng lặp khi ghi file từ server
 */
public class SyncAgent implements FileWatcherService.FileChangeListener {

    /** Callback để UI refresh khi cache local đổi */
    public interface SyncEventListener {
        void onLocalChangeDetected();
    }

    private final FileWatcherService fileWatcher;
    private final FileHashService hashService;
    private final NetworkService networkService;
    @SuppressWarnings("unused")
    private final UploadManager uploadManager;

    private SyncEventListener eventListener = null;

    private final LocalDatabaseManager localDbManager;
    private final NotificationManager notificationManager = NotificationManager.getInstance();
    private DownloadService downloadService;

    // cấu hình
    private Path syncDirectory;
    private boolean isRunning = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // debounce watcher
    private final Map<String, ScheduledFuture<?>> pendingSyncs = new ConcurrentHashMap<>();
    private static final long DEBOUNCE_DELAY_MS = 500;

    // xử lý queue (up-sync)
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SyncQueue-Processor");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean isProcessingQueue = new AtomicBoolean(false);

    // Chặn sự kiện watcher khi đang mirror / down-sync / thao tác chủ động
    private final AtomicBoolean suppressWatcherEvents = new AtomicBoolean(false);

    public SyncAgent(NetworkService networkService, UploadManager uploadManager, LocalDatabaseManager localDbManager) {
        this.networkService = networkService;
        this.fileWatcher = new FileWatcherService();
        this.hashService = new FileHashService();
        this.uploadManager = uploadManager;
        this.localDbManager = localDbManager;
        fileWatcher.addListener(this);
    }

    public void setEventListener(SyncEventListener listener) {
        this.eventListener = listener;
    }
    
    public LocalDatabaseManager getLocalDbManager() {
        return localDbManager;
    }

    /** Cho phép bọc một khối ghi/đổi tên file mà không cho Watcher bắn sự kiện. */
    public void runWatcherMuted(Runnable r) {
        boolean prev = suppressWatcherEvents.getAndSet(true);
        try {
            r.run();
        } finally {
            suppressWatcherEvents.set(prev);
        }
    }

    /** Start agent */
    public void start(String syncDirectoryPath) throws Exception {
        if (isRunning) return;

        syncDirectory = Paths.get(syncDirectoryPath);
        File dir = syncDirectory.toFile();
        if (!dir.exists()) dir.mkdirs();

        // Tạo FolderService wrapper cho DownloadService
        FolderService folderServiceWrapper = new FolderService(networkService, localDbManager);
        this.downloadService = new DownloadService(networkService, localDbManager, syncDirectoryPath, fileWatcher, folderServiceWrapper);
        
        // Đảm bảo root folder tồn tại trong local DB (với FolderID=1)
        ensureRootFolderExists();

        // MIRROR lần đầu: nếu chưa từng đồng bộ (since_seq == 0)
        long sinceSeq = getSinceSeqCompat();
        if (sinceSeq <= 0L) {
            System.out.println("🪞 First run → MIRROR overwrite local from server (seq)");
            suppressWatcherEvents.set(true);
            try {
                // xoá local, reset cache, tải toàn bộ từ server
            } finally {
                suppressWatcherEvents.set(false);
            }
        } else {
            // Lần chạy sau → chỉ scan để gắn trạng thái ban đầu
            scanLocalFilesOnStartup();
        }

        fileWatcher.start();
        fileWatcher.watchDirectory(syncDirectory);

        isRunning = true;
        System.out.println("SyncAgent started: " + syncDirectoryPath);
    }

    /** Stop agent */
    public void stop() {
        isRunning = false;

        for (ScheduledFuture<?> f : pendingSyncs.values()) f.cancel(false);
        pendingSyncs.clear();

        fileWatcher.stop();
        scheduler.shutdown();

        System.out.println("SyncAgent stopped");
    }

    /** Quét thư mục local khi khởi động để gắn trạng thái LOCAL_NEW/LOCAL_STALE và enqueue UPLOAD */
    private void scanLocalFilesOnStartup() {
        System.out.println("🔄 Quét thư mục cục bộ khi khởi động...");
        try (Stream<Path> s = Files.walk(this.syncDirectory)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                try { processFileChange(p, SyncOperation.UPLOAD); }
                catch (Exception e) { System.err.println("Scan error: " + p + " - " + e.getMessage()); }
            });
        } catch (Exception e) {
            System.err.println("❌ Lỗi quét: " + e.getMessage());
        }
        System.out.println("✅ Quét xong.");
    }

    /**
     * Tìm (hoặc tạo) FolderID local cho một path folder
     * Đảm bảo parent có trước; map parent theo LocalPath (relative)
     */
    private int getOrCreateFolderId(Path folderPath, Connection conn) throws SQLException {
        // Folder root "ảo" có FolderID=1
        if (folderPath == null || folderPath.equals(syncDirectory)) return 1;

        String relativePath = syncDirectory.relativize(folderPath).toString();
        String sqlPath = relativePath.replace("\\", "/");

        // try find
        try (PreparedStatement ps = conn.prepareStatement("SELECT FolderID FROM Folders WHERE LocalPath=?")) {
            ps.setString(1, sqlPath);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("FolderID");
        }

        // ensure parent
        Path parentPath = folderPath.getParent();
        int parentFolderId = getOrCreateFolderId(parentPath, conn);

        // insert với ON CONFLICT để tránh lỗi UNIQUE constraint
        String folderName = folderPath.getFileName() != null ? folderPath.getFileName().toString() : "";
        String sqlInsert = """
            INSERT INTO Folders (ParentFolderID, FolderName, LocalPath, SyncStatus, ServerFolderID)
            VALUES (?, ?, ?, 'LOCAL_CREATED', NULL)
            ON CONFLICT(LocalPath) DO NOTHING
        """;
        
        try (PreparedStatement ps = conn.prepareStatement(sqlInsert, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, parentFolderId);
            ps.setString(2, folderName);
            ps.setString(3, sqlPath);
            int affectedRows = ps.executeUpdate();

            if (affectedRows > 0) {
                // INSERT thành công (hàng mới)
                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    int newFolderId = rs.getInt(1);
                    
                    // Xếp hàng CREATE_FOLDER cho thư mục này (chống trùng)
                    try (PreparedStatement chk = conn.prepareStatement(
                            "SELECT 1 FROM SyncQueue WHERE Action='CREATE_FOLDER' AND LocalPath=? LIMIT 1")) {
                        chk.setString(1, sqlPath);
                        ResultSet ex = chk.executeQuery();
                        if (!ex.next()) {
                            // Tìm parent server id (nếu đã có)
                            Integer parentServerId = null;
                            try (PreparedStatement psP = conn.prepareStatement(
                                    "SELECT ServerFolderID FROM Folders WHERE FolderID=?")) {
                                psP.setInt(1, parentFolderId);
                                ResultSet r2 = psP.executeQuery();
                                if (r2.next()) parentServerId = (Integer) r2.getObject(1);
                            }
                            try (PreparedStatement psQ = conn.prepareStatement(
                                    "INSERT INTO SyncQueue (Action, LocalPath, TargetParentID, TargetName) VALUES ('CREATE_FOLDER', ?, ?, ?)")) {
                                psQ.setString(1, sqlPath);
                                psQ.setInt(2, parentServerId != null ? parentServerId : 1);
                                psQ.setString(3, folderName);
                                psQ.executeUpdate();
                            }
                        }
                    }
                    return newFolderId;
                }
            } else {
                // INSERT thất bại do CONFLICT - Lấy ID của hàng đã tồn tại
                try (PreparedStatement psSelect = conn.prepareStatement("SELECT FolderID FROM Folders WHERE LocalPath=?")) {
                    psSelect.setString(1, sqlPath);
                    ResultSet rsSelect = psSelect.executeQuery();
                    if (rsSelect.next()) return rsSelect.getInt("FolderID");
                }
            }
        }
        throw new SQLException("Không tạo được FolderID cho " + sqlPath);
    }

    // ========== FileWatcher callbacks ==========
    @Override public void onFileCreated(Path filePath)  { scheduleSync(filePath, SyncOperation.UPLOAD); }
    @Override public void onFileModified(Path filePath) { scheduleSync(filePath, SyncOperation.UPLOAD); }
    @Override public void onFileDeleted(Path filePath)  { scheduleSync(filePath, SyncOperation.DELETE); }

    private void scheduleSync(Path filePath, SyncOperation operation) {
        if (suppressWatcherEvents.get()) return; // đang mirror/down-sync → bỏ qua

        String relativePath = syncDirectory.relativize(filePath).toString();

        ScheduledFuture<?> existing = pendingSyncs.get(relativePath);
        if (existing != null && !existing.isDone()) existing.cancel(false);

        ScheduledFuture<?> nf = scheduler.schedule(() -> {
            processFileChange(filePath, operation);
            pendingSyncs.remove(relativePath);
        }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);

        pendingSyncs.put(relativePath, nf);
    }

    private void processFileChange(Path filePath, SyncOperation operation) {
        if (operation == SyncOperation.DELETE) {
            processDeletedEntity(filePath);
        } else if (Files.isDirectory(filePath)) {
            processDirectoryChange(filePath, operation);
        } else if (Files.isRegularFile(filePath)) {
            processFileChangeInternal(filePath, operation);
        }
    }

    /** Đánh dấu trạng thái file vào cache + ENQUEUE UPLOAD (nếu cần) */
    private void processFileChangeInternal(Path filePath, SyncOperation operation) {
        try {
            if (!Files.exists(filePath)) return;

            String relativePath = syncDirectory.relativize(filePath).toString();
            String sqlPath = relativePath.replace("\\", "/");
            File file = filePath.toFile();

            String currentHash = hashService.calculateFileHash(file);
            if (currentHash == null) return;

            try (Connection conn = localDbManager.getConnection()) {
                String lastHash = null;
                Integer fileId = null;
                Integer serverFileId = null;
                Integer lastVersion = null; // THÊM: Lưu LastKnownVersion

                System.out.println("🔍 [DEBUG] Tìm file trong DB với LocalPath: '" + sqlPath + "'");
                
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT FileID, ServerFileID, LastKnownHash, LastKnownVersion, SyncStatus FROM Files WHERE LocalPath=?")) {
                    ps.setString(1, sqlPath);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        fileId = rs.getInt("FileID");
                        
                        // Đọc ServerFileID an toàn (xử lý NULL)
                        int tempServerFileId = rs.getInt("ServerFileID");
                        if (rs.wasNull()) {
                            serverFileId = null;
                        } else {
                            serverFileId = tempServerFileId;
                        }
                        
                        lastHash = rs.getString("LastKnownHash");
                        lastVersion = rs.getObject("LastKnownVersion", Integer.class); // THÊM: Đọc version
                        System.out.println("✅ [DEBUG] File tìm thấy: FileID=" + fileId + ", ServerFileID=" + serverFileId + ", lastHash=" + lastHash + ", lastVersion=" + lastVersion);
                    } else {
                        System.out.println("❌ [DEBUG] File KHÔNG tìm thấy trong DB!");
                    }
                }

                String newStatus;
                if (lastHash == null) {
                    newStatus = LocalDatabaseManager.STATUS_LOCAL_NEW;
                } else if (!currentHash.equals(lastHash)) {
                    newStatus = LocalDatabaseManager.STATUS_LOCAL_STALE;
                } else {
                    return; // không đổi
                }

                int folderId = getOrCreateFolderId(filePath.getParent(), conn);

                // Sử dụng ON CONFLICT(LocalPath) DO UPDATE thay vì INSERT OR REPLACE
                // để tránh mất FileID và giữ đúng dữ liệu baseline cho OCC
                String sqlUpsert = """
                    INSERT INTO Files 
                        (FolderID, FileName, FileSize, LocalPath, LastKnownHash, LastKnownVersion, SyncStatus, ServerFileID)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(LocalPath) DO UPDATE SET
                        FolderID = excluded.FolderID,
                        FileName = excluded.FileName,
                        FileSize = excluded.FileSize,
                        LastKnownHash = excluded.LastKnownHash,
                        LastKnownVersion = excluded.LastKnownVersion,
                        SyncStatus = excluded.SyncStatus
                """;

                try (PreparedStatement ps = conn.prepareStatement(sqlUpsert)) {
                    // Các cột trong VALUES
                    ps.setInt(1, folderId);
                    ps.setString(2, file.getName());
                    ps.setLong(3, file.length());
                    ps.setString(4, sqlPath);
                    ps.setString(5, lastHash); // giữ baseline của server để OCC
                    
                    // Giữ nguyên LastKnownVersion (không reset về 0 cho file đã sync)
                    if (lastVersion != null) {
                        ps.setInt(6, lastVersion);
                    } else {
                        ps.setInt(6, 0); // Default về 0 cho file mới
                    }
                    
                    ps.setString(7, newStatus);
                    
                    // Giữ nguyên ServerFileID nếu đã có
                    if (serverFileId != null) {
                        ps.setInt(8, serverFileId);
                    } else {
                        ps.setNull(8, java.sql.Types.INTEGER);
                    }
                    
                    ps.executeUpdate();
                }

                // Enqueue UPLOAD (chống trùng)
                try (PreparedStatement chk = conn.prepareStatement(
                        "SELECT 1 FROM SyncQueue WHERE Action='UPLOAD' AND LocalPath=? LIMIT 1")) {
                    chk.setString(1, sqlPath);
                    ResultSet ex = chk.executeQuery();
                    if (!ex.next()) {
                        try (PreparedStatement psQ = conn.prepareStatement(
                                "INSERT INTO SyncQueue (Action, LocalPath) VALUES ('UPLOAD', ?)")) {
                            psQ.setString(1, sqlPath);
                            psQ.executeUpdate();
                        }
                    }
                }

                if (eventListener != null) eventListener.onLocalChangeDetected();
                System.out.println("[SyncAgent] ✓ " + newStatus + " + QUEUED UPLOAD : " + sqlPath);
            }
        } catch (Exception e) {
            System.err.println("Lỗi xử lý thay đổi file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Xử lý khi phát hiện thay đổi trên thư mục (tạo mới → CREATE_FOLDER đúng parent) */
    private void processDirectoryChange(Path filePath, SyncOperation operation) {
        if (operation != SyncOperation.UPLOAD) return;

        String relativePath = syncDirectory.relativize(filePath).toString();
        String sqlPath = relativePath.replace("\\", "/");
        String folderName = filePath.getFileName() != null ? filePath.getFileName().toString() : "";

        try (Connection conn = localDbManager.getConnection()) {
            // Tìm parent local path
            String parentSqlPath = "";
            Path parent = filePath.getParent();
            if (parent != null && !parent.equals(syncDirectory)) {
                parentSqlPath = syncDirectory.relativize(parent).toString().replace("\\", "/");
            }

            // Lấy ServerFolderID của parent nếu đã có
            Integer parentServerId = 1; // root mặc định
            if (!parentSqlPath.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT ServerFolderID, SyncStatus FROM Folders WHERE LocalPath=?")) {
                    ps.setString(1, parentSqlPath);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        Integer srv = (Integer) rs.getObject("ServerFolderID");
                        String st = rs.getString("SyncStatus");
                        if (srv != null && LocalDatabaseManager.STATUS_SYNCED.equals(st)) {
                            parentServerId = srv;
                        } else {
                            // parent chưa sync → xếp hàng tạo parent trước (chống trùng)
                            try (PreparedStatement chk = conn.prepareStatement(
                                    "SELECT 1 FROM SyncQueue WHERE Action='CREATE_FOLDER' AND LocalPath=? LIMIT 1")) {
                                chk.setString(1, parentSqlPath);
                                ResultSet ex = chk.executeQuery();
                                if (!ex.next()) {
                                    String parentName = parent.getFileName() != null ? parent.getFileName().toString() : "";
                                    try (PreparedStatement ins = conn.prepareStatement(
                                            "INSERT INTO SyncQueue (Action, LocalPath, TargetParentID, TargetName) " +
                                                    "VALUES ('CREATE_FOLDER', ?, ?, ?)")) {
                                        ins.setString(1, parentSqlPath);
                                        ins.setInt(2, 1); // tạm root; sẽ được cập nhật đúng khi server trả về
                                        ins.setString(3, parentName);
                                        ins.executeUpdate();
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Chống trùng CREATE_FOLDER cho chính thư mục này
            try (PreparedStatement chk = conn.prepareStatement(
                    "SELECT 1 FROM SyncQueue WHERE Action='CREATE_FOLDER' AND LocalPath=? LIMIT 1")) {
                chk.setString(1, sqlPath);
                ResultSet ex = chk.executeQuery();
                if (!ex.next()) {
                    try (PreparedStatement psQ = conn.prepareStatement(
                            "INSERT INTO SyncQueue (Action, TargetParentID, TargetName, LocalPath) " +
                                    "VALUES ('CREATE_FOLDER', ?, ?, ?)")) {
                        psQ.setInt(1, parentServerId != null ? parentServerId : 1);
                        psQ.setString(2, folderName);
                        psQ.setString(3, sqlPath);
                        psQ.executeUpdate();
                    }
                }
            }
            System.out.println("[SyncAgent] QUEUED CREATE_FOLDER: " + sqlPath + " (parentSrv=" + parentServerId + ")");
        } catch (Exception e) {
            System.err.println("Lỗi xử lý thư mục: " + e.getMessage());
        }
    }

    /** Xử lý khi thực thể (file/folder) bị xóa */
    private void processDeletedEntity(Path filePath) {
        String relativePath = syncDirectory.relativize(filePath).toString();
        String sqlPath = relativePath.replace("\\", "/");
        System.out.println("[Watcher] Delete detected: " + sqlPath);

        try (Connection conn = localDbManager.getConnection()) {
            boolean handled = false;

            // Kiểm tra xóa file
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT FileID, ServerFileID FROM Files WHERE LocalPath=? AND SyncStatus!=?")) {
                ps.setString(1, sqlPath);
                ps.setString(2, LocalDatabaseManager.STATUS_LOCAL_DELETED);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    // File có trong DB → xử lý bình thường
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE Files SET SyncStatus=? WHERE LocalPath=?")) {
                        upd.setString(1, LocalDatabaseManager.STATUS_LOCAL_DELETED);
                        upd.setString(2, sqlPath);
                        upd.executeUpdate();
                    }

                    // Enqueue DELETE_FILE (chống trùng)
                    try (PreparedStatement q = conn.prepareStatement(
                            "SELECT 1 FROM SyncQueue WHERE Action='DELETE_FILE' AND LocalPath=? LIMIT 1")) {
                        q.setString(1, sqlPath);
                        ResultSet ex = q.executeQuery();
                        if (!ex.next()) {
                            try (PreparedStatement ins = conn.prepareStatement(
                                    "INSERT INTO SyncQueue (Action, LocalPath) VALUES ('DELETE_FILE', ?)")) {
                                ins.setString(1, sqlPath);
                                ins.executeUpdate();
                            }
                            System.out.println("[SyncAgent] ✓ LOCAL_DELETED + QUEUED DELETE_FILE: " + sqlPath);
                        } else {
                            System.out.println("[SyncAgent] ✓ LOCAL_DELETED (already queued): " + sqlPath);
                        }
                    }

                    // Notify UI refresh
                    if (eventListener != null) eventListener.onLocalChangeDetected();
                    handled = true;
                }
            }

            if (handled) return;

            // Kiểm tra xóa folder
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT FolderID, ServerFolderID FROM Folders WHERE LocalPath=? AND FolderID<>1 AND SyncStatus!=?")) {
                ps.setString(1, sqlPath);
                ps.setString(2, LocalDatabaseManager.STATUS_LOCAL_DELETED);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    // Folder có trong DB → xử lý bình thường
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE Folders SET SyncStatus=? WHERE LocalPath=?")) {
                        upd.setString(1, LocalDatabaseManager.STATUS_LOCAL_DELETED);
                        upd.setString(2, sqlPath);
                        upd.executeUpdate();
                    }

                    // Enqueue DELETE_FOLDER (chống trùng)
                    try (PreparedStatement q = conn.prepareStatement(
                            "SELECT 1 FROM SyncQueue WHERE Action='DELETE_FOLDER' AND LocalPath=? LIMIT 1")) {
                        q.setString(1, sqlPath);
                        ResultSet ex = q.executeQuery();
                        if (!ex.next()) {
                            try (PreparedStatement ins = conn.prepareStatement(
                                    "INSERT INTO SyncQueue (Action, LocalPath) VALUES ('DELETE_FOLDER', ?)")) {
                                ins.setString(1, sqlPath);
                                ins.executeUpdate();
                            }
                            System.out.println("[SyncAgent] ✓ LOCAL_DELETED + QUEUED DELETE_FOLDER: " + sqlPath);
                        } else {
                            System.out.println("[SyncAgent] ✓ LOCAL_DELETED (already queued): " + sqlPath);
                        }
                    }

                    // Notify UI refresh
                    if (eventListener != null) eventListener.onLocalChangeDetected();
                    handled = true;
                }
            }

            if (!handled) {
                // File/folder không có trong DB - có thể do:
                // 1. File đang được download (chưa lưu DB kịp)
                // 2. File được tạo local nhưng chưa scan vào DB
                // 3. File được download từ server nhưng user xóa ngay trước khi cache được cập nhật

                // GIẢI PHÁP: Đợi một chút rồi kiểm tra lại (debounce đã xử lý)
                // Nếu sau debounce vẫn không có trong DB, bỏ qua (có thể là file tạm)

                System.out.println("[SyncAgent] ℹ️ Deleted entity not in DB (might be temp file or just downloaded): " + sqlPath);
                // Không cần enqueue vì:
                // - Nếu file chưa từng sync lên server → không cần delete
                // - Nếu file đang download → sẽ được tạo lại và user có thể xóa lần 2
            }

        } catch (Exception e) {
            System.err.println("Lỗi xử lý delete: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Placeholder: đồng bộ một file cụ thể từ queue */
    public void syncFile(String relativePath) {
        System.out.println("[TODO] syncFile(): sẽ đọc từ SyncQueue");
    }

    /** Trạng thái chung */
    public SyncStatus getStatus() {
        return new SyncStatus(isRunning, 0, 0, 0);
    }

    // ========== Xử lý hàng đợi (Up-sync) ==========
    public void triggerSyncQueue() {
        if (isProcessingQueue.compareAndSet(false, true)) {
            System.out.println("⚡ Xử lý SyncQueue...");
            syncExecutor.submit(this::processSyncQueueInternal);
        } else {
            System.out.println("ℹ️ Đang xử lý SyncQueue...");
        }
    }

    private void processSyncQueueInternal() {
        try {
            while (true) {
                if (!networkService.isOnline()) {
                    System.out.println("🔌 Offline, tạm dừng xử lý Queue.");
                    break;
                }

                // 1. Lấy task (tự mở/đóng connection)
                SyncTask task = getNextTask();
                if (task == null) {
                    System.out.println("✅ Up-sync xong → chạy Down-sync.");
                    triggerDownSync();
                    break;
                }

                try {
                    boolean ok;
                    switch (task.action) {
                        // 2. Các handler tự mở/đóng connection
                        case "CREATE_FOLDER": ok = handleCreateFolderTask(task); break;
                        case "UPLOAD":        ok = handleUploadTask(task); break;
                        case "DELETE_FILE":   ok = handleDeleteFileTask(task); break;
                        case "DELETE_FOLDER": ok = handleDeleteFolderTask(task); break;
                        default:
                            System.out.println("⚠️ Chưa hỗ trợ action: " + task.action);
                            ok = true;
                    }

                    // 3. Xóa task (tự mở/đóng connection)
                    if (ok) deleteTask(task.queueId);
                    else System.err.println("❌ Task fail, sẽ retry sau");

                } catch (Exception e) {
                    System.err.println("Lỗi xử lý task " + task.queueId + ": " + e.getMessage());
                }
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        } catch (Exception e) { // Catch lỗi chung (ví dụ: getNextTask không thể lấy connection)
            e.printStackTrace();
        } finally {
            isProcessingQueue.set(false);
        }
    }

    /**
     * Overload: Tự mở/đóng connection
     */
    private SyncTask getNextTask() {
        try (Connection conn = localDbManager.getConnection()) {
            return getNextTask(conn);
        } catch (SQLException e) {
            System.err.println("❌ getNextTask() lỗi: " + e.getMessage());
            return null;
        }
    }
    
    private SyncTask getNextTask(Connection conn) throws SQLException {
        String sql = "SELECT * FROM SyncQueue ORDER BY " +
                "CASE Action WHEN 'CREATE_FOLDER' THEN 1 WHEN 'UPLOAD' THEN 2 WHEN 'DELETE_FILE' THEN 3 WHEN 'DELETE_FOLDER' THEN 4 ELSE 5 END, " +
                "QueueID ASC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String action = rs.getString("Action");
                if ("CREATE_FOLDER".equals(action)) {
                    Integer parentId = rs.getObject("TargetParentID", Integer.class);
                    String name = rs.getString("TargetName");
                    String localPath = rs.getString("LocalPath");
                    return new SyncTask(rs.getInt("QueueID"), action, localPath, parentId, name);
                } else {
                    return new SyncTask(rs.getInt("QueueID"), action, rs.getString("LocalPath"));
                }
            }
        }
        return null;
    }

    /**
     * Overload: Tự mở/đóng connection
     */
    private void deleteTask(int queueId) {
        try (Connection conn = localDbManager.getConnection()) {
            deleteTask(conn, queueId);
        } catch (SQLException e) {
            System.err.println("❌ deleteTask() lỗi: " + e.getMessage());
        }
    }
    
    private void deleteTask(Connection conn, int queueId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM SyncQueue WHERE QueueID=?")) {
            ps.setInt(1, queueId);
            ps.executeUpdate();
        }
    }

    private boolean handleUploadTask(SyncTask task) throws Exception {
        File fileToUpload = syncDirectory.resolve(task.localPath).toFile();
        if (!fileToUpload.exists()) {
            System.err.println("File không tồn tại, bỏ task: " + task.localPath);
            return true;
        }
        int folderId = 1;
        Integer baseVersion = null;
        String lastKnownHash = null;
        try (Connection conn = localDbManager.getConnection()) {
            Path filePath = Paths.get(task.localPath);
            if (filePath.getParent() != null) {
                String parentLocalPath = filePath.getParent().toString().replace("\\", "/");
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT ServerFolderID, SyncStatus FROM Folders WHERE LocalPath=?")) {
                    ps.setString(1, parentLocalPath);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        Integer srvFolderId = rs.getObject("ServerFolderID", Integer.class);
                        String status = rs.getString("SyncStatus");
                        if (LocalDatabaseManager.STATUS_SYNCED.equals(status) && srvFolderId != null && srvFolderId > 0) {
                            folderId = srvFolderId;
                        } else {
                            System.err.println("⚠️ Folder cha chưa sync: " + parentLocalPath);
                            return false;
                        }
                    }
                }
            }
            System.out.println("🔍 [UPLOAD DEBUG] Tìm baseVersion và hash cho: '" + task.localPath + "'");
            try (PreparedStatement ps = conn.prepareStatement("SELECT ServerFileID, LastKnownVersion, LastKnownHash FROM Files WHERE LocalPath=?")) {
                ps.setString(1, task.localPath);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    Integer serverFileId = null;
                    
                    // Đọc ServerFileID an toàn
                    try {
                        Object srvIdObj = rs.getObject("ServerFileID");
                        if (srvIdObj != null) {
                            if (srvIdObj instanceof Integer) {
                                serverFileId = (Integer) srvIdObj;
                            } else {
                                serverFileId = Integer.parseInt(srvIdObj.toString());
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("⚠️ Lỗi đọc ServerFileID: " + e.getMessage());
                    }
                    
                    // Đọc LastKnownVersion an toàn
                    try {
                        Object versionObj = rs.getObject("LastKnownVersion");
                        if (versionObj != null) {
                            if (versionObj instanceof Integer) {
                                baseVersion = (Integer) versionObj;
                            } else if (versionObj instanceof Long) {
                                baseVersion = ((Long) versionObj).intValue();
                            } else {
                                String versionStr = versionObj.toString().trim();
                                if (!versionStr.isEmpty() && !versionStr.equalsIgnoreCase("null")) {
                                    baseVersion = Integer.parseInt(versionStr);
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("⚠️ Lỗi đọc LastKnownVersion: " + e.getMessage() + " - Đặt baseVersion = null");
                        baseVersion = null;
                    }
                    
                    // Lấy hash
                    lastKnownHash = rs.getString("LastKnownHash");
                    
                    System.out.println("✅ [UPLOAD DEBUG] ServerFileID: " + serverFileId + ", BaseVersion: " + baseVersion + ", Hash: " + (lastKnownHash != null ? lastKnownHash.substring(0, Math.min(16, lastKnownHash.length())) + "..." : "null"));
                    
                    // QUAN TRỌNG: Nếu có ServerFileID nhưng version=0/null, đặt lại version=1 (sử dụng hash để verify)
                    if (serverFileId != null && serverFileId > 0 && (baseVersion == null || baseVersion == 0)) {
                        System.err.println("⚠️ [UPLOAD] File có ServerFileID=" + serverFileId + " nhưng version=" + baseVersion + ". Đặt lại baseVersion=1 (dựa vào hash).");
                        baseVersion = 1;
                    }
                } else {
                    System.out.println("❌ [UPLOAD DEBUG] Không tìm thấy file trong DB - đây là file mới!");
                }
            } catch (Exception e) {
                System.err.println("⚠️ Lỗi khi tìm file trong DB: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("📤 [UPLOAD] File: " + fileToUpload.getName() + ", FolderId: " + folderId + ", BaseVersion: " + baseVersion);
        
        // Gọi uploadFile với baseVersion (int) thay vì hash (string)
        Response response;
        if (baseVersion != null && baseVersion > 0) {
            response = networkService.uploadFile(fileToUpload, folderId, lastKnownHash, baseVersion);
        } else {
            // File mới, không có baseVersion
            response = networkService.uploadFile(fileToUpload, folderId);
        }

        // Xử lý CONFLICT (status="conflict" HOẶC status="error" + message="CONFLICT")
        if ("conflict".equalsIgnoreCase(response.getStatus()) 
            || ("error".equals(response.getStatus()) && "CONFLICT".equals(response.getMessage()))) {
            System.err.println("🔥 CONFLICT: " + task.localPath);
            handleConflict(task, fileToUpload);
            return true;
        } else if ("success".equals(response.getStatus())) {
            updateFileStatusAfterUpload(task.localPath, response);
            System.out.println("✅ Upload OK: " + task.localPath);
            return true;
        } else {
            System.err.println("Upload lỗi: " + response.getMessage());
            return false;
        }
    }

    /**
     * Overload: Tự mở/đóng connection
     */
    private boolean handleCreateFolderTask(SyncTask task) throws Exception {
        try (Connection conn = localDbManager.getConnection()) {
            return handleCreateFolderTask(task, conn);
        }
    }
    
    private boolean handleCreateFolderTask(SyncTask task, Connection conn) throws Exception {
        if (task.targetParentID == null || task.targetName == null) {
            System.err.println("❌ CREATE_FOLDER thiếu thông tin");
            return true;
        }
        Response response = networkService.createFolder(task.targetName, task.targetParentID);
        if ("success".equals(response.getStatus())) {
            JsonObject data = asObj(response.getData());
            if (data == null || !data.has("folderId")) return false;
            int serverFolderId = data.get("folderId").getAsInt();

            // Lấy version từ response (mặc định là 1 nếu không có)
            int serverVersion = data.has("version") ? data.get("version").getAsInt() : 1;

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE Folders SET ServerFolderID=?, ServerVersion=?, SyncStatus=? WHERE LocalPath=?")) {
                ps.setInt(1, serverFolderId);
                ps.setInt(2, serverVersion);
                ps.setString(3, LocalDatabaseManager.STATUS_SYNCED);
                ps.setString(4, task.localPath);
                ps.executeUpdate();
            }
            System.out.println("✅ Folder tạo trên server: id=" + serverFolderId + ", version=" + serverVersion);
            return true;

        } else if ("error".equals(response.getStatus()) &&
                response.getMessage() != null &&
                response.getMessage().contains("đã tồn tại")) {

            Response tree = networkService.getFolderTree(task.targetParentID);
            if (!"success".equals(tree.getStatus())) return false;

            JsonObject tData = asObj(tree.getData());
            JsonArray folders = (tData != null && tData.has("folders"))
                    ? asArr(tData.get("folders"))
                    : asArr(tree.getData());
            if (folders != null) {
                for (JsonElement el : folders) {
                    JsonObject fo = asObj(el);
                    if (fo == null) continue;
                    String nm = fo.has("folderName") ? fo.get("folderName").getAsString() : null;
                    if (task.targetName.equals(nm)) {
                        int existingId = fo.get("folderId").getAsInt();
                        int existingVersion = fo.has("version") ? fo.get("version").getAsInt() : 1;

                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE Folders SET ServerFolderID=?, ServerVersion=?, SyncStatus=? WHERE LocalPath=?")) {
                            ps.setInt(1, existingId);
                            ps.setInt(2, existingVersion);
                            ps.setString(3, LocalDatabaseManager.STATUS_SYNCED);
                            ps.setString(4, task.localPath);
                            ps.executeUpdate();
                        }
                        System.out.println("✅ Gán ServerFolderID=" + existingId + " cho folder đã tồn tại");
                        return true;
                    }
                }
            }
            System.err.println("❌ Không tìm thấy folder đã tồn tại");
            return false;
        } else {
            System.err.println("❌ Tạo folder thất bại: " + response.getMessage());
            return false;
        }
    }

    private boolean handleDeleteFileTask(SyncTask task) throws Exception {
        Integer serverFileId = null;
        Integer baseVersion = null;

        try (Connection conn = localDbManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT ServerFileID, LastKnownVersion FROM Files WHERE LocalPath=?")) {
                ps.setString(1, task.localPath);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    serverFileId = rs.getObject("ServerFileID", Integer.class);
                    baseVersion  = rs.getObject("LastKnownVersion", Integer.class);
                }
            }
        }

        Response res;
        if (serverFileId != null && serverFileId > 0) {
            res = networkService.deleteFile(serverFileId, baseVersion);
        } else {
            // fallback: chưa có ServerFileID thì thử xoá theo path
            res = networkService.deleteFileByPath(task.localPath);
        }

        // OK hoặc server báo “không tìm thấy” => coi như đã xoá
        boolean ok =
                "success".equalsIgnoreCase(res.getStatus())
                        || ("error".equalsIgnoreCase(res.getStatus())
                        && res.getMessage() != null
                        && res.getMessage().toLowerCase().contains("không tìm thấy"));

        // conflict => không xoá được do version mismatch → kéo bản mới về
        if ("conflict".equalsIgnoreCase(res.getStatus())
                || ("error".equalsIgnoreCase(res.getStatus())
                && res.getMessage() != null
                && res.getMessage().toLowerCase().contains("version mismatch"))) {

            notificationManager.addNotification(
                    "⚠️ Xung đột khi xoá '" + task.localPath + "': server có phiên bản mới hơn. Sẽ tải lại từ server.",
                    com.pbl4.syncproject.client.models.NotificationItem.NotificationType.SYSTEM
            );
            triggerDownSync();  // để tải lại bản server
            ok = true;          // xoá task để không kẹt queue
        }

        if (!ok) {
            System.err.println("❌ DELETE_FILE fail: " + res.getMessage());
            return false;
        }

        // Update since_seq theo seq server trả về (đỡ thấy lại change của chính mình)
        try {
            JsonObject d = asObj(res.getData());
            if (d != null && d.has("seq")) {
                long seq = d.get("seq").getAsLong();
                long cur = getSinceSeqCompat();
                if (seq > cur) {
                    setSinceSeqCompat(seq);
                    System.out.println("✅ [DELETE_FILE] Cập nhật since_seq=" + seq);
                }
            }
        } catch (Exception ignore) {}

        // KHÔNG XÓA file khỏi DB, chỉ đánh dấu status để tránh Down-Sync tải lại
        // Giữ file trong DB với status='DELETED' để tracking
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE Files SET SyncStatus=? WHERE LocalPath=?")) {
            ps.setString(1, "DELETED");
            ps.setString(2, task.localPath);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                System.out.println("✅ [DELETE_FILE] Đã đánh dấu file DELETED trong DB: " + task.localPath);
            }
        }

        return true;
    }
    private boolean handleDeleteFolderTask(SyncTask task) throws Exception {
        Integer serverFolderId = null;
        Integer baseVersion = null;

        try (Connection conn = localDbManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT ServerFolderID, ServerVersion FROM Folders WHERE LocalPath=? AND FolderID<>1")) {
                ps.setString(1, task.localPath);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    serverFolderId = rs.getObject("ServerFolderID", Integer.class);
                    baseVersion = rs.getObject("ServerVersion", Integer.class);
                }
            }
        }

        Response res;
        if (serverFolderId != null && serverFolderId > 0) {
            res = networkService.deleteFolder(serverFolderId, true, baseVersion);
        } else {
            // folder chưa từng sync lên server → chỉ dọn local
            res = new Response("success", "Local-only folder", null);
        }

        boolean ok =
                "success".equalsIgnoreCase(res.getStatus())
                        || ("error".equalsIgnoreCase(res.getStatus())
                        && res.getMessage() != null
                        && res.getMessage().toLowerCase().contains("không tồn tại"));

        if (!ok) {
            System.err.println("❌ DELETE_FOLDER fail: " + res.getMessage());
            return false;
        }

        // Update since_seq nếu có
        try {
            JsonObject d = asObj(res.getData());
            if (d != null && d.has("seq")) {
                long seq = d.get("seq").getAsLong();
                long cur = getSinceSeqCompat();
                if (seq > cur) setSinceSeqCompat(seq);
            }
        } catch (Exception ignore) {}

        // Xoá cache local (cascade sẽ xoá Files con nếu FK ON DELETE CASCADE)
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM Folders WHERE LocalPath=?")) {
            ps.setString(1, task.localPath);
            ps.executeUpdate();
        }

        return true;
    }


    private void updateFileStatusAfterUpload(String localPath, Response serverResponse) throws SQLException {
        JsonObject d = asObj(serverResponse.getData());
        String newHash = null;
        Integer newVersion = null;

        if (d != null) {
            // Lấy hash mới từ server
            if (d.has("hash") && !d.get("hash").isJsonNull()) {
                newHash = d.get("hash").getAsString();
            }
            
            // Lấy version mới từ server (UploadFileHandler trả về 'newVersion')
            newVersion = jInt(d, "newVersion", "version");
        }

        if (newHash != null && newVersion != null) {
            // SỬA LỖI: Gọi phương thức cập nhật cả HASH và VERSION
            localDbManager.updateFileStatusHashAndVersion(
                    localPath, 
                    LocalDatabaseManager.STATUS_SYNCED, 
                    newHash, 
                    newVersion
            );
            System.out.println("✅ Cache đã cập nhật: " + localPath + " (v" + newVersion + ")");
        
        } else if (newHash != null) {
            // Fallback (không tối ưu, vì vẫn thiếu version)
            localDbManager.updateFileStatusAndHash(localPath, LocalDatabaseManager.STATUS_SYNCED, newHash);
            System.err.println("⚠️ Cache đã cập nhật (chỉ hash): " + localPath + " (Thiếu version từ server!)");
        } else {
            // Fallback (rất tệ)
            localDbManager.updateFileStatus(localPath, LocalDatabaseManager.STATUS_SYNCED);
            System.err.println("⚠️ Cache đã cập nhật (chỉ status): " + localPath + " (Thiếu hash/version từ server!)");
        }
    }
    
    // ========== Folder Tree Sync ==========
    
    /** Đảm bảo root folder (FolderID=1, ServerFolderID=1) tồn tại trong local DB */
    private void ensureRootFolderExists() {
        try (Connection conn = localDbManager.getConnection()) {
            // Kiểm tra xem root folder đã tồn tại chưa
            try (PreparedStatement ps = conn.prepareStatement("SELECT FolderID FROM Folders WHERE FolderID = 1")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    // Root folder đã tồn tại
                    return;
                }
            }
            
            // Tạo root folder với FolderID=1 và ServerFolderID=1
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO Folders (FolderID, ServerFolderID, ParentFolderID, FolderName, LocalPath, SyncStatus) " +
                    "VALUES (1, 1, NULL, 'Root', '', 'SYNCED')")) {
                ps.executeUpdate();
                System.out.println("✅ Đã tạo root folder trong local DB (FolderID=1, ServerFolderID=1)");
            }
        } catch (Exception e) {
            System.err.println("❌ Lỗi tạo root folder: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /** 
     * Tải toàn bộ folder tree từ server và lưu vào local DB
     * Method này đảm bảo client biết tất cả folders trước khi xử lý file changes
     */
    private void syncFolderTreeFromServer() {
        System.out.println("📁 Đang đồng bộ cây thư mục từ server...");
        try {
            java.util.Set<Integer> processedFolders = new java.util.HashSet<>();
            processedFolders.add(1); // Root folder (ID=1) đã tồn tại
            
            // Tải đệ quy từ root
            syncFolderTreeRecursive(null, processedFolders);
            
            System.out.println("✅ Đã đồng bộ " + processedFolders.size() + " thư mục từ server");
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi đồng bộ folder tree: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Tải folder tree đệ quy từ server
     * @param parentId null để lấy root folders, hoặc ID của parent folder
     * @param processedFolders Set để track folders đã xử lý
     */
    private void syncFolderTreeRecursive(Integer parentId, java.util.Set<Integer> processedFolders) {
        try {
            // Gọi API FOLDER_TREE để lấy children của parentId
            JsonObject data = new JsonObject();
            if (parentId != null) {
                data.addProperty("parentId", parentId);
            }
            // Đính kèm username để server có thể xác định userId/permissions
            String uname = networkService.getCurrentUsername();
            if (uname != null && !uname.isBlank()) data.addProperty("username", uname);
            
            Response response = networkService.sendRequest(new Request("FOLDER_TREE", data));
            
            if (!"success".equals(response.getStatus())) {
                System.err.println("❌ Lỗi tải folder tree (parentId=" + parentId + "): " + response.getMessage());
                return;
            }
            
            // Parse danh sách folders - response.getData() trả về JsonArray trực tiếp
            JsonArray folders = asArr(response.getData());
            if (folders == null || folders.size() == 0) {
                return; // Không có children
            }
            
            // Xử lý từng folder
            for (JsonElement el : folders) {
                JsonObject folder = asObj(el);
                if (folder == null) continue;
                
                Integer folderId = jInt(folder, "folderId", "FolderID");
                Integer serverParentId = jInt(folder, "parentFolderId", "ParentFolderID");
                String folderName = jStr(folder, "folderName", "FolderName", "name");
                Boolean hasChildren = folder.has("hasChildren") ? folder.get("hasChildren").getAsBoolean() : false;
                
                if (folderId == null || folderName == null) {
                    System.err.println("⚠️ Folder thiếu thông tin: " + folder);
                    continue;
                }
                
                // Root folder fix: nếu serverParentId == folderId (tự tham chiếu) hoặc folderId=1, đặt parent = NULL
                if (folderId == 1 || (serverParentId != null && serverParentId.equals(folderId))) {
                    serverParentId = null;
                    System.out.println("🔸 Folder '" + folderName + "' là ROOT, đặt parentId = NULL");
                }
                
                // Bỏ qua nếu đã xử lý
                if (processedFolders.contains(folderId)) {
                    continue;
                }
                
                // Lưu folder vào local DB
                System.out.println("📂 Đang xử lý folder: " + folderName + " (ID=" + folderId + ", Parent=" + serverParentId + ", hasChildren=" + hasChildren + ")");
                boolean success = saveFolderToLocalDB(folderId, serverParentId, folderName);
                if (success) {
                    processedFolders.add(folderId);
                    System.out.println("✅ Đã lưu folder: " + folderName + " (ID=" + folderId + ")");
                    
                    // Nếu folder này có children HOẶC là root folder (ID=1), tải đệ quy
                    // Root folder luôn cần check children ngay cả khi hasChildren=false 
                    // vì có thể DB structure có vấn đề
                    if (hasChildren || folderId == 1) {
                        System.out.println("🔄 Folder '" + folderName + "' (hasChildren=" + hasChildren + "), gọi đệ quy...");
                        syncFolderTreeRecursive(folderId, processedFolders);
                    } else {
                        System.out.println("📭 Folder '" + folderName + "' không có children, bỏ qua đệ quy");
                    }
                } else {
                    System.err.println("❌ Lưu folder '" + folderName + "' thất bại");
                }
            }
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi đồng bộ folder tree recursive (parentId=" + parentId + "): " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Lưu một folder vào local DB
     * @return true nếu thành công, false nếu thất bại
     */
    private boolean saveFolderToLocalDB(int serverFolderId, Integer serverParentId, String folderName) {
        try (Connection conn = localDbManager.getConnection()) {
            // Tìm local FolderID của parent
            Integer localParentId = null;
            if (serverParentId != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
                    ps.setInt(1, serverParentId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        localParentId = rs.getInt("FolderID");
                    } else {
                        // Parent chưa tồn tại
                        return false;
                    }
                }
            }
            
            // Build relative path
            String relativePath = buildRelativePathForFolder(serverFolderId, folderName, localParentId, conn);
            
            // Insert hoặc update folder
            String sql = """
                INSERT INTO Folders (ServerFolderID, ParentFolderID, FolderName, LocalPath, SyncStatus)
                VALUES (?, ?, ?, ?, 'SYNCED')
                ON CONFLICT(ServerFolderID) DO UPDATE SET
                    ParentFolderID = excluded.ParentFolderID,
                    FolderName = excluded.FolderName,
                    LocalPath = excluded.LocalPath,
                    SyncStatus = 'SYNCED'
            """;
            
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, serverFolderId);
                if (localParentId != null) {
                    ps.setInt(2, localParentId);
                } else {
                    ps.setNull(2, java.sql.Types.INTEGER);
                }
                ps.setString(3, folderName);
                ps.setString(4, relativePath);
                ps.executeUpdate();
            }
            
            // FIX RACE CONDITION: Commit ngay để đảm bảo dữ liệu visible cho query tiếp theo
            if (!conn.getAutoCommit()) {
                conn.commit();
            }
            
            // Tạo thư mục trên filesystem
            if (!relativePath.isEmpty()) {
                Path folderPath = syncDirectory.resolve(relativePath);
                Files.createDirectories(folderPath);
            }
            
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi lưu folder '" + folderName + "': " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Build relative path cho folder từ root
     */
    private String buildRelativePathForFolder(int serverFolderId, String folderName, Integer localParentId, Connection conn) throws SQLException {
        if (serverFolderId == 1 || localParentId == null) {
            // Root folder
            return "";
        }
        
        // Lấy path của parent
        String parentPath = "";
        try (PreparedStatement ps = conn.prepareStatement("SELECT LocalPath FROM Folders WHERE FolderID = ?")) {
            ps.setInt(1, localParentId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                parentPath = rs.getString("LocalPath");
            }
        }
        
        // Nối path
        if (parentPath == null || parentPath.isEmpty()) {
            return folderName;
        } else {
            return parentPath + "/" + folderName;
        }
    }

    // ========== Down-sync (SEQ) ==========
    private void triggerDownSync() {
        System.out.println("🔄 Bắt đầu Down-Sync (seq)...");
        suppressWatcherEvents.set(true);
        try {
            long sinceSeq = getSinceSeqCompat();
            
            // QUAN TRỌNG: Tải toàn bộ folder tree từ server TRƯỚC
            // để đảm bảo client biết tất cả các folder trước khi xử lý file changes
            syncFolderTreeFromServer();

            // Gọi API Change Feed theo seq
            JsonObject data = new JsonObject();
            data.addProperty("sinceSeq", sinceSeq);
            Response response = networkService.sendRequest(new Request("GET_CHANGES_SINCE", data));
            if (!"success".equals(response.getStatus())) {
                System.err.println("Lỗi Down-Sync: " + response.getMessage());
                return;
            }
            JsonObject responseData = asObj(response.getData());
            if (responseData == null) return;

            JsonArray changes = asArr(responseData.get("changes"));
            if (changes != null) {
                System.out.println("🔽 Nhận " + changes.size() + " thay đổi từ server.");
                
                // Phân loại changes: folders trước, files sau để tránh lỗi foreign key
                java.util.List<JsonObject> folderChanges = new java.util.ArrayList<>();
                java.util.List<JsonObject> fileChanges = new java.util.ArrayList<>();
                
                for (JsonElement change : changes) {
                    JsonObject obj = asObj(change);
                    if (obj == null) continue;
                    String type = obj.has("type") ? obj.get("type").getAsString() : "";
                    
                    if (type.startsWith("FOLDER_")) {
                        folderChanges.add(obj);
                    } else {
                        fileChanges.add(obj);
                    }
                }
                
                // Xử lý folder changes trước - lặp nhiều lần để đảm bảo parent được tạo trước child
                java.util.Set<Integer> processedFolders = new java.util.HashSet<>();
                int maxRetries = Math.max(folderChanges.size() * 2, 10); // Tăng số lần retry
                int retryCount = 0;
                
                System.out.println("📁 Bắt đầu xử lý " + folderChanges.size() + " folder changes...");
                
                while (!folderChanges.isEmpty() && retryCount < maxRetries) {
                    java.util.List<JsonObject> remainingFolders = new java.util.ArrayList<>();
                    int processedThisRound = 0;
                    
                    System.out.println("🔄 Round " + (retryCount + 1) + ": Đang xử lý " + folderChanges.size() + " folders...");
                    
                    for (JsonObject obj : folderChanges) {
                        try {
                            String type = obj.get("type").getAsString();
                            JsonObject payload = asObj(obj.get("data"));
                            if (payload == null) continue;

                            Integer folderId = jInt(payload, "FolderID", "folderId", "folderID");
                            Integer parentId = jInt(payload, "ParentFolderID", "parentFolderId", "parentID");
                            String folderName = jStr(payload, "Name", "folderName", "FolderName", "name");

                            if (folderId != null && processedFolders.contains(folderId)) {
                                // Folder này đã được xử lý, bỏ qua
                                continue;
                            }

                            // Debug log
                            System.out.println("  📂 Đang xử lý: FolderID=" + folderId + ", Name=" + folderName + 
                                             ", ParentID=" + parentId);

                            boolean success = false;
                            switch (type) {
                                case "FOLDER_MODIFIED":
                                case "FOLDER_CREATED":
                                case "FOLDER_UPDATE":
                                case "FOLDER_CREATE":
                                    success = handleServerFolderUpdate(payload);
                                    break;
                                case "FOLDER_DELETED":
                                case "FOLDER_DELETE":
                                    handleServerFolderDelete(payload);
                                    success = true; // Delete luôn thành công
                                    break;
                            }

                            if (success) {
                                if (folderId != null) processedFolders.add(folderId);
                                processedThisRound++;
                                System.out.println("    ✓ Thành công!");
                            } else {
                                // Folder chưa xử lý được (có thể parent chưa tồn tại), thêm vào danh sách chờ
                                remainingFolders.add(obj);
                                System.out.println("    ⏳ Tạm hoãn (sẽ thử lại)");
                            }
                        } catch (Exception e) {
                            // Log but keep going; add to remaining for retry attempts
                            System.err.println("Lỗi xử lý folder change " + obj + ": " + e.getMessage());
                            e.printStackTrace();
                            remainingFolders.add(obj);
                        }
                    }
                    
                    folderChanges = remainingFolders;
                    retryCount++;
                    
                    System.out.println("  ✅ Round " + retryCount + ": Đã xử lý " + processedThisRound + " folders, còn lại " + folderChanges.size());
                    
                    // Nếu không có tiến triển trong round này, thoát để tránh vòng lặp vô hạn
                    if (processedThisRound == 0 && !folderChanges.isEmpty()) {
                        System.err.println("⚠️ Không thể xử lý " + folderChanges.size() + " folder(s) sau " + retryCount + " lần thử");
                        // In ra chi tiết các folders không xử lý được
                        for (JsonObject obj : folderChanges) {
                            JsonObject payload = asObj(obj.get("data"));
                            if (payload != null) {
                                Integer fid = jInt(payload, "FolderID", "folderId", "folderID");
                                Integer pid = jInt(payload, "ParentFolderID", "parentFolderId", "parentID");
                                String fname = jStr(payload, "Name", "folderName", "FolderName", "name");
                                System.err.println("  ❌ FolderID=" + fid + ", Name=" + fname + ", ParentID=" + pid);
                            }
                        }
                        break;
                    }
                }
                
                System.out.println("✅ Hoàn thành xử lý folders. Đã xử lý: " + processedFolders.size() + " folders");
                
                // FIX RACE CONDITION: Sử dụng CountDownLatch để đợi tất cả downloads hoàn thành
                final java.util.concurrent.CountDownLatch downloadLatch = new java.util.concurrent.CountDownLatch(fileChanges.size());
                
                // Sau đó xử lý file changes
                for (JsonObject obj : fileChanges) {
                    try {
                        String type = obj.get("type").getAsString();
                        JsonObject payload = asObj(obj.get("data"));
                        if (payload == null) {
                            downloadLatch.countDown(); // Đếm xuống ngay cả khi skip
                            continue;
                        }
                        
                        switch (type) {
                            case "FILE_MODIFIED":
                            case "FILE_CREATED":
                            case "FILE_UPDATE":
                            case "FILE_CREATE":
                                try {
                                    handleServerFileUpdate(payload);
                                } catch (Exception e) {
                                    // handleServerFileUpdate should catch its own exceptions, but
                                    // guard here as well to ensure we never abort the whole down-sync
                                    System.err.println("Lỗi khi xử lý FILE change payload=" + payload + ": " + e.getMessage());
                                    e.printStackTrace();
                                } finally {
                                    downloadLatch.countDown(); // Đếm xuống sau khi xử lý xong
                                }
                                break;
                            case "FILE_DELETED":
                            case "FILE_DELETE":
                                try {
                                    handleServerFileDelete(payload);
                                } catch (Exception e) {
                                    System.err.println("Lỗi khi xử lý FILE_DELETE payload=" + payload + ": " + e.getMessage());
                                    e.printStackTrace();
                                } finally {
                                    downloadLatch.countDown(); // Đếm xuống sau khi xử lý xong
                                }
                                break;
                            default:
                                downloadLatch.countDown(); // Đếm xuống cho các case không xử lý
                                break;
                        }
                    } catch (Exception e) {
                        // Log per-change error and continue with next change
                        System.err.println("Lỗi xử lý change " + obj + ": " + e.getMessage());
                        e.printStackTrace();
                        downloadLatch.countDown(); // Đếm xuống ngay cả khi có lỗi
                    }
                }
                
                // ĐỢI tất cả downloads hoàn thành (timeout 60s)
                try {
                    System.out.println("⏳ Đang đợi " + fileChanges.size() + " file downloads hoàn thành...");
                    boolean completed = downloadLatch.await(60, java.util.concurrent.TimeUnit.SECONDS);
                    if (!completed) {
                        System.err.println("⚠️ Timeout: Một số downloads chưa hoàn thành sau 60s");
                      } else {
                        System.out.println("✅ Tất cả downloads đã hoàn thành!");
                      }
                } catch (InterruptedException e) {
                    System.err.println("⚠️ Bị gián đoạn khi đợi downloads: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }
            }

            // Lấy watermark seq mới
            long newHead = -1L;
            if (responseData.has("head")) newHead = responseData.get("head").getAsLong();
            else if (responseData.has("lastSeq")) newHead = responseData.get("lastSeq").getAsLong();
            if (newHead >= 0) {
                setSinceSeqCompat(newHead);
                System.out.println("✅ Down-Sync hoàn tất. since_seq=" + newHead);
            } else {
                System.out.println("⚠️ Server không trả head/lastSeq; giữ since_seq=" + sinceSeq);
            }

        } catch (Exception e) {
            System.err.println("Lỗi Down-Sync: " + e.getMessage());
            e.printStackTrace();
        } finally {
            suppressWatcherEvents.set(false);
        }
    }

    /** Server báo file sửa/tạo mới → tải về và cập nhật cache */
    private void handleServerFileUpdate(JsonObject data) {
        try {
            Integer fileId   = jInt(data,   "EntityId", "fileId", "FileID", "id");
            String  fileName = jStr(data,   "Name", "fileName", "FileName", "name");
            Integer folderId = jInt(data,   "FolderID", "folderId", "folderID");

            if (fileId == null || fileName == null || folderId == null) {
                System.err.println("handleServerFileUpdate: thiếu field. data=" + data);
                return;
            }

            System.out.println("[Down-Sync] Tải về: " + fileName + " (ID=" + fileId + " từ folderId: " + folderId + ")");

            // Trước khi tải file, đảm bảo client đã biết folder mapping
            Integer localFolderId = null;
            String folderLocalPath = null;
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT FolderID, LocalPath FROM Folders WHERE ServerFolderID = ?")) {
                ps.setInt(1, folderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        localFolderId = rs.getInt("FolderID");
                        folderLocalPath = rs.getString("LocalPath");
                    }
                }
            }

            if (localFolderId == null) {
                System.err.println("⚠️ Không tìm thấy mapping local cho ServerFolderID=" + folderId + ". Thử đồng bộ folder tree rồi thử lại.");
                // Thử đồng bộ folder tree từ server 1 lần
                syncFolderTreeFromServer();
                try (Connection conn = localDbManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement("SELECT FolderID, LocalPath FROM Folders WHERE ServerFolderID = ?")) {
                    ps.setInt(1, folderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            localFolderId = rs.getInt("FolderID");
                            folderLocalPath = rs.getString("LocalPath");
                        }
                    }
                }
            }

            if (localFolderId == null) {
                System.err.println("⛔ Bỏ qua việc tải file (ServerFileID=" + fileId + ") vì folder local cho ServerFolderID=" + folderId + " vẫn không tồn tại.");
                return;
            }

            // XÂY DỰNG relativePath từ folderLocalPath + fileName
            String relativePath;
            if (folderLocalPath == null || folderLocalPath.isEmpty()) {
                // Root folder
                relativePath = fileName;
            } else {
                // Thư mục con: nối path
                relativePath = folderLocalPath + "/" + fileName;
            }
            System.out.println("📍 Đường dẫn tải về: " + relativePath);

            com.pbl4.syncproject.client.models.FileItem item = new com.pbl4.syncproject.client.models.FileItem();
            item.setFileId(fileId);
            item.setFolderId(folderId);
            item.setFileName(fileName);
            item.setRelativePath(relativePath);

            downloadService.downloadAndSaveFile(item);

            notificationManager.addNotification(
                    "Đã tải phiên bản mới: " + fileName,
                    com.pbl4.syncproject.client.models.NotificationItem.NotificationType.SYSTEM
            );
        } catch (Exception e) {
            System.err.println("Lỗi handleServerFileUpdate cho data=" + data + ": " + e.getMessage());
            e.printStackTrace();
        }
    }


    /** Server báo file bị xóa (xử lý xung đột: LOCAL_STALE thắng) */
    private void handleServerFileDelete(JsonObject data) {
        try {
            Integer serverFileId = jInt(data, "EntityId", "fileId", "FileID", "id");
            if (serverFileId == null) {
                System.err.println("handleServerFileDelete: thiếu fileId. data=" + data);
                return;
            }

            String localPathStr = null;
            String syncStatus = null;
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT LocalPath, SyncStatus FROM Files WHERE ServerFileID=?")) {
                ps.setInt(1, serverFileId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    localPathStr = rs.getString("LocalPath");
                    syncStatus = rs.getString("SyncStatus");
                }
            }

            if (LocalDatabaseManager.STATUS_LOCAL_STALE.equals(syncStatus)) {
                notificationManager.addNotification(
                        "⚠️ Xung đột: File '" + localPathStr + "' bạn đang sửa đã bị xóa ở nơi khác. Đã giữ lại phiên bản của bạn.",
                        com.pbl4.syncproject.client.models.NotificationItem.NotificationType.SYSTEM
                );
                return;
            }

            if (localPathStr != null) {
                Path localPath = syncDirectory.resolve(localPathStr);
                Files.deleteIfExists(localPath);
                try (Connection conn = localDbManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement("DELETE FROM Files WHERE ServerFileID=?")) {
                    ps.setInt(1, serverFileId);
                    ps.executeUpdate();
                }
                notificationManager.addNotification(
                        "File đã bị xóa bởi người khác: " + localPath.getFileName(),
                        com.pbl4.syncproject.client.models.NotificationItem.NotificationType.SYSTEM
                );
            }
        } catch (Exception e) {
            System.err.println("Lỗi handleServerFileDelete: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Server báo folder sửa/tạo mới → tạo local và cập nhật cache 
     * @return true nếu thành công, false nếu parent chưa tồn tại */
    private boolean handleServerFolderUpdate(JsonObject data) {
        try {
            Integer serverFolderId = jInt(data, "FolderID", "folderId", "folderID");
            String  folderName     = jStr(data, "Name", "folderName", "FolderName", "name");
            String  relativePath   = jStr(data, "relativePath");
            Integer serverParentId = jInt(data, "ParentFolderID", "parentFolderId", "parentID");

            if (serverFolderId == null || folderName == null) {
                System.err.println("handleServerFolderUpdate: thiếu field. data=" + data);
                return false;
            }

            String rel = (relativePath != null && !relativePath.isBlank()) ? relativePath : folderName;

            // Kiểm tra parent folder có tồn tại trong local DB không
            Integer localParentId = null;
            if (serverParentId != null) {
                // Folder này có parent, cần tìm localParentId
                try (Connection conn = localDbManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement("SELECT FolderID FROM Folders WHERE ServerFolderID=?")) {
                    ps.setInt(1, serverParentId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        localParentId = rs.getInt("FolderID");
                    } else {
                        // Parent folder chưa tồn tại, trả về false để thử lại sau
                        System.out.println("⏳ Tạm hoãn folder '" + folderName + "' (ServerID=" + serverFolderId + 
                                         ") vì parent folder (ServerID=" + serverParentId + ") chưa tồn tại trong local DB");
                        return false;
                    }
                }
            } else {
                // Đây là root folder (không có parent)
                System.out.println("📁 Đây là ROOT folder: " + folderName + " (ServerID=" + serverFolderId + ")");
            }

            Path localPath = syncDirectory.resolve(rel);
            Files.createDirectories(localPath);

            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT OR REPLACE INTO Folders (ServerFolderID, ParentFolderID, FolderName, LocalPath, SyncStatus) " +
                                 "VALUES (?, ?, ?, ?, ?)")) {
                ps.setInt(1, serverFolderId);
                if (localParentId != null) ps.setInt(2, localParentId);
                else ps.setNull(2, java.sql.Types.INTEGER);
                ps.setString(3, folderName);
                ps.setString(4, rel);
                ps.setString(5, LocalDatabaseManager.STATUS_SYNCED);
                ps.executeUpdate();
            }
            
            System.out.println("✅ Đã tạo folder: " + folderName + " (ServerID=" + serverFolderId + ")");
            return true;
        } catch (Exception e) {
            System.err.println("Lỗi handleServerFolderUpdate: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** Server báo folder bị xóa (xử lý xung đột: nếu có file LOCAL_STALE thì giữ lại) */
    private void handleServerFolderDelete(JsonObject data) {
        try {
            Integer serverFolderId = jInt(data, "folderId", "FolderID", "folderID");
            if (serverFolderId == null) {
                System.err.println("handleServerFolderDelete: thiếu folderId. data=" + data);
                return;
            }

            String localPathStr = null;
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT LocalPath FROM Folders WHERE ServerFolderID=?")) {
                ps.setInt(1, serverFolderId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) localPathStr = rs.getString("LocalPath");
            }
            if (localPathStr == null) return;

            boolean hasLocalModified = false;
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT COUNT(*) FROM Files WHERE FolderID IN (" +
                                 "SELECT FolderID FROM Folders WHERE ServerFolderID=?" +
                                 ") AND SyncStatus=?")) {
                ps.setInt(1, serverFolderId);
                ps.setString(2, LocalDatabaseManager.STATUS_LOCAL_STALE);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) hasLocalModified = true;
            }
            if (hasLocalModified) {
                notificationManager.addNotification(
                        "⚠️ Xung đột: Thư mục '" + localPathStr + "' có file bạn đang sửa nhưng đã bị xóa ở nơi khác. Đã giữ lại các file của bạn.",
                        com.pbl4.syncproject.client.models.NotificationItem.NotificationType.SYSTEM
                );
                return;
            }

            Path localPath = syncDirectory.resolve(localPathStr);
            if (Files.exists(localPath)) {
                Files.walk(localPath)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
            }
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM Folders WHERE ServerFolderID=?")) {
                ps.setInt(1, serverFolderId);
                ps.executeUpdate();
            }

            notificationManager.addNotification(
                    "Thư mục đã bị xóa bởi người khác: " + localPathStr,
                    com.pbl4.syncproject.client.models.NotificationItem.NotificationType.SYSTEM
            );
        } catch (Exception e) {
            System.err.println("Lỗi handleServerFolderDelete: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Xử lý xung đột upload: đổi tên bản local + đánh dấu CONFLICT, sau đó để down-sync tải bản server */
    private void handleConflict(SyncTask task, File localFile) {
        try {
            String originalName = localFile.getName();
            String extension = "";
            String baseName = originalName;
            int dot = originalName.lastIndexOf('.');
            if (dot > 0) { baseName = originalName.substring(0, dot); extension = originalName.substring(dot); }

            // Tạo tên unique để tránh FileAlreadyExistsException
            String conflictedName;
            Path conflictedPath;
            int conflictNumber = 1;
            do {
                if (conflictNumber == 1) {
                    conflictedName = String.format("%s (Bản sao xung đột của %s)%s",
                            baseName, networkService.getCurrentUsername(), extension).trim();
                } else {
                    conflictedName = String.format("%s (Bản sao xung đột %d của %s)%s",
                            baseName, conflictNumber, networkService.getCurrentUsername(), extension).trim();
                }
                conflictedPath = localFile.toPath().resolveSibling(conflictedName);
                conflictNumber++;
            } while (Files.exists(conflictedPath));
            
            System.out.println("🔄 [Conflict] Đổi tên: " + originalName + " → " + conflictedName);
            
            // Đổi tên file trong chế độ muted để tránh FileWatcher phát hiện
            final Path sourcePath = localFile.toPath();
            final Path targetPath = conflictedPath;
            final String finalConflictedName = conflictedName;
            fileWatcher.runMuted(() -> {
                try {
                    Files.move(sourcePath, targetPath);
                } catch (IOException e) {
                    throw new RuntimeException("Lỗi đổi tên file conflict: " + e.getMessage(), e);
                }
            });

            String newSqlPath = syncDirectory.relativize(targetPath).toString().replace("\\", "/");
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE Files SET LocalPath=?, FileName=?, SyncStatus=? WHERE LocalPath=?")) {
                ps.setString(1, newSqlPath);
                ps.setString(2, finalConflictedName);
                ps.setString(3, LocalDatabaseManager.STATUS_CONFLICT);
                ps.setString(4, task.localPath);
                ps.executeUpdate();
            }

            notificationManager.addNotification(
                    "⚠️ Xung đột: '" + originalName + "' đã đổi thành '" + finalConflictedName + "'. Sẽ tải bản server về.",
                    com.pbl4.syncproject.client.models.NotificationItem.NotificationType.SYSTEM
            );

            // ----- FIX: KÍCH HOẠT DOWN-SYNC NGAY LẬP TỨC -----
            // Sau khi đổi tên file local, chủ động tải phiên bản đúng từ server về
            // (thay vì để fileWatcher tạo vòng lặp mới với file conflict).
            System.out.println("🔥 Kích hoạt Down-Sync ngay lập tức do có Xung đột (Conflict)...");
            // Chạy down-sync ở thread riêng để không block thread xử lý queue hiện tại
            new Thread(this::triggerDownSync, "Conflict-Down-Sync-Thread").start();
            // ---------------------------------------------
            
        } catch (Exception e) {
            System.err.println("Lỗi xử lý xung đột: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===================== MIRROR =====================
    // Cho phép Settings gọi refresh full (reset DB + mirror) mà không rò rỉ chi tiết bên trong
    public void forceFullRefreshFromSettings() throws Exception {
        // Tạm tắt watcher để tránh vòng lặp
        fileWatcher.pause();
        suppressWatcherEvents.set(true);
        try {
            // Dùng init DB sạch + mirror
            mirrorFromServerOverwriteLocal();
            // thông báo UI refresh
            if (eventListener != null) eventListener.onLocalChangeDetected();
        } finally {
            suppressWatcherEvents.set(false);
            fileWatcher.resume();
        }
    }

    /**
     * Xoá sạch dữ liệu trong thư mục đồng bộ + reset cache DB,
     * sau đó tải toàn bộ cây thư mục & file từ server về local (ghi đè).
     * Kết thúc: set since_seq = head hiện tại nếu server hỗ trợ, nếu không thì để 0.
     */
    private void mirrorFromServerOverwriteLocal() throws Exception {
        // 1) Xoá sạch nội dung thư mục sync (giữ thư mục root)
        if (Files.exists(syncDirectory)) {
            try (Stream<Path> walk = Files.walk(syncDirectory)) {
                walk.sorted((a, b) -> -a.compareTo(b)) // file trước, folder sau
                        .forEach(p -> {
                            if (!p.equals(syncDirectory)) {
                                try { Files.deleteIfExists(p); } catch (Exception ignore) {}
                            }
                        });
            }
        }

        // 2) Reset schema chuẩn bằng LocalDatabaseManager (drop & create lại toàn bộ)
        localDbManager.initializeDatabase(true);

        // 2.1) Đảm bảo có hàng root (FolderID = 1) cho cây thư mục local
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO Folders (FolderID, FolderName, LocalPath, SyncStatus) " +
                             "VALUES (1, '', '', 'SYNCED')")) {
            ps.executeUpdate();
        }

        // 3) Duyệt cây thư mục & tải file (mirror)
        mirrorFetchFoldersAndFiles();

        // 4) Lấy head seq hiện tại (nếu server có API), rồi lưu since_seq
        long head = fetchFeedHeadCompat();
        if (head > 0) {
            setSinceSeqCompat(head);
            System.out.println("✅ MIRROR hoàn tất. since_seq=" + head);
        } else {
            setSinceSeqCompat(0L);
            System.out.println("✅ MIRROR hoàn tất. (server không trả head) since_seq=0");
        }
    }
    private void mirrorFetchFoldersAndFiles() throws Exception {
        Response resRoots = networkService.getFolderTree(1);
        if (!"success".equals(resRoots.getStatus())) {
            resRoots = networkService.getFolderTree(0);
            if (!"success".equals(resRoots.getStatus())) {
                throw new IllegalStateException("FOLDER_TREE thất bại: " + resRoots.getMessage());
            }
        }

        JsonArray rootFolders = null;
        JsonObject d = asObj(resRoots.getData());
        if (d != null && d.has("folders")) {
            rootFolders = asArr(d.get("folders"));
        }
        if (rootFolders == null) {
            rootFolders = asArr(resRoots.getData());
        }

        if (rootFolders == null) {
            mirrorFilesInFolder(1, "");
            return;
        }

        for (JsonElement el : rootFolders) {
            JsonObject fo = asObj(el);
            if (fo == null) continue;

            Integer serverFolderId = jInt(fo, "folderId", "FolderID", "folderID", "id");
            String  folderName     = jStr(fo, "folderName", "FolderName", "name");

            if (serverFolderId == null || folderName == null) {
                System.err.println("mirror root: thiếu folderId/folderName. obj=" + fo);
                continue;
            }

            String rel = folderName;
            Files.createDirectories(syncDirectory.resolve(rel));

            Integer localParentId = 1;
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT OR REPLACE INTO Folders (ServerFolderID, ParentFolderID, FolderName, LocalPath, SyncStatus) " +
                                 "VALUES (?, ?, ?, ?, 'SYNCED')")) {
                ps.setInt(1, serverFolderId);
                ps.setInt(2, localParentId);
                ps.setString(3, folderName);
                ps.setString(4, rel);
                ps.executeUpdate();
            }

            mirrorFolderRecursive(serverFolderId, rel);
        }

        mirrorFilesInFolder(1, "");
    }

    private void mirrorFolderRecursive(int serverFolderId, String relativePath) throws Exception {
        mirrorFilesInFolder(serverFolderId, relativePath);

        Response resChild = networkService.getFolderTree(serverFolderId);
        if (!"success".equals(resChild.getStatus())) return;

        JsonArray childArr = null;
        JsonObject d = asObj(resChild.getData());
        if (d != null && d.has("folders")) {
            childArr = asArr(d.get("folders"));
        }
        if (childArr == null) childArr = asArr(resChild.getData());
        if (childArr == null) return;

        for (JsonElement el : childArr) {
            JsonObject fo = asObj(el);
            if (fo == null) continue;

            Integer childServerId = jInt(fo, "folderId", "FolderID", "folderID", "id");
            String  name          = jStr(fo, "folderName", "FolderName", "name");
            if (childServerId == null || name == null) {
                System.err.println("mirror child: thiếu folderId/folderName. obj=" + fo);
                continue;
            }

            String childRel = relativePath.isEmpty() ? name : (relativePath + "/" + name);
            Files.createDirectories(syncDirectory.resolve(childRel));

            Integer localParentId = null;
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
                ps.setInt(1, serverFolderId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) localParentId = rs.getInt("FolderID");
            }

            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT OR REPLACE INTO Folders (ServerFolderID, ParentFolderID, FolderName, LocalPath, SyncStatus) " +
                                 "VALUES (?, ?, ?, ?, 'SYNCED')")) {
                ps.setInt(1, childServerId);
                if (localParentId != null) ps.setInt(2, localParentId); else ps.setNull(2, java.sql.Types.INTEGER);
                ps.setString(3, name);
                ps.setString(4, childRel);
                ps.executeUpdate();
            }

            mirrorFolderRecursive(childServerId, childRel);
        }
    }

    private void mirrorFilesInFolder(int serverFolderId, String relativePath) throws Exception {
        Response res = networkService.getFileList(serverFolderId);
        if (!"success".equals(res.getStatus())) return;

        JsonArray filesArr = null;
        JsonObject d = asObj(res.getData());
        if (d != null && d.has("files")) filesArr = asArr(d.get("files"));
        if (filesArr == null) filesArr = asArr(res.getData());
        if (filesArr == null) return;

        Integer localFolderId = null;
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
            ps.setInt(1, serverFolderId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) localFolderId = rs.getInt("FolderID");
        }
        if (localFolderId == null) localFolderId = 1; // fallback root

        for (JsonElement el : filesArr) {
            JsonObject fo = asObj(el);
            if (fo == null) continue;

            Integer fileId = jInt(fo, "fileId", "FileID", "id");
            String  name   = jStr(fo, "fileName", "FileName", "name");
            Long    size   = jLong(fo, "fileSize", "size");
            String  hash   = jStr(fo, "fileHash", "hash");

            if (fileId == null || name == null) {
                System.err.println("mirror files: thiếu fileId/fileName. obj=" + fo);
                continue;
            }

            String relPath = relativePath.isEmpty() ? name : (relativePath + "/" + name);

            com.pbl4.syncproject.client.models.FileItem item = new com.pbl4.syncproject.client.models.FileItem();
            item.setFileId(fileId);
            item.setFolderId(serverFolderId);
            item.setFileName(name);
            item.setRelativePath(relPath);
            downloadService.downloadAndSaveFile(item);

            localDbManager.upsertDownloadedFile(
                    fileId,
                    localFolderId,
                    name,
                    size != null ? size : 0L,
                    relPath.replace("\\", "/"),
                    hash != null ? hash : ""
            );
        }

    }
    // =================== HẾT PHẦN MIRROR ===================

    // ==== since_seq COMPAT (không cần sửa LocalDatabaseManager) ====
    private long getSinceSeqCompat() {
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT Value FROM Settings WHERE Key='since_seq'")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Long.parseLong(rs.getString("Value"));
        } catch (Exception ignore) {}
        return 0L; // mặc định: chưa từng đồng bộ
    }

    private void setSinceSeqCompat(long seq) {
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO Settings (Key, Value) VALUES ('since_seq', ?)")) {
            ps.setString(1, String.valueOf(seq));
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("⚠️ Không thể set since_seq: " + e.getMessage());
        }
    }

    /**
     * Public method để NetworkService heartbeat có thể lấy since_seq hiện tại
     * @return since_seq hiện tại trong local DB
     */
    public long getSinceSeqForHeartbeat() {
        return getSinceSeqCompat();
    }

    /** Thử gọi GET_FEED_HEAD để lấy watermark seq hiện tại */
    private long fetchFeedHeadCompat() {
        try {
            Response res = networkService.sendRequest(new Request("GET_FEED_HEAD", new JsonObject()));
            if ("success".equals(res.getStatus())) {
                JsonObject d = asObj(res.getData());
                if (d != null) {
                    if (d.has("head")) return d.get("head").getAsLong();
                    if (d.has("lastSeq")) return d.get("lastSeq").getAsLong();
                }
            } else {
                System.out.println("GET_FEED_HEAD lỗi: " + res.getMessage());
            }
        } catch (Exception e) {
            System.out.println("GET_FEED_HEAD exception: " + e.getMessage());
        }
        return -1L; // không có
    }

    // ==== Data structures ====
    private static class SyncTask {
        int queueId;
        String action;
        String localPath;
        Integer targetParentID;
        String targetName;

        SyncTask(int queueId, String action, String localPath) {
            this.queueId = queueId; this.action = action; this.localPath = localPath;
        }
        SyncTask(int queueId, String action, String localPath, Integer targetParentID, String targetName) {
            this(queueId, action, localPath);
            this.targetParentID = targetParentID; this.targetName = targetName;
        }
    }

    public enum SyncOperation { UPLOAD, DELETE, DOWNLOAD }

    public static class SyncStatus {
        public final boolean isRunning;
        public final int queueSize;
        public final long processedCount;
        public final int trackedFiles;
        public SyncStatus(boolean isRunning, int queueSize, long processedCount, int trackedFiles) {
            this.isRunning = isRunning; this.queueSize = queueSize; this.processedCount = processedCount; this.trackedFiles = trackedFiles;
        }
        @Override public String toString() {
            return String.format("SyncStatus{running=%s, queue=%d, processed=%d, tracked=%d}",
                    isRunning, queueSize, processedCount, trackedFiles);
        }
    }

    // ==== JSON helpers (ép kiểu an toàn) ====
    private JsonObject asObj(JsonElement el) {
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }
    private JsonArray asArr(JsonElement el) {
        return (el != null && el.isJsonArray()) ? el.getAsJsonArray() : null;
    }

    // ======= Full Refresh (public API) =======
    private final java.util.concurrent.atomic.AtomicBoolean isRefreshing = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Thực thi full refresh theo SEQ (blocking):
     * - Tắt phát sự kiện watcher tạm thời (pause)
     * - Huỷ các debounce pending
     * - Mirror lại toàn bộ từ server (xoá files/DB cache rồi tải mới)
     * - Gọi eventListener để UI reload
     */
    public void forceFullRefreshBlocking() throws Exception {
        if (!isRefreshing.compareAndSet(false, true)) {
            System.out.println("Full refresh is already running. Skip.");
            return;
        }
        try {
            // Tắt watcher event (và huỷ debounce)
            suppressWatcherEvents.set(true);
            fileWatcher.pause();
            for (ScheduledFuture<?> f : pendingSyncs.values()) { try { f.cancel(false); } catch (Exception ignore) {} }
            pendingSyncs.clear();

            // Thực thi mirror (đã có xoá thư mục & reset DB bên trong)
            System.out.println("⟳ FULL REFRESH: resetting local state and mirroring from server...");
            mirrorFromServerOverwriteLocal(); // đã set since_seq theo head nếu có

            // Báo UI reload (bảng/khung view)
            if (eventListener != null) eventListener.onLocalChangeDetected();
            System.out.println("✅ FULL REFRESH done.");

        } finally {
            // Mở lại watcher
            fileWatcher.resume();
            suppressWatcherEvents.set(false);
            isRefreshing.set(false);
        }
    }
    // ==== JSON safe getters ====
    private Integer jInt(JsonObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try {
                    return o.get(k).getAsInt();
                } catch (Exception ignore) {
                    try { return (int) o.get(k).getAsLong(); } catch (Exception ignore2) {}
                    try { return Integer.parseInt(o.get(k).getAsString()); } catch (Exception ignore3) {}
                }
            }
        }
        return null;
    }
    private Long jLong(JsonObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try { return o.get(k).getAsLong(); } catch (Exception ignore) {
                    try { return Long.parseLong(o.get(k).getAsString()); } catch (Exception ignore2) {}
                }
            }
        }
        return null;
    }
    private String jStr(JsonObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try { return o.get(k).getAsString(); } catch (Exception ignore) {}
            }
        }
        return null;
    }

}

