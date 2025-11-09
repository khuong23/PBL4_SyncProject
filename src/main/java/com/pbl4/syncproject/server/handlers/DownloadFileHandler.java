package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.storage.StorageManager;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.dao.UserDAO;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Base64;

/**
 * Download file: trả nội dung base64 + metadata.
 * Yêu cầu (ít nhất một trong hai cách):
 *  - Cách 1: { "fileId": 123 }
 *  - Cách 2: { "folderId": 1, "fileName": "abc.txt" }
 */
public class DownloadFileHandler implements RequestHandler {

    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024; // 50MB

    @Override
    public Response handle(Request req) {
        try (Connection conn = DatabaseManager.getConnection()) {
            JsonObject data = (req != null) ? req.getData() : null;
            if (data == null) return error("Thiếu data");

            // Lấy userId để kiểm tra quyền
            int userId = UserDAO.getUserIdFromRequest(req);
            if (userId <= 0) {
                return error("Không xác định được người dùng");
            }

            Integer fileId   = data.has("fileId")    && !data.get("fileId").isJsonNull()    ? safeInt(data.get("fileId").getAsString())       : null;
            Integer folderId = data.has("folderId")  && !data.get("folderId").isJsonNull()  ? safeInt(data.get("folderId").getAsString())     : null;
            String  fileName = data.has("fileName")  && !data.get("fileName").isJsonNull()  ? data.get("fileName").getAsString()              : null;

            // Lấy metadata từ DB
            FileMeta meta;
            if (fileId != null && fileId > 0) {
                meta = findByFileId(conn, fileId);
            } else if (folderId != null && folderId > 0 && fileName != null && !fileName.isBlank()) {
                String cleanName = StorageManager.sanitizeName(fileName);
                meta = findByFolderAndName(conn, folderId, cleanName);
            } else {
                return error("Thiếu 'fileId' hoặc ('folderId' + 'fileName').");
            }

            if (meta == null) return error("Không tìm thấy file.");

            // Kiểm tra quyền READ trên folder chứa file
            if (!UserDAO.hasFolderPermission(userId, meta.folderId, "READ")) {
                return error("Bạn không có quyền đọc (download) file trong thư mục này.");
            }

            // Resolve đường dẫn vật lý theo cây Folders
            Path folderPath = StorageManager.getInstance().resolveFolderPathFromDb(conn, meta.folderId);
            StorageManager.getInstance().assertWithinRoot(folderPath);
            Path filePath = folderPath.resolve(meta.fileName).normalize();
            StorageManager.getInstance().assertWithinRoot(filePath);

            if (!Files.exists(filePath)) {
                return error("File không tồn tại trên đĩa.");
            }

            long size = Files.size(filePath);
            if (size > MAX_SIZE_BYTES) {
                return error("File quá lớn (> " + (MAX_SIZE_BYTES / (1024 * 1024)) + "MB). Hãy dùng tải theo chunk.");
            }

            byte[] bytes = Files.readAllBytes(filePath);
            String base64 = Base64.getEncoder().encodeToString(bytes);

            // FIX: Sử dụng hash từ DB thay vì tính lại từ file vật lý
            // Đảm bảo client nhận đúng hash mà server đang tracking trong DB
            String sha256FromFile = computeSHA256(bytes);
            String sha256FromDB = meta.dbHash;
            
            // PHÁT HIỆN DATA CORRUPTION và AUTO-FIX
            if (sha256FromDB != null && !sha256FromDB.equals(sha256FromFile)) {
                System.err.println("⚠️⚠️⚠️ PHÁT HIỆN DATA CORRUPTION ⚠️⚠️⚠️");
                System.err.println("File: " + meta.fileName + " (FileID=" + meta.fileId + ")");
                System.err.println("DB hash:   " + sha256FromDB);
                System.err.println("File hash: " + sha256FromFile);
                System.err.println("Version:   " + meta.version);
                System.err.println("→ AUTO-FIX: Cập nhật DB để khớp với file vật lý...");
                
                // AUTO-FIX: Cập nhật DB
                try (PreparedStatement psAutoFix = conn.prepareStatement(
                        "UPDATE Files SET FileHash = ? WHERE FileID = ?")) {
                    psAutoFix.setString(1, sha256FromFile);
                    psAutoFix.setInt(2, meta.fileId);
                    psAutoFix.executeUpdate();
                    sha256FromDB = sha256FromFile; // Cập nhật biến local
                    System.err.println("✅ Đã sửa DB!");
                } catch (SQLException e) {
                    System.err.println("❌ Không thể auto-fix: " + e.getMessage());
                }
            }
            
            // Luôn trả hash từ DB (đây là "source of truth")
            String hashToReturn = (sha256FromDB != null && !sha256FromDB.isEmpty()) ? sha256FromDB : sha256FromFile;

            JsonObject out = new JsonObject();
            out.addProperty("fileId", meta.fileId);
            out.addProperty("folderId", meta.folderId);
            out.addProperty("fileName", meta.fileName);
            out.addProperty("size", size);
            out.addProperty("hash", hashToReturn); // Trả hash từ DB
            out.addProperty("version", meta.version);
            out.addProperty("encoding", "base64");
            out.addProperty("fileContent", base64);
            out.addProperty("lastModified", meta.lastModified != null ? meta.lastModified.getTime() : null);

            System.out.println("[INFO]  Downloaded file: " + meta.fileName + " (v" + meta.version + ", hash=" + hashToReturn + ", " + size + " bytes)");
            return new Response("success", "Download successful", out);

        } catch (Exception e) {
            e.printStackTrace();
            return error("Download failed: " + e.getMessage());
        }
    }

    // ===== Helpers =====

    private FileMeta findByFileId(Connection conn, int fileId) throws SQLException {
        String sql = "SELECT FileID, FolderID, FileName, FileSize, FileHash, Version, LastModified, CreatedAt FROM Files WHERE FileID = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapMeta(rs);
            }
        }
    }

    private FileMeta findByFolderAndName(Connection conn, int folderId, String fileName) throws SQLException {
        String sql = "SELECT FileID, FolderID, FileName, FileSize, FileHash, Version, LastModified, CreatedAt FROM Files WHERE FolderID = ? AND FileName = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, folderId);
            ps.setString(2, fileName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapMeta(rs);
            }
        }
    }

    private FileMeta mapMeta(ResultSet rs) throws SQLException {
        FileMeta m = new FileMeta();
        m.fileId = rs.getInt("FileID");
        m.folderId = rs.getInt("FolderID");
        m.fileName = rs.getString("FileName");
        m.dbHash = rs.getString("FileHash");
        m.version = rs.getInt("Version");
        m.lastModified = rs.getTimestamp("LastModified");
        m.createdAt = rs.getTimestamp("CreatedAt");
        return m;
    }

    private String computeSHA256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Integer safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private Response error(String msg) {
        return new Response("error", msg, null);
    }

    // metadata tạm dùng trong handler
    @SuppressWarnings("unused")
    private static class FileMeta {
        int fileId;
        int folderId;
        String fileName;
        String dbHash;
        int version;
        Timestamp lastModified;
        Timestamp createdAt;
    }
}
