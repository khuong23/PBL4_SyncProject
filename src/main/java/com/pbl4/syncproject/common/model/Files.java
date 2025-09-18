package com.pbl4.syncproject.common.model;

import java.time.LocalDateTime;

public class Files {
    private int fileId;
    private String fileName;
    private int folderId;
    private int ownerId;
    private long size;
    private String fileHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Files() {}

    public Files(int fileId, String fileName, int folderId, int ownerId, long size, String fileHash,
                      LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.folderId = folderId;
        this.ownerId = ownerId;
        this.size = size;
        this.fileHash = fileHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public int getFileId() { return fileId; }
    public void setFileId(int fileId) { this.fileId = fileId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public int getFolderId() { return folderId; }
    public void setFolderId(int folderId) { this.folderId = folderId; }

    public int getOwnerId() { return ownerId; }
    public void setOwnerId(int ownerId) { this.ownerId = ownerId; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
