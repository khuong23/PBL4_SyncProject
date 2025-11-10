package com.pbl4.syncproject.server.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class SyncHistoryDAO {

    // Các loại hành động
    public static final String ACTION_UPLOAD_FILE   = "UPLOAD_FILE";
    public static final String ACTION_UPDATE_FILE   = "UPDATE_FILE";
    public static final String ACTION_DELETE_FILE   = "DELETE_FILE";
    public static final String ACTION_CREATE_FOLDER = "CREATE_FOLDER";
    public static final String ACTION_DELETE_FOLDER = "DELETE_FOLDER";

    private static final String INSERT_SQL =
            "INSERT INTO SyncHistory (UserID, FileID, FolderID, Action, Status, SyncTime) " +
                    "VALUES (?, ?, ?, ?, 'SUCCESS', NOW())";

    /**
     * Ghi một hành động vào bảng SyncHistory bằng chính Connection đang mở (cùng transaction).
     */
    public static void logAction(Connection conn,
                                 int userId,
                                 String action,
                                 Integer fileId,
                                 Integer folderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            ps.setInt(1, userId);
            if (fileId != null)  ps.setInt(2, fileId);   else ps.setNull(2, Types.INTEGER);
            if (folderId != null) ps.setInt(3, folderId); else ps.setNull(3, Types.INTEGER);
            ps.setString(4, action);
            ps.executeUpdate();
        }
    }

    /**
     * Lấy các thay đổi kể từ một thời điểm, NGOẠI TRỪ các thay đổi của chính user đó
     * Dùng cùng Connection để đảm bảo đọc trong cùng phiên/tx nếu cần.
     */
    public static List<HistoryItem> getChangesSince(Connection conn,
                                                    int requestingUserId,
                                                    Timestamp lastSyncTime) throws SQLException {
        List<HistoryItem> changes = new ArrayList<>();

        final String sql =
                "SELECT SyncID, UserID, FileID, FolderID, Action, SyncTime " +
                        "FROM SyncHistory " +
                        "WHERE SyncTime > ? AND UserID != ? " +
                        "ORDER BY SyncTime ASC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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

        public HistoryItem(int syncId, int userId, Integer fileId, Integer folderId,
                           String action, Timestamp syncTime) {
            this.syncId = syncId;
            this.userId = userId;
            this.fileId = fileId;
            this.folderId = folderId;
            this.action = action;
            this.syncTime = syncTime;
        }
    }
}
