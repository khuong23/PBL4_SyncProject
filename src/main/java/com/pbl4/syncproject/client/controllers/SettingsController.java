package com.pbl4.syncproject.client.controllers;

import com.pbl4.syncproject.client.services.SyncAgent;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

// --- THÊM IMPORT NÀY ---
import com.pbl4.syncproject.client.services.SettingsService;
import javafx.stage.StageStyle;
// -----------------------

public class SettingsController implements Initializable {

    // Network Settings
    @FXML private TextField txtServerIP;
    @FXML private TextField txtServerPort;
    @FXML private Spinner<Integer> spnTimeout;
    @FXML private CheckBox chkAutoReconnect;
    @FXML private CheckBox chkKeepAlive;
    @FXML private Spinner<Integer> spnRetryCount;
    @FXML private Spinner<Integer> spnRetryInterval;

    // Sync Settings
    @FXML private CheckBox chkAutoSync;
    @FXML private Spinner<Integer> spnSyncInterval;
    @FXML private CheckBox chkSyncOnStart;
    @FXML private CheckBox chkSyncSubfolders;
    @FXML private CheckBox chkSyncDocuments;
    @FXML private CheckBox chkSyncImages;
    @FXML private CheckBox chkSyncVideos;
    @FXML private CheckBox chkSyncArchives;
    @FXML private Spinner<Integer> spnMaxFileSize;
    @FXML private RadioButton rbNewerWins;
    @FXML private RadioButton rbLargerWins;
    @FXML private RadioButton rbAskUser;
    @FXML private RadioButton rbKeepBoth;
    @FXML private ToggleGroup conflictResolution;

    // Security Settings
    @FXML private CheckBox chkEncryptTransfer;
    @FXML private CheckBox chkEncryptStorage;
    @FXML private ComboBox<String> cmbEncryption;
    @FXML private CheckBox chkRememberPassword;
    @FXML private CheckBox chkAutoLogin;
    @FXML private Spinner<Integer> spnSessionTimeout;
    @FXML private CheckBox chkLogAccess;
    @FXML private CheckBox chkLogChanges;
    @FXML private CheckBox chkNotifyChanges;

    // General Settings
    @FXML private ComboBox<String> cmbLanguage;
    @FXML private ComboBox<String> cmbTheme;
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
        // Configure spinners with proper value factories
        spnTimeout.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1000, 60000, 30000, 1000));
        spnRetryCount.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 3));
        spnRetryInterval.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(5, 300, 30, 5));
        spnSyncInterval.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, 5));
        spnMaxFileSize.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1000, 100, 10));
        spnSessionTimeout.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(5, 480, 60, 5));

        // Make spinners editable
        spnTimeout.setEditable(true);
        spnRetryCount.setEditable(true);
        spnRetryInterval.setEditable(true);
        spnSyncInterval.setEditable(true);
        spnMaxFileSize.setEditable(true);
        spnSessionTimeout.setEditable(true);
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
        // Add validation for IP address
        txtServerIP.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!isValidIP(newVal)) {
                txtServerIP.setStyle("-fx-border-color: red;");
            } else {
                txtServerIP.setStyle("");
            }
        });

        // Add validation for port number
        txtServerPort.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                int port = Integer.parseInt(newVal);
                if (port < 1 || port > 65535) {
                    txtServerPort.setStyle("-fx-border-color: red;");
                } else {
                    txtServerPort.setStyle("");
                }
            } catch (NumberFormatException e) {
                txtServerPort.setStyle("-fx-border-color: red;");
            }
        });
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
        // Network Settings
        txtServerIP.setText("127.0.0.1");
        txtServerPort.setText("5000");
        spnTimeout.getValueFactory().setValue(30000);
        chkAutoReconnect.setSelected(true);
        chkKeepAlive.setSelected(true);
        spnRetryCount.getValueFactory().setValue(3);
        spnRetryInterval.getValueFactory().setValue(30);

        // Sync Settings
        chkAutoSync.setSelected(true);
        spnSyncInterval.getValueFactory().setValue(5);
        chkSyncOnStart.setSelected(true);
        chkSyncSubfolders.setSelected(true);
        chkSyncDocuments.setSelected(true);
        chkSyncImages.setSelected(true);
        chkSyncVideos.setSelected(false);
        chkSyncArchives.setSelected(true);
        spnMaxFileSize.getValueFactory().setValue(100);
        rbNewerWins.setSelected(true);

        // Security Settings
        chkEncryptTransfer.setSelected(true);
        chkEncryptStorage.setSelected(false);

        // Setup ComboBox items
        cmbEncryption.getItems().addAll("AES-128", "AES-256", "RSA-2048");
        cmbEncryption.setValue("AES-256");

        chkRememberPassword.setSelected(false);
        chkAutoLogin.setSelected(false);
        spnSessionTimeout.getValueFactory().setValue(60);
        chkLogAccess.setSelected(true);
        chkLogChanges.setSelected(true);
        chkNotifyChanges.setSelected(true);

        // General Settings
        cmbLanguage.getItems().addAll("Tiếng Việt", "English", "中文");
        cmbLanguage.setValue("Tiếng Việt");

        cmbTheme.getItems().addAll("Sáng", "Tối", "Tự động");
        cmbTheme.setValue("Sáng");
        chkShowNotifications.setSelected(true);
        chkSoundNotifications.setSelected(false);
        chkMinimizeToTray.setSelected(true);
        chkStartWithWindows.setSelected(false);
        txtSyncFolder.setText("C:\\SyncData");
        txtTempFolder.setText("C:\\Temp\\SyncApp");
        chkCleanTempOnExit.setSelected(true);
    }

    private boolean validateSettings() {
        // Validate IP address
        if (!isValidIP(txtServerIP.getText())) {
            showErrorMessage("Địa chỉ IP không hợp lệ!");
            return false;
        }

        // Validate port
        try {
            int port = Integer.parseInt(txtServerPort.getText());
            if (port < 1 || port > 65535) {
                showErrorMessage("Cổng phải trong khoảng 1-65535!");
                return false;
            }
        } catch (NumberFormatException e) {
            showErrorMessage("Cổng phải là số nguyên!");
            return false;
        }

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

    private boolean isValidIP(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }

        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
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
