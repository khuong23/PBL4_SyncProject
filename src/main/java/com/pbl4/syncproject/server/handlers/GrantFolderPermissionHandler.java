package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.dao.UserDAO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler để admin cấp quyền cho user trên folder
 */
public class GrantFolderPermissionHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        Response res = new Response();
        Connection conn = null;
        try {
            conn = DatabaseManager.getConnection();
            conn.setAutoCommit(false);

            JsonObject data = req.getData();
            if (data == null || !data.has("targetUserId") || !data.has("folderId") || !data.has("permissions")) {
                throw new IllegalArgumentException("Thiếu thông tin targetUserId, folderId hoặc permissions.");
            }

            int requesterUserId = AuthHelper.getUserIdFromRequest(req);
            int targetUserId = data.get("targetUserId").getAsInt();
            int folderId = data.get("folderId").getAsInt();
            JsonArray permissionsArray = data.getAsJsonArray("permissions");

            // Kiểm tra quyền admin
            if (!UserDAO.isAdmin(requesterUserId)) {
                throw new SecurityException("Chỉ Admin mới có quyền cấp quyền.");
            }

            // Xóa quyền cũ
            try (PreparedStatement deletePs = conn.prepareStatement(
                    "DELETE FROM FolderAccessControl WHERE UserID = ? AND FolderID = ?")) {
                deletePs.setInt(1, targetUserId);
                deletePs.setInt(2, folderId);
                deletePs.executeUpdate();
            }

            // Thêm quyền mới
            List<String> grantedPermissions = new ArrayList<>();
            if (permissionsArray != null && !permissionsArray.isEmpty()) {
                try (PreparedStatement insertPs = conn.prepareStatement(
                        "INSERT INTO FolderAccessControl (UserID, FolderID, Permission) VALUES (?, ?, ?)") ) {
                    for (JsonElement permElement : permissionsArray) {
                        String permission = permElement.getAsString().toUpperCase();
                        if (permission.equals("READ") || permission.equals("WRITE") || permission.equals("DELETE")) {
                            insertPs.setInt(1, targetUserId);
                            insertPs.setInt(2, folderId);
                            insertPs.setString(3, permission);
                            insertPs.addBatch();
                            grantedPermissions.add(permission);
                        }
                    }
                    insertPs.executeBatch();
                }
            }

            conn.commit();

            JsonObject outData = new JsonObject();
            outData.addProperty("targetUserId", targetUserId);
            outData.addProperty("folderId", folderId);
            outData.add("grantedPermissions", new com.google.gson.Gson().toJsonTree(grantedPermissions));

            res.setStatus("success");
            res.setMessage("Cập nhật quyền thành công.");
            res.setData(outData);

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignore) {}
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("Lỗi cấp quyền: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignore) {}
        }
        return res;
    }

    // Auth helper: cố gắng lấy userId từ request.data (tùy client gửi userId hoặc username)
    private static class AuthHelper {
        static int getUserIdFromRequest(Request req) {
            try {
                if (req == null || req.getData() == null) return -1;
                if (req.getData().has("userId")) return req.getData().get("userId").getAsInt();
                if (req.getData().has("username")) return UserDAO.getUserIdByUsername(req.getData().get("username").getAsString());
            } catch (Exception ignore) {}
            return -1;
        }
    }
}
