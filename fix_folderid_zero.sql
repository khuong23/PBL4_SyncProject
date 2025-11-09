-- Fix files with FolderID=0 (invalid folder reference)
-- These files should be in the root folder (FolderID=1)

-- First, check which files have FolderID=0
SELECT 
    FileID, 
    FolderID, 
    FileName, 
    Version,
    FileHash
FROM Files 
WHERE FolderID = 0;

-- Update Files table: Set FolderID=0 to FolderID=1 (root folder)
UPDATE Files 
SET FolderID = 1 
WHERE FolderID = 0;

-- Also update the Changes table to match
-- This ensures consistency between Files and Changes tables
UPDATE Changes 
SET FolderID = 1 
WHERE EntityType = 'FILE' 
  AND FolderID = 0;

-- Verify the fix
SELECT 
    f.FileID, 
    f.FolderID AS FileFolderID, 
    f.FileName,
    c.ChangeID,
    c.FolderID AS ChangeFolderID,
    c.ActionType,
    c.Version
FROM Files f
LEFT JOIN Changes c ON f.FileID = c.EntityId AND c.EntityType = 'FILE'
WHERE f.FolderID = 0 OR c.FolderID = 0;
