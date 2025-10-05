module com.pbl4.syncproject {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires javafx.graphics; // Added for completeness
    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires java.sql;
    requires com.google.gson;

    exports com.pbl4.syncproject.client to javafx.graphics;
    opens com.pbl4.syncproject.client to javafx.fxml;
    opens com.pbl4.syncproject.client.controllers to javafx.fxml; // Added for LoginController
    opens com.pbl4.syncproject to javafx.fxml; // For FXML file access

    opens com.pbl4.syncproject.common.jsonhandler to com.google.gson;
}