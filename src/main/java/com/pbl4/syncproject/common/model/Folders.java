package com.pbl4.syncproject.common.model;

import java.time.LocalDateTime;

public class Folders {
    private int folderId;
    private String folderName;
    private Integer parentId;
    private LocalDateTime createdAt;
    private LocalDateTime lastModified;

    public Folders() {}

    public Folders(int folderId, String folderName, Integer parentId,
                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.folderId = folderId;
        this.folderName = folderName;
        this.parentId = parentId;
        this.createdAt = createdAt;
        this.lastModified = updatedAt;
    }

    public int getFolderId() { return folderId; }
    public void setFolderId(int folderId) { this.folderId = folderId; }

    public String getFolderName() { return folderName; }
    public void setFolderName(String folderName) { this.folderName = folderName; }

    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) { this.parentId = parentId; }


    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return lastModified; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.lastModified = updatedAt; }
}
