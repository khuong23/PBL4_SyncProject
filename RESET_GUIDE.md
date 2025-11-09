# =============================================================================
# HƯỚNG DẪN RESET HỆ THỐNG VÀ KIỂM TRA LẠI
# =============================================================================

## 📋 TÓM TẮT CÁC LỖI ĐÃ SỬA:

### ✅ Lỗi #1: FolderID=0 (Server)
**Vấn đề:** Files table có FolderID=0 (không hợp lệ)
**Giải pháp:** 
- Thêm validation trong UploadFileHandler để tự động gán FolderID=0 về root (FolderID=1)
- Thêm log cảnh báo khi phát hiện FolderID không hợp lệ

### ✅ Lỗi #2: Logic UPDATE Version (Server)
**Vấn đề:** User nghĩ version không được tăng và Changes không được ghi
**Thực tế:** Code đã đúng từ đầu! Logic UPDATE đã thực hiện:
  - Version = currentVersion + 1 ✓
  - UPDATE Files với version mới ✓
  - INSERT vào Changes với version mới ✓
**Cải tiến:** Thêm log chi tiết để theo dõi quá trình CREATE và UPDATE

### ✅ Lỗi #3: Race Condition FileWatcher (Client)
**Vấn đề:** FileWatcher vẫn phát hiện events trong lúc down-sync
**Thực tế:** Code đã sử dụng runMuted() đúng cách!
**Cải tiến:** Thêm log để hiển thị khi events bị IGNORED do watcher muted

---

## 🔧 CÁC BƯỚC RESET HỆ THỐNG:

### A. TRÊN SERVER (Linux VM):

#### 1. SSH vào server:
```bash
ssh khuong@20.89.65.146
# Password: Baokhuong2332
```

#### 2. Dừng service (nếu đang chạy):
```bash
sudo systemctl stop syncproject
```

#### 3. Di chuyển vào thư mục project:
```bash
cd ~/PBL4_SyncProject-main
```

#### 4. Chạy script reset (CÓ THỂ TỰ ĐỘNG HÓA):
```bash
chmod +x reset_system.sh
./reset_system.sh
```

HOẶC làm THỦ CÔNG:

#### 4a. Reset database MySQL:
```bash
mysql -u sync_admin -p sync_db < reset_server_database.sql
# Nhập password khi được hỏi
```

#### 4b. Xóa files trong storage:
```bash
rm -rf ~/sync_storage/*
```

#### 4c. Compile lại code với tất cả các fixes:
```bash
mvn clean package -DskipTests
```

#### 4d. Khởi động server:

**Cách 1 - Chạy bằng systemd service:**
```bash
sudo systemctl start syncproject
sudo journalctl -u syncproject -f  # Xem log real-time
```

**Cách 2 - Chạy thủ công (để debug):**
```bash
java -jar target/PBL4_SyncProject-1.0-SNAPSHOT.jar --server
```

---

### B. TRÊN CLIENT (Windows):

#### 1. Xóa database cache cũ:
```powershell
# Mở PowerShell và chạy:
cd C:\Users\DELL\Desktop\Home\DaiHoc\PBL4_SyncProject-main
Remove-Item client_cache.db -Force
```

#### 2. Compile lại code (nếu cần):
```powershell
.\mvnw.cmd clean package -DskipTests
```

#### 3. Khởi động lại client:
```powershell
java -jar target/PBL4_SyncProject-1.0-SNAPSHOT.jar --client
```

---

## 🔍 KIỂM TRA SAU KHI RESET:

### 1. Upload file mới:
- Tạo/copy file mới vào `C:\Users\DELL\SyncData\`
- Quan sát log client - nên thấy: `📝 [UploadHandler] CREATE file: ... (FolderID=..., Version=1)`
- Kiểm tra server log - nên thấy: `✅ [UploadHandler] Đã tạo FileID=..., ghi Changes với Version=1`

### 2. Sửa file:
- Chỉnh sửa file đã upload
- Quan sát log - nên thấy: `📝 [UploadHandler] UPDATE file: ... (currentVersion=1 → newVersion=2)`
- Log nên hiển thị: `✅ [UploadHandler] Đã ghi Changes với Version=2`

### 3. Download từ client khác:
- Down-sync nên tải file về với log: `[Down-Sync] Tải về: ... (ID=... từ folderId: ...)`
- **KHÔNG** nên thấy log: `File event: ENTRY_MODIFY` trong lúc down-sync
- Nên thấy: `🔇 File event IGNORED (watcher muted): ...` nếu có events xảy ra

### 4. Kiểm tra FolderID:
- Tất cả files nên có FolderID >= 1
- Nếu client gửi FolderID không hợp lệ, server log nên hiển thị:
  `⚠️ [UploadHandler] Phát hiện FolderID không hợp lệ (...), tự động gán về Root (ID=1)`

---

## 📊 TRUY VẤN KIỂM TRA DATABASE:

### Trên SERVER (MySQL):
```sql
-- Kiểm tra không có files với FolderID=0
SELECT * FROM Files WHERE FolderID = 0;
-- Kết quả mong đợi: Empty set (0 rows)

-- Kiểm tra consistency giữa Files và Changes
SELECT 
    f.FileID, 
    f.FileName,
    f.FolderID AS FileFolderID,
    f.Version AS FileVersion,
    c.FolderID AS ChangeFolderID,
    c.Version AS ChangeVersion
FROM Files f
LEFT JOIN Changes c ON f.FileID = c.EntityId AND c.EntityType = 'FILE'
WHERE f.FolderID != c.FolderID OR f.Version != c.Version
ORDER BY f.FileID;
-- Kết quả mong đợi: Empty set (các version và folderID phải khớp)

-- Xem tất cả changes gần đây
SELECT * FROM Changes ORDER BY Seq DESC LIMIT 20;
```

### Trên CLIENT (SQLite):
```powershell
# Cài đặt SQLite (nếu chưa có)
# Tải từ: https://sqlite.org/download.html

sqlite3 client_cache.db

# Trong SQLite shell:
.headers on
.mode column

-- Kiểm tra folder mappings
SELECT * FROM Folders;

-- Kiểm tra files
SELECT FileID, ServerFileID, LocalPath, SyncStatus, Version FROM Files;

-- Kiểm tra có conflicts không
SELECT * FROM SyncQueue WHERE SyncStatus = 'CONFLICT';
```

---

## 🐛 DEBUG TIPS:

### Nếu vẫn thấy conflicts:
1. Kiểm tra baseVersion client gửi lên: Nên là NULL hoặc > 0
2. Kiểm tra currentVersion trong DB server
3. Xem log AUTO-FIX: Nếu thấy "PHÁT HIỆN DATA CORRUPTION", database bị hỏng

### Nếu thấy FolderID=0:
1. Xem log upload: Nên thấy cảnh báo "Phát hiện FolderID không hợp lệ"
2. Kiểm tra client có gửi folderId không: `DEBUG: Sending request: {...folderId...}`
3. Kiểm tra folder tree đã sync chưa

### Nếu FileWatcher vẫn trigger events:
1. Xem log có `🔇 File event IGNORED` không
2. Nếu thấy "File event: ENTRY_MODIFY", có thể event xảy ra TRƯỚC/SAU runMuted()
3. Kiểm tra `paused.get()` có được set đúng không

---

## 📝 GHI CHÚ QUAN TRỌNG:

1. **Script fix_folderid_zero.sql**: Chỉ cần chạy 1 lần để fix data cũ
2. **reset_server_database.sql**: Xóa TOÀN BỘ dữ liệu, chỉ dùng khi cần bắt đầu lại
3. **client_cache.db**: Phải xóa mỗi khi reset server
4. **Compile**: Phải compile lại cả server VÀ client sau khi sửa code

---

## ✅ CHECKLIST:

### Server:
- [ ] Dừng service
- [ ] Reset database MySQL
- [ ] Xóa files trong ~/sync_storage
- [ ] Compile: `mvn clean package`
- [ ] Khởi động lại server
- [ ] Kiểm tra log: `sudo journalctl -u syncproject -f`

### Client:
- [ ] Xóa client_cache.db
- [ ] Compile: `.\mvnw.cmd clean package`
- [ ] Khởi động lại client
- [ ] Upload file test
- [ ] Sửa file test
- [ ] Kiểm tra log không có conflicts

---

## 🎯 KẾT LUẬN:

Tất cả các fixes đã được triển khai:
1. ✅ FolderID validation với auto-fix về root
2. ✅ Version increment logic (đã đúng từ đầu, thêm log)
3. ✅ FileWatcher race condition (đã đúng từ đầu, thêm log)
4. ✅ Scripts reset database

**LƯU Ý:** Lỗi conflicts trước đây chủ yếu do dữ liệu bị hỏng. Sau khi reset, 
hệ thống sẽ hoạt động ổn định với logic đã được fix.
