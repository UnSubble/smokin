package com.unsubble.smokin.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.util.Objects;

public class MainScene extends Scene {

    public MainScene() {
        super(new MainWindow(), 1400, 850, Color.web(SmokinTheme.BG_DEEP_CSS));
        applyStylesheet();
    }

    public MainScene(Parent root) {
        super(root, 1400, 850, Color.web(SmokinTheme.BG_DEEP_CSS));
        applyStylesheet();
    }

    private void applyStylesheet() {
        String cssStr = Objects.requireNonNull(
                getClass().getResource("/smokin.css")
        ).toExternalForm();
        Objects.requireNonNull(cssStr);
        getStylesheets().add(cssStr);
    }
}
