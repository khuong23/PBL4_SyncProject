package com.pbl4.syncproject.client.controllers;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

public class UserPermissionController implements Initializable {

    @FXML private ComboBox<String> cmbUsers;
    @FXML private TextField txtFilePath;
    @FXML private Button btnBrowse;

    @FXML private CheckBox chkRead;
    @FXML private CheckBox chkWrite;
    @FXML private CheckBox chkEdit;
    @FXML private CheckBox chkDelete;
    @FXML private CheckBox chkExecute;

    @FXML private TextArea txtCurrentPermissions;
    @FXML private Label lblStatus;

    @FXML private Button btnApply;
    @FXML private Button btnReset;
    @FXML private Button btnClose;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupUI();
        setupEventHandlers();
    }

    private void setupUI() {
        // Populate users ComboBox
        cmbUsers.getItems().addAll("admin", "user1", "user2", "guest");

        // Select first user by default
        if (!cmbUsers.getItems().isEmpty()) {
            cmbUsers.getSelectionModel().selectFirst();
        }

        updateCurrentPermissions();
    }

    private void setupEventHandlers() {
        // User selection change
        cmbUsers.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateCurrentPermissions();
        });

        // File path change
        txtFilePath.textProperty().addListener((obs, oldVal, newVal) -> {
            updateCurrentPermissions();
        });

        // Permission checkboxes
        chkRead.selectedProperty().addListener((obs, oldVal, newVal) -> updatePermissionDisplay());
        chkWrite.selectedProperty().addListener((obs, oldVal, newVal) -> updatePermissionDisplay());
        chkEdit.selectedProperty().addListener((obs, oldVal, newVal) -> updatePermissionDisplay());
        chkDelete.selectedProperty().addListener((obs, oldVal, newVal) -> updatePermissionDisplay());
        chkExecute.selectedProperty().addListener((obs, oldVal, newVal) -> updatePermissionDisplay());
    }

    @FXML
    private void handleBrowse() {
        // Show file/directory chooser
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Chọn loại");
        alert.setHeaderText("Bạn muốn chọn file hay thư mục?");
        alert.setContentText("Chọn loại để tiếp tục:");

        ButtonType btnFile = new ButtonType("File");
        ButtonType btnDirectory = new ButtonType("Thư mục");
        ButtonType btnCancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(btnFile, btnDirectory, btnCancel);

        alert.showAndWait().ifPresent(response -> {
            if (response == btnFile) {
                chooseFile();
            } else if (response == btnDirectory) {
                chooseDirectory();
            }
        });
    }

    private void chooseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Tất cả file", "*.*"),
                new FileChooser.ExtensionFilter("Tài liệu", "*.doc", "*.docx", "*.pdf", "*.txt"),
                new FileChooser.ExtensionFilter("Hình ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif"),
                new FileChooser.ExtensionFilter("Video", "*.mp4", "*.avi", "*.mkv")
        );

        File selectedFile = fileChooser.showOpenDialog(btnBrowse.getScene().getWindow());
        if (selectedFile != null) {
            txtFilePath.setText(selectedFile.getAbsolutePath());
            showStatus("Đã chọn file: " + selectedFile.getName(), false);
        }
    }

    private void chooseDirectory() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Chọn thư mục");

        File selectedDirectory = directoryChooser.showDialog(btnBrowse.getScene().getWindow());
        if (selectedDirectory != null) {
            txtFilePath.setText(selectedDirectory.getAbsolutePath());
            showStatus("Đã chọn thư mục: " + selectedDirectory.getName(), false);
        }
    }

    @FXML
    private void handleApply() {
        String user = cmbUsers.getSelectionModel().getSelectedItem();
        String filePath = txtFilePath.getText().trim();

        if (user == null || user.isEmpty()) {
            showStatus("Vui lòng chọn người dùng!", true);
            return;
        }

        if (filePath.isEmpty()) {
            showStatus("Vui lòng chọn file hoặc thư mục!", true);
            return;
        }

        List<String> permissions = getSelectedPermissions();
        if (permissions.isEmpty()) {
            showStatus("Vui lòng chọn ít nhất một quyền!", true);
            return;
        }

        // Apply permissions (in real implementation, this would send to server)
        applyPermissions(user, filePath, permissions);
        showStatus("Đã áp dụng quyền thành công cho " + user, false);
        updateCurrentPermissions();
    }

    @FXML
    private void handleReset() {
        chkRead.setSelected(true);
        chkWrite.setSelected(false);
        chkEdit.setSelected(false);
        chkDelete.setSelected(false);
        chkExecute.setSelected(false);

        showStatus("Đã đặt lại quyền về mặc định", false);
        updatePermissionDisplay();
    }

    @FXML
    private void handleClose() {
        Stage stage = (Stage) btnClose.getScene().getWindow();
        stage.close();
    }

    private List<String> getSelectedPermissions() {
        List<String> permissions = new ArrayList<>();

        if (chkRead.isSelected()) permissions.add("READ");
        if (chkWrite.isSelected()) permissions.add("WRITE");
        if (chkEdit.isSelected()) permissions.add("EDIT");
        if (chkDelete.isSelected()) permissions.add("DELETE");
        if (chkExecute.isSelected()) permissions.add("EXECUTE");

        return permissions;
    }

    private void applyPermissions(String user, String filePath, List<String> permissions) {
        // In real implementation, this would send permission changes to server
        System.out.println("Applying permissions for user: " + user);
        System.out.println("File/Directory: " + filePath);
        System.out.println("Permissions: " + String.join(", ", permissions));
    }

    private void updateCurrentPermissions() {
        String user = cmbUsers.getSelectionModel().getSelectedItem();
        String filePath = txtFilePath.getText().trim();

        if (user == null || filePath.isEmpty()) {
            txtCurrentPermissions.setText("Chưa có thông tin quyền");
            return;
        }

        // In real implementation, this would query current permissions from server
        StringBuilder currentPerms = new StringBuilder();
        currentPerms.append("Người dùng: ").append(user).append("\n");
        currentPerms.append("File/Thư mục: ").append(filePath).append("\n");
        currentPerms.append("Quyền hiện tại:\n");

        // Sample permissions (would come from server)
        if (user.equals("admin")) {
            currentPerms.append("- Đọc: Có\n");
            currentPerms.append("- Ghi: Có\n");
            currentPerms.append("- Sửa đổi: Có\n");
            currentPerms.append("- Xóa: Có\n");
            currentPerms.append("- Thực thi: Có\n");
        } else if (user.equals("guest")) {
            currentPerms.append("- Đọc: Có\n");
            currentPerms.append("- Ghi: Không\n");
            currentPerms.append("- Sửa đổi: Không\n");
            currentPerms.append("- Xóa: Không\n");
            currentPerms.append("- Thực thi: Không\n");
        } else {
            currentPerms.append("- Đọc: Có\n");
            currentPerms.append("- Ghi: Có\n");
            currentPerms.append("- Sửa đổi: Không\n");
            currentPerms.append("- Xóa: Không\n");
            currentPerms.append("- Thực thi: Không\n");
        }

        txtCurrentPermissions.setText(currentPerms.toString());
    }

    private void updatePermissionDisplay() {
        List<String> selectedPerms = getSelectedPermissions();

        if (selectedPerms.isEmpty()) {
            showStatus("Chưa chọn quyền nào", true);
        } else {
            showStatus("Đã chọn: " + String.join(", ", selectedPerms), false);
        }
    }

    private void showStatus(String message, boolean isError) {
        lblStatus.setText(message);
        lblStatus.setVisible(true);

        if (isError) {
            lblStatus.setStyle("-fx-text-fill: #dc2626;");
        } else {
            lblStatus.setStyle("-fx-text-fill: #059669;");
        }

        // Hide status after 3 seconds
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                javafx.application.Platform.runLater(() -> lblStatus.setVisible(false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}
