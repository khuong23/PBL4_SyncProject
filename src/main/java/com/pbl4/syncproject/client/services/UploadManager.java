package com.pbl4.syncproject.client.services;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.views.IMainView;
import com.pbl4.syncproject.common.jsonhandler.Response;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * UploadManager
 * - Upload theo OCC (gửi kèm baseVersion + lastKnownHash) để tránh ghi đè
 * - Cập nhật cache cục bộ nếu ĐÃ có bản ghi (không cố insert khi thiếu LocalPath)
 * - Retry hợp lý + phân loại lỗi để UX rõ ràng
 *
 * Lưu ý:
 * - currentDirectory là ServerFolderID (String)
 * - Nếu cache Files chưa có bản ghi (ví dụ upload từ ngoài thư mục sync),
 *   ta chỉ cập nhật khi tìm thấy record; nếu không có, để Down-Sync điền sau.
 */
public class UploadManager {

    private final NetworkService networkService;
    private final IMainView mainView;
    private final LocalDatabaseManager localDb;
    private final int maxRetries = 3;

    public UploadManager(NetworkService networkService, IMainView mainView, LocalDatabaseManager localDb) {
        this.networkService = networkService;
        this.mainView = mainView;
        this.localDb = localDb;
    }

    /** API public: Upload một file lên folder server (id dạng chuỗi) */
    public void uploadFile(File file, String currentDirectory, UploadResultCallback callback) {
        uploadFileWithRetry(file, currentDirectory, 0, callback);
    }

    /** Validate cơ bản trước khi upload */
    public boolean validateFileForUpload(File file) {
        FileService.ValidationResult result = FileService.validateFileForUpload(file);
        if (!result.isValid) {
            mainView.showAlert("Lỗi", result.message, IMainView.AlertType.ERROR);
            return false;
        }
        if (file.length() == 0) {
            return mainView.showConfirmDialog("Cảnh báo", "File này rỗng. Bạn có chắc muốn tải lên?");
        }
        return true;
    }

    // ================= Core upload (có retry) =================

    private void uploadFileWithRetry(File file, String currentDirectory, int retryCount, UploadResultCallback callback) {
        Task<Response> uploadTask = new Task<>() {
            @Override
            protected Response call() throws Exception {
                try {
                    updateProgress(10, 100);
                    updateMessage("Đang chuẩn bị file: " + file.getName() + "...");
                    Thread.sleep(120);

                    updateProgress(30, 100);
                    updateMessage("Đang kiểm tra kết nối server...");

                    // Map server folder id
                    int serverFolderId = Integer.parseInt(currentDirectory.trim());

                    // =================== KIỂM TRA FILE LOCKING ===================
                    updateMessage("Đang kiểm tra quyền truy cập file...");
                    // Gọi hàm kiểm tra khóa file. Nếu file bị Word khóa, nó sẽ chờ ở đây.
                    waitForFileReadability(file);
                    // ============================================================

                    // Lấy baseline từ cache: hash + version (để gửi OCC)
                    Baseline base = findBaseline(serverFolderId, file.getName());

                    updateProgress(50, 100);
                    updateMessage("Đang tải lên file...");

                    // Gọi API upload có OCC
                    Response response = networkService.uploadFile(
                            file,
                            serverFolderId,
                            base.lastKnownHash,
                            base.lastKnownVersion // 0 nếu file mới
                    );

                    updateProgress(90, 100);
                    updateMessage("Xác nhận tải lên...");
                    Thread.sleep(120);

                    updateProgress(100, 100);
                    updateMessage("Hoàn thành!");
                    return response;

                } catch (Exception e) {
                    throw e;
                }
            }
        };

        // Bind UI
        uploadTask.progressProperty().addListener((obs, oldP, newP) ->
                mainView.showUploadProgress(file.getName(), newP.doubleValue())
        );
        uploadTask.messageProperty().addListener((obs, o, msg) -> {
            if (msg != null) mainView.setStatusMessage(msg);
        });

        uploadTask.setOnSucceeded(e -> {
            mainView.hideUploadProgress();
            Response res = uploadTask.getValue();

            if (res == null) {
                handleUploadError(file, currentDirectory, "Phản hồi rỗng từ server", retryCount, callback);
                return;
            }

            // CONFLICT (server có thể trả status=error/message=CONFLICT hoặc data.conflict=true)
            // CONFLICT (server có thể trả status="conflict" HOẶC status="error"/message="CONFLICT")
            boolean isConflict =
                    "conflict".equalsIgnoreCase(res.getStatus()) // Kiểm tra status="conflict" trực tiếp
                            || ("error".equalsIgnoreCase(res.getStatus()) && "CONFLICT".equalsIgnoreCase(String.valueOf(res.getMessage())))
                            || (res.getData() != null && res.getData().isJsonObject()
                            && res.getData().getAsJsonObject().has("conflict")
                            && res.getData().getAsJsonObject().get("conflict").getAsBoolean());

            if (isConflict) {
                mainView.showAlert(
                        "Xung đột phiên bản",
                        "File \"" + file.getName() + "\" đã bị thay đổi trên server trong lúc bạn chỉnh sửa.\n" +
                                "Hãy tải về bản mới nhất hoặc đổi tên file của bạn rồi thử lại.",
                        IMainView.AlertType.WARNING
                );
                Platform.runLater(() -> callback.onUploadResult(file, null, false, "CONFLICT"));
                return;
            }

            if ("success".equalsIgnoreCase(res.getStatus())) {
                handleUploadSuccess(file, currentDirectory, res, callback);
            } else {
                String err = res.getMessage() != null ? res.getMessage() : "Phản hồi không hợp lệ từ server";
                handleUploadError(file, currentDirectory, err, retryCount, callback);
            }
        });

        uploadTask.setOnFailed(e -> {
            mainView.hideUploadProgress();
            Throwable ex = uploadTask.getException();
            String err = ex != null ? ex.getMessage() : "Lỗi không xác định";
            handleUploadError(file, currentDirectory, err, retryCount, callback);
        });

        new Thread(uploadTask, "Upload-Task-" + file.getName()).start();
    }

    // ================ Thành công / Lỗi ================

    /** Thành công: cập nhật cache (nếu có) + thông báo UI */
    private void handleUploadSuccess(File file, String currentDirectory, Response response, UploadResultCallback callback) {
        String successMsg = response.getMessage() != null ? response.getMessage() : "File đã được tải lên thành công!";
        String folderDisplayName = resolveFolderNameFromServerId(currentDirectory);

        // Khai báo biến ngoài try-catch để có thể truy cập trong catch
        String newServerHash = null;
        Integer newServerVersion = null;
        Integer serverFolderId = tryParseInt(currentDirectory);
        Integer serverFileId = null;

        // Cập nhật cache nếu đã có record (chỉ UPDATE, không cố INSERT vì thiếu LocalPath)
        try {
            JsonObject data = (response.getData() != null && response.getData().isJsonObject())
                    ? response.getData().getAsJsonObject() : null;

            if (data != null) {
                if (data.has("hash") && !data.get("hash").isJsonNull()) {
                    newServerHash = data.get("hash").getAsString();
                }
                if (data.has("version") && !data.get("version").isJsonNull()) {
                    newServerVersion = safeGetInt(data.get("version").getAsString());
                } else if (data.has("newVersion") && !data.get("newVersion").isJsonNull()) {
                    newServerVersion = safeGetInt(data.get("newVersion").getAsString());
                }
                if (data.has("fileId") && !data.get("fileId").isJsonNull()) {
                    serverFileId = safeGetInt(data.get("fileId").getAsString());
                }
                if (data.has("folderId") && !data.get("folderId").isJsonNull()) {
                    serverFolderId = data.get("folderId").getAsInt();
                }
            }

            if (serverFolderId != null) {
                updateLocalCacheAfterUpload(serverFolderId, file.getName(), newServerHash, newServerVersion, serverFileId);
            }
        } catch (Exception e) {
            // Lỗi này rất quan trọng - cache local không được cập nhật sẽ gây xung đột lần upload sau
            System.err.println("⚠️ NGHIÊM TRỌNG: Không thể cập nhật cache local sau khi upload thành công!");
            System.err.println("   File: " + file.getName());
            System.err.println("   ServerFolderId: " + serverFolderId);
            System.err.println("   NewVersion: " + newServerVersion);
            System.err.println("   NewHash: " + newServerHash);
            e.printStackTrace();

            // Thông báo cho user về vấn đề này
            Platform.runLater(() ->
                    mainView.showAlert(
                            "Cảnh báo Cache",
                            "Upload thành công nhưng không thể cập nhật cache local.\n" +
                                    "Lần upload tiếp theo có thể gặp lỗi xung đột.\n" +
                                    "Chi tiết lỗi: " + e.getMessage(),
                            IMainView.AlertType.WARNING
                    )
            );
        }

        mainView.setStatusMessage("Tải lên thành công: " + file.getName());
        mainView.showAlert("Thành công", successMsg, IMainView.AlertType.INFORMATION);

        FileItem uiItem = new FileItem(
                FileService.getFileIcon(file.getName()) + " " + file.getName(),
                FileService.formatFileSize(file.length()),
                FileService.getFileType(file.getName()),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                "Đọc/Ghi",
                "✅ Đã đồng bộ",
                folderDisplayName
        );

        Platform.runLater(() -> callback.onUploadResult(file, uiItem, true, successMsg));
    }

    /** Lỗi: phân loại + hỏi retry nếu phù hợp */
    private void handleUploadError(File file, String currentDirectory, String errorMsg, int retryCount, UploadResultCallback callback) {
        ErrorType errorType = categorizeError(errorMsg);

        if (errorType == ErrorType.CONNECTION) {
            mainView.setConnectionStatus("Mất kết nối", false);
            // Nếu vừa mất kết nối, không retry quá 1 lần không hỏi
            if (retryCount >= 1) {
                showFinalConnectionError(file, errorMsg, retryCount);
                Platform.runLater(() -> callback.onUploadResult(file, null, false, errorMsg));
                return;
            }
        }

        if (errorType == ErrorType.FILE_INVALID || errorType == ErrorType.PERMISSION) {
            showNonRetryableError(file, errorMsg, errorType);
            Platform.runLater(() -> callback.onUploadResult(file, null, false, errorMsg));
            return;
        }

        if (retryCount < maxRetries) {
            String retryMessage = getRetryMessage(errorType, errorMsg, retryCount, maxRetries);
            boolean retry = mainView.showConfirmDialog("Lỗi tải lên", retryMessage);
            if (retry) {
                mainView.setStatusMessage("Đang thử lại... (" + (retryCount + 2) + "/" + (maxRetries + 1) + ")");
                int delayMs = getRetryDelay(errorType, retryCount);

                Task<Void> delayTask = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        Thread.sleep(delayMs);
                        return null;
                    }
                };
                delayTask.setOnSucceeded(ev -> uploadFileWithRetry(file, currentDirectory, retryCount + 1, callback));
                new Thread(delayTask, "Upload-Retry-Delay").start();
                return;
            }
        }

        showFinalError(file, errorMsg, retryCount, errorType);
        Platform.runLater(() -> callback.onUploadResult(file, null, false, errorMsg));
    }

    // =================== DB Helpers / OCC Baseline ===================

    /** Gói baseline để gửi kèm khi upload */
    private static class Baseline {
        final String lastKnownHash;
        final int lastKnownVersion; // 0 nếu chưa biết
        Baseline(String hash, int version) {
            this.lastKnownHash = hash;
            this.lastKnownVersion = Math.max(0, version);
        }
    }

    /**
     * Tìm baseline (hash + version) bằng ServerFolderID + FileName.
     * - Map ServerFolderID → local FolderID
     * - Lấy LastKnownHash + LastKnownVersion từ bảng Files
     */
    private Baseline findBaseline(int serverFolderId, String fileName) {
        final String sqlFindFolder = "SELECT FolderID FROM Folders WHERE ServerFolderID = ?";
        final String sqlFindFile   = "SELECT LastKnownHash, LastKnownVersion FROM Files WHERE FolderID = ? AND FileName = ?";

        try (Connection conn = localDb.getConnection();
             PreparedStatement psFolder = conn.prepareStatement(sqlFindFolder)) {

            psFolder.setInt(1, serverFolderId);
            try (ResultSet rsF = psFolder.executeQuery()) {
                if (!rsF.next()) return new Baseline(null, 0);
                int localFolderId = rsF.getInt("FolderID");

                try (PreparedStatement psFile = conn.prepareStatement(sqlFindFile)) {
                    psFile.setInt(1, localFolderId);
                    psFile.setString(2, fileName);
                    try (ResultSet rsFile = psFile.executeQuery()) {
                        if (rsFile.next()) {
                            String hash = rsFile.getString("LastKnownHash");
                            int version = rsFile.getInt("LastKnownVersion");
                            return new Baseline(hash, version);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("findBaseline error: " + e.getMessage());
        }
        return new Baseline(null, 0);
    }

    /**
     * Thành công → nếu đã có bản ghi thì UPDATE:
     *  - LastKnownHash (nếu server trả)
     *  - LastKnownVersion (nếu server trả)
     *  - ServerFileID (nếu server trả)
     *  - SyncStatus = 'SYNCED'
     *
     * KHÔNG insert mới vì thiếu LocalPath (để Down-Sync bổ sung).
     */
    private void updateLocalCacheAfterUpload(Integer serverFolderId, String fileName,
                                             String newServerHash, Integer newServerVersion, Integer serverFileId) {
        final String sqlFindFolder = "SELECT FolderID FROM Folders WHERE ServerFolderID = ?";
        final String sqlFindFileId = "SELECT FileID FROM Files WHERE FolderID = ? AND FileName = ?";
        final String sqlUpdateFile = "UPDATE Files SET " +
                "LastKnownHash = COALESCE(?, LastKnownHash), " +
                "LastKnownVersion = COALESCE(?, LastKnownVersion), " +
                "ServerFileID = COALESCE(?, ServerFileID), " +
                "SyncStatus = 'SYNCED' " +
                "WHERE FileID = ?";

        try (Connection conn = localDb.getConnection();
             PreparedStatement ps1 = conn.prepareStatement(sqlFindFolder)) {

            ps1.setInt(1, serverFolderId);
            try (ResultSet rs1 = ps1.executeQuery()) {
                if (!rs1.next()) return; // chưa có mapping → bỏ qua
                int localFolderId = rs1.getInt("FolderID");

                try (PreparedStatement ps2 = conn.prepareStatement(sqlFindFileId)) {
                    ps2.setInt(1, localFolderId);
                    ps2.setString(2, fileName);
                    try (ResultSet rs2 = ps2.executeQuery()) {
                        if (!rs2.next()) return; // chưa có bản ghi file → để down-sync điền
                        int fileId = rs2.getInt("FileID");

                        try (PreparedStatement ps3 = conn.prepareStatement(sqlUpdateFile)) {
                            if (newServerHash != null && !newServerHash.isBlank()) ps3.setString(1, newServerHash);
                            else ps3.setNull(1, Types.VARCHAR);

                            if (newServerVersion != null && newServerVersion >= 0) ps3.setInt(2, newServerVersion);
                            else ps3.setNull(2, Types.INTEGER);

                            if (serverFileId != null && serverFileId > 0) ps3.setInt(3, serverFileId);
                            else ps3.setNull(3, Types.INTEGER);

                            ps3.setInt(4, fileId);
                            ps3.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("updateLocalCacheAfterUpload error: " + e.getMessage());
        }
    }

    /**
     * Lấy tên thư mục hiển thị từ ServerFolderID (ưu tiên cache).
     */
    private String resolveFolderNameFromServerId(String serverIdStr) {
        Integer serverId = tryParseInt(serverIdStr);
        if (serverId == null) return serverIdStr;

        String sql = "SELECT FolderName FROM Folders WHERE ServerFolderID = ?";
        try (Connection conn = localDb.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("FolderName");
            }
        } catch (SQLException e) {
            // ignore
        }
        return "Thư mục #" + serverId;
    }

    private Integer tryParseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private Integer safeGetInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    // ===================== Error handling =====================

    private enum ErrorType { CONNECTION, TIMEOUT, FILE_INVALID, PERMISSION, SERVER_ERROR, UNKNOWN }

    private ErrorType categorizeError(String errorMsg) {
        if (errorMsg == null) return ErrorType.UNKNOWN;
        String m = errorMsg.toLowerCase();

        if (m.contains("kết nối") || m.contains("connect") || (m.contains("server") && m.contains("tắt"))) return ErrorType.CONNECTION;
        if (m.contains("timeout") || m.contains("time out")) return ErrorType.TIMEOUT;
        if (m.contains("không tồn tại") || (m.contains("file") && m.contains("invalid"))) return ErrorType.FILE_INVALID;
        if (m.contains("permission") || m.contains("quyền")) return ErrorType.PERMISSION;
        if (m.contains("server error") || m.contains("internal server")) return ErrorType.SERVER_ERROR;
        return ErrorType.UNKNOWN;
    }

    private String getRetryMessage(ErrorType errorType, String errorMsg, int retryCount, int maxRetries) {
        String base = "Lỗi tải lên file: " + errorMsg + "\n" +
                "Lần thử: " + (retryCount + 1) + "/" + (maxRetries + 1) + "\n\n";
        return switch (errorType) {
            case CONNECTION -> base + "Lỗi kết nối server. Thử lại?\n(Sẽ kiểm tra kết nối trước khi thử lại)";
            case TIMEOUT -> base + "Kết nối bị timeout. Thử lại?\n(Sẽ tăng thời gian chờ)";
            case SERVER_ERROR -> base + "Lỗi server. Thử lại?\n(Server có thể quá tải tạm thời)";
            default -> base + "Bạn có muốn thử lại không?";
        };
    }

    private int getRetryDelay(ErrorType errorType, int retryCount) {
        return switch (errorType) {
            case CONNECTION -> 5000 + (retryCount * 3000);
            case TIMEOUT -> 3000 + (retryCount * 2000);
            case SERVER_ERROR -> (int) Math.pow(2, retryCount) * 2000;
            default -> (int) Math.pow(2, retryCount) * 1000;
        };
    }

    private void showNonRetryableError(File file, String errorMsg, ErrorType type) {
        mainView.setStatusMessage("Lỗi tải lên: " + file.getName());
        String title = "Lỗi tải lên";
        String message = "Không thể tải lên file: " + errorMsg;
        switch (type) {
            case FILE_INVALID -> message += "\n\nVui lòng kiểm tra:\n• File có tồn tại/không hỏng\n• Tên file hợp lệ";
            case PERMISSION -> message += "\n\nVui lòng kiểm tra:\n• Quyền truy cập file\n• Quyền upload trên server";
            default -> message += "\n\nVui lòng liên hệ quản trị viên để được hỗ trợ";
        }
        mainView.showAlert(title, message, IMainView.AlertType.ERROR);
    }

    private void showFinalConnectionError(File file, String errorMsg, int retryCount) {
        mainView.setStatusMessage("Mất kết nối server");
        String message = "Không thể kết nối tới server sau " + (retryCount + 1) + " lần thử:\n\n" + errorMsg +
                "\n\nHướng dẫn khắc phục:\n" +
                "• Kiểm tra server có đang chạy không\n" +
                "• Kiểm tra kết nối internet / firewall\n" +
                "• Thử lại sau ít phút";
        mainView.showAlert("Lỗi kết nối", message, IMainView.AlertType.ERROR);
    }

    private void showFinalError(File file, String errorMsg, int retryCount, ErrorType type) {
        mainView.setStatusMessage("Lỗi tải lên: " + file.getName());
        String title = "Tải lên thất bại";
        String message = "Tải lên thất bại sau " + (retryCount + 1) + " lần thử:\n\n" + errorMsg;
        switch (type) {
            case CONNECTION ->
                    message += "\n\nHướng dẫn:\n• Kiểm tra mạng\n• Khởi động lại server\n• Liên hệ quản trị viên";
            case TIMEOUT ->
                    message += "\n\nHướng dẫn:\n• Thử lại khi mạng ổn định hơn\n• Kiểm tra kích thước file";
            case SERVER_ERROR ->
                    message += "\n\nHướng dẫn:\n• Thử lại sau ít phút\n• Kiểm tra dung lượng server";
            default ->
                    message += "\n\nHướng dẫn:\n• Kiểm tra mạng\n• Thử lại sau ít phút";
        }
        mainView.showAlert(title, message, IMainView.AlertType.ERROR);
    }

    // ================= File Locking Handler =================

    /**
     * Kiểm tra xem file có thể đọc được không (xử lý File Locking trên Windows).
     * Nếu file bị khóa bởi tiến trình khác (Word, Excel), thử lại vài lần trước khi bỏ cuộc.
     */
    private void waitForFileReadability(File file) throws Exception {
        int maxLockRetries = 5; // Thử tối đa 5 lần
        int sleepTime = 500;    // Chờ 0.5s mỗi lần

        for (int i = 0; i < maxLockRetries; i++) {
            // Thử mở luồng đọc. Trên Windows, nếu file bị lock, dòng này sẽ ném FileNotFoundException
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                // Nếu mở được nghĩa là file an toàn để upload -> thoát vòng lặp
                return;
            } catch (java.io.FileNotFoundException e) {
                // Kiểm tra thông điệp lỗi đặc trưng của Windows
                if (e.getMessage() != null && e.getMessage().contains("process cannot access")) {
                    // File đang bị khóa
                    if (i < maxLockRetries - 1) {
                        // Chưa hết số lần thử -> thông báo và chờ
                        System.out.println("⏳ File đang bận, thử lại (" + (i + 1) + ")...");
                        Thread.sleep(sleepTime);
                        sleepTime *= 2; // Tăng thời gian chờ (Exponential Backoff)
                    } else {
                        // Hết lượt thử -> ném ngoại lệ
                        throw new Exception("File đang bị mở bởi ứng dụng khác (Word/Excel). Vui lòng đóng file và thử lại.");
                    }
                } else {
                    // Lỗi không tìm thấy file thật sự
                    throw e;
                }
            } catch (java.io.IOException e) {
                // Các lỗi IO khác -> ném ra luôn
                throw e;
            }
        }
    }

    // ================= Callback =================

    public interface UploadResultCallback {
        void onUploadResult(File file, FileItem newFileItem, boolean success, String message);
    }
}