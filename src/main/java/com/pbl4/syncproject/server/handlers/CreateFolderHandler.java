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

public class CreateFolderHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        Response res = new Response();
        try (Connection conn = DatabaseManager.getConnection()) {
            JsonObject data = req.getData().getAsJsonObject();
            if (data == null || !data.has("folderName") || data.get("folderName").isJsonNull()) {
                return error("Thiếu 'folderName'");
            }
            String rawName = data.get("folderName").getAsString();
            String folderName = StorageManager.sanitizeName(rawName);
            Integer parentId = (data.has("parentFolderId") && !data.get("parentFolderId").isJsonNull())
                    ? data.get("parentFolderId").getAsInt() : null;

            if (folderName.isBlank()) return error("Tên thư mục không hợp lệ");

            // 1) Trùng tên trong cùng parent?
            String existsSql = "SELECT COUNT(*) FROM Folders WHERE " +
                    (parentId == null ? "ParentFolderID IS NULL" : "ParentFolderID=?") + " AND FolderName=?";

            try (PreparedStatement chk = conn.prepareStatement(existsSql)) {
                int idx = 1;
                if (parentId != null) chk.setInt(idx++, parentId);
                chk.setString(idx, folderName);
                try (ResultSet rs = chk.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) return error("Thư mục đã tồn tại");
                }
            }

            // 2) Insert DB
            int newId;
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO Folders(ParentFolderID, FolderName, LastModified, CreatedAt) " +
                            "VALUES(?, ?, NOW(), NOW())",
                    Statement.RETURN_GENERATED_KEYS)) {
                if (parentId == null) ins.setNull(1, Types.INTEGER); else ins.setInt(1, parentId);
                ins.setString(2, folderName);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) { keys.next(); newId = keys.getInt(1); }
            }

            // 3) Tạo thư mục trên đĩa theo cây DB
            StorageManager sm = StorageManager.getInstance();
            Path folderPath = sm.resolveFolderPathFromDb(conn, newId);
            Files.createDirectories(folderPath);

            // 4) Trả về
            JsonObject out = new JsonObject();
            out.addProperty("folderId", newId);
            out.addProperty("folderName", folderName);
            if (parentId != null) out.addProperty("parentFolderId", parentId); else out.add("parentFolderId", null);

            res.setStatus("success");
            res.setMessage("Tạo thư mục thành công");
            res.setData(out);
            return res;

        } catch (Exception e) {
            e.printStackTrace();
            return error("Lỗi tạo thư mục: " + e.getMessage());
        }
    }

    private Response error(String msg) {
        Response r = new Response();
        r.setStatus("error");
        r.setMessage(msg);
        return r;
    }
}
