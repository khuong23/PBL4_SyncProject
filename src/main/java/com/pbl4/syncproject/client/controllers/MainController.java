package com.pbl4.syncproject.client.controllers;

import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.services.*;
import com.pbl4.syncproject.client.utils.TaskWrapper;
import com.pbl4.syncproject.client.views.IMainView;
import com.pbl4.syncproject.client.views.MainView;
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
 * MainController: chỉ điều phối View <-> Services, xử lý sự kiện UI.
 * NetworkService được truyền từ Login (kết nối socket xuyên suốt).
 */
public class MainController implements Initializable {

    // ============ FXML UI Components ============
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

    // ============ Services ============
    private IMainView mainView;
    private NetworkService networkService; // được inject từ LoginController
    private FileService fileService;
    private FolderService folderService;
    private UploadManager uploadManager;
    private SyncAgent syncAgent;

    // ============ State ============
    private String currentUser = "admin";
    private String serverIp;
    private int serverPort;
    private int currentFolderId = -1;
    private String currentDirectory = "/shared";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Chỉ setup UI & handlers. Services sẽ được khởi tạo trong initAfterLogin()
        initializeView();
        setupEventHandlers();
    }

    // ========= Inject từ LoginController =========

    /** Gọi từ LoginController ngay sau khi login thành công */
    public void setNetworkService(NetworkService ns) {
        this.networkService = ns; // KHÔNG tạo mới NetworkService ở đây
    }

    /** Gọi từ LoginController để hiển thị IP/Port */
    public void setServerAddress(String ip, int port) {
        this.serverIp = ip;
        this.serverPort = port;
    }

    /** (Optional) Hiển thị tên người dùng */
    public void setUsername(String username) {
        this.currentUser = username != null ? username : "admin";
    }

    /** Gọi SAU KHI đã set ns + server address (+ username) */
    public void initAfterLogin() {
        if (networkService == null) {
            throw new IllegalStateException("NetworkService chưa được set!");
        }

        // Khởi tạo services dựa trên kết nối xuyên suốt đã có
        this.fileService = new FileService(networkService);
        this.folderService = new FolderService(networkService);
        this.uploadManager = new UploadManager(networkService, mainView);
        this.syncAgent = new SyncAgent(networkService, uploadManager);

        // Cập nhật UI trạng thái kết nối
        if (mainView != null) {
            mainView.setUserInfo("User: " + currentUser);
            mainView.setConnectionStatus("Kết nối: " + serverIp + ":" + serverPort, true);
            mainView.setNetworkStatus("Mạng: Đã kết nối", true);

            // View cần biết FileService để làm refresh tree
            mainView.setFileService(fileService);
        }

        // Load dữ liệu ban đầu và khởi động sync
        loadInitialData();
    }

    // ========= Khởi tạo UI =========

    private void initializeView() {
        mainView = new MainView(
                lblUserInfo,
                lblConnectionStatus,
                lblSyncStatus,
                lblSyncProgress,
                lblStatusMessage,
                lblFileCount,
                lblSelectedItems,
                lblNetworkStatus,
                txtSearch,
                cmbViewMode,
                cmbSortBy,
                treeDirectory,
                progressSync,
                tableFiles,
                colFileName,
                colFileSize,
                colFileType,
                colLastModified,
                colPermissions,
                colSyncStatus,
                colActions,
                null // fileService set sau trong initAfterLogin()
        );

        setupTableColumns();

        // Trạng thái chờ trước login
        mainView.setUserInfo("User: " + currentUser);
        mainView.setStatusMessage("Đang chờ kết nối server...");
        mainView.setConnectionStatus("● Chờ đăng nhập", false);
        mainView.setNetworkStatus("Mạng: Chưa kết nối", false);
    }

    private void setupTableColumns() {
        colFileName.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(cd.getValue().getFileName()));
        colFileSize.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(cd.getValue().getFileSize()));
        colFileType.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(cd.getValue().getFileType()));
        colLastModified.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(cd.getValue().getLastModified()));
        colPermissions.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(cd.getValue().getPermissions()));
        colSyncStatus.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(cd.getValue().getSyncStatus()));
        colActions.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty("Actions"));
    }

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

    // ========= Sau login =========

    private void loadInitialData() {
        mainView.refreshFolderTree();  // dựa trên fileService
        startSyncAgent();
        mainView.setStatusMessage("Sẵn sàng. Vui lòng chọn một thư mục để xem nội dung.");
    }

    private void startSyncAgent() {
        try {
            String syncDir = System.getProperty("user.home") + File.separator + "SyncFolder";
            syncAgent.start(syncDir);
            updateSyncAgentStatus();
        } catch (Exception e) {
            System.err.println("Failed to start sync agent: " + e.getMessage());
            mainView.setStatusMessage("Cảnh báo: Không thể khởi động auto sync");
        }
    }

    private void updateSyncAgentStatus() {
        if (syncAgent != null) {
            SyncAgent.SyncStatus st = syncAgent.getStatus();
            String text = "Auto Sync: " + (st.isRunning ? "Đang chạy" : "Dừng");
            if (st.isRunning) text += " | Queue: " + st.queueSize + " | Processed: " + st.processedCount;
            mainView.setSyncStatus(text, st.isRunning);
        }
    }

    // ========= Directory/File ops =========

    private void loadDirectoryFiles(int folderId) {
        if (fileService == null) {
            System.err.println("FileService chưa được khởi tạo");
            return;
        }
        currentFolderId = folderId;

        TaskWrapper.executeAsync(
                "Đang tải danh sách tệp cho thư mục '" + currentDirectory + "'...",
                () -> {
                    try {
                        return fileService.fetchAndParseFileList(folderId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                this::onDirectoryFilesLoaded,
                this::onFileListError,
                mainView
        );
    }

    private void onDirectoryFilesLoaded(List<FileItem> items) {
        mainView.updateFileList(FXCollections.observableArrayList(items));
        mainView.setStatusMessage("Thư mục '" + currentDirectory + "': " + items.size() + " mục.");
    }

    private void onFileListError(String error) {
        mainView.setStatusMessage("Lỗi: " + error);
        mainView.showAlert("Lỗi kết nối",
                "Không thể tải dữ liệu từ server:\n" + error +
                        "\n\nVui lòng:\n1. Kiểm tra ServerApp đã chạy\n2. Kiểm tra kết nối mạng",
                IMainView.AlertType.ERROR);
    }

    // ========= Event handlers =========

    private void handleDirectorySelected(Folders folder) {
        if (folder == null) return;
        currentFolderId = folder.getFolderId();
        currentDirectory = folder.getFolderName();
        loadDirectoryFiles(currentFolderId);
        mainView.setStatusMessage("Đã chọn thư mục: " + folder.getFolderName());
    }

    private void handleFileSelected(FileItem fileItem) {
        mainView.setStatusMessage("Đã chọn file: " + fileItem.getFileName());
    }

    private void handleFileAction(FileItem fileItem, String action) {
        switch (action.toLowerCase()) {
            case "download": downloadFile(fileItem); break;
            case "edit":     editFile(fileItem);     break;
            case "delete":   deleteFile(fileItem);   break;
            default:
                mainView.showAlert("Lỗi", "Hành động không được hỗ trợ: " + action,
                        IMainView.AlertType.ERROR);
        }
    }

    // ========= Business actions =========

    private void handleLogout() { logout(); }
    private void handleRefresh() { refresh(); }
    private void handleUpload() { upload(); }
    private void handleCreateFolder() { createFolder(); }
    private void handlePermissions() { openPermissions(); }
    private void handleSettings() { openSettings(); }
    private void handleSearch() { search(); }

    private void logout() {
        boolean confirmed = mainView.showConfirmDialog(
                "Xác nhận đăng xuất",
                "Bạn có chắc chắn muốn đăng xuất không?"
        );
        if (!confirmed) return;

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

    private void refresh() {
        mainView.refreshFolderTree();
        if (currentFolderId > 0) {
            loadDirectoryFiles(currentFolderId);
        } else {
            mainView.setStatusMessage("Sẵn sàng. Vui lòng chọn một thư mục để xem nội dung.");
        }
        updateSyncAgentStatus();
    }

    private void upload() {
        if (currentFolderId <= 0) {
            mainView.showAlert("Lỗi Tải Lên",
                    "Vui lòng chọn một thư mục cụ thể từ cây thư mục bên trái trước khi tải tệp lên!",
                    IMainView.AlertType.WARNING);
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Chọn file để tải lên");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Tất cả file", "*.*"),
                new FileChooser.ExtensionFilter("Tài liệu", "*.doc", "*.docx", "*.pdf", "*.txt", "*.rtf"),
                new FileChooser.ExtensionFilter("Hình ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.tiff"),
                new FileChooser.ExtensionFilter("Video", "*.mp4", "*.avi", "*.mkv", "*.mov", "*.wmv"),
                new FileChooser.ExtensionFilter("Audio", "*.mp3", "*.wav", "*.flac", "*.aac"),
                new FileChooser.ExtensionFilter("Archive", "*.zip", "*.rar", "*.7z", "*.tar", "*.gz")
        );

        File selectedFile = fc.showOpenDialog(btnUpload.getScene().getWindow());
        if (selectedFile != null) {
            uploadManager.uploadFile(selectedFile, String.valueOf(currentFolderId), (file, newFileItem, success, message) -> {
                if (success) {
                    loadDirectoryFiles(currentFolderId);
                    mainView.setStatusMessage(message);
                } else {
                    mainView.setStatusMessage("Upload thất bại: " + message);
                }
            });
        }
    }

    private void createFolder() {
        String folderName = mainView.showInputDialog(
                "Tạo thư mục mới",
                "Nhập tên thư mục:",
                "Thư mục mới"
        );
        if (folderName == null) return;
        folderName = folderName.trim();
        if (folderName.isEmpty()) {
            mainView.setStatusMessage("Tên thư mục không được rỗng.");
            return;
        }

        Integer parentId = currentFolderId > 0 ? currentFolderId : null;
        try {
            if (parentId == null) {
                folderService.createFolder(folderName);
            } else {
                folderService.createFolder(folderName, parentId);
            }
        } catch (Exception e) {
            mainView.setStatusMessage("Lỗi gửi yêu cầu tạo thư mục: " + e.getMessage());
        }
    }

    private void openPermissions() {
        openFXMLWindow("/com/pbl4/syncproject/user-permission.fxml",
                "Quản lý quyền người dùng", 600, 500);
    }

    private void openSettings() {
        openFXMLWindow("/com/pbl4/syncproject/settings.fxml",
                "Cài đặt hệ thống", 600, 500);
    }

    private void search() {
        String searchText = mainView.getSearchText();
        if (searchText.trim().isEmpty()) {
            mainView.showAlert("Thông báo", "Vui lòng nhập từ khóa tìm kiếm", IMainView.AlertType.INFORMATION);
            return;
        }
        mainView.setStatusMessage("Đang tìm kiếm: " + searchText);
    }

    private void downloadFile(FileItem fileItem) {
        mainView.setStatusMessage("Chức năng download sẽ được implement");
    }

    private void editFile(FileItem fileItem) {
        mainView.setStatusMessage("Chức năng edit sẽ được implement");
    }

    private void deleteFile(FileItem fileItem) {
        boolean confirmed = mainView.showConfirmDialog(
                "Xác nhận xóa",
                "Bạn có chắc chắn muốn xóa file: " + fileItem.getFileName() + "?"
        );
        if (confirmed) {
            mainView.setStatusMessage("Chức năng delete sẽ được implement");
        }
    }

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

    /** Dọn tài nguyên khi logout/đóng app */
    public void cleanup() {
        if (syncAgent != null) {
            syncAgent.stop();
        }
        if (networkService != null) {
            networkService.stop(); // đóng socket xuyên suốt
        }
    }
}
