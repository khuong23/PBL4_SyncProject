DROP DATABASE IF EXISTS syncdb;
CREATE DATABASE syncdb;
USE syncdb;

-- Bảng phân loại vai trò 
CREATE TABLE Roles (
    RoleID INT AUTO_INCREMENT PRIMARY KEY,
    RoleName VARCHAR(50) UNIQUE NOT NULL
);

-- Thêm sẵn dữ liệu role
INSERT INTO Roles (RoleName) VALUES ('ADMIN'), ('USER'), ('VIEWER');

-- Bảng người dùng
CREATE TABLE Users (
    UserID INT AUTO_INCREMENT PRIMARY KEY,
    Username VARCHAR(50) UNIQUE NOT NULL,
    PasswordHash VARCHAR(255) NOT NULL,
    Email VARCHAR(100) UNIQUE,
    RoleID INT NOT NULL, -- thay ENUM Role bằng bảng Role
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (RoleID) REFERENCES Roles(RoleID)
);

-- Bảng thư mục
CREATE TABLE Folders (
    FolderID INT AUTO_INCREMENT PRIMARY KEY,
    UserID INT NOT NULL,
    ParentFolderID INT NULL,
    FolderName VARCHAR(255) NOT NULL,
    LastModified TIMESTAMP,
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (UserID) REFERENCES Users(UserID),
    FOREIGN KEY (ParentFolderID) REFERENCES Folders(FolderID) ON DELETE CASCADE
);

-- Bảng file
CREATE TABLE Files (
    FileID INT AUTO_INCREMENT PRIMARY KEY,
    FolderID INT NOT NULL,
    FileName VARCHAR(255) NOT NULL,
    FileSize BIGINT,
    FileHash CHAR(64), -- tối ưu cho SHA-256
    LastModified TIMESTAMP,
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (FolderID) REFERENCES Folders(FolderID) ON DELETE CASCADE
);

-- Bảng quản lý phân quyền cho FILE
CREATE TABLE AccessControl (
    AccessID INT AUTO_INCREMENT PRIMARY KEY,
    UserID INT NOT NULL,
    FileID INT NOT NULL,
    Permission ENUM('READ', 'WRITE', 'DELETE') DEFAULT 'READ',
    GrantedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (UserID) REFERENCES Users(UserID),
    FOREIGN KEY (FileID) REFERENCES Files(FileID),
    UNIQUE KEY uq_access (UserID, FileID, Permission) -- ngăn trùng lặp quyền
);

-- Bảng quản lý phân quyền cho FOLDER
CREATE TABLE FolderAccessControl (
    AccessID INT AUTO_INCREMENT PRIMARY KEY,
    UserID INT NOT NULL,
    FolderID INT NOT NULL,
    Permission ENUM('READ', 'WRITE', 'DELETE') DEFAULT 'READ',
    GrantedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (UserID) REFERENCES Users(UserID),
    FOREIGN KEY (FolderID) REFERENCES Folders(FolderID),
    UNIQUE KEY uq_folder_access (UserID, FolderID, Permission)
);

-- Bảng lịch sử đồng bộ (cho cả File và Folder)
CREATE TABLE SyncHistory (
    SyncID INT AUTO_INCREMENT PRIMARY KEY,
    UserID INT NOT NULL,
    FileID INT NULL,
    FolderID INT NULL,
    Action ENUM(
        'UPLOAD_FILE', 'DOWNLOAD_FILE', 'DELETE_FILE', 'UPDATE_FILE',
        'CREATE_FOLDER', 'DELETE_FOLDER', 'UPDATE_FOLDER'
    ) NOT NULL,
    Status ENUM('SUCCESS', 'FAILED', 'IN_PROGRESS') DEFAULT 'SUCCESS',
    SyncTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (UserID) REFERENCES Users(UserID) ON DELETE CASCADE,
    FOREIGN KEY (FileID) REFERENCES Files(FileID) ON DELETE CASCADE,
    FOREIGN KEY (FolderID) REFERENCES Folders(FolderID) ON DELETE CASCADE,
    CHECK (
        (FileID IS NOT NULL AND FolderID IS NULL) OR
        (FileID IS NULL AND FolderID IS NOT NULL)
    )
);

-- ============================
-- Indexes cho hiệu năng JOIN
-- ============================
CREATE INDEX idx_folders_user ON Folders(UserID);
CREATE INDEX idx_folders_parent ON Folders(ParentFolderID);
CREATE INDEX idx_files_folder ON Files(FolderID);
CREATE INDEX idx_access_user ON AccessControl(UserID);
CREATE INDEX idx_access_file ON AccessControl(FileID);
CREATE INDEX idx_folderaccess_user ON FolderAccessControl(UserID);
CREATE INDEX idx_folderaccess_folder ON FolderAccessControl(FolderID);
CREATE INDEX idx_synchistory_user ON SyncHistory(UserID);
CREATE INDEX idx_synchistory_file ON SyncHistory(FileID);
CREATE INDEX idx_synchistory_folder ON SyncHistory(FolderID);
