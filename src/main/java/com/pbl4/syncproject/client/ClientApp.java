package com.pbl4.syncproject.client;

import com.pbl4.syncproject.client.services.LocalDatabaseManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ClientApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {

        LocalDatabaseManager.getInstance().initializeDatabase();
        FXMLLoader loader = new FXMLLoader(ClientApp.class.getResource("/com/pbl4/syncproject/login.fxml"));
        Scene scene = new Scene(loader.load(), 900, 580);
        stage.setTitle("Đăng nhập - Hệ thống đồng bộ dữ liệu");
        stage.setResizable(false);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}
