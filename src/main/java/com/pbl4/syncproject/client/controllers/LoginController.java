package com.pbl4.syncproject.client.controllers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.JsonUtils;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.concurrent.Task;
import javafx.stage.Stage;
import java.io.*;
import java.net.*;
import java.util.Enumeration;

public class LoginController {
    @FXML private TextField txtUsername;
    @FXML private PasswordField txtPassword;
    @FXML private Label lblStatus;
    @FXML private ComboBox<String> cmbIp;
    @FXML private ComboBox<String> cmbPort;
    @FXML private Button btnClear;

    public void initialize(){
        cmbPort.getItems().addAll("5000","21", "22", "80", "443", "8080");
        cmbPort.getSelectionModel().select(0); // 5000
        cmbIp.getItems().add("20.89.65.146");
        loadIpAddress();
    }
    private void loadIpAddress(){
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                Enumeration<InetAddress> inetAddresses = nic.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        cmbIp.getItems().add(inetAddress.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleLogin() {
        String username = txtUsername.getText();
        String password = txtPassword.getText();
        String ip = cmbIp.getSelectionModel().getSelectedItem();

        if (ip == null || cmbPort.getSelectionModel().isEmpty()) {
            lblStatus.setText("⚠️ Vui lòng chọn IP và Port!");
            lblStatus.setStyle("-fx-text-fill: orange;");
            return;
        }

        int port = Integer.parseInt(cmbPort.getSelectionModel().getSelectedItem());

        // Task chạy mạng trong background thread
        Task<Boolean> loginTask = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                try (Socket socket = new Socket(ip, port);
                     PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                    // Tạo JSON request
                    JsonObject data = new JsonObject();
                    data.addProperty("username", username);
                    data.addProperty("password", password);

                    Request req = new Request("LOGIN", data);
                    String jsonString = JsonUtils.toJson(req);
                    writer.println(jsonString); // gửi lên server

                    // Đọc response từ server
                    String responseStr = reader.readLine();
                    Response resObj = JsonUtils.fromJson(responseStr, Response.class);

                    // Trả về true nếu login thành công
                    return "success".equalsIgnoreCase(resObj.getStatus());
                }
            }
        };

        // Khi task thành công
        loginTask.setOnSucceeded(event -> {
            boolean success = loginTask.getValue();
            if (success) {
                lblStatus.setText("✅ Login thành công!");
                lblStatus.setStyle("-fx-text-fill: green;");

                // Mở giao diện chính
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pbl4/syncproject/main-refactored.fxml"));
                    Parent root = loader.load();

                    Stage mainStage = new Stage();
                    mainStage.setTitle("Hệ thống đồng bộ dữ liệu - " + username);
                    mainStage.setScene(new Scene(root, 1200, 800));
                    mainStage.setMaximized(true);
                    mainStage.show();

                    // Đóng login window
                    Stage loginStage = (Stage) txtUsername.getScene().getWindow();
                    loginStage.close();
                } catch (Exception e) {
                    lblStatus.setText("❌ Lỗi mở giao diện chính!");
                    lblStatus.setStyle("-fx-text-fill: red;");
                    e.printStackTrace();
                }
            } else {
                lblStatus.setText("❌ Sai tài khoản hoặc mật khẩu!");
                lblStatus.setStyle("-fx-text-fill: red;");
            }
        });

        // Khi task thất bại
        loginTask.setOnFailed(event -> {
            lblStatus.setText("❌ Không kết nối được server!");
            lblStatus.setStyle("-fx-text-fill: red;");
            loginTask.getException().printStackTrace();
        });

        // Chạy task
        new Thread(loginTask).start();
    }


    @FXML
    private void handleClear() {
        txtUsername.clear();
        txtPassword.clear();
        lblStatus.setText("");
        cmbIp.getSelectionModel().select("127.0.0.1");
        cmbPort.getSelectionModel().select(0);
    }
}