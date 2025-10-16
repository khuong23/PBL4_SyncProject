package com.pbl4.syncproject.client.services;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Main Sync Agent để quản lý automatic file synchronization
 * Sử dụng FileWatcher để detect changes và FileHashService để verify changes
 * Tránh polling liên tục và chỉ sync khi thực sự có thay đổi
 */
public class SyncAgent implements FileWatcherService.FileChangeListener {
    
    private final FileWatcherService fileWatcher;
    private final FileHashService hashService;
    private final NetworkService networkService;
    private final UploadManager uploadManager;
    private final SyncQueue syncQueue;
    
    // Sync configuration
    private Path syncDirectory;
    private boolean isRunning = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // Debounce mechanism để tránh sync quá nhiều lần cho cùng 1 file
    private final Map<String, ScheduledFuture<?>> pendingSyncs = new ConcurrentHashMap<>();
    private final long DEBOUNCE_DELAY_MS = 2000; // 2 seconds
    
    // File hashes để track changes
    private final Map<String, String> lastKnownHashes = new ConcurrentHashMap<>();
    
    public SyncAgent(NetworkService networkService, UploadManager uploadManager) {
        this.networkService = networkService;
        this.fileWatcher = new FileWatcherService();
        this.hashService = new FileHashService();
        this.uploadManager = uploadManager;
        this.syncQueue = new SyncQueue();
        
        // Register as listener
        fileWatcher.addListener(this);
    }
    
    /**
     * Start sync agent với sync directory
     */
    public void start(String syncDirectoryPath) throws Exception {
        if (isRunning) {
            return;
        }
        
        syncDirectory = Paths.get(syncDirectoryPath);
        
        // Ensure directory exists
        File dir = syncDirectory.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        // Start file watcher
        fileWatcher.start();
        fileWatcher.watchDirectory(syncDirectory);
        
        // Start sync queue processor
        syncQueue.start();
        
        // Calculate initial hashes
        calculateInitialHashes();
        
        isRunning = true;
        System.out.println("SyncAgent started for directory: " + syncDirectoryPath);
    }
    
    /**
     * Stop sync agent
     */
    public void stop() {
        isRunning = false;
        
        // Cancel pending syncs
        for (ScheduledFuture<?> future : pendingSyncs.values()) {
            future.cancel(false);
        }
        pendingSyncs.clear();
        
        // Stop services
        fileWatcher.stop();
        syncQueue.stop();
        scheduler.shutdown();
        
        System.out.println("SyncAgent stopped");
    }
    
    /**
     * Calculate hashes cho tất cả files hiện tại
     */
    private void calculateInitialHashes() {
        try {
            Map<String, String> hashes = hashService.calculateDirectoryHashes(syncDirectory);
            lastKnownHashes.putAll(hashes);
            System.out.println("Calculated initial hashes for " + hashes.size() + " files");
        } catch (Exception e) {
            System.err.println("Error calculating initial hashes: " + e.getMessage());
        }
    }
    
    // FileChangeListener implementation
    
    @Override
    public void onFileCreated(Path filePath) {
        scheduleSync(filePath, SyncOperation.UPLOAD);
    }
    
    @Override
    public void onFileModified(Path filePath) {
        scheduleSync(filePath, SyncOperation.UPLOAD);
    }
    
    @Override
    public void onFileDeleted(Path filePath) {
        scheduleSync(filePath, SyncOperation.DELETE);
    }
    
    /**
     * Schedule sync operation với debounce
     */
    private void scheduleSync(Path filePath, SyncOperation operation) {
        String relativePath = syncDirectory.relativize(filePath).toString();
        
        // Cancel existing pending sync cho file này
        ScheduledFuture<?> existingSync = pendingSyncs.get(relativePath);
        if (existingSync != null && !existingSync.isDone()) {
            existingSync.cancel(false);
        }
        
        // Schedule new sync after debounce delay
        ScheduledFuture<?> newSync = scheduler.schedule(() -> {
            processFileChange(filePath, operation);
            pendingSyncs.remove(relativePath);
        }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
        
        pendingSyncs.put(relativePath, newSync);
    }
    
    /**
     * Process file change with hash verification
     */
    private void processFileChange(Path filePath, SyncOperation operation) {
        try {
            File file = filePath.toFile();
            String relativePath = syncDirectory.relativize(filePath).toString();
            
            if (operation == SyncOperation.DELETE) {
                // File deleted - sync deletion
                lastKnownHashes.remove(relativePath);
                syncQueue.addTask(new SyncTask(relativePath, null, SyncOperation.DELETE));
                return;
            }
            
            // For create/modify - verify hash changed
            String currentHash = hashService.calculateFileHash(file);
            String lastHash = lastKnownHashes.get(relativePath);
            
            if (currentHash != null && !currentHash.equals(lastHash)) {
                // Hash changed - file really modified
                lastKnownHashes.put(relativePath, currentHash);
                syncQueue.addTask(new SyncTask(relativePath, file, operation));
                System.out.println("Queued sync for " + relativePath + " (hash changed)");
            } else {
                System.out.println("Skipped sync for " + relativePath + " (hash unchanged)");
            }
            
        } catch (Exception e) {
            System.err.println("Error processing file change for " + filePath + ": " + e.getMessage());
        }
    }
    
    /**
     * Manual sync specific file
     */
    public void syncFile(String relativePath) {
        try {
            Path filePath = syncDirectory.resolve(relativePath);
            File file = filePath.toFile();
            
            if (file.exists()) {
                String hash = hashService.calculateFileHash(file);
                lastKnownHashes.put(relativePath, hash);
                syncQueue.addTask(new SyncTask(relativePath, file, SyncOperation.UPLOAD));
            }
        } catch (Exception e) {
            System.err.println("Error manual syncing file: " + e.getMessage());
        }
    }
    
    /**
     * Get sync status
     */
    public SyncStatus getStatus() {
        return new SyncStatus(
            isRunning,
            syncQueue.getQueueSize(),
            syncQueue.getProcessedCount(),
            lastKnownHashes.size()
        );
    }
    
    // Inner classes
    
    public enum SyncOperation {
        UPLOAD, DELETE, DOWNLOAD
    }
    
    public static class SyncTask {
        public final String relativePath;
        public final File file;
        public final SyncOperation operation;
        public final long timestamp;
        
        public SyncTask(String relativePath, File file, SyncOperation operation) {
            this.relativePath = relativePath;
            this.file = file;
            this.operation = operation;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    public static class SyncStatus {
        public final boolean isRunning;
        public final int queueSize;
        public final long processedCount;
        public final int trackedFiles;
        
        public SyncStatus(boolean isRunning, int queueSize, long processedCount, int trackedFiles) {
            this.isRunning = isRunning;
            this.queueSize = queueSize;
            this.processedCount = processedCount;
            this.trackedFiles = trackedFiles;
        }
        
        @Override
        public String toString() {
            return String.format("SyncStatus{running=%s, queue=%d, processed=%d, tracked=%d}", 
                               isRunning, queueSize, processedCount, trackedFiles);
        }
    }
    
    /**
     * Sync Queue để xử lý sync tasks
     */
    private class SyncQueue {
        private final BlockingQueue<SyncTask> queue = new LinkedBlockingQueue<>();
        private final ExecutorService processor = Executors.newSingleThreadExecutor();
        private volatile boolean running = false;
        private long processedCount = 0;
        
        public void start() {
            running = true;
            processor.submit(this::processQueue);
        }
        
        public void stop() {
            running = false;
            processor.shutdown();
        }
        
        public void addTask(SyncTask task) {
            queue.offer(task);
        }
        
        public int getQueueSize() {
            return queue.size();
        }
        
        public long getProcessedCount() {
            return processedCount;
        }
        
        private void processQueue() {
            while (running) {
                try {
                    SyncTask task = queue.take(); // Blocking
                    processTask(task);
                    processedCount++;
                    
                    // Small delay để tránh spam server
                    Thread.sleep(500);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Error processing sync task: " + e.getMessage());
                }
            }
        }
        
        private void processTask(SyncTask task) {
            try {
                switch (task.operation) {
                    case UPLOAD:
                        if (task.file != null && task.file.exists()) {
                            networkService.uploadFile(task.file);
                            System.out.println("Uploaded: " + task.relativePath);
                        }
                        break;
                        
                    case DELETE:
                        // TODO: Implement delete file on server
                        System.out.println("Would delete: " + task.relativePath);
                        break;
                        
                    case DOWNLOAD:
                        // TODO: Implement download file from server
                        System.out.println("Would download: " + task.relativePath);
                        break;
                }
            } catch (Exception e) {
                System.err.println("Error syncing " + task.relativePath + ": " + e.getMessage());
            }
        }
    }
}