package com.pbl4.syncproject.client.controllers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.client.services.NetworkService;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.net.*;

import java.util.Enumeration;

public class LoginController {

    @FXML private TextField txtUsername;
    @FXML private PasswordField txtPassword;
    @FXML private Label lblStatus;
    @FXML private ComboBox<String> cmbIp;
    @FXML private ComboBox<String> cmbPort;
    @FXML private Button btnClear;
    @FXML private Button btnLogin;

    // Gói kết quả đăng nhập
    private static final class LoginResult {
        final boolean success;
        final NetworkService ns;
        LoginResult(boolean success, NetworkService ns) {
            this.success = success;
            this.ns = ns;
        }
    }

    public void initialize() {
        cmbPort.getItems().setAll("8080", "21", "22", "80", "443");
        cmbPort.getSelectionModel().select("8080");
        cmbIp.getItems().addAll("20.89.65.146", "127.0.0.1");
        cmbIp.getSelectionModel().selectFirst();
        loadIpAddress();
        setStatus("", null);
    }

    private void loadIpAddress() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress ia = addrs.nextElement();
                    if (!ia.isLoopbackAddress() && ia instanceof Inet4Address) {
                        String ip = ia.getHostAddress();
                        if (!cmbIp.getItems().contains(ip)) {
                            cmbIp.getItems().add(ip);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleLogin() {
        final String username = txtUsername.getText() == null ? "" : txtUsername.getText().trim();
        final String password = txtPassword.getText() == null ? "" : txtPassword.getText();
        final String ip = cmbIp.getSelectionModel().getSelectedItem();

        if (ip == null || cmbPort.getSelectionModel().isEmpty()) {
            setStatus("⚠️ Vui lòng chọn IP và Port!", "orange"); return;
        }
        if (username.isEmpty() || password.isEmpty()) {
            setStatus("⚠️ Nhập đầy đủ tài khoản & mật khẩu!", "orange"); return;
        }

        final int port;
        try {
            port = Integer.parseInt(cmbPort.getSelectionModel().getSelectedItem());
        } catch (NumberFormatException ex) {
            setStatus("⚠️ Port không hợp lệ!", "orange"); return;
        }

        setBusy(true);

        Task<LoginResult> loginTask = new Task<LoginResult>() {
            @Override
            protected LoginResult call() throws Exception {
                // Chuẩn bị request LOGIN
                JsonObject data = new JsonObject();
                data.addProperty("username", username);
                data.addProperty("password", password);
                Request req = new Request("LOGIN", data);

                // Mở kết nối persistent và gọi login
                NetworkService ns = new NetworkService(ip, port);
                ns.start();
                Response res = ns.sendRequest(req);

                boolean ok = "success".equalsIgnoreCase(res.getStatus());
                if (!ok) {
                    ns.stop(); // đóng nếu login fail
                }
                return new LoginResult(ok, ns);
            }
        };

        loginTask.setOnSucceeded(ev -> {
            setBusy(false);
            LoginResult r = loginTask.getValue();
            if (r != null && r.success) {
                setStatus("✅ Login thành công!", "green");
                openMainAndCloseLogin(username, ip, port, r.ns);
            } else {
                setStatus("❌ Sai tài khoản hoặc mật khẩu!", "red");
            }
        });

        loginTask.setOnFailed(ev -> {
            setBusy(false);
            setStatus("❌ Không kết nối được server!", "red");
            Throwable ex = loginTask.getException();
            if (ex != null) ex.printStackTrace();
        });

        new Thread(loginTask, "login-task").start();
    }

    @FXML
    private void handleClear() {
        txtUsername.clear();
        txtPassword.clear();
        setStatus("", null);
        cmbIp.getSelectionModel().select("127.0.0.1");
        cmbPort.getSelectionModel().select("8080");
    }

    private void openMainAndCloseLogin(String username, String ip, int port, NetworkService ns) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/main-refactored.fxml"));
            Parent root = loader.load();

            MainController mainController = loader.getController();
            if (mainController != null) {
                mainController.setServerAddress(ip, port);
                mainController.setNetworkService(ns); // truyền kết nối xuyên suốt
                mainController.setUsername(username);
                mainController.initAfterLogin();   // <-- gọi khởi tạo sau login
            }

            Stage mainStage = new Stage();
            mainStage.setTitle("Hệ thống đồng bộ dữ liệu - " + username);
            mainStage.setScene(new Scene(root, 1200, 800));
            mainStage.setMaximized(true);
            // đóng socket/cleanup khi tắt window chính
            mainStage.setOnCloseRequest(e -> {
                if (mainController != null) mainController.cleanup();
            });
            mainStage.show();

            ((Stage) txtUsername.getScene().getWindow()).close();
        } catch (Exception e) {
            setStatus("❌ Lỗi mở giao diện chính!", "red");
            e.printStackTrace();
            if (ns != null) ns.stop(); // tránh rò nếu UI mở lỗi
        }
    }

    private void setBusy(boolean busy) {
        if (btnLogin != null) btnLogin.setDisable(busy);
        if (btnClear != null) btnClear.setDisable(busy);
        txtUsername.setDisable(busy);
        txtPassword.setDisable(busy);
        cmbIp.setDisable(busy);
        cmbPort.setDisable(busy);
        if (busy) setStatus("⏳ Đang đăng nhập...", "gray");
    }

    private void setStatus(String msg, String color) {
        lblStatus.setText(msg == null ? "" : msg);
        if (color != null) {
            lblStatus.setStyle("-fx-text-fill: " + color + ";");
        } else {
            lblStatus.setStyle(null);
        }
    }
}
