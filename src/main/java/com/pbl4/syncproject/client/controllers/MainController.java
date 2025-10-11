package com.pbl4.syncproject.client.controllers;

import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.services.FileService;
import com.pbl4.syncproject.client.services.NetworkService;
import com.pbl4.syncproject.client.services.SyncAgent;
import com.pbl4.syncproject.client.services.UploadManager;
import com.pbl4.syncproject.client.utils.TaskWrapper;
import com.pbl4.syncproject.client.views.IMainView;
import com.pbl4.syncproject.client.views.MainView;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * MainController theo nguyên tắc Single Responsibility Principle (SRP)
 * Chỉ chịu trách nhiệm: Coordinate giữa View và Services, handle UI events
 * Tất cả business logic được delegate cho các Service classes
 */
public class MainController implements Initializable {

    // FXML UI Components
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

    // Services - Single source of truth cho business logic
    private IMainView mainView;
    private NetworkService networkService;
    private FileService fileService;
    private UploadManager uploadManager;
    private SyncAgent syncAgent;
    
    // State
    private ObservableList<FileItem> fileItems = FXCollections.observableArrayList();
    private String currentUser = "admin";
    private String currentDirectory = "/shared";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeServices();
        initializeView();
        setupEventHandlers();
        loadInitialData();
    }

    // === INITIALIZATION METHODS ===

    /**
     * Initialize services theo dependency order
     */
    private void initializeServices() {
        networkService = new NetworkService();
        fileService = new FileService(networkService);
        syncAgent = new SyncAgent(networkService);
    }

    /**
     * Initialize MainView wrapper
     */
    private void initializeView() {
        mainView = new MainView(
            lblUserInfo, lblConnectionStatus, lblSyncStatus, lblSyncProgress,
            lblStatusMessage, lblFileCount, lblSelectedItems, lblNetworkStatus,
            txtSearch, cmbViewMode, cmbSortBy, treeDirectory, progressSync,
            tableFiles, colFileName, colFileSize, colFileType, colLastModified,
            colPermissions, colSyncStatus, colActions
        );

        // Now we can create UploadManager with mainView
        uploadManager = new UploadManager(networkService, mainView);

        // Setup initial UI state
        mainView.setUserInfo("User: " + currentUser);
        mainView.setStatusMessage("Đã kết nối thành công");
        mainView.setConnectionStatus("● Kết nối: Thành công", true);
        mainView.setNetworkStatus("Mạng: Kết nối", true);
    }

    /**
     * Setup event handlers - delegate to business logic
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
     * Load initial data and start services
     */
    private void loadInitialData() {
        loadFileList();
        startSyncAgent();
        selectDefaultDirectory();
    }

    // === FILE OPERATIONS ===

    /**
     * Load file list from server - delegate to FileService
     */
    private void loadFileList() {
        TaskWrapper.executeAsync(
            "Đang tải danh sách file từ database...",
            () -> {
                try {
                    return fileService.fetchAndParseFileList();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            },
            this::onFileListLoaded,
            error -> onFileListError(error),
            mainView
        );
    }

    /**
     * Handle successful file list loading
     */
    private void onFileListLoaded(List<FileItem> items) {
        fileItems.clear();
        fileItems.addAll(items);
        
        mainView.updateFileList(fileItems);
        mainView.setFileCount(items.size());
        mainView.setStatusMessage("Đã tải " + items.size() + " items từ database");
        
        updateSyncStatus();
    }

    /**
     * Handle file list loading error
     */
    private void onFileListError(String error) {
        mainView.setStatusMessage("Lỗi: " + error);
        mainView.showAlert("Lỗi kết nối", 
            "Không thể tải dữ liệu từ server:\\n" + error + 
            "\\n\\nVui lòng:\\n1. Kiểm tra ServerApp đã chạy\\n2. Kiểm tra kết nối mạng", 
            IMainView.AlertType.ERROR);
    }

    /**
     * Load files for specific directory
     */
    private void loadDirectoryFiles(String directory) {
        TaskWrapper.executeAsync(
            "Đang tải dữ liệu thư mục: " + directory,
            () -> filterFilesByDirectory(directory),
            loadedFiles -> {
                mainView.updateFileList(loadedFiles);
                String folderName = directory.replace("📁 ", "").trim();
                mainView.setStatusMessage("Thư mục " + folderName + ": " + loadedFiles.size() + " file");
            },
            mainView
        );
    }

    /**
     * Filter files by directory - pure logic, no side effects
     */
    private ObservableList<FileItem> filterFilesByDirectory(String directory) {
        ObservableList<FileItem> filteredFiles = FXCollections.observableArrayList();
        String folderName = directory.replace("📁 ", "").trim();
        
        for (FileItem item : fileItems) {
            if (item.getFolderName() != null && item.getFolderName().equals(folderName)) {
                filteredFiles.add(item);
            }
        }
        
        return filteredFiles;
    }

    // === SYNC AGENT OPERATIONS ===

    /**
     * Start sync agent
     */
    private void startSyncAgent() {
        try {
            String syncDir = System.getProperty("user.home") + File.separator + "SyncFolder";
            syncAgent.start(syncDir);
            System.out.println("Started sync agent for directory: " + syncDir);
            updateSyncAgentStatus();
        } catch (Exception e) {
            System.err.println("Failed to start sync agent: " + e.getMessage());
            mainView.setStatusMessage("Cảnh báo: Không thể khởi động auto sync");
        }
    }

    /**
     * Update sync agent status on UI
     */
    private void updateSyncAgentStatus() {
        if (syncAgent != null) {
            SyncAgent.SyncStatus status = syncAgent.getStatus();
            String statusText = "Auto Sync: " + (status.isRunning ? "Đang chạy" : "Dừng");
            if (status.isRunning) {
                statusText += " | Queue: " + status.queueSize + " | Processed: " + status.processedCount;
            }
            mainView.setSyncStatus(statusText, status.isRunning);
        }
    }

    /**
     * Update general sync status
     */
    private void updateSyncStatus() {
        mainView.setSyncStatus("Đồng bộ: Hoạt động", true);
    }

    /**
     * Select default directory after loading
     */
    private void selectDefaultDirectory() {
        TaskWrapper.executeAsync(
            null,
            () -> {
                try {
                    Thread.sleep(1000); // Wait for data loading
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "shared";
            },
            selectedFolder -> handleDirectorySelected("📁 " + selectedFolder),
            mainView
        );
    }

    // === EVENT HANDLERS ===

    @FXML private void handleLogout() { logout(); }
    @FXML private void handleRefresh() { refresh(); }
    @FXML private void handleUpload() { upload(); }
    @FXML private void handleCreateFolder() { createFolder(); }
    @FXML private void handlePermissions() { openPermissions(); }
    @FXML private void handleSettings() { openSettings(); }
    @FXML private void handleSearch() { search(); }

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
     * Handle file actions
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

    // === BUSINESS LOGIC METHODS ===

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
                cleanup();
                
                Stage currentStage = (Stage) btnLogout.getScene().getWindow();
                
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/login.fxml"));
                Parent root = loader.load();
                
                Stage loginStage = new Stage();
                loginStage.setTitle("Đăng nhập - File Sync");
                loginStage.setScene(new Scene(root, 400, 300));
                loginStage.show();
                
                currentStage.close();
                
            } catch (Exception e) {
                mainView.showAlert("Lỗi", "Không thể đăng xuất: " + e.getMessage(), 
                                 IMainView.AlertType.ERROR);
            }
        }
    }

    /**
     * Refresh data
     */
    private void refresh() {
        loadFileList();
        updateSyncAgentStatus();
    }

    /**
     * Upload file - delegate to UploadManager
     */
    private void upload() {
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
            uploadManager.uploadFile(selectedFile, currentDirectory, (file, newFileItem, success, message) -> {
                if (success) {
                    if (newFileItem != null) {
                        fileItems.add(newFileItem);
                        mainView.updateFileList(fileItems);
                    } else {
                        loadFileList(); // Refresh from server
                    }
                    mainView.setStatusMessage(message);
                } else {
                    mainView.setStatusMessage("Upload thất bại: " + message);
                }
            });
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
            // TODO: Implement folder creation via NetworkService
            mainView.setStatusMessage("Chức năng tạo thư mục sẽ được implement");
        }
    }

    /**
     * Open permissions window
     */
    private void openPermissions() {
        openFXMLWindow("/com/pbl4/syncproject/user-permission.fxml", 
                      "Quản lý quyền người dùng", 600, 500);
    }

    /**
     * Open settings window
     */
    private void openSettings() {
        openFXMLWindow("/com/pbl4/syncproject/settings.fxml", 
                      "Cài đặt hệ thống", 600, 500);
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
     * Download file
     */
    private void downloadFile(FileItem fileItem) {
        // TODO: Implement download logic via NetworkService
        mainView.setStatusMessage("Chức năng download sẽ được implement");
    }

    /**
     * Edit file
     */
    private void editFile(FileItem fileItem) {
        // TODO: Implement edit logic
        mainView.setStatusMessage("Chức năng edit sẽ được implement");
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
            // TODO: Implement delete logic via NetworkService
            mainView.setStatusMessage("Chức năng delete sẽ được implement");
        }
    }

    // === UTILITY METHODS ===

    /**
     * Open FXML window utility
     */
    private void openFXMLWindow(String fxmlPath, String title, int width, int height) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent root = loader.load();

            Stage stage = new Stage();
            stage.setTitle(title);
            stage.setScene(new Scene(root, width, height));
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.showAndWait();
        } catch (Exception e) {
            mainView.showAlert("Lỗi", "Không thể mở cửa sổ: " + e.getMessage(), 
                             IMainView.AlertType.ERROR);
        }
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        if (syncAgent != null) {
            syncAgent.stop();
            System.out.println("Sync agent stopped during cleanup");
        }
    }
}