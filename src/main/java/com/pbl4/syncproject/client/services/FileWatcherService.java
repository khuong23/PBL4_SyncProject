package com.pbl4.syncproject.client.services;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service để monitor file system changes sử dụng Java WatchService
 * Detect real-time file create, modify, delete events
 */
public class FileWatcherService {
    
    private WatchService watchService;
    private final Map<WatchKey, Path> keyToPathMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isRunning = false;
    
    // Callback interfaces cho different events
    public interface FileChangeListener {
        void onFileCreated(Path filePath);
        void onFileModified(Path filePath);
        void onFileDeleted(Path filePath);
    }
    
    private final Set<FileChangeListener> listeners = ConcurrentHashMap.newKeySet();
    
    /**
     * Start watching service
     */
    public void start() throws IOException {
        if (isRunning) {
            return;
        }
        
        watchService = FileSystems.getDefault().newWatchService();
        isRunning = true;
        
        // Start monitoring thread
        executor.submit(this::watchLoop);
        System.out.println("FileWatcher service started");
    }
    
    /**
     * Stop watching service
     */
    public void stop() {
        isRunning = false;
        
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing watch service: " + e.getMessage());
        }
        
        executor.shutdown();
        System.out.println("FileWatcher service stopped");
    }
    
    /**
     * Add directory để watch
     */
    public void watchDirectory(Path directory) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Directory does not exist: " + directory);
        }
        
        // Register directory và subdirectories
        registerRecursive(directory);
        System.out.println("Started watching directory: " + directory);
    }
    
    /**
     * Register directory và tất cả subdirectories
     */
    private void registerRecursive(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                registerDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * Register single directory
     */
    private void registerDirectory(Path directory) throws IOException {
        WatchKey key = directory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        
        keyToPathMap.put(key, directory);
    }
    
    /**
     * Add listener cho file change events
     */
    public void addListener(FileChangeListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Remove listener
     */
    public void removeListener(FileChangeListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Main watch loop
     */
    private void watchLoop() {
        while (isRunning) {
            try {
                WatchKey key = watchService.take(); // Blocking call
                Path directory = keyToPathMap.get(key);
                
                if (directory == null) {
                    continue;
                }
                
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path fullPath = directory.resolve(fileName);
                    
                    // Handle different event types
                    handleFileEvent(kind, fullPath);
                    
                    // If new directory was created, register it too
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                        try {
                            registerRecursive(fullPath);
                        } catch (IOException e) {
                            System.err.println("Error registering new directory: " + e.getMessage());
                        }
                    }
                }
                
                // Reset key
                boolean valid = key.reset();
                if (!valid) {
                    keyToPathMap.remove(key);
                    if (keyToPathMap.isEmpty()) {
                        break;
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error in watch loop: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handle file system events
     */
    private void handleFileEvent(WatchEvent.Kind<?> kind, Path filePath) {
        // Ignore temporary và hidden files
        String fileName = filePath.getFileName().toString();
        if (fileName.startsWith(".") || fileName.endsWith(".tmp") || fileName.endsWith(".swp")) {
            return;
        }
        
        // Only process regular files
        if (!Files.isRegularFile(filePath) && kind != StandardWatchEventKinds.ENTRY_DELETE) {
            return;
        }
        
        System.out.println("File event: " + kind.name() + " - " + filePath);
        
        // Notify listeners
        for (FileChangeListener listener : listeners) {
            try {
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    listener.onFileCreated(filePath);
                } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    listener.onFileModified(filePath);
                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    listener.onFileDeleted(filePath);
                }
            } catch (Exception e) {
                System.err.println("Error in file change listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Check if service is running
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Get số directories đang được watched
     */
    public int getWatchedDirectoryCount() {
        return keyToPathMap.size();
    }
}