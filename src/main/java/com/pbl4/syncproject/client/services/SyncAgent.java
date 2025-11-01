package com.pbl4.syncproject.client.services;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;

// --- THÊM CÁC IMPORT NÀY ---
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.Response;
// -----------------------------

/**
 * Main Sync Agent để quản lý automatic file synchronization
 * Sử dụng FileWatcher để detect changes và FileHashService để verify changes
 * Tránh polling liên tục và chỉ sync khi thực sự có thay đổi
 */
public class SyncAgent implements FileWatcherService.FileChangeListener {
    
    private final FileWatcherService fileWatcher;
    private final FileHashService hashService;
    private final NetworkService networkService;
    @SuppressWarnings("unused")
    private final UploadManager uploadManager;
    
    // --- THÊM DÒNG NÀY ---
    private final LocalDatabaseManager localDbManager;
    private final NotificationManager notificationManager = NotificationManager.getInstance(); // BƯỚC 7.4
    // -----------------------
    
    // Sync configuration
    private Path syncDirectory;
    private boolean isRunning = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // Debounce mechanism để tránh sync quá nhiều lần cho cùng 1 file
    private final Map<String, ScheduledFuture<?>> pendingSyncs = new ConcurrentHashMap<>();
    private final long DEBOUNCE_DELAY_MS = 2000; // 2 seconds
    
    // --- XÓA CÁC BIẾN SAU ---
    // private final SyncQueue syncQueue; // <-- ĐÃ XÓA
    // private final Map<String, String> lastKnownHashes = new ConcurrentHashMap<>(); // <-- ĐÃ XÓA
    // -----------------------
    
    // --- THÊM CÁC BIẾN NÀY (BƯỚC 5.3) ---
    // Luồng nền để chạy tác vụ đồng bộ
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SyncQueue-Processor");
        t.setDaemon(true);
        return t;
    });
    // Biến cờ (Atomic) để đảm bảo chỉ 1 luồng xử lý queue chạy
    private final AtomicBoolean isProcessingQueue = new AtomicBoolean(false);
    // -----------------------
    
    // --- SỬA LẠI HÀM CONSTRUCTOR ---
    public SyncAgent(NetworkService networkService, UploadManager uploadManager, LocalDatabaseManager localDbManager) {
        this.networkService = networkService;
        this.fileWatcher = new FileWatcherService();
        this.hashService = new FileHashService();
        this.uploadManager = uploadManager;
        
        // --- THÊM DÒNG NÀY ---
        this.localDbManager = localDbManager;
        // -----------------------
        
        // Register as listener
        fileWatcher.addListener(this);
    }
    // ---------------------------------
    
    /**
     * Start sync agent với sync directory
     */
    public void start(String syncDirectoryPath) throws Exception {
        if (isRunning) {
            return;
        }
        
        syncDirectory = Paths.get(syncDirectoryPath);
        
        // Ensure directory exists
        File dir = syncDirectory.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        // --- THÊM DÒNG NÀY ---
        // 1. Quét các file đã có (như test.txt) và thêm vào SyncQueue
        scanLocalFilesOnStartup();
        // -----------------------
        
        // 2. Start file watcher (cho các thay đổi MỚI)
        fileWatcher.start();
        fileWatcher.watchDirectory(syncDirectory);
        
        // --- XÓA CÁC DÒNG SAU ---
        // syncQueue.start(); // <-- ĐÃ XÓA
        // calculateInitialHashes(); // <-- ĐÃ XÓA
        // -----------------------
        
        isRunning = true;
        System.out.println("SyncAgent (Offline-Mode) started cho thư mục: " + syncDirectoryPath);
    }
    
    /**
     * Stop sync agent
     */
    public void stop() {
        isRunning = false;
        
        // Cancel pending syncs
        for (ScheduledFuture<?> future : pendingSyncs.values()) {
            future.cancel(false);
        }
        pendingSyncs.clear();
        
        // Stop services
        fileWatcher.stop();
        // --- XÓA DÒNG SAU ---
        // syncQueue.stop(); // <-- ĐÃ XÓA
        // -----------------------
        scheduler.shutdown();
        
        System.out.println("SyncAgent (Offline-Mode) stopped");
    }

    // --- XÓA HOÀN TOÀN HÀM NÀY ---
    // private void calculateInitialHashes() { ... } // <-- ĐÃ XÓA
    // -----------------------
    
    /**
     * (MỚI) Quét thư mục đồng bộ cục bộ khi khởi động.
     * So sánh các file trên đĩa với CSDL cache.
     * Nếu file có trên đĩa nhưng không có trong cache (hoặc hash khác),
     * nó sẽ được thêm vào SyncQueue để upload.
     */
    private void scanLocalFilesOnStartup() {
        System.out.println("🔄 Bắt đầu quét thư mục cục bộ khi khởi động...");
        
        try (java.util.stream.Stream<Path> fileStream = Files.walk(this.syncDirectory)) {
            fileStream
                .filter(Files::isRegularFile) // Chỉ lấy file
                .forEach(filePath -> {
                    try {
                        // Chúng ta "giả vờ" như file vừa được sửa
                        // Hàm này sẽ tự động kiểm tra hash và thêm vào queue nếu cần
                        processFileChange(filePath, SyncOperation.UPLOAD);
                    } catch (Exception e) {
                        System.err.println("Lỗi khi quét file: " + filePath + " - " + e.getMessage());
                    }
                });
        } catch (java.io.IOException e) {
            System.err.println("❌ Lỗi nghiêm trọng khi quét thư mục đồng bộ: " + e.getMessage());
        }
        System.out.println("✅ Quét thư mục cục bộ hoàn tất.");
    }
    
    /**
     * (MỚI) Tìm hoặc tạo FolderID cho một đường dẫn thư mục.
     * Đảm bảo rằng tất cả các thư mục cha trong đường dẫn đều có FolderID.
     * 
     * @param folderPath Đường dẫn tuyệt đối của thư mục
     * @param conn Connection đến database
     * @return FolderID hợp lệ (không bao giờ null)
     * @throws SQLException nếu có lỗi database
     */
    private int getOrCreateFolderId(Path folderPath, Connection conn) throws SQLException {
        // Nếu là thư mục đồng bộ gốc, trả về FolderID = 1 (root)
        if (folderPath.equals(syncDirectory)) {
            return 1;
        }
        
        String relativePath = syncDirectory.relativize(folderPath).toString();
        String sqlPath = relativePath.replace("\\", "/");
        
        // Kiểm tra xem thư mục đã có trong Folders chưa
        String sqlSelect = "SELECT FolderID FROM Folders WHERE LocalPath = ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlSelect)) {
            ps.setString(1, sqlPath);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt("FolderID");
            }
        }
        
        // Nếu chưa có, cần tạo mới
        // Đầu tiên, đảm bảo thư mục cha cũng có FolderID
        Path parentPath = folderPath.getParent();
        int parentFolderId = getOrCreateFolderId(parentPath, conn);
        
        // Tạo folder mới trong database
        String folderName = folderPath.getFileName().toString();
        String sqlInsert = "INSERT INTO Folders (ParentFolderID, FolderName, LocalPath, SyncStatus) VALUES (?, ?, ?, 'LOCAL_CREATED')";
        
        try (PreparedStatement ps = conn.prepareStatement(sqlInsert, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, parentFolderId);
            ps.setString(2, folderName);
            ps.setString(3, sqlPath);
            ps.executeUpdate();
            
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                int newFolderId = rs.getInt(1);
                System.out.println("[SyncAgent] Tạo folder mới trong cache: " + sqlPath + " (FolderID=" + newFolderId + ")");
                
                // Thêm vào SyncQueue để đồng bộ lên server
                String sqlQueue = "INSERT OR IGNORE INTO SyncQueue (Action, LocalPath, TargetParentID, TargetName) VALUES ('CREATE_FOLDER', ?, ?, ?)";
                try (PreparedStatement psQueue = conn.prepareStatement(sqlQueue)) {
                    psQueue.setString(1, sqlPath); // LocalPath để cập nhật sau khi sync
                    psQueue.setInt(2, parentFolderId);
                    psQueue.setString(3, folderName);
                    psQueue.executeUpdate();
                }
                
                return newFolderId;
            } else {
                throw new SQLException("Không thể tạo folder mới, không lấy được FolderID");
            }
        }
    }
    
    // FileChangeListener implementation
    
    @Override
    public void onFileCreated(Path filePath) {
        scheduleSync(filePath, SyncOperation.UPLOAD);
    }
    
    @Override
    public void onFileModified(Path filePath) {
        scheduleSync(filePath, SyncOperation.UPLOAD);
    }
    
    @Override
    public void onFileDeleted(Path filePath) {
        scheduleSync(filePath, SyncOperation.DELETE);
    }
    
    /**
     * Schedule sync operation với debounce
     */
    private void scheduleSync(Path filePath, SyncOperation operation) {
        String relativePath = syncDirectory.relativize(filePath).toString();
        
        // Cancel existing pending sync cho file này
        ScheduledFuture<?> existingSync = pendingSyncs.get(relativePath);
        if (existingSync != null && !existingSync.isDone()) {
            existingSync.cancel(false);
        }
        
        // Schedule new sync after debounce delay
        ScheduledFuture<?> newSync = scheduler.schedule(() -> {
            processFileChange(filePath, operation);
            pendingSyncs.remove(relativePath);
        }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
        
        pendingSyncs.put(relativePath, newSync);
    }
    
    /**
     * Phân loại thay đổi là File hay Folder (ĐÃ VIẾT LẠI CHO BƯỚC 5)
     */
    private void processFileChange(Path filePath, SyncOperation operation) {
        // Kiểm tra xem đây là file hay thư mục
        // Lưu ý: Đối với sự kiện DELETE, Files.exists(filePath) sẽ là false.
        
        if (operation == SyncOperation.DELETE) {
            // Chúng ta không biết đây là file hay thư mục,
            // chúng ta phải kiểm tra CSDL cục bộ
            processDeletedEntity(filePath);
        } else if (Files.isDirectory(filePath)) {
            // Đây là một thư mục (Create/Modify)
            processDirectoryChange(filePath, operation);
        } else if (Files.isRegularFile(filePath)) {
            // Đây là một file (Create/Modify)
            processFileChangeInternal(filePath, operation);
        }
    }

    /**
     * Xử lý thay đổi File (tên mới của hàm ở Bước 4)
     */
    private void processFileChangeInternal(Path filePath, SyncOperation operation) {
        String relativePath = syncDirectory.relativize(filePath).toString();
        String sqlPath = relativePath.replace("\\", "/"); 
        File file = filePath.toFile();

        try (Connection conn = localDbManager.getConnection()) {
            
            // --- XỬ LÝ TẠO MỚI / SỬA FILE ---
            if (!file.exists()) return; // File có thể đã bị xóa ngay sau khi tạo

            // 1. Tính hash mới của file
            String currentHash = hashService.calculateFileHash(file);
            if (currentHash == null) return; // Lỗi tính hash

            // 2. Lấy hash cũ (nếu có) từ CSDL SQLite
            String lastHash = null;
            String sqlSelect = "SELECT LastKnownHash FROM Files WHERE LocalPath = ?";
            try (PreparedStatement psSelect = conn.prepareStatement(sqlSelect)) {
                psSelect.setString(1, sqlPath);
                ResultSet rs = psSelect.executeQuery();
                if (rs.next()) {
                    lastHash = rs.getString("LastKnownHash");
                }
            }

            // 3. So sánh hash
            if (currentHash.equals(lastHash)) {
                System.out.println("[Offline Watcher] Bỏ qua (hash không đổi): " + sqlPath);
                return; // Hash giống hệt, không cần đồng bộ
            }

            System.out.println("[Offline Watcher] Đã phát hiện thay đổi FILE: " + sqlPath);

            // 4. Tìm hoặc tạo FolderID cho thư mục cha
            Path parentFolder = filePath.getParent();
            int folderId = getOrCreateFolderId(parentFolder, conn);

            // 5. Cập nhật CSDL cục bộ (UPSERT)
            // --- BƯỚC 7.1: GIỮ LastKnownHash (hash gốc) để phát hiện xung đột ---
            // Chúng ta KHÔNG cập nhật LastKnownHash nữa, chỉ cập nhật các thông tin khác
            // và đánh dấu là LOCAL_MODIFIED.
            String sqlUpsertFile = "INSERT OR REPLACE INTO Files "
                    + "(FileID, FolderID, FileName, FileSize, LocalPath, LastKnownHash, SyncStatus) "
                    + "VALUES ("
                    + " (SELECT FileID FROM Files WHERE LocalPath = ?), " // Giữ FileID cũ (nếu có)
                    + " ?, " // --- SỬA: Dùng folderId từ getOrCreateFolderId() ---
                    + " ?, ?, ?, "
                    + " (SELECT LastKnownHash FROM Files WHERE LocalPath = ?), " // --- QUAN TRỌNG: Giữ LastKnownHash cũ ---
                    + " 'LOCAL_MODIFIED'" // Đánh dấu là đã sửa
                    + ");";
            
            try (PreparedStatement psUpsert = conn.prepareStatement(sqlUpsertFile)) {
                psUpsert.setString(1, sqlPath);
                psUpsert.setInt(2, folderId); // --- SỬA: Dùng folderId ---
                psUpsert.setString(3, file.getName());
                psUpsert.setLong(4, file.length());
                psUpsert.setString(5, sqlPath);
                psUpsert.setString(6, sqlPath); // Tham số cho (SELECT LastKnownHash...)
                psUpsert.executeUpdate();
            }

            // 5. Thêm vào hàng đợi (Queue) để upload khi online
            String sqlQueue = "INSERT OR REPLACE INTO SyncQueue (Action, LocalPath) VALUES ('UPLOAD', ?)";
            try (PreparedStatement psQueue = conn.prepareStatement(sqlQueue)) {
                psQueue.setString(1, sqlPath);
                psQueue.executeUpdate();
            }

        } catch (Exception e) {
            System.err.println("Lỗi xử lý thay đổi file offline: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // --- THÊM HÀM MỚI ĐỂ XỬ LÝ THƯ MỤC ---
    
    /**
     * Xử lý khi phát hiện thay đổi trên THƯ MỤC (Create/Modify).
     */
    private void processDirectoryChange(Path filePath, SyncOperation operation) {
        String relativePath = syncDirectory.relativize(filePath).toString();
        String sqlPath = relativePath.replace("\\", "/");
        String folderName = filePath.getFileName().toString();
        
        try (Connection conn = localDbManager.getConnection()) {
            
            // Chỉ xử lý CREATE_FOLDER. Logic MODIFY thư mục (đổi tên) rất phức tạp, tạm bỏ qua.
            if (operation == SyncOperation.UPLOAD) { // Coi như là CREATE
                System.out.println("[Offline Watcher] Đã phát hiện tạo THƯ MỤC: " + sqlPath);
                
                // TODO: Tìm ParentFolderID chính xác
                int pId = 1; // Tạm thời giả định tạo dưới root
                
                // Thêm vào hàng đợi (Queue)
                String sqlQueue = "INSERT INTO SyncQueue (Action, TargetParentID, TargetName) VALUES ('CREATE_FOLDER', ?, ?)";
                try (PreparedStatement psQueue = conn.prepareStatement(sqlQueue)) {
                    psQueue.setInt(1, pId); // ID thư mục cha
                    psQueue.setString(2, folderName); // Tên thư mục mới
                    psQueue.executeUpdate();
                }
            }
            
        } catch (Exception e) {
            System.err.println("Lỗi xử lý thay đổi thư mục offline: " + e.getMessage());
        }
    }

    /**
     * Xử lý khi một thực thể (file/folder) bị XÓA.
     */
    private void processDeletedEntity(Path filePath) {
        String relativePath = syncDirectory.relativize(filePath).toString();
        String sqlPath = relativePath.replace("\\", "/");
        
        System.out.println("[Offline Watcher] Đã phát hiện xóa: " + sqlPath);

        try (Connection conn = localDbManager.getConnection()) {
            // Thử tìm trong bảng Files
            String sqlFindFile = "SELECT FileID FROM Files WHERE LocalPath = ? AND SyncStatus != 'LOCAL_DELETED'";
            try (PreparedStatement psFile = conn.prepareStatement(sqlFindFile)) {
                psFile.setString(1, sqlPath);
                ResultSet rs = psFile.executeQuery();
                if (rs.next()) {
                    // Đây là FILE - xử lý xóa file
                    // 1. Cập nhật trạng thái file trong CSDL cục bộ
                    String sqlUpdate = "UPDATE Files SET SyncStatus = 'LOCAL_DELETED' WHERE LocalPath = ?";
                    try (PreparedStatement psUpdate = conn.prepareStatement(sqlUpdate)) {
                        psUpdate.setString(1, sqlPath);
                        psUpdate.executeUpdate();
                    }

                    // 2. Thêm vào hàng đợi (Queue) để xóa trên server khi online
                    String sqlQueue = "INSERT OR REPLACE INTO SyncQueue (Action, LocalPath) VALUES ('DELETE_FILE', ?)";
                    try (PreparedStatement psQueue = conn.prepareStatement(sqlQueue)) {
                        psQueue.setString(1, sqlPath);
                        psQueue.executeUpdate();
                    }
                    return;
                }
            }
            
            // TODO: Thử tìm trong bảng Folders và xử lý DELETE_FOLDER
            // (Cần LocalPath trong bảng Folders để thực hiện điều này)
            
        } catch (Exception e) {
            System.err.println("Lỗi xử lý xóa offline: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Manual sync specific file (ĐÃ ĐÓNG GÓI - SẼ SỬA Ở BƯỚC 5)
     */
    public void syncFile(String relativePath) {
        System.out.println("[TODO Bước 5] syncFile() sẽ được viết lại để đọc từ SyncQueue (CSDL)");
    }
    
    /**
     * Get sync status (ĐÃ ĐÓNG GÓI - SẼ SỬA Ở BƯỚC 5)
     */
    public SyncStatus getStatus() {
        // TODO: Đọc từ SyncQueue table trong CSDL
        return new SyncStatus(isRunning, 0, 0, 0);
    }
    
    // --- THÊM CÁC HÀM MỚI SAU (BƯỚC 5.3) ---
    
    /**
     * (PUBLIC) Kích hoạt việc xử lý hàng đợi (Được gọi bởi MainController khi online).
     */
    public void triggerSyncQueue() {
        // Sử dụng cờ Atomic, nếu đang xử lý rồi thì không chạy thêm
        if (isProcessingQueue.compareAndSet(false, true)) {
            System.out.println("⚡ Kích hoạt xử lý SyncQueue...");
            syncExecutor.submit(this::processSyncQueueInternal);
        } else {
            System.out.println("ℹ️ Đang xử lý SyncQueue, bỏ qua lần kích hoạt này.");
        }
    }

    /**
     * (PRIVATE) Vòng lặp xử lý chính, chạy trên luồng nền.
     */
    private void processSyncQueueInternal() {
        try (Connection conn = localDbManager.getConnection()) {
            while (true) {
                // 1. Kiểm tra xem còn online không
                if (!networkService.isOnline()) {
                    System.out.println("🔌 Mất kết nối, tạm dừng xử lý SyncQueue.");
                    break; 
                }
                
                // 2. Lấy 1 tác vụ từ CSDL Queue
                SyncTask task = getNextTask(conn);
                if (task == null) {
                    // === HÀNG ĐỢI UP-SYNC ĐÃ TRỐNG ===
                    System.out.println("✅ Up-Sync Queue (Upload) đã xử lý xong.");
                    
                    // --- GỌI DOWN-SYNC (BƯỚC 6) ---
                    triggerDownSync();
                    // -----------------------------
                    
                    break; // Hết việc
                }

                // 3. Thực thi tác vụ
                try {
                    System.out.println("⏳ Đang xử lý tác vụ: " + task.action + " cho " + task.localPath);
                    boolean success = false;
                    switch (task.action) {
                        case "CREATE_FOLDER":
                            success = handleCreateFolderTask(task, conn);
                            break;
                        case "UPLOAD":
                            success = handleUploadTask(task);
                            break;
                        case "DELETE_FILE":
                            success = handleDeleteFileTask(task);
                            break;
                        // TODO: Thêm case cho DELETE_FOLDER
                        default:
                            System.out.println("⚠️ Action chưa được hỗ trợ: " + task.action);
                            success = true; // Xóa để tránh loop vô tận
                    }

                    // 4. Xóa tác vụ nếu thành công
                    if (success) {
                        deleteTask(conn, task.queueId);
                    } else {
                        // TODO: Tăng RetryCount
                        System.err.println("❌ Tác vụ thất bại, sẽ retry sau");
                    }

                } catch (Exception e) {
                    System.err.println("Lỗi khi thực thi tác vụ QueueID " + task.queueId + ": " + e.getMessage());
                    // TODO: Tăng RetryCount
                }
                
                // Delay nhỏ giữa các tác vụ
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            isProcessingQueue.set(false); // Đánh dấu đã xử lý xong
        }
    }

    /**
     * Lấy tác vụ tiếp theo từ CSDL Queue.
     */
    private SyncTask getNextTask(Connection conn) throws SQLException {
        // Ưu tiên CREATE_FOLDER trước UPLOAD để đảm bảo folder tồn tại trên server
        // Priority order: CREATE_FOLDER > UPLOAD > DELETE_FILE > DELETE_FOLDER
        String sql = "SELECT * FROM SyncQueue " +
                     "ORDER BY " +
                     "  CASE Action " +
                     "    WHEN 'CREATE_FOLDER' THEN 1 " +
                     "    WHEN 'UPLOAD' THEN 2 " +
                     "    WHEN 'DELETE_FILE' THEN 3 " +
                     "    WHEN 'DELETE_FOLDER' THEN 4 " +
                     "    ELSE 5 " +
                     "  END, " +
                     "  QueueID ASC " +
                     "LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String action = rs.getString("Action");
                
                // Nếu là CREATE_FOLDER, đọc thêm TargetParentID và TargetName
                if ("CREATE_FOLDER".equals(action)) {
                    Integer targetParentID = rs.getObject("TargetParentID", Integer.class);
                    String targetName = rs.getString("TargetName");
                    String localPath = rs.getString("LocalPath"); // Có thể NULL
                    
                    // Debug log
                    System.out.println("🔍 CREATE_FOLDER task: parentID=" + targetParentID + ", name=" + targetName + ", localPath=" + localPath);
                    
                    return new SyncTask(
                            rs.getInt("QueueID"),
                            action,
                            localPath, // Có thể NULL
                            targetParentID,
                            targetName
                    );
                } else {
                    return new SyncTask(
                            rs.getInt("QueueID"),
                            action,
                            rs.getString("LocalPath")
                    );
                }
            }
        }
        return null;
    }

    /**
     * Xóa tác vụ đã hoàn thành khỏi CSDL Queue.
     */
    private void deleteTask(Connection conn, int queueId) throws SQLException {
        String sql = "DELETE FROM SyncQueue WHERE QueueID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, queueId);
            ps.executeUpdate();
        }
    }

    /**
     * Xử lý tác vụ UPLOAD.
     */
    private boolean handleUploadTask(SyncTask task) throws Exception {
        File fileToUpload = syncDirectory.resolve(task.localPath).toFile();
        if (!fileToUpload.exists()) {
            System.err.println("File " + task.localPath + " không tồn tại để upload, có thể đã bị xóa. Xóa tác vụ.");
            return true; // Coi như thành công để xóa tác vụ
        }
        
        int folderId = 1; // Default root
        String lastKnownHash = null; // Hash gốc

        try (Connection conn = localDbManager.getConnection()) {
            // --- Lấy FolderID từ bảng Folders dựa trên đường dẫn folder cha ---
            Path filePath = Paths.get(task.localPath);
            if (filePath.getParent() != null) {
                String parentLocalPath = filePath.getParent().toString().replace("\\", "/");
                
                // Tìm FolderID của folder cha trong bảng Folders
                String sqlFolder = "SELECT FolderID, SyncStatus FROM Folders WHERE LocalPath = ?";
                try (PreparedStatement psFolder = conn.prepareStatement(sqlFolder)) {
                    psFolder.setString(1, parentLocalPath);
                    ResultSet rsFolder = psFolder.executeQuery();
                    if (rsFolder.next()) {
                        int foundFolderId = rsFolder.getInt("FolderID");
                        String syncStatus = rsFolder.getString("SyncStatus");
                        
                        // Kiểm tra xem folder đã được sync chưa
                        if ("SYNCED".equals(syncStatus) && foundFolderId > 0) {
                            folderId = foundFolderId;
                            System.out.println("📂 Tìm thấy FolderID=" + folderId + " cho folder: " + parentLocalPath);
                        } else {
                            System.err.println("⚠️ Folder chưa được sync với server: " + parentLocalPath + " (status=" + syncStatus + ", id=" + foundFolderId + ")");
                            return false; // Retry sau khi folder được sync
                        }
                    } else {
                        System.err.println("⚠️ Không tìm thấy folder trong DB: " + parentLocalPath + ", dùng root");
                    }
                }
            }
            
            // --- Lấy LastKnownHash từ bảng Files (nếu file đã tồn tại) ---
            String sqlFile = "SELECT LastKnownHash FROM Files WHERE LocalPath = ?";
            try (PreparedStatement psFile = conn.prepareStatement(sqlFile)) {
                psFile.setString(1, task.localPath);
                ResultSet rsFile = psFile.executeQuery();
                if (rsFile.next()) {
                    lastKnownHash = rsFile.getString("LastKnownHash");
                }
            }
        }
        // -----------------------------

        // Gọi NetworkService để upload (GỬI KÈM HASH GỐC)
        Response response = networkService.uploadFile(fileToUpload, folderId, lastKnownHash);
        
        // --- BƯỚC 7.3: BẮT LỖI XUNG ĐỘT ---
        if ("error".equals(response.getStatus()) && "CONFLICT".equals(response.getMessage())) {
            System.err.println("🔥 XUNG ĐỘT BỊ PHÁT HIỆN cho file: " + task.localPath);
            handleConflict(task, fileToUpload);
            return true; // Đã xử lý (bằng cách đổi tên), xóa tác vụ
        } 
        // ---------------------------------
        
        else if ("success".equals(response.getStatus())) {
            // Cập nhật lại CSDL cục bộ
            updateFileStatusAfterUpload(task.localPath, response);
            System.out.println("✅ Upload thành công: " + task.localPath);
            return true;
        } else {
            System.err.println("Upload thất bại (lỗi khác): " + response.getMessage());
            return false; // Lỗi khác, thử lại sau
        }
    }

    /**
     * Xử lý tác vụ CREATE_FOLDER.
     */
    private boolean handleCreateFolderTask(SyncTask task, Connection conn) throws Exception {
        if (task.targetParentID == null || task.targetName == null) {
            System.err.println("❌ CREATE_FOLDER task thiếu thông tin: parentID=" + task.targetParentID + ", name=" + task.targetName);
            return true; // Xóa task lỗi
        }
        
        System.out.println("📁 Tạo folder trên server: " + task.targetName + " (parent=" + task.targetParentID + ")");
        
        // Gọi NetworkService để tạo folder trên server
        Response response = networkService.createFolder(task.targetName, task.targetParentID);
        
        if ("success".equals(response.getStatus())) {
            // Lấy FolderID mới từ server
            JsonObject data = response.getData().getAsJsonObject();
            int serverFolderId = data.get("folderId").getAsInt();
            
            // Cập nhật FolderID trong local database
            String sqlUpdate = "UPDATE Folders SET FolderID = ?, SyncStatus = 'SYNCED' WHERE LocalPath = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlUpdate)) {
                ps.setInt(1, serverFolderId);
                ps.setString(2, task.localPath);
                ps.executeUpdate();
            }
            
            System.out.println("✅ Folder đã được tạo trên server với ID=" + serverFolderId);
            return true;
            
        } else if ("error".equals(response.getStatus()) && 
                   response.getMessage() != null && 
                   response.getMessage().contains("đã tồn tại")) {
            
            // Folder đã tồn tại trên server, cần lấy FolderID của nó
            System.out.println("⚠️ Folder đã tồn tại, đang lấy FolderID từ server...");
            
            // Gọi getFolderTree để lấy thông tin folder
            Response treeResponse = networkService.getFolderTree(task.targetParentID);
            if ("success".equals(treeResponse.getStatus())) {
                // Response.data trực tiếp là JsonArray, không phải JsonObject
                JsonArray folders = treeResponse.getData().getAsJsonArray();
                
                // Tìm folder với tên trùng khớp
                for (int i = 0; i < folders.size(); i++) {
                    JsonObject folder = folders.get(i).getAsJsonObject();
                    String folderName = folder.get("folderName").getAsString();
                    if (task.targetName.equals(folderName)) {
                        int existingFolderId = folder.get("folderId").getAsInt();
                        
                        // Cập nhật FolderID trong local database
                        String sqlUpdate = "UPDATE Folders SET FolderID = ?, SyncStatus = 'SYNCED' WHERE LocalPath = ?";
                        try (PreparedStatement ps = conn.prepareStatement(sqlUpdate)) {
                            ps.setInt(1, existingFolderId);
                            ps.setString(2, task.localPath);
                            ps.executeUpdate();
                        }
                        
                        System.out.println("✅ Đã cập nhật FolderID=" + existingFolderId + " cho folder đã tồn tại");
                        return true;
                    }
                }
            }
            
            // Nếu không tìm thấy, coi như thất bại
            System.err.println("❌ Không tìm thấy folder đã tồn tại để lấy ID");
            return false;
            
        } else {
            System.err.println("❌ Tạo folder thất bại: " + response.getMessage());
            return false; // Retry sau
        }
    }
    
    /**
     * Xử lý tác vụ DELETE_FILE.
     */
    private boolean handleDeleteFileTask(SyncTask task) throws Exception {
        // TODO: Gửi yêu cầu DELETE_FILE_BY_PATH (cần handler ở server)
        // Tạm thời return true
        System.out.println("⚠️ DELETE_FILE chưa được triển khai đầy đủ: " + task.localPath);
        
        // Xóa file khỏi CSDL cục bộ
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM Files WHERE LocalPath = ?")) {
            ps.setString(1, task.localPath);
            ps.executeUpdate();
        }
        return true;
    }
    
    /**
     * Cập nhật CSDL cục bộ sau khi upload thành công.
     */
    private void updateFileStatusAfterUpload(String localPath, Response serverResponse) throws SQLException {
        // TODO: Server trả về metadata mới (FileID, Hash, LastModified)
        // Tạm thời chỉ cập nhật SyncStatus
        String sql = "UPDATE Files SET SyncStatus = 'SYNCED' WHERE LocalPath = ?";
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, localPath);
            ps.executeUpdate();
        }
    }

    // --- THÊM CÁC HÀM MỚI CỦA BƯỚC 6 ---
    
    /**
     * Kích hoạt quá trình Down-sync (tải về thay đổi từ server).
     */
    private void triggerDownSync() {
        System.out.println("🔄 Bắt đầu quá trình Down-Sync (tải về)...");
        try {
            // 1. Lấy mốc thời gian cũ
            Timestamp lastSync = localDbManager.getLastSyncTime();
            
            // 2. Gọi API server
            Response response = networkService.getChangesSince(lastSync);
            
            if (response.getStatus().equals("success")) {
                JsonObject responseData = response.getData().getAsJsonObject();
                JsonArray changes = responseData.getAsJsonArray("changes");
                System.out.println("🔄 Nhận được " + changes.size() + " thay đổi từ server.");
                
                // TODO: Tạm dừng FileWatcher để tránh vòng lặp
                // fileWatcher.pause(); 
                
                // 3. Xử lý từng thay đổi
                for (JsonElement change : changes) {
                    JsonObject obj = change.getAsJsonObject();
                    String type = obj.get("type").getAsString();
                    JsonObject data = obj.getAsJsonObject("data");
                    
                    switch (type) {
                        case "FILE_MODIFIED":
                            handleServerFileUpdate(data);
                            break;
                        case "FILE_DELETED":
                            handleServerFileDelete(data);
                            break;
                        // TODO: Thêm case cho FOLDER_MODIFIED, FOLDER_DELETED
                        default:
                            System.out.println("⚠️ Type chưa được hỗ trợ: " + type);
                    }
                }
                
                // 4. Cập nhật mốc thời gian mới
                long serverTime = responseData.get("serverTime").getAsLong();
                localDbManager.setLastSyncTime(new Timestamp(serverTime));
                System.out.println("✅ Down-Sync hoàn tất.");
                
            } else {
                System.err.println("Lỗi Down-Sync: " + response.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Lỗi nghiêm trọng khi Down-Sync: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // TODO: Bật lại FileWatcher
            // fileWatcher.resume();
        }
    }

    /**
     * Xử lý khi server báo 1 file bị SỬA/TẠO MỚI.
     */
    private void handleServerFileUpdate(JsonObject data) throws SQLException {
        int fileId = data.get("FileID").getAsInt();
        String fileName = data.get("FileName").getAsString();
        String fileHash = data.get("FileHash").getAsString();
        
        // TODO: Cần có logic để xác định đường dẫn cục bộ (LocalPath)
        // Bạn cần xây dựng 1 hàm getLocalPath(fileId)
        String localPath = "temp/" + fileName; // Tạm thời
        
        System.out.println("Down-sync: Tải về " + fileName);
        
        // 1. Tải file về (sử dụng DownloadFileHandler)
        boolean downloaded = networkService.downloadFile(fileId, syncDirectory.resolve(localPath));

        if (downloaded) {
            // 2. Cập nhật CSDL SQLite
            String sql = "INSERT OR REPLACE INTO Files (FileID, FolderID, FileName, FileSize, LocalPath, LastKnownHash, SyncStatus) "
                       + "VALUES (?, ?, ?, ?, ?, ?, 'SYNCED')";
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setInt(1, fileId);
                ps.setInt(2, data.get("FolderID").getAsInt());
                ps.setString(3, fileName);
                ps.setLong(4, data.get("FileSize").getAsLong());
                ps.setString(5, localPath);
                ps.setString(6, fileHash);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Xử lý khi server báo 1 file bị XÓA.
     */
    private void handleServerFileDelete(JsonObject data) throws Exception {
        int fileId = data.get("FileID").getAsInt();
        System.out.println("Down-sync: Xóa file ID " + fileId);
        
        // 1. Tìm file trong CSDL cục bộ
        String localPath = null;
        String sqlFind = "SELECT LocalPath FROM Files WHERE FileID = ?";
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlFind)) {
            ps.setInt(1, fileId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                localPath = rs.getString("LocalPath");
            }
        }
        
        // 2. Xóa file vật lý
        if (localPath != null) {
            Files.deleteIfExists(syncDirectory.resolve(localPath));
            
            // 3. Xóa khỏi CSDL cục bộ
            String sqlDelete = "DELETE FROM Files WHERE FileID = ?";
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sqlDelete)) {
                ps.setInt(1, fileId);
                ps.executeUpdate();
            }
        }
    }

    /**
     * BƯỚC 7.4: Xử lý khi phát hiện xung đột upload.
     * Đổi tên file cục bộ và đánh dấu là 'CONFLICT'
     */
    private void handleConflict(SyncTask task, File localFile) {
        try {
            // 1. Tạo tên file xung đột mới
            String originalName = localFile.getName();
            String extension = "";
            String baseName = originalName;
            
            int dotIndex = originalName.lastIndexOf('.');
            if (dotIndex > 0) {
                baseName = originalName.substring(0, dotIndex);
                extension = originalName.substring(dotIndex);
            }

            String conflictedName = String.format("%s (Bản sao xung đột của %s)%s", 
                                        baseName, 
                                        networkService.getCurrentUsername(), 
                                        extension).trim();
            
            Path conflictedPath = localFile.toPath().resolveSibling(conflictedName);

            // 2. Đổi tên file cục bộ
            Files.move(localFile.toPath(), conflictedPath);
            System.out.println("✏️ Đã đổi tên file xung đột thành: " + conflictedName);

            // 3. Cập nhật CSDL cục bộ:
            // Đổi đường dẫn của file cũ thành đường dẫn mới và đánh dấu là CONFLICT
            String newSqlPath = syncDirectory.relativize(conflictedPath).toString().replace("\\", "/");
            String sqlUpdate = "UPDATE Files SET LocalPath = ?, FileName = ?, SyncStatus = 'CONFLICT' "
                             + "WHERE LocalPath = ?";
            
            try (Connection conn = localDbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sqlUpdate)) {
                
                ps.setString(1, newSqlPath);
                ps.setString(2, conflictedName);
                ps.setString(3, task.localPath); // Đường dẫn cũ
                ps.executeUpdate();
            }

            // 4. Thông báo cho người dùng
            notificationManager.addNotification(
                "⚠️ Xung đột: File '" + originalName + "' của bạn đã được đổi tên thành '" + conflictedName + "'. " +
                "Phiên bản mới nhất từ server sẽ được tải về.",
                com.pbl4.syncproject.client.models.NotificationItem.NotificationType.SYSTEM
            );
            
            // 5. Để Down-sync (Bước 6) xử lý
            // Sau khi hàm này kết thúc, SyncQueue (up-sync) sẽ tiếp tục.
            // Khi nó hoàn tất, Down-sync (triggerDownSync) sẽ tự động chạy.
            // Down-sync sẽ thấy file 'bao_cao.docx' bị thay đổi (FILE_MODIFIED)
            // và gọi handleServerFileUpdate(), tải file mới của server về 
            // vào đúng vị trí 'task.localPath' (vì file cũ đã bị đổi tên).

        } catch (Exception e) {
            System.err.println("Lỗi nghiêm trọng khi xử lý xung đột: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Class nội bộ để chứa thông tin tác vụ
    private static class SyncTask {
        int queueId;
        String action;
        String localPath;
        Integer targetParentID;  // Dùng cho CREATE_FOLDER
        String targetName;       // Dùng cho CREATE_FOLDER
        
        public SyncTask(int queueId, String action, String localPath) {
            this.queueId = queueId;
            this.action = action;
            this.localPath = localPath;
        }
        
        public SyncTask(int queueId, String action, String localPath, Integer targetParentID, String targetName) {
            this.queueId = queueId;
            this.action = action;
            this.localPath = localPath;
            this.targetParentID = targetParentID;
            this.targetName = targetName;
        }
    }
    
    // Inner classes
    
    // --- GIỮ LẠI ENUM SyncOperation ---
    public enum SyncOperation {
        UPLOAD, DELETE, DOWNLOAD
    }
    
    // --- GIỮ LẠI SyncStatus ---
    public static class SyncStatus {
        public final boolean isRunning;
        public final int queueSize;
        public final long processedCount;
        public final int trackedFiles;
        
        public SyncStatus(boolean isRunning, int queueSize, long processedCount, int trackedFiles) {
            this.isRunning = isRunning;
            this.queueSize = queueSize;
            this.processedCount = processedCount;
            this.trackedFiles = trackedFiles;
        }
        
        @Override
        public String toString() {
            return String.format("SyncStatus{running=%s, queue=%d, processed=%d, tracked=%d}", 
                               isRunning, queueSize, processedCount, trackedFiles);
        }
    }
    
    // --- XÓA HOÀN TOÀN CÁC LỚP SAU ---
    // public static class SyncTask { ... } // <-- ĐÃ XÓA
    // private class SyncQueue { ... } // <-- ĐÃ XÓA HOÀN TOÀN CLASS NÀY
    // ------------------------------------------
}