# Tách GUI khỏi Controller - Refactoring Documentation

## Tổng quan

Dự án đã được refactor để tách biệt GUI logic khỏi business logic theo mô hình MVP (Model-View-Presenter) pattern. Điều này giúp:

- **Tách biệt trách nhiệm**: Business logic và presentation logic được tách riêng
- **Dễ bảo trì**: Code dễ đọc, dễ hiểu và dễ sửa đổi
- **Khả năng test**: Business logic có thể được test độc lập
- **Tái sử dụng**: View có thể được thay thế hoặc tái sử dụng

## Cấu trúc mới

### 1. Packages Structure

```
src/main/java/com/pbl4/syncproject/client/
├── controllers/
│   ├── MainController.java              (Original - deprecated)
│   └── MainControllerRefactored.java    (New - Business Logic only)
├── models/
│   └── FileItem.java                    (Data Model)
└── views/
    ├── IMainView.java                   (View Interface/Contract)
    └── MainView.java                    (GUI Logic Implementation)
```

### 2. Files mới được tạo

#### `IMainView.java` - Interface Definition
- Định nghĩa contract giữa Controller và View
- Chứa tất cả các methods cần thiết cho UI operations
- Đảm bảo loose coupling giữa các components

#### `MainView.java` - GUI Logic Implementation
- Implement interface `IMainView`
- Chịu trách nhiệm tất cả UI operations:
  - Setup components (TableView, TreeView, ComboBox...)
  - Update UI elements (labels, progress bars, status...)
  - Handle UI events và delegate tới Controller
  - Show dialogs và alerts
  - Manage UI state

#### `MainControllerRefactored.java` - Business Logic Only
- Chỉ chứa business logic và event handling
- Delegate tất cả UI operations cho View
- Quản lý data và business rules
- Handle network operations, file operations
- Coordinate between Model và View

#### `FileItem.java` - Data Model
- Tách biệt data model khỏi UI components
- Chứa file information và properties
- Independent của UI framework

### 3. FXML Files

#### `main-refactored.fxml`
- FXML mới sử dụng `MainControllerRefactored`
- Giống với `main.fxml` nhưng bind với controller mới

## Cách hoạt động

### Flow of Control

1. **Initialization**:
   ```
   FXML loads → MainControllerRefactored.initialize()
   → Creates MainView with UI components
   → Setup event handlers delegation
   ```

2. **User Interaction**:
   ```
   User clicks button → FXML calls Controller method
   → Controller processes business logic
   → Controller calls View methods to update UI
   ```

3. **UI Updates**:
   ```
   Controller wants to update UI → Calls View interface methods
   → View implements actual UI changes
   → UI reflects the new state
   ```

### Event Handling Pattern

```java
// Trong Controller
@FXML
private void handleUpload() {
    upload(); // Business logic method
}

private void upload() {
    // Business logic
    FileChooser fileChooser = new FileChooser();
    File selectedFile = fileChooser.showOpenDialog(...);
    if (selectedFile != null) {
        uploadFile(selectedFile); // More business logic
    }
}

private void uploadFile(File file) {
    // Business logic with UI updates via View
    Task<Void> uploadTask = new Task<Void>() { ... };
    
    uploadTask.progressProperty().addListener((obs, oldProgress, newProgress) -> {
        mainView.showUploadProgress(file.getName(), newProgress.doubleValue());
    });
    
    uploadTask.setOnSucceeded(e -> {
        mainView.hideUploadProgress();
        // Update data model
        fileItems.add(newFileItem);
        mainView.updateFileList(fileItems);
    });
}
```

## Lợi ích của cấu trúc mới

### 1. Separation of Concerns
- **Controller**: Business logic, data management, coordination
- **View**: UI rendering, user interaction, presentation
- **Model**: Data structures, business entities

### 2. Testability
```java
// Có thể test business logic độc lập
@Test
public void testUploadFile() {
    // Mock IMainView
    IMainView mockView = Mockito.mock(IMainView.class);
    
    // Test controller logic
    // Verify interactions with view
}
```

### 3. Flexibility
- Có thể thay đổi UI implementation mà không ảnh hưởng business logic
- Có thể tạo multiple views cho cùng một controller
- Dễ dàng add new features

### 4. Maintainability
- Code được tổ chức rõ ràng theo trách nhiệm
- Dễ tìm và fix bugs
- Dễ thêm features mới

## Hướng dẫn sử dụng

### 1. Chạy phiên bản mới
Thay đổi trong LoginController để load main-refactored.fxml:

```java
FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/main-refactored.fxml"));
```

### 2. Thêm features mới

#### Thêm UI operation mới:
1. Add method to `IMainView.java`
2. Implement in `MainView.java`
3. Call from Controller khi cần

#### Thêm business logic mới:
1. Add method to Controller
2. Wire FXML event nếu cần
3. Update View through interface methods

### 3. Mở rộng

#### Tạo View implementation khác:
```java
public class MainViewAlternative implements IMainView {
    // Different UI implementation
    // Same interface, different look/behavior
}
```

#### Test business logic:
```java
public class MainControllerTest {
    private MainControllerRefactored controller;
    private IMainView mockView;
    
    @Before
    public void setup() {
        mockView = Mockito.mock(IMainView.class);
        controller = new MainControllerRefactored();
        // Inject mock view
    }
}
```

## Migration Path

### Từ MainController cũ sang mới:

1. **Phase 1**: Sử dụng MainControllerRefactored với main-refactored.fxml
2. **Phase 2**: Update LoginController để load file mới
3. **Phase 3**: Remove MainController cũ khi đã stable
4. **Phase 4**: Rename files để clean up

### Compatibility
- MainController cũ vẫn hoạt động bình thường
- Có thể chạy song song để so sánh
- Không breaking changes cho existing code

## Best Practices

### 1. Controller chỉ chứa business logic
```java
// Good
private void processFile(File file) {
    // Validate file
    // Process data
    // Update model
    // Notify view
}

// Bad
private void processFile(File file) {
    tableView.getItems().add(...);  // Direct UI manipulation
}
```

### 2. View handle tất cả UI operations
```java
// Good
mainView.showProgress(fileName, progress);
mainView.updateFileList(fileItems);

// Bad
progressBar.setProgress(progress);  // Direct UI access
```

### 3. Use interface để communicate
```java
// Good
IMainView mainView;
mainView.showAlert("Error", message, AlertType.ERROR);

// Bad
Alert alert = new Alert(...);  // Direct UI creation in Controller
```

## Troubleshooting

### Common Issues:

1. **NullPointerException**: Ensure View is initialized before use
2. **UI not updating**: Make sure updates are on JavaFX Application Thread
3. **Events not working**: Check FXML bindings và event handler setup

### Debug Tips:

1. Add logging để trace method calls
2. Use breakpoints trong both Controller và View
3. Check thread safety cho UI operations

## Kết luận

Cấu trúc mới cung cấp:
- **Better organization**: Code được tổ chức tốt hơn
- **Easier maintenance**: Dễ maintain và extend
- **Better testing**: Có thể test riêng biệt
- **More flexibility**: Dễ thay đổi và customize

Đây là foundation tốt cho việc phát triển application phức tạp hơn trong tương lai.