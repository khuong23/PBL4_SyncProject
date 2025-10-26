package com.pbl4.syncproject.client.models;

import java.time.LocalDateTime;

public class NotificationItem {

    // Enum để xác định loại thông báo (và icon tương ứng)
    public enum NotificationType {
        PERMISSION_CHANGE, // Quyền (admin cấp quyền)
        FILE_UPLOAD,       // Tải file lên
        USER_ACTION,       // Hành động người dùng (kết bạn,...)
        SYSTEM             // Thông báo hệ thống
    }

    private final String message;
    private final LocalDateTime timestamp;
    private final NotificationType type;
    private boolean read;

    public NotificationItem(String message, NotificationType type) {
        this.message = message;
        this.type = type;
        this.timestamp = LocalDateTime.now(); // Tự động lấy giờ hiện tại
        this.read = false;
    }

    // Getters
    public String getMessage() { return message; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public NotificationType getType() { return type; }
    public boolean isRead() { return read; }

    // Setter
    public void setRead(boolean read) { this.read = read; }

    /**
     * Lấy biểu tượng (emoji) dựa trên loại thông báo
     */
    public String getIcon() {
        switch (type) {
            case PERMISSION_CHANGE:
                return "🔐"; // Icon Quyền
            case FILE_UPLOAD:
                return "📤"; // Icon Tải lên
            case USER_ACTION:
                return "👤"; // Icon Người dùng
            case SYSTEM:
            default:
                return "ℹ️"; // Icon Thông tin
        }
    }
}
