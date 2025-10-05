-- Insert sample data for testing

-- Insert sample folders
INSERT INTO Folders (FolderID, ParentFolderID, FolderName, LastModified) VALUES 
(1, NULL, 'shared', NOW()),
(2, 1, 'documents', NOW()),
(3, 1, 'images', NOW()),
(4, 1, 'videos', NOW());

-- Insert sample files
INSERT INTO Files (FolderID, FileName, FileSize, FileHash, LastModified) VALUES 
-- Files in shared folder
(1, 'document1.docx', 2621440, 'hash1', DATE_SUB(NOW(), INTERVAL 1 DAY)),
(1, 'spreadsheet.xlsx', 1887436, 'hash2', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(1, 'image.png', 867328, 'hash3', DATE_SUB(NOW(), INTERVAL 3 HOUR)),
(1, 'video.mp4', 15925248, 'hash4', DATE_SUB(NOW(), INTERVAL 5 HOUR)),

-- Files in documents folder  
(2, 'report.pdf', 1048576, 'hash5', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
(2, 'presentation.pptx', 3145728, 'hash6', DATE_SUB(NOW(), INTERVAL 2 HOUR)),

-- Files in images folder
(3, 'photo1.jpg', 2097152, 'hash7', DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
(3, 'photo2.png', 1572864, 'hash8', DATE_SUB(NOW(), INTERVAL 45 MINUTE)),

-- Files in videos folder
(4, 'movie.mkv', 52428800, 'hash9', DATE_SUB(NOW(), INTERVAL 6 HOUR)),
(4, 'clip.mp4', 10485760, 'hash10', DATE_SUB(NOW(), INTERVAL 1 HOUR));

-- Insert sample users
INSERT INTO Users (Username, PasswordHash, Email, RoleID) VALUES 
('admin', '123', 'admin@example.com', 1),
('user1', '123', 'user1@example.com', 2);

-- Grant access permissions
INSERT INTO AccessControl (UserID, FileID, Permission) 
SELECT 1, FileID, 'READ_WRITE' FROM Files;

INSERT INTO AccessControl (UserID, FileID, Permission) 
SELECT 2, FileID, 'READ_ONLY' FROM Files WHERE FileID <= 5;