package com.pbl4.syncproject.client.services;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.common.jsonhandler.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Base64;

/**
 * Service để tải file từ server về và lưu vào thư mục đồng bộ.
 */
public class DownloadService {

    private final NetworkService networkService;
    private final LocalDatabaseManager localDbManager;
    private final String syncDirectoryPath; // ví dụ: C:\SyncData

    public DownloadService(NetworkService networkService, LocalDatabaseManager localDbManager, String syncDirectoryPath) {
        this.networkService = networkService;
        this.localDbManager = localDbManager;
        this.syncDirectoryPath = syncDirectoryPath;
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

        Response response = networkService.downloadFile(originalFileName, fileItem.getFolderId());
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
        String sql = "INSERT OR REPLACE INTO Files (FileID, FolderID, FileName, FileSize, LocalPath, LastKnownHash, SyncStatus) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'SYNCED')";

        try (Connection conn = localDbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, item.getFileId());
            ps.setInt(2, item.getFolderId());
            String originalName = item.getFileName();
            if (originalName.contains(" ")) originalName = originalName.substring(originalName.indexOf(' ') + 1).trim();
            ps.setString(3, originalName);
            // FileSize unknown here - set 0 if missing
            ps.setLong(4, 0L);
            ps.setString(5, localPath.replace("\\", "/"));
            ps.setString(6, serverHash);

            ps.executeUpdate();
        }
    }
}
