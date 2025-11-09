package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.storage.StorageManager;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.dao.SyncHistoryDAO;
import com.pbl4.syncproject.server.dao.UserDAO;
import com.pbl4.syncproject.server.dao.ChangesDAO;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

public class DeleteFileHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        Path filePath = null;

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false); // TX cho OCC + change-feed

            JsonObject data = (req != null) ? req.getData() : null;
            if (data == null) return err("Thiếu data");

            int userId = UserDAO.getUserIdFromRequest(req);
            if (userId <= 0) return err("Không xác định được người dùng");

            // Đọc tham số
            Integer fileId   = getOptInt(data, "fileId");
            Integer folderId = getOptInt(data, "folderId");
            String  fileName = (data.has("fileName") && !data.get("fileName").isJsonNull())
                    ? data.get("fileName").getAsString() : null;
            Integer baseVersion = getOptInt(data, "baseVersion"); // OCC tuỳ chọn

            // Tìm file + khoá hàng
            FileMeta meta;
            if (fileId != null && fileId > 0) {
                meta = findByFileIdForUpdate(conn, fileId);
            } else if (folderId != null && folderId > 0 && fileName != null && !fileName.isBlank()) {
                meta = findByFolderAndNameForUpdate(conn, folderId, StorageManager.sanitizeName(fileName));
            } else {
                return err("Cần 'fileId' hoặc ('folderId' + 'fileName').");
            }
            if (meta == null) return err("Không tìm thấy file.");
//            sau nay doi quyen xoa file
//            if (!UserDAO.hasFolderPermission(userId, meta.folderId, "DELETE")) {
//                return err("Bạn không có quyền xóa (delete) file trong thư mục này.");
//            }

            // OCC: nếu client có gửi baseVersion thì bắt buộc khớp
            if (baseVersion != null && !baseVersion.equals(meta.version)) {
                conn.rollback();
                JsonObject body = new JsonObject();
                body.addProperty("currentVersion", meta.version);
                return new Response("conflict", "Version mismatch", body);
            }

            // Chuẩn bị đường dẫn để xóa sau khi commit
            Path folderPath = StorageManager.getInstance().resolveFolderPathFromDb(conn, meta.folderId);
            StorageManager.getInstance().assertWithinRoot(folderPath);
            filePath = folderPath.resolve(meta.fileName).normalize();
            StorageManager.getInstance().assertWithinRoot(filePath);

            // Ghi change-feed trước khi xoá row (tombstone)
            int newVersion = meta.version + 1;
            long seq = ChangesDAO.insertFileChange(
                    conn,
                    meta.fileId,
                    "DELETE",
                    newVersion,
                    null,            // hash null cho delete
                    null,            // size null cho delete
                    meta.folderId,
                    meta.fileName,
                    userId
            );

            // Xoá DB (guard theo PK là đủ; có thể thêm WHERE Version=? nếu muốn cực chặt)
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM Files WHERE FileID=?")) {
                ps.setInt(1, meta.fileId);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    conn.rollback();
                    return err("Xoá DB thất bại (FileID=" + meta.fileId + ")");
                }
            }

            // Lịch sử
            SyncHistoryDAO.logAction(userId, SyncHistoryDAO.ACTION_DELETE_FILE, meta.fileId, meta.folderId);

            conn.commit();

            // Sau COMMIT mới xóa trên đĩa (tránh mất file khi DB fail)
            boolean removedOnDisk = false;
            try {
                removedOnDisk = Files.deleteIfExists(filePath);
            } catch (Exception fsEx) {
                // log cảnh báo, có thể có job dọn rác sau
                System.err.println("[WARN] Delete FS failed: " + fsEx.getMessage());
            }

            JsonObject out = new JsonObject();
            out.addProperty("fileId", meta.fileId);
            out.addProperty("folderId", meta.folderId);
            out.addProperty("fileName", meta.fileName);
            out.addProperty("removedFromDisk", removedOnDisk);
            out.addProperty("newVersion", newVersion);
            out.addProperty("seq", seq);

            return new Response("success", "Xoá file thành công", out);

        } catch (Exception e) {
            e.printStackTrace();
            return err("Lỗi xoá file: " + e.getMessage());
        }
    }

    // ===== Helpers =====
    private static class FileMeta {
        int fileId, folderId, version;
        String fileName;
    }

    private FileMeta findByFileIdForUpdate(Connection c, int fileId) throws SQLException {
        String sql = "SELECT FileID, FolderID, FileName, Version FROM Files WHERE FileID=? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                FileMeta m = new FileMeta();
                m.fileId = rs.getInt("FileID");
                m.folderId = rs.getInt("FolderID");
                m.fileName = rs.getString("FileName");
                m.version = rs.getInt("Version");
                return m;
            }
        }
    }

    private FileMeta findByFolderAndNameForUpdate(Connection c, int folderId, String fileName) throws SQLException {
        String sql = "SELECT FileID, FolderID, FileName, Version FROM Files " +
                "WHERE FolderID=? AND FileName=? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, folderId);
            ps.setString(2, fileName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                FileMeta m = new FileMeta();
                m.fileId = rs.getInt("FileID");
                m.folderId = rs.getInt("FolderID");
                m.fileName = rs.getString("FileName");
                m.version = rs.getInt("Version");
                return m;
            }
        }
    }

    private Integer getOptInt(JsonObject data, String key) {
        if (!data.has(key) || data.get(key).isJsonNull()) return null;
        try { return data.get(key).getAsInt(); } catch (Exception ignore) { return null; }
    }

    private Response err(String msg) { return new Response("error", msg, null); }
}
