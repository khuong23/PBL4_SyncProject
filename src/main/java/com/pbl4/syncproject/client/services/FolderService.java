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
        data.addProperty("username", net.getCurrentUsername()); // Thêm username
        return net.sendRequest(new Request("CREATE_FOLDER", data));
    }

    public Response createFolder(String folderName, int parentId) throws Exception {
        if (parentId <= 0) return createFolder(folderName);
        JsonObject data = new JsonObject();
        data.addProperty("folderName", folderName.trim());
        data.addProperty("parentFolderId", parentId);
        data.addProperty("username", net.getCurrentUsername()); // Thêm username
        return net.sendRequest(new Request("CREATE_FOLDER", data));
    }
    /** Đổi tên thư mục */
    public Response renameFolder(int folderId, String newName) throws Exception {
        if (folderId <= 0) throw new IllegalArgumentException("folderId không hợp lệ");
        if (newName == null || newName.trim().isEmpty()) throw new IllegalArgumentException("newName rỗng");

        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("newName", newName.trim());
        data.addProperty("username", net.getCurrentUsername()); // Thêm username
        return net.sendRequest(new Request("RENAME_FOLDER", data));
    }

    /** Xoá thư mục */
    public Response deleteFolder(int folderId, boolean recursive) throws Exception {
        if (folderId <= 0) throw new IllegalArgumentException("folderId không hợp lệ");
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("recursive", recursive); // Gửi cờ recursive lên server
        data.addProperty("username", net.getCurrentUsername()); // --- FIX LỖI: Thêm username ---
        return net.sendRequest(new Request("DELETE_FOLDER", data));
    }

    /** Di chuyển thư mục */
    public Response moveFolder(int folderId, int newParentId) throws Exception {
        if (folderId <= 0) throw new IllegalArgumentException("folderId không hợp lệ");
        if (newParentId < 0) throw new IllegalArgumentException("newParentId không hợp lệ");
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("newParentId", newParentId);
        data.addProperty("username", net.getCurrentUsername()); // Thêm username
        return net.sendRequest(new Request("MOVE_FOLDER", data));
    }
    
// ...
    /** 
     * Đồng bộ cây thư mục từ server - được gọi khi cần re-sync folder structure
     * FIX: Bây giờ thực sự lưu folder vào local DB thay vì chỉ gọi API
     */
    public void syncFolderTreeFromServer(LocalDatabaseManager localDbManager) throws Exception {
        System.out.println("📁 [FolderService] Đang đồng bộ cây thư mục từ server...");
        
        java.util.Set<Integer> processedFolders = new java.util.HashSet<>();
        processedFolders.add(1); // Root folder (ID=1) đã tồn tại
        
        // Tải đệ quy từ root
        syncFolderTreeRecursive(null, processedFolders, localDbManager);
        
        System.out.println("✅ [FolderService] Đã đồng bộ " + processedFolders.size() + " thư mục từ server");
    }
    
    /**
     * Tải folder tree đệ quy từ server và lưu vào local DB
     */
    private void syncFolderTreeRecursive(Integer parentId, java.util.Set<Integer> processedFolders, 
                                         LocalDatabaseManager localDbManager) throws Exception {
        // Gọi API FOLDER_TREE để lấy children của parentId
        JsonObject data = new JsonObject();
        if (parentId != null) {
            data.addProperty("parentId", parentId);
        }
        String username = net.getCurrentUsername();
        if (username != null && !username.isBlank()) {
            data.addProperty("username", username);
        }
        
        Response response = net.sendRequest(new Request("FOLDER_TREE", data));
        
        if (!"success".equals(response.getStatus())) {
            throw new Exception("Lỗi tải folder tree: " + response.getMessage());
        }
        
        // Parse danh sách folders
        com.google.gson.JsonArray folders = response.getData() != null && response.getData().isJsonArray() 
            ? response.getData().getAsJsonArray() : null;
            
        if (folders == null || folders.size() == 0) {
            return; // Không có children
        }
        
        // Xử lý từng folder
        for (com.google.gson.JsonElement el : folders) {
            if (!el.isJsonObject()) continue;
            JsonObject folder = el.getAsJsonObject();
            
            Integer folderId = getIntField(folder, "folderId", "FolderID");
            Integer serverParentId = getIntField(folder, "parentFolderId", "ParentFolderID");
            String folderName = getStringField(folder, "folderName", "FolderName", "name");
            
            if (folderId == null || folderName == null) {
                System.err.println("⚠️ [FolderService] Folder thiếu thông tin: " + folder);
                continue;
            }
            
            // Root folder fix
            if (folderId == 1 || (serverParentId != null && serverParentId.equals(folderId))) {
                serverParentId = null;
            }
            
            // Bỏ qua nếu đã xử lý
            if (processedFolders.contains(folderId)) {
                continue;
            }
            
            // Lưu folder vào local DB bằng upsertFolderByServerIds
            // Build localPath từ folderName (đơn giản hóa)
            String localPath = folderName; // Có thể cải thiện sau
            int localFolderId = localDbManager.upsertFolderByServerIds(folderId, serverParentId, folderName, localPath, 1);
            
            if (localFolderId > 0) {
                processedFolders.add(folderId);
                System.out.println("✅ [FolderService] Đã lưu folder: " + folderName + " (ID=" + folderId + ")");
                
                // Kiểm tra nếu có children
                Boolean hasChildren = folder.has("hasChildren") ? folder.get("hasChildren").getAsBoolean() : false;
                if (hasChildren || folderId == 1) {
                    syncFolderTreeRecursive(folderId, processedFolders, localDbManager);
                }
            } else {
                System.err.println("❌ [FolderService] Lưu folder '" + folderName + "' thất bại");
            }
        }
    }
    
    // Helper methods
    private Integer getIntField(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsInt();
                } catch (Exception ignore) {}
            }
        }
        return null;
    }
    
    private String getStringField(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsString();
                } catch (Exception ignore) {}
            }
        }
        return null;
    }
}
