package com.pbl4.syncproject.common.model;

import java.time.LocalDateTime;

public class Files {
    private int fileId;
    private int folderId;
    private String fileName;
    private long fileSize;
    private String fileHash;
    private LocalDateTime createdAt;
    private LocalDateTime lastModified;

    public Files() {}

    public Files(int fileId, int folderId, String fileName, long fileSize, String fileHash,
                   LocalDateTime createdAt, LocalDateTime lastModified) {
        this.fileId = fileId;
        this.folderId = folderId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.createdAt = createdAt;
        this.lastModified = lastModified;
    }

    public int getFileId() { return fileId; }
    public void setFileId(int fileId) { this.fileId = fileId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public int getFolderId() { return folderId; }
    public void setFolderId(int folderId) { this.folderId = folderId; }

    public long getSize() { return fileSize; }
    public void setSize(long size) { this.fileSize = size; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return lastModified; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.lastModified = updatedAt; }
}
