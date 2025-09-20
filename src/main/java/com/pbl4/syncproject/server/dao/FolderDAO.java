package com.pbl4.syncproject.server.dao;

import com.pbl4.syncproject.common.model.Folders;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class FolderDAO {
    private final Connection dbConnection;

    public FolderDAO(Connection dbConnection) {
        this.dbConnection = dbConnection;
    }

    // Lấy tất cả folder con của folder parentId
    public List<Folders> getChildren(int parentId) throws SQLException {
        String sql = "SELECT * FROM Folders WHERE ParentFolderID = ?";
        List<Folders> list = new ArrayList<>();

        try (PreparedStatement stm = dbConnection.prepareStatement(sql)) {
            stm.setInt(1, parentId);
            try (ResultSet rs = stm.executeQuery()) {
                while (rs.next()) {
                    Timestamp tsLastModified = rs.getTimestamp("LastModified");
                    list.add(new Folders(
                            rs.getInt("FolderID"),
                            rs.getString("FolderName"),
                            (Integer) rs.getObject("ParentFolderID"),
                            rs.getTimestamp("CreatedAt").toLocalDateTime(),
                            tsLastModified != null ? tsLastModified.toLocalDateTime() : null
                    ));
                }
            }
        }
        return list;
    }

    // Lấy folder root (ParentFolderID IS NULL)
    public Folders getRootFolder() throws SQLException {
        String sql = "SELECT * FROM Folders WHERE ParentFolderID IS NULL";

        try (PreparedStatement stm = dbConnection.prepareStatement(sql);
             ResultSet rs = stm.executeQuery()) {

            if (rs.next()) {
                Timestamp tsLastModified = rs.getTimestamp("LastModified");
                return new Folders(
                        rs.getInt("FolderID"),
                        rs.getString("FolderName"),
                        (Integer) rs.getObject("ParentFolderID"),
                        rs.getTimestamp("CreatedAt").toLocalDateTime(),
                        tsLastModified != null ? tsLastModified.toLocalDateTime() : null
                );
            }
        }
        return null;
    }
}
