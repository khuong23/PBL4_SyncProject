package com.pbl4.syncproject.client.controllers;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.concurrent.Task;
import java.io.*;
import java.net.*;
import java.util.Enumeration;

public class LoginController {
    @FXML private TextField txtUsername;
    @FXML private PasswordField txtPassword;
    @FXML private Label lblStatus;
    @FXML private ComboBox<String> cmbIp;
    @FXML private ComboBox<String> cmbPort;
    @FXML

    public void initialize(){
        cmbPort.getItems().addAll("5000","21", "22", "80", "443", "8080");
        cmbPort.getSelectionModel().select(0); // 5000
        cmbIp.getItems().add("127.0.0.1");
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
        // Create a Task for the network operation
        Task<String> loginTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                try (Socket socket = new Socket(ip, port);
                     PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                    writer.println(username + "," + password); // Send as one line
                    return reader.readLine(); // Read the server response
                }
            }
        };

        // Handle the task result on the JavaFX thread
        loginTask.setOnSucceeded(event -> {
            String response = loginTask.getValue();
            if ("SUCCESS".equals(response)) {
                lblStatus.setText("✅ Login thành công!");
                lblStatus.setStyle("-fx-text-fill: green;");
            } else {
                lblStatus.setText("❌ Sai tài khoản hoặc mật khẩu!");
                lblStatus.setStyle("-fx-text-fill: red;");
            }
        });

        // Handle exceptions on the JavaFX thread
        loginTask.setOnFailed(event -> {
            lblStatus.setText("❌ Không kết nối được server!");
            lblStatus.setStyle("-fx-text-fill: red;");
            loginTask.getException().printStackTrace(); // Debug the exception
        });

        // Run the task in a background thread
        new Thread(loginTask).start();
    }
}