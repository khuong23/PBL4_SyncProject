package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.common.storage.StorageManager;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.sql.Timestamp;
import java.util.Base64;

public class UploadFileHandler implements RequestHandler {

    // Giới hạn khi upload bằng base64 (có thể tăng/giảm)
    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    @Override
    public Response handle(Request req) {
        try (Connection connection = DatabaseManager.getConnection()) {

            JsonObject data = req.getData();
            if (data == null
                    || !data.has("fileName") || data.get("fileName").isJsonNull()
                    || !data.has("fileContent") || data.get("fileContent").isJsonNull()) {
                return error("Thiếu 'fileName' hoặc 'fileContent'");
            }

            final String rawFileName = data.get("fileName").getAsString();
            final String fileName = StorageManager.sanitizeName(rawFileName);

            // client cũ gửi "fileContent" (base64)
            final String base64Content = data.get("fileContent").getAsString();

            // folderId: nếu không truyền/<=0 sẽ dùng root (ParentFolderID IS NULL)
            final int folderId = (data.has("folderId") && data.get("folderId").getAsInt() > 0)
                    ? data.get("folderId").getAsInt()
                    : ensureRootFolder(connection);

            byte[] fileBytes = Base64.getDecoder().decode(base64Content);
            if (fileBytes.length > MAX_SIZE_BYTES) {
                return error("File quá lớn (> " + (MAX_SIZE_BYTES / 1024 / 1024) + "MB). Hãy chuyển sang upload theo chunk.");
            }

            // 1) Xác định đường dẫn vật lý theo cây Folders (FolderName, ParentFolderID)
            Path folderPath = StorageManager.getInstance().resolveFolderPathFromDb(connection, folderId);
            Files.createDirectories(folderPath);
            StorageManager.getInstance().assertWithinRoot(folderPath);

            // 2) Ghi trực tiếp vào đích
            Path dst = folderPath.resolve(fileName).normalize();
            Files.write(dst, fileBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            // 3) Metadata
            long fileSize = Files.size(dst);
            String fileHash = computeSHA256(fileBytes);
            long lastModifiedMs = Files.getLastModifiedTime(dst).toMillis();

            // 4) Upsert DB (yêu cầu UNIQUE(FolderID, FileName))
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO Files (FolderID, FileName, FileSize, FileHash, LastModified) " +
                            "VALUES (?,?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE FileSize=VALUES(FileSize), FileHash=VALUES(FileHash), LastModified=VALUES(LastModified)"
            )) {
                ps.setInt(1, folderId);
                ps.setString(2, fileName);
                ps.setLong(3, fileSize);
                ps.setString(4, fileHash);
                ps.setTimestamp(5, new Timestamp(lastModifiedMs));
                ps.executeUpdate();
            }

            // 5) Trả data cho client
            JsonObject out = new JsonObject();
            out.addProperty("folderId", folderId);
            out.addProperty("fileName", fileName);
            out.addProperty("size", fileSize);
            out.addProperty("hash", fileHash);
            out.addProperty("lastModified", lastModifiedMs);

            return new Response("success", "File uploaded successfully", out);

        } catch (Exception e) {
            e.printStackTrace();
            return error("Upload failed: " + e.getMessage());
        }
    }

    // ---------- Helpers ----------

    private boolean existsFile(Connection c, int folderId, String fileName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM Files WHERE FolderID=? AND FileName=? LIMIT 1")) {
            ps.setInt(1, folderId);
            ps.setString(2, fileName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Tính SHA-256
    private String computeSHA256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(data);
        StringBuilder sb = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // Đảm bảo có root: ParentFolderID IS NULL (theo schema cũ của bạn)
    private int ensureRootFolder(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT FolderID FROM Folders WHERE ParentFolderID IS NULL LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        // chưa có → tạo "Root"
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO Folders (FolderName, ParentFolderID, LastModified) VALUES (?, NULL, NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            ins.setString(1, "Root");
            ins.executeUpdate();
            try (ResultSet keys = ins.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
            }
        }
        throw new SQLException("Failed to create root folder");
    }

    private Response success(String msg) {
        return new Response("success", msg, null);
    }

    private Response error(String msg) {
        return new Response("error", msg, null);
    }
}
