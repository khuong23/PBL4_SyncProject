package com.pbl4.syncproject.client.services;

import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.views.IMainView;
import com.pbl4.syncproject.common.jsonhandler.Response;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Service để quản lý upload operations với retry logic và error handling
 * Tách biệt khỏi Controller để đơn giản hóa và tái sử dụng
 */
public class UploadManager {
    
    private final NetworkService networkService;
    private final IMainView mainView;
    private final int maxRetries = 3;
    
    public UploadManager(NetworkService networkService, IMainView mainView) {
        this.networkService = networkService;
        this.mainView = mainView;
    }
    
    /**
     * Upload file với callback để báo cáo kết quả về Controller
     */
    public void uploadFile(File file, String currentDirectory, UploadResultCallback callback) {
        uploadFileWithRetry(file, currentDirectory, 0, callback);
    }
    
    /**
     * Validate file trước khi upload
     */
    public boolean validateFileForUpload(File file) {
        FileService.ValidationResult result = FileService.validateFileForUpload(file);
        
        if (!result.isValid) {
            mainView.showAlert("Lỗi", result.message, IMainView.AlertType.ERROR);
            return false;
        }
        
        // Check if file is empty - show warning but allow
        if (file.length() == 0) {
            return mainView.showConfirmDialog("Cảnh báo",
                "File này rỗng. Bạn có chắc muốn tải lên?");
        }
        
        return true;
    }
    
    /**
     * Upload file với retry mechanism
     */
    private void uploadFileWithRetry(File file, String currentDirectory, int retryCount, UploadResultCallback callback) {
        Task<Response> uploadTask = new Task<Response>() {
            @Override
            protected Response call() throws Exception {
                try {
                    // Update progress to 10%
                    updateProgress(10, 100);
                    updateMessage("Đang chuẩn bị file: " + file.getName() + "...");
                    
                    // Simulate file preparation time
                    Thread.sleep(500);
                    
                    // Update progress to 30%
                    updateProgress(30, 100);
                    updateMessage("Đang kết nối server...");

                    // Update progress to 50%
                    updateProgress(50, 100);
                    updateMessage("Đang tải lên file...");
                    
                    // --- BẮT ĐẦU SỬA ĐỔI ---
                    // Tham số 'currentDirectory' thực chất đang chứa chuỗi ID của thư mục (ví dụ: "3").
                    // Chúng ta chỉ cần chuyển đổi nó thành số nguyên.
                    int folderId = Integer.parseInt(currentDirectory);
                    // --- KẾT THÚC SỬA ĐỔI ---
                    
                    // Upload file using NetworkService
                    Response response = networkService.uploadFile(file, folderId);
                    
                    updateProgress(90, 100);
                    updateMessage("Xác nhận tải lên...");
                    
                    // Simulate verification
                    Thread.sleep(300);
                    
                    updateProgress(100, 100);
                    updateMessage("Hoàn thành!");
                    
                    return response;
                } catch (Exception e) {
                    throw e;
                }
            }
        };
        
        // Bind progress to UI
        uploadTask.progressProperty().addListener((obs, oldProgress, newProgress) -> {
            double progress = newProgress.doubleValue();
            mainView.showUploadProgress(file.getName(), progress);
        });
        
        // Bind status message to UI
        uploadTask.messageProperty().addListener((obs, oldMessage, newMessage) -> {
            if (newMessage != null) {
                mainView.setStatusMessage(newMessage);
            }
        });
        
        uploadTask.setOnSucceeded(e -> {
            mainView.hideUploadProgress();
            
            Response response = uploadTask.getValue();
            if (response != null && "success".equals(response.getStatus())) {
                handleUploadSuccess(file, currentDirectory, response, callback);
            } else {
                String errorMsg = response != null && response.getMessage() != null ? 
                                response.getMessage() : "Phản hồi không hợp lệ từ server";
                handleUploadError(file, currentDirectory, errorMsg, retryCount, callback);
            }
        });
        
        uploadTask.setOnFailed(e -> {
            mainView.hideUploadProgress();
            Throwable exception = uploadTask.getException();
            String errorMsg = exception != null ? exception.getMessage() : "Lỗi không xác định";
            handleUploadError(file, currentDirectory, errorMsg, retryCount, callback);
        });
        
        new Thread(uploadTask).start();
    }
    
    /**
     * Xử lý upload thành công
     */
    private void handleUploadSuccess(File file, String currentDirectory, Response response, UploadResultCallback callback) {
        // Get current folder name for the uploaded file
        String currentFolderName = currentDirectory.replace("📁 ", "").replace("/", "").trim();
        
        // Create FileItem để add vào list
        FileItem newFile = new FileItem(
            FileService.getFileIcon(file.getName()) + " " + file.getName(),
            FileService.formatFileSize(file.length()),
            FileService.getFileType(file.getName()),
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
            "Đọc/Ghi",
            "✅ Đã đồng bộ",
            currentFolderName
        );
        
        mainView.setStatusMessage("Tải lên thành công: " + file.getName());
        
        String successMsg = response.getMessage() != null ? response.getMessage() : 
                          "File đã được tải lên thành công!";
        mainView.showAlert("Thành công", successMsg, IMainView.AlertType.INFORMATION);
        
        // Notify callback về success
        Platform.runLater(() -> callback.onUploadResult(file, newFile, true, successMsg));
    }
    
    /**
     * Xử lý upload error với retry logic
     */
    private void handleUploadError(File file, String currentDirectory, String errorMsg, int retryCount, UploadResultCallback callback) {
        // Categorize error type
        ErrorType errorType = categorizeError(errorMsg);
        
        // For connection errors, always check connection first
        if (errorType == ErrorType.CONNECTION) {
            mainView.setConnectionStatus("Mất kết nối", false);
            
            // Don't retry connection errors too aggressively
            if (retryCount >= 1) {
                showFinalConnectionError(file, errorMsg, retryCount);
                Platform.runLater(() -> callback.onUploadResult(file, null, false, errorMsg));
                return;
            }
        }
        
        // Don't retry certain types of errors
        if (errorType == ErrorType.FILE_INVALID || errorType == ErrorType.PERMISSION) {
            showNonRetryableError(file, errorMsg, errorType);
            Platform.runLater(() -> callback.onUploadResult(file, null, false, errorMsg));
            return;
        }
        
        if (retryCount < maxRetries) {
            // Show retry dialog with error-specific message
            String retryMessage = getRetryMessage(errorType, errorMsg, retryCount, maxRetries);
            boolean retry = mainView.showConfirmDialog("Lỗi tải lên", retryMessage);

            if (retry) {
                mainView.setStatusMessage("Đang thử lại... (" + (retryCount + 2) + "/" + (maxRetries + 1) + ")");

                // Different retry delays based on error type
                int delayMs = getRetryDelay(errorType, retryCount);

                // Retry after delay
                Task<Void> retryTask = new Task<Void>() {
                    @Override
                    protected Void call() throws Exception {
                        Thread.sleep(delayMs);
                        return null;
                    }
                };

                retryTask.setOnSucceeded(e -> {
                    uploadFileWithRetry(file, currentDirectory, retryCount + 1, callback);
                });

                new Thread(retryTask).start();
                return;
            }
        }

        // Final failure - no more retries
        showFinalError(file, errorMsg, retryCount, errorType);
        Platform.runLater(() -> callback.onUploadResult(file, null, false, errorMsg));
    }
    
    // =================================================================
    // ERROR HANDLING UTILITIES
    // =================================================================
    
    /**
     * Error types cho better handling
     */
    private enum ErrorType {
        CONNECTION, TIMEOUT, FILE_INVALID, PERMISSION, SERVER_ERROR, UNKNOWN
    }
    
    /**
     * Categorize error dựa trên error message
     */
    private ErrorType categorizeError(String errorMsg) {
        if (errorMsg == null) return ErrorType.UNKNOWN;
        
        String lowerMsg = errorMsg.toLowerCase();
        if (lowerMsg.contains("kết nối") || lowerMsg.contains("connect") || 
            lowerMsg.contains("server") && lowerMsg.contains("tắt")) {
            return ErrorType.CONNECTION;
        }
        if (lowerMsg.contains("timeout") || lowerMsg.contains("time out")) {
            return ErrorType.TIMEOUT;
        }
        if (lowerMsg.contains("không tồn tại") || lowerMsg.contains("file") && lowerMsg.contains("invalid")) {
            return ErrorType.FILE_INVALID;
        }
        if (lowerMsg.contains("permission") || lowerMsg.contains("quyền")) {
            return ErrorType.PERMISSION;
        }
        if (lowerMsg.contains("server error") || lowerMsg.contains("internal server")) {
            return ErrorType.SERVER_ERROR;
        }
        return ErrorType.UNKNOWN;
    }
    
    /**
     * Get retry message dựa trên error type
     */
    private String getRetryMessage(ErrorType errorType, String errorMsg, int retryCount, int maxRetries) {
        String baseMsg = "Lỗi tải lên file: " + errorMsg + "\n" +
                        "Lần thử: " + (retryCount + 1) + "/" + maxRetries + "\n\n";
        
        switch (errorType) {
            case CONNECTION:
                return baseMsg + "Lỗi kết nối server. Bạn có muốn thử lại không?\n" +
                              "(Sẽ kiểm tra kết nối trước khi thử lại)";
            case TIMEOUT:
                return baseMsg + "Kết nối bị timeout. Bạn có muốn thử lại không?\n" +
                              "(Sẽ tăng thời gian chờ)";
            case SERVER_ERROR:
                return baseMsg + "Lỗi server. Bạn có muốn thử lại không?\n" +
                              "(Server có thể tạm thời quá tải)";
            default:
                return baseMsg + "Bạn có muốn thử lại không?";
        }
    }
    
    /**
     * Get retry delay dựa trên error type
     */
    private int getRetryDelay(ErrorType errorType, int retryCount) {
        switch (errorType) {
            case CONNECTION:
                return 5000 + (retryCount * 3000); // 5s, 8s, 11s...
            case TIMEOUT:
                return 3000 + (retryCount * 2000); // 3s, 5s, 7s...
            case SERVER_ERROR:
                return (int) Math.pow(2, retryCount) * 2000; // 2s, 4s, 8s...
            default:
                return (int) Math.pow(2, retryCount) * 1000; // 1s, 2s, 4s...
        }
    }
    
    /**
     * Show error cho non-retryable errors
     */
    private void showNonRetryableError(File file, String errorMsg, ErrorType errorType) {
        mainView.setStatusMessage("Lỗi tải lên: " + file.getName());
        
        String title = "Lỗi tải lên";
        String message = "Không thể tải lên file: " + errorMsg;
        
        switch (errorType) {
            case FILE_INVALID:
                message += "\n\nVui lòng kiểm tra:\n• File có tồn tại không\n• File có bị hỏng không\n• Tên file có hợp lệ không";
                break;
            case PERMISSION:
                message += "\n\nVui lòng kiểm tra:\n• Quyền truy cập file\n• Quyền upload trên server\n• Liên hệ quản trị viên";
                break;
            default:
                message += "\n\nVui lòng liên hệ quản trị viên để được hỗ trợ";
                break;
        }
        
        mainView.showAlert(title, message, IMainView.AlertType.ERROR);
    }
    
    /**
     * Show final connection error
     */
    private void showFinalConnectionError(File file, String errorMsg, int retryCount) {
        mainView.setStatusMessage("Mất kết nối server");
        
        String message = "Không thể kết nối tới server sau " + (retryCount + 1) + " lần thử:\n\n" + errorMsg +
                        "\n\nHướng dẫn khắc phục:\n" +
                        "• Kiểm tra server có đang chạy không\n" +
                        "• Kiểm tra kết nối internet\n" +
                        "• Kiểm tra firewall và antivirus\n" +
                        "• Thử lại sau 5-10 phút\n" +
                        "• Liên hệ quản trị viên nếu vấn đề tiếp tục";
        
        mainView.showAlert("Lỗi kết nối", message, IMainView.AlertType.ERROR);
    }
    
    /**
     * Show final error sau khi all retries failed
     */
    private void showFinalError(File file, String errorMsg, int retryCount, ErrorType errorType) {
        mainView.setStatusMessage("Lỗi tải lên: " + file.getName());
        
        String title = "Tải lên thất bại";
        String message = "Tải lên thất bại sau " + (retryCount + 1) + " lần thử:\n\n" + errorMsg;
        
        switch (errorType) {
            case CONNECTION:
                message += "\n\nHướng dẫn:\n• Kiểm tra kết nối mạng\n• Khởi động lại server\n• Liên hệ quản trị viên";
                break;
            case TIMEOUT:
                message += "\n\nHướng dẫn:\n• Thử lại khi mạng ổn định hơn\n• Kiểm tra file có quá lớn không\n• Liên hệ IT support";
                break;
            case SERVER_ERROR:
                message += "\n\nHướng dẫn:\n• Thử lại sau ít phút\n• Kiểm tra dung lượng server\n• Liên hệ quản trị viên";
                break;
            default:
                message += "\n\nHướng dẫn:\n• Kiểm tra kết nối mạng\n• Thử lại sau ít phút\n• Liên hệ support nếu vấn đề tiếp tục";
                break;
        }
        
        mainView.showAlert(title, message, IMainView.AlertType.ERROR);
    }
    
    /**
     * Callback interface cho upload results
     */
    public interface UploadResultCallback {
        void onUploadResult(File file, FileItem newFileItem, boolean success, String message);
    }
}