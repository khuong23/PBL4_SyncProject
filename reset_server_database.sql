-- =============================================================================
-- SCRIPT DỌN DẸP DATABASE SERVER (MySQL)
-- =============================================================================
-- Script này sẽ XÓA TOÀN BỘ dữ liệu trong bảng Files, Changes, SyncHistory, Folders
-- và RESET về trạng thái ban đầu (chỉ giữ Users và Permissions)
--
-- ⚠️ CẢNH BÁO: Script này sẽ XÓA TOÀN BỘ dữ liệu file sync!
-- Chỉ chạy khi bạn muốn bắt đầu lại từ đầu!
-- =============================================================================

USE sync_db;

-- Tắt foreign key checks để tránh lỗi khi truncate
SET FOREIGN_KEY_CHECKS = 0;

-- Xóa toàn bộ dữ liệu file sync
TRUNCATE TABLE SyncHistory;
TRUNCATE TABLE Changes;
TRUNCATE TABLE Files;

-- Xóa folders NHƯNG GIỮ LẠI root folder (FolderID=1)
DELETE FROM Folders WHERE FolderID > 1;

-- Reset root folder về trạng thái ban đầu
UPDATE Folders 
SET 
    FolderName = 'Root',
    ParentFolderID = NULL,
    FolderPath = '/',
    Version = 1,
    LastModified = NOW(),
    CreatedAt = NOW()
WHERE FolderID = 1;

-- Bật lại foreign key checks
SET FOREIGN_KEY_CHECKS = 1;

-- Hiển thị kết quả
SELECT 'Files' AS TableName, COUNT(*) AS RowCount FROM Files
UNION ALL
SELECT 'Changes', COUNT(*) FROM Changes
UNION ALL
SELECT 'Folders', COUNT(*) FROM Folders
UNION ALL
SELECT 'SyncHistory', COUNT(*) FROM SyncHistory;

SELECT '✅ Database đã được reset về trạng thái ban đầu!' AS Status;
