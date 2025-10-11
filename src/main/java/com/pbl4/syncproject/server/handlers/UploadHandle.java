package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Base64;

public class UploadHandle implements RequestHandler {

    public UploadHandle() {
        // Constructor không cần connection nữa
    }

    @Override
    public Response handle(Request req) {
        // Dùng try-with-resources để tự động quản lý connection
        try (Connection connection = DatabaseManager.getConnection()) {
            JsonObject data = req.getData();
            String fileName = data.get("fileName").getAsString();
            String base64Content = data.get("fileContent").getAsString();
            
            // Luôn đảm bảo có root folder để upload
            int folderId;
            if (data.has("folderId") && data.get("folderId").getAsInt() > 0) {
                folderId = data.get("folderId").getAsInt();
            } else {
                // Tạo hoặc lấy root folder (ParentFolderID = NULL)
                folderId = ensureRootFolder(connection);
            }

            byte[] fileBytes = Base64.getDecoder().decode(base64Content);

            File uploadDir = new File("uploads");
            if (!uploadDir.exists() && !uploadDir.mkdirs()) {
                return new Response("error", "Failed to create upload directory", null);
            }

            File savedFile = new File(uploadDir, fileName);
            try (OutputStream os = new FileOutputStream(savedFile)) {
                os.write(fileBytes);
            }

            long fileSize = savedFile.length();
            String fileHash = computeSHA256(fileBytes);
            long lastModified = savedFile.lastModified();

            // Check if file exists
            String checkSql = "SELECT COUNT(*) FROM Files WHERE FolderID = ? AND FileName = ?";
            try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
                checkStmt.setInt(1, folderId);
                checkStmt.setString(2, fileName);
                var rs = checkStmt.executeQuery();
                rs.next();
                int count = rs.getInt(1);

                if (count > 0) {
                    // Update existing file metadata
                    String updateSql = "UPDATE Files SET FileSize = ?, FileHash = ?, LastModified = ? WHERE FolderID = ? AND FileName = ?";
                    try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                        updateStmt.setLong(1, fileSize);
                        updateStmt.setString(2, fileHash);
                        updateStmt.setTimestamp(3, new Timestamp(lastModified));
                        updateStmt.setInt(4, folderId);
                        updateStmt.setString(5, fileName);
                        updateStmt.executeUpdate();
                    }
                    return new Response("success", "File updated successfully: " + fileName, null);
                } else {
                    // Insert new file
                    String insertSql = "INSERT INTO Files (FolderID, FileName, FileSize, FileHash, LastModified) VALUES (?, ?, ?, ?, ?)";
                    try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                        insertStmt.setInt(1, folderId);
                        insertStmt.setString(2, fileName);
                        insertStmt.setLong(3, fileSize);
                        insertStmt.setString(4, fileHash);
                        insertStmt.setTimestamp(5, new Timestamp(lastModified));
                        insertStmt.executeUpdate();
                    }
                    return new Response("success", "File uploaded successfully: " + fileName, null);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new Response("error", "Upload failed: " + e.getMessage(), null);
        }
    }

    // Tính hash SHA-256
    private String computeSHA256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    // Đảm bảo có root folder, tạo nếu chưa có
    private int ensureRootFolder(Connection connection) throws SQLException {
        // Kiểm tra xem đã có root folder chưa
        String checkSql = "SELECT FolderID FROM Folders WHERE ParentFolderID IS NULL LIMIT 1";
        try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
            var rs = checkStmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("FolderID");
            }
        }
        
        // Nếu chưa có, tạo root folder
        String insertSql = "INSERT INTO Folders (FolderName, ParentFolderID, LastModified) VALUES (?, NULL, NOW())";
        try (PreparedStatement insertStmt = connection.prepareStatement(insertSql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            insertStmt.setString(1, "Root");
            insertStmt.executeUpdate();
            
            var keys = insertStmt.getGeneratedKeys();
            if (keys.next()) {
                return keys.getInt(1);
            }
        }
        
        throw new SQLException("Failed to create root folder");
    }
}
