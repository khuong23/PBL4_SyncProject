package com.pbl4.syncproject.server.dao;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.Request;

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

    /**
     * Kiểm tra xem người dùng có quyền cụ thể trên thư mục hay không.
     * @param userId ID người dùng
     * @param folderId ID thư mục
     * @param permission Quyền cần kiểm tra ("READ", "WRITE", "DELETE")
     * @return true nếu có quyền, false nếu không
     */
    public static boolean hasFolderPermission(int userId, int folderId, String permission) {
        // Admin (RoleID = 1) có mọi quyền
        if (isAdmin(userId)) {
            return true;
        }

        // Kiểm tra quyền cụ thể trong bảng FolderAccessControl
        String sql = "SELECT 1 FROM FolderAccessControl WHERE UserID = ? AND FolderID = ? AND Permission = ? LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, folderId);
            ps.setString(3, permission.toUpperCase()); // Đảm bảo permission viết hoa
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next(); // Có bản ghi -> có quyền
            }
        } catch (SQLException e) {
            System.err.println("Lỗi kiểm tra quyền thư mục: " + e.getMessage());
            e.printStackTrace();
            return false; // Lỗi -> coi như không có quyền
        }
    }

    /**
     * Lấy UserID từ Request (từ data của request).
     * Ưu tiên lấy userId trực tiếp, nếu không có thì lấy qua username.
     * @param req Request từ client
     * @return userId hoặc -1 nếu không xác định được
     */
    public static int getUserIdFromRequest(Request req) {
        if (req == null || req.getData() == null) return -1;
        JsonObject data = req.getData();
        
        // Ưu tiên lấy userId nếu client gửi lên
        if (data.has("userId") && !data.get("userId").isJsonNull()) {
            try {
                return data.get("userId").getAsInt();
            } catch (Exception e) {
                // Nếu là string, thử parse
                try {
                    return Integer.parseInt(data.get("userId").getAsString());
                } catch (Exception ignore) {}
            }
        }
        
        // Nếu không, thử lấy qua username (cần query DB)
        if (data.has("username") && !data.get("username").isJsonNull()) {
            try {
                return getUserIdByUsername(data.get("username").getAsString());
            } catch (Exception ignore) {}
        }
        
        return -1; // Không thể xác định user
    }
}
