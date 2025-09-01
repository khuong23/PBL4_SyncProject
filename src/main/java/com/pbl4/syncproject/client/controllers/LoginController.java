package com.pbl4.syncproject.client.controllers;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.concurrent.Task;
import java.io.*;
import java.net.Socket;

public class LoginController {
    @FXML private TextField txtUsername;
    @FXML private PasswordField txtPassword;
    @FXML private Label lblStatus;

    @FXML
    private void handleLogin() {
        String username = txtUsername.getText();
        String password = txtPassword.getText();

        // Create a Task for the network operation
        Task<String> loginTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                try (Socket socket = new Socket("localhost", 5000);
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