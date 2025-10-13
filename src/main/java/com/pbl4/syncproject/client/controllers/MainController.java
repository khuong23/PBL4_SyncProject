package com.pbl4.syncproject.client.controllers;

import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.services.FileService;
import com.pbl4.syncproject.client.services.NetworkService;
import com.pbl4.syncproject.client.services.SyncAgent;
import com.pbl4.syncproject.client.services.UploadManager;
import com.pbl4.syncproject.client.utils.TaskWrapper;
import com.pbl4.syncproject.client.views.IMainView;
import com.pbl4.syncproject.client.views.MainView;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import javafx.collections.FXCollections;
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
    @FXML private TreeView<Folders> treeDirectory;
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
    // THAY ĐỔI: Bỏ biến allFileItems vì chúng ta sẽ không lưu trữ tất cả các file nữa
    // private ObservableList<FileItem> allFileItems = FXCollections.observableArrayList();
    private String currentUser = "admin";
    private String currentDirectory = "/shared"; // Kept for compatibility, but now tracking folderId
    private int currentFolderId = -1; // Track selected folder ID for uploads and operations

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Chỉ initialize view và setup handlers
        // Services sẽ được khởi tạo sau khi login thành công
        initializeView();
        setupEventHandlers();
        // loadInitialData() sẽ được gọi sau trong setServerAddress()
    }

    // === INITIALIZATION METHODS ===

    /**
     * Set server address from login screen
     * BẮT BUỘC phải gọi method này từ LoginController sau khi login thành công
     */
    public void setServerAddress(String serverIP, int serverPort) {
        // Initialize services with server address from login
        networkService = new NetworkService(serverIP, serverPort);
        fileService = new FileService(networkService);
        syncAgent = new SyncAgent(networkService);

        // Update connection status
        if (mainView != null) {
            mainView.setConnectionStatus("Kết nối: " + serverIP + ":" + serverPort, true);
            mainView.setNetworkStatus("Mạng: Đã kết nối", true);

            // Update FileService trong MainView (sẽ tự động refresh folder tree)
            mainView.setFileService(fileService);

            // Now that we have networkService, initialize uploadManager
            uploadManager = new UploadManager(networkService, mainView);

            // Load initial data sau khi đã có services
            loadInitialData();
        }
    }

    /**
     * Initialize MainView wrapper
     * FileService sẽ được set sau khi login thành công
     */
    private void initializeView() {
        mainView = new MainView(
                lblUserInfo,           // 1
                lblConnectionStatus,   // 2
                lblSyncStatus,         // 3
                lblSyncProgress,       // 4
                lblStatusMessage,      // 5
                lblFileCount,          // 6
                lblSelectedItems,      // 7
                lblNetworkStatus,      // 8
                txtSearch,             // 9
                cmbViewMode,           // 10
                cmbSortBy,             // 11
                treeDirectory,         // 12
                progressSync,          // 13
                tableFiles,            // 14
                colFileName,           // 15
                colFileSize,           // 16
                colFileType,           // 17
                colLastModified,       // 18
                colPermissions,        // 19
                colSyncStatus,         // 20
                colActions,            // 21
                null                   // 22 - fileService sẽ được set sau
        );

        // UploadManager sẽ được khởi tạo sau trong setServerAddress() khi có networkService
        // uploadManager = new UploadManager(networkService, mainView);

        // Setup TableView columns manually to avoid module access issues
        setupTableColumns();

        // Setup initial UI state
        mainView.setUserInfo("User: " + currentUser);
        mainView.setStatusMessage("Đang chờ kết nối server...");
        mainView.setConnectionStatus("● Chờ đăng nhập", false);
        mainView.setNetworkStatus("Mạng: Chưa kết nối", false);
    }

    /**
     * Setup TableView columns manually to avoid JavaFX module access issues
     */
    private void setupTableColumns() {
        // Setup cell value factories manually instead of using PropertyValueFactory
        colFileName.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().getFileName()));
        colFileSize.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().getFileSize()));
        colFileType.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().getFileType()));
        colLastModified.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().getLastModified()));
        colPermissions.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().getPermissions()));
        colSyncStatus.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().getSyncStatus()));
        colActions.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty("Actions")); // Placeholder for action buttons
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
        // SỬA ĐỔI: Thiết lập cây thư mục với lazy loading
        setupDirectoryTreeWithLazyLoading();
        startSyncAgent();
        mainView.setStatusMessage("Sẵn sàng. Vui lòng chọn một thư mục để xem nội dung.");
    }

    /**
     * Thiết lập cây thư mục với lazy loading
     */
    private void setupDirectoryTreeWithLazyLoading() {
        Folders rootFolderData = new Folders();
        rootFolderData.setFolderId(0); // ID 0 đại diện cho gốc ảo, không có trong DB
        rootFolderData.setFolderName("Thư mục đồng bộ");

        TreeItem<Folders> rootItem = new TreeItem<>(rootFolderData);
        rootItem.setExpanded(true);
        treeDirectory.setRoot(rootItem);
        treeDirectory.setShowRoot(true);

        treeDirectory.setCellFactory(tv -> new TreeCell<Folders>() {
            @Override
            protected void updateItem(Folders item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText("📁 " + item.getFolderName());
                }
            }
        });
        
        // Listener để chọn thư mục
        treeDirectory.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                handleDirectorySelected(newVal.getValue());
            }
        });
        
        // Tải các thư mục gốc lần đầu
        loadAndPopulateChildren(treeDirectory.getRoot());
    }

    /**
     * Tải và điền các thư mục con cho một TreeItem với lazy loading
     */
    private void loadAndPopulateChildren(TreeItem<Folders> parentItem) {
        // Hiển thị trạng thái đang tải
        Folders loadingFolder = new Folders();
        loadingFolder.setFolderId(-1);
        loadingFolder.setFolderName("Đang tải...");
        TreeItem<Folders> loadingItem = new TreeItem<>(loadingFolder);
        parentItem.getChildren().clear();
        parentItem.getChildren().add(loadingItem);

        TaskWrapper.executeAsync(
            "Đang tải thư mục...",
            () -> {
                try {
                    // Lấy folderId của parent. Nếu là gốc ảo, ID là 0.
                    int parentId = parentItem.getValue().getFolderId();
                    Response response = networkService.getFolderTree(parentId);
                    if (response != null && "success".equals(response.getStatus())) {
                        return fileService.parseFoldersFromResponse(response);
                    }
                    throw new Exception(response != null ? response.getMessage() : "Lỗi không xác định");
                } catch (Exception e) {
                    throw new RuntimeException("Không thể tải cây thư mục: " + e.getMessage(), e);
                }
            },
            (List<Folders> children) -> {
                // Xóa item "Đang tải..." và thêm các con thực sự
                parentItem.getChildren().clear();
                for (Folders folder : children) {
                    TreeItem<Folders> childItem = createTreeItemWithLazyLoading(folder);
                    parentItem.getChildren().add(childItem);
                }
            },
            (String error) -> {
                parentItem.getChildren().clear(); // Xóa "Đang tải..."
                mainView.showAlert("Lỗi", "Không thể tải danh sách thư mục: " + error, IMainView.AlertType.ERROR);
            },
            mainView
        );
    }
    
    /**
     * Tạo một TreeItem và thêm listener lazy loading
     */
    private TreeItem<Folders> createTreeItemWithLazyLoading(Folders folder) {
        TreeItem<Folders> item = new TreeItem<>(folder);

        // Thêm listener cho việc mở rộng để lazy load các con
        item.expandedProperty().addListener((observable, oldValue, newValue) -> {
            // Nếu item được mở rộng và chưa có con nào được tải
            if (newValue && item.getChildren().isEmpty()) {
                loadAndPopulateChildren(item);
            }
        });

        return item;
    }

    // === FILE OPERATIONS ===

    // BỎ: Các phương thức loadFullDataFromServer và onFullDataLoaded không còn cần thiết nữa
    /*
    private void loadFullDataFromServer() { ... }
    private void onFullDataLoaded(List<FileItem> items) { ... }
    */

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
     * SỬA ĐỔI: Lấy danh sách tệp cho một thư mục cụ thể từ máy chủ.
     * @param folderId ID của thư mục cần hiển thị tệp.
     */
    private void loadDirectoryFiles(int folderId) {
        if (fileService == null) {
            System.err.println("FileService chưa được khởi tạo");
            return;
        }

        currentFolderId = folderId; // Cập nhật ID thư mục hiện tại

        TaskWrapper.executeAsync(
                "Đang tải danh sách tệp cho thư mục '" + currentDirectory + "'...",
                () -> {
                    try {
                        // Gọi API để lấy tệp cho folderId cụ thể
                        return fileService.fetchAndParseFileList(folderId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                this::onDirectoryFilesLoaded, // Hàm xử lý mới
                this::onFileListError,
                mainView
        );
    }

    /**
     * MỚI: Xử lý khi danh sách tệp cho một thư mục được tải thành công.
     */
    private void onDirectoryFilesLoaded(List<FileItem> items) {
        mainView.updateFileList(FXCollections.observableArrayList(items));
        mainView.setStatusMessage("Thư mục '" + currentDirectory + "': " + items.size() + " mục.");
    }

    // Thêm phương thức mới này vào MainController.java

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
    private void handleDirectorySelected(Folders folder) {
        if (folder == null) {
            return;
        }
        currentFolderId = folder.getFolderId();
        currentDirectory = folder.getFolderName(); // Keep for display purposes
        loadDirectoryFiles(folder.getFolderId());
        mainView.setStatusMessage("Đã chọn thư mục: " + folder.getFolderName());
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
// File: src/main/java/com/pbl4/syncproject/client/controllers/MainController.java

    @FXML
    private void refresh() {
        // SỬA ĐỔI: Thay vì gọi mainView, controller sẽ tự xử lý việc làm mới
        // bằng cách gọi lại logic tải cây thư mục gốc.
        if (treeDirectory.getRoot() != null) {
            // Gọi lại hàm loadAndPopulateChildren cho thư mục gốc (root)
            // để xây dựng lại cây từ đầu một cách chính xác.
            loadAndPopulateChildren(treeDirectory.getRoot());
        }

        // Tải lại danh sách tệp cho thư mục đang được chọn (nếu có)
        if (currentFolderId > 0) {
            loadDirectoryFiles(currentFolderId);
        } else {
            mainView.setStatusMessage("Sẵn sàng. Vui lòng chọn một thư mục để xem nội dung.");
            // Xóa danh sách tệp cũ nếu không có thư mục nào được chọn
            mainView.updateFileList(FXCollections.observableArrayList());
        }

        // Cập nhật các trạng thái khác
        updateSyncAgentStatus();
    }

    /**
     * Upload file - delegate to UploadManager
     */
    private void upload() {
        // --- BẮT ĐẦU SỬA ĐỔI ---
        // Kiểm tra xem người dùng đã chọn một thư mục hợp lệ hay chưa.
        // currentFolderId = 1 là thư mục gốc, chúng ta coi nó là chưa chọn.
        // Hoặc có thể người dùng chưa chọn gì cả (giá trị vẫn là -1).
        if (currentFolderId <= 0) { // ID thư mục hợp lệ trong CSDL bắt đầu từ 1.
            mainView.showAlert("Lỗi Tải Lên", "Vui lòng chọn một thư mục cụ thể từ cây thư mục bên trái trước khi tải tệp lên!", IMainView.AlertType.WARNING);
            return;
        }
        // --- KẾT THÚC SỬA ĐỔI ---

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
            // Gửi ID thư mục hiện tại đã được chọn đến UploadManager
            uploadManager.uploadFile(selectedFile, String.valueOf(currentFolderId), (file, newFileItem, success, message) -> {
                if (success) {
                    // Sau khi tải lên thành công, tải lại danh sách tệp cho thư mục hiện tại
                    loadDirectoryFiles(currentFolderId);
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

        if (folderName == null) return; // bấm Cancel
        folderName = folderName.trim();
        if (folderName.isEmpty()) {
            mainView.setStatusMessage("Tên thư mục không được rỗng.");
            return;
        }

        // Lấy parentId từ UI (nếu không có thì null)
        Integer parentId = null;
        try {
            parentId = currentFolderId;
        } catch (Exception ignore) {
            // không có selection thì coi như tạo ở root
        }

        try {
            if (parentId == null || parentId <= 0) {
                networkService.createFolder(folderName);
            }
            else {
                networkService.createFolder(folderName, parentId);
            }
        } catch (Exception e) {
            mainView.setStatusMessage("Lỗi gửi yêu cầu tạo thư mục: " + e.getMessage());
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