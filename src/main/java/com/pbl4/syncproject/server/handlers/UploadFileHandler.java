package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.dao.UserDAO;
import com.pbl4.syncproject.server.dao.FilesDAO;
import com.pbl4.syncproject.server.dao.SyncHistoryDAO;
import com.pbl4.syncproject.server.dao.ChangesDAO;

import com.pbl4.syncproject.common.storage.StorageManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Base64;

public class UploadFileHandler implements RequestHandler {

    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB
    private static final int ROOT_FOLDER_ID = 1;

    @Override
    public Response handle(Request req) {
        Path writtenPath = null;

        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false); // OCC + thay đổi nhiều bảng => cần transaction

            JsonObject data = req.getData();
            if (data == null
                    || !data.has("fileName") || data.get("fileName").isJsonNull()
                    || !data.has("fileContent") || data.get("fileContent").isJsonNull()) {
                return error("Thiếu 'fileName' hoặc 'fileContent'");
            }

            // Lấy userId để kiểm tra quyền
            int userId = UserDAO.getUserIdFromRequest(req);
            if (userId <= 0) {
                return error("Không xác định được người dùng");
            }

            final String rawFileName = data.get("fileName").getAsString();
            final String fileName = StorageManager.sanitizeName(rawFileName);
            if (fileName.isBlank()) return error("Tên file không hợp lệ");

            // Client có thể gửi kèm baseVersion (bắt buộc với UPDATE)
            // FIX: Không dùng final để có thể điều chỉnh khi cần
            Integer baseVersion = (data.has("baseVersion") && !data.get("baseVersion").isJsonNull())
                    ? data.get("baseVersion").getAsInt()
                    : null;

            // (Tùy chọn) vẫn nhận lastKnownHash để cảnh báo sớm
            final String clientBaseHash = (data.has("lastKnownHash") && !data.get("lastKnownHash").isJsonNull())
                    ? data.get("lastKnownHash").getAsString()
                    : null;

            // folderId: nếu không truyền/<=0 sẽ dùng root ID=1
            final int folderId = (data.has("folderId") && safeInt(data.get("folderId").getAsString()) > 0)
                    ? safeInt(data.get("folderId").getAsString())
                    : ROOT_FOLDER_ID;

            // Đảm bảo folder tồn tại
            if (!folderExists(connection, folderId)) {
                return error("Folder không tồn tại (folderId=" + folderId + ")");
            }

            // Kiểm tra quyền WRITE trên folder
            if (!UserDAO.hasFolderPermission(userId, folderId, "WRITE")) {
                return error("Bạn không có quyền ghi (upload) vào thư mục này.");
            }

            // ========== OCC: Đọc phiên bản hiện tại với khóa hàng ==========
            Integer existingFileId = null;
            Integer currentVersion = null;
            String  currentHash    = null;

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT FileID, Version, FileHash FROM Files " +
                            "WHERE FolderID=? AND FileName=? FOR UPDATE")) {
                ps.setInt(1, folderId);
                ps.setString(2, fileName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingFileId = rs.getInt("FileID");
                        currentVersion = rs.getInt("Version");
                        currentHash    = rs.getString("FileHash");
                    }
                }
            }

            // FIX: Nếu file đã tồn tại mà client KHÔNG gửi baseVersion
            // → Client không biết file đã tồn tại (cache bị xóa/mất đồng bộ)
            // → Chấp nhận như UPDATE với baseVersion = 0, nhưng CẢNH BÁO conflict
            if (existingFileId != null && baseVersion == null) {
                // Thay vì từ chối ngay, hãy coi baseVersion = 0 và kiểm tra hash
                baseVersion = 0;
                System.out.println("⚠️ Client upload file đã tồn tại mà không gửi baseVersion. File: " + fileName + ", FileID: " + existingFileId);
                // Tiếp tục xử lý như UPDATE, sẽ kiểm tra version/hash ở bước tiếp theo
            }

            // Nếu có baseVersion nhưng KHÔNG khớp -> conflict
            if (existingFileId != null && baseVersion != null && !baseVersion.equals(currentVersion)) {
                JsonObject body = new JsonObject();
                body.addProperty("currentVersion", currentVersion);
                if (currentHash != null) body.addProperty("currentHash", currentHash);
                connection.rollback();
                return new Response("conflict", "Conflict occur", body);
            }

            // (Tuỳ chọn) Cảnh báo sớm bằng hash: nếu clientBaseHash khác serverHash => cũng coi là conflict
            if (existingFileId != null && clientBaseHash != null && currentHash != null
                    && !clientBaseHash.equals(currentHash)) {
                JsonObject body = new JsonObject();
                body.addProperty("currentVersion", currentVersion);
                body.addProperty("currentHash", currentHash);
                body.addProperty("reason", "Hash mismatch (another client updated)");
                connection.rollback();
                return new Response("conflict", "conflict occur", body);
            }

            // ========== Ghi nội dung vật lý ==========
            final String base64Content = data.get("fileContent").getAsString();
            if (base64Content.isBlank()) {
                connection.rollback();
                return error("Nội dung file rỗng");
            }

            byte[] fileBytes = Base64.getDecoder().decode(base64Content);
            if (fileBytes.length > MAX_SIZE_BYTES) {
                connection.rollback();
                return error("File quá lớn (> " + (MAX_SIZE_BYTES / (1024 * 1024)) + " MB). Hãy chuyển sang upload theo chunk.");
            }

            Path folderPath = StorageManager.getInstance().resolveFolderPathFromDb(connection, folderId);
            Files.createDirectories(folderPath);
            StorageManager.getInstance().assertWithinRoot(folderPath);

            Path dst = folderPath.resolve(fileName).normalize();
            StorageManager.getInstance().assertWithinRoot(dst);
            Files.write(dst, fileBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            writtenPath = dst;

            long fileSize = Files.size(dst);
            String fileHash = computeSHA256(fileBytes);
            Timestamp lastModified = new Timestamp(Files.getLastModifiedTime(dst).toMillis());

            // ========== Cập nhật DB với Version/Changes ==========
            int newVersion;
            int fileId;

            if (existingFileId == null) {
                // CREATE: Version = 1
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO Files (FolderID, FileName, FileSize, FileHash, LastModified, Version) " +
                                "VALUES (?,?,?,?,?,1)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, folderId);
                    ps.setString(2, fileName);
                    ps.setLong(3, fileSize);
                    ps.setString(4, fileHash);
                    ps.setTimestamp(5, lastModified);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) fileId = keys.getInt(1);
                        else throw new SQLException("Không lấy được FileID sau khi INSERT");
                    }
                }
                newVersion = 1;

                // Ghi change feed
                ChangesDAO.insertFileChange(connection, fileId, "CREATE", newVersion, fileHash, fileSize, folderId, fileName, userId);

                // (Giữ lại lịch sử nếu bạn vẫn muốn)
                SyncHistoryDAO.logAction(userId, SyncHistoryDAO.ACTION_UPLOAD_FILE, fileId, folderId);

            } else {
                // UPDATE: Version = current + 1
                newVersion = currentVersion + 1;
                fileId = existingFileId;

                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE Files SET FileSize=?, FileHash=?, LastModified=?, Version=? WHERE FileID=?")) {
                    ps.setLong(1, fileSize);
                    ps.setString(2, fileHash);
                    ps.setTimestamp(3, lastModified);
                    ps.setInt(4, newVersion);
                    ps.setInt(5, fileId);
                    ps.executeUpdate();
                }

                // Ghi change feed
                ChangesDAO.insertFileChange(connection, fileId, "UPDATE", newVersion, fileHash, fileSize, folderId, fileName, userId);

                SyncHistoryDAO.logAction(userId, SyncHistoryDAO.ACTION_UPDATE_FILE, fileId, folderId);
            }

            // Lấy lastSeq để trả luôn cho client (tiện cập nhật since_seq)
            long lastSeq = ChangesDAO.getLastSeq(connection);

            connection.commit();

            // 6) Trả data cho client
            JsonObject out = new JsonObject();
            out.addProperty("fileId", fileId);
            out.addProperty("folderId", folderId);
            out.addProperty("fileName", fileName);
            out.addProperty("size", fileSize);
            out.addProperty("hash", fileHash);
            out.addProperty("lastModified", lastModified.getTime());
            out.addProperty("newVersion", newVersion);
            out.addProperty("seq", lastSeq);

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
