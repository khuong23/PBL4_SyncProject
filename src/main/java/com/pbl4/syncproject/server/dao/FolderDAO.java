package com.pbl4.syncproject.server.dao;

import com.pbl4.syncproject.common.model.Folders;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class FolderDAO {

    public FolderDAO() {
        // Constructor không cần connection nữa
    }

    // Lấy tất cả folder con của folder parentId
    public List<Folders> getChildren(Connection connection, int parentId) throws SQLException {
        System.out.println("DEBUG FolderDAO: Getting children for parentId: " + parentId + ", connection: " + connection);
        String sql = "SELECT * FROM Folders WHERE ParentFolderID = ?";
        List<Folders> list = new ArrayList<>();

        try (PreparedStatement stm = connection.prepareStatement(sql)) {
            System.out.println("DEBUG FolderDAO: PreparedStatement created successfully");
            stm.setInt(1, parentId);
            System.out.println("DEBUG FolderDAO: Parameter set, executing query...");
            try (ResultSet rs = stm.executeQuery()) {
                System.out.println("DEBUG FolderDAO: Query executed, processing results...");
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
                System.out.println("DEBUG FolderDAO: Found " + list.size() + " folders");
            }
        } catch (SQLException e) {
            System.out.println("DEBUG FolderDAO ERROR: " + e.getMessage());
            throw e;
        }
        return list;
    }

    // Lấy folder root (ParentFolderID IS NULL)
    public Folders getRootFolder(Connection connection) throws SQLException {
        String sql = "SELECT * FROM Folders WHERE ParentFolderID IS NULL";

        try (PreparedStatement stm = connection.prepareStatement(sql);
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
