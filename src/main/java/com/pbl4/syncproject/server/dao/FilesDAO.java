package com.pbl4.syncproject.server.dao;

import com.pbl4.syncproject.common.model.Files;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class FilesDAO {

    private static final String COLS =
            "FileID, FolderID, FileName, FileSize, FileHash, CreatedAt, LastModified";

    private static final String SQL_GET_IN_FOLDER =
            "SELECT " + COLS + " FROM Files WHERE FolderID = ? ORDER BY FileName ASC";

    private FilesDAO() {}

    public static List<Files> getFilesInFolder(int folderId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_GET_IN_FOLDER)) {

            ps.setInt(1, folderId);

            try (ResultSet rs = ps.executeQuery()) {
                List<Files> out = new ArrayList<>();
                while (rs.next()) {
                    Timestamp created = rs.getTimestamp("CreatedAt");     // NOT NULL theo schema
                    Timestamp last    = rs.getTimestamp("LastModified");  // có thể NULL
                    out.add(new Files(
                            rs.getInt("FileID"),
                            rs.getInt("FolderID"),
                            rs.getString("FileName"),
                            rs.getLong("FileSize"),
                            rs.getString("FileHash"),
                            created != null ? created.toLocalDateTime() : null,
                            last != null ? last.toLocalDateTime() : null
                    ));
                }
                return out;
            }
        }
    }

    /**
     * BƯỚC 7.2: Lấy file theo tên và folder (để kiểm tra xung đột)
     */
    public static Files getFileByNameAndFolder(String fileName, int folderId) throws SQLException {
        String sql = "SELECT " + COLS + " FROM Files WHERE FileName = ? AND FolderID = ? LIMIT 1";
        
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setString(1, fileName);
            ps.setInt(2, folderId);
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp created = rs.getTimestamp("CreatedAt");
                    Timestamp last = rs.getTimestamp("LastModified");
                    return new Files(
                            rs.getInt("FileID"),
                            rs.getInt("FolderID"),
                            rs.getString("FileName"),
                            rs.getLong("FileSize"),
                            rs.getString("FileHash"),
                            created != null ? created.toLocalDateTime() : null,
                            last != null ? last.toLocalDateTime() : null
                    );
                }
                return null; // File không tồn tại
            }
        }
    }
}
