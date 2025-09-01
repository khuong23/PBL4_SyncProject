package com.pbl4.syncproject.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ClientApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(ClientApp.class.getResource("/com/pbl4/syncproject/login.fxml"));
        Scene scene = new Scene(loader.load(), 400, 250);
        stage.setTitle("Client Login");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}
