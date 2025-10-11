package com.pbl4.syncproject.server.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class UserDAO {

    public UserDAO() {
        // Constructor không cần connection nữa  
    }

    public boolean checkLogin(Connection connection, String username, String password) {
        String sql = "SELECT * FROM Users WHERE Username=? AND PasswordHash=?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, password); // production: hash password
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
