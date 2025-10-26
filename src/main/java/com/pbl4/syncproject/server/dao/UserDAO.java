package com.pbl4.syncproject.server.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class UserDAO {

    private static final String SQL_CHECK =
            "SELECT 1 FROM Users WHERE Username = ? AND PasswordHash = ? LIMIT 1";

    private UserDAO() {}

    /** So khớp trực tiếp username + password (plain/hash) trong DB. */
    public static boolean checkLogin(String username, String password) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_CHECK)) {

            ps.setString(1, username);
            ps.setString(2, password); // TODO: thay bằng verify hash sau
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // log tùy ý
            e.printStackTrace();
            return false;
        }
    }

    /** Trả về UserID theo username, hoặc -1 nếu không tìm thấy */
    public static int getUserIdByUsername(String username) {
        if (username == null || username.isBlank()) return -1;
        final String sql = "SELECT UserID FROM Users WHERE Username = ? LIMIT 1";
        try (java.sql.Connection conn = DatabaseManager.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (java.sql.SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    /** Kiểm tra user có phải Admin (RoleID == 1) hay không */
    public static boolean isAdmin(int userId) {
        if (userId <= 0) return false;
        final String sql = "SELECT RoleID FROM Users WHERE UserID = ? LIMIT 1";
        try (java.sql.Connection conn = DatabaseManager.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (java.sql.SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}
