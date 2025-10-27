package com.pbl4.syncproject.client.controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.models.NotificationItem;
import com.pbl4.syncproject.client.services.NetworkService;
import com.pbl4.syncproject.client.services.FileService;
import com.pbl4.syncproject.client.services.NotificationManager;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

public class UserPermissionController implements Initializable {

    @FXML private ComboBox<String> cmbUsers;
    @FXML private TextField txtFilePath;
    @FXML private Button btnBrowse;

    @FXML private CheckBox chkRead;
    @FXML private CheckBox chkWrite;
    @FXML private CheckBox chkDelete;

    @FXML private TextArea txtCurrentPermissions;
    @FXML private Label lblStatus;

    @FXML private Button btnApply;
    @FXML private Button btnReset;
    @FXML private Button btnClose;

    // Injected services
    private NetworkService networkService;
    private FileService fileService;
    private String currentUser;
    
    // NotificationManager
    private NotificationManager notificationManager = NotificationManager.getInstance();

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
        chkDelete.selectedProperty().addListener((obs, oldVal, newVal) -> updatePermissionDisplay());
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
                // Lấy các thư mục con của root (parentId=1) thay vì root itself
                // Điều này sẽ trả về: documents, images, shared, videos, etc.
                folders = fileService.fetchAndParseFolderTree(1); // parentId=1 (root)
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
                showStatus("Đã chọn thư mục: " + chosen + " (ID=" + selectedFolderId + ")", false);
                updateCurrentPermissions(); // Cập nhật quyền sau khi chọn thư mục
            });
        } catch (Exception e) {
            showStatus("Lỗi lấy danh sách thư mục: " + e.getMessage(), true);
        }
    }

    @FXML
    private void handleApply() {
        String user = cmbUsers.getSelectionModel().getSelectedItem();

        if (user == null || user.isEmpty()) {
            showStatus("Vui lòng chọn người dùng!", true);
            return;
        }

        if (selectedFolderId <= 0) {
            showStatus("Vui lòng chọn thư mục (nhấn Browse) để cấp quyền!", true);
            return;
        }

        List<String> permissions = getSelectedPermissions();
        // Bỏ kiểm tra permissions.isEmpty() để cho phép thu hồi toàn bộ quyền
        // if (permissions.isEmpty()) {
        //     showStatus("Vui lòng chọn ít nhất một quyền!", true);
        //     return;
        // }

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
                
                // Gửi thông báo
                String folderPath = txtFilePath.getText();
                String permString = String.join(", ", permissions);
                notificationManager.addNotification(
                    "Admin (" + currentUser + ") đã cấp quyền [" + permString + "] " +
                    "cho user '" + user + "' trên thư mục '" + folderPath + "'.",
                    NotificationItem.NotificationType.PERMISSION_CHANGE
                );
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
        chkRead.setSelected(false);
        chkWrite.setSelected(false);
        chkDelete.setSelected(false);

        showStatus("Đã đặt lại các lựa chọn quyền", false);
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
        if (chkDelete.isSelected()) permissions.add("DELETE");

        return permissions;
    }

    @SuppressWarnings("unused")
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

    /**
     * Gọi phương thức này sau khi inject các service để load lại dữ liệu từ server
     */
    public void reinitializeWithServices() {
        if (networkService != null) {
            loadUsersFromServer();
            updateCurrentPermissions();
        }
    }

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
        String selectedUserName = cmbUsers.getSelectionModel().getSelectedItem();
        String selectedFolderPath = txtFilePath.getText().trim(); // Tên thư mục đang hiển thị

        // Chỉ thực hiện khi đã chọn user và thư mục hợp lệ (có folderId)
        if (selectedUserName == null || selectedFolderPath.isEmpty() || selectedFolderId <= 0 || networkService == null) {
            txtCurrentPermissions.setText("Vui lòng chọn người dùng và thư mục hợp lệ.");
            // Đặt lại các checkbox về trạng thái mặc định
            resetCheckboxesToDefault();
            return;
        }

        Integer targetUserId = userMap.get(selectedUserName);
        if (targetUserId == null) {
            txtCurrentPermissions.setText("Không tìm thấy ID cho người dùng: " + selectedUserName);
            resetCheckboxesToDefault();
            return;
        }

        // Tạo request để lấy quyền hiện tại
        JsonObject requestData = new JsonObject();
        requestData.addProperty("userId", targetUserId);
        requestData.addProperty("folderId", selectedFolderId);
        Request getPermsRequest = new Request("GET_FOLDER_PERMISSIONS", requestData);

        // Hiển thị tạm thời là đang tải
        txtCurrentPermissions.setText("Đang tải quyền hiện tại...");
        resetCheckboxesToDefault(); // Reset trước khi load

        // Gọi API bất đồng bộ
        new Thread(() -> {
            try {
                Response response = networkService.sendRequest(getPermsRequest);
                Platform.runLater(() -> { // Cập nhật UI trên JavaFX thread
                    if (response != null && "success".equals(response.getStatus()) && response.getData() != null && response.getData().isJsonObject()) {
                        JsonObject responseData = response.getData().getAsJsonObject();
                        JsonArray permsArray = responseData.has("permissions") 
                            ? responseData.getAsJsonArray("permissions") 
                            : new JsonArray();
                        
                        List<String> currentPermsList = new ArrayList<>();
                        StringBuilder permsText = new StringBuilder("Người dùng: " + selectedUserName + "\n");
                        permsText.append("Thư mục: ").append(selectedFolderPath).append("\n");
                        permsText.append("Quyền hiện tại:\n");

                        // Bỏ chọn tất cả checkbox trước
                        chkRead.setSelected(false);
                        chkWrite.setSelected(false);
                        chkDelete.setSelected(false);

                        if (permsArray.size() == 0) {
                            permsText.append("- Không có quyền nào.");
                        } else {
                            for (JsonElement permElement : permsArray) {
                                String perm = permElement.getAsString();
                                currentPermsList.add(perm);
                                permsText.append("- ").append(perm).append(": Có\n");
                                // Tự động check vào ô tương ứng
                                switch (perm.toUpperCase()) {
                                    case "READ":
                                        chkRead.setSelected(true);
                                        break;
                                    case "WRITE":
                                        chkWrite.setSelected(true);
                                        break;
                                    case "DELETE":
                                        chkDelete.setSelected(true);
                                        break;
                                }
                            }
                        }
                        txtCurrentPermissions.setText(permsText.toString());
                        updatePermissionDisplay(); // Cập nhật label trạng thái chọn

                    } else {
                        String errorMsg = (response != null && response.getMessage() != null) ? response.getMessage() : "Không thể lấy quyền.";
                        txtCurrentPermissions.setText("Lỗi: " + errorMsg);
                        resetCheckboxesToDefault();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    txtCurrentPermissions.setText("Lỗi kết nối khi lấy quyền: " + e.getMessage());
                    resetCheckboxesToDefault();
                    e.printStackTrace();
                });
            }
        }).start();
    }

    // Hàm phụ trợ để reset checkbox
    private void resetCheckboxesToDefault() {
        chkRead.setSelected(false); // Không chọn mặc định
        chkWrite.setSelected(false);
        chkDelete.setSelected(false);
        updatePermissionDisplay();
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
