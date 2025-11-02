package com.pbl4.syncproject.server.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class SyncHistoryDAO {

    // Định nghĩa các loại hành động
    public static final String ACTION_UPLOAD_FILE = "UPLOAD_FILE";
    public static final String ACTION_UPDATE_FILE = "UPDATE_FILE";
    public static final String ACTION_DELETE_FILE = "DELETE_FILE";
    public static final String ACTION_CREATE_FOLDER = "CREATE_FOLDER";
    public static final String ACTION_DELETE_FOLDER = "DELETE_FOLDER";

    /**
     * Ghi lại một hành động vào lịch sử đồng bộ
     */
    public static void logAction(int userId, String action, Integer fileId, Integer folderId) {
        String sql = "INSERT INTO SyncHistory (UserID, FileID, FolderID, Action, Status, SyncTime) "
                   + "VALUES (?, ?, ?, ?, 'SUCCESS', NOW())";
        
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, userId);
            
            if (fileId != null) ps.setInt(2, fileId);
            else ps.setNull(2, java.sql.Types.INTEGER);
            
            if (folderId != null) ps.setInt(3, folderId);
            else ps.setNull(3, java.sql.Types.INTEGER);
            
            ps.setString(4, action);
            
            ps.executeUpdate();
            
        } catch (SQLException e) {
            System.err.println("Lỗi khi ghi SyncHistory: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Lấy các thay đổi kể từ một thời điểm, NGOẠI TRỪ các thay đổi của chính user đó
     */
    public static List<HistoryItem> getChangesSince(int requestingUserId, Timestamp lastSyncTime) throws SQLException {
        List<HistoryItem> changes = new ArrayList<>();
        // Lấy tất cả thay đổi KHÔNG PHẢI do user này thực hiện
        String sql = "SELECT SyncID, UserID, FileID, FolderID, Action, SyncTime "
                   + "FROM SyncHistory "
                   + "WHERE SyncTime > ? AND UserID != ? "
                   + "ORDER BY SyncTime ASC";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setTimestamp(1, lastSyncTime);
            ps.setInt(2, requestingUserId);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    changes.add(new HistoryItem(
                        rs.getInt("SyncID"),
                        rs.getInt("UserID"),
                        (Integer) rs.getObject("FileID"),
                        (Integer) rs.getObject("FolderID"),
                        rs.getString("Action"),
                        rs.getTimestamp("SyncTime")
                    ));
                }
            }
        }
        return changes;
    }

    // Class nội bộ để chứa kết quả
    public static class HistoryItem {
        public final int syncId;
        public final int userId;
        public final Integer fileId;
        public final Integer folderId;
        public final String action;
        public final Timestamp syncTime;

        public HistoryItem(int syncId, int userId, Integer fileId, Integer folderId, String action, Timestamp syncTime) {
            this.syncId = syncId;
            this.userId = userId;
            this.fileId = fileId;
            this.folderId = folderId;
            this.action = action;
            this.syncTime = syncTime;
        }
    }
}
