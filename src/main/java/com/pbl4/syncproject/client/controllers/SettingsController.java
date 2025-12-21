package com.pbl4.syncproject.client.controllers;

import com.pbl4.syncproject.client.services.SyncAgent;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

// --- THÊM IMPORT NÀY ---
import com.pbl4.syncproject.client.services.SettingsService;
import javafx.stage.StageStyle;
// -----------------------

public class SettingsController implements Initializable {

    // General Settings
    @FXML private CheckBox chkShowNotifications;
    @FXML private CheckBox chkSoundNotifications;
    @FXML private CheckBox chkMinimizeToTray;
    @FXML private CheckBox chkStartWithWindows;
    @FXML private TextField txtSyncFolder;
    @FXML private TextField txtTempFolder;
    @FXML private CheckBox chkCleanTempOnExit;

    // Action Buttons
    @FXML private Button btnResetToDefault;
    @FXML private Button btnCancel;
    @FXML private Button btnApply;
    @FXML private Button btnOK;

    private SyncAgent syncAgent;
    public void setSyncAgent(SyncAgent agent) { this.syncAgent = agent; }
    // --- THÊM DÒNG NÀY ---
    private SettingsService settingsService;
    // -----------------------

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // --- THÊM DÒNG NÀY ---
        this.settingsService = SettingsService.getInstance();
        // -----------------------

        setupSpinners();
        loadSettings();
        setupValidation();
    }

    private void setupSpinners() {
        // No spinners in simplified settings
    }

    private void loadSettings() {
        // --- SỬA LẠI: Tải cài đặt THẬT từ SettingsService ---
        // Lấy đường dẫn mặc định nếu chưa có cài đặt
        String defaultSyncDir = System.getProperty("user.home") + File.separator + "SyncData";
        String defaultTempDir = System.getProperty("java.io.tmpdir") + File.separator + "SyncApp";

        // Tải từ file properties
        txtSyncFolder.setText(settingsService.getSetting(SettingsService.KEY_SYNC_DIRECTORY, defaultSyncDir));
        txtTempFolder.setText(settingsService.getSetting(SettingsService.KEY_TEMP_DIRECTORY, defaultTempDir));

        // ... (Các cài đặt khác như CheckBox, Spinner có thể thêm sau nếu cần)
        // ----------------------------------------------------
    }

    private void setupValidation() {
        // No validation needed for simplified settings
    }

    @FXML
    private void handleChooseSyncFolder() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Chọn thư mục đồng bộ");

        // Set initial directory
        String currentPath = txtSyncFolder.getText();
        if (!currentPath.isEmpty()) {
            File currentDir = new File(currentPath);
            if (currentDir.exists() && currentDir.isDirectory()) {
                directoryChooser.setInitialDirectory(currentDir);
            }
        }

        File selectedDirectory = directoryChooser.showDialog(txtSyncFolder.getScene().getWindow());
        if (selectedDirectory != null) {
            txtSyncFolder.setText(selectedDirectory.getAbsolutePath());
        }
    }

    @FXML
    private void handleChooseTempFolder() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Chọn thư mục tạm");

        // Set initial directory
        String currentPath = txtTempFolder.getText();
        if (!currentPath.isEmpty()) {
            File currentDir = new File(currentPath);
            if (currentDir.exists() && currentDir.isDirectory()) {
                directoryChooser.setInitialDirectory(currentDir);
            }
        }

        File selectedDirectory = directoryChooser.showDialog(txtTempFolder.getScene().getWindow());
        if (selectedDirectory != null) {
            txtTempFolder.setText(selectedDirectory.getAbsolutePath());
        }
    }

    @FXML
    private void handleResetToDefault() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Xác nhận");
        alert.setHeaderText("Đặt lại về mặc định");
        alert.setContentText("Bạn có chắc chắn muốn đặt lại tất cả cài đặt về giá trị mặc định?");

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                resetToDefaults();
            }
        });
    }

    @FXML
    private void handleCancel() {
        Stage stage = (Stage) btnCancel.getScene().getWindow();
        stage.close();
    }

    @FXML
    private void handleApply() {
        if (!validateSettings()) return;

        saveSettings();

        // Hỏi nhanh cho chắc (tuỳ bạn, có thể bỏ confirm)
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Làm mới dữ liệu");
        confirm.setHeaderText("Làm mới toàn bộ dữ liệu cục bộ");
        confirm.setContentText(
                "Thao tác sẽ:\n" +
                        "• Reset cache DB cục bộ\n" +
                        "• Tải lại toàn bộ dữ liệu mới nhất từ server (mirror)\n\n" +
                        "Bạn có muốn tiếp tục?");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                doFullRefreshNow();
            }
        });
    }
    // Chạy refresh ở background + hiển thị progress modal
    private void doFullRefreshNow() {
        if (syncAgent == null) {
            showErrorMessage("Chưa gắn SyncAgent cho SettingsController");
            return;
        }

        Alert wait = new Alert(Alert.AlertType.INFORMATION);
        wait.initStyle(StageStyle.UNDECORATED);
        wait.initOwner(btnApply.getScene().getWindow());
        wait.setTitle("Đang làm mới dữ liệu...");
        wait.setHeaderText("Đang reset DB cục bộ và tải mới từ server");
        wait.getDialogPane().setContent(new ProgressIndicator());
        wait.getDialogPane().getButtonTypes().clear();

        // Store reference to the window
        Stage waitStage = (Stage) wait.getDialogPane().getScene().getWindow();
        waitStage.setOnCloseRequest(e -> e.consume()); // Prevent manual closing
        wait.show();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                syncAgent.forceFullRefreshFromSettings();
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                if (waitStage.isShowing()) {
                    waitStage.setOnCloseRequest(null);
                    waitStage.close();
                }
                showSuccessMessage("Đã làm mới dữ liệu thành công!");
                // Close settings window
                ((Stage) btnApply.getScene().getWindow()).close();
            });
        });

        task.setOnFailed(e -> {
            Platform.runLater(() -> {
                if (waitStage.isShowing()) {
                    waitStage.setOnCloseRequest(null);
                    waitStage.close();
                }
                Throwable ex = task.getException();
                showErrorMessage("Làm mới thất bại: " + (ex != null ? ex.getMessage() : "Không rõ lỗi"));
            });
        });

        new Thread(task, "Force-Full-Refresh").start();
    }


    @FXML
    private void handleOK() {
        if (validateSettings()) {
            saveSettings();
            Stage stage = (Stage) btnOK.getScene().getWindow();
            stage.close();
        }
    }

    private void resetToDefaults() {
        // General Settings
        chkShowNotifications.setSelected(true);
        chkSoundNotifications.setSelected(false);
        chkMinimizeToTray.setSelected(true);
        chkStartWithWindows.setSelected(false);

        String defaultSyncDir = System.getProperty("user.home") + File.separator + "SyncData";
        String defaultTempDir = System.getProperty("java.io.tmpdir") + File.separator + "SyncApp";
        txtSyncFolder.setText(defaultSyncDir);
        txtTempFolder.setText(defaultTempDir);
        chkCleanTempOnExit.setSelected(true);
    }

    private boolean validateSettings() {
        // Validate folders
        String syncFolder = txtSyncFolder.getText().trim();
        if (syncFolder.isEmpty()) {
            showErrorMessage("Vui lòng chọn thư mục đồng bộ!");
            return false;
        }

        String tempFolder = txtTempFolder.getText().trim();
        if (tempFolder.isEmpty()) {
            showErrorMessage("Vui lòng chọn thư mục tạm!");
            return false;
        }

        return true;
    }

    private void saveSettings() {
        // --- SỬA LẠI: Lưu cài đặt THẬT vào SettingsService ---
        // 1. Lưu vào đối tượng SettingsService (trong bộ nhớ)
        settingsService.setSetting(SettingsService.KEY_SYNC_DIRECTORY, txtSyncFolder.getText());
        settingsService.setSetting(SettingsService.KEY_TEMP_DIRECTORY, txtTempFolder.getText());
        // ... (Lưu các cài đặt khác nếu cần)

        // 2. Yêu cầu SettingsService ghi ra file
        settingsService.saveSettings();

        System.out.println("✅ Đã lưu cài đặt:");
        System.out.println("  - Thư mục đồng bộ: " + txtSyncFolder.getText());
        System.out.println("  - Thư mục tạm: " + txtTempFolder.getText());
        // ----------------------------------------------------
    }

    private void showSuccessMessage(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Thành công");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showErrorMessage(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
