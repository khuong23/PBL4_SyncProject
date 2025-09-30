package com.pbl4.syncproject.server.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class DatabaseManager {
    private static final String URL = "jdbc:mysql://syncserver.mysql.database.azure.com:3306/syncdb?useSSL=true";
    private static final String USER = "sync_user";
    private static final String PASSWORD = "Syncpass123";

    private static Connection connection;

    // Mở kết nối
    public static Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(URL, USER, PASSWORD);
            System.out.println("✅ Database connected");
        }
        return connection;
    }

    // Đóng kết nối (nếu cần)
    public static void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("🔌 Database connection closed");
            }
        } catch (SQLException e) {
            System.err.println("❌ Error closing DB: " + e.getMessage());
        }
    }

    public List<String> getSubFolders(int parentFolderId) throws SQLException {
        List<String> folders = new ArrayList<>();
        String sql = "SELECT FolderName FROM Folders WHERE ParentFolderID = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, parentFolderId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    folders.add(rs.getString("FolderName"));
                }
            }
        }
        return folders;
    }

    public List<String> getFilesInFolder(int folderId) throws SQLException {
        List<String> files = new ArrayList<>();
        String sql = "SELECT FileName FROM Files WHERE FolderID = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, folderId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    files.add(rs.getString("FileName"));
                }
            }
        }
        return files;
    }
}
