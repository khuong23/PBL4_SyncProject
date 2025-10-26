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
        String sql = "SELECT f.*, " +
                     "(EXISTS (SELECT 1 FROM Folders fc WHERE fc.ParentFolderID = f.FolderID)) AS hasChildren " +
                     "FROM Folders f WHERE f.ParentFolderID = ? ORDER BY f.FolderName ASC";
        List<Folders> list = new ArrayList<>();

        try (PreparedStatement stm = dbConnection.prepareStatement(sql)) {
            stm.setInt(1, parentId);
            try (ResultSet rs = stm.executeQuery()) {
                while (rs.next()) {
                    Timestamp tsLastModified = rs.getTimestamp("LastModified");
                    Folders folder = new Folders(
                            rs.getInt("FolderID"),
                            rs.getString("FolderName"),
                            (Integer) rs.getObject("ParentFolderID"),
                            rs.getTimestamp("CreatedAt").toLocalDateTime(),
                            tsLastModified != null ? tsLastModified.toLocalDateTime() : null
                    );
                    folder.setHasChildren(rs.getBoolean("hasChildren"));
                    list.add(folder);
                }
            }
        }
        return list;
    }

    // Lấy tất cả folder root (ParentFolderID IS NULL) - cho lazy loading
    public List<Folders> getRootFolders() throws SQLException {
        String sql = "SELECT f.*, " +
                     "(EXISTS (SELECT 1 FROM Folders fc WHERE fc.ParentFolderID = f.FolderID)) AS hasChildren " +
                     "FROM Folders f WHERE f.ParentFolderID IS NULL ORDER BY f.FolderName ASC";
        List<Folders> list = new ArrayList<>();

        try (PreparedStatement stm = dbConnection.prepareStatement(sql)) {
            try (ResultSet rs = stm.executeQuery()) {
                while (rs.next()) {
                    Timestamp tsLastModified = rs.getTimestamp("LastModified");
                    Folders folder = new Folders(
                            rs.getInt("FolderID"),
                            rs.getString("FolderName"),
                            (Integer) rs.getObject("ParentFolderID"),
                            rs.getTimestamp("CreatedAt").toLocalDateTime(),
                            tsLastModified != null ? tsLastModified.toLocalDateTime() : null
                    );
                    folder.setHasChildren(rs.getBoolean("hasChildren"));
                    list.add(folder);
                }
            }
        }
        return list;
    }

    // Kiểm tra xem một thư mục có con hay không - cho lazy loading
    public boolean hasChildren(int folderId) throws SQLException {
        String sql = "SELECT 1 FROM Folders WHERE ParentFolderID = ? LIMIT 1";
        try (PreparedStatement stm = dbConnection.prepareStatement(sql)) {
            stm.setInt(1, folderId);
            try (ResultSet rs = stm.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Lấy folder root (ParentFolderID IS NULL) - giữ để tương thích ngược
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
