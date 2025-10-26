package com.pbl4.syncproject.client.views;

import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.services.FileService;
import com.pbl4.syncproject.client.utils.TaskWrapper;
import com.pbl4.syncproject.common.model.Folders;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * MainView class chịu trách nhiệm quản lý tất cả các thao tác GUI
 * Tách biệt presentation logic khỏi business logic trong Controller
 */
public class MainView implements IMainView {

    // UI Components - sẽ được inject từ Controller
    private Label lblUserInfo;
    private Label lblConnectionStatus;
    private Label lblSyncStatus;
    private Label lblSyncProgress;
    private Label lblStatusMessage;
    private Label lblFileCount;
    private Label lblSelectedItems;
    private Label lblNetworkStatus;

    private TextField txtSearch;
    private ComboBox<String> cmbViewMode;
    private ComboBox<String> cmbSortBy;

    private TreeView<Folders> treeDirectory;
    private ProgressBar progressSync;
    private TableView<FileItem> tableFiles;

    private TableColumn<FileItem, String> colFileName;
    private TableColumn<FileItem, String> colFileSize;
    private TableColumn<FileItem, String> colFileType;
    private TableColumn<FileItem, String> colLastModified;
    private TableColumn<FileItem, String> colPermissions;
    private TableColumn<FileItem, String> colSyncStatus;
    private TableColumn<FileItem, String> colActions;

    // Event Handlers
    @SuppressWarnings("unused")
    private Runnable onLogout;
    @SuppressWarnings("unused")
    private Runnable onRefresh;
    @SuppressWarnings("unused")
    private Runnable onUpload;
    @SuppressWarnings("unused")
    private Runnable onCreateFolder;
    @SuppressWarnings("unused")
    private Runnable onPermissions;
    @SuppressWarnings("unused")
    private Runnable onSettings;
    @SuppressWarnings("unused")
    private Runnable onSearch;
    private DirectorySelectionHandler onDirectorySelected;
    private FileSelectionHandler onFileSelected;
    private FileActionHandler onFileDoubleClick;

    // Data
    private ObservableList<FileItem> originalFileItems = FXCollections.observableArrayList();
    private String currentDirectory = "/shared";
    
    // Map để theo dõi các TreeItem đã tải con hay chưa (cho lazy loading)
    private final Map<TreeItem<Folders>, Boolean> loadedChildrenMap = new HashMap<>();

    // Services
    private FileService fileService;

    /**
     * Constructor - nhận tất cả UI components từ Controller
     */
    public MainView(Label lblUserInfo, Label lblConnectionStatus, Label lblSyncStatus,
                    Label lblSyncProgress, Label lblStatusMessage, Label lblFileCount,
                    Label lblSelectedItems, Label lblNetworkStatus, TextField txtSearch,
                    ComboBox<String> cmbViewMode, ComboBox<String> cmbSortBy,
                    TreeView<Folders> treeDirectory, ProgressBar progressSync,
                    TableView<FileItem> tableFiles, TableColumn<FileItem, String> colFileName,
                    TableColumn<FileItem, String> colFileSize, TableColumn<FileItem, String> colFileType,
                    TableColumn<FileItem, String> colLastModified, TableColumn<FileItem, String> colPermissions,
                    TableColumn<FileItem, String> colSyncStatus, TableColumn<FileItem, String> colActions,
                    FileService fileService) {

        this.lblUserInfo = lblUserInfo;
        this.lblConnectionStatus = lblConnectionStatus;
        this.lblSyncStatus = lblSyncStatus;
        this.lblSyncProgress = lblSyncProgress;
        this.lblStatusMessage = lblStatusMessage;
        this.lblFileCount = lblFileCount;
        this.lblSelectedItems = lblSelectedItems;
        this.lblNetworkStatus = lblNetworkStatus;
        this.txtSearch = txtSearch;
        this.cmbViewMode = cmbViewMode;
        this.cmbSortBy = cmbSortBy;
        this.treeDirectory = treeDirectory;
        this.progressSync = progressSync;
        this.tableFiles = tableFiles;
        this.colFileName = colFileName;
        this.colFileSize = colFileSize;
        this.colFileType = colFileType;
        this.colLastModified = colLastModified;
        this.colPermissions = colPermissions;
        this.colSyncStatus = colSyncStatus;
        this.colActions = colActions;
        this.fileService = fileService; // Có thể null ban đầu

        initializeView();
    }

    /**
     * Set FileService sau khi login thành công
     */
    @Override
    public void setFileService(FileService fileService) {
        this.fileService = fileService;
        System.out.println("📁 FileService đã được cập nhật trong MainView");

        // Tự động refresh folder tree sau khi FileService được set
        // REMOVED: Gây duplicate loading vì MainController đã gọi loadInitialData()
        // if (fileService != null) {
        //     refreshFolderTree();
        // }
    }

    /**
     * Refresh folder tree - gọi sau khi login thành công hoặc khi cần reload folders
     * Implementation của IMainView interface
     */
    @Override
    public void refreshFolderTree() {
        if (treeDirectory != null && treeDirectory.getRoot() != null) {
            // Xóa trạng thái đã tải của tất cả các node
            loadedChildrenMap.clear();

            TreeItem<Folders> rootItem = treeDirectory.getRoot();
            rootItem.getChildren().clear(); // Xóa hết con cũ
            rootItem.setExpanded(false); // Đóng root lại

            // Thêm lại placeholder cho root và đánh dấu chưa tải
            addPlaceholderNode(rootItem);
            loadedChildrenMap.put(rootItem, false); // Đánh dấu root chưa tải con

            System.out.println("🔄 Folder tree refreshed, lazy load state reset.");
            
            // Có thể tự động mở rộng và tải con của root nếu muốn
            // rootItem.setExpanded(true); // Nếu muốn tự mở root sau khi refresh
        }
    }

    /**
     * Khởi tạo view và setup các components
     */
    private void initializeView() {
        setupComboBoxes();
        setupTableView();
        setupDirectoryTree();
        setupSearchFunctionality();
    }

    private void setupComboBoxes() {
        // Setup view mode ComboBox
        cmbViewMode.getItems().addAll("Chi tiết", "Biểu tượng", "Danh sách");
        cmbViewMode.setValue("Chi tiết");
        cmbViewMode.valueProperty().addListener((obs, oldVal, newVal) -> {
            changeViewMode(newVal);
        });

        // Setup sort by ComboBox
        cmbSortBy.getItems().addAll("Tên", "Kích thước", "Ngày sửa đổi", "Loại file");
        cmbSortBy.setValue("Tên");
        cmbSortBy.valueProperty().addListener((obs, oldVal, newVal) -> {
            sortFiles(newVal);
        });
    }

    private void setupTableView() {
        // Setup columns
        colFileName.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colFileSize.setCellValueFactory(new PropertyValueFactory<>("fileSize"));
        colFileType.setCellValueFactory(new PropertyValueFactory<>("fileType"));
        colLastModified.setCellValueFactory(new PropertyValueFactory<>("lastModified"));
        colPermissions.setCellValueFactory(new PropertyValueFactory<>("permissions"));
        colSyncStatus.setCellValueFactory(new PropertyValueFactory<>("syncStatus"));

        // Setup action column with buttons
        colActions.setCellFactory(column -> new TableCell<FileItem, String>() {
            private final Button downloadBtn = new Button("📥");
            private final Button editBtn = new Button("✏️");
            private final Button deleteBtn = new Button("🗑️");

            {
                downloadBtn.setOnAction(e -> {
                    FileItem item = getTableView().getItems().get(getIndex());
                    if (onFileDoubleClick != null) {
                        onFileDoubleClick.onFileAction(item, "download");
                    }
                });

                editBtn.setOnAction(e -> {
                    FileItem item = getTableView().getItems().get(getIndex());
                    if (onFileDoubleClick != null) {
                        onFileDoubleClick.onFileAction(item, "edit");
                    }
                });

                deleteBtn.setOnAction(e -> {
                    FileItem item = getTableView().getItems().get(getIndex());
                    if (onFileDoubleClick != null) {
                        onFileDoubleClick.onFileAction(item, "delete");
                    }
                });

                downloadBtn.setStyle("-fx-background-radius: 3; -fx-padding: 2 6;");
                editBtn.setStyle("-fx-background-radius: 3; -fx-padding: 2 6;");
                deleteBtn.setStyle("-fx-background-radius: 3; -fx-padding: 2 6; -fx-background-color: #fecaca;");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    HBox hbox = new HBox(3);
                    hbox.getChildren().addAll(downloadBtn, editBtn, deleteBtn);
                    setGraphic(hbox);
                }
            }
        });

        // Selection listener
        tableFiles.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            updateSelectionInfo();
            if (newSelection != null && onFileSelected != null) {
                onFileSelected.onFileSelected(newSelection);
            }
        });
    }

    private void setupDirectoryTree() {
        // Create root folder object
        Folders rootFolder = new Folders();
        rootFolder.setFolderId(1); // ID thư mục gốc là 1
        rootFolder.setFolderName("Thư mục gốc"); // Đặt tên rõ ràng hơn

        // Create root TreeItem
        TreeItem<Folders> rootItem = new TreeItem<>(rootFolder);
        rootItem.setExpanded(false); // Bắt đầu không mở rộng
        treeDirectory.setRoot(rootItem);
        treeDirectory.setShowRoot(true);

        // *** LAZY LOADING: Thêm placeholder để hiển thị mũi tên mở rộng ***
        addPlaceholderNode(rootItem);

        // Lắng nghe sự kiện mở rộng/thu gọn cho root
        rootItem.expandedProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue) { // Chỉ tải khi mở rộng (newValue = true)
                loadChildrenIfNeeded(rootItem);
            }
        });

        // --- Thêm ContextMenu ---
        ContextMenu folderContextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("🗑️ Xóa thư mục");
        folderContextMenu.getItems().add(deleteItem);
        treeDirectory.setContextMenu(folderContextMenu);

        // Chỉ hiển thị menu khi click chuột phải vào một thư mục hợp lệ (không phải root)
        treeDirectory.setOnContextMenuRequested(event -> {
            TreeItem<Folders> selectedItem = treeDirectory.getSelectionModel().getSelectedItem();
            // Không hiển thị nếu không chọn gì, hoặc chọn root, hoặc giá trị là null
            if (selectedItem == null || selectedItem == treeDirectory.getRoot() || 
                selectedItem.getValue() == null || selectedItem.getValue().getFolderId() <= 1) {
                folderContextMenu.hide();
                event.consume();
            }
        });
        // --- Kết thúc thêm ContextMenu ---

        // Selection listener - now receives Folders object
        treeDirectory.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                Folders selectedFolder = newVal.getValue();
                if (onDirectorySelected != null) {
                    onDirectorySelected.onDirectorySelected(selectedFolder);
                }
            }
        });

        // Custom cell factory to display folder icon + name và thêm lazy loading cho từng cell
        treeDirectory.setCellFactory(tv -> new TreeCell<Folders>() {
            @Override
            protected void updateItem(Folders item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else if (item.getFolderId() == 0) { 
                    // Node placeholder - hiển thị với style khác
                    setText(item.getFolderName());
                    setGraphic(null);
                    setStyle("-fx-text-fill: #94a3b8; -fx-font-style: italic;");
                } else {
                    setText("📁 " + item.getFolderName());
                    setStyle(""); // Reset style cho các folder thật

                    // *** LAZY LOADING: Thêm listener mở rộng cho từng cell (FALLBACK) ***
                    TreeItem<Folders> treeItem = getTreeItem();
                    if (treeItem != null && treeItem != tv.getRoot()) {
                        // Kiểm tra xem đã quản lý node này chưa
                        if (!loadedChildrenMap.containsKey(treeItem)) {
                            // Debug: In ra hasChildren để kiểm tra
                            System.out.println("📂 CELLF ACTORY: Folder: " + item.getFolderName() + " | hasChildren: " + item.getHasChildren());
                            
                            if (item.getHasChildren()) {
                                // 1. Nếu CÓ con: Thêm listener và placeholder
                                treeItem.expandedProperty().addListener((observable, oldValue, newValue) -> {
                                    if (newValue) { // Chỉ tải khi mở rộng
                                        loadChildrenIfNeeded(treeItem);
                                    }
                                });
                                loadedChildrenMap.put(treeItem, false); // Đánh dấu chưa tải
                                addPlaceholderNode(treeItem); // Thêm placeholder để có mũi tên
                                System.out.println("  ✅ [CellFactory] Đã thêm placeholder cho: " + item.getFolderName());
                            } else {
                                // 2. Nếu KHÔNG có con: Đánh dấu là đã tải (vì không có gì để tải)
                                loadedChildrenMap.put(treeItem, true);
                                System.out.println("  ⭕ [CellFactory] Không thêm placeholder cho: " + item.getFolderName() + " (không có con)");
                                // Không thêm placeholder -> Sẽ không có mũi tên
                            }
                        }
                    }
                }
            }
        });
    }

    /**
     * Thêm một node giả vào TreeItem để nó hiển thị mũi tên mở rộng.
     * Node giả này sẽ bị xóa khi dữ liệu con thật được tải.
     */
    private void addPlaceholderNode(TreeItem<Folders> item) {
        if (item != null && item.getChildren().isEmpty()) {
            // Tạo một đối tượng Folders giả với ID đặc biệt (0 = placeholder)
            Folders placeholder = new Folders();
            placeholder.setFolderId(0);
            placeholder.setFolderName("⏳ Nhấn để tải...");
            item.getChildren().add(new TreeItem<>(placeholder));
            System.out.println("    🔸 addPlaceholderNode() được gọi cho: " + item.getValue().getFolderName());
        } else if (item != null && !item.getChildren().isEmpty()) {
            System.out.println("    ⚠️ addPlaceholderNode() bị bỏ qua (đã có children): " + item.getValue().getFolderName());
        }
    }

    /**
     * Tải các thư mục con cho một TreeItem nếu chúng chưa được tải.
     */
    private void loadChildrenIfNeeded(TreeItem<Folders> parentItem) {
        // Kiểm tra xem đã tải con chưa (trạng thái trong map)
        Boolean loaded = loadedChildrenMap.get(parentItem);
        // Chỉ tải nếu chưa tải (loaded == false) và có FileService
        if (loaded != null && !loaded && fileService != null) {
            Folders parentFolder = parentItem.getValue();
            if (parentFolder == null || parentFolder.getFolderId() <= 0) {
                // Không tải con cho node giả hoặc node không hợp lệ
                return;
            }
            int parentId = parentFolder.getFolderId();

            System.out.println("🔄 Loading children for: " + parentFolder.getFolderName() + " (ID=" + parentId + ")");

            // Hiển thị thông báo đang tải
            String loadingMsg = "Đang tải thư mục: " + parentFolder.getFolderName() + "...";
            setStatusMessage("⏳ " + loadingMsg);
            showLoadingProgress(true);
            showToast(loadingMsg, "info", 2);

            // Đánh dấu là đang tải để tránh gọi lại
            loadedChildrenMap.put(parentItem, true); // Đánh dấu là đã bắt đầu tải (true)

            // Gọi FileService để lấy thư mục con (chạy nền)
            TaskWrapper.executeAsync(
                "Đang tải thư mục con...", // Thông báo trạng thái
                () -> { // Nhiệm vụ chạy nền
                    try {
                        return fileService.fetchAndParseFolderTree(parentId); // Gọi API lấy con
                    } catch (Exception e) {
                        throw new RuntimeException("Lỗi tải thư mục con cho ID=" + parentId + ": " + e.getMessage(), e);
                    }
                },
                (childFolders) -> { // onSuccess - Chạy trên UI Thread
                    System.out.println("🎯 onSuccess callback được gọi! childFolders size: " + (childFolders != null ? childFolders.size() : "null"));
                    
                    // Xóa node placeholder "Loading..." trước khi thêm con thật
                    parentItem.getChildren().removeIf(item -> item.getValue().getFolderId() == 0);

                    if (childFolders != null && !childFolders.isEmpty()) {
                        System.out.println("📦 Bắt đầu thêm " + childFolders.size() + " folder con vào tree...");
                        for (Folders child : childFolders) {
                            TreeItem<Folders> childItem = new TreeItem<>(child);
                            
                            // *** QUAN TRỌNG: Thêm logic lazy loading NGAY TẠI ĐÂY ***
                            // Đánh dấu node này trong map
                            if (child.getHasChildren()) {
                                // Folder CÓ con: Thêm listener và placeholder
                                childItem.expandedProperty().addListener((observable, oldValue, newValue) -> {
                                    if (newValue) {
                                        loadChildrenIfNeeded(childItem);
                                    }
                                });
                                loadedChildrenMap.put(childItem, false); // Chưa tải con
                                addPlaceholderNode(childItem); // Thêm placeholder để có mũi tên
                                System.out.println("  ✅ Folder CÓ con, đã thêm placeholder: " + child.getFolderName());
                            } else {
                                // Folder KHÔNG có con: Đánh dấu đã tải
                                loadedChildrenMap.put(childItem, true); // Không cần tải gì
                                System.out.println("  ⭕ Folder KHÔNG có con: " + child.getFolderName());
                            }
                            
                            // Thêm vào tree
                            parentItem.getChildren().add(childItem);
                        }
                        // Hiển thị thông báo thành công
                        String successMsg = "Đã tải " + childFolders.size() + " thư mục từ: " + parentFolder.getFolderName();
                        setStatusMessage("✅ " + successMsg);
                        showToast(successMsg, "success", 3);
                    } else {
                        String emptyMsg = "Thư mục '" + parentFolder.getFolderName() + "' không có thư mục con";
                        setStatusMessage("📁 " + emptyMsg);
                        showToast(emptyMsg, "info", 2);
                    }
                    showLoadingProgress(false);
                    System.out.println("✅ Loaded " + (childFolders != null ? childFolders.size() : 0) + " children for: " + parentFolder.getFolderName());
                },
                (errorMsg) -> { // onError - Chạy trên UI Thread
                    System.err.println("❌ Error loading children for " + parentFolder.getFolderName() + ": " + errorMsg);
                    // Hiển thị lỗi cho người dùng
                    String errorMessage = "Không thể tải thư mục con của '" + parentFolder.getFolderName() + "'";
                    setStatusMessage("❌ " + errorMessage);
                    showLoadingProgress(false);
                    showToast(errorMessage, "error", 4);
                    showAlert("Lỗi Tải Thư Mục", "Không thể tải các thư mục con: " + errorMsg, AlertType.ERROR);
                    // Đặt lại trạng thái để có thể thử tải lại khi click lần nữa
                    loadedChildrenMap.put(parentItem, false);
                    // Xóa node "Loading..." nếu có lỗi
                    parentItem.getChildren().removeIf(item -> item.getValue().getFolderId() == 0);
                    // Có thể thêm lại placeholder để user thử lại
                    addPlaceholderNode(parentItem);
                },
                this // Truyền MainView để TaskWrapper có thể gọi showAlert, setStatusMessage
            );
        } else if (loaded == null) {
            // Trường hợp TreeItem chưa được quản lý bởi map (có thể xảy ra nếu CellFactory chưa chạy)
            System.out.println("Item not managed yet: " + parentItem.getValue().getFolderName());
            // Thử đánh dấu và tải lại
            loadedChildrenMap.put(parentItem, false);
            loadChildrenIfNeeded(parentItem);
        } else {
            // Đã tải rồi hoặc đang tải, không cần làm gì
            System.out.println("Children already loaded or loading for: " + parentItem.getValue().getFolderName());
        }
    }

    private void setupSearchFunctionality() {
        txtSearch.textProperty().addListener((obs, oldText, newText) -> {
            filterFiles(newText);
        });
    }

    // IMainView Implementation

    @Override
    public void setUserInfo(String userInfo) {
        Platform.runLater(() -> lblUserInfo.setText(userInfo));
    }

    @Override
    public void setConnectionStatus(String status, boolean isConnected) {
        Platform.runLater(() -> {
            lblConnectionStatus.setText(status);
            lblConnectionStatus.setStyle(isConnected ?
                    "-fx-text-fill: #10b981;" : "-fx-text-fill: #dc2626;");
        });
    }

    @Override
    public void setNetworkStatus(String status, boolean isConnected) {
        Platform.runLater(() -> {
            lblNetworkStatus.setText(status);
            lblNetworkStatus.setStyle(isConnected ?
                    "-fx-text-fill: #059669;" : "-fx-text-fill: #dc2626;");
        });
    }

    @Override
    public void setStatusMessage(String message) {
        Platform.runLater(() -> lblStatusMessage.setText(message));
    }

    @Override
    public void setSyncStatus(String status, boolean isSuccess) {
        Platform.runLater(() -> {
            lblSyncStatus.setText(status);
            lblSyncStatus.setStyle(isSuccess ?
                    "-fx-text-fill: #059669;" : "-fx-text-fill: #f59e0b;");
        });
    }

    @Override
    public void setSyncProgress(double progress, String message, boolean visible) {
        Platform.runLater(() -> {
            progressSync.setProgress(progress);
            progressSync.setVisible(visible);
            lblSyncProgress.setText(message);
            lblSyncProgress.setVisible(visible);
        });
    }

    /**
     * Hiển thị/ẩn progress bar khi đang tải dữ liệu
     */
    private void showLoadingProgress(boolean show) {
        Platform.runLater(() -> {
            if (show) {
                progressSync.setProgress(-1); // Indeterminate progress
                progressSync.setVisible(true);
                lblSyncProgress.setText("Đang tải...");
                lblSyncProgress.setVisible(true);
            } else {
                progressSync.setVisible(false);
                lblSyncProgress.setVisible(false);
            }
        });
    }

    @Override
    public void setFileCount(int count) {
        Platform.runLater(() -> {
            lblFileCount.setText(count + " file" + (count != 1 ? "s" : ""));
        });
    }

    @Override
    public void setSelectedItemsInfo(String info) {
        Platform.runLater(() -> lblSelectedItems.setText(info));
    }

    @Override
    public void updateFileList(ObservableList<FileItem> fileItems) {
        Platform.runLater(() -> {
            this.originalFileItems.clear();
            this.originalFileItems.addAll(fileItems);
            tableFiles.setItems(this.originalFileItems);
            setFileCount(fileItems.size());
        });
    }

    @Override
    public void refreshFileList() {
        Platform.runLater(() -> {
            tableFiles.refresh();
            setFileCount(tableFiles.getItems().size());
        });
    }

    @Override
    public void clearSelection() {
        Platform.runLater(() -> {
            tableFiles.getSelectionModel().clearSelection();
            updateSelectionInfo();
        });
    }

    @Override
    public FileItem getSelectedFile() {
        return tableFiles.getSelectionModel().getSelectedItem();
    }

    @Override
    public ObservableList<FileItem> getSelectedFiles() {
        return tableFiles.getSelectionModel().getSelectedItems();
    }

    @Override
    public int getSelectedFileId() {
        FileItem selected = tableFiles.getSelectionModel().getSelectedItem();
        if (selected != null) {
            return selected.getFileId();
        }
        return -1; // Không có file nào được chọn
    }

    @Override
    public String getSelectedFileNameOriginal() {
        FileItem selected = tableFiles.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getFileName() != null) {
            // Loại bỏ icon ở đầu tên file
            String displayName = selected.getFileName();
            int firstSpace = displayName.indexOf(" ");
            if (firstSpace > 0 && firstSpace < 5) { // Giả sử icon chỉ có vài ký tự
                return displayName.substring(firstSpace + 1).trim();
            }
            return displayName.trim(); // Trả về nếu không có dạng icon + tên
        }
        return null;
    }

    @Override
    public void updateDirectoryTree() {
        Platform.runLater(() -> {
            // Refresh directory tree if needed
            setupDirectoryTree();
        });
    }

    @Override
    public void selectDirectory(String directory) {
        Platform.runLater(() -> {
            currentDirectory = directory;
            // Find and select the directory in tree view
            // This would require traversing the tree
        });
    }

    @Override
    public String getCurrentDirectory() {
        return currentDirectory;
    }

    @Override
    public Folders getSelectedFolder() {
        TreeItem<Folders> selectedItem = treeDirectory.getSelectionModel().getSelectedItem();
        if (selectedItem != null && selectedItem != treeDirectory.getRoot()) {
            return selectedItem.getValue();
        }
        return null; // Không có thư mục hợp lệ nào được chọn
    }

    @Override
    public void clearFileListDisplay() {
        Platform.runLater(() -> {
            tableFiles.setItems(FXCollections.observableArrayList()); // Đặt danh sách rỗng
            setFileCount(0); // Cập nhật số lượng file
        });
    }

    @Override
    public void filterFiles(String searchText) {
        Platform.runLater(() -> {
            if (searchText == null || searchText.trim().isEmpty()) {
                tableFiles.setItems(originalFileItems);
            } else {
                ObservableList<FileItem> filteredList = FXCollections.observableArrayList();
                for (FileItem item : originalFileItems) {
                    if (item.getFileName().toLowerCase().contains(searchText.toLowerCase()) ||
                            item.getFileType().toLowerCase().contains(searchText.toLowerCase())) {
                        filteredList.add(item);
                    }
                }
                tableFiles.setItems(filteredList);
            }
            setFileCount(tableFiles.getItems().size());
        });
    }

    @Override
    public void sortFiles(String sortBy) {
        Platform.runLater(() -> {
            ObservableList<FileItem> sortedList = FXCollections.observableArrayList(tableFiles.getItems());

            switch (sortBy) {
                case "Tên":
                    sortedList.sort((a, b) -> a.getFileName().compareToIgnoreCase(b.getFileName()));
                    break;
                case "Kích thước":
                    sortedList.sort((a, b) -> a.getFileSize().compareToIgnoreCase(b.getFileSize()));
                    break;
                case "Ngày sửa đổi":
                    sortedList.sort((a, b) -> a.getLastModified().compareToIgnoreCase(b.getLastModified()));
                    break;
                case "Loại file":
                    sortedList.sort((a, b) -> a.getFileType().compareToIgnoreCase(b.getFileType()));
                    break;
            }
            tableFiles.setItems(sortedList);
        });
    }

    @Override
    public void changeViewMode(String viewMode) {
        Platform.runLater(() -> {
            setStatusMessage("Đã chuyển sang chế độ xem: " + viewMode);
            // Implement actual view mode changes here
        });
    }

    @Override
    public void showAlert(String title, String message, AlertType type) {
        Platform.runLater(() -> {
            Alert.AlertType fxType;
            switch (type) {
                case INFORMATION: fxType = Alert.AlertType.INFORMATION; break;
                case WARNING: fxType = Alert.AlertType.WARNING; break;
                case ERROR: fxType = Alert.AlertType.ERROR; break;
                case CONFIRMATION: fxType = Alert.AlertType.CONFIRMATION; break;
                default: fxType = Alert.AlertType.INFORMATION;
            }

            Alert alert = new Alert(fxType);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    @Override
    public void showFileProperties(FileItem fileItem) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Thuộc tính file");
            alert.setHeaderText("Thông tin chi tiết: " + fileItem.getFileName());
            alert.setContentText(
                    "Tên file: " + fileItem.getFileName() + "\n" +
                            "Kích thước: " + fileItem.getFileSize() + "\n" +
                            "Loại: " + fileItem.getFileType() + "\n" +
                            "Sửa đổi lần cuối: " + fileItem.getLastModified() + "\n" +
                            "Quyền truy cập: " + fileItem.getPermissions() + "\n" +
                            "Trạng thái đồng bộ: " + fileItem.getSyncStatus()
            );
            alert.showAndWait();
        });
    }

    @Override
    public boolean showConfirmDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    @Override
    public String showInputDialog(String title, String message, String defaultValue) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(message);

        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    @Override
    public void showUploadProgress(String fileName, double progress) {
        Platform.runLater(() -> {
            setSyncProgress(progress, "Đang tải lên: " + fileName, true);
        });
    }

    @Override
    public void hideUploadProgress() {
        Platform.runLater(() -> {
            setSyncProgress(0, "", false);
        });
    }

    @Override
    public void showDownloadProgress(String fileName, double progress) {
        Platform.runLater(() -> {
            setSyncProgress(progress, "Đang tải xuống: " + fileName, true);
        });
    }

    @Override
    public void hideDownloadProgress() {
        Platform.runLater(() -> {
            setSyncProgress(0, "", false);
        });
    }

    @Override
    public void enableUI(boolean enabled) {
        Platform.runLater(() -> {
            // Enable/disable all interactive components
            txtSearch.setDisable(!enabled);
            cmbViewMode.setDisable(!enabled);
            cmbSortBy.setDisable(!enabled);
            treeDirectory.setDisable(!enabled);
            tableFiles.setDisable(!enabled);
        });
    }

    @Override
    public void setButtonsEnabled(boolean enabled) {
        Platform.runLater(() -> {
            // This would need button references to be passed or managed differently
            // For now, we'll implement this when we refactor the controller
        });
    }

    @Override
    public String getSearchText() {
        return txtSearch.getText();
    }

    @Override
    public void clearSearchText() {
        Platform.runLater(() -> txtSearch.clear());
    }

    @Override
    public String getSelectedViewMode() {
        return cmbViewMode.getValue();
    }

    @Override
    public void setSelectedViewMode(String viewMode) {
        Platform.runLater(() -> cmbViewMode.setValue(viewMode));
    }

    @Override
    public String getSelectedSortBy() {
        return cmbSortBy.getValue();
    }

    @Override
    public void setSelectedSortBy(String sortBy) {
        Platform.runLater(() -> cmbSortBy.setValue(sortBy));
    }

    // Event Handler Setters
    @Override
    public void setOnLogout(Runnable handler) {
        this.onLogout = handler;
    }

    @Override
    public void setOnRefresh(Runnable handler) {
        this.onRefresh = handler;
    }

    @Override
    public void setOnUpload(Runnable handler) {
        this.onUpload = handler;
    }

    @Override
    public void setOnCreateFolder(Runnable handler) {
        this.onCreateFolder = handler;
    }

    @Override
    public void setOnPermissions(Runnable handler) {
        this.onPermissions = handler;
    }

    @Override
    public void setOnSettings(Runnable handler) {
        this.onSettings = handler;
    }

    @Override
    public void setOnSearch(Runnable handler) {
        this.onSearch = handler;
    }

    @Override
    public void setOnDirectorySelected(DirectorySelectionHandler handler) {
        this.onDirectorySelected = handler;
    }

    @Override
    public void setOnFileSelected(FileSelectionHandler handler) {
        this.onFileSelected = handler;
    }

    @Override
    public void setOnFileDoubleClick(FileActionHandler handler) {
        this.onFileDoubleClick = handler;
    }

    // Private helper methods
    private void updateSelectionInfo() {
        int selectedCount = tableFiles.getSelectionModel().getSelectedItems().size();
        if (selectedCount == 0) {
            setSelectedItemsInfo("Không có mục nào được chọn");
        } else {
            setSelectedItemsInfo(selectedCount + " mục được chọn");
        }
    }

    /**
     * Hiển thị thông báo Toast tạm thời (tự động biến mất sau vài giây)
     * @param message Nội dung thông báo
     * @param type Loại thông báo: "info", "success", "error", "warning"
     * @param durationSeconds Thời gian hiển thị (giây)
     */
    private void showToast(String message, String type, int durationSeconds) {
        Platform.runLater(() -> {
            // Tạo Label cho toast
            Label toast = new Label(message);
            toast.setStyle(
                "-fx-background-color: " + getToastBackgroundColor(type) + ";" +
                "-fx-text-fill: white;" +
                "-fx-padding: 10px 20px;" +
                "-fx-background-radius: 5px;" +
                "-fx-font-size: 13px;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);"
            );
            
            // Thêm icon theo loại
            String icon = getToastIcon(type);
            toast.setText(icon + " " + message);
            
            // Tạo Stage mới cho toast (popup)
            Stage toastStage = new Stage();
            toastStage.initOwner(tableFiles.getScene().getWindow());
            toastStage.setResizable(false);
            toastStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
            
            // Tạo scene với background trong suốt
            javafx.scene.Scene scene = new javafx.scene.Scene(toast);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            toastStage.setScene(scene);
            
            // Đặt vị trí toast ở giữa dưới màn hình
            Stage mainStage = (Stage) tableFiles.getScene().getWindow();
            toastStage.setX(mainStage.getX() + mainStage.getWidth() / 2 - 150);
            toastStage.setY(mainStage.getY() + mainStage.getHeight() - 100);
            
            toastStage.show();
            
            // Tự động đóng sau X giây
            javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                javafx.util.Duration.seconds(durationSeconds)
            );
            delay.setOnFinished(e -> toastStage.close());
            delay.play();
        });
    }
    
    /**
     * Lấy màu nền cho toast theo loại
     */
    private String getToastBackgroundColor(String type) {
        switch (type.toLowerCase()) {
            case "success": return "#10b981"; // Green
            case "error": return "#ef4444"; // Red
            case "warning": return "#f59e0b"; // Orange
            case "info":
            default: return "#3b82f6"; // Blue
        }
    }
    
    /**
     * Lấy icon cho toast theo loại
     */
    private String getToastIcon(String type) {
        switch (type.toLowerCase()) {
            case "success": return "✅";
            case "error": return "❌";
            case "warning": return "⚠️";
            case "info":
            default: return "ℹ️";
        }
    }

    // Hàm helper để mở hộp thoại lưu file (có thể gọi từ Controller)
    public File showSaveFileChooser(String initialFileName) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Lưu File Tải Về");
        fileChooser.setInitialFileName(initialFileName); // Gợi ý tên file

        // Có thể thêm ExtensionFilter nếu biết loại file
        // fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));

        // Lấy cửa sổ hiện tại để hiển thị dialog đúng vị trí
        Stage stage = (Stage) tableFiles.getScene().getWindow();
        return fileChooser.showSaveDialog(stage);
    }
}