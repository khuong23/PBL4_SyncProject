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
    private final Connection connection;

    public UploadHandle() {
        Connection conn = null;
        try {
            conn = DatabaseManager.getConnection();
        } catch (SQLException e) {
            e.printStackTrace();
            // You may want to handle this more gracefully in production
        }
        this.connection = conn;
    }

    @Override
    public Response handle(Request req) {
        try {
            JsonObject data = req.getData();
            String fileName = data.get("fileName").getAsString();
            String base64Content = data.get("fileContent").getAsString();
            int folderId = data.has("folderId") ? data.get("folderId").getAsInt() : 0;

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
}
