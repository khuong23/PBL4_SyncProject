package com.pbl4.syncproject.client.views;

import com.pbl4.syncproject.client.models.NotificationItem;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class NotificationCell extends ListCell<NotificationItem> {

    private final HBox layout;
    private final Label iconLabel;
    private final Label messageLabel;
    private final Label timestampLabel;
    private final VBox textVBox;

    public NotificationCell() {
        super();
        
        // Cấu trúc: HBox [Icon, VBox[Message, Timestamp]]
        
        iconLabel = new Label();
        iconLabel.setFont(Font.font("System", 24)); // Kích thước icon
        iconLabel.setMinWidth(40);

        messageLabel = new Label();
        messageLabel.setWrapText(true); // Tự động xuống dòng
        messageLabel.setFont(Font.font("Segoe UI", 14));

        timestampLabel = new Label();
        timestampLabel.setFont(Font.font("Segoe UI", 12));
        timestampLabel.setStyle("-fx-text-fill: #6b7280;"); // Màu xám

        textVBox = new VBox(5, messageLabel, timestampLabel);
        
        layout = new HBox(10, iconLabel, textVBox);
        layout.setPadding(new Insets(10));
    }

    @Override
    protected void updateItem(NotificationItem item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            setStyle("");
        } else {
            // Cập nhật nội dung
            iconLabel.setText(item.getIcon());
            messageLabel.setText(item.getMessage());
            timestampLabel.setText(formatTimestamp(item.getTimestamp()));

            // (Tùy chọn) Đổi màu nền nếu chưa đọc
            if (!item.isRead()) {
                setStyle("-fx-background-color: #eef2ff;"); // Màu xanh nhạt
            } else {
                setStyle("-fx-background-color: white;");
            }
            
            setGraphic(layout);
        }
    }
    
    /**
     * Chuyển đổi thời gian thành "2 giờ trước", "3 ngày trước"
     */
    private String formatTimestamp(LocalDateTime timestamp) {
        Duration duration = Duration.between(timestamp, LocalDateTime.now());

        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return "vài giây trước";
        }
        long minutes = duration.toMinutes();
        if (minutes < 60) {
            return minutes + " phút trước";
        }
        long hours = duration.toHours();
        if (hours < 24) {
            return hours + " giờ trước";
        }
        long days = duration.toDays();
        if (days < 7) {
            return days + " ngày trước";
        }
        
        // Cũ hơn 1 tuần thì hiển thị ngày tháng
        return timestamp.format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'lúc' HH:mm"));
    }
}
