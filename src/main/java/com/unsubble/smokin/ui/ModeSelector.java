package com.unsubble.smokin.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

public class ModeSelector extends HBox {

    private final Button repeaterBtn;
    private final Button intruderBtn;
    private Mode currentMode = Mode.REPEATER;
    private ModeChangeListener listener;
    public ModeSelector() {
        setSpacing(SmokinTheme.SPACING_SM);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(0));

        repeaterBtn = buildModeButton("REPEATER", Mode.REPEATER);
        intruderBtn = buildModeButton("INTRUDER", Mode.INTRUDER);

        getChildren().addAll(repeaterBtn, intruderBtn);

        refreshStyles();
    }

    public void setOnModeChanged(ModeChangeListener listener) {
        this.listener = listener;
    }

    public Mode getCurrentMode() {
        return currentMode;
    }

    private Button buildModeButton(String label, Mode mode) {
        Button btn = new Button(label);
        btn.setFont(SmokinTheme.FONT_UI_SEMI);
        btn.setFocusTraversable(false);
        btn.setOnAction(e -> selectMode(mode));
        return btn;
    }

    private void selectMode(Mode mode) {
        if (currentMode == mode) return;
        currentMode = mode;
        refreshStyles();
        if (listener != null) listener.onModeChanged(mode);
    }

    private void refreshStyles() {
        repeaterBtn.setStyle(SmokinTheme.modeSelectorButtonStyle(currentMode == Mode.REPEATER));
        intruderBtn.setStyle(SmokinTheme.modeSelectorButtonStyle(currentMode == Mode.INTRUDER));
    }

    public enum Mode {REPEATER, INTRUDER}

    @FunctionalInterface
    public interface ModeChangeListener {
        void onModeChanged(Mode newMode);
    }
}
