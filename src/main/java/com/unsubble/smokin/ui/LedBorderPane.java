package com.unsubble.smokin.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

public class LedBorderPane extends BorderPane {

    private LedState currentState;
    private Timeline pulseTimeline;
    private Timeline fadeTimeline;

    public LedBorderPane() {
        currentState = LedState.IDLE;
        applyIdleStyle();
    }

    public LedState getState() {
        return currentState;
    }

    public void setState(LedState state) {
        if (currentState == state) return;
        currentState = state;
        cancelAnimations();

        switch (state) {
            case IDLE -> applyIdleStyle();
            case SENDING -> applySendingPulse();
            case SUCCESS -> applySuccessGlow();
            case ERROR -> applyErrorGlow();
        }
    }

    private void applyIdleStyle() {
        setStyle(baseStyle(SmokinTheme.BORDER_DEFAULT_CSS, 1.0, 0));
    }

    private void applySendingPulse() {
        setStyle(baseStyle(SmokinTheme.ACCENT_BLUE_CSS, 1.0, 4));
        pulseTimeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(700),
                        new KeyValue(opacityProperty(), 0.7)),
                new KeyFrame(Duration.millis(1400),
                        new KeyValue(opacityProperty(), 1.0))
        );
        pulseTimeline.setCycleCount(Timeline.INDEFINITE);
        pulseTimeline.play();
    }

    private void applySuccessGlow() {
        setStyle(baseStyle(SmokinTheme.ACCENT_CYAN_CSS, 1.5, 8));
        scheduleReturn();
    }

    private void applyErrorGlow() {
        setStyle(baseStyle("#E05252", 1.5, 8));
        scheduleReturn();
    }

    private void scheduleReturn() {
        fadeTimeline = new Timeline(
                new KeyFrame(Duration.millis(1800), e -> setState(LedState.IDLE))
        );
        fadeTimeline.play();
    }

    private String baseStyle(String borderColor, double borderWidth, double blur) {
        return "-fx-background-color: " + SmokinTheme.BG_PRIMARY_CSS + ";"
                + "-fx-border-color: " + borderColor + ";"
                + "-fx-border-width: " + borderWidth + ";"
                + "-fx-border-radius: " + SmokinTheme.RADIUS_MD + ";"
                + "-fx-background-radius: " + SmokinTheme.RADIUS_MD + ";";
    }

    private void cancelAnimations() {
        if (pulseTimeline != null) {
            pulseTimeline.stop();
            pulseTimeline = null;
        }
        if (fadeTimeline != null) {
            fadeTimeline.stop();
            fadeTimeline = null;
        }
        setOpacity(1.0);
    }

    public enum LedState {
        IDLE,
        SENDING,
        SUCCESS,
        ERROR
    }
}
