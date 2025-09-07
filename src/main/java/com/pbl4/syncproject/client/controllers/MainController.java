package com.pbl4.syncproject.client.controllers;

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
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.ResourceBundle;

public class MainController implements Initializable {

    // UI Components
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

    @FXML private ContextMenu fileContextMenu;

    // Data
    private ObservableList<FileItem> fileItems = FXCollections.observableArrayList();
    private String currentUser = "admin";
    private String currentDirectory = "/shared";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupUI();
        setupTableView();
        setupDirectoryTree();
        loadInitialData();
    }

    private void setupUI() {
        lblUserInfo.setText("User: " + currentUser);
        lblStatusMessage.setText("Đã kết nối thành công");

        // Setup ComboBox items and default values
        cmbViewMode.getItems().addAll("Chi tiết", "Biểu tượng", "Danh sách");
        cmbViewMode.setValue("Chi tiết");

        cmbSortBy.getItems().addAll("Tên", "Kích thước", "Ngày sửa đổi", "Loại file");
        cmbSortBy.setValue("Tên");

        // Setup search functionality
        txtSearch.textProperty().addListener((obs, oldText, newText) -> {
            filterFiles(newText);
        });

        // Setup view mode and sorting
        cmbViewMode.valueProperty().addListener((obs, oldVal, newVal) -> {
            changeViewMode(newVal);
        });

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
                downloadBtn.setOnAction(e -> handleDownload());
                editBtn.setOnAction(e -> handleEdit());
                deleteBtn.setOnAction(e -> handleDelete());

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
                    var hbox = new javafx.scene.layout.HBox(3);
                    hbox.getChildren().addAll(downloadBtn, editBtn, deleteBtn);
                    setGraphic(hbox);
                }
            }
        });

        tableFiles.setItems(fileItems);

        // Selection listener
        tableFiles.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            updateSelectionInfo();
        });

        // Context menu
        tableFiles.setContextMenu(fileContextMenu);
    }

    private void setupDirectoryTree() {
        TreeItem<String> rootItem = new TreeItem<>("📁 Thư mục đồng bộ");
        rootItem.setExpanded(true);

        TreeItem<String> sharedFolder = new TreeItem<>("📁 shared");
        TreeItem<String> documentsFolder = new TreeItem<>("📁 documents");
        TreeItem<String> imagesFolder = new TreeItem<>("📁 images");
        TreeItem<String> videosFolder = new TreeItem<>("📁 videos");

        rootItem.getChildren().add(sharedFolder);
        rootItem.getChildren().add(documentsFolder);
        rootItem.getChildren().add(imagesFolder);
        rootItem.getChildren().add(videosFolder);

        treeDirectory.setRoot(rootItem);
        treeDirectory.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                currentDirectory = newVal.getValue();
                loadDirectoryFiles(currentDirectory);
            }
        });
    }

    private void loadInitialData() {
        // Load sample data
        fileItems.addAll(
                new FileItem("📄 document1.docx", "2.5 MB", "Document",
                        LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                        "Đọc/Ghi", "✅ Đã đồng bộ"),
                new FileItem("📊 spreadsheet.xlsx", "1.8 MB", "Spreadsheet",
                        LocalDateTime.now().minusDays(2).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                        "Chỉ đọc", "🔄 Đang đồng bộ"),
                new FileItem("🖼️ image.png", "847 KB", "Image",
                        LocalDateTime.now().minusHours(3).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                        "Đọc/Ghi", "✅ Đã đồng bộ"),
                new FileItem("🎥 video.mp4", "15.2 MB", "Video",
                        LocalDateTime.now().minusHours(5).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                        "Chỉ đọc", "❌ Lỗi đồng bộ")
        );

        updateFileCount();
        updateSyncStatus();
    }

    // Event handlers
    @FXML
    private void handleLogout() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Xác nhận đăng xuất");
        alert.setHeaderText("Bạn có chắc chắn muốn đăng xuất?");
        alert.setContentText("Tất cả các thao tác đồng bộ sẽ bị dừng.");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Close application or return to login
            Platform.exit();
        }
    }

    @FXML
    private void handleRefresh() {
        lblStatusMessage.setText("Đang làm mới...");

        Task<Void> refreshTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                Thread.sleep(1000); // Simulate network operation
                return null;
            }
        };

        refreshTask.setOnSucceeded(e -> {
            lblStatusMessage.setText("Làm mới thành công");
            loadDirectoryFiles(currentDirectory);
        });

        new Thread(refreshTask).start();
    }

    @FXML
    private void handleUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để tải lên");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Tất cả file", "*.*"),
                new FileChooser.ExtensionFilter("Tài liệu", "*.doc", "*.docx", "*.pdf", "*.txt"),
                new FileChooser.ExtensionFilter("Hình ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif"),
                new FileChooser.ExtensionFilter("Video", "*.mp4", "*.avi", "*.mkv")
        );

        File selectedFile = fileChooser.showOpenDialog(btnUpload.getScene().getWindow());
        if (selectedFile != null) {
            uploadFile(selectedFile);
        }
    }

    @FXML
    private void handleCreateFolder() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Tạo thư mục mới");
        dialog.setHeaderText("Tạo thư mục mới trong " + currentDirectory);
        dialog.setContentText("Tên thư mục:");

        Optional<String> result = dialog.showAndWait();
        if (result.isPresent() && !result.get().trim().isEmpty()) {
            createFolder(result.get().trim());
        }
    }

    @FXML
    private void handlePermissions() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/user-permission.fxml"));
            Parent root = loader.load();

            Stage permissionStage = new Stage();
            permissionStage.setTitle("Quản lý quyền người dùng");
            permissionStage.setScene(new Scene(root, 450, 600));
            permissionStage.initModality(Modality.APPLICATION_MODAL);
            permissionStage.setResizable(false);
            permissionStage.centerOnScreen();
            permissionStage.show();

            lblStatusMessage.setText("Đã mở cửa sổ quản lý quyền");
        } catch (Exception e) {
            showAlert("Lỗi", "Không thể mở cửa sổ quản lý quyền: " + e.getMessage(), Alert.AlertType.ERROR);
            e.printStackTrace();
        }
    }

    @FXML
    private void handleSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/settings.fxml"));
            Parent root = loader.load();

            Stage settingsStage = new Stage();
            settingsStage.setTitle("Cài đặt ứng dụng");
            settingsStage.setScene(new Scene(root, 600, 500));
            settingsStage.initModality(Modality.APPLICATION_MODAL);
            settingsStage.setResizable(false);
            settingsStage.centerOnScreen();
            settingsStage.show();

            lblStatusMessage.setText("Đã mở cửa sổ cài đặt");
        } catch (Exception e) {
            showAlert("Lỗi", "Không thể mở cửa sổ cài đặt: " + e.getMessage(), Alert.AlertType.ERROR);
            e.printStackTrace();
        }
    }

    @FXML
    private void handleSearch() {
        String searchText = txtSearch.getText().trim();
        if (!searchText.isEmpty()) {
            filterFiles(searchText);
        }
    }

    @FXML
    private void handleDownload() {
        FileItem selectedItem = tableFiles.getSelectionModel().getSelectedItem();
        if (selectedItem != null) {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle("Chọn thư mục lưu file");
            File selectedDirectory = directoryChooser.showDialog(btnUpload.getScene().getWindow());

            if (selectedDirectory != null) {
                downloadFile(selectedItem, selectedDirectory);
            }
        }
    }

    @FXML
    private void handleRename() {
        FileItem selectedItem = tableFiles.getSelectionModel().getSelectedItem();
        if (selectedItem != null) {
            TextInputDialog dialog = new TextInputDialog(selectedItem.getFileName());
            dialog.setTitle("Đổi tên file");
            dialog.setHeaderText("Đổi tên file: " + selectedItem.getFileName());
            dialog.setContentText("Tên mới:");

            Optional<String> result = dialog.showAndWait();
            if (result.isPresent() && !result.get().trim().isEmpty()) {
                renameFile(selectedItem, result.get().trim());
            }
        }
    }

    @FXML
    private void handleDelete() {
        FileItem selectedItem = tableFiles.getSelectionModel().getSelectedItem();
        if (selectedItem != null) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Xác nhận xóa");
            alert.setHeaderText("Xóa file: " + selectedItem.getFileName());
            alert.setContentText("Bạn có chắc chắn muốn xóa file này?\nThao tác này không thể hoàn tác.");

            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                deleteFile(selectedItem);
            }
        }
    }

    @FXML
    private void handleCopy() {
        // Implement copy functionality
        lblStatusMessage.setText("Đã sao chép file");
    }

    @FXML
    private void handleCut() {
        // Implement cut functionality
        lblStatusMessage.setText("Đã cắt file");
    }

    @FXML
    private void handleProperties() {
        FileItem selectedItem = tableFiles.getSelectionModel().getSelectedItem();
        if (selectedItem != null) {
            showFileProperties(selectedItem);
        }
    }

    private void handleEdit() {
        FileItem selectedItem = tableFiles.getSelectionModel().getSelectedItem();
        if (selectedItem != null) {
            if (selectedItem.getPermissions().contains("Ghi")) {
                lblStatusMessage.setText("Đang mở file để chỉnh sửa...");
                // Implement edit functionality
            } else {
                showAlert("Không có quyền", "Bạn không có quyền chỉnh sửa file này.", Alert.AlertType.WARNING);
            }
        }
    }

    // Utility methods
    private void loadDirectoryFiles(String directory) {
        lblStatusMessage.setText("Đang tải danh sách file...");
        // Simulate loading files from server
        // In real implementation, this would make network call
        updateFileCount();
    }

    private void uploadFile(File file) {
        lblStatusMessage.setText("Đang tải lên: " + file.getName());
        progressSync.setVisible(true);
        lblSyncProgress.setVisible(true);

        Task<Void> uploadTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                for (int i = 0; i <= 100; i += 10) {
                    Thread.sleep(200);
                    final int progress = i;
                    Platform.runLater(() -> {
                        progressSync.setProgress(progress / 100.0);
                        lblSyncProgress.setText("Đang tải lên... " + progress + "%");
                    });
                }
                return null;
            }
        };

        uploadTask.setOnSucceeded(e -> {
            progressSync.setVisible(false);
            lblSyncProgress.setVisible(false);
            lblStatusMessage.setText("Tải lên thành công: " + file.getName());

            // Add to file list
            String fileIcon = getFileIcon(file.getName());
            fileItems.add(new FileItem(
                    fileIcon + " " + file.getName(),
                    formatFileSize(file.length()),
                    getFileType(file.getName()),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    "Đọc/Ghi",
                    "🔄 Đang đồng bộ"
            ));
            updateFileCount();
        });

        new Thread(uploadTask).start();
    }

    private void createFolder(String folderName) {
        lblStatusMessage.setText("Đang tạo thư mục: " + folderName);
        // Implement folder creation
        fileItems.add(new FileItem(
                "📁 " + folderName,
                "-",
                "Folder",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                "Đọc/Ghi",
                "✅ Đã đồng bộ"
        ));
        updateFileCount();
        lblStatusMessage.setText("Đã tạo thư mục: " + folderName);
    }

    private void downloadFile(FileItem fileItem, File destination) {
        lblStatusMessage.setText("Đang tải xuống: " + fileItem.getFileName());
        // Implement download functionality
        lblStatusMessage.setText("Tải xuống thành công: " + fileItem.getFileName());
    }

    private void renameFile(FileItem fileItem, String newName) {
        lblStatusMessage.setText("Đang đổi tên file...");
        String icon = fileItem.getFileName().split(" ")[0];
        fileItem.setFileName(icon + " " + newName);
        tableFiles.refresh();
        lblStatusMessage.setText("Đã đổi tên thành: " + newName);
    }

    private void deleteFile(FileItem fileItem) {
        lblStatusMessage.setText("Đang xóa file...");
        fileItems.remove(fileItem);
        updateFileCount();
        lblStatusMessage.setText("Đã xóa file: " + fileItem.getFileName());
    }

    private void filterFiles(String searchText) {
        if (searchText.isEmpty()) {
            tableFiles.setItems(fileItems);
        } else {
            ObservableList<FileItem> filteredList = FXCollections.observableArrayList();
            for (FileItem item : fileItems) {
                if (item.getFileName().toLowerCase().contains(searchText.toLowerCase())) {
                    filteredList.add(item);
                }
            }
            tableFiles.setItems(filteredList);
        }
        updateFileCount();
    }

    private void sortFiles(String sortBy) {
        ObservableList<FileItem> sortedList = FXCollections.observableArrayList(fileItems);
        switch (sortBy) {
            case "Tên":
                sortedList.sort((a, b) -> a.getFileName().compareToIgnoreCase(b.getFileName()));
                break;
            case "Kích thước":
                sortedList.sort((a, b) -> a.getFileSize().compareToIgnoreCase(b.getFileSize()));
                break;
            case "Ngày sửa đổi":
                sortedList.sort((a, b) -> b.getLastModified().compareToIgnoreCase(a.getLastModified()));
                break;
            case "Loại file":
                sortedList.sort((a, b) -> a.getFileType().compareToIgnoreCase(b.getFileType()));
                break;
        }
        tableFiles.setItems(sortedList);
    }

    private void changeViewMode(String viewMode) {
        // Implement view mode changes
        lblStatusMessage.setText("Đã chuyển sang chế độ xem: " + viewMode);
    }

    private void updateFileCount() {
        int count = tableFiles.getItems().size();
        lblFileCount.setText(count + " file" + (count != 1 ? "s" : ""));
    }

    private void updateSelectionInfo() {
        int selectedCount = tableFiles.getSelectionModel().getSelectedItems().size();
        if (selectedCount == 0) {
            lblSelectedItems.setText("Không có mục nào được chọn");
        } else {
            lblSelectedItems.setText(selectedCount + " mục được chọn");
        }
    }

    private void updateSyncStatus() {
        long syncedFiles = fileItems.stream()
                .mapToInt(item -> item.getSyncStatus().contains("Đã đồng bộ") ? 1 : 0)
                .sum();

        if (syncedFiles == fileItems.size()) {
            lblSyncStatus.setText("✅ Tất cả file đã đồng bộ");
            lblSyncStatus.setStyle("-fx-text-fill: #059669;");
        } else {
            lblSyncStatus.setText("🔄 Đang đồng bộ " + (fileItems.size() - syncedFiles) + " file");
            lblSyncStatus.setStyle("-fx-text-fill: #f59e0b;");
        }
    }

    private void showFileProperties(FileItem fileItem) {
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
    }

    private void showAlert(String title, String message, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private String getFileIcon(String fileName) {
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

    private String getFileType(String fileName) {
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

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // Inner class for file items
    public static class FileItem {
        private String fileName;
        private String fileSize;
        private String fileType;
        private String lastModified;
        private String permissions;
        private String syncStatus;

        public FileItem(String fileName, String fileSize, String fileType,
                        String lastModified, String permissions, String syncStatus) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.fileType = fileType;
            this.lastModified = lastModified;
            this.permissions = permissions;
            this.syncStatus = syncStatus;
        }

        // Getters and setters
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public String getFileSize() { return fileSize; }
        public void setFileSize(String fileSize) { this.fileSize = fileSize; }

        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }

        public String getLastModified() { return lastModified; }
        public void setLastModified(String lastModified) { this.lastModified = lastModified; }

        public String getPermissions() { return permissions; }
        public void setPermissions(String permissions) { this.permissions = permissions; }

        public String getSyncStatus() { return syncStatus; }
        public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }
    }
}
