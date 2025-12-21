package com.pbl4.syncproject.client.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

// fix line 315
/**
 * SyncAgent (3-tier event pipeline):
 * FileWatcherService  -->  SyncAgent (pending gate)  -->  MainController (ask user / trigger sync)
 *
 * SyncAgent chỉ làm:
 *  - Listen local FS events (file + folder)
 *  - Poll remote changes (getChangesSince)
 *  - Biến đổi thành "PendingChange" và đợi user accept
 *  - Khi accept: bắn callback cho UI layer (MainController) để thực thi upload/download thật.
 *
 * Lưu ý: phần "thực thi sync" phụ thuộc vào context UI (currentDirectory / mapping folder),
 * nên được thực hiện ở MainController.
 */
public class SyncAgent implements FileWatcherService.FileChangeListener {

    public enum PendingSource { LOCAL, REMOTE }

    public enum LocalChangeType { CREATE, MODIFY, DELETE }

    public static final class PendingChange {
        public final String id;
        public final PendingSource source;

        // UI text
        public final String title;
        public final String message;

        // LOCAL payload
        public final LocalChangeType localType; // null nếu REMOTE
        public final String relativePath;        // null nếu REMOTE batch
        public final boolean isDirectory;        // LOCAL only

        // REMOTE payload (batch)
        public final long remoteSinceSeq;
        public final long remoteLastSeq;
        public final JsonArray remoteChanges;    // may be null

        public final long createdAtEpochMs;

        private PendingChange(
                String id,
                PendingSource source,
                String title,
                String message,
                LocalChangeType localType,
                String relativePath,
                boolean isDirectory,
                long remoteSinceSeq,
                long remoteLastSeq,
                JsonArray remoteChanges
        ) {
            this.id = id;
            this.source = source;
            this.title = title;
            this.message = message;
            this.localType = localType;
            this.relativePath = relativePath;
            this.isDirectory = isDirectory;
            this.remoteSinceSeq = remoteSinceSeq;
            this.remoteLastSeq = remoteLastSeq;
            this.remoteChanges = remoteChanges;
            this.createdAtEpochMs = System.currentTimeMillis();
        }

        public static PendingChange forLocal(LocalChangeType type, String relativePath, boolean isDirectory) {
            String id = UUID.randomUUID().toString();
            String title = "Local thay đổi";
            String msg = (isDirectory ? "Thư mục" : "File") + " thay đổi: " + relativePath + " (" + type + ").\n"
                    + "Bạn có muốn đồng bộ thay đổi này lên server không?";
            return new PendingChange(id, PendingSource.LOCAL, title, msg, type, relativePath, isDirectory,
                    0L, 0L, null);
        }

        public static PendingChange forRemoteBatch(long sinceSeq, long lastSeq, JsonArray changes) {
            String id = UUID.randomUUID().toString();
            int n = changes == null ? 0 : changes.size();
            String title = "Server có thay đổi";
            String msg = "Server vừa có " + n + " thay đổi (sinceSeq=" + sinceSeq + ").\n"
                    + "Bạn có muốn tải/đồng bộ các thay đổi này về máy không?";
            return new PendingChange(id, PendingSource.REMOTE, title, msg, null, null, false,
                    sinceSeq, lastSeq, changes);
        }

        public int getRemoteChangeCount() {
            return remoteChanges == null ? 0 : remoteChanges.size();
        }

        public boolean isRemoteBatch() {
            return source == PendingSource.REMOTE;
        }
    }

    public interface SyncEventListener {
        /** Có 1 pending mới -> UI nên hỏi user accept */
        default void onPendingChangeProposed(PendingChange change) {}

        /** Snapshot danh sách pending */
        default void onPendingChangesUpdated(List<PendingChange> pending) {}

        /** User đã accept LOCAL pending (MainController sẽ tự quyết định upload/delete) */
        default void onLocalPendingAccepted(PendingChange change) {}

        /** User đã accept REMOTE pending batch (MainController sẽ tự quyết định download/apply) */
        default void onRemotePendingAccepted(PendingChange change) {}

        default void onError(String message, Throwable cause) {}
    }

    private final NetworkService networkService;
    private final LocalDatabaseManager localDbManager;

    private final FileWatcherService fileWatcher = new FileWatcherService();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SyncAgent-RemotePoll");
        t.setDaemon(true);
        return t;
    });

    private volatile Path rootDir;
    private volatile SyncEventListener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean suppressWatcher = new AtomicBoolean(false);

    // Pending approval gate
    private final ConcurrentMap<String, PendingChange> pendingById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> pendingLocalByRelPath = new ConcurrentHashMap<>();
    private volatile String pendingRemoteBatchId = null;

    public SyncAgent(NetworkService networkService, LocalDatabaseManager localDbManager) {
        this.networkService = networkService;
        this.localDbManager = localDbManager;
        this.fileWatcher.addListener(this);
    }

    public void setEventListener(SyncEventListener listener) {
        this.listener = listener;
    }

    public void start(Path rootDir) throws IOException {
        if (running.get()) return;
        this.rootDir = rootDir.toAbsolutePath().normalize();
        this.fileWatcher.start();
        this.fileWatcher.watchDirectory(rootDir);
        running.set(true);

        // Poll remote changes (tùy hệ thống, bạn có thể tăng delay)
        scheduler.scheduleWithFixedDelay(this::pollRemoteChangesSafe, 1500, 3000, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running.set(false);
        try { fileWatcher.stop(); } catch (Exception ignored) {}
        scheduler.shutdownNow();
        pendingById.clear();
        pendingLocalByRelPath.clear();
        pendingRemoteBatchId = null;
    }

    /** Dùng khi apply remote xuống disk để tránh watcher bắn ngược lại */
    public void runMuted(Runnable action) {
        fileWatcher.runMuted(action);
    }

    /** Nếu muốn tắt/bật gate watcher thủ công */
    public void setSuppressWatcher(boolean suppress) {
        suppressWatcher.set(suppress);
    }

    public List<PendingChange> getPendingChanges() {
        ArrayList<PendingChange> out = new ArrayList<>(pendingById.values());
        out.sort(Comparator.comparingLong(p -> p.createdAtEpochMs));
        return out;
    }

    public void acceptPending(String pendingId) {
        PendingChange pc = pendingById.get(pendingId);
        if (pc == null) return;

        if (pc.source == PendingSource.LOCAL) {
            // remove pending now to avoid double-accept
            pendingById.remove(pendingId);
            if (pc.relativePath != null) pendingLocalByRelPath.remove(pc.relativePath, pendingId);
            notifyPendingUpdated();

            if (listener != null) listener.onLocalPendingAccepted(pc);
            return;
        }

        if (pc.source == PendingSource.REMOTE) {
            // remove pending now; nếu apply fail thì controller có thể tạo lại / re-poll
            pendingById.remove(pendingId);
            if (pendingRemoteBatchId != null && pendingRemoteBatchId.equals(pendingId)) {
                pendingRemoteBatchId = null;
            }
            notifyPendingUpdated();

            // NOTE: controller có thể setSinceSeq(remoteLastSeq) sau khi apply thành công.
            if (listener != null) listener.onRemotePendingAccepted(pc);
        }
    }

    public void dismissPending(String pendingId) {
        PendingChange pc = pendingById.remove(pendingId);
        if (pc == null) return;
        if (pc.source == PendingSource.LOCAL && pc.relativePath != null) {
            pendingLocalByRelPath.remove(pc.relativePath, pendingId);
        }
        if (pc.source == PendingSource.REMOTE && pendingRemoteBatchId != null && pendingRemoteBatchId.equals(pendingId)) {
            pendingRemoteBatchId = null;
        }
        notifyPendingUpdated();
    }

    private void notifyPendingUpdated() {
        if (listener == null) return;
        try {
            listener.onPendingChangesUpdated(getPendingChanges());
        } catch (Exception ignored) {}
    }

    // ======================================================================
    // Local FS events -> Pending
    // ======================================================================

    private void proposeLocal(LocalChangeType type, Path absolutePath, boolean isDirectory) {
        if (!running.get()) return;
        if (suppressWatcher.get()) return;
        if (rootDir == null) return;

        Path abs = absolutePath.toAbsolutePath().normalize();
        if (!abs.startsWith(rootDir)) return;

        String rel = rootDir.relativize(abs).toString().replace("\\", "/");
        if (rel.isEmpty()) return;

        // Dedup theo relativePath
        String existingId = pendingLocalByRelPath.get(rel);
        if (existingId != null) {
            pendingById.remove(existingId);
        }

        PendingChange pending = PendingChange.forLocal(type, rel, isDirectory);
        pendingById.put(pending.id, pending);
        pendingLocalByRelPath.put(rel, pending.id);
        notifyPendingUpdated();

        if (listener != null) {
            try { listener.onPendingChangeProposed(pending); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public void onFileCreated(Path filePath) {
        proposeLocal(LocalChangeType.CREATE, filePath, false);
    }

    @Override
    public void onFileModified(Path filePath) {
        proposeLocal(LocalChangeType.MODIFY, filePath, false);
    }

    @Override
    public void onFileDeleted(Path filePath) {
        proposeLocal(LocalChangeType.DELETE, filePath, false);
    }

    @Override
    public void onDirectoryCreated(Path dirPath) {
        proposeLocal(LocalChangeType.CREATE, dirPath, true);
    }

    @Override
    public void onDirectoryDeleted(Path dirPath) {
        proposeLocal(LocalChangeType.DELETE, dirPath, true);
    }

    @Override
    public void onDirectoryRenamed(Path oldDirPath, Path newDirPath) {
        // watcher hiện tại không phát hiện rename "atomically" -> thường là DELETE + CREATE
        proposeLocal(LocalChangeType.MODIFY, newDirPath, true);
    }

    // ======================================================================
    // Remote poll -> Pending batch
    // ======================================================================

    private void pollRemoteChangesSafe() {
        if (!running.get()) return;
        if (pendingRemoteBatchId != null) return; // đang đợi user accept batch trước

        long sinceSeq = 0L;
        try {
            sinceSeq = localDbManager.getSinceSeq();
        } catch (Exception ignored) {}

        try {
            Response r = networkService.getChangesSince(sinceSeq);
            if (r == null || !"success".equalsIgnoreCase(r.getStatus()) || r.getData() == null) return;


            RemoteParseResult parsed = parseRemoteResponse(r.getData(), sinceSeq);
            if (parsed == null || parsed.changes == null || parsed.changes.size() == 0) return;

            PendingChange pending = PendingChange.forRemoteBatch(sinceSeq, parsed.lastSeq, parsed.changes);
            pendingById.put(pending.id, pending);
            pendingRemoteBatchId = pending.id;

            notifyPendingUpdated();
            if (listener != null) listener.onPendingChangeProposed(pending);

        } catch (Exception e) {
            if (listener != null) listener.onError("Lỗi poll remote changes", e);
        }
    }

    private static final class RemoteParseResult {
        final JsonArray changes;
        final long lastSeq;

        RemoteParseResult(JsonArray changes, long lastSeq) {
            this.changes = changes;
            this.lastSeq = lastSeq;
        }
    }

    /**
     * Parse "data" trả về từ getChangesSince(sinceSeq).
     * Hỗ trợ vài schema phổ biến:
     *  - { changes: [...], lastSeq: 123 }
     *  - { data: { changes: [...] , lastSeq: 123 } }
     *  - [ ... ]  (treat as changes, lastSeq=sinceSeq)
     */
    private RemoteParseResult parseRemoteResponse(JsonElement data, long sinceSeq) {
        if (data == null) return null;

        if (data.isJsonArray()) {
            return new RemoteParseResult(data.getAsJsonArray(), sinceSeq);
        }

        if (!data.isJsonObject()) return null;
        JsonObject obj = data.getAsJsonObject();

        // unwrap { data: {...} }
        if (obj.has("data") && obj.get("data").isJsonObject()) {
            obj = obj.getAsJsonObject("data");
        }

        JsonArray changes = null;
        if (obj.has("changes") && obj.get("changes").isJsonArray()) {
            changes = obj.getAsJsonArray("changes");
        } else if (obj.has("items") && obj.get("items").isJsonArray()) {
            changes = obj.getAsJsonArray("items");
        }

        long lastSeq = sinceSeq;
        if (obj.has("lastSeq")) {
            try { lastSeq = obj.get("lastSeq").getAsLong(); } catch (Exception ignored) {}
        } else if (obj.has("newSinceSeq")) {
            try { lastSeq = obj.get("newSinceSeq").getAsLong(); } catch (Exception ignored) {}
        } else if (obj.has("sinceSeq")) {
            try { lastSeq = obj.get("sinceSeq").getAsLong(); } catch (Exception ignored) {}
        }

        return new RemoteParseResult(changes, lastSeq);
    }

    // ======================================================================
    // Backward-compatible helpers (MainController có thể đang gọi các API cũ)
    // ======================================================================

    public LocalDatabaseManager getLocalDbManager() {
        return localDbManager;
    }

    public long getSinceSeqForHeartbeat() {
        try { return localDbManager.getSinceSeq(); }
        catch (Exception e) { return 0L; }
    }

    public String getStatus() {
        return running.get() ? "RUNNING" : "STOPPED";
    }

    /**
     * Trigger sync queue check - called by heartbeat when remote changes detected
     */
    public void triggerSyncQueue() {
        // Force immediate poll of remote changes
        if (running.get() && pendingRemoteBatchId == null) {
            pollRemoteChangesSafe();
        }
    }

    /**
     * Force full refresh from server - called by SettingsController
     * Resets local DB and mirrors all data from server
     */
    public void forceFullRefreshFromSettings() throws Exception {
        if (!running.get()) {
            throw new IllegalStateException("SyncAgent is not running");
        }

        // Pause watcher to avoid loop
        fileWatcher.pause();
        try {
            mirrorFromServerOverwriteLocal();
        } finally {
            fileWatcher.resume();
        }
    }

    /**
     * Mirror all data from server, overwriting local files and DB
     */
    private void mirrorFromServerOverwriteLocal() throws Exception {
        System.out.println("🔄 Starting full mirror from server...");

        // 1. Clear local sync directory (keep root folder)
        if (rootDir != null && Files.exists(rootDir)) {
            try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(rootDir)) {
                walk.sorted((a, b) -> -a.compareTo(b)) // files first, then folders
                    .forEach(p -> {
                        if (!p.equals(rootDir)) {
                            try { Files.deleteIfExists(p); }
                            catch (Exception ignore) {}
                        }
                    });
            }
        }

        // 2. Reset local database schema
        localDbManager.initializeDatabase(true);

        // 3. Mirror folder tree and files from server
        mirrorFetchFoldersAndFiles();

        // 4. Get current server seq and update local since_seq
        try {
            com.pbl4.syncproject.common.jsonhandler.Response pingRes =
                networkService.send(new com.pbl4.syncproject.common.jsonhandler.Request("PING", new com.google.gson.JsonObject()));

            if (pingRes != null && "success".equalsIgnoreCase(pingRes.getStatus())) {
                com.google.gson.JsonObject data = pingRes.getData() != null && pingRes.getData().isJsonObject()
                    ? pingRes.getData().getAsJsonObject()
                    : new com.google.gson.JsonObject();

                long lastSeq = data.has("lastSeq") ? data.get("lastSeq").getAsLong() : 0L;

                if (lastSeq > 0) {
                    localDbManager.setSinceSeq(lastSeq);
                    System.out.println("✅ Mirror complete. since_seq=" + lastSeq);
                } else {
                    localDbManager.setSinceSeq(0L);
                    System.out.println("✅ Mirror complete. since_seq=0");
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not fetch lastSeq from server: " + e.getMessage());
            localDbManager.setSinceSeq(0L);
        }
    }

    /**
     * Recursively fetch folder tree and files from server
     */
    private void mirrorFetchFoldersAndFiles() throws Exception {
        // Get root folders from server
        com.pbl4.syncproject.common.jsonhandler.Response resRoots = networkService.getFolderTree(1);
        if (!"success".equals(resRoots.getStatus())) {
            resRoots = networkService.getFolderTree(0);
            if (!"success".equals(resRoots.getStatus())) {
                throw new IllegalStateException("FOLDER_TREE failed: " + resRoots.getMessage());
            }
        }

        com.google.gson.JsonArray rootFolders = null;
        com.google.gson.JsonElement dataEl = resRoots.getData();

        if (dataEl != null && dataEl.isJsonObject()) {
            com.google.gson.JsonObject d = dataEl.getAsJsonObject();
            if (d.has("folders")) {
                rootFolders = d.getAsJsonArray("folders");
            }
        }

        if (rootFolders == null && dataEl != null && dataEl.isJsonArray()) {
            rootFolders = dataEl.getAsJsonArray();
        }

        if (rootFolders == null || rootFolders.size() == 0) {
            // No folders, just mirror files in root
            mirrorFilesInFolder(1, "");
            return;
        }

        // Process each root folder
        for (com.google.gson.JsonElement el : rootFolders) {
            if (!el.isJsonObject()) continue;
            com.google.gson.JsonObject fo = el.getAsJsonObject();

            Integer serverFolderId = getJsonInt(fo, "folderId", "FolderID", "folderID", "id");
            String folderName = getJsonString(fo, "folderName", "FolderName", "name");

            if (serverFolderId == null || folderName == null || folderName.trim().isEmpty()) {
                System.err.println("Mirror root: missing or invalid folderId/folderName");
                continue;
            }

            // Skip root folder if it appears in the list to avoid replacing Root(ID=1)
            if (serverFolderId == 1) continue;

            // Create local directory
            java.nio.file.Path folderPath = rootDir.resolve(folderName);
            Files.createDirectories(folderPath);

            // Safely insert or update folder (avoid REPLACE which deletes and re-inserts)
            try (java.sql.Connection conn = localDbManager.getConnection()) {
                // Check if folder exists
                Integer existingFolderId = null;
                try (java.sql.PreparedStatement psCheck = conn.prepareStatement(
                        "SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
                    psCheck.setInt(1, serverFolderId);
                    java.sql.ResultSet rs = psCheck.executeQuery();
                    if (rs.next()) {
                        existingFolderId = rs.getInt("FolderID");
                    }
                }

                if (existingFolderId != null) {
                    // UPDATE existing folder
                    try (java.sql.PreparedStatement psUpdate = conn.prepareStatement(
                            "UPDATE Folders SET ParentFolderID = ?, FolderName = ?, LocalPath = ?, SyncStatus = 'SYNCED' WHERE FolderID = ?")) {
                        psUpdate.setInt(1, 1); // parent is root
                        psUpdate.setString(2, folderName);
                        psUpdate.setString(3, folderName);
                        psUpdate.setInt(4, existingFolderId);
                        psUpdate.executeUpdate();
                    }
                } else {
                    // INSERT new folder
                    try (java.sql.PreparedStatement psInsert = conn.prepareStatement(
                            "INSERT INTO Folders (ServerFolderID, ParentFolderID, FolderName, LocalPath, SyncStatus) " +
                            "VALUES (?, ?, ?, ?, 'SYNCED')")) {
                        psInsert.setInt(1, serverFolderId);
                        psInsert.setInt(2, 1); // parent is root
                        psInsert.setString(3, folderName);
                        psInsert.setString(4, folderName);
                        psInsert.executeUpdate();
                    }
                }
            }

            // Recursively mirror this folder
            mirrorFolderRecursive(serverFolderId, folderName);
        }

        // Mirror files in root folder
        mirrorFilesInFolder(1, "");
    }

    /**
     * Recursively mirror a folder and its children
     */
    private void mirrorFolderRecursive(int serverFolderId, String relativePath) throws Exception {
        System.out.println("\n=== mirrorFolderRecursive ===");
        System.out.println("  serverFolderId: " + serverFolderId);
        System.out.println("  relativePath: " + relativePath);

        // First, mirror files in this folder
        mirrorFilesInFolder(serverFolderId, relativePath);

        // Then, get child folders
        System.out.println("  Calling getFolderTree(" + serverFolderId + ")...");
        com.pbl4.syncproject.common.jsonhandler.Response resChild = networkService.getFolderTree(serverFolderId);
        if (!"success".equals(resChild.getStatus())) {
            System.out.println("  getFolderTree failed: " + resChild.getMessage());
            return;
        }

        com.google.gson.JsonArray childArr = null;
        com.google.gson.JsonElement dataEl = resChild.getData();

        if (dataEl != null && dataEl.isJsonObject()) {
            com.google.gson.JsonObject d = dataEl.getAsJsonObject();
            if (d.has("folders")) {
                childArr = d.getAsJsonArray("folders");
            }
        }

        if (childArr == null && dataEl != null && dataEl.isJsonArray()) {
            childArr = dataEl.getAsJsonArray();
        }

        if (childArr == null) {
            System.out.println("  No children found (childArr is null)");
            return;
        }

        System.out.println("  Server returned " + childArr.size() + " children:");
        for (com.google.gson.JsonElement el : childArr) {
            if (!el.isJsonObject()) continue;
            com.google.gson.JsonObject fo = el.getAsJsonObject();
            Integer childId = getJsonInt(fo, "folderId", "FolderID", "folderID", "id");
            String childName = getJsonString(fo, "folderName", "FolderName", "name");
            Integer childParentId = getJsonInt(fo, "parentFolderId");
            System.out.println("    - ID=" + childId + ", Name=" + childName + ", ParentID=" + childParentId);
        }

        // Process each child folder
        for (com.google.gson.JsonElement el : childArr) {
            if (!el.isJsonObject()) continue;
            com.google.gson.JsonObject fo = el.getAsJsonObject();

            Integer childServerId = getJsonInt(fo, "folderId", "FolderID", "folderID", "id");
            String name = getJsonString(fo, "folderName", "FolderName", "name");
            Integer childParentId = getJsonInt(fo, "parentFolderId", "ParentFolderID", "parentId");

            if (childServerId == null || name == null || name.trim().isEmpty()) {
                System.err.println("Mirror child: missing or invalid folderId/folderName");
                continue;
            }

            if (childServerId == 1) continue; // Skip root

            // CRITICAL FIX: Only process folders that are actual children of the current folder
            // This prevents infinite loops when server returns all folders instead of just children
            if (childParentId == null || childParentId != serverFolderId) {
                System.out.println("  Skipping folder '" + name + "' (ID=" + childServerId +
                                   ", ParentID=" + childParentId + ") - not a direct child of " + serverFolderId);
                continue;
            }

            String childRel = relativePath.isEmpty() ? name : (relativePath + "/" + name);
            java.nio.file.Path childPath = rootDir.resolve(childRel);
            Files.createDirectories(childPath);

            // Get local parent folder ID
            Integer localParentId = null;
            try (java.sql.Connection conn = localDbManager.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
                ps.setInt(1, serverFolderId);
                java.sql.ResultSet rs = ps.executeQuery();
                if (rs.next()) localParentId = rs.getInt("FolderID");
            }

            // Safely insert or update child folder (avoid REPLACE)
            try (java.sql.Connection conn = localDbManager.getConnection()) {
                // Check if folder exists
                Integer existingFolderId = null;
                try (java.sql.PreparedStatement psCheck = conn.prepareStatement(
                        "SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
                    psCheck.setInt(1, childServerId);
                    java.sql.ResultSet rs = psCheck.executeQuery();
                    if (rs.next()) {
                        existingFolderId = rs.getInt("FolderID");
                    }
                }

                if (existingFolderId != null) {
                    // UPDATE existing folder
                    try (java.sql.PreparedStatement psUpdate = conn.prepareStatement(
                            "UPDATE Folders SET ParentFolderID = ?, FolderName = ?, LocalPath = ?, SyncStatus = 'SYNCED' WHERE FolderID = ?")) {
                        if (localParentId != null) {
                            psUpdate.setInt(1, localParentId);
                        } else {
                            psUpdate.setNull(1, java.sql.Types.INTEGER);
                        }
                        psUpdate.setString(2, name);
                        psUpdate.setString(3, childRel);
                        psUpdate.setInt(4, existingFolderId);
                        psUpdate.executeUpdate();
                    }
                } else {
                    // INSERT new folder
                    try (java.sql.PreparedStatement psInsert = conn.prepareStatement(
                            "INSERT INTO Folders (ServerFolderID, ParentFolderID, FolderName, LocalPath, SyncStatus) " +
                            "VALUES (?, ?, ?, ?, 'SYNCED')")) {
                        psInsert.setInt(1, childServerId);
                        if (localParentId != null) {
                            psInsert.setInt(2, localParentId);
                        } else {
                            psInsert.setNull(2, java.sql.Types.INTEGER);
                        }
                        psInsert.setString(3, name);
                        psInsert.setString(4, childRel);
                        psInsert.executeUpdate();
                    }
                }
            }

            // Recursively mirror child folder
            mirrorFolderRecursive(childServerId, childRel);
        }
    }

    /**
     * Mirror all files in a specific folder
     */
    private void mirrorFilesInFolder(int serverFolderId, String relativePath) throws Exception {
        com.pbl4.syncproject.common.jsonhandler.Response res = networkService.getFileList(serverFolderId);
        if (!"success".equals(res.getStatus())) return;

        com.google.gson.JsonArray filesArr = null;
        com.google.gson.JsonElement dataEl = res.getData();

        if (dataEl != null && dataEl.isJsonObject()) {
            com.google.gson.JsonObject d = dataEl.getAsJsonObject();
            if (d.has("files")) {
                filesArr = d.getAsJsonArray("files");
            }
        }

        if (filesArr == null && dataEl != null && dataEl.isJsonArray()) {
            filesArr = dataEl.getAsJsonArray();
        }

        if (filesArr == null) return;

        // Get local folder ID
        Integer localFolderId = null;
        try (java.sql.Connection conn = localDbManager.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
            ps.setInt(1, serverFolderId);
            java.sql.ResultSet rs = ps.executeQuery();
            if (rs.next()) localFolderId = rs.getInt("FolderID");
        }

        if (localFolderId == null) localFolderId = 1; // fallback to root

        // Download each file
        for (com.google.gson.JsonElement el : filesArr) {
            if (!el.isJsonObject()) continue;
            com.google.gson.JsonObject fo = el.getAsJsonObject();

            Integer fileId = getJsonInt(fo, "fileId", "FileID", "id");
            String name = getJsonString(fo, "fileName", "FileName", "name");
            Long size = getJsonLong(fo, "fileSize", "size");
            String hash = getJsonString(fo, "fileHash", "hash");

            if (fileId == null || name == null) {
                System.err.println("Mirror files: missing fileId/fileName");
                continue;
            }

            String relPath = relativePath.isEmpty() ? name : (relativePath + "/" + name);

            // Download file using NetworkService directly
            try {
                byte[] fileData = networkService.downloadFile(fileId);
                java.nio.file.Path targetPath = rootDir.resolve(relPath);
                Files.createDirectories(targetPath.getParent());

                // Write file in muted mode
                runMuted(() -> {
                    try {
                        Files.write(targetPath, fileData,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                // Update local DB
                localDbManager.upsertDownloadedFile(
                    fileId,
                    localFolderId,
                    name,
                    size != null ? size : 0L,
                    relPath.replace("\\", "/"),
                    hash != null ? hash : ""
                );

                System.out.println("✓ Mirrored: " + relPath);

            } catch (Exception e) {
                System.err.println("Failed to mirror file " + name + ": " + e.getMessage());
            }
        }
    }

    // Helper methods for JSON parsing
    private Integer getJsonInt(com.google.gson.JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try { return obj.get(key).getAsInt(); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String getJsonString(com.google.gson.JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try { return obj.get(key).getAsString(); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    private Long getJsonLong(com.google.gson.JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try { return obj.get(key).getAsLong(); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }
}
