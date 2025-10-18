package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.storage.StorageManager;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;

public class DeleteFolderHandler implements RequestHandler {
    private static final int ROOT_ID = 1;

    @Override
    public Response handle(Request req) {
        try (Connection conn = DatabaseManager.getConnection()) {
            JsonObject data = (req != null) ? req.getData() : null;
            if (data == null || !data.has("folderId") || data.get("folderId").isJsonNull()) {
                return err("Thiếu 'folderId'");
            }
            int folderId = data.get("folderId").getAsInt();
            boolean recursive = data.has("recursive") && !data.get("recursive").isJsonNull()
                    && data.get("recursive").getAsBoolean();

            if (folderId == ROOT_ID) return err("Không thể xoá thư mục gốc (ID=1)");
            if (!folderExists(conn, folderId)) return err("Folder không tồn tại (ID=" + folderId + ")");

            // Resolve đường dẫn vật lý một lần
            Path folderPath = StorageManager.getInstance().resolveFolderPathFromDb(conn, folderId);
            StorageManager.getInstance().assertWithinRoot(folderPath);

            if (!recursive) {
                // Chỉ xoá khi rỗng (DB)
                int subCount = countChildFolders(conn, folderId);
                int fileCount = countFilesInFolder(conn, folderId);
                if (subCount > 0 || fileCount > 0) {
                    JsonObject info = new JsonObject();
                    info.addProperty("childFolders", subCount);
                    info.addProperty("childFiles", fileCount);
                    return new Response("error", "Thư mục không rỗng. Thêm 'recursive=true' để xoá toàn bộ.", info);
                }

                // FS: xoá nếu rỗng
                deleteDirIfEmpty(folderPath);

                // DB: xoá 1 dòng (không cần txn phức tạp)
                deleteFolderRow(conn, folderId);

                JsonObject out = new JsonObject();
                out.addProperty("folderId", folderId);
                out.addProperty("recursive", false);
                return new Response("success", "Xoá thư mục trống thành công", out);
            }

            // recursive = true
            // FS: xoá cả cây dưới thư mục này (nếu tồn tại)
            deleteFsTree(folderPath);

            // DB: xoá 1 dòng, phần còn lại CASCADE
            deleteFolderRow(conn, folderId);

            JsonObject out = new JsonObject();
            out.addProperty("folderId", folderId);
            out.addProperty("recursive", true);
            return new Response("success", "Xoá thư mục (đệ quy) thành công", out);

        } catch (Exception e) {
            e.printStackTrace();
            return err("Lỗi xoá thư mục: " + e.getMessage());
        }
    }

    // ===== Helpers (tối giản) =====

    private boolean folderExists(Connection c, int folderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM Folders WHERE FolderID=? LIMIT 1")) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private int countChildFolders(Connection c, int parentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM Folders WHERE ParentFolderID=?")) {
            ps.setInt(1, parentId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private int countFilesInFolder(Connection c, int folderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM Files WHERE FolderID=?")) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private void deleteFolderRow(Connection c, int folderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM Folders WHERE FolderID=?")) {
            ps.setInt(1, folderId);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new SQLException("Không xoá được folderID=" + folderId);
        }
    }

    /** Xoá thư mục rỗng trên FS (im lặng nếu không tồn tại hoặc không rỗng). */
    private void deleteDirIfEmpty(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                if (ds.iterator().hasNext()) return; // không rỗng
            }
            Files.deleteIfExists(dir);
            System.out.println("[INFO]  Deleted empty FS folder: " + dir);
        } catch (Exception e) {
            System.err.println("[WARN]  Cannot delete empty FS folder: " + dir + " - " + e.getMessage());
        }
    }

    /** Xoá toàn bộ cây trên FS cho thư mục cho trước. */
    private void deleteFsTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
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
