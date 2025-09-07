# Hệ thống đồng bộ dữ liệu - PBL4

## Đề tài 508: Tìm hiểu Hệ điều hành Windows/Linux và xây dựng ứng dụng tự động đồng bộ dữ liệu trên nhiều máy trong mạng

### Mô tả đề tài
Một nhóm người trong Công ty cần chia sẻ dữ liệu với nhau và dữ liệu được tự động đồng bộ với các thư mục chia sẻ trong mạng cục bộ (LAN)/ mạng diện rộng (WAN)/ Internet.

### Yêu cầu
- ✅ Tìm hiểu và sử dụng chồng giao thức TCP/IP
- ✅ Xây dựng chương trình kết nối với nhau qua LAN/WAN/Internet
- ✅ Mỗi tác tử (agent) trong chương trình sẽ quản lý một thư mục chia sẻ trên máy cục bộ
- ✅ Dữ liệu trên thư mục chia sẻ của mỗi máy được đồng bộ với thư mục chia sẻ trên các máy khác
- ✅ Chương trình trên máy cục bộ sẽ quản lý và cung cấp thông tin file, liệt kê danh sách file, cho phép tải về và có thể chỉnh sửa (nếu được cấp quyền) và tự động đồng bộ

### Tính năng chính

#### 🔐 Xác thực và bảo mật
- Đăng nhập với tài khoản và mật khẩu
- Mã hóa dữ liệu khi truyền (AES-256, RSA-2048)
- Quản lý quyền truy cập chi tiết (Đọc/Ghi/Sửa/Xóa)
- Ghi log truy cập và thay đổi file

#### 📁 Quản lý file và thư mục
- Hiển thị danh sách file trong thư mục đồng bộ
- Tải lên/tải xuống file
- Tạo thư mục mới
- Đổi tên, xóa file/thư mục
- Tìm kiếm file theo tên
- Sắp xếp file theo tên, kích thước, ngày sửa đổi

#### 🔄 Đồng bộ dữ liệu
- Đồng bộ tự động theo khoảng thời gian
- Đồng bộ thủ công khi cần thiết
- Xử lý xung đột file (file mới hơn thắng, hỏi người dùng, giữ cả hai)
- Hiển thị trạng thái đồng bộ realtime
- Hỗ trợ đồng bộ các loại file khác nhau

#### 🌐 Kết nối mạng
- Kết nối TCP/IP
- Tự động kết nối lại khi mất kết nối
- Cấu hình IP và Port linh hoạt
- Kiểm tra trạng thái kết nối

#### ⚙️ Cài đặt linh hoạt
- Cấu hình mạng (IP, Port, Timeout)
- Cài đặt đồng bộ (tần suất, loại file)
- Cài đặt bảo mật (mã hóa, quyền truy cập)
- Cài đặt giao diện (ngôn ngữ, chủ đề)

### Cấu trúc dự án

```
src/
├── main/
│   ├── java/
│   │   └── com/pbl4/syncproject/
│   │       ├── client/
│   │       │   ├── ClientApp.java                 # Ứng dụng client chính
│   │       │   └── controllers/
│   │       │       ├── LoginController.java       # Xử lý đăng nhập
│   │       │       ├── MainController.java        # Giao diện chính
│   │       │       ├── UserPermissionController.java # Quản lý quyền
│   │       │       └── SettingsController.java    # Cài đặt ứng dụng
│   │       └── server/
│   │           ├── ServerApp.java                 # Ứng dụng server
│   │           ├── dao/
│   │           │   └── DatabaseManager.java       # Quản lý database
│   │           └── handlers/
│   │               └── ClientHandler.java         # Xử lý client request
│   └── resources/
│       └── com/pbl4/syncproject/
│           ├── login.fxml                         # Giao diện đăng nhập
│           ├── main.fxml                          # Giao diện chính
│           ├── user-permission.fxml               # Dialog quản lý quyền
│           └── settings.fxml                      # Dialog cài đặt
```

### Giao diện ứng dụng

#### 1. Màn hình đăng nhập
- Nhập username và password
- Chọn IP server và port
- Tự động phát hiện IP trong mạng LAN
- Nút Clear để xóa thông tin đã nhập

#### 2. Giao diện chính
- **Header**: Thông tin người dùng, trạng thái kết nối, nút đăng xuất
- **Toolbar**: Các chức năng chính (Làm mới, Tải lên, Tạo thư mục, Quản lý quyền, Cài đặt, Tìm kiếm)
- **Panel trái**: Cây thư mục đồng bộ và trạng thái đồng bộ
- **Panel phải**: Bảng danh sách file với các cột:
  - Tên file (với icon)
  - Kích thước
  - Loại file
  - Ngày sửa đổi cuối
  - Quyền truy cập
  - Trạng thái đồng bộ
  - Thao tác (Tải xuống, Sửa, Xóa)
- **Status bar**: Thông tin trạng thái, số lượng file, mục được chọn

#### 3. Dialog quản lý quyền
- Chọn người dùng
- Chọn file/thư mục
- Cấp quyền: Đọc, Ghi, Sửa đổi, Xóa, Thực thi
- Hiển thị quyền hiện tại

#### 4. Dialog cài đặt
- **Tab Mạng**: Cấu hình server, connection settings
- **Tab Đồng bộ**: Cài đặt tự động đồng bộ, loại file, xử lý xung đột
- **Tab Bảo mật**: Mã hóa, xác thực, kiểm soát truy cập
- **Tab Chung**: Ngôn ngữ, giao diện, thông báo, thư mục

### Luồng hoạt động

1. **Khởi động ứng dụng**
   - Hiển thị màn hình đăng nhập
   - Người dùng nhập thông tin và kết nối server

2. **Sau khi đăng nhập thành công**
   - Mở giao diện chính
   - Tải danh sách file từ server
   - Bắt đầu đồng bộ tự động (nếu được bật)

3. **Quản lý file**
   - Người dùng có thể xem, tải lên, tải xuống, sửa, xóa file
   - Mọi thao tác đều được kiểm tra quyền trước khi thực hiện
   - Thay đổi được đồng bộ với các client khác

4. **Đồng bộ dữ liệu**
   - Ứng dụng tự động kiểm tra thay đổi theo chu kỳ
   - Khi có thay đổi, đồng bộ với server và các client khác
   - Xử lý xung đột theo cài đặt

### Công nghệ sử dụng

- **Ngôn ngữ**: Java
- **Giao diện**: JavaFX với FXML
- **Mạng**: Socket TCP/IP
- **Database**: SQLite (hoặc có thể tích hợp H2, MySQL)
- **Build tool**: Maven

### Cách chạy ứng dụng

1. **Khởi động Server**
   ```bash
   mvn compile exec:java -Dexec.mainClass="com.pbl4.syncproject.server.ServerApp"
   ```

2. **Khởi động Client**
   ```bash
   mvn compile exec:java -Dexec.mainClass="com.pbl4.syncproject.client.ClientApp"
   ```

3. **Build project**
   ```bash
   mvn clean compile
   ```

### Tài khoản mặc định

| Username | Password | Quyền |
|----------|----------|-------|
| admin    | admin123 | Toàn quyền |
| user1    | user123  | Đọc/Ghi |
| user2    | user123  | Đọc/Ghi |
| guest    | guest123 | Chỉ đọc |

### Hướng phát triển

- Tích hợp với cloud storage (Google Drive, Dropbox)
- Hỗ trợ đồng bộ file lớn với resume capability
- Thêm tính năng chat/messaging giữa các users
- Web interface cho quản trị hệ thống
- Mobile app (Android/iOS)
- Tối ưu performance với nhiều file lớn
- Backup và restore dữ liệu

### Đóng góp

Dự án được phát triển bởi nhóm sinh viên trong khuôn khổ môn PBL4. Mọi đóng góp và phản hồi đều được hoan nghênh.

### License

MIT License - Xem file LICENSE để biết thêm chi tiết.
