-- ==============================
-- KIỂM TRA DỮ LIỆU FILE ĐÃ TẢI LÊN
-- ==============================

USE syncdb;

-- 1. Kiểm tra tất cả các file trong database
SELECT 
    f.FileID,
    f.FileName,
    f.FileSize,
    ROUND(f.FileSize / 1024 / 1024, 2) AS FileSizeMB,
    f.FileHash,
    fo.FolderName AS ParentFolder,
    f.LastModified,
    f.CreatedAt
FROM Files f
JOIN Folders fo ON f.FolderID = fo.FolderID
ORDER BY f.CreatedAt DESC;

-- 2. Thống kê số lượng file theo folder
SELECT 
    fo.FolderName,
    COUNT(f.FileID) AS TotalFiles,
    SUM(f.FileSize) AS TotalSizeBytes,
    ROUND(SUM(f.FileSize) / 1024 / 1024, 2) AS TotalSizeMB
FROM Folders fo
LEFT JOIN Files f ON fo.FolderID = f.FolderID
GROUP BY fo.FolderID, fo.FolderName
ORDER BY TotalFiles DESC;

-- 3. Kiểm tra file được tải lên gần đây nhất (trong 24h)
SELECT 
    f.FileID,
    f.FileName,
    f.FileSize,
    fo.FolderName,
    f.CreatedAt,
    TIMESTAMPDIFF(MINUTE, f.CreatedAt, NOW()) AS MinutesAgo
FROM Files f
JOIN Folders fo ON f.FolderID = fo.FolderID
WHERE f.CreatedAt >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
ORDER BY f.CreatedAt DESC;

-- 4. Kiểm tra lịch sử upload từ bảng SyncHistory
SELECT 
    sh.SyncID,
    u.Username,
    f.FileName,
    fo.FolderName,
    sh.Action,
    sh.Status,
    sh.SyncTime
FROM SyncHistory sh
JOIN Users u ON sh.UserID = u.UserID
LEFT JOIN Files f ON sh.FileID = f.FileID
LEFT JOIN Folders fo ON f.FolderID = fo.FolderID
WHERE sh.Action IN ('UPLOAD_FILE', 'UPDATE_FILE')
ORDER BY sh.SyncTime DESC
LIMIT 20;

-- 5. Kiểm tra file có hash trùng lặp (để phát hiện file duplicate)
SELECT 
    f.FileHash,
    COUNT(*) AS DuplicateCount,
    GROUP_CONCAT(f.FileName) AS FileNames
FROM Files f
WHERE f.FileHash IS NOT NULL AND f.FileHash != ''
GROUP BY f.FileHash
HAVING COUNT(*) > 1;

-- 6. Kiểm tra file lớn nhất và nhỏ nhất
(SELECT 
    'LARGEST' AS Type,
    f.FileName,
    f.FileSize,
    ROUND(f.FileSize / 1024 / 1024, 2) AS FileSizeMB,
    fo.FolderName
FROM Files f
JOIN Folders fo ON f.FolderID = fo.FolderID
ORDER BY f.FileSize DESC
LIMIT 1)
UNION ALL
(SELECT 
    'SMALLEST' AS Type,
    f.FileName,
    f.FileSize,
    ROUND(f.FileSize / 1024 / 1024, 2) AS FileSizeMB,
    fo.FolderName
FROM Files f
JOIN Folders fo ON f.FolderID = fo.FolderID
ORDER BY f.FileSize ASC
LIMIT 1);

-- 7. Kiểm tra file theo user access permissions
SELECT 
    u.Username,
    f.FileName,
    fo.FolderName,
    ac.Permission,
    f.FileSize,
    f.CreatedAt
FROM AccessControl ac
JOIN Users u ON ac.UserID = u.UserID
JOIN Files f ON ac.FileID = f.FileID
JOIN Folders fo ON f.FolderID = fo.FolderID
ORDER BY u.Username, f.FileName;

-- 8. Tìm file test.txt (nếu có)
SELECT 
    f.FileID,
    f.FileName,
    f.FileSize,
    fo.FolderName,
    f.FileHash,
    f.LastModified,
    f.CreatedAt
FROM Files f
JOIN Folders fo ON f.FolderID = fo.FolderID
WHERE f.FileName LIKE '%test%'
   OR f.FileName LIKE '%.txt';

-- 9. Kiểm tra tổng dung lượng database
SELECT 
    COUNT(*) AS TotalFiles,
    SUM(FileSize) AS TotalBytes,
    ROUND(SUM(FileSize) / 1024 / 1024, 2) AS TotalMB,
    ROUND(SUM(FileSize) / 1024 / 1024 / 1024, 2) AS TotalGB,
    MIN(CreatedAt) AS FirstFileUpload,
    MAX(CreatedAt) AS LastFileUpload
FROM Files;