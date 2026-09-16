package com.unsubble.smokin;

import com.unsubble.smokin.ui.MainScene;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.Objects;

public class Main extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        try {
            InputStream iconStream = getClass().getResourceAsStream("/icon.png");
            Objects.requireNonNull(iconStream);
            Image icon = new Image(iconStream);
            primary.getIcons().add(icon);
        } catch (Exception ignored) {
        }

        MainScene scene = new MainScene();

        primary.setTitle("Smokin");
        primary.setMinWidth(900);
        primary.setMinHeight(600);
        primary.setResizable(true);
        primary.setScene(scene);
        primary.show();
    }
}
