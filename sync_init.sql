DROP DATABASE IF EXISTS syncdb;
CREATE DATABASE syncdb
	DEFAULT CHARACTER SET utf8mb4
	DEFAULT COLLATE utf8mb4_unicode_ci;
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
                       CONSTRAINT fk_users_role
                           FOREIGN KEY (RoleID) REFERENCES Roles(RoleID)
                               ON UPDATE RESTRICT ON DELETE RESTRICT
);
-- Thêm user
INSERT INTO Users (Username, PasswordHash, Email, RoleID)
VALUES ('admin', '123', 'admin@example.com', '1'),
       ('baominh', '123', 'baominh@example.com', '2'),
       ('nguyenlong', '123', 'nguyenlong@example.com', '2');

-- Bảng thư mục
CREATE TABLE Folders (
                         FolderID INT AUTO_INCREMENT PRIMARY KEY,
                         ParentFolderID INT NULL,
                         FolderName VARCHAR(255) NOT NULL,
                         LastModified TIMESTAMP,
                         CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                         CONSTRAINT fk_folders_parent
                             FOREIGN KEY (ParentFolderID) REFERENCES Folders(FolderID)
                                 ON UPDATE CASCADE ON DELETE CASCADE,
                         CONSTRAINT uq_folder_siblings UNIQUE (ParentFolderID, FolderName)
);
-- Seed root
INSERT INTO Folders (FolderID, ParentFolderID, FolderName)
VALUES (1, NULL, 'root');

INSERT INTO Folders (ParentFolderID, FolderName) VALUES
                                                     (1, 'documents'),
                                                     (1, 'images'),
                                                     (1, 'shared'),
                                                     (1, 'videos');

-- Bảng file
CREATE TABLE Files (
                       FileID INT AUTO_INCREMENT PRIMARY KEY,
                       FolderID INT NOT NULL,
                       FileName VARCHAR(255) NOT NULL,
                       FileSize BIGINT,
                       FileHash CHAR(64), -- tối ưu cho SHA-256
                       LastModified TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
                       CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       CONSTRAINT fk_files_folder
                           FOREIGN KEY (FolderID) REFERENCES Folders(FolderID)
                               ON UPDATE CASCADE ON DELETE CASCADE,
                       CONSTRAINT uq_file_siblings UNIQUE (FolderID, FileName)
);

-- Bảng quản lý phân quyền cho FILE
CREATE TABLE FileAccessControl (
                                   FileAccessID INT AUTO_INCREMENT PRIMARY KEY,
                                   UserID INT NOT NULL,
                                   FileID INT NOT NULL,
                                   Permission ENUM('READ', 'WRITE', 'DELETE') DEFAULT 'READ',
                                   GrantedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                   CONSTRAINT fk_facc_user
                                       FOREIGN KEY (UserID) REFERENCES Users(UserID)
                                           ON UPDATE CASCADE ON DELETE CASCADE,
                                   CONSTRAINT fk_facc_file
                                       FOREIGN KEY (FileID) REFERENCES Files(FileID)
                                           ON UPDATE CASCADE ON DELETE CASCADE,
                                   CONSTRAINT uq_facc UNIQUE (UserID, FileID, Permission)
);

-- Bảng quản lý phân quyền cho FOLDER
CREATE TABLE FolderAccessControl (
                                     FolderAccessID INT AUTO_INCREMENT PRIMARY KEY,
                                     UserID INT NOT NULL,
                                     FolderID INT NOT NULL,
                                     Permission ENUM('READ', 'WRITE', 'DELETE') DEFAULT 'READ',
                                     GrantedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                     CONSTRAINT fk_folacc_user
                                         FOREIGN KEY (UserID) REFERENCES Users(UserID)
                                             ON UPDATE CASCADE ON DELETE CASCADE,
                                     CONSTRAINT fk_folacc_folder
                                         FOREIGN KEY (FolderID) REFERENCES Folders(FolderID)
                                             ON UPDATE CASCADE ON DELETE CASCADE,
                                     CONSTRAINT uq_folacc UNIQUE (UserID, FolderID, Permission)
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
                             CONSTRAINT fk_sync_user
                                 FOREIGN KEY (UserID) REFERENCES Users(UserID)
                                     ON UPDATE CASCADE ON DELETE CASCADE,
                             CONSTRAINT fk_sync_file
                                 FOREIGN KEY (FileID) REFERENCES Files(FileID)
                                     ON UPDATE CASCADE ON DELETE CASCADE,
                             CONSTRAINT fk_sync_folder
                                 FOREIGN KEY (FolderID) REFERENCES Folders(FolderID)
                                     ON UPDATE CASCADE ON DELETE CASCADE
);

-- ============================
-- Indexes cho hiệu năng JOIN
-- ============================

CREATE INDEX idx_folders_parent ON Folders(ParentFolderID);
CREATE INDEX idx_files_folder ON Files(FolderID);
CREATE INDEX idx_fileaccess_user ON FileAccessControl(UserID);
CREATE INDEX idx_fileaccess_file ON FileAccessControl(FileID);
CREATE INDEX idx_folderaccess_user ON FolderAccessControl(UserID);
CREATE INDEX idx_folderaccess_folder ON FolderAccessControl(FolderID);
CREATE INDEX idx_synchistory_user ON SyncHistory(UserID);
CREATE INDEX idx_synchistory_file ON SyncHistory(FileID);
CREATE INDEX idx_synchistory_folder ON SyncHistory(FolderID);
