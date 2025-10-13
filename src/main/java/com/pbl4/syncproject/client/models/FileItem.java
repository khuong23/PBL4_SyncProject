package com.pbl4.syncproject.client.models;

/**
 * Model class representing a file item in the sync system
 * Tách biệt data model khỏi UI components
 */
public class FileItem {
    private String fileName;
    private String fileSize;
    private String fileType;
    private String lastModified;
    private String permissions;
    private String syncStatus;
    private String folderName; // Thêm trường folder để biết file thuộc folder nào

    public FileItem() {
        // Default constructor
    }

    public FileItem(String fileName, String fileSize, String fileType,
                    String lastModified, String permissions, String syncStatus) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileType = fileType;
        this.lastModified = lastModified;
        this.permissions = permissions;
        this.syncStatus = syncStatus;
    }

    // Constructor with folder name
    public FileItem(String fileName, String fileSize, String fileType,
                    String lastModified, String permissions, String syncStatus,
                    String folderName) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileType = fileType;
        this.lastModified = lastModified;
        this.permissions = permissions;
        this.syncStatus = syncStatus;
        this.folderName = folderName;
    }

    // Getters and setters
    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileSize() {
        return fileSize;
    }

    public void setFileSize(String fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public String getPermissions() {
        return permissions;
    }

    public void setPermissions(String permissions) {
        this.permissions = permissions;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    @Override
    public String toString() {
        return "FileItem{" +
                "fileName='" + fileName + '\'' +
                ", fileSize='" + fileSize + '\'' +
                ", fileType='" + fileType + '\'' +
                ", lastModified='" + lastModified + '\'' +
                ", permissions='" + permissions + '\'' +
                ", syncStatus='" + syncStatus + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        FileItem fileItem = (FileItem) obj;
        return fileName != null ? fileName.equals(fileItem.fileName) : fileItem.fileName == null;
    }

    @Override
    public int hashCode() {
        return fileName != null ? fileName.hashCode() : 0;
    }
}