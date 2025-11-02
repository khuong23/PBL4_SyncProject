package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Files;
import com.pbl4.syncproject.common.model.Folders;
import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.dao.FilesDAO;
import com.pbl4.syncproject.server.dao.FolderDAO;
import com.pbl4.syncproject.server.dao.SyncHistoryDAO;
import com.pbl4.syncproject.server.dao.UserDAO;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.List;

/**
 * Handler để lấy danh sách các thay đổi trên server kể từ một mốc thời gian.
 * (Down-sync - Hoàn thiện)
 */
public class GetChangesSinceHandler implements RequestHandler {

    @Override
    public Response handle(Request request) {
        try (Connection conn = DatabaseManager.getConnection()) {
            JsonObject data = request.getData();
            if (data == null || !data.has("lastSyncTime")) {
                return new Response("error", "Thiếu 'lastSyncTime'", null);
            }
            
            // 1. Lấy thông tin request
            int userId = UserDAO.getUserIdFromRequest(request);
            if (userId <= 0) {
                return new Response("error", "Không xác định được người dùng", null);
            }
            
            long timestampLong = data.get("lastSyncTime").getAsLong();
            Timestamp lastSyncTime = new Timestamp(timestampLong);

            // 2. Lấy danh sách thay đổi từ SyncHistory (những thay đổi KHÔNG do user này tạo)
            List<SyncHistoryDAO.HistoryItem> historyItems = SyncHistoryDAO.getChangesSince(userId, lastSyncTime);
            
            JsonArray changes = new JsonArray();
            FolderDAO folderDAO = new FolderDAO(conn); // Dùng cho getFolderById

            // 3. Xử lý từng thay đổi
            for (SyncHistoryDAO.HistoryItem item : historyItems) {
                // Kiểm tra quyền: User này có quyền đọc thư mục chứa file/folder bị thay đổi không?
                int folderToCheck = (item.fileId != null) 
                                    ? FilesDAO.getFileById(item.fileId).getFolderId() 
                                    : item.folderId;
                                    
                if (folderToCheck > 0 && !UserDAO.hasFolderPermission(userId, folderToCheck, "READ")) {
                    continue; // Bỏ qua thay đổi này nếu user không có quyền xem
                }
                
                // Xây dựng đối tượng JSON cho thay đổi
                JsonObject change = new JsonObject();
                change.addProperty("syncTime", item.syncTime.getTime());

                switch (item.action) {
                    case SyncHistoryDAO.ACTION_UPLOAD_FILE:
                    case SyncHistoryDAO.ACTION_UPDATE_FILE:
                        Files file = FilesDAO.getFileById(item.fileId);
                        if (file != null) {
                            change.addProperty("type", "FILE_MODIFIED");
                            change.add("data", fileToJson(file, folderDAO));
                            changes.add(change);
                        }
                        break;
                    
                    case SyncHistoryDAO.ACTION_DELETE_FILE:
                        change.addProperty("type", "FILE_DELETED");
                        change.add("data", fileIdToJson(item.fileId, item.folderId));
                        changes.add(change);
                        break;
                        
                    case SyncHistoryDAO.ACTION_CREATE_FOLDER:
                        Folders folder = folderDAO.getFolderById(item.folderId);
                        if (folder != null) {
                            change.addProperty("type", "FOLDER_MODIFIED");
                            change.add("data", folderToJson(folder, folderDAO));
                            changes.add(change);
                        }
                        break;
                        
                    case SyncHistoryDAO.ACTION_DELETE_FOLDER:
                        change.addProperty("type", "FOLDER_DELETED");
                        change.add("data", folderIdToJson(item.folderId));
                        changes.add(change);
                        break;
                }
            }
            
            JsonObject responseData = new JsonObject();
            responseData.add("changes", changes);
            responseData.addProperty("serverTime", System.currentTimeMillis());

            return new Response("success", "Lấy danh sách thay đổi thành công", responseData);

        } catch (Exception e) {
            e.printStackTrace();
            return new Response("error", "Lỗi server khi lấy danh sách thay đổi: " + e.getMessage(), null);
        }
    }
    
    // --- CÁC HÀM HELPER CHUYỂN ĐỔI SANG JSON ---

    private JsonObject fileToJson(Files file, FolderDAO folderDAO) {
        JsonObject obj = new JsonObject();
        obj.addProperty("FileID", file.getFileId());
        obj.addProperty("FolderID", file.getFolderId());
        obj.addProperty("FileName", file.getFileName());
        obj.addProperty("FileHash", file.getFileHash());
        obj.addProperty("FileSize", file.getSize());
        if (file.getUpdatedAt() != null) {
            obj.addProperty("LastModified", file.getUpdatedAt().toString());
        }
        // Thêm relativePath để client biết lưu file ở đâu
        try {
            String relativePath = folderDAO.getRelativePath(file.getFolderId());
            if (!relativePath.isEmpty()) {
                obj.addProperty("relativePath", relativePath + "/" + file.getFileName());
            } else {
                obj.addProperty("relativePath", file.getFileName());
            }
        } catch (Exception e) {
            obj.addProperty("relativePath", file.getFileName());
        }
        return obj;
    }

    private JsonObject fileIdToJson(Integer fileId, Integer folderId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("FileID", fileId);
        obj.addProperty("FolderID", folderId);
        return obj;
    }

    private JsonObject folderToJson(Folders folder, FolderDAO folderDAO) {
        JsonObject obj = new JsonObject();
        obj.addProperty("FolderID", folder.getFolderId());
        obj.addProperty("ParentFolderID", folder.getParentId());
        obj.addProperty("FolderName", folder.getFolderName());
        obj.addProperty("hasChildren", folder.getHasChildren());
        // Thêm relativePath
        try {
            String relativePath = folderDAO.getRelativePath(folder.getFolderId());
            obj.addProperty("relativePath", relativePath);
        } catch (Exception e) {
            obj.addProperty("relativePath", folder.getFolderName());
        }
        return obj;
    }
    
    private JsonObject folderIdToJson(Integer folderId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("FolderID", folderId);
        return obj;
    }
}
