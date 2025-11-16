package com.pbl4.syncproject.client.controllers;

import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.models.NotificationItem;
import com.pbl4.syncproject.client.services.FileService;
import com.pbl4.syncproject.client.services.FolderService;
import com.pbl4.syncproject.client.services.LocalDatabaseManager;
import com.pbl4.syncproject.client.services.NetworkService;
import com.pbl4.syncproject.client.services.NotificationManager;
import com.pbl4.syncproject.client.services.SyncAgent;
import com.pbl4.syncproject.client.services.UploadManager;
import com.pbl4.syncproject.client.services.DownloadService;
import com.pbl4.syncproject.client.services.SettingsService;
import com.pbl4.syncproject.client.services.FileHashService;
import com.pbl4.syncproject.client.services.FileWatcherService;
import com.pbl4.syncproject.client.utils.TaskWrapper;
import com.pbl4.syncproject.client.views.IMainView;
import com.pbl4.syncproject.client.views.MainView;
import com.pbl4.syncproject.client.views.NotificationCell;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.application.Platform;
import org.controlsfx.control.PopOver;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * MainController theo nguyên tắc Single Responsibility Principle (SRP)
 * Chỉ chịu trách nhiệm: Coordinate giữa View và Services, handle UI events
 * Tất cả business logic được delegate cho các Service classes
 */
public class MainController implements Initializable, SyncAgent.SyncEventListener {

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

    // Notification UI Components
    @FXML private StackPane notificationButtonWrapper;
    @FXML private Button btnNotifications;

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
    private FolderService folderService;
    private UploadManager uploadManager;
    private DownloadService downloadService;
    private SyncAgent syncAgent;
    private NotificationManager notificationManager;
    private String syncDirectoryPath; // đường dẫn thư mục đồng bộ
    private SettingsService settingsService; // --- THÊM DÒNG NÀY ---

    // PopOver để hiển thị thông báo
    private PopOver notificationPopOver;

    // State
    // THAY ĐỔI: Bỏ biến allFileItems vì chúng ta sẽ không lưu trữ tất cả các file nữa
    // private ObservableList<FileItem> allFileItems = FXCollections.observableArrayList();
    private String currentUser = null; // Sẽ được set từ LoginController sau khi đăng nhập
    private String currentDirectory = "/shared"; // Kept for compatibility, but now tracking folderId
    private int currentFolderId = -1; // Track selected folder ID for uploads and operations

    // Map để theo dõi các TreeItem đã tải con hay chưa (cho lazy loading)
    private final Map<TreeItem<Folders>, Boolean> loadedChildrenMap = new HashMap<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // --- THÊM DÒNG NÀY ---
        this.settingsService = SettingsService.getInstance();
        // Tải cài đặt ngay lập tức
        this.settingsService.loadSettings();
        // -----------------------

        // Chỉ initialize view và setup handlers
        // Services sẽ được khởi tạo sau khi login thành công
        initializeView();
        setupEventHandlers();

        // Khởi tạo NotificationManager
        this.notificationManager = NotificationManager.getInstance();
        setupNotificationCenter();

        // loadInitialData() sẽ được gọi sau trong setServerAddress()
    }

    // === INITIALIZATION METHODS ===

    /**
     * Set server address and username from login screen
     * BẮT BUỘC phải gọi method này từ LoginController sau khi login thành công
     */
    public void setServerAddress(String serverIP, int serverPort, String username) {
        // Lưu username từ login
        this.currentUser = username;

        // Initialize services with server address from login
        networkService = new NetworkService(serverIP, serverPort);
        networkService.setCurrentUsername(currentUser); // Set username cho NetworkService

        // --- SỬA LẠI: Thêm LocalDatabaseManager vào FileService ---
        fileService = new FileService(networkService, LocalDatabaseManager.getInstance());
        // -----------------------------------------------------------

        // FIX: Inject LocalDatabaseManager vào FolderService để nó có thể lưu folder mapping
        folderService = new FolderService(networkService, LocalDatabaseManager.getInstance());

        // --- SỬA LẠI: Khởi tạo mainView trước ---
        // mainView phải được khởi tạo trước
        if (mainView == null) {
            initializeView(); // Đảm bảo mainView được khởi tạo
        }
        mainView.setFileService(fileService);
        uploadManager = new UploadManager(networkService, mainView, LocalDatabaseManager.getInstance());

        // --- SỬA LẠI DÒNG NÀY ---
        // Dòng cũ: syncAgent = new SyncAgent(networkService, uploadManager);
        // Dòng mới:
        syncAgent = new SyncAgent(networkService, uploadManager, LocalDatabaseManager.getInstance());
        // -----------------------

        // Cập nhật UI ban đầu
        mainView.setConnectionStatus("Kết nối: Vui lòng chờ...", true);
        mainView.setNetworkStatus("Mạng: Đang kiểm tra...", true);
        mainView.setUserInfo("User: " + currentUser);

        // --- LẮNG NGHE TRẠNG THÁI MẠNG ---
        networkService.isOnlineProperty().addListener((obs, wasOnline, isNowOnline) -> {
            // Được gọi mỗi khi trạng thái online/offline thay đổi
            onNetworkStatusChanged(isNowOnline);
        });
        // --------------------------------------------------

        // --- Lấy đường dẫn đồng bộ TỪ FILE CÀI ĐẶT ---
        String defaultSyncDir = System.getProperty("user.home") + File.separator + "SyncData";
        this.syncDirectoryPath = settingsService.getSetting(SettingsService.KEY_SYNC_DIRECTORY, defaultSyncDir);

        // Khởi tạo DownloadService với đường dẫn ĐÚNG
        FileWatcherService dummyWatcher = new FileWatcherService();
        this.downloadService = new DownloadService(networkService, LocalDatabaseManager.getInstance(), this.syncDirectoryPath, dummyWatcher, folderService);
        System.out.println("✅ Khởi tạo DownloadService với đường dẫn: " + this.syncDirectoryPath);

        // Load initial data sau khi đã có services (trong đó có startSyncAgent)
        loadInitialData();

        // 🔥 SAU KHI SYNCAGENT ĐÃ START → BẬT HEARTBEAT THEO SEQ
        networkService.startHeartbeat(
                // onRemoteChange: khi lastSeq > since_seq
                () -> {
                    if (syncAgent != null) {
                        System.out.println("🔥 Heartbeat phát hiện lastSeq mới → triggerSyncQueue()");
                        syncAgent.triggerSyncQueue();
                    }
                },
                // getSinceSeq: đọc từ SyncAgent
                () -> (syncAgent != null) ? syncAgent.getSinceSeqForHeartbeat() : 0L
        );
    }

    /**
     * Deprecated: Sử dụng setServerAddress(serverIP, serverPort, username) thay thế
     * Method này giữ lại để tương thích ngược
     */
    @Deprecated
    public void setServerAddress(String serverIP, int serverPort) {
        setServerAddress(serverIP, serverPort, "guest"); // Default username nếu không truyền
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
        mainView.setOnFolderAction(this::handleFolderAction);
    }

    /**
     * Load initial data and start services
     */
    private void loadInitialData() {
        // SỬA ĐỔI: KHÔNG setup tree ở đây nữa, MainView đã setup rồi
        // setupDirectoryTreeWithLazyLoading(); // <-- COMMENT ĐI vì MainView đã có setupDirectoryTree()

        startSyncAgent();
        mainView.setStatusMessage("Sẵn sàng. Vui lòng chọn một thư mục để xem nội dung.");
    }

    /**
     * Thiết lập cây thư mục với lazy loading
     */
    private void setupDirectoryTreeWithLazyLoading() {
        Folders rootFolderData = new Folders();
        rootFolderData.setFolderId(1); // ID 1 là thư mục gốc thật trong DB
        rootFolderData.setFolderName("Thư mục đồng bộ");

        TreeItem<Folders> rootItem = new TreeItem<>(rootFolderData);
        rootItem.setExpanded(true);
        treeDirectory.setRoot(rootItem);
        treeDirectory.setShowRoot(true);
        
        // Tự động chọn root folder khi khởi tạo
        treeDirectory.getSelectionModel().select(rootItem);
        currentFolderId = 1; // Set default to root folder

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
        mainView.refreshFolderTree();
    }

    /**
     * Tải và điền các thư mục con cho một TreeItem với lazy loading
     */
    private void loadAndPopulateChildren(TreeItem<Folders> parentItem) {
        // Kiểm tra xem đã tải con chưa
        Boolean loaded = loadedChildrenMap.get(parentItem);
        if (loaded != null && loaded) {
            // Đã tải rồi, không cần tải lại
            return;
        }

        // Đánh dấu là đang tải
        loadedChildrenMap.put(parentItem, true);

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
                    if (children != null && !children.isEmpty()) {
                        for (Folders folder : children) {
                            TreeItem<Folders> childItem = createTreeItemWithLazyLoading(folder);
                            parentItem.getChildren().add(childItem);
                        }
                    }
                    // Không cần placeholder nếu không có con, TreeView sẽ tự ẩn mũi tên
                },
                (String error) -> {
                    parentItem.getChildren().clear(); // Xóa "Đang tải..."
                    loadedChildrenMap.put(parentItem, false); // Đặt lại để có thể thử lại
                    // Thêm lại placeholder để user có thể thử lại
                    addPlaceholderNode(parentItem);
                    mainView.showAlert("Lỗi", "Không thể tải danh sách thư mục: " + error, IMainView.AlertType.ERROR);
                },
                mainView
        );
    }

    /**
     * Thêm node placeholder để hiển thị mũi tên expand
     */
    private void addPlaceholderNode(TreeItem<Folders> item) {
        if (item != null && item.getChildren().isEmpty()) {
            Folders placeholder = new Folders();
            placeholder.setFolderId(0); // ID đặc biệt cho placeholder
            placeholder.setFolderName(""); // Không hiển thị text
            item.getChildren().add(new TreeItem<>(placeholder));
        }
    }

    /**
     * Tạo một TreeItem và thêm listener lazy loading với placeholder
     */
    private TreeItem<Folders> createTreeItemWithLazyLoading(Folders folder) {
        TreeItem<Folders> item = new TreeItem<>(folder);

        // Thêm placeholder để hiển thị mũi tên expand
        // Node này sẽ bị xóa khi thực sự tải con
        addPlaceholderNode(item);

        // Đánh dấu chưa tải con
        loadedChildrenMap.put(item, false);

        // Thêm listener cho việc mở rộng để lazy load các con
        item.expandedProperty().addListener((observable, oldValue, newValue) -> {
            // Nếu item được mở rộng và chưa tải con
            if (newValue) {
                Boolean loaded = loadedChildrenMap.get(item);
                // Kiểm tra xem có phải là placeholder không (1 con duy nhất với ID = 0)
                boolean hasOnlyPlaceholder = item.getChildren().size() == 1 &&
                        item.getChildren().get(0).getValue().getFolderId() == 0;
                if ((loaded == null || !loaded) && hasOnlyPlaceholder) {
                    loadAndPopulateChildren(item);
                }
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
            // --- SỬA LẠI: Sử dụng biến syncDirectoryPath đã được tải từ SettingsService ---
            // Dòng cũ: String syncDir = System.getProperty("user.home") + File.separator + "SyncFolder";
            String syncDir = this.syncDirectoryPath; // Sử dụng đường dẫn từ cài đặt
            // -------------------------------------------------------------------------------

            // --- ĐĂNG KÝ LISTENER ĐỂ NHẬN THÔNG BÁO TỪ SYNCAGENT ---
            syncAgent.setEventListener(this);
            // --------------------------------------------------------

            syncAgent.start(syncDir);
            System.out.println("✅ Started sync agent for directory: " + syncDir);
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
    @SuppressWarnings("unused")
    private void updateSyncStatus() {
        mainView.setSyncStatus("Đồng bộ: Hoạt động", true);
    }

    // --- IMPLEMENT SYNCAGENT.SYNCEVENTLISTENER ---
    /**
     * Callback từ SyncAgent khi có thay đổi local được phát hiện
     * Method này được gọi từ background thread, nên phải dùng Platform.runLater
     */
    @Override
    public void onLocalChangeDetected() {
        // Đánh thức sync queue (nếu chưa chạy)
        if (syncAgent != null) {
            syncAgent.triggerSyncQueue();
        }
        // Chuyển sang UI thread để cập nhật giao diện
        Platform.runLater(() -> {
            System.out.println("🔄 [MainController] Nhận tín hiệu thay đổi local, đang refresh UI...");

            // Refresh lại danh sách file TỪ LOCAL DB (không phải từ server)
            // để hiển thị đúng trạng thái "Đang chờ upload"
            if (currentFolderId > 0) {
                loadDirectoryFilesFromLocalDB(currentFolderId);
            }

            // Cập nhật status message
            mainView.setStatusMessage("📝 Phát hiện thay đổi file local");
        });
    }
    
    /**
     * Load danh sách files từ Local DB thay vì từ server
     * Dùng khi cần hiển thị trạng thái real-time (QUEUED, LOCAL_STALE, etc.)
     */
    private void loadDirectoryFilesFromLocalDB(int folderId) {
        TaskWrapper.executeAsync(
                "Đang tải danh sách file từ cache local...",
                () -> {
                    // Tìm local FolderID tương ứng với server FolderID
                    // (Giả sử folderId là ServerFolderID, cần convert sang local FolderID)
                    Integer localFolderId = null;
                    try (java.sql.Connection conn = syncAgent.getLocalDbManager().getConnection();
                         java.sql.PreparedStatement ps = conn.prepareStatement(
                                 "SELECT FolderID FROM Folders WHERE ServerFolderID = ?")) {
                        ps.setInt(1, folderId);
                        java.sql.ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            localFolderId = rs.getInt("FolderID");
                        }
                    } catch (Exception e) {
                        System.err.println("Lỗi tìm local FolderID: " + e.getMessage());
                        return new java.util.ArrayList<FileItem>();
                    }
                    
                    if (localFolderId == null) {
                        return new java.util.ArrayList<FileItem>();
                    }
                    
                    // Lấy files từ local DB
                    java.util.List<java.util.Map<String, Object>> rows = 
                        syncAgent.getLocalDbManager().getFilesInFolder(localFolderId);
                    
                    // Convert sang FileItem
                    java.util.List<FileItem> items = new java.util.ArrayList<>();
                    for (java.util.Map<String, Object> row : rows) {
                        FileItem item = new FileItem();
                        
                        Object serverFileIdObj = row.get("serverFileId");
                        if (serverFileIdObj != null) {
                            item.setFileId((Integer) serverFileIdObj);
                        }
                        
                        item.setFolderId(folderId); // Server FolderID
                        item.setFileName((String) row.get("fileName"));
                        Long fileSize = (Long) row.get("fileSize");
                        item.setFileSize(fileSize != null ? String.valueOf(fileSize) : "0");
                        item.setRelativePath((String) row.get("localPath"));
                        
                        // Hiển thị trạng thái sync
                        String syncStatus = (String) row.get("syncStatus");
                        item.setSyncStatus(syncStatus);
                        
                        items.add(item);
                    }
                    
                    return items;
                },
                this::onDirectoryFilesLoaded,
                this::onFileListError,
                mainView
        );
    }
    // ----------------------------------------------

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
            case "open":
                openFileLocally(fileItem);
                break;
            case "download":
                // Use DownloadService if available
                if (downloadService != null) {
                    mainView.setStatusMessage("Đang tải về " + fileItem.getFileName() + "...");
                    TaskWrapper.executeAsync(
                            "Đang tải về: " + fileItem.getFileName(),
                            () -> { try { downloadService.downloadAndSaveFile(fileItem); return true; } catch (Exception e) { throw new RuntimeException(e); } },
                            (success) -> {
                                mainView.setStatusMessage("✅ Tải về thành công: " + fileItem.getFileName());
                                mainView.showAlert("Thành công", "Đã tải file về thư mục đồng bộ!", IMainView.AlertType.INFORMATION);
                            },
                            (error) -> {
                                mainView.setStatusMessage("❌ Tải về thất bại: " + error);
                                mainView.showAlert("Lỗi", "Không thể tải file: " + error, IMainView.AlertType.ERROR);
                            },
                            mainView
                    );
                } else {
                    downloadFile(fileItem);
                }
                break;
            case "upload":
                uploadSingleFile(fileItem);
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
        System.out.println("🔄 Refresh button clicked - Reloading files for current folder...");

        // FIX: Không reset folder tree để giữ vị trí người dùng đang chọn
        // mainView.refreshFolderTree(); // <--- VÔ HIỆU HÓA DÒNG NÀY

        // Tải lại danh sách tệp cho thư mục đang được chọn (nếu có)
        if (currentFolderId > 0) {
            System.out.println("   Reloading files for current folderId: " + currentFolderId);
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
        // currentFolderId >= 1 là hợp lệ (bao gồm cả thư mục gốc ID=1).
        if (currentFolderId < 1) { // ID thư mục hợp lệ trong CSDL bắt đầu từ 1.
            mainView.showAlert("Lỗi Tải Lên", "Vui lòng chọn một thư mục từ cây thư mục bên trái trước khi tải tệp lên!", IMainView.AlertType.WARNING);
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

                    // Gửi thông báo
                    notificationManager.addNotification(
                            "Bạn đã tải lên thành công file: " + file.getName(),
                            NotificationItem.NotificationType.FILE_UPLOAD
                    );

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
     * Upload a single file (manual sync)
     * Called when user clicks Upload button in UI
     */
    private void uploadSingleFile(FileItem fileItem) {
        try {
            // 1. Get sync directory from settings
            SettingsService settingsService = SettingsService.getInstance();
            String syncDirectory = settingsService.getSetting(SettingsService.KEY_SYNC_DIRECTORY, null);

            if (syncDirectory == null || syncDirectory.isEmpty()) {
                mainView.showAlert("Lỗi", "Thư mục đồng bộ chưa được thiết lập!", IMainView.AlertType.ERROR);
                return;
            }

            // 2. Get relative path and construct full path
            String relativePath = fileItem.getRelativePath();
            if (relativePath == null || relativePath.isEmpty()) {
                mainView.showAlert("Lỗi", "Không tìm thấy đường dẫn file!", IMainView.AlertType.ERROR);
                return;
            }

            File localFile = new File(syncDirectory, relativePath);
            if (!localFile.exists() || !localFile.isFile()) {
                mainView.showAlert("Lỗi", "File không tồn tại trên máy: " + localFile.getAbsolutePath(), IMainView.AlertType.ERROR);
                return;
            }

            // 3. Confirm with user
            boolean confirmed = mainView.showConfirmDialog(
                    "Xác nhận upload",
                    "Bạn có chắc chắn muốn upload file: " + fileItem.getFileName() + " lên server?"
            );
            if (!confirmed) {
                return;
            }

            // 4. Calculate file hash BEFORE upload (to save after success)
            String currentFileHash = "";
            try {
                FileHashService hashService = new FileHashService();
                currentFileHash = hashService.calculateFileHash(localFile);
            } catch (Exception e) {
                mainView.showAlert("Lỗi", "Không thể tính hash file: " + e.getMessage(), IMainView.AlertType.ERROR);
                return;
            }

            // 5. Validate file before upload
            if (uploadManager != null && !uploadManager.validateFileForUpload(localFile)) {
                return;
            }

            // 6. Upload file using UploadManager
            mainView.setStatusMessage("Đang upload " + fileItem.getFileName() + "...");

            // Use folderId as string (UploadManager expects currentDirectory as string)
            String currentDirectory = String.valueOf(fileItem.getFolderId());

            // Make hash final to use in callback
            final String uploadedFileHash = currentFileHash;

            if (uploadManager != null) {
                uploadManager.uploadFile(localFile, currentDirectory, new UploadManager.UploadResultCallback() {
                    @Override
                    public void onUploadResult(File file, FileItem newFileItem, boolean success, String message) {
                        if (success) {
                            // LỖI ĐÃ SỬA: Update both status AND hash
                            LocalDatabaseManager dbManager = LocalDatabaseManager.getInstance();
                            dbManager.updateFileStatusAndHash(relativePath,
                                    LocalDatabaseManager.STATUS_SYNCED,
                                    uploadedFileHash);

                            // Update status message
                            mainView.setStatusMessage("✅ Upload thành công: " + fileItem.getFileName());

                            // Refresh UI to show updated status
                            if (currentFolderId > 0) {
                                loadDirectoryFiles(currentFolderId);
                            }
                        } else {
                            mainView.setStatusMessage("❌ Upload thất bại: " + message);
                        }
                    }
                });
            } else {
                mainView.showAlert("Lỗi", "UploadManager chưa được khởi tạo!", IMainView.AlertType.ERROR);
            }

        } catch (Exception e) {
            mainView.showAlert("Lỗi", "Lỗi khi upload file: " + e.getMessage(), IMainView.AlertType.ERROR);
            mainView.setStatusMessage("Lỗi: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Mở file bằng chương trình mặc định của hệ điều hành.
     * Tự động tải về nếu file chưa tồn tại trên máy.
     */
    private void openFileLocally(FileItem fileItem) {
        try {
            // 1. Lấy đường dẫn file đầy đủ trên máy
            SettingsService settingsService = SettingsService.getInstance();
            String syncDir = settingsService.getSetting(SettingsService.KEY_SYNC_DIRECTORY, "");

            if (syncDir == null || syncDir.isEmpty()) {
                mainView.showAlert("Lỗi", "Thư mục đồng bộ chưa được thiết lập!", IMainView.AlertType.ERROR);
                return;
            }

            // Lấy relativePath
            String relativePath = fileItem.getRelativePath();
            if (relativePath == null || relativePath.isEmpty()) {
                mainView.showAlert("Lỗi", "Không xác định được đường dẫn file!", IMainView.AlertType.ERROR);
                return;
            }

            File fileToOpen = new File(syncDir, relativePath);

            // 2. Kiểm tra file có tồn tại không
            if (!fileToOpen.exists()) {
                // File không có trên máy -> Hỏi người dùng có muốn tải về không
                boolean download = mainView.showConfirmDialog(
                        "File chưa có trên máy",
                        "File '" + fileItem.getFileName() + "' chưa có trên máy.\n" +
                                "Bạn có muốn tải về và mở không?"
                );

                if (!download) {
                    return;
                }

                // Tải file về trước
                mainView.setStatusMessage("Đang tải file về để mở...");

                if (downloadService != null) {
                    TaskWrapper.executeAsync(
                            "Đang tải về: " + fileItem.getFileName(),
                            () -> {
                                try {
                                    downloadService.downloadAndSaveFile(fileItem);
                                    return true;
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            (success) -> {
                                mainView.setStatusMessage("✅ Tải về thành công. Đang mở file...");
                                // Mở file sau khi tải về
                                Platform.runLater(() -> openFileWithDesktop(fileToOpen));
                            },
                            (error) -> {
                                mainView.setStatusMessage("❌ Không thể tải file: " + error);
                                mainView.showAlert("Lỗi", "Không thể tải file về để mở: " + error,
                                        IMainView.AlertType.ERROR);
                            },
                            mainView
                    );
                } else {
                    mainView.showAlert("Lỗi", "DownloadService chưa được khởi tạo!", IMainView.AlertType.ERROR);
                }

            } else {
                // 3. File đã có -> Mở ngay
                openFileWithDesktop(fileToOpen);
            }

        } catch (Exception e) {
            mainView.showAlert("Lỗi Mở File", "Không thể mở file: " + e.getMessage(),
                    IMainView.AlertType.ERROR);
            e.printStackTrace();
        }
    }

    /**
     * Hàm helper để gọi java.awt.Desktop để mở file
     */
    private void openFileWithDesktop(File file) {
        if (!Desktop.isDesktopSupported()) {
            mainView.showAlert("Lỗi", "Hệ điều hành không hỗ trợ mở file tự động.",
                    IMainView.AlertType.ERROR);
            return;
        }

        try {
            Desktop.getDesktop().open(file);
            mainView.setStatusMessage("✅ Đã mở file: " + file.getName());
        } catch (IOException e) {
            mainView.showAlert("Lỗi Mở File",
                    "Không tìm thấy chương trình mặc định để mở file này.\n" +
                            "Vui lòng mở thủ công tại: " + file.getAbsolutePath(),
                    IMainView.AlertType.ERROR);
            e.printStackTrace();
        }
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
            try {
                // Gọi NetworkService để xóa file trên server
                Response response = fileService.deleteFile(
                        fileItem.getFileId(),
                        fileItem.getFolderId(),
                        fileItem.getFileName()
                );

                if (response != null && "success".equals(response.getStatus())) {
                    mainView.setStatusMessage("Đã xóa file: " + fileItem.getFileName());
                    mainView.showAlert("Thành công", "File đã được xóa: " + fileItem.getFileName(),
                            IMainView.AlertType.INFORMATION);

                    // Làm mới danh sách file
                    if (currentFolderId > 0) {
                        loadDirectoryFiles(currentFolderId);
                    }
                } else {
                    String errorMsg = response != null ? response.getMessage() : "Không có phản hồi từ server";
                    mainView.showAlert("Lỗi", "Không thể xóa file: " + errorMsg,
                            IMainView.AlertType.ERROR);
                    mainView.setStatusMessage("Lỗi khi xóa file: " + errorMsg);
                }
            } catch (Exception e) {
                mainView.showAlert("Lỗi", "Lỗi khi xóa file: " + e.getMessage(),
                        IMainView.AlertType.ERROR);
                mainView.setStatusMessage("Lỗi: " + e.getMessage());
            }
        }
    }

    /**
     * Handle folder actions (delete, rename, etc.)
     */
    private void handleFolderAction(int folderId, String action) {
        if ("delete".equalsIgnoreCase(action)) {
            deleteFolder(folderId);
        }
        // Có thể thêm các action khác như rename, move, etc.
    }

    /**
     * Delete folder
     */
    private void deleteFolder(int folderId) {
        boolean confirmed = mainView.showConfirmDialog(
                "Xác nhận xóa thư mục",
                "Bạn có chắc chắn muốn xóa thư mục này?\n" +
                        "Thư mục sẽ được xóa cùng với tất cả nội dung bên trong (recursive)."
        );

        if (confirmed) {
            try {
                // Gọi FolderService để xóa thư mục trên server (recursive = true)
                Response response = folderService.deleteFolder(folderId, true);

                if (response != null && "success".equals(response.getStatus())) {
                    mainView.setStatusMessage("Đã xóa thư mục thành công");
                    mainView.showAlert("Thành công", "Thư mục đã được xóa",
                            IMainView.AlertType.INFORMATION);

                    // Làm mới cây thư mục
                    mainView.refreshFolderTree();

                    // Xóa danh sách file hiển thị nếu đang xem thư mục bị xóa
                    if (currentFolderId == folderId) {
                        mainView.clearFileListDisplay();
                        currentFolderId = -1;
                    }
                } else {
                    String errorMsg = response != null ? response.getMessage() : "Không có phản hồi từ server";
                    mainView.showAlert("Lỗi", "Không thể xóa thư mục: " + errorMsg,
                            IMainView.AlertType.ERROR);
                    mainView.setStatusMessage("Lỗi khi xóa thư mục: " + errorMsg);
                }
            } catch (Exception e) {
                mainView.showAlert("Lỗi", "Lỗi khi xóa thư mục: " + e.getMessage(),
                        IMainView.AlertType.ERROR);
                mainView.setStatusMessage("Lỗi: " + e.getMessage());
            }
        }
    }

    // === NOTIFICATION CENTER METHODS ===

    /**
     * Khởi tạo Trung tâm thông báo (nút chuông, badge, và popover)
     */
    private void setupNotificationCenter() {
        if (notificationButtonWrapper == null || btnNotifications == null) {
            System.err.println("Notification UI components not initialized");
            return;
        }

        // 1. Tạo Label để làm Badge (số thông báo)
        Label badge = new Label("0");
        badge.setStyle(
                "-fx-background-color: #dc2626; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 10px; " +
                        "-fx-padding: 2 6; " +
                        "-fx-background-radius: 10; " +
                        "-fx-font-weight: bold;"
        );
        badge.setVisible(false); // Chỉ hiển thị khi có thông báo
        badge.setManaged(false); // Không chiếm không gian khi ẩn

        // 2. Binding số thông báo chưa đọc vào Badge
        notificationManager.unreadCountProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasUnread = newVal.intValue() > 0;
            badge.setVisible(hasUnread);
            badge.setText(String.valueOf(newVal));
        });

        // 3. Đặt vị trí cho Badge (nằm trên góc phải của nút)
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        StackPane.setMargin(badge, new Insets(-5, -5, 0, 0));
        notificationButtonWrapper.getChildren().add(badge);
    }

    /**
     * Xử lý sự kiện khi nhấn nút chuông thông báo
     */
    @FXML
    private void handleShowNotifications() {
        // 1. Khởi tạo PopOver (chỉ 1 lần)
        if (notificationPopOver == null) {
            notificationPopOver = new PopOver();
            notificationPopOver.setArrowLocation(PopOver.ArrowLocation.TOP_CENTER);
            notificationPopOver.setDetachable(false);
            notificationPopOver.setHeaderAlwaysVisible(true);
            notificationPopOver.setTitle("Thông báo");

            // 2. Tạo ListView
            ListView<NotificationItem> notificationList = new ListView<>();
            notificationList.setPrefSize(350, 450); // Kích thước panel

            // 3. Gán danh sách thông báo từ Manager
            notificationList.setItems(notificationManager.getNotifications());

            // 4. Sử dụng CellFactory tùy chỉnh (NotificationCell)
            notificationList.setCellFactory(lv -> new NotificationCell());

            // 5. Placeholder khi không có thông báo
            Label emptyLabel = new Label("Không có thông báo nào");
            emptyLabel.setStyle("-fx-text-fill: #9ca3af; -fx-padding: 20;");
            notificationList.setPlaceholder(emptyLabel);

            // 6. Đặt ListView làm nội dung cho PopOver
            notificationPopOver.setContentNode(notificationList);

            // 7. Khi PopOver ẩn đi, đánh dấu đã đọc
            notificationPopOver.setOnHidden(e -> {
                notificationManager.markAllAsRead();
            });
        }

        // 8. Hiển thị PopOver bên dưới nút chuông
        notificationPopOver.show(btnNotifications);
    }

    // === UTILITY METHODS ===

    /**
     * Open FXML window utility
     */
    private void openFXMLWindow(String fxmlPath, String title, int width, int height) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent root = loader.load();

            // Lấy controller sau khi load FXML
            Object controller = loader.getController();

            // Kiểm tra và inject service nếu là UserPermissionController
            if (controller instanceof UserPermissionController) {
                UserPermissionController permController = (UserPermissionController) controller;
                permController.setNetworkService(this.networkService); // Truyền networkService
                permController.setFileService(this.fileService);       // Truyền fileService
                permController.setCurrentUser(this.currentUser);       // Truyền currentUser
                // Sau khi inject service, gọi lại setupUI để load dữ liệu từ server
                permController.reinitializeWithServices();
            }
            // Có thể thêm tương tự cho SettingsController nếu cần
            if (controller instanceof SettingsController) {
                SettingsController settingsController = (SettingsController) controller;
                settingsController.setSyncAgent(this.syncAgent); // <— truyền agent vào đây

            }

            Stage stage = new Stage();
            stage.setTitle(title);
            stage.setScene(new Scene(root, width, height));
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.showAndWait();
        } catch (Exception e) {
            mainView.showAlert("Lỗi", "Không thể mở cửa sổ: " + e.getMessage(),
                    IMainView.AlertType.ERROR);
            e.printStackTrace(); // In lỗi ra console để debug
        }
    }

    /**
     * Được gọi bởi Listener trong NetworkService khi trạng thái mạng thay đổi.
     * @param isNowOnline Trạng thái mạng mới
     */
    private void onNetworkStatusChanged(boolean isNowOnline) {
        if (isNowOnline) {
            System.out.println("SỰ KIỆN: Trở lại ONLINE");
            mainView.setConnectionStatus("● Kết nối: Thành công", true);
            mainView.setNetworkStatus("Mạng: Đã kết nối", true);

            // --- BỎ COMMENT VÀ SỬA DÒNG NÀY ---
            // Kích hoạt hàng đợi đồng bộ
            if (syncAgent != null) {
                syncAgent.triggerSyncQueue();
            }
            // ---------------------------------

            // Tải lại thư mục hiện tại để lấy dữ liệu mới từ server
            handleRefresh();

        } else {
            System.out.println("SỰ KIỆN: Mất kết nối (OFFLINE)");
            mainView.setConnectionStatus("● Mất kết nối", false);
            mainView.setNetworkStatus("Mạng: Offline", false);

            // --- DỪNG ĐỒNG BỘ ---
            // TODO: (Bước 5)
            // syncAgent.stopSyncQueue();
            System.out.println("LOGIC (TODO): Tạm dừng hàng đợi đồng bộ.");
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

        // Dừng heartbeat khi đóng ứng dụng
        if (networkService != null) {
            networkService.stopHeartbeat();
            System.out.println("Heartbeat stopped during cleanup");
        }
    }
}