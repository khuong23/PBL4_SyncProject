package com.pbl4.syncproject.client.services;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service để tính và quản lý file hashes
 * Dùng để detect file changes một cách chính xác
 */
public class FileHashService {
    
    // Cache để lưu file hashes để tránh tính lại liên tục
    private final Map<String, String> fileHashCache = new ConcurrentHashMap<>();
    
    // Map để lưu last modified time
    private final Map<String, Long> lastModifiedCache = new ConcurrentHashMap<>();
    
    /**
     * Tính MD5 hash của file
     */
    public String calculateFileHash(File file) throws Exception {
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        
        String filePath = file.getAbsolutePath();
        long lastModified = file.lastModified();
        
        // Check cache trước - nếu file không đổi thì dùng cache
        if (lastModifiedCache.containsKey(filePath) && 
            lastModifiedCache.get(filePath) == lastModified &&
            fileHashCache.containsKey(filePath)) {
            return fileHashCache.get(filePath);
        }
        
        // Tính hash mới
        String hash = computeMD5Hash(file);
        
        // Update cache
        fileHashCache.put(filePath, hash);
        lastModifiedCache.put(filePath, lastModified);
        
        return hash;
    }
    
    /**
     * Compute MD5 hash của file
     */
    private String computeMD5Hash(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        
        // Convert byte array to hex string
        byte[] hashBytes = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        
        return sb.toString();
    }
    
    /**
     * Kiểm tra file có thay đổi không so với hash đã lưu
     */
    public boolean hasFileChanged(File file, String expectedHash) {
        try {
            String currentHash = calculateFileHash(file);
            return currentHash != null && !currentHash.equals(expectedHash);
        } catch (Exception e) {
            System.err.println("Error checking file hash for " + file.getName() + ": " + e.getMessage());
            return true; // Assume changed if error
        }
    }
    
    /**
     * Lấy hash từ cache (không tính lại)
     */
    public String getCachedHash(String filePath) {
        return fileHashCache.get(filePath);
    }
    
    /**
     * Clear cache cho file cụ thể
     */
    public void clearCache(String filePath) {
        fileHashCache.remove(filePath);
        lastModifiedCache.remove(filePath);
    }
    
    /**
     * Clear toàn bộ cache
     */
    public void clearAllCache() {
        fileHashCache.clear();
        lastModifiedCache.clear();
    }
    
    /**
     * Tính hash cho tất cả files trong directory
     */
    public Map<String, String> calculateDirectoryHashes(Path directory) throws Exception {
        Map<String, String> hashes = new HashMap<>();
        
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return hashes;
        }
        
        Files.walk(directory)
            .filter(Files::isRegularFile)
            .forEach(filePath -> {
                try {
                    File file = filePath.toFile();
                    String hash = calculateFileHash(file);
                    if (hash != null) {
                        // Sử dụng relative path để consistent across systems
                        String relativePath = directory.relativize(filePath).toString();
                        hashes.put(relativePath, hash);
                    }
                } catch (Exception e) {
                    System.err.println("Error calculating hash for " + filePath + ": " + e.getMessage());
                }
            });
        
        return hashes;
    }
    
    /**
     * So sánh 2 hash maps để tìm differences
     */
    public Set<String> findChangedFiles(Map<String, String> oldHashes, Map<String, String> newHashes) {
        Set<String> changedFiles = new HashSet<>();
        
        // Check for modified and new files
        for (Map.Entry<String, String> entry : newHashes.entrySet()) {
            String filePath = entry.getKey();
            String newHash = entry.getValue();
            String oldHash = oldHashes.get(filePath);
            
            if (oldHash == null || !oldHash.equals(newHash)) {
                changedFiles.add(filePath);
            }
        }
        
        // Check for deleted files
        for (String filePath : oldHashes.keySet()) {
            if (!newHashes.containsKey(filePath)) {
                changedFiles.add(filePath);
            }
        }
        
        return changedFiles;
    }
}