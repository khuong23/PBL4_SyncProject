package com.pbl4.syncproject.client.services;

import com.google.gson.*;
import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FileService (revised)
 * - Gọi NetworkService.sendRequest(...) để lấy danh sách file / cây thư mục
 * - Không dùng testConnection(), getFileList(), getFolderTree() trên NetworkService
 * - Parser “chịu đựng” khác biệt key / cấu trúc JSON
 * - Giữ nguyên validate & display utils
 */
public class FileService {

    // ======= Điều chỉnh nếu server dùng action/key khác =======
    private static final String ACTION_LIST_FILES  = "GET_FILE_LIST";
    private static final String ACTION_FOLDER_TREE = "FOLDER_TREE";

    private static final String KEY_FOLDER_ID     = "folderId";
    private static final String KEY_FILES         = "files";
    private static final String KEY_FOLDERS       = "folders";

    // Một số alias/field phổ biến
    private static final String KEY_ID            = "id";
    private static final String KEY_FOLDER_ID_ALT = "folderId";
    private static final String KEY_PARENT_ID     = "parentFolderId";
    private static final String KEY_NAME          = "name";
    private static final String KEY_NAME_ALT      = "folderName";
    private static final String KEY_SIZE          = "size";
    private static final String KEY_TYPE          = "fileType";
    private static final String KEY_LAST_MODIFIED = "lastModified";
    private static final String KEY_CREATED_AT    = "createdAt";
    private static final String KEY_PERMISSION    = "permission";
    private static final String KEY_SYNC_STATUS   = "syncStatus";
    private static final String KEY_FOLDER_NAME   = "folderName"; // tên thư mục chứa (khi parse file)

    private final NetworkService networkService;

    public FileService(NetworkService networkService) {
        this.networkService = networkService;
    }

    // ==========================================================
    // FETCH APIs
    // ==========================================================

    /** Lấy & parse toàn bộ danh sách file (server quyết định phạm vi) */
    public ObservableList<FileItem> fetchAndParseFileList() throws Exception {
        JsonObject data = new JsonObject(); // không tham số
        Response response = networkService.sendRequest(new Request(ACTION_LIST_FILES, data));
        ensureSuccess(response, "Danh sách tệp (all)");
        return parseFileListResponse(response);
    }

    /** Lấy & parse danh sách file theo folderId */
    public ObservableList<FileItem> fetchAndParseFileList(int folderId) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty(KEY_FOLDER_ID, folderId);
        Response response = networkService.sendRequest(new Request(ACTION_LIST_FILES, data));
        ensureSuccess(response, "Danh sách tệp cho folderId=" + folderId);
        return parseFileListResponse(response);
    }

    /** Lấy & parse cây thư mục */
    public List<Folders> fetchAndParseFolderTree() throws Exception {
        Response response = networkService.sendRequest(new Request(ACTION_FOLDER_TREE, null));
        ensureSuccess(response, "Cây thư mục");

        List<Folders> folders = parseFoldersFromResponse(response);
        if (folders == null || folders.isEmpty()) {
            throw new Exception("Cây thư mục rỗng hoặc không hợp lệ từ server.");
        }
        return folders;
    }

    // ==========================================================
    // PARSERS
    // ==========================================================

    /** Đảm bảo status=success; nếu không ném exception + message rõ */
    private static void ensureSuccess(Response resp, String ctx) throws Exception {
        if (resp == null) throw new Exception(ctx + " - không có phản hồi từ server");
        if (!"success".equalsIgnoreCase(resp.getStatus())) {
            String msg = resp.getMessage() != null ? resp.getMessage() : "unknown error";
            throw new Exception(ctx + " - server trả về lỗi: " + msg);
        }
    }

    /** Parse folders từ Response (chịu được array/object) */
    public List<Folders> parseFoldersFromResponse(Response response) {
        List<Folders> out = new ArrayList<>();
        try {
            JsonElement datum = response.getData();
            if (datum == null || datum.isJsonNull()) return out;

            if (datum.isJsonArray()) {
                JsonArray arr = datum.getAsJsonArray();
                for (JsonElement el : arr) {
                    if (el != null && el.isJsonObject()) {
                        Folders f = createFolderFromJson(el.getAsJsonObject());
                        if (f != null) out.add(f);
                    }
                }
            } else if (datum.isJsonObject()) {
                JsonObject data = datum.getAsJsonObject();
                if (data.has(KEY_FOLDERS) && data.get(KEY_FOLDERS).isJsonArray()) {
                    JsonArray arr = data.getAsJsonArray(KEY_FOLDERS);
                    for (JsonElement el : arr) {
                        if (el != null && el.isJsonObject()) {
                            Folders f = createFolderFromJson(el.getAsJsonObject());
                            if (f != null) out.add(f);
                        }
                    }
                } else {
                    // fallback: data có thể là 1 folder object
                    Folders f = createFolderFromJson(data);
                    if (f != null) out.add(f);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing folders: " + e.getMessage());
            e.printStackTrace();
        }
        return out;
    }

    /** Tạo Folders từ JSON (chịu alias key) */
    private Folders createFolderFromJson(JsonObject json) {
        if (json == null || json.isJsonNull()) return null;
        try {
            int id = getInt(json, KEY_FOLDER_ID_ALT, -1);
            if (id < 0) id = getInt(json, KEY_ID, -1);

            String name = getString(json, KEY_NAME_ALT, null);
            if (name == null) name = getString(json, KEY_NAME, null);

            Integer parentId = json.has(KEY_PARENT_ID) && json.get(KEY_PARENT_ID).isJsonPrimitive()
                    ? json.get(KEY_PARENT_ID).getAsInt()
                    : null;

            LocalDateTime createdAt   = parseDateSafe(getString(json, KEY_CREATED_AT, null));
            LocalDateTime lastModified= parseDateSafe(getString(json, KEY_LAST_MODIFIED, null));

            if (id <= 0 || name == null) return null;
            return new Folders(id, name, parentId, createdAt, lastModified);

        } catch (Exception e) {
            System.err.println("Error creating Folders from JSON: " + e.getMessage());
            return null;
        }
    }

    /** Parse danh sách FileItem từ Response (chỉ file, không folder) */
    public ObservableList<FileItem> parseFileListResponse(Response response) {
        ObservableList<FileItem> items = FXCollections.observableArrayList();
        try {
            JsonElement datum = response.getData();
            if (datum == null || datum.isJsonNull()) return items;

            if (datum.isJsonObject()) {
                JsonObject data = datum.getAsJsonObject();

                if (data.has(KEY_FILES) && data.get(KEY_FILES).isJsonArray()) {
                    JsonArray files = data.getAsJsonArray(KEY_FILES);
                    for (JsonElement el : files) {
                        if (el != null && el.isJsonObject()) {
                            FileItem fi = createFileItemFromJson(el.getAsJsonObject(), false);
                            if (fi != null) items.add(fi);
                        }
                    }
                } else if (data.entrySet().size() > 0) {
                    // fallback: 1 file object
                    FileItem one = createFileItemFromJson(data, false);
                    if (one != null) items.add(one);
                }
            } else if (datum.isJsonArray()) {
                // fallback: mảng file trực tiếp
                JsonArray arr = datum.getAsJsonArray();
                for (JsonElement el : arr) {
                    if (el != null && el.isJsonObject()) {
                        FileItem fi = createFileItemFromJson(el.getAsJsonObject(), false);
                        if (fi != null) items.add(fi);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing file list response: " + e.getMessage());
        }
        return items;
    }

    /** Tạo FileItem từ JSON */
    public FileItem createFileItemFromJson(JsonObject json, boolean isFolder) {
        if (json == null || json.isJsonNull()) return null;
        try {
            String name = getString(json, KEY_NAME, null);
            if (name == null) name = getString(json, KEY_NAME_ALT, null);
            if (name == null) return null;

            // size có thể là number hoặc string
            String sizeStr = "";
            if (json.has(KEY_SIZE)) {
                JsonElement se = json.get(KEY_SIZE);
                if (se.isJsonPrimitive() && se.getAsJsonPrimitive().isNumber()) {
                    long v = se.getAsLong();
                    sizeStr = formatFileSize(v);
                } else if (se.isJsonPrimitive()) {
                    sizeStr = se.getAsString();
                }
            }

            String fileType    = isFolder ? "Folder" : getString(json, KEY_TYPE, "File");
            String lastMod     = getString(json, KEY_LAST_MODIFIED, "");
            String permission  = getString(json, KEY_PERMISSION, "Đọc/Ghi");
            String syncStatus  = getString(json, KEY_SYNC_STATUS, "✅ Đã đồng bộ");

            String folderName;
            if (isFolder) {
                folderName = name;
            } else {
                folderName = getString(json, KEY_FOLDER_NAME, "shared");
            }

            String icon        = isFolder ? "📁" : getFileIcon(name);
            String displayName = icon + " " + name;

            return new FileItem(displayName, sizeStr, fileType, lastMod, permission, syncStatus, folderName);

        } catch (Exception e) {
            System.err.println("Error creating FileItem: " + e.getMessage());
            return null;
        }
    }

    /** Lọc theo tên thư mục */
    public ObservableList<FileItem> filterFilesByFolder(ObservableList<FileItem> allFiles, String folderName) {
        ObservableList<FileItem> filtered = FXCollections.observableArrayList();
        if (allFiles == null || folderName == null) return filtered;
        for (FileItem f : allFiles) {
            if (folderName.equals(f.getFolderName())) filtered.add(f);
        }
        return filtered;
    }

    // ==========================================================
    // DISPLAY UTILITIES
    // ==========================================================

    public static String getFileIcon(String fileName) {
        if (fileName != null && fileName.contains(".")) {
            String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            switch (ext) {
                case "doc": case "docx": case "txt": return "📄";
                case "xls": case "xlsx":             return "📊";
                case "png": case "jpg": case "jpeg":
                case "gif": case "bmp": case "tiff": return "🖼️";
                case "mp4": case "avi": case "mkv":
                case "mov": case "wmv":              return "🎥";
                case "pdf":                           return "📕";
                default:                              return "📄";
            }
        }
        return "📄";
    }

    public static String getFileType(String fileName) {
        if (fileName != null && fileName.contains(".")) {
            String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            switch (ext) {
                case "doc": case "docx": return "Document";
                case "xls": case "xlsx": return "Spreadsheet";
                case "png": case "jpg": case "jpeg": case "gif": return "Image";
                case "mp4": case "avi": case "mkv": case "mov": case "wmv": return "Video";
                case "pdf": return "PDF";
                case "txt": return "Text";
                default: return "File";
            }
        }
        return "File";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // ==========================================================
    // VALIDATION (giữ nguyên API cũ)
    // ==========================================================

    public static ValidationResult validateFileForUpload(java.io.File file) {
        if (file == null || !file.exists()) {
            return new ValidationResult(false, "File không tồn tại!");
        }
        if (!file.isFile()) {
            return new ValidationResult(false, "Chỉ có thể tải lên file, không thể tải lên thư mục!");
        }
        long maxSizeBytes = 100L * 1024 * 1024; // 100MB
        if (file.length() > maxSizeBytes) {
            return new ValidationResult(false,
                    "File quá lớn! Kích thước tối đa cho phép: 100MB\n" +
                            "Kích thước file hiện tại: " + formatFileSize(file.length()));
        }
        String fileName = file.getName();
        if (fileName.trim().isEmpty()) {
            return new ValidationResult(false, "Tên file không hợp lệ!");
        }
        String invalidChars = "<>:\"/\\\\|?*";
        for (int i = 0; i < invalidChars.length(); i++) {
            char c = invalidChars.charAt(i);
            if (fileName.indexOf(c) >= 0) {
                return new ValidationResult(false,
                        "Tên file chứa ký tự không hợp lệ: " + c + "\n" +
                                "Các ký tự không được phép: " + invalidChars);
            }
        }
        String[] blockedExtensions = {".exe", ".bat", ".cmd", ".com", ".scr", ".pif", ".vbs", ".js"};
        String lower = fileName.toLowerCase();
        for (String ext : blockedExtensions) {
            if (lower.endsWith(ext)) {
                return new ValidationResult(false,
                        "Loại file này không được phép tải lên vì lý do bảo mật: " + ext);
            }
        }
        return new ValidationResult(true, "File hợp lệ");
    }

    public static class ValidationResult {
        public final boolean isValid;
        public final String message;
        public ValidationResult(boolean isValid, String message) {
            this.isValid = isValid;
            this.message = message;
        }
    }

    // ==========================================================
    // JSON helpers & date parsing
    // ==========================================================

    private static String getString(JsonObject obj, String key, String def) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            JsonPrimitive p = obj.getAsJsonPrimitive(key);
            if (p.isString()) return p.getAsString();
            if (p.isNumber() || p.isBoolean()) return p.getAsString();
        }
        return def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            JsonPrimitive p = obj.getAsJsonPrimitive(key);
            if (p.isNumber()) return p.getAsInt();
            if (p.isString()) {
                try { return Integer.parseInt(p.getAsString()); } catch (Exception ignore) {}
            }
        }
        return def;
    }

    private static LocalDateTime parseDateSafe(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return LocalDateTime.parse(s); } catch (Exception ignore) { return null; }
    }
}
