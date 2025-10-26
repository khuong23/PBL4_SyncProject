package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Handler để lấy danh sách quyền của một user trên một folder cụ thể
 * Request data format:
 * {
 *   "userId": <int>,
 *   "folderId": <int>
 * }
 * 
 * Response data format (success):
 * {
 *   "userId": <int>,
 *   "folderId": <int>,
 *   "permissions": ["READ", "WRITE", "DELETE", ...]
 * }
 */
public class GetFolderPermissionsHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        Response res = new Response();
        Connection conn = null;
        
        try {
            conn = DatabaseManager.getConnection();
            
            JsonObject data = req.getData();
            if (data == null || !data.has("userId") || !data.has("folderId")) {
                throw new IllegalArgumentException("Thiếu thông tin userId hoặc folderId.");
            }

            int userId = data.get("userId").getAsInt();
            int folderId = data.get("folderId").getAsInt();

            // Query permissions từ database
            Set<String> permissions = new HashSet<>();
            
            String query = "SELECT Permission FROM FolderAccessControl WHERE UserID = ? AND FolderID = ?";
            
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, userId);
                ps.setInt(2, folderId);
                
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String permission = rs.getString("Permission");
                        if (permission != null && !permission.isEmpty()) {
                            permissions.add(permission.toUpperCase());
                        }
                    }
                }
            }

            // Tạo response data
            JsonObject outData = new JsonObject();
            outData.addProperty("userId", userId);
            outData.addProperty("folderId", folderId);
            
            JsonArray permArray = new JsonArray();
            for (String perm : permissions) {
                permArray.add(perm);
            }
            outData.add("permissions", permArray);

            res.setStatus("success");
            res.setMessage("Lấy quyền thành công.");
            res.setData(outData);

        } catch (IllegalArgumentException e) {
            res.setStatus("error");
            res.setMessage(e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("Lỗi truy vấn database: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("Lỗi lấy quyền: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignore) {}
            }
        }
        
        return res;
    }
}
