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
}
