package com.pbl4.syncproject.client.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

// --- THÊM CÁC IMPORT MỚI ---
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
// -----------------------------

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service để xử lý file-related operations:
 * - Parsing JSON responses từ server thành FileItem objects
 * - File display utilities (icons, types, formatting)
 * - Data transformation logic
 */
public class FileService {

    private final NetworkService networkService;
    
    // --- THÊM BIẾN MỚI ---
    private final LocalDatabaseManager localDbManager;
    // -----------------------

    // --- SỬA LẠI CONSTRUCTOR ---
    public FileService(NetworkService networkService, LocalDatabaseManager localDbManager) {
        this.networkService = networkService;
        this.localDbManager = localDbManager;
    }
    // ---------------------------

    /**
     * Fetch và parse file list từ server (all files)
     */
    public ObservableList<FileItem> fetchAndParseFileList() throws Exception {
        // Test connection first
        if (!networkService.testConnection()) {
            throw new Exception("Không thể kết nối tới server - vui lòng khởi động ServerApp");
        }

        // Get file list from server
        Response response = networkService.getFileList();

        if (response != null && "success".equals(response.getStatus())) {
            return parseFileListResponse(response);
        }

        throw new Exception("Server không trả về dữ liệu hợp lệ");
    }

    /**
     * Fetch và parse file list từ server theo folder ID cụ thể
     * (ĐÃ SỬA ĐỔI ĐỂ HỢP NHẤT LOCAL VÀ SERVER - MANUAL SYNC MODE)
     */
    public ObservableList<FileItem> fetchAndParseFileList(int folderId) throws Exception {
        
        // Danh sách file cuối cùng để hiển thị
        ObservableList<FileItem> finalItems = FXCollections.observableArrayList();
        // Map để theo dõi file server (Key = Tên file)
        java.util.Map<String, FileItem> serverFileMap = new java.util.HashMap<>();

        // 1. LẤY FILE TỪ SERVER (NẾU ONLINE)
        if (networkService.isOnline()) {
            try {
                Response response = networkService.getFileList(folderId);
                if (response != null && "success".equals(response.getStatus())) {
                    ObservableList<FileItem> serverItems = parseFileListResponse(response);
                    for (FileItem item : serverItems) {
                        // Lấy tên file gốc (không icon) làm key
                        String originalName = item.getFileName();
                        if (originalName.contains(" ")) {
                            originalName = originalName.substring(originalName.indexOf(" ") + 1).trim();
                        }
                        serverFileMap.put(originalName, item);
                        finalItems.add(item); // Thêm file server vào danh sách
                    }
                    System.out.println("[Online] Đã tải " + serverItems.size() + " file từ server.");
                } else {
                    throw new Exception("Server không trả về dữ liệu hợp lệ cho folder ID: " + folderId);
                }
            } catch (Exception e) {
                System.err.println("Lỗi khi gọi server (sẽ chỉ hiển thị cache): " + e.getMessage());
                // Không ném lỗi, tiếp tục để tải cache
            }
        }

        // 2. LẤY FILE TỪ CACHE CỤC BỘ (LOCAL_NEW, LOCAL_STALE)
        int localFolderId = getLocalFolderIdFromServerId(folderId);
        
        // Chỉ lấy file Mới hoặc file Sửa
        String sql = "SELECT * FROM Files WHERE FolderID = ? AND (SyncStatus = ? OR SyncStatus = ?)";

        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, localFolderId);
            ps.setString(2, LocalDatabaseManager.STATUS_LOCAL_NEW);
            ps.setString(3, LocalDatabaseManager.STATUS_LOCAL_STALE);
            ResultSet rs = ps.executeQuery();
            
            while (rs.next()) {
                String fileName = rs.getString("FileName");
                String syncStatus = rs.getString("SyncStatus");
                
                // Nếu file này cũng có trên server (trường hợp LOCAL_STALE)
                if (serverFileMap.containsKey(fileName)) {
                    // File này đã có trong finalItems (từ server), 
                    // chúng ta chỉ cần tìm và cập nhật trạng thái của nó
                    for (FileItem item : finalItems) {
                        String originalName = item.getFileName();
                        if (originalName.contains(" ")) {
                            originalName = originalName.substring(originalName.indexOf(" ") + 1).trim();
                        }
                        if (originalName.equals(fileName)) {
                            item.setSyncStatus(syncStatus); // Cập nhật thành "LOCAL_STALE" (Vàng)
                            item.setRelativePath(rs.getString("LocalPath")); // Quan trọng cho upload
                            break;
                        }
                    }
                } else {
                    // File này không có trên server (trường hợp LOCAL_NEW)
                    // Tạo FileItem mới
                    String displayName = getFileIcon(fileName) + " " + fileName;
                    String fileSize = formatFileSize(rs.getLong("FileSize"));
                    String fileType = getFileType(fileName);

                    FileItem item = new FileItem(
                            displayName, fileSize, fileType,
                            "N/A (Local)", "Đọc/Ghi", syncStatus // Trạng thái "LOCAL_NEW" (Xanh)
                    );
                    item.setFileId(rs.getInt("FileID")); // ID Cục bộ
                    item.setFolderId(localFolderId); // ID Cục bộ
                    item.setRelativePath(rs.getString("LocalPath")); // Quan trọng để upload
                    finalItems.add(item); // Thêm file local vào danh sách
                }
            }
        } catch (SQLException e) {
            System.err.println("Lỗi đọc file cache SQLite: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[FileService] Tổng cộng " + finalItems.size() + " file (bao gồm local & server)");
        return finalItems;
    }
    
    /**
     * Lấy Local FolderID (PK) từ ServerFolderID (UNIQUE)
     */
    private int getLocalFolderIdFromServerId(int serverFolderId) {
        // Tạm thời coi 2 ID là một, nhưng lý tưởng là phải truy vấn
        // SELECT FolderID FROM Folders WHERE ServerFolderID = ?
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
            ps.setInt(1, serverFolderId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt("FolderID");
            }
        } catch (SQLException e) {
            System.err.println("Lỗi lấy LocalFolderID: " + e.getMessage());
        }
        // Fallback: Giả định serverFolderId 1 = localFolderId 1 (root)
        return serverFolderId;
    }

    /**
     * Fetch and parse folder tree từ server với parentId cụ thể (cho lazy loading)
     * @param parentId ID của thư mục cha (0 = lấy thư mục gốc)
     */
    public List<Folders> fetchAndParseFolderTree(int parentId) throws Exception {
        
        // --- SỬA LẠI: HỖ TRỢ OFFLINE ---
        if (networkService.isOnline()) {
            // ONLINE: Lấy từ server và cập nhật cache
            try {
                Response response = networkService.getFolderTree(parentId);
                if (response != null && "success".equals(response.getStatus())) {
                    List<Folders> folders = parseFoldersFromResponse(response);
                    
                    // Cập nhật cache
                    saveFoldersToCache(parentId, folders);
                    System.out.println("[Online] Đã tải " + folders.size() + " thư mục từ server.");

                    return folders;
                }
                String errorMsg = response != null ? response.getMessage() : "Không có phản hồi từ server";
                throw new Exception("Không có cây thư mục từ server: " + errorMsg);
                
            } catch (Exception e) {
                // Nếu gọi server lỗi, thử đọc từ cache
                System.err.println("Lỗi khi gọi server, thử đọc thư mục từ cache: " + e.getMessage());
                return getFoldersFromCache(parentId);
            }
        } else {
            // OFFLINE: Đọc thẳng từ cache
            System.out.println("[Offline] Đang đọc thư mục từ cache cho parent " + parentId);
            return getFoldersFromCache(parentId);
        }
        // ---------------------------------
    }

    /**
     * Fetch and parse folder tree từ server (để tương thích ngược)
     * Nếu server không có root folder (parentId=0), sẽ fallback về default folders
     */
    public List<Folders> fetchAndParseFolderTree() throws Exception {
        return fetchAndParseFolderTree(0); // Mặc định lấy thư mục gốc
    }

    /**
     * Parse folders từ server response
     */
    public List<Folders> parseFoldersFromResponse(Response response) {
        List<Folders> folders = new ArrayList<>();

        try {
            JsonElement datum = response.getData();
            if (datum == null) return folders;

            if (datum.isJsonArray()) {
                // FOLDER_TREE handler may return a raw array
                JsonArray foldersArray = datum.getAsJsonArray();
                for (int i = 0; i < foldersArray.size(); i++) {
                    JsonObject folder = foldersArray.get(i).getAsJsonObject();
                    Folders folderObj = createFolderFromJson(folder);
                    if (folderObj != null) folders.add(folderObj);
                }
            } else if (datum.isJsonObject()) {
                JsonObject data = datum.getAsJsonObject();
                if (data.has("folders")) {
                    JsonArray foldersArray = data.getAsJsonArray("folders");
                    for (int i = 0; i < foldersArray.size(); i++) {
                        JsonObject folder = foldersArray.get(i).getAsJsonObject();
                        Folders folderObj = createFolderFromJson(folder);
                        if (folderObj != null) folders.add(folderObj);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Error parsing folders from response: " + e.getMessage());
            e.printStackTrace();
        }

        return folders;
    }

    /**
     * Tạo Folders object từ JsonObject
     */
    private Folders createFolderFromJson(JsonObject json) {
        try {
            int id = json.has("folderId") ? json.get("folderId").getAsInt() : json.get("id").getAsInt();
            String name = json.has("folderName") ? json.get("folderName").getAsString() : json.get("name").getAsString();
            Integer parentId = json.has("parentFolderId") ? json.get("parentFolderId").getAsInt() : null;

            LocalDateTime createdAt = null;
            LocalDateTime lastModified = null;

            // Parse timestamps if available
            if (json.has("createdAt")) {
                try {
                    createdAt = LocalDateTime.parse(json.get("createdAt").getAsString());
                } catch (Exception e) {
                    // Ignore parsing errors
                }
            }

            if (json.has("lastModified")) {
                try {
                    lastModified = LocalDateTime.parse(json.get("lastModified").getAsString());
                } catch (Exception e) {
                    // Ignore parsing errors
                }
            }

            Folders folder = new Folders(id, name, parentId, createdAt, lastModified);
            
            // Parse hasChildren nếu có
            if (json.has("hasChildren")) {
                boolean hasChildren = json.get("hasChildren").getAsBoolean();
                folder.setHasChildren(hasChildren);
                System.out.println("🔍 Parsed folder: " + name + " | hasChildren: " + hasChildren);
            } else {
                System.out.println("⚠️ Folder " + name + " không có field 'hasChildren' trong JSON!");
            }

            return folder;

        } catch (Exception e) {
            System.err.println("Error creating Folders from JSON: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parse response từ server thành FileItem objects
     */
    public ObservableList<FileItem> parseFileListResponse(Response response) {
        ObservableList<FileItem> items = FXCollections.observableArrayList();

        try {
            JsonObject data = response.getData().getAsJsonObject();
            if (data == null) return items;

            // Parse folders - chỉ để build tree structure, không add vào file list
            if (data.has("folders")) {
                // Folders sẽ được xử lý bởi MainView để build tree structure
                // Không add vào items list vì user không muốn thấy folder trong file list
            }

            // Parse files - đây là những gì user muốn thấy trong file list  
            if (data.has("files")) {
                JsonArray files = data.getAsJsonArray("files");
                for (int i = 0; i < files.size(); i++) {
                    JsonObject file = files.get(i).getAsJsonObject();
                    FileItem fileItem = createFileItemFromJson(file, false); // false = not folder
                    if (fileItem != null) {
                        items.add(fileItem);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Error parsing file list response: " + e.getMessage());
        }

        return items;
    }

    /**
     * Tạo FileItem từ JsonObject (data từ database)
     */
    public FileItem createFileItemFromJson(JsonObject json, boolean isFolder) {
        try {
            String name = json.get("name").getAsString();
            String size = json.has("size") ? json.get("size").getAsString() : "";
            String fileType = isFolder ? "Folder" :
                    (json.has("fileType") ? json.get("fileType").getAsString() : "File");
            String lastModified = json.has("lastModified") ? json.get("lastModified").getAsString() : "";
            String permission = json.has("permission") ? json.get("permission").getAsString() : "Đọc/Ghi";
            String syncStatus = json.has("syncStatus") ? json.get("syncStatus").getAsString() : "✅ Đã đồng bộ";

            // Get folder name - for files, it comes from database query
            // For folders, the name IS the folder name
            String folderName;
            if (isFolder) {
                folderName = name; // For folders, name = folder name
            } else {
                // For files, determine folder based on database relationship
                // Default to "shared" if not specified
                folderName = json.has("folderName") ? json.get("folderName").getAsString() : "shared";
            }

            // Add appropriate icon
            String icon = isFolder ? "📁" : getFileIcon(name);
            String displayName = icon + " " + name;

            FileItem fileItem = new FileItem(displayName, size, fileType, lastModified, permission, syncStatus, folderName);
            
            // Set fileId và folderId từ JSON
            if (json.has("fileId")) {
                fileItem.setFileId(json.get("fileId").getAsInt());
            } else if (json.has("id")) {
                fileItem.setFileId(json.get("id").getAsInt());
            }
            
            if (json.has("folderId")) {
                fileItem.setFolderId(json.get("folderId").getAsInt());
            }

            // --- BƯỚC 6.2: Đọc relativePath nếu server đã trả về ---
            if (json.has("relativePath")) {
                try {
                    fileItem.setRelativePath(json.get("relativePath").getAsString());
                } catch (Exception ignore) {}
            }
            
            return fileItem;

        } catch (Exception e) {
            System.err.println("Error creating FileItem from JSON: " + e.getMessage());
            return null;
        }
    }

    /**
     * Filter files theo folder name
     */
    public ObservableList<FileItem> filterFilesByFolder(ObservableList<FileItem> allFiles, String folderName) {
        ObservableList<FileItem> filteredFiles = FXCollections.observableArrayList();

        for (FileItem item : allFiles) {
            if (item.getFolderName() != null && item.getFolderName().equals(folderName)) {
                filteredFiles.add(item);
            }
        }

        return filteredFiles;
    }

    // =================================================================
    // FILE DISPLAY UTILITIES
    // =================================================================

    /**
     * Get file icon dựa trên extension
     */
    public static String getFileIcon(String fileName) {
        if (fileName.contains(".")) {
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            switch (extension) {
                case "doc":
                case "docx":
                case "txt":
                    return "📄";
                case "xls":
                case "xlsx":
                    return "📊";
                case "png":
                case "jpg":
                case "jpeg":
                case "gif":
                    return "🖼️";
                case "mp4":
                case "avi":
                case "mkv":
                    return "🎥";
                case "pdf":
                    return "📕";
                default:
                    return "📄";
            }
        }
        return "📄";
    }

    /**
     * Get file type dựa trên extension
     */
    public static String getFileType(String fileName) {
        if (fileName.contains(".")) {
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            switch (extension) {
                case "doc":
                case "docx":
                    return "Document";
                case "xls":
                case "xlsx":
                    return "Spreadsheet";
                case "png":
                case "jpg":
                case "jpeg":
                case "gif":
                    return "Image";
                case "mp4":
                case "avi":
                case "mkv":
                    return "Video";
                case "pdf":
                    return "PDF";
                case "txt":
                    return "Text";
                default:
                    return "File";
            }
        }
        return "File";
    }

    /**
     * Format file size in human readable format
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Validate file trước khi upload
     */
    public static ValidationResult validateFileForUpload(java.io.File file) {
        // Check if file exists
        if (!file.exists()) {
            return new ValidationResult(false, "File không tồn tại!");
        }

        // Check if it's a file (not directory)
        if (!file.isFile()) {
            return new ValidationResult(false, "Chỉ có thể tải lên file, không thể tải lên thư mục!");
        }

        // Check file size (max 100MB)
        long maxSizeBytes = 100 * 1024 * 1024; // 100MB
        if (file.length() > maxSizeBytes) {
            return new ValidationResult(false,
                    "File quá lớn! Kích thước tối đa cho phép: 100MB\n" +
                            "Kích thước file hiện tại: " + formatFileSize(file.length()));
        }

        // Check filename validity
        String fileName = file.getName();
        if (fileName.trim().isEmpty()) {
            return new ValidationResult(false, "Tên file không hợp lệ!");
        }

        // Check for invalid characters in filename
        String invalidChars = "<>:\"/\\\\|?*";
        for (char c : invalidChars.toCharArray()) {
            if (fileName.indexOf(c) >= 0) {
                return new ValidationResult(false,
                        "Tên file chứa ký tự không hợp lệ: " + c + "\n" +
                                "Các ký tự không được phép: " + invalidChars);
            }
        }

        // Check file extension (security)
        String[] blockedExtensions = {".exe", ".bat", ".cmd", ".com", ".scr", ".pif", ".vbs", ".js"};
        String lowerFileName = fileName.toLowerCase();
        for (String ext : blockedExtensions) {
            if (lowerFileName.endsWith(ext)) {
                return new ValidationResult(false,
                        "Loại file này không được phép tải lên vì lý do bảo mật: " + ext);
            }
        }

        return new ValidationResult(true, "File hợp lệ");
    }

    /**
     * Result của file validation
     */
    public static class ValidationResult {
        public final boolean isValid;
        public final String message;

        public ValidationResult(boolean isValid, String message) {
            this.isValid = isValid;
            this.message = message;
        }
    }

    /**
     * Delete file by sending DELETE_FILE request to server
     * @param fileId ID của file cần xóa
     * @param folderId ID của thư mục chứa file
     * @param fileName Tên file cần xóa
     * @return Response từ server
     */
    public Response deleteFile(Integer fileId, Integer folderId, String fileName) throws Exception {
        JsonObject data = new JsonObject();
        
        if (fileId != null && fileId > 0) {
            data.addProperty("fileId", fileId);
        }
        if (folderId != null && folderId > 0) {
            data.addProperty("folderId", folderId);
        }
        if (fileName != null && !fileName.trim().isEmpty()) {
            data.addProperty("fileName", fileName);
        }
        
        // LỖI SỬA: Thêm username để server xác định người dùng
        if (networkService.getCurrentUsername() != null) {
            data.addProperty("username", networkService.getCurrentUsername());
        }
        
        com.pbl4.syncproject.common.jsonhandler.Request request = 
            new com.pbl4.syncproject.common.jsonhandler.Request("DELETE_FILE", data);
        
        return networkService.sendRequest(request);
    }

    // =================================================================
    // LOCAL CACHE (SQLITE) METHODS
    // =================================================================

    /**
     * Lấy danh sách Files từ CSDL SQLite cục bộ (khi OFFLINE).
     */
    private ObservableList<FileItem> getFilesFromCache(int folderId) {
        ObservableList<FileItem> items = FXCollections.observableArrayList();
        String sql = "SELECT * FROM Files WHERE FolderID = ? AND SyncStatus != 'LOCAL_DELETED'";

        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, folderId);
            ResultSet rs = ps.executeQuery();
            
            while (rs.next()) {
                // Tạo FileItem từ dữ liệu SQLite
                String fileName = rs.getString("FileName");
                String displayName = getFileIcon(fileName) + " " + fileName;
                String fileSize = formatFileSize(rs.getLong("FileSize"));
                String fileType = getFileType(fileName);
                String syncStatus = rs.getString("SyncStatus"); // Lấy trạng thái từ cache

                FileItem item = new FileItem(
                        displayName,
                        fileSize,
                        fileType,
                        "N/A (Offline)", // Không có LastModified trong cache, có thể thêm sau
                        "N/A",
                        "CACHE: " + syncStatus // Hiển thị trạng thái cache
                );
                item.setFileId(rs.getInt("FileID"));
                item.setFolderId(rs.getInt("FolderID"));
                items.add(item);
            }
        } catch (SQLException e) {
            System.err.println("Lỗi đọc file cache SQLite: " + e.getMessage());
        }
        return items;
    }

    /**
     * Lưu danh sách Files (từ server) vào CSDL SQLite cục bộ (khi ONLINE).
     */
    private void saveFilesToCache(int folderId, ObservableList<FileItem> items) {
        String sqlDelete = "DELETE FROM Files WHERE FolderID = ?";
        String sqlInsert = "INSERT INTO Files (FileID, FolderID, FileName, FileSize, LocalPath, LastKnownHash, SyncStatus) "
                         + "VALUES (?, ?, ?, ?, ?, ?, 'SYNCED')";

        try (Connection conn = localDbManager.getConnection()) {
            conn.setAutoCommit(false);

            // 1. Xóa tất cả file cũ của thư mục này trong cache
            try (PreparedStatement psDelete = conn.prepareStatement(sqlDelete)) {
                psDelete.setInt(1, folderId);
                psDelete.executeUpdate();
            }

            // 2. Thêm file mới
            try (PreparedStatement psInsert = conn.prepareStatement(sqlInsert)) {
                for (FileItem item : items) {
                    psInsert.setInt(1, item.getFileId());
                    psInsert.setInt(2, item.getFolderId());
                    // Lấy tên file gốc (bỏ icon)
                    String originalName = item.getFileName().substring(item.getFileName().indexOf(" ") + 1);
                    psInsert.setString(3, originalName);
                    
                    // Cần parse lại Long từ String "1.2 MB" (Tạm thời để 0)
                    // TODO: Sửa lại logic parse size
                    psInsert.setLong(4, 0L); 
                    
                    // TODO: Cần có đường dẫn file cục bộ thực tế
                    psInsert.setString(5, "path/to/" + originalName); 
                    
                    // TODO: Cần lấy Hash thực tế từ server
                    psInsert.setString(6, "temp_hash"); 
                    
                    psInsert.addBatch();
                }
                psInsert.executeBatch();
            }
            
            conn.commit();
        } catch (SQLException e) {
            System.err.println("Lỗi lưu file cache SQLite: " + e.getMessage());
        }
    }

    /**
     * Lấy danh sách Folders từ CSDL SQLite cục bộ (khi OFFLINE).
     */
    private List<Folders> getFoldersFromCache(int parentId) {
        List<Folders> folders = new ArrayList<>();
        // Nếu parentId = 0, lấy root (ParentFolderID IS NULL)
        String sql = (parentId == 0)
                ? "SELECT * FROM Folders WHERE ParentFolderID IS NULL AND SyncStatus != 'LOCAL_DELETED'"
                : "SELECT * FROM Folders WHERE ParentFolderID = ? AND SyncStatus != 'LOCAL_DELETED'";

        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            if (parentId != 0) {
                ps.setInt(1, parentId);
            }
            
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Folders folder = new Folders();
                folder.setFolderId(rs.getInt("FolderID"));
                folder.setParentId(rs.getInt("ParentFolderID"));
                folder.setFolderName(rs.getString("FolderName"));
                // TODO: Cần query `hasChildren` cho chế độ offline
                folder.setHasChildren(false); 
                folders.add(folder);
            }
        } catch (SQLException e) {
            System.err.println("Lỗi đọc folder cache SQLite: " + e.getMessage());
        }
        return folders;
    }

    /**
     * Lưu danh sách Folders (từ server) vào CSDL SQLite cục bộ (khi ONLINE).
     */
    private void saveFoldersToCache(int parentId, List<Folders> folders) {
        // Logic này phức tạp hơn, cần UPSERT (INSERT OR REPLACE)
        String sqlUpsert = "INSERT OR REPLACE INTO Folders (FolderID, ParentFolderID, FolderName, SyncStatus) "
                         + "VALUES (?, ?, ?, 'SYNCED')";
        
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlUpsert)) {
            
            conn.setAutoCommit(false);
            for (Folders folder : folders) {
                ps.setInt(1, folder.getFolderId());
                if (folder.getParentId() != null) {
                    ps.setInt(2, folder.getParentId());
                } else {
                    ps.setNull(2, java.sql.Types.INTEGER);
                }
                ps.setString(3, folder.getFolderName());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();

        } catch (SQLException e) {
            System.err.println("Lỗi lưu folder cache SQLite: " + e.getMessage());
        }
    }
}