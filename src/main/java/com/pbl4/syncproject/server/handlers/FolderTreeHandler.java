package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import com.pbl4.syncproject.server.dao.FolderDAO;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class FolderTreeHandler implements RequestHandler {

    private final FolderDAO folderDAO;

    public FolderTreeHandler(Connection dbConnection) {
        this.folderDAO = new FolderDAO(dbConnection);
    }
    @Override
    public Response handle(Request req) {
        JsonObject data = req.getData();
        // SỬA ĐỔI: Nếu không có parentId hoặc parentId <= 0, ta sẽ lấy thư mục gốc.
        Integer parentId = (data != null && data.has("parentId") && data.get("parentId").getAsInt() > 0)
                ? data.get("parentId").getAsInt()
                : null;

        Response res = new Response();
        try {
            List<Folders> children;
            if (parentId == null) {
                // Lấy các thư mục gốc (ParentFolderID IS NULL)
                children = folderDAO.getRootFolders();
            } else {
                children = folderDAO.getChildren(parentId);
            }

            if (children != null) {
                JsonArray array = new JsonArray();
                for (Folders child : children) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("folderId", child.getFolderId());
                    if (child.getParentId() != null) {
                        obj.addProperty("parentFolderId", child.getParentId());
                    } else {
                        obj.add("parentFolderId", null);
                    }
                    obj.addProperty("folderName", child.getFolderName());
                    // Thêm thuộc tính để client biết liệu thư mục này có con hay không
                    obj.addProperty("hasChildren", folderDAO.hasChildren(child.getFolderId()));

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
