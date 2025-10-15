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
}
