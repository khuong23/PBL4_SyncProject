package com.pbl4.syncproject.server.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseManager {
    // =====================================================
    // DATABASE ENVIRONMENT CONFIGURATION
    // Đổi USE_LOCAL = true/false để switch giữa LOCAL và CLOUD
    // =====================================================
    private static final boolean USE_LOCAL = false; // true = LOCAL, false = AZURE CLOUD

    // LOCAL Database (for testing)
    private static final String LOCAL_URL = "jdbc:mysql://127.0.0.1:3307/syncdb?serverTimezone=UTC";
    private static final String LOCAL_USER = "root";
    private static final String LOCAL_PASSWORD = "123456";

    // AZURE Cloud Database (for production)
    private static final String CLOUD_URL = "jdbc:mysql://syncserver.mysql.database.azure.com:3306/syncdb"
            + "?sslMode=REQUIRED&serverTimezone=UTC&connectTimeout=5000&socketTimeout=15000";
    private static final String CLOUD_USER = "sync_user";
    private static final String CLOUD_PASSWORD = "Syncpass123";

    // Current configuration (auto-selected based on USE_LOCAL)
    private static final String URL = USE_LOCAL ? LOCAL_URL : CLOUD_URL;
    private static final String USER = USE_LOCAL ? LOCAL_USER : CLOUD_USER;
    private static final String PASSWORD = USE_LOCAL ? LOCAL_PASSWORD : CLOUD_PASSWORD;

    // ❌ XÓA static Connection - đây là nguyên nhân chính gây lỗi!
    // private static Connection connection;

    /**
     * Phương thức này giờ đây sẽ LUÔN TẠO MỘT KẾT NỐI MỚI.
     * Điều này đảm bảo mỗi luồng client có kết nối riêng, tránh xung đột.
     * @return một đối tượng Connection mới.
     * @throws SQLException nếu không thể tạo kết nối.
     */
    public static Connection getConnection() throws SQLException {
        String env = USE_LOCAL ? "LOCAL (Testing)" : "AZURE CLOUD (Production)";
        System.out.println("✅ Creating new DB connection for environment: " + env);
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
