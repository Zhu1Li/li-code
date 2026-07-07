package com.licode.gui;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public final class GUIConstants {

    private GUIConstants() {}

    // Colors (Catppuccin Mocha inspired)
    public static final String BG = "#1e1e2e";
    public static final String SURFACE = "#313244";
    public static final String BUBBLE_USER = "#45475a";
    public static final String TEXT_PRIMARY = "#cdd6f4";
    public static final String TEXT_SECONDARY = "#a6adc8";
    public static final String TEXT_MUTED = "#6c7086";
    public static final String ACCENT = "#89b4fa";
    public static final String ERROR = "#f38ba8";
    public static final String INPUT_BG = "#313244";
    public static final String CODE_BG = "#181825";
    public static final String BORDER = "#45475a";

    public static Color color(String hex) {
        return Color.web(hex);
    }

    // Fonts
    public static final Font MONO_FONT = Font.font("Monospaced", 15);
    public static final Font MONO_SMALL = Font.font("Monospaced", 13);
    public static final Font SANS_FONT = Font.font("System", 14);
    public static final Font SANS_BOLD = Font.font("System", javafx.scene.text.FontWeight.BOLD, 15);

    // Layout
    public static final double MIN_WINDOW_WIDTH = 800;
    public static final double MIN_WINDOW_HEIGHT = 500;
    public static final double DEFAULT_WIDTH = 1100;
    public static final double DEFAULT_HEIGHT = 800;
    public static final double SIDEBAR_WIDTH = 300;
    public static final double INPUT_HEIGHT = 30;
    public static final double STATUS_HEIGHT = 25;
    public static final double BUBBLE_PADDING = 10;
    public static final double MESSAGE_SPACING = 6;
}
