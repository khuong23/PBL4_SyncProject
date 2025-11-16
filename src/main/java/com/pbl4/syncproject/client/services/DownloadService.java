package com.pbl4.syncproject.client.services;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.common.jsonhandler.Response;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

/**
 * Service để tải file từ server về và lưu vào thư mục đồng bộ.
 */
public class DownloadService {

    private final NetworkService networkService;
    private final LocalDatabaseManager localDbManager;
    private final String syncDirectoryPath; // ví dụ: C:\SyncData
    private final FileHashService fileHashService; // Thêm để tính hash
    private final FileWatcherService fileWatcher; // Thêm để pause khi ghi file
    private final FolderService folderService; // Thêm để re-sync folder tree khi cần

    public DownloadService(NetworkService networkService, LocalDatabaseManager localDbManager, String syncDirectoryPath, FileWatcherService fileWatcher, FolderService folderService) {
        this.networkService = networkService;
        this.localDbManager = localDbManager;
        this.syncDirectoryPath = syncDirectoryPath;
        this.fileHashService = new FileHashService(); // Khởi tạo
        this.fileWatcher = fileWatcher; // Lưu reference
        this.folderService = folderService; // Lưu reference
    }

    public void downloadAndSaveFile(FileItem fileItem) throws Exception {
        if (fileItem == null || fileItem.getFileId() <= 0) {
            throw new IllegalArgumentException("FileItem không hợp lệ hoặc fileId=0.");
        }

        // Giữ lại thông tin gốc từ FileItem (có thể không hoàn chỉnh từ change event)
        final String originalFileName = fileItem.getFileName();
        final String originalRelativePath = fileItem.getRelativePath();

        System.out.println("Đang tải về: " + originalFileName + " (ID: " + fileItem.getFileId() + ")");

        // 1. GỌI API TẢI FILE TRƯỚC (để lấy dữ liệu đáng tin cậy)
        Response response = networkService.downloadFile(fileItem.getFileId(), originalFileName, 0); // folderId=0 vì chưa biết
        if (response == null || !"success".equals(response.getStatus())) {
            throw new IOException("Tải file thất bại từ server: " + (response != null ? response.getMessage() : "null response"));
        }

        // 2. LẤY DỮ LIỆU ĐÁNG TIN CẬY TỪ RESPONSE API
        JsonObject data = response.getData().getAsJsonObject();
        String base64Content = data.has("fileContent") ? data.get("fileContent").getAsString() : "";
        String serverHash = data.has("hash") ? data.get("hash").getAsString() : "";
        int serverVersion = data.has("version") ? data.get("version").getAsInt() : 1;
        
        // QUAN TRỌNG: Lấy serverFolderId từ response (ĐÁNG TIN CẬY), không dùng fileItem.getFolderId()
        int serverFolderId = data.has("folderId") ? data.get("folderId").getAsInt() : fileItem.getFolderId();
        
        // DEBUG: Kiểm tra xem server có trả về version không
        System.out.println("🔍 [DOWNLOAD] Server response - hash: " + (serverHash.isEmpty() ? "EMPTY" : "OK") + ", version: " + serverVersion);

        byte[] fileBytes = Base64.getDecoder().decode(base64Content);
        
        // 3. LẤY LOCAL FOLDER ID (DỰA TRÊN DỮ LIỆU TỪ API)
        Integer localFolderId = localDbManager.getLocalFolderIdByServerId(serverFolderId);
        
        if (localFolderId == null) {
            System.err.println("⚠️ Không tìm thấy mapping local cho ServerFolderID=" + serverFolderId + " (cho file " + originalFileName + "). Thử đồng bộ folder tree...");
            // FIX: Thử re-sync cây thư mục với LocalDatabaseManager
            try {
                folderService.syncFolderTreeFromServer(localDbManager);
            } catch (Exception e) {
                System.err.println("Lỗi khi re-sync folder tree: " + e.getMessage());
            }
            // Thử lại lần nữa
            localFolderId = localDbManager.getLocalFolderIdByServerId(serverFolderId);
            if (localFolderId == null) {
                System.err.println("⛔ Lỗi nghiêm trọng: Không thể lấy localFolderId cho " + serverFolderId + " sau khi re-sync.");
                throw new IOException("Không thể map ServerFolderID " + serverFolderId + " về local folder cho file " + originalFileName);
            }
        }
        // === Đã có localFolderId hợp lệ ===
        
        // 4. XÁC ĐỊNH ĐƯỜNG DẪN ĐÍCH
        String folderPath = localDbManager.buildRelativePath(localFolderId);
        if (folderPath == null) {
            throw new IOException("Không thể lấy đường dẫn cho localFolderId: " + localFolderId);
        }
        
        // Xây dựng relativePath từ folderPath + fileName
        String fileName = originalFileName;
        if (fileName.contains(" ")) {
            fileName = fileName.substring(fileName.indexOf(' ') + 1).trim();
        }
        
        String relativePath = (originalRelativePath != null && !originalRelativePath.isEmpty())
                ? originalRelativePath
                : Paths.get(folderPath, fileName).toString().replace("\\", "/");
        
        // Cập nhật lại FileItem với thông tin chính xác
        fileItem.setRelativePath(relativePath);
        
        Path localDestination = Paths.get(syncDirectoryPath, relativePath);
        
        // Tạo thư mục cha nếu cần
        Files.createDirectories(localDestination.getParent());
        
        // 5. GHI FILE TRONG CHẾ ĐỘ MUTED
        final Path finalPath = localDestination;
        fileWatcher.runMuted(() -> {
            try {
                Files.write(finalPath, fileBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("Lỗi ghi file: " + e.getMessage(), e);
            }
        });
        
        // 6. CẬP NHẬT CACHE SAU KHI GHI FILE (với localFolderId từ response API)
        updateFileCacheAfterDownload(fileItem, localDestination.toString(), serverHash, serverVersion, localFolderId);

        System.out.println("Đã ghi file thành công: " + localDestination);
    }

    private void updateFileCacheAfterDownload(FileItem item, String localPath, String serverHash, int serverVersion, int localFolderId) throws Exception {
        // Nhận localFolderId từ tham số (đã được xác minh trước khi gọi hàm này)
        
        // --- LỖI ĐÃ SỬA: Tính hash của file vừa tải và lấy thông tin chính xác ---
        File downloadedFile = new File(localPath);
        String currentHash = fileHashService.calculateFileHash(downloadedFile);
        long fileSize = downloadedFile.length();
        
        // Lấy tên file gốc (loại bỏ icon nếu có)
        String originalName = item.getFileName();
        if (originalName.contains(" ")) {
            originalName = originalName.substring(originalName.indexOf(' ') + 1).trim();
        }
        
        // Chuẩn hóa đường dẫn (dùng /) - PHẢI dùng relativePath từ item
        String relativePath = item.getRelativePath();
        if (relativePath == null || relativePath.isEmpty()) {
            // Fallback: Tính relative path từ absolute path
            Path syncDir = Paths.get(syncDirectoryPath);
            Path filePath = Paths.get(localPath);
            relativePath = syncDir.relativize(filePath).toString().replace("\\", "/");
        } else {
            // Đảm bảo dấu / thống nhất
            relativePath = relativePath.replace("\\", "/");
        }
        
        System.out.println("🔍 [DOWNLOAD DEBUG] LocalPath: " + localPath);
        System.out.println("🔍 [DOWNLOAD DEBUG] RelativePath: " + relativePath);
        System.out.println("🔍 [DOWNLOAD DEBUG] Hash: " + currentHash);
        System.out.println("🔍 [DOWNLOAD DEBUG] Version: " + serverVersion);
        
        // GỌI HÀM UPSERT với VERSION (dùng overload 7 tham số)
        localDbManager.upsertDownloadedFile(
                item.getFileId(),     // ServerFileID
                localFolderId,        // LocalFolderID
                originalName,         // Tên file
                fileSize,             // Kích thước chính xác
                relativePath,         // Đường dẫn tương đối
                currentHash,          // Hash vừa tính (không dùng serverHash)
                serverVersion         // VERSION từ server
        );
        
        System.out.println("✅ Đã cập nhật cache sau download: " + relativePath + " (v" + serverVersion + ")");
        // --- KẾT THÚC SỬA LỖI ---
    }
}
