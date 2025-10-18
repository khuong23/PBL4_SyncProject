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

public class DeleteFileHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        try (Connection conn = DatabaseManager.getConnection()) {
            JsonObject data = (req != null) ? req.getData() : null;
            if (data == null) return err("Thiếu data");

            Integer fileId   = (data.has("fileId")   && !data.get("fileId").isJsonNull())   ? safeInt(data.get("fileId").getAsString()) : null;
            Integer folderId = (data.has("folderId") && !data.get("folderId").isJsonNull()) ? safeInt(data.get("folderId").getAsString()) : null;
            String fileName  = (data.has("fileName") && !data.get("fileName").isJsonNull()) ? data.get("fileName").getAsString() : null;

            FileMeta meta;
            if (fileId != null && fileId > 0) {
                meta = findByFileId(conn, fileId);
            } else if (folderId != null && folderId > 0 && fileName != null && !fileName.isBlank()) {
                meta = findByFolderAndName(conn, folderId, StorageManager.sanitizeName(fileName));
            } else {
                return err("Cần 'fileId' hoặc ('folderId' + 'fileName').");
            }
            if (meta == null) return err("Không tìm thấy file.");

            // Xoá trên đĩa (nếu có)
            Path folderPath = StorageManager.getInstance().resolveFolderPathFromDb(conn, meta.folderId);
            StorageManager.getInstance().assertWithinRoot(folderPath);
            Path filePath = folderPath.resolve(meta.fileName).normalize();
            StorageManager.getInstance().assertWithinRoot(filePath);

            boolean existedOnDisk = Files.deleteIfExists(filePath);
            System.out.println("[INFO]  Delete file FS: " + filePath + " existed=" + existedOnDisk);

            // Xoá DB
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM Files WHERE FileID=?")) {
                ps.setInt(1, meta.fileId);
                int rows = ps.executeUpdate();
                if (rows == 0) return err("Xoá DB thất bại (FileID=" + meta.fileId + ")");
            }

            JsonObject out = new JsonObject();
            out.addProperty("fileId", meta.fileId);
            out.addProperty("folderId", meta.folderId);
            out.addProperty("fileName", meta.fileName);
            out.addProperty("removedFromDisk", existedOnDisk);

            return new Response("success", "Xoá file thành công", out);

        } catch (Exception e) {
            e.printStackTrace();
            return err("Lỗi xoá file: " + e.getMessage());
        }
    }

    // ===== Helpers =====
    private static class FileMeta {
        int fileId, folderId;
        String fileName;
    }

    private FileMeta findByFileId(Connection c, int fileId) throws SQLException {
        String sql = "SELECT FileID, FolderID, FileName FROM Files WHERE FileID=? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                FileMeta m = new FileMeta();
                m.fileId = rs.getInt(1);
                m.folderId = rs.getInt(2);
                m.fileName = rs.getString(3);
                return m;
            }
        }
    }

    private FileMeta findByFolderAndName(Connection c, int folderId, String fileName) throws SQLException {
        String sql = "SELECT FileID, FolderID, FileName FROM Files WHERE FolderID=? AND FileName=? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, folderId);
            ps.setString(2, fileName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                FileMeta m = new FileMeta();
                m.fileId = rs.getInt(1);
                m.folderId = rs.getInt(2);
                m.fileName = rs.getString(3);
                return m;
            }
        }
    }

    private Integer safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private Response err(String msg) { return new Response("error", msg, null); }
}
