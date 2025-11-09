package com.pbl4.syncproject.server.utils;

import com.pbl4.syncproject.common.storage.StorageManager;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;

/**
 * Utility để đồng bộ lại FileHash trong DB với file vật lý
 * Chạy một lần khi phát hiện DB và file không khớp
 */
public class SyncDatabaseHashUtil {

    public static void main(String[] args) {
        System.out.println("=== Bắt đầu đồng bộ FileHash trong DB ===");
        
        try (Connection conn = DatabaseManager.getConnection()) {
            // Lấy tất cả file từ DB
            String sql = "SELECT f.FileID, f.FolderID, f.FileName, f.FileHash, f.Version " +
                        "FROM Files f ORDER BY f.FileID";
            
            int totalFiles = 0;
            int updatedFiles = 0;
            int errorFiles = 0;
            
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                
                while (rs.next()) {
                    totalFiles++;
                    int fileId = rs.getInt("FileID");
                    int folderId = rs.getInt("FolderID");
                    String fileName = rs.getString("FileName");
                    String dbHash = rs.getString("FileHash");
                    int version = rs.getInt("Version");
                    
                    try {
                        // Tìm file vật lý
                        Path folderPath = StorageManager.getInstance().resolveFolderPathFromDb(conn, folderId);
                        Path filePath = folderPath.resolve(fileName).normalize();
                        
                        if (!Files.exists(filePath)) {
                            System.err.println("❌ File không tồn tại: " + filePath + " (FileID=" + fileId + ")");
                            errorFiles++;
                            continue;
                        }
                        
                        // Tính hash từ file vật lý
                        byte[] fileBytes = Files.readAllBytes(filePath);
                        String actualHash = computeSHA256(fileBytes);
                        
                        // So sánh với DB
                        if (dbHash == null || !dbHash.equals(actualHash)) {
                            System.out.println("🔧 Đồng bộ FileID=" + fileId + " (" + fileName + ")");
                            System.out.println("   DB hash:   " + (dbHash != null ? dbHash : "NULL"));
                            System.out.println("   File hash: " + actualHash);
                            
                            // Cập nhật DB
                            try (PreparedStatement psUpdate = conn.prepareStatement(
                                    "UPDATE Files SET FileHash = ? WHERE FileID = ?")) {
                                psUpdate.setString(1, actualHash);
                                psUpdate.setInt(2, fileId);
                                psUpdate.executeUpdate();
                                updatedFiles++;
                                System.out.println("   ✅ Đã cập nhật!");
                            }
                        }
                        
                    } catch (Exception e) {
                        System.err.println("❌ Lỗi xử lý FileID=" + fileId + ": " + e.getMessage());
                        errorFiles++;
                    }
                }
            }
            
            System.out.println("\n=== Kết quả ===");
            System.out.println("Tổng số file: " + totalFiles);
            System.out.println("Đã cập nhật:  " + updatedFiles);
            System.out.println("Lỗi:          " + errorFiles);
            System.out.println("Không đổi:    " + (totalFiles - updatedFiles - errorFiles));
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi nghiêm trọng: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static String computeSHA256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
