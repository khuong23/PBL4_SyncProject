package com.pbl4.syncproject.server.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseManager {
    private static final String URL = "jdbc:mysql://127.0.0.1:3307/syncdb";
    private static final String USER = "sync_user";
    private static final String PASSWORD = "baokhuong2332";

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
}
