package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.storage.StorageManager;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

/**
 * Handler để xóa file theo relative path (được sử dụng bởi SyncAgent).
 * Request data:
 * - relativePath: đường dẫn tương đối từ thư mục gốc, ví dụ: "Folder1/subfolder/file.txt"
 */
public class DeleteFileByPathHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        Response res = new Response();
        try (Connection conn = DatabaseManager.getConnection()) {
            JsonObject data = req != null ? req.getData() : null;
            if (data == null || !data.has("relativePath")) {
                res.setStatus("error");
                res.setMessage("Thiếu relativePath.");
                return res;
            }

            String relativePath = data.get("relativePath").getAsString();
            if (relativePath == null || relativePath.isBlank()) {
                res.setStatus("error");
                res.setMessage("relativePath không hợp lệ.");
                return res;
            }

            // Parse relativePath để tìm folder và fileName
            // Ví dụ: "Folder1/subfolder/file.txt" -> folder="Folder1/subfolder", fileName="file.txt"
            String[] parts = relativePath.split("[/\\\\]");
            if (parts.length == 0) {
                res.setStatus("error");
                res.setMessage("relativePath không hợp lệ (không có file).");
                return res;
            }

            String fileName = parts[parts.length - 1];
            StringBuilder folderPathBuilder = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) folderPathBuilder.append("/");
                folderPathBuilder.append(parts[i]);
            }
            String folderPath = folderPathBuilder.toString();

            // Tìm folderId từ folderPath
            int folderId = resolveFolderIdFromPath(conn, folderPath);
            if (folderId <= 0) {
                res.setStatus("error");
                res.setMessage("Không tìm thấy folder cho path: " + folderPath);
                return res;
            }

            // Tìm file trong DB
            FileMeta meta = findByFolderAndName(conn, folderId, fileName);
            if (meta == null) {
                res.setStatus("error");
                res.setMessage("Không tìm thấy file: " + fileName + " trong folder ID=" + folderId);
                return res;
            }

            // Xóa trên đĩa
            Path physicalFolderPath = StorageManager.getInstance().resolveFolderPathFromDb(conn, meta.folderId);
            StorageManager.getInstance().assertWithinRoot(physicalFolderPath);
            Path filePath = physicalFolderPath.resolve(meta.fileName).normalize();
            StorageManager.getInstance().assertWithinRoot(filePath);

            boolean existedOnDisk = Files.deleteIfExists(filePath);
            System.out.println("[INFO] DeleteFileByPath: " + filePath + " existed=" + existedOnDisk);

            // Xóa DB
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM Files WHERE FileID=?")) {
                ps.setInt(1, meta.fileId);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    res.setStatus("error");
                    res.setMessage("Xóa DB thất bại (FileID=" + meta.fileId + ")");
                    return res;
                }
            }

            res.setStatus("success");
            res.setMessage("Đã xóa file: " + relativePath);
            JsonObject outData = new JsonObject();
            outData.addProperty("fileId", meta.fileId);
            outData.addProperty("relativePath", relativePath);
            res.setData(outData);

        } catch (Exception e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("Lỗi xóa file: " + e.getMessage());
        }
        return res;
    }

    /**
     * Tìm folderId từ relative path (ví dụ: "Folder1/subfolder")
     * Nếu folderPath rỗng, trả về root folder (parentId=0)
     */
    private int resolveFolderIdFromPath(Connection conn, String folderPath) throws SQLException {
        if (folderPath == null || folderPath.isBlank()) {
            // Root folder: tìm folder có ParentID=0 hoặc NULL
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT FolderID FROM Folders WHERE ParentID IS NULL OR ParentID=0 LIMIT 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt("FolderID");
                }
            }
            return -1;
        }

        String[] parts = folderPath.split("[/\\\\]");
        int currentFolderId = -1;

        // Bắt đầu từ root
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT FolderID FROM Folders WHERE ParentID IS NULL OR ParentID=0 LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) currentFolderId = rs.getInt("FolderID");
            }
        }

        if (currentFolderId <= 0) return -1;

        // Duyệt từng phần của path
        for (String folderName : parts) {
            if (folderName.isBlank()) continue;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT FolderID FROM Folders WHERE ParentID=? AND FolderName=? LIMIT 1")) {
                ps.setInt(1, currentFolderId);
                ps.setString(2, folderName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentFolderId = rs.getInt("FolderID");
                    } else {
                        return -1; // Không tìm thấy folder
                    }
                }
            }
        }

        return currentFolderId;
    }

    private FileMeta findByFolderAndName(Connection conn, int folderId, String fileName) throws SQLException {
        String sql = "SELECT FileID, FileName, FileSize, UploadDate, FilePath, FolderID FROM Files WHERE FolderID=? AND FileName=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, folderId);
            ps.setString(2, fileName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    FileMeta fm = new FileMeta();
                    fm.fileId = rs.getInt("FileID");
                    fm.fileName = rs.getString("FileName");
                    fm.folderId = rs.getInt("FolderID");
                    return fm;
                }
            }
        }
        return null;
    }

    private static class FileMeta {
        int fileId;
        String fileName;
        int folderId;
    }
}
