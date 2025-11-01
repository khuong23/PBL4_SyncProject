package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Files;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler để lấy danh sách các thay đổi trên server kể từ một mốc thời gian.
 * (Bước 6 - Down-sync)
 */
public class GetChangesSinceHandler implements RequestHandler {

    @Override
    public Response handle(Request request) {
        try {
            JsonObject data = request.getData();
            // TODO: Lấy userId từ session khi có authentication
            // String userId = request.getSession().getUserId();
            
            long timestampLong = data.get("lastSyncTime").getAsLong();
            Timestamp lastSyncTime = new Timestamp(timestampLong);

            // TODO: Bước 6.3 - Gọi FilesDAO.getFilesModifiedSince() khi đã implement
            // List<Files> changedFiles = FilesDAO.getFilesModifiedSince(userId, lastSyncTime);
            
            // Tạm thời trả về danh sách rỗng
            List<Files> changedFiles = new ArrayList<>();

            // 3. Xây dựng JSON trả về
            JsonArray changes = new JsonArray();
            
            // Thêm file vào danh sách
            for (Files file : changedFiles) {
                JsonObject change = new JsonObject();
                // TODO: Kiểm tra isDeleted khi Files model có trường này
                change.addProperty("type", "FILE_MODIFIED");
                change.add("data", fileToJson(file));
                changes.add(change);
            }
            
            // TODO: Thêm logic cho folders khi implement getFoldersModifiedSince

            JsonObject responseData = new JsonObject();
            responseData.add("changes", changes);
            responseData.addProperty("serverTime", System.currentTimeMillis());

            return new Response("success", "Lấy danh sách thay đổi thành công", responseData);

        } catch (Exception e) {
            e.printStackTrace();
            return new Response("error", "Lỗi server khi lấy danh sách thay đổi: " + e.getMessage(), null);
        }
    }
    
    /**
     * Chuyển Files object sang JSON
     */
    private JsonObject fileToJson(Files file) {
        JsonObject obj = new JsonObject();
        obj.addProperty("FileID", file.getFileId());
        obj.addProperty("FolderID", file.getFolderId());
        obj.addProperty("FileName", file.getFileName());
        obj.addProperty("FileHash", file.getFileHash());
        obj.addProperty("FileSize", file.getSize());
        if (file.getUpdatedAt() != null) {
            obj.addProperty("LastModified", file.getUpdatedAt().toString());
        }
        return obj;
    }
}
