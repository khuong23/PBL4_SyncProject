package com.pbl4.syncproject.client.services;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

public class FolderService {
    private final NetworkService net;

    public FolderService(NetworkService net) {
        this.net = net;
    }

    public Response createFolder(String folderName) throws Exception {
        if (folderName == null || folderName.trim().isEmpty())
            throw new IllegalArgumentException("folderName rỗng");
        JsonObject data = new JsonObject();
        data.addProperty("folderName", folderName.trim());
        return net.sendRequest(new Request("CREATE_FOLDER", data));
    }

    public Response createFolder(String folderName, int parentId) throws Exception {
        if (parentId <= 0) return createFolder(folderName);
        JsonObject data = new JsonObject();
        data.addProperty("folderName", folderName.trim());
        data.addProperty("parentFolderId", parentId);
        return net.sendRequest(new Request("CREATE_FOLDER", data));
    }
    /** Đổi tên thư mục */
    public Response renameFolder(int folderId, String newName) throws Exception {
        if (folderId <= 0) throw new IllegalArgumentException("folderId không hợp lệ");
        if (newName == null || newName.trim().isEmpty()) throw new IllegalArgumentException("newName rỗng");

        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("newName", newName.trim());
        return net.sendRequest(new Request("RENAME_FOLDER", data));
    }

    /** Xoá thư mục */
    public Response deleteFolder(int folderId) throws Exception {
        if (folderId <= 0) throw new IllegalArgumentException("folderId không hợp lệ");
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        return net.sendRequest(new Request("DELETE_FOLDER", data));
    }

    /** Di chuyển thư mục */
    public Response moveFolder(int folderId, int newParentId) throws Exception {
        if (folderId <= 0) throw new IllegalArgumentException("folderId không hợp lệ");
        if (newParentId < 0) throw new IllegalArgumentException("newParentId không hợp lệ");
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("newParentId", newParentId);
        return net.sendRequest(new Request("MOVE_FOLDER", data));
    }
}
