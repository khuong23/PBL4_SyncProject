package com.pbl4.syncproject.server.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;

public final class DatabaseManager {
    private static final HikariDataSource DS;

    static {
        // Có thể override qua biến môi trường, còn không dùng default bên dưới
        String url  = getenv("DB_URL",
                "jdbc:mysql://syncserver.mysql.database.azure.com:3306/syncdb"
                        + "?sslMode=REQUIRED&enabledTLSProtocols=TLSv1.2,TLSv1.3"
                        + "&serverTimezone=UTC&connectTimeout=5000&socketTimeout=15000&tcpKeepAlive=true");
        String user = getenv("DB_USER", "sync_user");
        String pass = getenv("DB_PASSWORD", "Syncpass123");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(10);       // đủ cho đa luồng vừa phải
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(7000);
        cfg.setIdleTimeout(60000);
        cfg.setMaxLifetime(30 * 60_000);  // 30 phút
        cfg.setConnectionTestQuery("SELECT 1");

        DS = new HikariDataSource(cfg);

        // Đóng pool khi JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(DatabaseManager::closePool));
    }

    private DatabaseManager() {}

    /** Lấy connection từ pool (dùng try-with-resources). */
    public static Connection getConnection() throws SQLException {
        return DS.getConnection();
    }

    /** Transaction gọn: tự commit/rollback. */
    public static <T> T inTransaction(SQLFunction<Connection, T> work) throws SQLException {
        try (Connection c = getConnection()) {
            boolean old = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T res = work.apply(c);
                c.commit();
                return res;
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException ignore) {}
                if (e instanceof SQLException se) throw se;
                throw new SQLException(e);
            } finally {
                try { c.setAutoCommit(old); } catch (SQLException ignore) {}
            }
        }
    }

    /** Ping DB để health-check. */
    public static boolean ping() {
        try (Connection c = getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Đóng pool (thường không cần gọi thủ công). */
    public static void closePool() {
        if (DS != null && !DS.isClosed()) DS.close();
    }

    // --- helpers ---
    private static String getenv(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }

    @FunctionalInterface
    public interface SQLFunction<I, O> { O apply(I input) throws Exception; }
}
