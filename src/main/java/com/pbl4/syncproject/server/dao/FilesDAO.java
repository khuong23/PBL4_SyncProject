package com.pbl4.syncproject.server.dao;

import com.pbl4.syncproject.common.model.Files;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;

public class FilesDAO {
    
    public FilesDAO() {
        // Constructor không cần connection nữa
    }    public List<Files> getFilesInFolder(Connection connection, int folderId) throws SQLException {
        String sql = "SELECT * FROM Files WHERE FolderID = ?";
        try (PreparedStatement stm = connection.prepareStatement(sql)) {
            stm.setInt(1, folderId);
            try (ResultSet rs = stm.executeQuery()) {
                List<Files> list = new ArrayList<>();
                while (rs.next()) {
                    // Lấy LastModified cẩn thận, có thể null
                    java.sql.Timestamp tsLastModified = rs.getTimestamp("LastModified");
                    list.add(new Files(
                            rs.getInt("FileID"),
                            rs.getInt("FolderID"),
                            rs.getString("FileName"),
                            rs.getLong("FileSize"),
                            rs.getString("FileHash"),
                            rs.getTimestamp("CreatedAt").toLocalDateTime(),
                            tsLastModified != null ? tsLastModified.toLocalDateTime() : null
                    ));
                }
                return list;
            }
        }
    }
}
