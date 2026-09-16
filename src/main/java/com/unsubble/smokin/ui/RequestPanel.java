package com.unsubble.smokin.ui;

import com.unsubble.smokin.model.Protocol;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Arrays;

public class RequestPanel extends VBox {

    private final TextField urlField;
    private final Button sendButton;
    private final CheckBox followRedirects;
    private final ComboBox<Protocol> protocolComboBox;
    private final TextArea requestEditor;

    public RequestPanel() {
        setSpacing(0);
        setFillWidth(true);
        setStyle("-fx-background-color: " + SmokinTheme.BG_PRIMARY_CSS + ";"
                + "-fx-border-color: " + SmokinTheme.BORDER_DEFAULT_CSS + ";"
                + "-fx-border-radius: " + SmokinTheme.RADIUS_MD + ";"
                + "-fx-background-radius: " + SmokinTheme.RADIUS_MD + ";");

        urlField = new TextField("https://example.com/");
        urlField.setPromptText("https://example.com/path");
        urlField.setStyle(
                "-fx-background-color: " + SmokinTheme.BG_INPUT_CSS + ";"
                        + "-fx-border-color: " + SmokinTheme.BORDER_SUBTLE_CSS + ";"
                        + "-fx-border-radius: " + SmokinTheme.RADIUS_SM + ";"
                        + "-fx-background-radius: " + SmokinTheme.RADIUS_SM + ";"
                        + "-fx-text-fill: " + SmokinTheme.TEXT_PRIMARY_CSS + ";"
                        + "-fx-font-family: 'Inter';"
                        + "-fx-font-size: 12px;"
                        + "-fx-padding: 5 10 5 10;"
        );
        HBox.setHgrow(urlField, Priority.ALWAYS);

        sendButton = new Button("Send");
        sendButton.setStyle(SmokinTheme.primaryButtonStyle());
        sendButton.setMinWidth(70);
        sendButton.setFocusTraversable(false);

        followRedirects = new CheckBox("Follow Redirects");
        followRedirects.setStyle(SmokinTheme.labelStyle());

        ObservableList<Protocol> protocols = FXCollections.observableList(Arrays.stream(Protocol.values()).toList());
        protocolComboBox = new ComboBox<>(protocols);
        protocolComboBox.setStyle(SmokinTheme.labelStyle());
        protocolComboBox.setValue(Protocol.HTTP);

        HBox topBar = new HBox(SmokinTheme.SPACING_SM, protocolComboBox, urlField, sendButton, followRedirects);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(SmokinTheme.SPACING_MD));
        topBar.setStyle("-fx-border-color: transparent transparent "
                + SmokinTheme.BORDER_SUBTLE_CSS + " transparent;"
                + "-fx-border-width: 0 0 1 0;");

        requestEditor = new TextArea();
        requestEditor.setFont(SmokinTheme.FONT_MONO);
        requestEditor.setWrapText(false);
        requestEditor.setStyle("-fx-text-fill: " + SmokinTheme.TEXT_MONO_CSS + ";"
                        + "-fx-border-color: transparent;"
                        + "-fx-border-width: 0;"
                        + "-fx-background-insets: 0;"
                        + "-fx-padding: 8 6 8 6;"
                        + "-fx-font-family: 'JetBrains Mono';"
                        + "-fx-font-size: 16px;"
        );

        VBox requestWrapper = new VBox(requestEditor);
        requestWrapper.setStyle("-fx-background-color: " + SmokinTheme.BG_INPUT_CSS + ";"
                + "-fx-padding: 8 6 8 6;");

        VBox.setVgrow(requestEditor, Priority.ALWAYS);
        VBox.setVgrow(requestWrapper, Priority.ALWAYS);

        getChildren().addAll(topBar, requestWrapper);
    }

    public String getRequestText() {
        return requestEditor.getText();
    }

    public void setRequestText(String txt) {
        requestEditor.setText(txt);
    }

    public Button getSendButton() {
        return sendButton;
    }

    public TextField getUrlField() {
        return urlField;
    }

    public Protocol getSelectedProtocol() {
        return protocolComboBox.getValue();
    }
}
