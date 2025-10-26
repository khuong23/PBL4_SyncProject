package com.pbl4.syncproject.client.services;

import com.pbl4.syncproject.client.models.NotificationItem;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Singleton service để quản lý danh sách thông báo
 * (Chỉ ở phía client - thông báo sẽ mất khi đóng app)
 */
public class NotificationManager {

    private static NotificationManager instance;
    private final ObservableList<NotificationItem> notifications;
    private final SimpleIntegerProperty unreadCount;

    private NotificationManager() {
        notifications = FXCollections.observableArrayList();
        unreadCount = new SimpleIntegerProperty(0);
    }

    /**
     * Lấy instance duy nhất của NotificationManager
     */
    public static synchronized NotificationManager getInstance() {
        if (instance == null) {
            instance = new NotificationManager();
        }
        return instance;
    }

    /**
     * Thêm một thông báo mới vào đầu danh sách
     */
    public void addNotification(String message, NotificationItem.NotificationType type) {
        NotificationItem item = new NotificationItem(message, type);
        
        // Phải chạy trên UI thread để cập nhật ObservableList an toàn
        Platform.runLater(() -> {
            notifications.add(0, item); // Thêm vào đầu danh sách
            unreadCount.set(unreadCount.get() + 1);
        });
    }

    /**
     * Lấy danh sách thông báo để hiển thị trên UI
     */
    public ObservableList<NotificationItem> getNotifications() {
        return notifications;
    }

    /**
     * Lấy số lượng thông báo chưa đọc (để binding)
     */
    public SimpleIntegerProperty unreadCountProperty() {
        return unreadCount;
    }

    /**
     * Đánh dấu tất cả là đã đọc (khi mở panel)
     */
    public void markAllAsRead() {
        Platform.runLater(() -> {
            for (NotificationItem item : notifications) {
                item.setRead(true);
            }
            unreadCount.set(0);
        });
    }
}
