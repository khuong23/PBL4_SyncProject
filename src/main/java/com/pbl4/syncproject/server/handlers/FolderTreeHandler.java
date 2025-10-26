package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import com.pbl4.syncproject.server.dao.FolderDAO;
import com.pbl4.syncproject.server.dao.UserDAO;

import java.sql.SQLException;
import java.util.List;

/**
 * Handler trả về cây thư mục con của một folder cho trước.
 * Quy ước: Root folder có ID = 1.
 * - Nếu không gửi parentId hoặc parentId = 0 -> mặc định lấy con của root (1).
 * - Trả về mảng (có thể rỗng) thay vì lỗi khi không có thư mục con.
 */
public class FolderTreeHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        Response res = new Response();
        try {
            JsonObject data = (req != null) ? req.getData() : null;

            int parentId = 1; // mặc định root
            if (data != null && data.has("parentId")) {
                try {
                    parentId = data.get("parentId").getAsInt();
                } catch (Exception ignore) {
                    parentId = 1;
                }
            }
            if (parentId == 0) parentId = 1;

            // Lấy danh sách thư mục con (dùng pool bên trong DAO)
            // Nếu client cung cấp thông tin userId/username thì lọc theo quyền
            int userId = AuthHelper.getUserIdFromRequest(req);
            List<Folders> children;
            if (userId > 0 && UserDAO.isAdmin(userId)) {
                // Admin thấy tất cả
                children = FolderDAO.getChildren(parentId);
            } else if (userId > 0) {
                // Lọc theo quyền (READ)
                children = FolderDAO.getChildren(parentId, userId);
            } else {
                // Không có thông tin user -> trả về toàn bộ (fallback)
                children = FolderDAO.getChildren(parentId);
            }

            JsonArray array = new JsonArray();
            if (children != null) {
                for (Folders child : children) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("folderId", child.getFolderId());
                    if (child.getParentId() != null) {
                        obj.addProperty("parentFolderId", child.getParentId());
                    } else {
                        obj.add("parentFolderId", JsonNull.INSTANCE);
                    }
                    obj.addProperty("folderName", child.getFolderName());

                    // createdAt (có thể null)
                    if (child.getCreatedAt() != null) {
                        obj.addProperty("createdAt", child.getCreatedAt().toString());
                    } else {
                        obj.add("createdAt", JsonNull.INSTANCE);
                    }

                    // lastModified (có thể null)
                    // LƯU Ý: Model Folders nên dùng getLastModified(); nếu bạn đang dùng getUpdatedAt(), đổi lại cho khớp.
                    if (child.getUpdatedAt() != null) {
                        obj.addProperty("lastModified", child.getUpdatedAt().toString());
                    } else {
                        obj.add("lastModified", JsonNull.INSTANCE);
                    }

                    array.add(obj);
                }
            }

            res.setStatus("success");
            res.setMessage("Folder tree retrieved");
            res.setData(array);
            return res;

        } catch (SQLException e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("DB error: " + e.getMessage());
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("Handler error: " + e.getMessage());
            return res;
        }
    }

    // Helper tạm để lấy userId từ request. Hệ thống xác thực thực tế nên thay bằng cơ chế session/token.
    private static class AuthHelper {
        static int getUserIdFromRequest(Request req) {
            try {
                if (req == null) return -1;
                if (req.getData() == null) return -1;
                if (req.getData().has("userId") && req.getData().get("userId").isJsonPrimitive()) {
                    return req.getData().get("userId").getAsInt();
                }
                if (req.getData().has("username") && req.getData().get("username").isJsonPrimitive()) {
                    String uname = req.getData().get("username").getAsString();
                    return UserDAO.getUserIdByUsername(uname);
                }
            } catch (Exception ignore) {
            }
            return -1;
        }
    }
}
