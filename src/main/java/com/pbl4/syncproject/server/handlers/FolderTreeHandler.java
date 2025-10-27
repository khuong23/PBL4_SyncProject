package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.dao.FolderDAO;
import com.pbl4.syncproject.server.dao.UserDAO;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

public class FolderTreeHandler implements RequestHandler {

    public FolderTreeHandler() {
        // No-arg constructor - get connection from pool in handle method
    }
    
    @Override
    public Response handle(Request req) {
        Response res = new Response();
        
        try (Connection conn = DatabaseManager.getConnection()) {
            FolderDAO folderDAO = new FolderDAO(conn);
            
            // Lấy userId để kiểm tra quyền
            int userId = UserDAO.getUserIdFromRequest(req);
            if (userId <= 0) {
                res.setStatus("error");
                res.setMessage("Không xác định được người dùng");
                return res;
            }
            
            JsonObject data = req.getData();
            // Lấy parentId từ request
            // parentId = 0 hoặc null: lấy root folders (ParentFolderID IS NULL)
            // parentId > 0: lấy children của folder có ID = parentId
            Integer parentId = null;
            if (data != null && data.has("parentId")) {
                int pid = data.get("parentId").getAsInt();
                if (pid > 0) {
                    parentId = pid;
                }
            }

            List<Folders> children;
            if (parentId == null) {
                // Lấy các thư mục gốc (ParentFolderID IS NULL)
                children = folderDAO.getRootFolders();
            } else {
                // Kiểm tra quyền READ trên folder cha trước
                // Ngoại lệ: Root folder (ID=1) luôn được phép xem để user có thể điều hướng
                if (parentId != 1 && !UserDAO.hasFolderPermission(userId, parentId, "READ")) {
                    res.setStatus("error");
                    res.setMessage("Bạn không có quyền xem thư mục này");
                    return res;
                }
                // Lấy các thư mục con của parentId
                children = folderDAO.getChildren(parentId);
            }

            // Lọc các folder mà user có quyền READ
            List<Folders> filteredChildren = children.stream()
                .filter(folder -> UserDAO.hasFolderPermission(userId, folder.getFolderId(), "READ"))
                .collect(Collectors.toList());

            if (filteredChildren != null) {
                JsonArray array = new JsonArray();
                for (Folders child : filteredChildren) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("folderId", child.getFolderId());
                    if (child.getParentId() != null) {
                        obj.addProperty("parentFolderId", child.getParentId());
                    } else {
                        obj.add("parentFolderId", null);
                    }
                    obj.addProperty("folderName", child.getFolderName());
                    // Sử dụng hasChildren từ đối tượng Folders (đã được query từ SQL)
                    obj.addProperty("hasChildren", child.getHasChildren());

                    if (child.getCreatedAt() != null) {
                        obj.addProperty("createdAt", child.getCreatedAt().toString());
                    } else {
                        obj.add("createdAt", null);
                    }
                    if (child.getUpdatedAt() != null) {
                        obj.addProperty("lastModified", child.getUpdatedAt().toString());
                    } else {
                        obj.add("lastModified", null);
                    }
                    array.add(obj);
                }
                res.setStatus("success");
                res.setMessage("Folder tree retrieved");
                res.setData(array);
            } else {
                res.setStatus("error");
                res.setMessage("Could not retrieve children for parentId " + parentId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage(e.getMessage());
        }
        return res;
    }
}
