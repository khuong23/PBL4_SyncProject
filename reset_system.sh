#!/bin/bash

# =============================================================================
# SCRIPT RESET TOÀN BỘ HỆ THỐNG (SERVER + CLIENT)
# =============================================================================
# Script này sẽ:
# 1. Dừng server
# 2. Reset database MySQL trên server
# 3. Xóa tất cả files trong storage
# 4. Compile lại code
# 5. Khởi động lại server
#
# ⚠️ CẢNH BÁO: Script này sẽ XÓA TOÀN BỘ dữ liệu!
# =============================================================================

echo "======================================================================"
echo "  RESET TOÀN BỘ HỆ THỐNG SYNC"
echo "======================================================================"
echo ""
echo "⚠️  CẢNH BÁO: Script này sẽ XÓA TOÀN BỘ dữ liệu file sync!"
echo "⚠️  Bạn có chắc chắn muốn tiếp tục?"
echo ""
read -p "Nhập 'YES' để xác nhận: " confirm

if [ "$confirm" != "YES" ]; then
    echo "❌ Đã hủy."
    exit 1
fi

echo ""
echo "======================================================================"
echo "Bước 1: Dừng server..."
echo "======================================================================"
sudo systemctl stop syncproject || echo "⚠️ Service chưa chạy hoặc chưa được tạo"

echo ""
echo "======================================================================"
echo "Bước 2: Reset database MySQL..."
echo "======================================================================"
mysql -u sync_admin -p sync_db < reset_server_database.sql
if [ $? -ne 0 ]; then
    echo "❌ Lỗi khi reset database!"
    exit 1
fi

echo ""
echo "======================================================================"
echo "Bước 3: Xóa files trong storage..."
echo "======================================================================"
# Thay đổi đường dẫn này theo cấu hình server của bạn
STORAGE_PATH="/home/khuong/sync_storage"

if [ -d "$STORAGE_PATH" ]; then
    echo "Đang xóa: $STORAGE_PATH"
    rm -rf "$STORAGE_PATH"/*
    echo "✅ Đã xóa toàn bộ files"
else
    echo "⚠️ Thư mục storage không tồn tại: $STORAGE_PATH"
    echo "Tạo thư mục mới..."
    mkdir -p "$STORAGE_PATH"
fi

echo ""
echo "======================================================================"
echo "Bước 4: Compile lại code..."
echo "======================================================================"
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo "❌ Lỗi khi compile!"
    exit 1
fi

echo ""
echo "======================================================================"
echo "Bước 5: Khởi động lại server..."
echo "======================================================================"
sudo systemctl start syncproject || echo "⚠️ Không thể khởi động service, hãy chạy thủ công"

echo ""
echo "======================================================================"
echo "✅ HOÀN THÀNH!"
echo "======================================================================"
echo ""
echo "📋 HƯỚNG DẪN TIẾP THEO:"
echo "   1. Trên CLIENT (Windows): Xóa file client_cache.db"
echo "      Đường dẫn: C:\\Users\\DELL\\Desktop\\Home\\DaiHoc\\PBL4_SyncProject-main\\client_cache.db"
echo ""
echo "   2. Khởi động lại client"
echo ""
echo "   3. Kiểm tra log server:"
echo "      sudo journalctl -u syncproject -f"
echo ""
echo "   hoặc nếu chạy thủ công:"
echo "      cd ~/PBL4_SyncProject-main"
echo "      java -jar target/PBL4_SyncProject-1.0-SNAPSHOT.jar --server"
echo ""
