package com.pbl4.syncproject.client.views;

import com.pbl4.syncproject.client.models.FileItem;
import com.pbl4.syncproject.client.services.FileService;
import com.pbl4.syncproject.common.model.Folders;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;

import java.util.List;
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
    private Runnable onLogout;
    private Runnable onRefresh;
    private Runnable onUpload;
    private Runnable onCreateFolder;
    private Runnable onPermissions;
    private Runnable onSettings;
    private Runnable onSearch;
    private DirectorySelectionHandler onDirectorySelected;
    private FileSelectionHandler onFileSelected;
    private FileActionHandler onFileDoubleClick;

    // Data
    private ObservableList<FileItem> originalFileItems = FXCollections.observableArrayList();
    private String currentDirectory = "/shared";

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
        this.fileService = fileService;

        initializeView();
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
        rootFolder.setFolderId(0);
        rootFolder.setFolderName("Thư mục đồng bộ");

        // Create root TreeItem
        TreeItem<Folders> rootItem = new TreeItem<>(rootFolder);
        rootItem.setExpanded(true);
        treeDirectory.setRoot(rootItem);
        treeDirectory.setShowRoot(true);

        // Load from DB asynchronously
        loadFoldersFromDatabase(rootItem);

        // Selection listener - now receives Folders object
        treeDirectory.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                Folders selectedFolder = newVal.getValue();
                if (onDirectorySelected != null) {
                    onDirectorySelected.onDirectorySelected(selectedFolder);
                }
            }
        });

        // Custom cell factory to display folder icon + name
        treeDirectory.setCellFactory(tv -> new TreeCell<Folders>() {
            @Override
            protected void updateItem(Folders item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText("📁 " + item.getFolderName());
                }
            }
        });
    }

    /**
     * Load folder tree from server via FileService
     */
    private void loadFoldersFromDatabase(TreeItem<Folders> rootItem) {
        new Thread(() -> {
            try {
                if (fileService == null) {
                    throw new IllegalStateException("FileService chưa được khởi tạo");
                }
                List<Folders> folders = fileService.fetchAndParseFolderTree();
                Platform.runLater(() -> {
                    rootItem.getChildren().clear();
                    for (Folders folder : folders) {
                        TreeItem<Folders> item = new TreeItem<>(folder);
                        rootItem.getChildren().add(item);
                    }
                    if (!folders.isEmpty()) {
                        rootItem.setExpanded(true);
                    }
                });
            } catch (Exception ex) {
                // Fallback to default static folders
                System.err.println("Failed to load folders from database: " + ex.getMessage());
                Platform.runLater(() -> setupDefaultFolders(rootItem));
            }
        }).start();
    }

    /**
     * Fallback default folders when server not available
     */
    private void setupDefaultFolders(TreeItem<Folders> rootItem) {
        rootItem.getChildren().clear();

        Folders shared = new Folders(1, "shared", null, null, null);
        Folders documents = new Folders(2, "documents", null, null, null);
        Folders images = new Folders(3, "images", null, null, null);
        Folders videos = new Folders(4, "videos", null, null, null);

        rootItem.getChildren().add(new TreeItem<>(shared));
        rootItem.getChildren().add(new TreeItem<>(documents));
        rootItem.getChildren().add(new TreeItem<>(images));
        rootItem.getChildren().add(new TreeItem<>(videos));
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
}