package com.pbl4.syncproject.client.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.common.model.Folders;
import com.pbl4.syncproject.common.jsonhandler.Response;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    public FileService(NetworkService networkService) {
        this.networkService = networkService;
    }

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
     */
    public ObservableList<FileItem> fetchAndParseFileList(int folderId) throws Exception {
        // Get file list from server for specific folder
        Response response = networkService.getFileList(folderId);

        if (response != null && "success".equals(response.getStatus())) {
            return parseFileListResponse(response);
        }

        throw new Exception("Server không trả về dữ liệu hợp lệ cho folder ID: " + folderId);
    }

    /**
     * Fetch and parse folder tree từ server
     * Nếu server không có root folder (parentId=0), sẽ fallback về default folders
     */
    public List<Folders> fetchAndParseFolderTree() throws Exception {
        Response response = networkService.getFolderTree();

        if (response != null && "success".equals(response.getStatus())) {
            List<Folders> folders = parseFoldersFromResponse(response);
            if (folders != null && !folders.isEmpty()) {
                return folders;
            }
        }

        // Server không có folder tree hoặc trả về error
        // Throw exception để MainView fallback về setupDefaultFolders
        String errorMsg = response != null ? response.getMessage() : "No response from server";
        throw new Exception("Server folder tree unavailable: " + errorMsg);
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

            return new Folders(id, name, parentId, createdAt, lastModified);

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

            return new FileItem(displayName, size, fileType, lastModified, permission, syncStatus, folderName);

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
     * Get folder ID từ directory path
     */
    public static int getFolderIdFromDirectory(String directory) {
        if (directory == null) return 1; // Default to shared

        String folderName = directory.replace("📁 ", "").replace("/", "").trim();

        switch (folderName.toLowerCase()) {
            case "shared":
                return 1;
            case "documents":
                return 2;
            case "images":
                return 3;
            case "videos":
                return 4;
            default:
                return 1; // Default to shared folder
        }
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
}