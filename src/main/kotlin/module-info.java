module org.example.danicnc {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires java.xml;
    requires kotlin.stdlib;
    requires clipper2;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;

    opens org.example.danicnc to javafx.fxml;
    exports org.example.danicnc;
}