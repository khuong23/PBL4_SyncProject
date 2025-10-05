module com.pbl4.syncproject {
    requires transitive javafx.controls;
    requires transitive javafx.fxml;
    requires transitive javafx.web;
    requires transitive javafx.graphics; // Added for completeness
    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires java.sql;
    requires java.desktop;
    requires transitive com.google.gson;

    exports com.pbl4.syncproject.client to javafx.graphics;
    exports com.pbl4.syncproject.client.services; // Export services package
    exports com.pbl4.syncproject.client.models; // Export models package for FileItem
    exports com.pbl4.syncproject.client.controllers; // Export controllers package
    exports com.pbl4.syncproject.common.jsonhandler; // Export for Response class
    exports com.pbl4.syncproject.client.views;

    opens com.pbl4.syncproject.client to javafx.fxml;
    opens com.pbl4.syncproject.client.controllers to javafx.fxml, javafx.base; // Added for LoginController
    opens com.pbl4.syncproject.client.views to javafx.fxml, javafx.base;
    opens com.pbl4.syncproject.client.models to javafx.fxml, javafx.base;
    opens com.pbl4.syncproject.common.jsonhandler to com.google.gson;
}