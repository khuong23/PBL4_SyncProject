package com.pbl4.syncproject.server.dao;

import com.pbl4.syncproject.common.model.Folders;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class FolderDAO {

    private static final String COLS =
            "FolderID, FolderName, ParentFolderID, CreatedAt, LastModified";

    private static final String SQL_CHILDREN =
            "SELECT " + COLS + " FROM Folders WHERE ParentFolderID = ? ORDER BY FolderName ASC";

    private static final String SQL_ROOT_ONE =
            "SELECT " + COLS + "FROM Folders WHERE FolderID = 1 LIMIT 1";

    private FolderDAO() {}

    /** Lấy tất cả folder con của parentId (dùng pool, thread-safe). */
    public static List<Folders> getChildren(int parentId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_CHILDREN)) {
            ps.setInt(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Folders> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    /** Lấy root folder (FolderID == 1). Trả về 1 folder, hoặc null nếu chưa có. */
    public static Folders getRootFolder() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_ROOT_ONE);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? map(rs) : null;
        }
    }

    // ---- Mapper ----
    private static Folders map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("CreatedAt");     // thường NOT NULL
        Timestamp last    = rs.getTimestamp("LastModified");  // có thể NULL
        Integer parentId  = (Integer) rs.getObject("ParentFolderID"); // giữ được NULL

        return new Folders(
                rs.getInt("FolderID"),
                rs.getString("FolderName"),
                parentId,
                created != null ? created.toLocalDateTime() : null,
                last != null ? last.toLocalDateTime() : null
        );
    }
}
