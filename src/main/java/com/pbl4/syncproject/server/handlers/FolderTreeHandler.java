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
import java.util.logging.Handler;
import java.util.logging.Logger;

public class FolderTreeHandler implements RequestHandler {

    private final FolderDAO folderDAO;

    public FolderTreeHandler(Connection dbConnection) {
        this.folderDAO = new FolderDAO(dbConnection);
    }
    @Override
    public Response handle(Request req) {
        JsonObject data = req.getData();
        Integer parentId = data.has("parentId") ? data.get("parentId").getAsInt() : null;

        Response res = new Response();
        // Handle root folder request
        if(parentId == 0) {
            try {
                Folders root = folderDAO.getRootFolder();
                if (root != null) {
                    JsonArray array = new JsonArray();
                    JsonObject obj = new JsonObject();
                    obj.addProperty("folderId", root.getFolderId());
                    obj.addProperty("parentFolderId", root.getParentId());
                    obj.addProperty("folderName", root.getFolderName());
                    obj.addProperty("createdAt", root.getCreatedAt() != null ? root.getCreatedAt().toString() : "");
                    obj.addProperty("lastModified", root.getUpdatedAt() != null ? root.getUpdatedAt().toString() : "");
                    array.add(obj);
                    res.setStatus("success");
                    res.setMessage("Root folder retrieved");
                    res.setData(array);
                } else {
                    res.setStatus("error");
                    res.setMessage("Root folder not found");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                res.setStatus("error");
                res.setMessage(e.getMessage());
            }
            return res;
        }
        // Handle children folders request
        try {
            List<Folders> children = folderDAO.getChildren(parentId != null ? parentId : -1);
            if (children != null && !children.isEmpty()) {
                JsonArray array = new JsonArray();
                for (Folders child : children) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("folderId", child.getFolderId());
                    obj.addProperty("parentFolderId", child.getParentId());
                    obj.addProperty("folderName", child.getFolderName());
                    obj.addProperty("createdAt", child.getCreatedAt() != null ? child.getCreatedAt().toString() : "");
                    obj.addProperty("lastModified", child.getUpdatedAt() != null ? child.getUpdatedAt().toString() : "");
                    array.add(obj);
                }
                res.setStatus("success");
                res.setMessage("Folder tree retrieved");
                res.setData(array);
            } else {
                res.setStatus("error");
                res.setMessage("No children found for parentId " + parentId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage(e.getMessage());
        }
        return res;
    }
}
