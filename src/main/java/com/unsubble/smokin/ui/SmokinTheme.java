package com.unsubble.smokin.ui;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public final class SmokinTheme {

    private SmokinTheme() {
    }

    public static final Color BG_DEEP      = Color.web("#0D1117");
    public static final Color BG_PRIMARY   = Color.web("#161B22");
    public static final Color BG_SURFACE   = Color.web("#1C2330");
    public static final Color BG_INPUT     = Color.web("#141920");

    public static final Color TEXT_PRIMARY  = Color.web("#E8EDF3");
    public static final Color TEXT_MUTED    = Color.web("#8B95A3");
    public static final Color TEXT_MONO     = Color.web("#C9D1DB");

    public static final Color BORDER_SUBTLE  = Color.web("#252D3A");
    public static final Color BORDER_DEFAULT = Color.web("#2E3746");

    public static final Color ACCENT_CYAN   = Color.web("#5BC8D0");
    public static final Color ACCENT_BLUE   = Color.web("#5B9BD5");
    public static final Color ACCENT_PURPLE = Color.web("#7B2FBE");
    public static final Color ACCENT_PURPLE_BRIGHT = Color.web("#9B59F0");

    public static final Color LED_IDLE    = Color.web("#252D3A");
    public static final Color LED_SENDING = Color.web("#5B9BD5");
    public static final Color LED_SUCCESS = Color.web("#5BC8D0");
    public static final Color LED_ERROR   = Color.web("#E05252");

    public static final String BG_DEEP_CSS      = "#0D1117";
    public static final String BG_PRIMARY_CSS   = "#161B22";
    public static final String BG_SURFACE_CSS   = "#1C2330";
    public static final String BG_INPUT_CSS     = "#101516";
    public static final String TEXT_PRIMARY_CSS = "#E8EDF3";
    public static final String TEXT_MUTED_CSS   = "#8B95A3";
    public static final String TEXT_MONO_CSS    = "#C9D1DB";
    public static final String BORDER_SUBTLE_CSS  = "#252D3A";
    public static final String BORDER_DEFAULT_CSS = "#2E3746";
    public static final String ACCENT_CYAN_CSS    = "#5BC8D0";
    public static final String ACCENT_BLUE_CSS    = "#5B9BD5";
    public static final String ACCENT_PURPLE_CSS  = "#7B2FBE";
    public static final String ACCENT_PURPLE_BRIGHT_CSS = "#9B59F0";

    public static final double RADIUS_SM = 4;
    public static final double RADIUS_MD = 6;
    public static final double RADIUS_LG = 8;

    public static final double SPACING_XS = 4;
    public static final double SPACING_SM = 8;
    public static final double SPACING_MD = 12;
    public static final double SPACING_LG = 16;
    public static final double SPACING_XL = 24;

    public static final Font FONT_UI_SM   = Font.font("Inter", FontWeight.NORMAL, 11);
    public static final Font FONT_UI      = Font.font("Inter", FontWeight.NORMAL, 12);
    public static final Font FONT_UI_MED  = Font.font("Inter", FontWeight.MEDIUM, 12);
    public static final Font FONT_UI_SEMI = Font.font("Inter", FontWeight.BOLD,   12);
    public static final Font FONT_LABEL   = Font.font("Inter", FontWeight.BOLD,   10);
    public static final Font FONT_MONO    = Font.font("JetBrains Mono", FontWeight.NORMAL, 12);
    public static final Font FONT_MONO_SM = Font.font("JetBrains Mono", FontWeight.NORMAL, 11);

    public static String panelStyle() {
        return "-fx-background-color: " + BG_PRIMARY_CSS + ";"
             + "-fx-border-color: " + BORDER_DEFAULT_CSS + ";"
             + "-fx-border-radius: " + RADIUS_MD + ";"
             + "-fx-background-radius: " + RADIUS_MD + ";";
    }

    public static String inputStyle() {
        return "-fx-background-color: " + BG_INPUT_CSS + ";"
             + "-fx-border-color: " + BORDER_SUBTLE_CSS + ";"
             + "-fx-border-radius: " + RADIUS_SM + ";"
             + "-fx-background-radius: " + RADIUS_SM + ";"
             + "-fx-text-fill: " + TEXT_MONO_CSS + ";"
             + "-fx-font-family: 'JetBrains Mono';"
             + "-fx-font-size: 12px;";
    }

    public static String labelStyle() {
        return "-fx-text-fill: " + TEXT_MUTED_CSS + ";"
             + "-fx-font-size: 10px;"
             + "-fx-font-weight: bold;";
    }

    public static String sectionHeaderStyle() {
        return "-fx-text-fill: " + TEXT_MUTED_CSS + ";"
             + "-fx-font-size: 10px;"
             + "-fx-font-weight: bold;"
             + "-fx-letter-spacing: 0.08em;";
    }

    public static String primaryButtonStyle() {
        return "-fx-background-color: " + ACCENT_BLUE_CSS + ";"
             + "-fx-text-fill: " + BG_DEEP_CSS + ";"
             + "-fx-border-color: transparent;"
             + "-fx-border-radius: " + RADIUS_SM + ";"
             + "-fx-background-radius: " + RADIUS_SM + ";"
             + "-fx-padding: 5 14 5 14;"
             + "-fx-font-weight: bold;"
             + "-fx-font-size: 12px;"
             + "-fx-cursor: hand;";
    }

    public static String dangerButtonStyle() {
        return "-fx-background-color: " + LED_ERROR.toString().replace("0x","#").substring(0,7) + ";"
             + "-fx-text-fill: " + TEXT_PRIMARY_CSS + ";"
             + "-fx-border-color: transparent;"
             + "-fx-border-radius: " + RADIUS_SM + ";"
             + "-fx-background-radius: " + RADIUS_SM + ";"
             + "-fx-padding: 5 14 5 14;"
             + "-fx-font-weight: bold;"
             + "-fx-font-size: 12px;"
             + "-fx-cursor: hand;";
    }

    public static String ghostButtonStyle() {
        return "-fx-background-color: transparent;"
             + "-fx-text-fill: " + TEXT_MUTED_CSS + ";"
             + "-fx-border-color: " + BORDER_DEFAULT_CSS + ";"
             + "-fx-border-radius: " + RADIUS_SM + ";"
             + "-fx-background-radius: " + RADIUS_SM + ";"
             + "-fx-padding: 5 14 5 14;"
             + "-fx-font-size: 12px;"
             + "-fx-cursor: hand;";
    }

    public static String modeSelectorButtonStyle(boolean active) {
        if (active) {
            return "-fx-background-color: " + ACCENT_PURPLE_CSS + ";"
                 + "-fx-text-fill: " + TEXT_PRIMARY_CSS + ";"
                 + "-fx-border-color: " + ACCENT_PURPLE_BRIGHT_CSS + ";"
                 + "-fx-border-radius: " + RADIUS_SM + ";"
                 + "-fx-background-radius: " + RADIUS_SM + ";"
                 + "-fx-padding: 6 22 6 22;"
                 + "-fx-font-weight: bold;"
                 + "-fx-font-size: 11px;"
                 + "-fx-letter-spacing: 0.1em;"
                 + "-fx-cursor: hand;";
        } else {
            return "-fx-background-color: transparent;"
                 + "-fx-text-fill: " + TEXT_MUTED_CSS + ";"
                 + "-fx-border-color: " + BORDER_DEFAULT_CSS + ";"
                 + "-fx-border-radius: " + RADIUS_SM + ";"
                 + "-fx-background-radius: " + RADIUS_SM + ";"
                 + "-fx-padding: 6 22 6 22;"
                 + "-fx-font-size: 11px;"
                 + "-fx-letter-spacing: 0.1em;"
                 + "-fx-cursor: hand;";
        }
    }
}
