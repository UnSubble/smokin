package com.unsubble.smokin.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class RepeaterPanel extends VBox {

    private final LedBorderPane responsePane;
    private final TextArea responseViewer;
    private final CheckBox lastByteCheck;
    private final CheckBox asyncCheck;
    private final Spinner<Integer> threadCountSpinner;

    public RepeaterPanel() {
        setSpacing(SmokinTheme.SPACING_MD);
        setFillWidth(true);
        setPadding(new Insets(0));

        Label sectionLabel = sectionHeader("RESPONSE");

        responseViewer = new TextArea();
        responseViewer.setFont(SmokinTheme.FONT_MONO);
        responseViewer.setEditable(false);
        responseViewer.setWrapText(false);
        responseViewer.setStyle(
                "-fx-background-color: " + SmokinTheme.BG_INPUT_CSS + ";"
                        + "-fx-text-fill: " + SmokinTheme.TEXT_MONO_CSS + ";"
                        + "-fx-border-color: transparent;"
                        + "-fx-border-width: 0;"
                        + "-fx-background-insets: 0;"
                        + "-fx-padding: 12 14 12 14;"
                        + "-fx-font-family: 'JetBrains Mono';"
                        + "-fx-font-size: 16px;"
        );
        responseViewer.setPromptText("Response will appear here…");

        responsePane = new LedBorderPane();
        responsePane.setCenter(responseViewer);
        VBox.setVgrow(responsePane, Priority.ALWAYS);

        Label optionsHeader = sectionHeader("EXECUTION OPTIONS");

        lastByteCheck = new CheckBox("Last Byte Synchronization");
        lastByteCheck.setStyle(checkStyle());
        lastByteCheck.setSelected(false);

        asyncCheck = new CheckBox("Async");
        asyncCheck.setStyle(checkStyle());
        asyncCheck.setSelected(true);

        Label threadsLabel = new Label("Threads:");
        threadsLabel.setStyle("-fx-text-fill: " + SmokinTheme.TEXT_MUTED_CSS + ";"
                + "-fx-font-size: 12px;");

        threadCountSpinner = new Spinner<>(1, 256, 10);
        threadCountSpinner.setEditable(true);
        threadCountSpinner.setStyle(
                "-fx-background-color: " + SmokinTheme.BG_INPUT_CSS + ";"
                        + "-fx-border-color: " + SmokinTheme.BORDER_DEFAULT_CSS + ";"
                        + "-fx-border-radius: " + SmokinTheme.RADIUS_SM + ";"
                        + "-fx-text-fill: " + SmokinTheme.TEXT_PRIMARY_CSS + ";"
                        + "-fx-font-size: 12px;"
        );
        threadCountSpinner.setPrefWidth(70);
        threadCountSpinner.setFocusTraversable(false);

        HBox threadRow = new HBox(SmokinTheme.SPACING_SM, threadsLabel, threadCountSpinner);
        threadRow.setAlignment(Pos.CENTER_LEFT);

        lastByteCheck.selectedProperty().addListener((
                obs, wasSelected, isSelected) -> {

            if (isSelected)
                asyncCheck.setSelected(true);
        });

        VBox optionsBox = createOptionsBox(optionsHeader, threadRow);

        getChildren().addAll(sectionLabel, responsePane, optionsBox);
    }

    private VBox createOptionsBox(Label optionsHeader, HBox threadRow) {
        VBox optionsBox = new VBox(SmokinTheme.SPACING_SM,
                optionsHeader, asyncCheck, threadRow, lastByteCheck);
        optionsBox.setPadding(new Insets(SmokinTheme.SPACING_MD));
        optionsBox.setStyle(
                "-fx-background-color: " + SmokinTheme.BG_SURFACE_CSS + ";"
                        + "-fx-border-color: " + SmokinTheme.BORDER_SUBTLE_CSS + ";"
                        + "-fx-border-radius: " + SmokinTheme.RADIUS_MD + ";"
                        + "-fx-background-radius: " + SmokinTheme.RADIUS_MD + ";"
        );
        return optionsBox;
    }


    public void setResponse(String text) {
        responseViewer.setText(text);
    }

    public void setLedState(LedBorderPane.LedState state) {
        responsePane.setState(state);
    }

    public boolean isLastByteSyncEnabled() {
        return lastByteCheck.isSelected();
    }

    public boolean isAsyncEnabled() {
        return asyncCheck.isSelected();
    }

    public int getThreadCount() {
        return threadCountSpinner.getValue();
    }

    public void showMockResponse() {
        setState(LedBorderPane.LedState.SENDING);

        javafx.application.Platform.runLater(() -> {
            setResponse("""
                        TODO...
                    """);
            setState(LedBorderPane.LedState.SUCCESS);
        });
    }

    private void setState(LedBorderPane.LedState state) {
        responsePane.setState(state);
    }

    private Label sectionHeader(String text) {
        Label lbl = new Label(text);
        lbl.setStyle(
                "-fx-text-fill: " + SmokinTheme.TEXT_MUTED_CSS + ";"
                        + "-fx-font-size: 10px;"
                        + "-fx-font-weight: bold;"
        );
        return lbl;
    }

    private String checkStyle() {
        return "-fx-text-fill: " + SmokinTheme.TEXT_PRIMARY_CSS + ";"
                + "-fx-font-size: 12px;"
                + "-fx-cursor: hand;";
    }
}
