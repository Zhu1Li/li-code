package com.licode.gui;

import com.licode.session.SessionManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class SessionSidebar extends VBox {

    private final SessionManager sessionManager;
    private final SessionTitleStore titleStore;
    private final VBox sessionList;
    private final Consumer<String> onSessionSelect;
    private final Runnable onNewSession;

    private String activeSessionId;
    private final Map<String, String> titleCache;

    SessionSidebar(SessionManager sessionManager, Path sessionsDir,
                   Consumer<String> onSessionSelect, Runnable onNewSession) {
        this.sessionManager = sessionManager;
        this.titleStore = new SessionTitleStore(sessionsDir);
        this.onSessionSelect = onSessionSelect;
        this.onNewSession = onNewSession;
        this.titleCache = new java.util.LinkedHashMap<>();

        setMinWidth(GUIConstants.SIDEBAR_WIDTH);
        setPrefWidth(GUIConstants.SIDEBAR_WIDTH);
        setStyle("-fx-background-color: " + GUIConstants.SURFACE + ";");
        setPadding(new Insets(10));

        // Header
        Label header = new Label("Sessions");
        header.setStyle("-fx-text-fill: " + GUIConstants.TEXT_PRIMARY
                + "; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 4 0 8 0;");

        // New session button
        Button newBtn = new Button("+ New Chat");
        newBtn.setMaxWidth(Double.MAX_VALUE);
        newBtn.setStyle("-fx-background-color: " + GUIConstants.ACCENT
                + "; -fx-text-fill: #1e1e2e; -fx-font-weight: bold;"
                + " -fx-font-size: 13px; -fx-padding: 8 0 8 0; -fx-background-radius: 6;");
        newBtn.setOnAction(e -> {
            if (onNewSession != null) onNewSession.run();
        });

        // Session list
        this.sessionList = new VBox(3);
        sessionList.setPadding(new Insets(6, 0, 4, 0));
        ScrollPane scrollPane = new ScrollPane(sessionList);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(header, newBtn, scrollPane);

        this.titleCache.putAll(titleStore.loadAll());
    }

    void setActiveSession(String sessionId) {
        this.activeSessionId = sessionId;
    }

    void updateTitle(String sessionId, String title) {
        titleCache.put(sessionId, title);
    }

    SessionTitleStore getTitleStore() {
        return titleStore;
    }

    void refresh() {
        List<SessionManager.SessionInfo> sessions = sessionManager.listSessions(null, null, null);
        sessionList.getChildren().clear();

        for (SessionManager.SessionInfo info : sessions) {
            String title = titleCache.getOrDefault(info.id(),
                    info.firstMessage() != null ? truncate(info.firstMessage(), 40) : "New session");
            boolean isActive = info.id().equals(activeSessionId);

            Label item = new Label(title);
            String itemStyle = "-fx-font-size: 14px; -fx-padding: 5 8 5 8;";
            if (isActive) {
                itemStyle += "-fx-text-fill: " + GUIConstants.TEXT_PRIMARY + ";";
            } else {
                itemStyle += "-fx-text-fill: " + GUIConstants.TEXT_SECONDARY + ";";
            }
            item.setStyle(itemStyle);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setWrapText(true);

            // Show message count as subtitle
            Label meta = new Label(info.messageCount() + " msgs, " + formatDate(info));
            String metaStyle;
            if (isActive) {
                metaStyle = "-fx-text-fill: " + GUIConstants.TEXT_SECONDARY
                        + "; -fx-font-size: 11px; -fx-padding: 0 8 2 8;";
            } else {
                metaStyle = "-fx-text-fill: " + GUIConstants.TEXT_MUTED
                        + "; -fx-font-size: 11px; -fx-padding: 0 8 2 8;";
            }
            meta.setStyle(metaStyle);

            // Tooltip for the whole row
            Tooltip rowTooltip = new Tooltip("Session: " + truncate(title, 60)
                    + "\nMessages: " + info.messageCount()
                    + "\nModified: " + formatDate(info));

            // Row background
            String rowBg = isActive ? GUIConstants.BUBBLE_USER : "transparent";
            String rowHoverBg = GUIConstants.BORDER;
            String rowBase = "-fx-background-color: " + rowBg
                    + "; -fx-background-radius: 6; -fx-padding: 3 4 3 4;";

            if (!isActive) {
                VBox titleArea = new VBox(0, item, meta);
                HBox.setHgrow(titleArea, Priority.ALWAYS);

                Button delBtn = new Button("×"); // × symbol
                delBtn.setStyle("-fx-background-color: transparent;"
                        + " -fx-text-fill: " + GUIConstants.TEXT_MUTED
                        + "; -fx-font-size: 16px; -fx-font-weight: bold;"
                        + " -fx-padding: 0 6 0 0; -fx-min-width: 24; -fx-min-height: 24;");
                delBtn.setOnMouseEntered(e -> delBtn.setStyle(
                        delBtn.getStyle().replace(GUIConstants.TEXT_MUTED, GUIConstants.ERROR)
                                .replace("16px", "18px")));
                delBtn.setOnMouseExited(e -> delBtn.setStyle(
                        delBtn.getStyle().replace(GUIConstants.ERROR, GUIConstants.TEXT_MUTED)
                                .replace("18px", "16px")));
                delBtn.setOnAction(e -> {
                    sessionManager.deleteSession(info.id());
                    titleStore.remove(info.id());
                    titleCache.remove(info.id());
                    refresh();
                });

                HBox row = new HBox(2);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setStyle(rowBase);
                row.getChildren().addAll(titleArea, delBtn);

                // Click + hover on the entire row
                row.setOnMouseClicked(e -> onSessionSelect.accept(info.id()));
                row.setOnMouseEntered(e ->
                        row.setStyle(rowBase + "-fx-background-color: " + rowHoverBg + ";"));
                row.setOnMouseExited(e -> row.setStyle(rowBase));

                Tooltip.install(row, rowTooltip);

                sessionList.getChildren().add(row);
            } else {
                VBox wrapper = new VBox(0, item, meta);
                wrapper.setStyle(rowBase);
                Tooltip.install(wrapper, rowTooltip);
                sessionList.getChildren().add(wrapper);
            }
        }
    }

    private static String formatDate(SessionManager.SessionInfo info) {
        if (info.modTime() == null) return "";
        var dt = java.time.LocalDateTime.ofInstant(info.modTime(),
                java.time.ZoneId.systemDefault());
        var now = java.time.LocalDate.now();
        if (dt.toLocalDate().equals(now)) {
            return dt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        return dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
