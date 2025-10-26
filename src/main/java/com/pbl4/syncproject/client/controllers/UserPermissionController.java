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
import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.services.NetworkService;
import com.pbl4.syncproject.client.services.FileService;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

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

    // Injected services
    private NetworkService networkService;
    private FileService fileService;
    private String currentUser;

    // Map username -> userId
    private final Map<String, Integer> userMap = new HashMap<>();
    private int selectedFolderId = -1;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupUI();
        setupEventHandlers();
    }

    private void setupUI() {
        // Load users from server (if networkService injected) otherwise fallback to sample
        if (networkService != null) {
            loadUsersFromServer();
        } else {
            cmbUsers.getItems().addAll("admin", "user1", "user2", "guest");
            if (!cmbUsers.getItems().isEmpty()) cmbUsers.getSelectionModel().selectFirst();
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
        // Mở dialog chọn thư mục từ server (sử dụng FileService nếu có)
        try {
            List<com.pbl4.syncproject.common.model.Folders> folders = null;
            if (fileService != null) {
                folders = fileService.fetchAndParseFolderTree();
            }
            if (folders == null || folders.isEmpty()) {
                showStatus("Không có thư mục từ server để chọn.", true);
                return;
            }

            List<String> names = new ArrayList<>();
            Map<String, Integer> nameToId = new HashMap<>();
            for (com.pbl4.syncproject.common.model.Folders f : folders) {
                names.add(f.getFolderName());
                nameToId.put(f.getFolderName(), f.getFolderId());
            }
            ChoiceDialog<String> dlg = new ChoiceDialog<>(names.get(0), names);
            dlg.setTitle("Chọn thư mục từ server");
            dlg.setHeaderText("Chọn thư mục để cấp quyền");
            dlg.setContentText("Thư mục:");
            dlg.initOwner(btnBrowse.getScene().getWindow());
            dlg.showAndWait().ifPresent(chosen -> {
                txtFilePath.setText(chosen);
                selectedFolderId = nameToId.getOrDefault(chosen, -1);
                showStatus("Đã chọn thư mục: " + chosen, false);
            });
        } catch (Exception e) {
            showStatus("Lỗi lấy danh sách thư mục: " + e.getMessage(), true);
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

        if (selectedFolderId <= 0) {
            showStatus("Vui lòng chọn thư mục (nhấn Browse) để cấp quyền!", true);
            return;
        }

        List<String> permissions = getSelectedPermissions();
        if (permissions.isEmpty()) {
            showStatus("Vui lòng chọn ít nhất một quyền!", true);
            return;
        }

        // Gửi request GRANT_FOLDER_PERMISSION lên server
        try {
            Integer targetUserId = userMap.get(user);
            if (targetUserId == null) {
                showStatus("Không xác định được userId của người dùng: " + user, true);
                return;
            }

            JsonObject data = new JsonObject();
            data.addProperty("targetUserId", targetUserId);
            data.addProperty("folderId", selectedFolderId);
            JsonArray arr = new JsonArray();
            for (String p : permissions) arr.add(p);
            data.add("permissions", arr);
            // Thêm thông tin người gửi để server kiểm tra admin (tạm thời)
            if (currentUser != null) data.addProperty("username", currentUser);

            Request req = new Request("GRANT_FOLDER_PERMISSION", data);
            Response resp = networkService.sendRequest(req);
            if (resp != null && "success".equalsIgnoreCase(resp.getStatus())) {
                showStatus("Đã áp dụng quyền thành công cho " + user, false);
            } else {
                showStatus("Cấp quyền thất bại: " + (resp != null ? resp.getMessage() : "No response"), true);
            }
            updateCurrentPermissions();
        } catch (Exception e) {
            showStatus("Lỗi khi gửi yêu cầu cấp quyền: " + e.getMessage(), true);
        }
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

    // ======= Injectors ======
    public void setNetworkService(NetworkService ns) { this.networkService = ns; }
    public void setFileService(FileService fs) { this.fileService = fs; }
    public void setCurrentUser(String username) { this.currentUser = username; }

    // Tải danh sách user từ server (GET_USER_LIST)
    private void loadUsersFromServer() {
        try {
            Request req = new Request("GET_USER_LIST", null);
            Response resp = networkService.sendRequest(req);
            if (resp != null && "success".equalsIgnoreCase(resp.getStatus()) && resp.getData() != null && resp.getData().isJsonArray()) {
                cmbUsers.getItems().clear();
                userMap.clear();
                for (com.google.gson.JsonElement el : resp.getData().getAsJsonArray()) {
                    if (el != null && el.isJsonObject()) {
                        com.google.gson.JsonObject obj = el.getAsJsonObject();
                        String uname = obj.has("username") ? obj.get("username").getAsString() : null;
                        int uid = obj.has("userId") ? obj.get("userId").getAsInt() : -1;
                        if (uname != null) {
                            cmbUsers.getItems().add(uname);
                            userMap.put(uname, uid);
                        }
                    }
                }
                if (!cmbUsers.getItems().isEmpty()) cmbUsers.getSelectionModel().selectFirst();
            } else {
                showStatus("Không thể lấy danh sách user từ server.", true);
            }
        } catch (Exception e) {
            showStatus("Lỗi khi lấy user list: " + e.getMessage(), true);
        }
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
