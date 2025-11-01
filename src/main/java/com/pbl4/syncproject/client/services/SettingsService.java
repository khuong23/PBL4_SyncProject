package com.pbl4.syncproject.client.services;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Quản lý việc đọc và ghi file cài đặt (client_settings.properties).
 * Sử dụng Singleton pattern.
 */
public class SettingsService {

    private static SettingsService instance;
    private final Properties properties = new Properties();
    private final String settingsFilePath = "client_settings.properties";

    // Khóa (key) cho các cài đặt
    public static final String KEY_SYNC_DIRECTORY = "sync.directory";
    public static final String KEY_TEMP_DIRECTORY = "temp.directory";

    private SettingsService() {
        loadSettings(); // Tải cài đặt khi khởi tạo
    }

    public static synchronized SettingsService getInstance() {
        if (instance == null) {
            instance = new SettingsService();
        }
        return instance;
    }

    /**
     * Tải cài đặt từ file .properties.
     */
    public void loadSettings() {
        File settingsFile = new File(settingsFilePath);
        if (settingsFile.exists()) {
            try (InputStream input = new FileInputStream(settingsFile)) {
                properties.load(input);
                System.out.println("✅ Đã tải cài đặt từ: " + settingsFilePath);
            } catch (IOException e) {
                System.err.println("Không thể tải file cài đặt: " + e.getMessage());
            }
        }
    }

    /**
     * Lưu cài đặt hiện tại vào file .properties.
     */
    public void saveSettings() {
        try (OutputStream output = new FileOutputStream(settingsFilePath)) {
            properties.store(output, "Client App Settings");
            System.out.println("✅ Đã lưu cài đặt vào: " + settingsFilePath);
        } catch (IOException e) {
            System.err.println("Không thể lưu file cài đặt: " + e.getMessage());
        }
    }

    /**
     * Lấy một giá trị cài đặt.
     * @param key Khóa cài đặt (ví dụ: "sync.directory")
     * @param defaultValue Giá trị mặc định nếu không tìm thấy
     */
    public String getSetting(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Thiết lập một giá trị cài đặt (trong bộ nhớ).
     * Cần gọi saveSettings() để lưu ra file.
     */
    public void setSetting(String key, String value) {
        properties.setProperty(key, value);
    }
}
