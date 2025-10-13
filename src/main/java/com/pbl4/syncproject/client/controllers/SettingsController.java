package com.pbl4.syncproject.client.controllers;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

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

    @Override
    public void initialize(URL location, ResourceBundle resources) {
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
        // In real implementation, load from configuration file or registry
        // For now, we'll use default values which are already set in FXML

        // Example of loading settings from a properties file:
        // Properties props = new Properties();
        // try (InputStream input = new FileInputStream("config.properties")) {
        //     props.load(input);
        //     txtServerIP.setText(props.getProperty("server.ip", "127.0.0.1"));
        //     txtServerPort.setText(props.getProperty("server.port", "5000"));
        //     // ... load other settings
        // } catch (IOException e) {
        //     // Use default values
        // }
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
        if (validateSettings()) {
            saveSettings();
            showSuccessMessage("Đã áp dụng cài đặt thành công!");
        }
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
        // In real implementation, save to configuration file or registry
        // For now, we'll just print the settings

        System.out.println("Saving settings:");
        System.out.println("Server IP: " + txtServerIP.getText());
        System.out.println("Server Port: " + txtServerPort.getText());
        System.out.println("Auto Sync: " + chkAutoSync.isSelected());
        System.out.println("Sync Interval: " + spnSyncInterval.getValue());
        // ... save other settings

        // Example of saving to properties file:
        // Properties props = new Properties();
        // props.setProperty("server.ip", txtServerIP.getText());
        // props.setProperty("server.port", txtServerPort.getText());
        // props.setProperty("auto.sync", String.valueOf(chkAutoSync.isSelected()));
        // // ... set other properties
        //
        // try (OutputStream output = new FileOutputStream("config.properties")) {
        //     props.store(output, "Application Settings");
        // } catch (IOException e) {
        //     showErrorMessage("Không thể lưu cài đặt: " + e.getMessage());
        // }
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
