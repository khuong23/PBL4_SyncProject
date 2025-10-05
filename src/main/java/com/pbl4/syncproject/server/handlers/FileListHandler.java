package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;

/**
 * Handler để lấy danh sách files và folders từ database
 */
public class FileListHandler implements RequestHandler {
    private final Connection dbConnection;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    public FileListHandler(Connection dbConnection) {
        this.dbConnection = dbConnection;
    }

    @Override
    public Response handle(Request request) {
        Response response = new Response();
        
        try {
            JsonObject data = request.getData();
            int folderId = data != null && data.has("folderId") ? data.get("folderId").getAsInt() : 0;
            
            JsonObject responseData = new JsonObject();
            
            // Lấy danh sách folders
            JsonArray folders = getFolders(folderId);
            responseData.add("folders", folders);
            
            // Lấy danh sách files
            JsonArray files = getFiles(folderId);
            responseData.add("files", files);
            
            response.setStatus("success");
            response.setMessage("Lấy danh sách thành công");
            response.setData(responseData);
            
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus("error");
            response.setMessage("Lỗi khi lấy danh sách: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Lấy danh sách folders con của folder hiện tại
     */
    private JsonArray getFolders(int parentFolderId) throws Exception {
        JsonArray folders = new JsonArray();
        
        String sql = "SELECT FolderID, FolderName, LastModified, CreatedAt FROM Folders WHERE ParentFolderID ";
        if (parentFolderId == 0) {
            sql += "IS NULL"; // Root folders
        } else {
            sql += "= ?";
        }
        sql += " ORDER BY FolderName";
        
        System.out.println("SERVER DEBUG: Getting folders with query: " + sql + " (parentFolderId: " + parentFolderId + ")");
        
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            if (parentFolderId != 0) {
                stmt.setInt(1, parentFolderId);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                int folderCount = 0;
                while (rs.next()) {
                    folderCount++;
                    JsonObject folder = new JsonObject();
                    folder.addProperty("id", rs.getInt("FolderID"));
                    folder.addProperty("name", rs.getString("FolderName"));
                    folder.addProperty("type", "folder");
                    
                    // Format date
                    java.sql.Timestamp lastModified = rs.getTimestamp("LastModified");
                    if (lastModified != null) {
                        folder.addProperty("lastModified", dateFormat.format(lastModified));
                    } else {
                        java.sql.Timestamp createdAt = rs.getTimestamp("CreatedAt");
                        folder.addProperty("lastModified", dateFormat.format(createdAt));
                    }
                    
                    folder.addProperty("size", "");
                    folder.addProperty("permission", "Đọc/Ghi");
                    folder.addProperty("syncStatus", "✅ Đã đồng bộ");
                    
                    folders.add(folder);
                    System.out.println("SERVER DEBUG: Added folder: " + rs.getString("FolderName") + " (ID: " + rs.getInt("FolderID") + ")");
                }
                System.out.println("SERVER DEBUG: Total folders found: " + folderCount);
            }
        }
        
        return folders;
    }
    
    /**
     * Lấy danh sách files trong folder hiện tại
     */
    private JsonArray getFiles(int folderId) throws Exception {
        JsonArray files = new JsonArray();
        
        String sql;
        
        if (folderId == 0) {
            // Root request - return ALL files with folder information
            sql = "SELECT f.FileID, f.FileName, f.FileSize, f.LastModified, f.CreatedAt, " +
                  "fd.FolderName as FolderName " +
                  "FROM Files f " +
                  "INNER JOIN Folders fd ON f.FolderID = fd.FolderID " +
                  "ORDER BY fd.FolderName, f.FileName";
            System.out.println("SERVER DEBUG: Getting ALL files with query: " + sql);
        } else {
            // Specific folder request
            sql = "SELECT f.FileID, f.FileName, f.FileSize, f.LastModified, f.CreatedAt, " +
                  "fd.FolderName as FolderName " +
                  "FROM Files f " +
                  "INNER JOIN Folders fd ON f.FolderID = fd.FolderID " +
                  "WHERE f.FolderID = ? ORDER BY f.FileName";
            System.out.println("SERVER DEBUG: Getting files for folder " + folderId + " with query: " + sql);
        }
        
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            if (folderId != 0) {
                stmt.setInt(1, folderId);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                int fileCount = 0;
                while (rs.next()) {
                    fileCount++;
                    JsonObject file = new JsonObject();
                    file.addProperty("id", rs.getInt("FileID"));
                    file.addProperty("name", rs.getString("FileName"));
                    file.addProperty("type", "file");
                    
                    // Add folder name for client-side filtering
                    file.addProperty("folderName", rs.getString("FolderName"));
                    
                    // Format file size
                    long fileSize = rs.getLong("FileSize");
                    file.addProperty("size", formatFileSize(fileSize));
                    
                    // Get file type from extension
                    String fileName = rs.getString("FileName");
                    file.addProperty("fileType", getFileType(fileName));
                    
                    // Format date
                    java.sql.Timestamp lastModified = rs.getTimestamp("LastModified");
                    if (lastModified != null) {
                        file.addProperty("lastModified", dateFormat.format(lastModified));
                    } else {
                        java.sql.Timestamp createdAt = rs.getTimestamp("CreatedAt");
                        file.addProperty("lastModified", dateFormat.format(createdAt));
                    }
                    
                    file.addProperty("permission", "Đọc/Ghi");
                    file.addProperty("syncStatus", "✅ Đã đồng bộ");
                    
                    files.add(file);
                    System.out.println("SERVER DEBUG: Added file: " + fileName + " (FolderName: " + rs.getString("FolderName") + ")");
                }
                System.out.println("SERVER DEBUG: Total files found: " + fileCount);
            }
        }
        
        return files;
    }
    
    /**
     * Format file size in human readable format
     */
    private String formatFileSize(long bytes) {
        if (bytes == 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    /**
     * Get file type from extension
     */
    private String getFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "File";
        }
        
        String extension = fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
        
        switch (extension) {
            case ".doc":
            case ".docx":
            case ".pdf":
            case ".txt":
            case ".rtf":
                return "Document";
            case ".xls":
            case ".xlsx":
            case ".csv":
                return "Spreadsheet";
            case ".ppt":
            case ".pptx":
                return "Presentation";
            case ".jpg":
            case ".jpeg":
            case ".png":
            case ".gif":
            case ".bmp":
            case ".tiff":
                return "Image";
            case ".mp4":
            case ".avi":
            case ".mkv":
            case ".mov":
            case ".wmv":
                return "Video";
            case ".mp3":
            case ".wav":
            case ".flac":
            case ".aac":
                return "Audio";
            case ".zip":
            case ".rar":
            case ".7z":
            case ".tar":
            case ".gz":
                return "Archive";
            default:
                return "File";
        }
    }
}