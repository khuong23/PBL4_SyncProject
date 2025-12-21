package com.pbl4.syncproject.client.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.JsonUtils;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * NetworkService
 * - Giao tiếp JSON với server
 * - Hỗ trợ since_seq: getChangesSince(), getFeedHead()/pingGetLastSeq()
 * - Heartbeat online/offline + heartbeat phát hiện thay đổi mới (lastSeq > since_seq)
 * - API thao tác file/folder cơ bản: upload, download, create, delete, move, rename
 *
 * Lưu ý:
 *  - Không shutdown executor khi stopHeartbeat(); chỉ hủy các Future → có thể start lại.
 *  - Tất cả request đều tự chèn username nếu có.
 */
public class NetworkService {

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    private String serverIP;
    private int serverPort;
    private String currentUsername; // đính kèm mọi request

    // ---- Heartbeat ----
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Heartbeat-Thread");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> statusHeartbeat; // ping online/offline
    private ScheduledFuture<?> seqHeartbeat;    // ping lastSeq để kích down-sync

    private final BooleanProperty isOnlineProperty = new SimpleBooleanProperty(false);
    private volatile boolean lastPingStatus = false;

    public NetworkService() { /* setServerAddress() sau */ }
    public NetworkService(String serverIP, int serverPort) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
    }

    // ---- Config / State ----
    public void setServerAddress(String serverIP, int serverPort) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        System.out.println("📍 NetworkService configured: " + serverIP + ":" + serverPort);
    }
    public String getServerIP() { return serverIP; }
    public int getServerPort() { return serverPort; }

    public void setCurrentUsername(String username) {
        this.currentUsername = username;
        System.out.println("👤 NetworkService username set: " + username);
    }
    public String getCurrentUsername() { return this.currentUsername; }

    /** Trạng thái ONLINE dựa trên heartbeat/ping (nên dùng cho UI). */
    public boolean isOnline() { return isOnlineProperty.get(); }
    /** Truy cập property để bind UI. */
    public BooleanProperty isOnlineProperty() { return isOnlineProperty; }
    /** Trạng thái socket raw (có thể true nhưng server không phản hồi ping). */
    public boolean isConnected() { return ClientConnectionManager.getInstance().isConnected(); }

    // ---------------- HEARTBEAT ----------------

    /** Heartbeat chỉ để cập nhật ONLINE/OFFLINE (30s/lần). An toàn, idempotent. */
    public synchronized void startHeartbeat() {
        if (statusHeartbeat != null && !statusHeartbeat.isCancelled() && !statusHeartbeat.isDone()) return;
        statusHeartbeat = heartbeatScheduler.scheduleAtFixedRate(this::performPing, 0, 30, TimeUnit.SECONDS);
    }

    /** Heartbeat phát hiện thay đổi server theo seq (3s/lần) và gọi onRemoteChange khi cần. */
    public synchronized void startHeartbeat(Runnable onRemoteChange, LongSupplier getSinceSeq) {
        // Đảm bảo heartbeat ONLINE cũng chạy
        startHeartbeat();
        if (seqHeartbeat != null && !seqHeartbeat.isCancelled() && !seqHeartbeat.isDone()) return;

        seqHeartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                Response res = sendRequest(new Request("PING", new JsonObject()));
                if (!"success".equals(res.getStatus())) {
                    markOffline();
                    return;
                }
                JsonObject data = asObj(res.getData());
                long lastSeq = (data != null && data.has("lastSeq")) ? safeGetLong(data.get("lastSeq")) : 0L;
                long currentSince = getSinceSeq.getAsLong();
                if (lastSeq > currentSince) onRemoteChange.run();

                // cập nhật ONLINE nếu vừa trở lại
                markOnline();
            } catch (Exception e) {
                markOffline();
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    /** Hủy các heartbeat đang chạy (có thể start lại). */
    public synchronized void stopHeartbeat() {
        if (statusHeartbeat != null) { statusHeartbeat.cancel(false); statusHeartbeat = null; }
        if (seqHeartbeat != null)    { seqHeartbeat.cancel(false);    seqHeartbeat = null; }
    }

    private void performPing() {
        try {
            Response response = sendRequest(new Request("PING", new JsonObject()));
            if ("success".equals(response.getStatus())) {
                markOnline();
            } else {
                markOffline();
            }
        } catch (Exception e) {
            System.out.println("[Heartbeat] Ping thất bại: " + e.getMessage());
            markOffline();
        }
    }

    private void markOnline() {
        if (!lastPingStatus) {
            lastPingStatus = true;
            Platform.runLater(() -> isOnlineProperty.set(true));
            System.out.println("[Heartbeat] ONLINE");
        }
    }

    private void markOffline() {
        if (lastPingStatus) {
            lastPingStatus = false;
            Platform.runLater(() -> isOnlineProperty.set(false));
            System.out.println("[Heartbeat] OFFLINE");
        }
    }

    // ------------------------- Helpers chung -------------------------

    private void validateServerAddress() throws Exception {
        if (serverIP == null || serverIP.trim().isEmpty() || serverPort <= 0) {
            throw new Exception("Server address chưa được thiết lập! Vui lòng gọi setServerAddress() trước.");
        }
    }

    private void addUsernameToData(JsonObject data) {
        if (currentUsername != null && !currentUsername.isBlank()) {
            data.addProperty("username", currentUsername);
        }
    }

    private long safeGetLong(JsonElement el) {
        try {
            if (el == null || el.isJsonNull()) return 0L;
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) return el.getAsLong();
            return Long.parseLong(el.getAsString());
        } catch (Exception e) {
            return 0L;
        }
    }

    private JsonObject asObj(JsonElement el) {
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // --------------------------- API UPLOAD ---------------------------

    /** Upload với lastKnownHash (cũ) — giữ để tương thích; mặc định baseVersion=0 (file mới). */
    public Response uploadFile(File file, int folderId, String lastKnownHash) throws Exception {
        return uploadFile(file, folderId, lastKnownHash, 0);
    }

    /** Upload file mới không hash — tương thích cũ. */
    public Response uploadFile(File file, int folderId) throws Exception {
        return uploadFile(file, folderId, null, 0);
    }

    /** Upload lên root — tương thích cũ. */
    public Response uploadFile(File file) throws Exception {
        return uploadFile(file, 1, null, 0);
    }

    /** Upload theo OCC: gửi kèm baseVersion. */
    public Response uploadFile(File file, int folderId, String lastKnownHash, int baseVersion) throws Exception {
        validateServerAddress();

        if (!file.exists()) throw new Exception("File không tồn tại: " + file.getAbsolutePath());
        if (!file.canRead()) throw new Exception("Không thể đọc file: " + file.getAbsolutePath());
        if (file.length() > MAX_FILE_SIZE) {
            throw new Exception("File quá lớn. Tối đa: " + formatFileSize(MAX_FILE_SIZE));
        }

        try {
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String base64Content = Base64.getEncoder().encodeToString(fileBytes);
            if (base64Content.isEmpty()) throw new Exception("Nội dung file rỗng hoặc không thể đọc được");

            JsonObject data = new JsonObject();
            data.addProperty("fileName",     file.getName());
            data.addProperty("fileContent",  base64Content);
            data.addProperty("folderId",     folderId);
            data.addProperty("fileSize",     file.length());
            data.addProperty("lastModified", file.lastModified());

            if (lastKnownHash != null) data.addProperty("lastKnownHash", lastKnownHash);
            
            // FIX: Luôn gửi baseVersion (ngay cả khi = 0) để server xử lý OCC đúng
            // Server sẽ biết: baseVersion=0 là CREATE, baseVersion > 0 là UPDATE
            data.addProperty("baseVersion", baseVersion);

            addUsernameToData(data);
            return sendRequest(new Request("UPLOAD_FILE", data));
        } catch (OutOfMemoryError e) {
            throw new Exception("File quá lớn để xử lý trong bộ nhớ. Hãy thử file nhỏ hơn.");
        } catch (IOException e) {
            throw new Exception("Lỗi đọc file: " + e.getMessage(), e);
        }
    }

    // --------------------------- API LIST/TREE ---------------------------

    public Response getFileList(int folderId) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        addUsernameToData(data);
        return sendRequest(new Request("GET_FILE_LIST", data));
    }

    public Response getFileList() throws Exception { return getFileList(1); }

    public Response createFolder(String folderName, int parentFolderId) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("folderName", folderName);
        data.addProperty("parentFolderId", parentFolderId);
        addUsernameToData(data);
        return sendRequest(new Request("CREATE_FOLDER", data));
    }

    public Response createFolder(String folderName) throws Exception { return createFolder(folderName, 1); }

    public Response getFolderTree(int parentId) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("parentId", parentId);
        addUsernameToData(data);
        return sendRequest(new Request("FOLDER_TREE", data));
    }

    public Response getFolderTree() throws Exception { return getFolderTree(1); } // Mặc định lấy root folder ID=1

    // --------------------------- API DOWNLOAD ---------------------------

    /** Cũ: trả Response; nên dùng downloadFileTo() để ghi file luôn. */
    public Response downloadFile(int fileId, String fileName, int folderId) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("fileId", fileId);
        addUsernameToData(data);
        return sendRequest(new Request("DOWNLOAD_FILE", data));
    }

    /** Mới: tải file và ghi về đĩa (tạo thư mục nếu cần). */
    public boolean downloadFileTo(int fileId, String fileName, int folderId, Path saveTo) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("fileId", fileId);
        addUsernameToData(data);

        Response res = sendRequest(new Request("DOWNLOAD_FILE", data));
        if (!"success".equals(res.getStatus())) return false;

        JsonObject d = asObj(res.getData());
        if (d == null || !d.has("fileContent")) return false;

        byte[] bytes = Base64.getDecoder().decode(d.get("fileContent").getAsString());
        Files.createDirectories(saveTo.getParent());
        Files.write(saveTo, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return true;
    }

    // --------------------------- API CHANGE FEED ---------------------------

    /** Lấy thay đổi kể từ sinceSeq (Version/Seq). */
    public Response getChangesSince(long sinceSeq) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("sinceSeq", sinceSeq);
        addUsernameToData(data);
        return sendRequest(new Request("GET_CHANGES_SINCE", data));
    }

    /** Wrapper chính thức cho GET_FEED_HEAD (nếu server hỗ trợ). */
    public long getFeedHead() throws Exception {
        Response res = sendRequest(new Request("GET_FEED_HEAD", new JsonObject()));
        if (!"success".equals(res.getStatus())) return -1L;
        JsonObject d = asObj(res.getData());
        if (d == null) return -1L;
        if (d.has("head"))    return safeGetLong(d.get("head"));
        if (d.has("lastSeq")) return safeGetLong(d.get("lastSeq"));
        return -1L;
    }

    /** Lấy lastSeq hiện tại bằng PING (fallback khi không có GET_FEED_HEAD). */
    public long pingGetLastSeq() throws Exception {
        Response res = sendRequest(new Request("PING", new JsonObject()));
        if (!"success".equals(res.getStatus())) return 0L;
        JsonObject d = asObj(res.getData());
        return (d != null && d.has("lastSeq")) ? safeGetLong(d.get("lastSeq")) : 0L;
    }

    // --------------------------- API FILE/FOLDER OPS (bổ sung) ---------------------------

    public Response deleteFile(int fileId, Integer baseVersion) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("fileId", fileId);
        if (baseVersion != null) data.addProperty("baseVersion", baseVersion);
        addUsernameToData(data);
        return sendRequest(new Request("DELETE_FILE", data));
    }

    public Response deleteFolder(int folderId, boolean recursive, Integer baseVersion) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("recursive", recursive);
        if (baseVersion != null) data.addProperty("baseVersion", baseVersion);
        addUsernameToData(data);
        return sendRequest(new Request("DELETE_FOLDER", data));
    }

    public Response deleteFileByPath(String localPath) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("localPath", localPath);
        addUsernameToData(data);
        try {
            return sendRequest(new Request("DELETE_FILE_BY_PATH", data));
        } catch (Exception e) {
            e.printStackTrace();
            return new Response("error", "Lỗi khi gửi yêu cầu xoá file: " + e.getMessage(), null);
        }
    }

    public Response renameFile(int fileId, String newName) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("fileId", fileId);
        data.addProperty("newName", newName);
        addUsernameToData(data);
        return sendRequest(new Request("RENAME_FILE", data));
    }

    public Response renameFolder(int folderId, String newName) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("newName", newName);
        addUsernameToData(data);
        return sendRequest(new Request("RENAME_FOLDER", data));
    }

    public Response moveFile(int fileId, int targetFolderId) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("fileId", fileId);
        data.addProperty("targetFolderId", targetFolderId);
        addUsernameToData(data);
        return sendRequest(new Request("MOVE_FILE", data));
    }

    public Response moveFolder(int folderId, int targetParentId) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("targetParentId", targetParentId);
        addUsernameToData(data);
        return sendRequest(new Request("MOVE_FOLDER", data));
    }

    // ---------------------- Kết nối & gửi nhận chung ----------------------

    public boolean testConnection() {
        try {
            ClientConnectionManager m = ClientConnectionManager.getInstance();
            if (!m.isConnected()) {
                if (serverIP != null && serverPort > 0) {
                    m.connect(serverIP, serverPort);
                    return true;
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Test connection failed: " + e.getMessage());
            return false;
        }
    }

    public Response sendRequest(Request request) throws Exception {
        ClientConnectionManager m = ClientConnectionManager.getInstance();
        if (!m.isConnected()) {
            if (serverIP != null && serverPort > 0) {
                m.connect(serverIP, serverPort);
            } else {
                throw new Exception("Mất kết nối tới server và không có thông tin để kết nối lại. Vui lòng đăng nhập lại.");
            }
        }

        try {
            String requestJson = JsonUtils.toJson(request);
            if (requestJson == null || requestJson.trim().isEmpty()) {
                throw new Exception("Lỗi tạo request JSON");
            }
            System.out.println("DEBUG: Sending request: " + requestJson.substring(0, Math.min(120, requestJson.length())));
            String responseStr = m.sendRequestAndGetResponse(requestJson);
            if (responseStr == null) throw new Exception("Server không phản hồi hoặc đã ngắt kết nối");

            System.out.println("DEBUG: Received response: " + responseStr.substring(0, Math.min(120, responseStr.length())));
            Response response = JsonUtils.fromJson(responseStr, Response.class);
            if (response == null) throw new Exception("Không thể phân tích phản hồi từ server");
            return response;

        } catch (IOException e) {
            m.close();
            markOffline();
            throw new Exception("Lỗi kết nối mạng: " + e.getMessage(), e);
        } catch (Exception e) {
            // Nếu thông báo đã đầy đủ, ném lại; nếu không, bọc message chung
            String msg = e.getMessage();
            if (msg != null && (msg.startsWith("Không thể") || msg.startsWith("Mất kết nối"))) throw e;
            throw new Exception("Lỗi không xác định: " + msg, e);
        }
    }
}
