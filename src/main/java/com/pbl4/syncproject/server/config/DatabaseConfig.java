package com.pbl4.syncproject.server.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Database configuration and connection utility
 * Handles MySQL driver loading and connection management
 */
public class DatabaseConfig {
    
    // Database configuration
    private static final String DEFAULT_URL = "jdbc:mysql://127.0.0.1:3307/syncdb";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASSWORD = "123456";
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    
    // Connection properties
    private static final String SERVER_TIMEZONE = "UTC";
    private static final String USE_SSL = "false";
    private static final String ALLOW_PUBLIC_KEY_RETRIEVAL = "true";
    
    private static boolean driverLoaded = false;
    
    /**
     * Initialize and load MySQL driver
     */
    public static void initializeDriver() {
        if (!driverLoaded) {
            try {
                Class.forName(MYSQL_DRIVER);
                System.out.println("✅ MySQL Driver loaded successfully");
                driverLoaded = true;
            } catch (ClassNotFoundException e) {
                System.err.println("❌ Failed to load MySQL Driver: " + e.getMessage());
                System.err.println("Please ensure mysql-connector-j is in classpath");
                throw new RuntimeException("MySQL Driver not found", e);
            }
        }
    }
    
    /**
     * Create database connection with default settings
     */
    public static Connection createConnection() throws SQLException {
        return createConnection(DEFAULT_URL, DEFAULT_USER, DEFAULT_PASSWORD);
    }
    
    /**
     * Create database connection with custom parameters
     */
    public static Connection createConnection(String url, String user, String password) throws SQLException {
        initializeDriver();
        
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("serverTimezone", SERVER_TIMEZONE);
        props.setProperty("useSSL", USE_SSL);
        props.setProperty("allowPublicKeyRetrieval", ALLOW_PUBLIC_KEY_RETRIEVAL);
        
        try {
            Connection connection = DriverManager.getConnection(url, props);
            System.out.println("✅ Database connected successfully to: " + getServerInfo(url));
            return connection;
        } catch (SQLException e) {
            System.err.println("❌ Database connection failed: " + e.getMessage());
            System.err.println("URL: " + url);
            System.err.println("User: " + user);
            throw e;
        }
    }
    
    /**
     * Test database connection
     */
    public static boolean testConnection() {
        try (Connection connection = createConnection()) {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            System.err.println("❌ Database connection test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test database connection with custom parameters
     */
    public static boolean testConnection(String url, String user, String password) {
        try (Connection connection = createConnection(url, user, password)) {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            System.err.println("❌ Database connection test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Extract server info from URL for logging
     */
    private static String getServerInfo(String url) {
        try {
            // Extract host:port/database from jdbc:mysql://host:port/database
            String[] parts = url.split("//");
            if (parts.length > 1) {
                return parts[1];
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return url;
    }
    
    /**
     * Get database configuration info for debugging
     */
    public static void printDatabaseInfo() {
        System.out.println("=== Database Configuration ===");
        System.out.println("URL: " + DEFAULT_URL);
        System.out.println("User: " + DEFAULT_USER);
        System.out.println("Driver: " + MYSQL_DRIVER);
        System.out.println("Driver Loaded: " + driverLoaded);
        System.out.println("SSL: " + USE_SSL);
        System.out.println("Timezone: " + SERVER_TIMEZONE);
        System.out.println("============================");
    }
}