package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.storage.StorageManager;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.dao.SyncHistoryDAO;
import com.pbl4.syncproject.server.dao.UserDAO;
import com.pbl4.syncproject.server.dao.ChangesDAO; // <-- thêm

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;

public class DeleteFolderHandler implements RequestHandler {
    private static final int ROOT_ID = 1;

    @Override
    public Response handle(Request req) {
        Path folderPath = null;

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false); // TX cho OCC + change-feed

            JsonObject data = (req != null) ? req.getData() : null;
            if (data == null || !data.has("folderId") || data.get("folderId").isJsonNull()) {
                return err("Thiếu 'folderId'");
            }

            // Auth & quyền
            int userId = UserDAO.getUserIdFromRequest(req);
            if (userId <= 0) return err("Cần xác thực người dùng");

            int folderId = data.get("folderId").getAsInt();
            boolean recursive = data.has("recursive") && !data.get("recursive").isJsonNull()
                    && data.get("recursive").getAsBoolean();

            // OCC tuỳ chọn
            Integer baseVersion = (data.has("baseVersion") && !data.get("baseVersion").isJsonNull())
                    ? data.get("baseVersion").getAsInt() : null;

            if (folderId == ROOT_ID) return err("Không thể xoá thư mục gốc (ID=1)");
//             doi quyen xoa folder sau
//            if (!UserDAO.hasFolderPermission(userId, folderId, "DELETE")) {
//                return err("Bạn không có quyền xóa (DELETE) thư mục này.");
//            }

            // Lấy meta + khoá hàng
            FolderMeta meta = getFolderMetaForUpdate(conn, folderId);
            if (meta == null) {
                conn.rollback();
                return err("Folder không tồn tại (ID=" + folderId + ")");
            }

            // OCC
            if (baseVersion != null && !baseVersion.equals(meta.version)) {
                conn.rollback();
                JsonObject body = new JsonObject();
                body.addProperty("currentVersion", meta.version);
                return new Response("error", "Conflict: Version mismatch", body);
            }

            // Nếu không recursive: folder phải rỗng (DB)
            if (!recursive) {
                int subCount = countChildFolders(conn, folderId);
                int fileCount = countFilesInFolder(conn, folderId);
                if (subCount > 0 || fileCount > 0) {
                    conn.rollback();
                    JsonObject info = new JsonObject();
                    info.addProperty("childFolders", subCount);
                    info.addProperty("childFiles", fileCount);
                    return new Response("error", "Thư mục không rỗng. Thêm 'recursive=true' để xoá toàn bộ.", info);
                }
            }

            // Resolve đường dẫn trước khi xoá DB (để còn dùng sau commit)
            folderPath = StorageManager.getInstance().resolveFolderPathFromDb(conn, folderId);
            StorageManager.getInstance().assertWithinRoot(folderPath);

            // Ghi change-feed: FOLDER_DELETE (FolderId = parent/container), Version = current+1
            int newVersion = meta.version + 1;
            long seq = ChangesDAO.insertFolderChange(
                    conn, folderId, "DELETE", meta.name, meta.parentId, userId, newVersion
            );

            // Xoá DB với OCC
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM Folders WHERE FolderID=? AND Version=?")) {
                ps.setInt(1, folderId);
                ps.setInt(2, meta.version);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    conn.rollback();
                    JsonObject body = new JsonObject();
                    body.addProperty("currentVersion", meta.version);
                    return new Response("error", "Conflict: Folder changed by another update", body);
                }
            }

            // Lịch sử
            SyncHistoryDAO.logAction(conn, userId, SyncHistoryDAO.ACTION_DELETE_FOLDER, null, folderId);

            conn.commit();

            // Sau COMMIT mới xóa trên FS (tránh DB-ok nhưng FS-fail làm lệch)
            boolean removedFromDisk = false;
            try {
                if (recursive) {
                    deleteFsTree(folderPath);
                    removedFromDisk = true;
                } else {
                    removedFromDisk = deleteDirIfEmpty(folderPath);
                }
            } catch (Exception fsEx) {
                System.err.println("[WARN] Delete FS failed: " + fsEx.getMessage());
                // có thể log lại để dọn rác thủ công sau
            }

            JsonObject out = new JsonObject();
            out.addProperty("folderId", folderId);
            out.addProperty("parentFolderId", meta.parentId);
            out.addProperty("recursive", recursive);
            out.addProperty("newVersion", newVersion);
            out.addProperty("seq", seq);
            out.addProperty("removedFromDisk", removedFromDisk);

            return new Response("success", "Xoá thư mục thành công", out);

        } catch (Exception e) {
            e.printStackTrace();
            return err("Lỗi xoá thư mục: " + e.getMessage());
        }
    }

    // ===== Helpers =====

    private static class FolderMeta {
        int id;
        Integer parentId; // có thể null nếu root
        String name;
        int version;
    }

    private FolderMeta getFolderMetaForUpdate(Connection c, int folderId) throws SQLException {
        String sql = "SELECT FolderID, ParentFolderID, FolderName, Version " +
                "FROM Folders WHERE FolderID=? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                FolderMeta m = new FolderMeta();
                m.id = rs.getInt("FolderID");
                m.parentId = (Integer) rs.getObject("ParentFolderID");
                m.name = rs.getString("FolderName");
                m.version = rs.getInt("Version");
                return m;
            }
        }
    }

    private int countChildFolders(Connection c, int parentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM Folders WHERE ParentFolderID=?")) {
            ps.setInt(1, parentId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private int countFilesInFolder(Connection c, int folderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM Files WHERE FolderID=?")) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private boolean deleteDirIfEmpty(Path dir) {
        try {
            if (!Files.exists(dir)) return true; // coi như đã "xóa"
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                if (ds.iterator().hasNext()) return false; // không rỗng
            }
            Files.deleteIfExists(dir);
            System.out.println("[INFO]  Deleted empty FS folder: " + dir);
            return true;
        } catch (Exception e) {
            System.err.println("[WARN]  Cannot delete empty FS folder: " + dir + " - " + e.getMessage());
            return false;
        }
    }

    private void deleteFsTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                StorageManager.getInstance().assertWithinRoot(file);
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                StorageManager.getInstance().assertWithinRoot(dir);
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        System.out.println("[INFO]  Deleted FS tree: " + root);
    }

    private Response err(String msg) { return new Response("error", msg, null); }
}
