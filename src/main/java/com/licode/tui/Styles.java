package com.licode.tui;

import com.williamcallahan.tui4j.compat.bubbletea.lipgloss.Style;
import com.williamcallahan.tui4j.compat.bubbletea.lipgloss.color.ANSI256Color;

public final class Styles {

    private Styles() {}

    private static final ANSI256Color PURPLE = new ANSI256Color(99);
    private static final ANSI256Color DIM = new ANSI256Color(242);
    private static final ANSI256Color MUTED = new ANSI256Color(245);
    private static final ANSI256Color TEXT = new ANSI256Color(252);
    private static final ANSI256Color BRIGHT = new ANSI256Color(255);
    private static final ANSI256Color GREEN = new ANSI256Color(78);
    private static final ANSI256Color RED = new ANSI256Color(203);
    private static final ANSI256Color CYAN = new ANSI256Color(80);
    private static final ANSI256Color SEP = new ANSI256Color(236);

    // User
    public static final Style userText = Style.newStyle()
            .foreground(BRIGHT).bold(true);

    // AI
    public static final Style aiMarker = Style.newStyle()
            .foreground(PURPLE).bold(true);
    public static final Style aiText = Style.newStyle()
            .foreground(TEXT);
    public static final Style streamingDot = Style.newStyle()
            .foreground(PURPLE);

    // Thinking
    public static final Style thinkingLabel = Style.newStyle()
            .foreground(PURPLE);
    public static final Style thinkingContent = Style.newStyle()
            .foreground(DIM);

    // System / Error
    public static final Style systemText = Style.newStyle()
            .foreground(DIM);
    public static final Style errorText = Style.newStyle()
            .foreground(RED);

    // Input
    public static final Style prompt = Style.newStyle()
            .foreground(CYAN).bold(true);
    public static final Style inputText = Style.newStyle()
            .foreground(BRIGHT);
    public static final Style separator = Style.newStyle()
            .foreground(SEP);

    // Banner
    public static final Style banner = Style.newStyle()
            .foreground(PURPLE).bold(true);
    public static final Style bannerDim = Style.newStyle()
            .foreground(DIM);

    // Provider selection
    public static final Style selectLabel = Style.newStyle()
            .foreground(PURPLE).bold(true);
    public static final Style selectedItem = Style.newStyle()
            .foreground(CYAN).bold(true);
    public static final Style normalItem = Style.newStyle()
            .foreground(MUTED);

    // Placeholder
    public static final Style placeholder = Style.newStyle()
            .foreground(new ANSI256Color(240));

    // Status bar
    public static final Style statusBar = Style.newStyle()
            .foreground(DIM);
    public static final Style statusItem = Style.newStyle()
            .foreground(MUTED);

    // Cursor
    public static final Style cursorBlock = Style.newStyle()
            .foreground(new ANSI256Color(0))
            .background(CYAN);

    // Helper
    public static final Style inlineCyan = Style.newStyle().foreground(CYAN);
    public static final Style inlineDim = Style.newStyle().foreground(DIM);
    public static final Style inlineGreen = Style.newStyle().foreground(GREEN);
    public static final Style inlineRed = Style.newStyle().foreground(RED);

    public static String cyan(String s) { return inlineCyan.render(s); }
    public static String dim(String s) { return inlineDim.render(s); }
    public static String green(String s) { return inlineGreen.render(s); }
    public static String red(String s) { return inlineRed.render(s); }
}
