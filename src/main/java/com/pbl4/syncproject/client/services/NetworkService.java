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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class NetworkService {

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    private String serverIp;
    private int serverPort;
    private String currentUsername;

    private final BooleanProperty onlineProperty = new SimpleBooleanProperty(false);

    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Heartbeat");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> heartbeatFuture;

    public BooleanProperty onlineProperty() {
        return onlineProperty;
    }

    // Backwards-compatible alias many controllers expect
    public BooleanProperty isOnlineProperty() {
        return onlineProperty();
    }

    public boolean isOnline() {
        return onlineProperty.get();
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    /** Được gọi sau khi login thành công. */
    public void configure(String ip, int port, String username) throws IOException {
        this.serverIp = ip;
        this.serverPort = port;
        this.currentUsername = username;
        ClientConnectionManager.getInstance().connect(ip, port);
        // start a simple heartbeat with no callbacks to keep onlineProperty updated
        startHeartbeat();
    }

    // ------------------ HEARTBEAT ------------------

    // Make a public no-arg startHeartbeat for older callers
    public void startHeartbeat() {
        startHeartbeat(null, null);
    }

    /**
     * Start heartbeat that optionally notifies when server's lastSeq > client's sinceSeq
     * @param onRemoteChange runnable executed on JavaFX thread when remote changes detected (maybe null)
     * @param getSinceSeq supplier that returns sinceSeq to compare against lastSeq from server (may be null)
     */
    public void startHeartbeat(Runnable onRemoteChange, Supplier<Long> getSinceSeq) {
        // cancel any existing heartbeat
        if (heartbeatFuture != null && !heartbeatFuture.isDone()) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }

        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                Response res = send(new Request("PING", new JsonObject()));
                boolean ok = res != null && "success".equalsIgnoreCase(res.getStatus());

                // update online property on JavaFX thread
                Platform.runLater(() -> onlineProperty.set(ok));

                if (ok && onRemoteChange != null && getSinceSeq != null) {
                    try {
                        JsonObject data = asObject(res.getData());
                        long lastSeq = data.has("lastSeq") ? data.get("lastSeq").getAsLong() : 0L;
                        long since = 0L;
                        try {
                            since = getSinceSeq.get();
                        } catch (Exception e) {
                            System.err.println("[Heartbeat] error getting sinceSeq: " + e.getMessage());
                        }
                        if (lastSeq > since) {
                            Platform.runLater(onRemoteChange);
                        }
                    } catch (Exception e) {
                        // ignore parse errors
                        System.err.println("[Heartbeat] parse error: " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                Platform.runLater(() -> onlineProperty.set(false));
                System.err.println("[Heartbeat] " + e.getMessage());
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    public void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    // ------------------ CORE SEND ------------------

    public Response send(Request req) throws Exception {
        ensureConfigured();

        JsonObject data = asObject(req.getData());
        // auto attach username
        if (currentUsername != null && !currentUsername.isBlank()) {
            data.addProperty("username", currentUsername);
        }
        req.setData(data);

        String jsonReq = JsonUtils.toJson(req);
        String jsonRes;

        try {
            jsonRes = ClientConnectionManager
                    .getInstance()
                    .sendRequestAndGetResponse(jsonReq);
        } catch (IOException e) {
            Platform.runLater(() -> onlineProperty.set(false));
            throw new Exception("Mất kết nối tới server: " + e.getMessage(), e);
        }

        Response res = JsonUtils.fromJson(jsonRes, Response.class);
        if (res != null && "success".equalsIgnoreCase(res.getStatus())) {
            Platform.runLater(() -> onlineProperty.set(true));
        }
        return res;
    }

    /** Backwards-compatible wrapper name used by some callers */
    public Response sendRequest(Request req) throws Exception {
        return send(req);
    }

    private void ensureConfigured() throws Exception {
        if (serverIp == null || serverIp.isBlank() || serverPort <= 0) {
            throw new Exception("NetworkService chưa được configure(serverIp, port, username)");
        }
    }

    private JsonObject asObject(JsonElement el) {
        if (el == null || el.isJsonNull()) return new JsonObject();
        if (el.isJsonObject()) return el.getAsJsonObject();
        JsonObject o = new JsonObject();
        o.add("value", el);
        return o;
    }

    // ------------------ API: Change feed ------------------

    public Response getChangesSince(long sinceSeq) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("sinceSeq", sinceSeq);
        return send(new Request("GET_CHANGES_SINCE", data));
    }

    // ------------------ API: File list ------------------

    public Response getFileList() throws Exception {
        return send(new Request("GET_FILE_LIST", new JsonObject()));
    }

    public Response getFileList(int folderId) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        return send(new Request("GET_FILE_LIST", data));
    }

    // ------------------ API: Folder tree ------------------

    public Response getFolderTree(int parentFolderId) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("parentFolderId", parentFolderId);
        return send(new Request("FOLDER_TREE", data));
    }

    // Convenience overload
    public Response getFolderTree() throws Exception {
        return getFolderTree(0);
    }

    // ------------------ API: Upload ------------------

    public Response uploadFile(File file,
                               int folderId,
                               String clientBaseHash,
                               int baseVersion) throws Exception {

        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File không tồn tại: " + file.getAbsolutePath());
        }

        long size = file.length();
        if (size > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File quá lớn (>100MB): " + file.getName());
        }

        byte[] content = Files.readAllBytes(file.toPath());
        String b64 = Base64.getEncoder().encodeToString(content);

        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);
        data.addProperty("fileName", file.getName());
        data.addProperty("fileSize", size);
        data.addProperty("fileContent", b64);

        if (clientBaseHash != null) data.addProperty("baseHash", clientBaseHash);
        if (baseVersion >= 0)    data.addProperty("baseVersion", baseVersion);

        return send(new Request("UPLOAD_FILE", data));
    }

    // ------------------ API: Download ------------------

    /**
     * Older convenience: download and return raw bytes
     */
    public byte[] downloadFile(int fileId) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("fileId", fileId);

        Response res = send(new Request("DOWNLOAD_FILE", data));
        if (res == null || !"success".equalsIgnoreCase(res.getStatus())) {
            throw new Exception("Tải file thất bại: " + (res != null ? res.getMessage() : "null response"));
        }

        JsonObject body = asObject(res.getData());
        String b64 = body.get("fileContent").getAsString();
        return Base64.getDecoder().decode(b64);
    }

    /**
     * Overload used by DownloadService: returns full Response so caller can read hash/version/folderId
     */
    public Response downloadFile(int fileId, String originalFileName, int folderId) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("fileId", fileId);
        if (originalFileName != null) data.addProperty("fileName", originalFileName);
        data.addProperty("folderId", folderId);
        return send(new Request("DOWNLOAD_FILE", data));
    }

    // ------------------ API: helper ghi file vật lý ------------------

    public Path downloadFileTo(Path targetPath, int fileId) throws Exception {
        byte[] data = downloadFile(fileId);
        Files.createDirectories(targetPath.getParent());
        Files.write(targetPath, data,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return targetPath;
    }

    // ------------------ Misc helpers ------------------

    public boolean testConnection() {
        try {
            return ClientConnectionManager.getInstance().isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public Response createFolder(String folderName) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("folderName", folderName);
        return send(new Request("CREATE_FOLDER", data));
    }

    public Response createFolder(String folderName, Integer parentId) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("folderName", folderName);
        if (parentId != null) data.addProperty("parentFolderId", parentId);
        return send(new Request("CREATE_FOLDER", data));
    }
}
