package com.unsubble.smokin.ui;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class MainWindow extends BorderPane {

    private final ModeSelector modeSelector;
    private final RequestPanel requestPanel;
    private final RepeaterPanel repeaterPanel;

    private final StackPane modeArea;

    public MainWindow() {
        setStyle("-fx-background-color: " + SmokinTheme.BG_DEEP_CSS + ";");

        MenuBar menuBar = buildMenuBar();
        setTop(menuBar);

        modeSelector = new ModeSelector();

        HBox modeSelectorRow = createModeSelectorRow();

        requestPanel = new RequestPanel();
        repeaterPanel = new RepeaterPanel();

        VBox.setVgrow(requestPanel, Priority.ALWAYS);
        HBox.setHgrow(requestPanel, Priority.ALWAYS);

        modeArea = new StackPane(repeaterPanel);
        modeArea.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(modeArea, Priority.ALWAYS);

        HBox workspace = new HBox(SmokinTheme.SPACING_LG,
                requestPanel, modeArea);
        workspace.setFillHeight(true);
        workspace.setPadding(new Insets(SmokinTheme.SPACING_LG));
        VBox.setVgrow(workspace, Priority.ALWAYS);

        modeSelector.setOnModeChanged(this::switchMode);

        requestPanel.getSendButton().setOnAction(e -> {
            if (modeSelector.getCurrentMode() == ModeSelector.Mode.REPEATER) {
                repeaterPanel.showMockResponse();
            }
        });

        VBox topContainer = new VBox(menuBar, modeSelectorRow);
        setTop(topContainer);
        setCenter(workspace);
    }

    private HBox createModeSelectorRow() {
        HBox modeSelectorRow = new HBox(modeSelector);
        modeSelectorRow.setAlignment(Pos.CENTER_LEFT);
        modeSelectorRow.setPadding(new Insets(
                SmokinTheme.SPACING_MD,
                SmokinTheme.SPACING_LG,
                SmokinTheme.SPACING_MD,
                SmokinTheme.SPACING_LG));
        modeSelectorRow.setStyle(
                "-fx-background-color: " + SmokinTheme.BG_PRIMARY_CSS + ";"
                        + "-fx-border-color: transparent transparent "
                        + SmokinTheme.BORDER_DEFAULT_CSS + " transparent;"
                        + "-fx-border-width: 0 0 1 0;"
        );
        return modeSelectorRow;
    }


    private void switchMode(ModeSelector.Mode mode) {
        modeArea.getChildren().setAll(repeaterPanel);
    }

    private MenuBar buildMenuBar() {
        MenuBar bar = new MenuBar();
        bar.setStyle(
                "-fx-background-color: " + SmokinTheme.BG_PRIMARY_CSS + ";"
                        + "-fx-border-color: transparent transparent "
                        + SmokinTheme.BORDER_SUBTLE_CSS + " transparent;"
                        + "-fx-border-width: 0 0 1 0;"
                        + "-fx-padding: 0;"
        );

        Menu file = menu("File");
        file.getItems().addAll(
                item("New", null),
                item("Open…", null),
                item("Save", null),
                new SeparatorMenuItem(),
                item("Exit", e -> javafx.application.Platform.exit())
        );

        Menu edit = menu("Edit");
        edit.getItems().addAll(
                disabledItem("Undo"),
                disabledItem("Redo"),
                new SeparatorMenuItem(),
                disabledItem("Cut"),
                disabledItem("Copy"),
                disabledItem("Paste")
        );

        Menu view = menu("View");
        MenuItem toRepeater = item("Repeater",
                e -> modeSelector.setOnModeChanged(null));
        MenuItem toIntruder = item("Intruder", null);
        view.getItems().addAll(toRepeater, toIntruder, new SeparatorMenuItem(),
                disabledItem("Settings"));

        Menu tools = menu("Tools");
        tools.getItems().add(disabledItem("Settings"));

        Menu help = menu("Help");
        help.getItems().add(item("About", e -> showAbout()));

        bar.getMenus().addAll(file, edit, view, tools, help);
        return bar;
    }

    private Menu menu(String title) {
        Menu m = new Menu(title);
        m.setStyle("-fx-text-fill: " + SmokinTheme.TEXT_PRIMARY_CSS + ";");
        return m;
    }

    private MenuItem item(String title, EventHandler<ActionEvent> handler) {
        MenuItem mi = new MenuItem(title);
        if (handler != null) mi.setOnAction(handler);
        return mi;
    }

    private MenuItem disabledItem(String title) {
        MenuItem mi = new MenuItem(title);
        mi.setDisable(true);
        return mi;
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Smokin");
        alert.setHeaderText("Smokin - HTTP Security Tool");
        alert.setContentText("""
                Version 1.0-SNAPSHOT
                
                Minimalist HTTP testing tool.
                Inspired by a midnight navy tuxedo.""");
        alert.getDialogPane().setStyle(
                "-fx-background-color: " + SmokinTheme.BG_PRIMARY_CSS + ";"
                        + "-fx-text-fill: " + SmokinTheme.TEXT_PRIMARY_CSS + ";"
        );
        alert.showAndWait();
    }
}
