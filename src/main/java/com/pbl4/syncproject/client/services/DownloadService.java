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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;

/**
 * Service để tải file từ server về và lưu vào thư mục đồng bộ.
 */
public class DownloadService {

    private final NetworkService networkService;
    private final LocalDatabaseManager localDbManager;
    private final String syncDirectoryPath; // ví dụ: C:\SyncData
    private final FileHashService fileHashService; // Thêm để tính hash

    public DownloadService(NetworkService networkService, LocalDatabaseManager localDbManager, String syncDirectoryPath) {
        this.networkService = networkService;
        this.localDbManager = localDbManager;
        this.syncDirectoryPath = syncDirectoryPath;
        this.fileHashService = new FileHashService(); // Khởi tạo
    }

    public void downloadAndSaveFile(FileItem fileItem) throws Exception {
        if (fileItem == null || fileItem.getRelativePath() == null) {
            throw new IllegalArgumentException("FileItem không hợp lệ hoặc thiếu relativePath.");
        }

        String relativePath = fileItem.getRelativePath();
        Path localDestination = Paths.get(syncDirectoryPath, relativePath);

        // Tạo thư mục cha nếu cần
        Files.createDirectories(localDestination.getParent());

        // Gọi NetworkService để tải file
        String originalFileName = fileItem.getFileName();
        // Nếu fileItem có icon, strip icon
        if (originalFileName.contains(" ")) {
            originalFileName = originalFileName.substring(originalFileName.indexOf(' ') + 1).trim();
        }

        System.out.println("Đang tải về: " + relativePath + " (ID: " + fileItem.getFileId() + ")");

        Response response = networkService.downloadFile(fileItem.getFileId(), originalFileName, fileItem.getFolderId());
        if (response == null || !"success".equals(response.getStatus())) {
            throw new IOException("Tải file thất bại từ server: " + (response != null ? response.getMessage() : "null response"));
        }

        JsonObject data = response.getData().getAsJsonObject();
        String base64Content = data.has("fileContent") ? data.get("fileContent").getAsString() : "";
        String serverHash = data.has("hash") ? data.get("hash").getAsString() : "";

        byte[] fileBytes = Base64.getDecoder().decode(base64Content);
        Files.write(localDestination, fileBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("Đã ghi file thành công: " + localDestination);

        // Cập nhật cache local
        updateFileCacheAfterDownload(fileItem, localDestination.toString(), serverHash);
    }

    private void updateFileCacheAfterDownload(FileItem item, String localPath, String serverHash) throws Exception {
        // FileItem contains server IDs, we need to find or create local FolderID
        int serverFolderId = item.getFolderId();
        Integer localFolderId = null;
        
        // Find local FolderID by ServerFolderID
        String sqlFindFolder = "SELECT FolderID FROM Folders WHERE ServerFolderID = ?";
        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlFindFolder)) {
            ps.setInt(1, serverFolderId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                localFolderId = rs.getInt("FolderID");
            }
        }
        
        if (localFolderId == null) {
            System.err.println("⚠️ Không tìm thấy FolderID local cho ServerFolderID=" + serverFolderId);
            // TODO: Có thể cần tạo folder cục bộ trước khi download file
            return;
        }
        
        // --- LỖI ĐÃ SỬA: Tính hash của file vừa tải và lấy thông tin chính xác ---
        File downloadedFile = new File(localPath);
        String currentHash = fileHashService.calculateFileHash(downloadedFile);
        long fileSize = downloadedFile.length();
        
        // Lấy tên file gốc (loại bỏ icon nếu có)
        String originalName = item.getFileName();
        if (originalName.contains(" ")) {
            originalName = originalName.substring(originalName.indexOf(' ') + 1).trim();
        }
        
        // Chuẩn hóa đường dẫn (dùng /)
        String relativePath = item.getRelativePath();
        if (relativePath == null) {
            relativePath = localPath.replace("\\", "/");
        }
        
        // GỌI HÀM UPSERT MỚI (thay vì SQL thủ công)
        localDbManager.upsertDownloadedFile(
                item.getFileId(),     // ServerFileID
                localFolderId,        // LocalFolderID
                originalName,         // Tên file
                fileSize,             // Kích thước chính xác
                relativePath,         // Đường dẫn tương đối
                currentHash           // Hash vừa tính (không dùng serverHash)
        );
        
        System.out.println("✅ Đã cập nhật cache sau download: " + relativePath);
        // --- KẾT THÚC SỬA LỖI ---
    }
}
