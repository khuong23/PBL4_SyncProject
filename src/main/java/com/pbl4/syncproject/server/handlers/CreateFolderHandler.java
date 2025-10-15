package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonNull;
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
 * Tạo thư mục mới trong cây Folders.
 * Quy ước: Root folder có ID = 1 (ParentFolderID của root = NULL, các thư mục con trỏ về 1).
 */
public class CreateFolderHandler implements RequestHandler {

    private static final int ROOT_ID = 1;

    @Override
    public Response handle(Request req) {
        try (Connection conn = DatabaseManager.getConnection()) {

            JsonObject data = (req != null) ? req.getData() : null;
            if (data == null || !data.has("folderName") || data.get("folderName").isJsonNull()) {
                return error("Thiếu 'folderName'");
            }

            // Sanitize tên
            final String rawName = data.get("folderName").getAsString();
            final String folderName = StorageManager.sanitizeName(rawName);
            if (folderName.isBlank()) return error("Tên thư mục không hợp lệ");

            // parentFolderId: mặc định tạo dưới ROOT_ID
            int parentId = ROOT_ID;
            if (data.has("parentFolderId") && !data.get("parentFolderId").isJsonNull()) {
                try { parentId = data.get("parentFolderId").getAsInt(); } catch (Exception ignore) {}
                if (parentId <= 0) parentId = ROOT_ID;
            }

            // Bắt buộc root tồn tại
            if (!folderExists(conn, ROOT_ID)) {
                return error("Root folder (ID=1) chưa tồn tại. Hãy seed dữ liệu Folders trước.");
            }

            // Kiểm tra parent có tồn tại?
            if (!folderExists(conn, parentId)) {
                return error("Parent folder không tồn tại (ID=" + parentId + ")");
            }

            // Kiểm tra trùng tên trong cùng parent
            if (existsNameInParent(conn, parentId, folderName)) {
                return error("Thư mục đã tồn tại trong thư mục cha");
            }

            // 1) Insert DB
            int newId;
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO Folders (ParentFolderID, FolderName, LastModified, CreatedAt) " +
                            "VALUES (?, ?, NOW(), NOW())",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setInt(1, parentId);
                ins.setString(2, folderName);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Không lấy được FolderID mới");
                    newId = keys.getInt(1);
                }
            }

            // 2) Tạo thư mục trên đĩa theo cây DB
            StorageManager sm = StorageManager.getInstance();
            Path folderPath = sm.resolveFolderPathFromDb(conn, newId);
            try {
                sm.assertWithinRoot(folderPath);
                Files.createDirectories(folderPath);
            } catch (Exception ioEx) {
                // Rollback "thủ công" phần DB nếu tạo thư mục thất bại
                deleteFolderByIdQuiet(conn, newId);
                throw ioEx;
            }

            // 3) Trả về
            JsonObject out = new JsonObject();
            out.addProperty("folderId", newId);
            out.addProperty("folderName", folderName);
            out.addProperty("parentFolderId", parentId);
            out.add("lastModified", JsonNull.INSTANCE); // client có thể refresh lại list để lấy thời gian

            return new Response("success", "Tạo thư mục thành công", out);

        } catch (Exception e) {
            e.printStackTrace();
            return error("Lỗi tạo thư mục: " + e.getMessage());
        }
    }

    // ===== Helpers =====

    private boolean folderExists(Connection conn, int folderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM Folders WHERE FolderID = ? LIMIT 1")) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean existsNameInParent(Connection conn, int parentId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM Folders WHERE ParentFolderID = ? AND FolderName = ? LIMIT 1")) {
            ps.setInt(1, parentId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void deleteFolderByIdQuiet(Connection conn, int folderId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM Folders WHERE FolderID = ?")) {
            ps.setInt(1, folderId);
            ps.executeUpdate();
        } catch (Exception ignore) {}
    }

    private Response error(String msg) {
        return new Response("error", msg, null);
    }
}
