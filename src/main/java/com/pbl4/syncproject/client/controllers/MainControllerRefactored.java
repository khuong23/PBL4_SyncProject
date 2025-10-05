package com.pbl4.syncproject.client.controllers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.services.NetworkService;
import com.pbl4.syncproject.client.views.IMainView;
import com.pbl4.syncproject.client.views.MainView;
import com.pbl4.syncproject.common.jsonhandler.Response;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

/**
 * Refactored MainController - chỉ chứa business logic và event handling
 * UI operations được delegate cho MainView
 */
public class MainControllerRefactored implements Initializable {

    // UI Components - được inject bởi FXML
    @FXML private Label lblUserInfo;
    @FXML private Label lblConnectionStatus;
    @FXML private Label lblSyncStatus;
    @FXML private Label lblSyncProgress;
    @FXML private Label lblStatusMessage;
    @FXML private Label lblFileCount;
    @FXML private Label lblSelectedItems;
    @FXML private Label lblNetworkStatus;

    @FXML private Button btnLogout;
    @FXML private Button btnRefresh;
    @FXML private Button btnUpload;
    @FXML private Button btnCreateFolder;
    @FXML private Button btnPermissions;
    @FXML private Button btnSettings;
    @FXML private Button btnSearch;

    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbViewMode;
    @FXML private ComboBox<String> cmbSortBy;

    @FXML private TreeView<String> treeDirectory;
    @FXML private ProgressBar progressSync;

    @FXML private TableView<FileItem> tableFiles;
    @FXML private TableColumn<FileItem, String> colFileName;
    @FXML private TableColumn<FileItem, String> colFileSize;
    @FXML private TableColumn<FileItem, String> colFileType;
    @FXML private TableColumn<FileItem, String> colLastModified;
    @FXML private TableColumn<FileItem, String> colPermissions;
    @FXML private TableColumn<FileItem, String> colSyncStatus;
    @FXML private TableColumn<FileItem, String> colActions;

    // Business Logic Components
    private IMainView mainView;
    private NetworkService networkService; // Network service for server communication
    private ObservableList<FileItem> fileItems = FXCollections.observableArrayList();
    private String currentUser = "admin";
    private String currentDirectory = "/shared";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeView();
        setupEventHandlers();
        loadInitialData();
    }

    /**
     * Khởi tạo MainView và wire up UI components
     */
    private void initializeView() {
        // Khởi tạo network service
        networkService = new NetworkService();
        
        // Tạo MainView instance với tất cả UI components
        mainView = new MainView(
            lblUserInfo, lblConnectionStatus, lblSyncStatus, lblSyncProgress,
            lblStatusMessage, lblFileCount, lblSelectedItems, lblNetworkStatus,
            txtSearch, cmbViewMode, cmbSortBy, treeDirectory, progressSync,
            tableFiles, colFileName, colFileSize, colFileType, colLastModified,
            colPermissions, colSyncStatus, colActions
        );

        // Setup user info
        mainView.setUserInfo("User: " + currentUser);
        mainView.setStatusMessage("Đã kết nối thành công");
        mainView.setConnectionStatus("● Kết nối: Thành công", true);
        mainView.setNetworkStatus("Mạng: Kết nối", true);
    }

    /**
     * Setup event handlers - delegate UI events to business logic
     */
    private void setupEventHandlers() {
        mainView.setOnLogout(this::handleLogout);
        mainView.setOnRefresh(this::handleRefresh);
        mainView.setOnUpload(this::handleUpload);
        mainView.setOnCreateFolder(this::handleCreateFolder);
        mainView.setOnPermissions(this::handlePermissions);
        mainView.setOnSettings(this::handleSettings);
        mainView.setOnSearch(this::handleSearch);
        
        mainView.setOnDirectorySelected(this::handleDirectorySelected);
        mainView.setOnFileSelected(this::handleFileSelected);
        mainView.setOnFileDoubleClick(this::handleFileAction);
    }

    /**
     * Load initial data từ server và update view
     */
    private void loadInitialData() {
        loadRealDataFromServer();
        
        // Set default folder selection to "shared" after data is loaded
        Platform.runLater(() -> {
            // Give some time for data to load then select shared folder
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // Wait for data loading
                    Platform.runLater(() -> {
                        handleDirectorySelected("📁 shared");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }
    
    /**
     * Load data thực từ server
     */
    private void loadRealDataFromServer() {
        mainView.setStatusMessage("Đang tải danh sách file từ database...");
        
        Task<Void> loadTask = new Task<Void>() {
            private final ObservableList<FileItem> loadedItems = FXCollections.observableArrayList();
            
            @Override
            protected Void call() throws Exception {
                try {
                    updateMessage("Đang kiểm tra kết nối server...");
                    System.out.println("DEBUG: Testing connection to server...");
                    
                    // Test connection first
                    if (!networkService.testConnection()) {
                        throw new Exception("Không thể kết nối tới server - vui lòng khởi động ServerApp");
                    }
                    
                    updateMessage("Đang lấy danh sách từ database...");
                    System.out.println("DEBUG: Connection OK, getting file list from database...");
                    
                    // Get file list from server (which queries database)
                    Response response = networkService.getFileList();
                    System.out.println("DEBUG: Server response: " + (response != null ? response.getStatus() : "null"));
                    
                    if (response != null && "success".equals(response.getStatus())) {
                        System.out.println("DEBUG: Parsing database response...");
                        parseFileListResponse(response, loadedItems);
                        System.out.println("DEBUG: Parsed " + loadedItems.size() + " items from database");
                        
                        // If we got data from server, it's real database data
                        if (loadedItems.size() > 0) {
                            updateMessage("Đã tải " + loadedItems.size() + " items từ database");
                            return null;
                        }
                    }
                    
                    // If no data from server or error, throw exception to use fallback
                    throw new Exception("Không có dữ liệu từ database hoặc server trả về lỗi");
                    
                } catch (Exception e) {
                    // If failed to load from server, fall back to sample data
                    System.err.println("DEBUG: Failed to load from database, falling back to sample: " + e.getMessage());
                    loadRealDataFromServer();
                }
                
                return null;
            }
            
            @Override
            protected void succeeded() {
                System.out.println("DEBUG: Successfully loaded " + loadedItems.size() + " items");
                fileItems.clear();
                fileItems.addAll(loadedItems);
                mainView.updateFileList(fileItems);
                updateSyncStatus();
                
                // Check if data is from database (real data has proper hash) or sample
                boolean isRealData = loadedItems.stream()
                    .anyMatch(item -> item.getFileName().contains("So_do_khoi_Cholesky") || 
                                    item.getFileName().contains("102230357"));
                
                mainView.setStatusMessage("Đã tải " + fileItems.size() + " items từ " + 
                    (isRealData ? "DATABASE" : "sample data"));
            }
            
            @Override
            protected void failed() {
                System.err.println("DEBUG: Task failed, loading sample data");
                // Fall back to sample data
                loadSampleDataFallback();
                mainView.setStatusMessage("Không thể tải từ database, sử dụng dữ liệu mẫu");
            }
        };
        
        loadTask.messageProperty().addListener((obs, oldMessage, newMessage) -> {
            if (newMessage != null) {
                mainView.setStatusMessage(newMessage);
            }
        });
        
        new Thread(loadTask).start();
    }
    
    /**
     * Parse response từ server thành FileItem objects
     */
    private void parseFileListResponse(Response response, ObservableList<FileItem> items) {
        try {
            JsonObject data = response.getData().getAsJsonObject();
            if (data == null) return;
            
            // Parse folders - chỉ để build tree structure, không add vào file list
            if (data.has("folders")) {
                JsonArray folders = data.getAsJsonArray("folders");
                System.out.println("DEBUG: Found " + folders.size() + " folders from server");
                // Folders sẽ được xử lý bởi MainView để build tree structure
                // Không add vào items list vì user không muốn thấy folder trong file list
            }
            
            // Parse files - đây là những gì user muốn thấy trong file list  
            if (data.has("files")) {
                JsonArray files = data.getAsJsonArray("files");
                System.out.println("DEBUG: Found " + files.size() + " files from server");
                for (int i = 0; i < files.size(); i++) {
                    JsonObject file = files.get(i).getAsJsonObject();
                    FileItem fileItem = createFileItemFromJson(file, false); // false = not folder
                    if (fileItem != null) {
                        items.add(fileItem);
                        System.out.println("DEBUG: Added file: " + fileItem.getFileName() + " (folder: " + fileItem.getFolderName() + ")");
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error parsing file list response: " + e.getMessage());
        }
    }
    
    /**
     * Tạo FileItem từ JsonObject (data từ database)
     */
    private FileItem createFileItemFromJson(JsonObject json, boolean isFolder) {
        try {
            String name = json.get("name").getAsString();
            String size = json.has("size") ? json.get("size").getAsString() : "";
            String fileType = isFolder ? "Folder" : 
                             (json.has("fileType") ? json.get("fileType").getAsString() : "File");
            String lastModified = json.has("lastModified") ? json.get("lastModified").getAsString() : "";
            String permission = json.has("permission") ? json.get("permission").getAsString() : "Đọc/Ghi";
            String syncStatus = json.has("syncStatus") ? json.get("syncStatus").getAsString() : "✅ Đã đồng bộ";
            
            // Get folder name - for files, it comes from database query
            // For folders, the name IS the folder name
            String folderName;
            if (isFolder) {
                folderName = name; // For folders, name = folder name
            } else {
                // For files, determine folder based on database relationship
                // Default to "shared" if not specified
                folderName = json.has("folderName") ? json.get("folderName").getAsString() : "shared";
            }
            
            // Add appropriate icon
            String icon = isFolder ? "📁" : getFileIcon(name);
            String displayName = icon + " " + name;
            
            System.out.println("DEBUG: Creating FileItem - Name: " + name + ", Folder: " + folderName + ", IsFolder: " + isFolder);
            
            return new FileItem(displayName, size, fileType, lastModified, permission, syncStatus, folderName);
            
        } catch (Exception e) {
            System.err.println("Error creating FileItem from JSON: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Load sample data as fallback
     */
    private void loadSampleDataFallback() {
        System.out.println("DEBUG: Loading sample data fallback...");
        fileItems.clear();
        loadRealDataFromServer();
        mainView.updateFileList(fileItems);
        updateSyncStatus();
        System.out.println("DEBUG: Sample data loaded with " + fileItems.size() + " items");
    }
    
    /**
     * Load sample data into provided list
     */
//    private void loadSampleData(ObservableList<FileItem> items) {
//        System.out.println("DEBUG: Adding sample data to list...");
//
//        // Add files (from database query results) with folder info
//        items.add(new FileItem("� So_do_khoi_Cholesky (1).docx", "36.99 KB", "DOCX", "21/09/2025 23:02", "Đọc/Ghi", "✅ Đã đồng bộ", "shared"));
//        items.add(new FileItem("� 102230357_Lê Bá Nguyên Long.pdf", "460.38 KB", "PDF", "21/09/2025 23:00", "Đọc/Ghi", "✅ Đã đồng bộ", "shared"));
//        items.add(new FileItem("� UploadHandle.java", "4.69 KB", "JAVA", "21/09/2025 22:55", "Đọc/Ghi", "✅ Đã đồng bộ", "shared"));
//        items.add(new FileItem("� document1.docx", "2.50 MB", "DOCX", "21/09/2025 22:55", "Đọc/Ghi", "✅ Đã đồng bộ", "shared"));
//        items.add(new FileItem("📄 spreadsheet.xlsx", "1.80 MB", "XLSX", "21/09/2025 22:55", "Đọc/Ghi", "✅ Đã đồng bộ", "shared"));
//        items.add(new FileItem("�️ image.png", "867.33 KB", "PNG", "21/09/2025 19:55", "Đọc/Ghi", "✅ Đã đồng bộ", "shared"));
//        items.add(new FileItem("🎬 video.mp4", "15.19 MB", "MP4", "21/09/2025 17:55", "Đọc/Ghi", "✅ Đã đồng bộ", "shared"));
//
//        // Files in documents folder
//        items.add(new FileItem("📄 report.pdf", "1.00 MB", "PDF", "21/09/2025 21:55", "Đọc", "✅ Đã đồng bộ", "documents"));
//        items.add(new FileItem("� presentation.pptx", "3.00 MB", "PPTX", "21/09/2025 20:55", "Đọc", "✅ Đã đồng bộ", "documents"));
//
//        // Files in images folder
//        items.add(new FileItem("🖼️ photo1.jpg", "2.00 MB", "JPG", "21/09/2025 22:25", "Đọc/Ghi", "✅ Đã đồng bộ", "images"));
//        items.add(new FileItem("🖼️ photo2.png", "1.50 MB", "PNG", "21/09/2025 22:10", "Đọc/Ghi", "✅ Đã đồng bộ", "images"));
//
//        // Files in videos folder
//        items.add(new FileItem("🎬 movie.mkv", "50.00 MB", "MKV", "21/09/2025 16:55", "Đọc/Ghi", "✅ Đã đồng bộ", "videos"));
//        items.add(new FileItem("🎬 clip.mp4", "10.00 MB", "MP4", "21/09/2025 21:55", "Đọc/Ghi", "✅ Đã đồng bộ", "videos"));
//
//        System.out.println("DEBUG: Added " + items.size() + " sample items");
//    }

    // FXML Event Handlers - delegate to business logic methods
    @FXML
    private void handleLogout() {
        logout();
    }

    @FXML
    private void handleRefresh() {
        refresh();
    }

    @FXML
    private void handleUpload() {
        upload();
    }

    @FXML
    private void handleCreateFolder() {
        createFolder();
    }

    @FXML
    private void handlePermissions() {
        openPermissions();
    }

    @FXML
    private void handleSettings() {
        openSettings();
    }

    @FXML
    private void handleSearch() {
        search();
    }

    // Business Logic Methods

    /**
     * Logout business logic
     */
    private void logout() {
        boolean confirmed = mainView.showConfirmDialog(
            "Xác nhận đăng xuất", 
            "Bạn có chắc chắn muốn đăng xuất không?"
        );
        
        if (confirmed) {
            try {
                // Close current stage
                Stage currentStage = (Stage) btnLogout.getScene().getWindow();
                
                // Open login window
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/login.fxml"));
                Parent root = loader.load();
                
                Stage loginStage = new Stage();
                loginStage.setTitle("Đăng nhập hệ thống");
                loginStage.setScene(new Scene(root, 900, 580));
                loginStage.show();
                
                currentStage.close();
            } catch (Exception e) {
                mainView.showAlert("Lỗi", "Không thể mở màn hình đăng nhập: " + e.getMessage(), 
                                 IMainView.AlertType.ERROR);
            }
        }
    }

    /**
     * Refresh data business logic
     */
    private void refresh() {
        loadRealDataFromServer();
    }

    /**
     * Upload file business logic
     */
    private void upload() {
        // Check server connection first
        if (!checkServerConnection()) {
            return; // Error already shown in checkServerConnection
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để tải lên");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Tất cả file", "*.*"),
            new FileChooser.ExtensionFilter("Tài liệu", "*.doc", "*.docx", "*.pdf", "*.txt", "*.rtf"),
            new FileChooser.ExtensionFilter("Hình ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.tiff"),
            new FileChooser.ExtensionFilter("Video", "*.mp4", "*.avi", "*.mkv", "*.mov", "*.wmv"),
            new FileChooser.ExtensionFilter("Audio", "*.mp3", "*.wav", "*.flac", "*.aac"),
            new FileChooser.ExtensionFilter("Archive", "*.zip", "*.rar", "*.7z", "*.tar", "*.gz")
        );

        File selectedFile = fileChooser.showOpenDialog(btnUpload.getScene().getWindow());
        if (selectedFile != null) {
            // Validate file before upload
            if (validateFileForUpload(selectedFile)) {
                uploadFileWithRetry(selectedFile, 0); // Start with retry count 0
            }
        }
    }

    /**
     * Create folder business logic
     */
    private void createFolder() {
        String folderName = mainView.showInputDialog(
            "Tạo thư mục mới", 
            "Nhập tên thư mục:", 
            "Thư mục mới"
        );
        
        if (folderName != null && !folderName.trim().isEmpty()) {
            createFolderOnServer(folderName.trim());
        }
    }

    /**
     * Open permissions window
     */
    private void openPermissions() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/user-permission.fxml"));
            Parent root = loader.load();

            Stage permissionStage = new Stage();
            permissionStage.setTitle("Quản lý quyền người dùng");
            permissionStage.setScene(new Scene(root, 600, 500));
            permissionStage.initModality(Modality.APPLICATION_MODAL);
            permissionStage.showAndWait();
        } catch (Exception e) {
            mainView.showAlert("Lỗi", "Không thể mở cửa sổ quyền: " + e.getMessage(), 
                             IMainView.AlertType.ERROR);
        }
    }

    /**
     * Open settings window
     */
    private void openSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/settings.fxml"));
            Parent root = loader.load();

            Stage settingsStage = new Stage();
            settingsStage.setTitle("Cài đặt hệ thống");
            settingsStage.setScene(new Scene(root, 600, 500));
            settingsStage.initModality(Modality.APPLICATION_MODAL);
            settingsStage.showAndWait();
        } catch (Exception e) {
            mainView.showAlert("Lỗi", "Không thể mở cửa sổ cài đặt: " + e.getMessage(), 
                             IMainView.AlertType.ERROR);
        }
    }

    /**
     * Search business logic
     */
    private void search() {
        String searchText = mainView.getSearchText();
        if (searchText.trim().isEmpty()) {
            mainView.showAlert("Thông báo", "Vui lòng nhập từ khóa tìm kiếm", IMainView.AlertType.INFORMATION);
            return;
        }
        
        mainView.setStatusMessage("Đang tìm kiếm: " + searchText);
        // Search logic is handled by the view automatically through text change listener
    }

    /**
     * Handle directory selection
     */
    private void handleDirectorySelected(String directory) {
        currentDirectory = directory;
        loadDirectoryFiles(directory);
        mainView.setStatusMessage("Đã chọn thư mục: " + directory);
    }

    /**
     * Handle file selection
     */
    private void handleFileSelected(FileItem fileItem) {
        mainView.setStatusMessage("Đã chọn file: " + fileItem.getFileName());
    }

    /**
     * Handle file actions (download, edit, delete)
     */
    private void handleFileAction(FileItem fileItem, String action) {
        switch (action.toLowerCase()) {
            case "download":
                downloadFile(fileItem);
                break;
            case "edit":
                editFile(fileItem);
                break;
            case "delete":
                deleteFile(fileItem);
                break;
            default:
                mainView.showAlert("Lỗi", "Hành động không được hỗ trợ: " + action, 
                                 IMainView.AlertType.ERROR);
        }
    }

    // Private Business Logic Methods

    /**
     * Load files from directory
     */
    private void loadDirectoryFiles(String directory) {
        mainView.setStatusMessage("Đang tải dữ liệu thư mục: " + directory);
        
        Task<ObservableList<FileItem>> loadTask = new Task<ObservableList<FileItem>>() {
            @Override
            protected ObservableList<FileItem> call() throws Exception {
                Thread.sleep(300); // Simulate loading
                
                // Filter files by selected folder
                ObservableList<FileItem> filteredFiles = FXCollections.observableArrayList();
                
                // Extract folder name from tree selection (remove emoji and spaces)
                String folderName = directory.replace("📁 ", "").trim();
                System.out.println("DEBUG: Filtering files for folder: " + folderName);
                
                for (FileItem item : fileItems) {
                    if (item.getFolderName() != null && item.getFolderName().equals(folderName)) {
                        filteredFiles.add(item);
                        System.out.println("DEBUG: Added file: " + item.getFileName() + " from folder: " + item.getFolderName());
                    }
                }
                
                System.out.println("DEBUG: Filtered " + filteredFiles.size() + " files for folder " + folderName);
                return filteredFiles;
            }
        };
        
        loadTask.setOnSucceeded(e -> {
            ObservableList<FileItem> loadedFiles = loadTask.getValue();
            mainView.updateFileList(loadedFiles);
            String folderName = directory.replace("📁 ", "").trim();
            mainView.setStatusMessage("Thư mục " + folderName + ": " + loadedFiles.size() + " file");
            System.out.println("DEBUG: Successfully updated UI with " + loadedFiles.size() + " files");
        });
        
        loadTask.setOnFailed(e -> {
            mainView.showAlert("Lỗi", "Không thể tải dữ liệu thư mục", IMainView.AlertType.ERROR);
            mainView.setStatusMessage("Lỗi tải dữ liệu");
            System.err.println("DEBUG: Failed to load directory files");
        });
        
        new Thread(loadTask).start();
    }
    
    /**
     * Upload file with retry mechanism
     */
    private void uploadFileWithRetry(File file, int retryCount) {
        final int maxRetries = 3;
        
        Task<Response> uploadTask = new Task<Response>() {
            @Override
            protected Response call() throws Exception {
                try {
                    // Update progress to 10%
                    updateProgress(10, 100);
                    updateMessage("Đang chuẩn bị file: " + file.getName() + "...");
                    
                    // Simulate file preparation time
                    Thread.sleep(500);
                    
                    // Update progress to 30%
                    updateProgress(30, 100);
                    updateMessage("Đang kết nối server...");
                    
                    // Test connection first
                    if (!networkService.testConnection()) {
                        throw new Exception("Không thể kết nối tới server");
                    }
                    
                    // Update progress to 50%
                    updateProgress(50, 100);
                    updateMessage("Đang tải lên file...");
                    
                    // Get folder ID from current directory
                    int folderId = getFolderIdFromDirectory(currentDirectory);
                    
                    // Upload file using NetworkService
                    Response response = networkService.uploadFile(file, folderId);
                    
                    updateProgress(90, 100);
                    updateMessage("Xác nhận tải lên...");
                    
                    // Simulate verification
                    Thread.sleep(300);
                    
                    updateProgress(100, 100);
                    updateMessage("Hoàn thành!");
                    
                    return response;
                } catch (Exception e) {
                    // Log error for debugging
                    System.err.println("Upload failed for " + file.getName() + ": " + e.getMessage());
                    throw e;
                }
            }
        };
        
        // Bind progress to UI (remove duplicate listener)
        uploadTask.progressProperty().addListener((obs, oldProgress, newProgress) -> {
            double progress = newProgress.doubleValue();
            mainView.showUploadProgress(file.getName(), progress);
        });
        
        // Bind status message to UI
        uploadTask.messageProperty().addListener((obs, oldMessage, newMessage) -> {
            if (newMessage != null) {
                mainView.setStatusMessage(newMessage);
            }
        });
        
        uploadTask.setOnSucceeded(e -> {
            mainView.hideUploadProgress();
            
            Response response = uploadTask.getValue();
            if (response != null && "success".equals(response.getStatus())) {
                // Get current folder name for the uploaded file
                String currentFolderName = currentDirectory.replace("📁 ", "").replace("/", "").trim();
                
                // Add uploaded file to list
                FileItem newFile = new FileItem(
                    getFileIcon(file.getName()) + " " + file.getName(),
                    formatFileSize(file.length()),
                    getFileType(file.getName()),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    "Đọc/Ghi",
                    "✅ Đã đồng bộ",
                    currentFolderName
                );
                
                fileItems.add(newFile);
                mainView.updateFileList(fileItems);
                mainView.setStatusMessage("Tải lên thành công: " + file.getName());
                
                String successMsg = response.getMessage() != null ? response.getMessage() : 
                                  "File đã được tải lên thành công!";
                mainView.showAlert("Thành công", successMsg, IMainView.AlertType.INFORMATION);
                updateSyncStatus();
            } else {
                String errorMsg = response != null && response.getMessage() != null ? 
                                response.getMessage() : "Phản hồi không hợp lệ từ server";
                handleUploadError(file, errorMsg, retryCount, maxRetries);
            }
        });
        
        uploadTask.setOnFailed(e -> {
            mainView.hideUploadProgress();
            Throwable exception = uploadTask.getException();
            String errorMsg = exception != null ? exception.getMessage() : "Lỗi không xác định";
            handleUploadError(file, errorMsg, retryCount, maxRetries);
        });
        
        // Disable upload button during upload
        btnUpload.setDisable(true);
        uploadTask.setOnSucceeded(e -> btnUpload.setDisable(false));
        uploadTask.setOnFailed(e -> btnUpload.setDisable(false));
        
        new Thread(uploadTask).start();
    }

    /**
     * Create folder on server
     */
    private void createFolderOnServer(String folderName) {
        Task<Response> createTask = new Task<Response>() {
            @Override
            protected Response call() throws Exception {
                // Use NetworkService to create folder
                return networkService.createFolder(folderName);
            }
        };
        
        createTask.setOnSucceeded(e -> {
            Response response = createTask.getValue();
            if ("success".equals(response.getStatus())) {
                // For folders, folderName = the folder's own name
                FileItem newFolder = new FileItem(
                    "📁 " + folderName,
                    "-",
                    "Folder",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    "Đọc/Ghi",
                    "✅ Đã đồng bộ",
                    folderName // For folders, folderName is the folder itself
                );
                
                fileItems.add(0, newFolder); // Add at beginning
                mainView.updateFileList(fileItems);
                mainView.setStatusMessage("Tạo thư mục thành công: " + folderName);
                mainView.showAlert("Thành công", response.getMessage(), IMainView.AlertType.INFORMATION);
                updateSyncStatus();
            } else {
                mainView.setStatusMessage("Lỗi tạo thư mục: " + response.getMessage());
                mainView.showAlert("Lỗi", "Không thể tạo thư mục: " + response.getMessage(), 
                                 IMainView.AlertType.ERROR);
            };
        });
        
        createTask.setOnFailed(e -> {
            Throwable exception = createTask.getException();
            String errorMsg = exception != null ? exception.getMessage() : "Lỗi không xác định";
            mainView.showAlert("Lỗi", "Không thể tạo thư mục: " + errorMsg, 
                             IMainView.AlertType.ERROR);
            mainView.setStatusMessage("Lỗi tạo thư mục");
        });
        
        new Thread(createTask).start();
    }

    /**
     * Download file
     */
    private void downloadFile(FileItem fileItem) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Chọn thư mục lưu file");
        
        File selectedDirectory = directoryChooser.showDialog(btnUpload.getScene().getWindow());
        if (selectedDirectory != null) {
            Task<Void> downloadTask = new Task<Void>() {
                @Override
                protected Void call() throws Exception {
                    // Simulate download with progress
                    for (int i = 0; i <= 100; i += 10) {
                        Thread.sleep(150);
                        updateProgress(i, 100);
                    }
                    return null;
                }
            };
            
            downloadTask.progressProperty().addListener((obs, oldProgress, newProgress) -> {
                mainView.showDownloadProgress(fileItem.getFileName(), newProgress.doubleValue());
            });
            
            downloadTask.setOnSucceeded(e -> {
                mainView.hideDownloadProgress();
                mainView.setStatusMessage("Tải xuống thành công: " + fileItem.getFileName());
            });
            
            downloadTask.setOnFailed(e -> {
                mainView.hideDownloadProgress();
                mainView.showAlert("Lỗi", "Tải xuống thất bại", IMainView.AlertType.ERROR);
            });
            
            new Thread(downloadTask).start();
        }
    }

    /**
     * Edit file
     */
    private void editFile(FileItem fileItem) {
        mainView.showAlert("Thông báo", "Chức năng chỉnh sửa sẽ được triển khai sau", 
                         IMainView.AlertType.INFORMATION);
        mainView.setStatusMessage("Đang chỉnh sửa: " + fileItem.getFileName());
    }

    /**
     * Delete file
     */
    private void deleteFile(FileItem fileItem) {
        boolean confirmed = mainView.showConfirmDialog(
            "Xác nhận xóa", 
            "Bạn có chắc chắn muốn xóa file: " + fileItem.getFileName() + "?"
        );
        
        if (confirmed) {
            fileItems.remove(fileItem);
            mainView.updateFileList(fileItems);
            mainView.setStatusMessage("Đã xóa: " + fileItem.getFileName());
            updateSyncStatus();
        }
    }

    /**
     * Update sync status
     */
    private void updateSyncStatus() {
        long syncedFiles = fileItems.stream()
                .mapToLong(item -> item.getSyncStatus().contains("Đã đồng bộ") ? 1 : 0)
                .sum();

        if (syncedFiles == fileItems.size()) {
            mainView.setSyncStatus("✅ Tất cả file đã đồng bộ", true);
        } else {
            mainView.setSyncStatus("🔄 Đang đồng bộ " + (fileItems.size() - syncedFiles) + " file", false);
        }
    }

    // Utility Methods

    /**
     * Get folder ID from directory path
     */
    private int getFolderIdFromDirectory(String directory) {
        if (directory == null) return 1; // Default to shared
        
        String folderName = directory.replace("📁 ", "").replace("/", "").trim();
        
        switch (folderName.toLowerCase()) {
            case "shared":
                return 1;
            case "documents":
                return 2;
            case "images":
                return 3;
            case "videos":
                return 4;
            default:
                return 1; // Default to shared folder
        }
    }

    private String getFileIcon(String fileName) {
        if (fileName.contains(".")) {
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            switch (extension) {
                case "doc":
                case "docx":
                case "txt":
                    return "📄";
                case "xls":
                case "xlsx":
                    return "📊";
                case "png":
                case "jpg":
                case "jpeg":
                case "gif":
                    return "🖼️";
                case "mp4":
                case "avi":
                case "mkv":
                    return "🎥";
                case "pdf":
                    return "📕";
                default:
                    return "📄";
            }
        }
        return "📄";
    }

    private String getFileType(String fileName) {
        if (fileName.contains(".")) {
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            switch (extension) {
                case "doc":
                case "docx":
                    return "Document";
                case "xls":
                case "xlsx":
                    return "Spreadsheet";
                case "png":
                case "jpg":
                case "jpeg":
                case "gif":
                    return "Image";
                case "mp4":
                case "avi":
                case "mkv":
                    return "Video";
                case "pdf":
                    return "PDF";
                case "txt":
                    return "Text";
                default:
                    return "File";
            }
        }
        return "File";
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    /**
     * Check server connection before performing operations
     */
    private boolean checkServerConnection() {
        mainView.setStatusMessage("Đang kiểm tra kết nối server...");
        
        try {
            String connectionResult = networkService.testConnectionWithDetails();
            
            if (connectionResult.equals("OK")) {
                mainView.setConnectionStatus("Đã kết nối", true);
                mainView.setStatusMessage("Server sẵn sàng");
                return true;
            } else {
                mainView.setConnectionStatus("Mất kết nối", false);
                mainView.setStatusMessage("Không thể kết nối server");
                
                String errorMessage = "Không thể kết nối tới server:\n\n" + connectionResult + 
                                    "\n\nVui lòng:\n" +
                                    "• Kiểm tra server có đang chạy không\n" +
                                    "• Kiểm tra kết nối mạng\n" +
                                    "• Thử lại sau ít phút";
                
                mainView.showAlert("Lỗi kết nối", errorMessage, IMainView.AlertType.ERROR);
                return false;
            }
        } catch (Exception e) {
            mainView.setConnectionStatus("Lỗi kết nối", false);
            mainView.setStatusMessage("Lỗi kiểm tra kết nối");
            
            String errorMessage = "Lỗi kiểm tra kết nối server:\n\n" + e.getMessage() + 
                                "\n\nVui lòng kiểm tra:\n" +
                                "• Server có đang chạy không\n" +
                                "• Địa chỉ server đúng chưa\n" +
                                "• Firewall có block kết nối không";
            
            mainView.showAlert("Lỗi kết nối", errorMessage, IMainView.AlertType.ERROR);
            return false;
        }
    }

    /**
     * Validate file before upload
     */
    private boolean validateFileForUpload(File file) {
        // Check if file exists
        if (!file.exists()) {
            mainView.showAlert("Lỗi", "File không tồn tại!", IMainView.AlertType.ERROR);
            return false;
        }

        // Check if it's a file (not directory)
        if (!file.isFile()) {
            mainView.showAlert("Lỗi", "Chỉ có thể tải lên file, không thể tải lên thư mục!", IMainView.AlertType.ERROR);
            return false;
        }

        // Check file size (max 100MB)
        long maxSizeBytes = 100 * 1024 * 1024; // 100MB
        if (file.length() > maxSizeBytes) {
            mainView.showAlert("Lỗi",
                "File quá lớn! Kích thước tối đa cho phép: 100MB\\n" +
                "Kích thước file hiện tại: " + formatFileSize(file.length()),
                IMainView.AlertType.ERROR);
            return false;
        }

        // Check if file is empty
        if (file.length() == 0) {
            mainView.showAlert("Cảnh báo",
                "File này rỗng. Bạn có chắc muốn tải lên?",
                IMainView.AlertType.WARNING);
            // Let user decide, don't block empty files
        }

        // Check filename validity
        String fileName = file.getName();
        if (fileName.trim().isEmpty()) {
            mainView.showAlert("Lỗi", "Tên file không hợp lệ!", IMainView.AlertType.ERROR);
            return false;
        }

        // Check for invalid characters in filename
        String invalidChars = "<>:\"/\\\\|?*";
        for (char c : invalidChars.toCharArray()) {
            if (fileName.indexOf(c) >= 0) {
                mainView.showAlert("Lỗi",
                    "Tên file chứa ký tự không hợp lệ: " + c + "\\n" +
                    "Các ký tự không được phép: " + invalidChars,
                    IMainView.AlertType.ERROR);
                return false;
            }
        }

        // Check file extension (security)
        String[] blockedExtensions = {".exe", ".bat", ".cmd", ".com", ".scr", ".pif", ".vbs", ".js"};
        String lowerFileName = fileName.toLowerCase();
        for (String ext : blockedExtensions) {
            if (lowerFileName.endsWith(ext)) {
                mainView.showAlert("Lỗi",
                    "Loại file này không được phép tải lên vì lý do bảo mật: " + ext,
                    IMainView.AlertType.ERROR);
                return false;
            }
        }

        // Check if file already exists in current list
        for (FileItem item : fileItems) {
            String existingFileName = item.getFileName();
            // Remove icon prefix for comparison
            if (existingFileName.contains(" ")) {
                existingFileName = existingFileName.substring(existingFileName.indexOf(" ") + 1);
            }
            if (existingFileName.equals(fileName)) {
                boolean overwrite = mainView.showConfirmDialog("File đã tồn tại",
                    "File '" + fileName + "' đã tồn tại. Bạn có muốn ghi đè không?");
                if (!overwrite) {
                    return false;
                }
                break;
            }
        }

        return true;
    }

    /**
     * Handle upload error with retry mechanism and error categorization
     */
    private void handleUploadError(File file, String errorMsg, int retryCount, int maxRetries) {
        // Categorize error type
        ErrorType errorType = categorizeError(errorMsg);
        
        // For connection errors, always check connection first
        if (errorType == ErrorType.CONNECTION) {
            mainView.setConnectionStatus("Mất kết nối", false);
            
            // Don't retry connection errors too aggressively
            if (retryCount >= 1) {
                showFinalConnectionError(file, errorMsg, retryCount);
                return;
            }
        }
        
        // Don't retry certain types of errors
        if (errorType == ErrorType.FILE_INVALID || errorType == ErrorType.PERMISSION) {
            showNonRetryableError(file, errorMsg, errorType);
            return;
        }
        
        if (retryCount < maxRetries) {
            // Show retry dialog with error-specific message
            String retryMessage = getRetryMessage(errorType, errorMsg, retryCount, maxRetries);
            boolean retry = mainView.showConfirmDialog("Lỗi tải lên", retryMessage);

            if (retry) {
                mainView.setStatusMessage("Đang thử lại... (" + (retryCount + 2) + "/" + (maxRetries + 1) + ")");

                // Different retry delays based on error type
                int delayMs = getRetryDelay(errorType, retryCount);

                // Retry after delay
                Task<Void> retryTask = new Task<Void>() {
                    @Override
                    protected Void call() throws Exception {
                        Thread.sleep(delayMs);
                        return null;
                    }
                };

                retryTask.setOnSucceeded(e -> {
                    uploadFileWithRetry(file, retryCount + 1);
                });

                new Thread(retryTask).start();
                return;
            }
        }

        // Final failure - no more retries
        showFinalError(file, errorMsg, retryCount, errorType);
    }
    
    /**
     * Error types for better handling
     */
    private enum ErrorType {
        CONNECTION, TIMEOUT, FILE_INVALID, PERMISSION, SERVER_ERROR, UNKNOWN
    }
    
    /**
     * Categorize error based on error message
     */
    private ErrorType categorizeError(String errorMsg) {
        if (errorMsg == null) return ErrorType.UNKNOWN;
        
        String lowerMsg = errorMsg.toLowerCase();
        if (lowerMsg.contains("kết nối") || lowerMsg.contains("connect") || 
            lowerMsg.contains("server") && lowerMsg.contains("tắt")) {
            return ErrorType.CONNECTION;
        }
        if (lowerMsg.contains("timeout") || lowerMsg.contains("time out")) {
            return ErrorType.TIMEOUT;
        }
        if (lowerMsg.contains("không tồn tại") || lowerMsg.contains("file") && lowerMsg.contains("invalid")) {
            return ErrorType.FILE_INVALID;
        }
        if (lowerMsg.contains("permission") || lowerMsg.contains("quyền")) {
            return ErrorType.PERMISSION;
        }
        if (lowerMsg.contains("server error") || lowerMsg.contains("internal server")) {
            return ErrorType.SERVER_ERROR;
        }
        return ErrorType.UNKNOWN;
    }
    
    /**
     * Get retry message based on error type
     */
    private String getRetryMessage(ErrorType errorType, String errorMsg, int retryCount, int maxRetries) {
        String baseMsg = "Lỗi tải lên file: " + errorMsg + "\n" +
                        "Lần thử: " + (retryCount + 1) + "/" + maxRetries + "\n\n";
        
        switch (errorType) {
            case CONNECTION:
                return baseMsg + "Lỗi kết nối server. Bạn có muốn thử lại không?\n" +
                              "(Sẽ kiểm tra kết nối trước khi thử lại)";
            case TIMEOUT:
                return baseMsg + "Kết nối bị timeout. Bạn có muốn thử lại không?\n" +
                              "(Sẽ tăng thời gian chờ)";
            case SERVER_ERROR:
                return baseMsg + "Lỗi server. Bạn có muốn thử lại không?\n" +
                              "(Server có thể tạm thời quá tải)";
            default:
                return baseMsg + "Bạn có muốn thử lại không?";
        }
    }
    
    /**
     * Get retry delay based on error type
     */
    private int getRetryDelay(ErrorType errorType, int retryCount) {
        switch (errorType) {
            case CONNECTION:
                return 5000 + (retryCount * 3000); // 5s, 8s, 11s...
            case TIMEOUT:
                return 3000 + (retryCount * 2000); // 3s, 5s, 7s...
            case SERVER_ERROR:
                return (int) Math.pow(2, retryCount) * 2000; // 2s, 4s, 8s...
            default:
                return (int) Math.pow(2, retryCount) * 1000; // 1s, 2s, 4s...
        }
    }
    
    /**
     * Show error for non-retryable errors
     */
    private void showNonRetryableError(File file, String errorMsg, ErrorType errorType) {
        mainView.setStatusMessage("Lỗi tải lên: " + file.getName());
        
        String title = "Lỗi tải lên";
        String message = "Không thể tải lên file: " + errorMsg;
        
        switch (errorType) {
            case FILE_INVALID:
                message += "\n\nVui lòng kiểm tra:\n• File có tồn tại không\n• File có bị hỏng không\n• Tên file có hợp lệ không";
                break;
            case PERMISSION:
                message += "\n\nVui lòng kiểm tra:\n• Quyền truy cập file\n• Quyền upload trên server\n• Liên hệ quản trị viên";
                break;
            case CONNECTION:
            case TIMEOUT:
            case SERVER_ERROR:
            case UNKNOWN:
            default:
                message += "\n\nVui lòng liên hệ quản trị viên để được hỗ trợ";
                break;
        }
        
        mainView.showAlert(title, message, IMainView.AlertType.ERROR);
    }
    
    /**
     * Show final connection error
     */
    private void showFinalConnectionError(File file, String errorMsg, int retryCount) {
        mainView.setStatusMessage("Mất kết nối server");
        
        String message = "Không thể kết nối tới server sau " + (retryCount + 1) + " lần thử:\n\n" + errorMsg +
                        "\n\nHướng dẫn khắc phục:\n" +
                        "• Kiểm tra server có đang chạy không\n" +
                        "• Kiểm tra kết nối internet\n" +
                        "• Kiểm tra firewall và antivirus\n" +
                        "• Thử lại sau 5-10 phút\n" +
                        "• Liên hệ quản trị viên nếu vấn đề tiếp tục";
        
        mainView.showAlert("Lỗi kết nối", message, IMainView.AlertType.ERROR);
    }
    
    /**
     * Show final error after all retries failed
     */
    private void showFinalError(File file, String errorMsg, int retryCount, ErrorType errorType) {
        mainView.setStatusMessage("Lỗi tải lên: " + file.getName());
        
        String title = "Tải lên thất bại";
        String message = "Tải lên thất bại sau " + (retryCount + 1) + " lần thử:\n\n" + errorMsg;
        
        switch (errorType) {
            case CONNECTION:
                message += "\n\nHướng dẫn:\n• Kiểm tra kết nối mạng\n• Khởi động lại server\n• Liên hệ quản trị viên";
                break;
            case TIMEOUT:
                message += "\n\nHướng dẫn:\n• Thử lại khi mạng ổn định hơn\n• Kiểm tra file có quá lớn không\n• Liên hệ IT support";
                break;
            case SERVER_ERROR:
                message += "\n\nHướng dẫn:\n• Thử lại sau ít phút\n• Kiểm tra dung lượng server\n• Liên hệ quản trị viên";
                break;
            case FILE_INVALID:
            case PERMISSION:
            case UNKNOWN:
            default:
                message += "\n\nHướng dẫn:\n• Kiểm tra kết nối mạng\n• Thử lại sau ít phút\n• Liên hệ support nếu vấn đề tiếp tục";
                break;
        }
        
        mainView.showAlert(title, message, IMainView.AlertType.ERROR);
    }

}