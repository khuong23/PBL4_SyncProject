module com.pbl4.syncproject {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires transitive javafx.graphics;
    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires java.sql;
    requires com.google.gson;
    requires com.zaxxer.hikari;
    requires java.desktop; // Cần cho java.awt.Desktop (mở file)

    exports com.pbl4.syncproject.client to javafx.graphics;
    opens com.pbl4.syncproject.client to javafx.fxml;
    opens com.pbl4.syncproject.client.controllers to javafx.fxml;

    opens com.pbl4.syncproject.common.jsonhandler to com.google.gson;
}