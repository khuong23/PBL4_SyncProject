package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.common.storage.StorageManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Base64;

public class UploadFileHandler implements RequestHandler {

    // Giới hạn upload base64 (có thể điều chỉnh)
    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB
    private static final int ROOT_FOLDER_ID = 1;

    @Override
    public Response handle(Request req) {
        Path writtenPath = null;

        try (Connection connection = DatabaseManager.getConnection()) {
            JsonObject data = req.getData();
            if (data == null
                    || !data.has("fileName") || data.get("fileName").isJsonNull()
                    || !data.has("fileContent") || data.get("fileContent").isJsonNull()) {
                return error("Thiếu 'fileName' hoặc 'fileContent'");
            }

            final String rawFileName = data.get("fileName").getAsString();
            final String fileName = StorageManager.sanitizeName(rawFileName);
            if (fileName.isBlank()) return error("Tên file không hợp lệ");

            // Base64 content (client cũ)
            final String base64Content = data.get("fileContent").getAsString();
            if (base64Content.isBlank()) return error("Nội dung file rỗng");

            // folderId: nếu không truyền/<=0 sẽ dùng root ID=1
            final int folderId = (data.has("folderId") && safeInt(data.get("folderId").getAsString()) > 0)
                    ? safeInt(data.get("folderId").getAsString())
                    : ROOT_FOLDER_ID;

            // Đảm bảo folder tồn tại (đặc biệt là root=1)
            if (!folderExists(connection, folderId)) {
                return error("Folder không tồn tại (folderId=" + folderId + ")");
            }

            // Giải mã base64 + kiểm soát kích thước
            byte[] fileBytes = Base64.getDecoder().decode(base64Content);
            if (fileBytes.length > MAX_SIZE_BYTES) {
                return error("File quá lớn (> " + (MAX_SIZE_BYTES / (1024 * 1024)) + " MB). Hãy chuyển sang upload theo chunk.");
            }

            // 1) Resolve đường dẫn vật lý theo cây Folders
            Path folderPath = StorageManager.getInstance().resolveFolderPathFromDb(connection, folderId);
            Files.createDirectories(folderPath);
            StorageManager.getInstance().assertWithinRoot(folderPath);

            // 2) Ghi file
            Path dst = folderPath.resolve(fileName).normalize();
            StorageManager.getInstance().assertWithinRoot(dst); // bảo vệ PATH TRAVERSAL
            Files.write(dst, fileBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            writtenPath = dst; // để rollback nếu DB lỗi

            // 3) Metadata
            long fileSize = Files.size(dst);
            String fileHash = computeSHA256(fileBytes);
            Timestamp lastModified = new Timestamp(Files.getLastModifiedTime(dst).toMillis());

            // 4) Upsert DB: yêu cầu UNIQUE(FolderID, FileName)
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO Files (FolderID, FileName, FileSize, FileHash, LastModified) " +
                            "VALUES (?,?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE FileSize=VALUES(FileSize), FileHash=VALUES(FileHash), LastModified=VALUES(LastModified)"
            )) {
                ps.setInt(1, folderId);
                ps.setString(2, fileName);
                ps.setLong(3, fileSize);
                ps.setString(4, fileHash);
                ps.setTimestamp(5, lastModified);
                ps.executeUpdate();
            }

            // 5) Trả data cho client
            JsonObject out = new JsonObject();
            out.addProperty("folderId", folderId);
            out.addProperty("fileName", fileName);
            out.addProperty("size", fileSize);
            out.addProperty("hash", fileHash);
            out.addProperty("lastModified", lastModified.getTime());

            return new Response("success", "File uploaded successfully", out);

        } catch (Exception e) {
            e.printStackTrace();
            // Nếu DB lỗi sau khi đã ghi file → xóa file để tránh mồ côi
            if (writtenPath != null) {
                try { Files.deleteIfExists(writtenPath); } catch (Exception ignore) {}
            }
            return error("Upload failed: " + e.getMessage());
        }
    }

    // ---------- Helpers ----------

    private boolean folderExists(Connection c, int folderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM Folders WHERE FolderID = ? LIMIT 1")) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String computeSHA256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(data);
        StringBuilder sb = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private Response error(String msg) {
        return new Response("error", msg, null);
    }
}
