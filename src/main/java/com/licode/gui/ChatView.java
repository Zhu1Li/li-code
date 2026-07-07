package com.licode.gui;

import com.licode.command.Command;
import com.licode.command.CommandContext;
import com.licode.command.CommandRegistry;
import com.licode.config.ProviderConfig;
import com.licode.permission.PermissionMode;
import com.licode.runtime.LiRuntime;
import com.licode.session.SessionManager;
import com.licode.tui.ChatMessage;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatView extends SplitPane {

    private final LiRuntime runtime;
    private final ProviderConfig provider;
    private final Stage stage;

    private final VBox messageList;
    private final ScrollPane scrollPane;
    private final TextArea inputField;
    private final Button sendButton;
    private final Label modelLabel;
    private final Label modeLabel;
    private final Label tokenLabel;
    private final Label mcpLabel;
    private final Label hookLabel;
    private final Label skillLabel;
    private final Label toolLabel;
    private final Label workDirLabel;
    private final Tooltip mcpTooltip = new Tooltip();
    private final Tooltip hookTooltip = new Tooltip();
    private final Tooltip skillTooltip = new Tooltip();
    private final Tooltip toolTooltip = new Tooltip();
    private final javafx.stage.Popup slashPopup = new javafx.stage.Popup();
    private final javafx.scene.control.ListView<String> slashList =
            new javafx.scene.control.ListView<>();
    private final SessionSidebar sidebar;
    private final CommandRegistry cmdRegistry;

    private boolean streaming;
    private ChatBubble currentStreamBubble;
    private ChatBubble currentToolBubble;
    private boolean toolCallOccurred;
    private final List<ChatBubble> streamTextBubbles = new ArrayList<>();
    private final List<String> inputHistory = new ArrayList<>();
    private int historyPos = -1;

    private int totalInputTokens;
    private int totalOutputTokens;

    // Plan mode state
    private boolean planModeActive;
    private PermissionMode prePlanPermissionMode = PermissionMode.DEFAULT;

    // Title generation
    private final SessionTitleGenerator titleGenerator;
    private String currentUserQuestion;
    private final StringBuilder currentAssistantResponse = new StringBuilder();
    private boolean titleGenerated;

    // Streaming thinking
    private final StringBuilder thinkingBuf = new StringBuilder();
    private ChatBubble currentThinkingBubble;

    // Team mailbox polling
    private final ScheduledExecutorService mailboxPoller =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mailbox-poller");
                t.setDaemon(true);
                return t;
            });

    public ChatView(LiRuntime runtime, ProviderConfig provider, Stage stage) {
        this.runtime = runtime;
        this.provider = provider;
        this.stage = stage;

        // Sidebar
        var sessionMgr = runtime.getSessionManager();
        var sessionsDir = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve(".licode").resolve("sessions");
        this.sidebar = new SessionSidebar(sessionMgr, sessionsDir,
                this::switchSession, this::newSession);

        // Command registry for slash commands
        this.cmdRegistry = new CommandRegistry();
        runtime.wireSkillsToCommands(cmdRegistry);

        // Title generator
        this.titleGenerator = new SessionTitleGenerator(provider);

        // ── Right side: chat area ─────────────────────────────────────

        BorderPane chatArea = new BorderPane();

        // Top: status bar
        this.modelLabel = statusChip(provider.getModel());
        this.modelLabel.getStyleClass().add("status-model");
        this.modeLabel = statusChip("");
        this.tokenLabel = statusChip("ready");
        this.tokenLabel.getStyleClass().add("status-tokens");
        this.mcpLabel = statusChip("");
        this.mcpLabel.getStyleClass().add("status-indicator");
        this.hookLabel = statusChip("");
        this.hookLabel.getStyleClass().add("status-indicator");
        this.skillLabel = statusChip("");
        this.skillLabel.getStyleClass().add("status-indicator");
        this.toolLabel = statusChip("");
        this.toolLabel.getStyleClass().add("status-indicator");

        // Working directory — click to change
        String wd = System.getProperty("user.dir");
        this.workDirLabel = statusChip(shortenPath(wd));
        workDirLabel.getStyleClass().addAll("status-indicator", "status-workdir");
        workDirLabel.setTooltip(new Tooltip("Working directory: " + wd + "\nClick to change"));
        workDirLabel.setOnMouseClicked(e -> chooseWorkingDir());

        // Install tooltips once — refreshStatusBar updates their text
        mcpTooltip.setShowDelay(javafx.util.Duration.millis(200));
        Tooltip.install(mcpLabel, mcpTooltip);
        hookTooltip.setShowDelay(javafx.util.Duration.millis(200));
        Tooltip.install(hookLabel, hookTooltip);
        skillTooltip.setShowDelay(javafx.util.Duration.millis(200));
        Tooltip.install(skillLabel, skillTooltip);
        toolTooltip.setShowDelay(javafx.util.Duration.millis(200));
        Tooltip.install(toolLabel, toolTooltip);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox statusBar = new HBox(6, modelLabel, modeLabel, tokenLabel,
                mcpLabel, hookLabel, skillLabel, toolLabel, spacer, workDirLabel);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setMinHeight(GUIConstants.STATUS_HEIGHT);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        chatArea.setTop(statusBar);
        refreshStatusBar();

        // Center: message list
        this.messageList = new VBox(GUIConstants.MESSAGE_SPACING);
        messageList.setPadding(new Insets(10));
        this.scrollPane = new ScrollPane(messageList);
        scrollPane.getStyleClass().add("scroll-pane");
        scrollPane.setFitToWidth(true);
        // Force messageList width to track viewport, so children (ChatBubble)
        // receive a concrete width constraint and TextFlow wrapping kicks in.
        messageList.minWidthProperty().bind(scrollPane.widthProperty().subtract(20));
        chatArea.setCenter(scrollPane);

        // Bottom: input area
        this.inputField = new TextArea();
        inputField.getStyleClass().add("input-field");
        inputField.setPromptText("Type your message... (Enter to send, Shift+Enter to wrap, / for commands)");
        inputField.setWrapText(true);
        inputField.setPrefRowCount(1);
        inputField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.TAB && isSlashTyping()) {
                e.consume();
                completeSlashCommand();
            } else if (e.getCode() == KeyCode.ENTER && e.isShiftDown()) {
                // Shift+Enter: insert newline at cursor
                int caret = inputField.getCaretPosition();
                inputField.insertText(caret, "\n");
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                hideSlashPopup();
                e.consume();
                sendMessage();
            } else if (e.getCode() == KeyCode.UP && !e.isShiftDown()) {
                if (!slashPopup.isShowing()) {
                    navigateHistory(-1);
                }
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN && !e.isShiftDown()) {
                if (!slashPopup.isShowing()) {
                    navigateHistory(1);
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideSlashPopup();
                e.consume();
            }
        });
        // Auto-grow: expand as user types more lines
        inputField.textProperty().addListener((obs, old, val) -> {
            updateSlashPopup();
            int lines = 1;
            for (int i = 0; i < val.length(); i++) {
                if (val.charAt(i) == '\n') lines++;
            }
            inputField.setPrefRowCount(Math.clamp(lines, 1, 8));
        });
        HBox.setHgrow(inputField, Priority.ALWAYS);

        this.sendButton = new Button("Send");
        sendButton.getStyleClass().add("send-button");
        sendButton.setOnAction(e -> onSendOrStop());

        HBox inputArea = new HBox(10, inputField, sendButton);
        inputArea.getStyleClass().add("input-area");
        inputArea.setAlignment(Pos.CENTER_LEFT);
        inputArea.setMinHeight(GUIConstants.INPUT_HEIGHT);
        chatArea.setBottom(inputArea);

        // Slash command suggestion popup
        slashList.getStyleClass().add("slash-popup");
        slashList.setMaxHeight(200);
        slashList.prefWidthProperty().bind(inputField.widthProperty());
        slashPopup.getContent().add(slashList);
        slashList.setOnMouseClicked(ev -> completeSlashCommand());
        slashList.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ENTER || ev.getCode() == KeyCode.TAB) {
                ev.consume();
                completeSlashCommand();
            } else if (ev.getCode() == KeyCode.ESCAPE) {
                hideSlashPopup();
            }
        });

        // ── SplitPane layout ──────────────────────────────────────────

        getItems().addAll(sidebar, chatArea);
        setDividerPositions(0.22); // sidebar takes ~22%
        SplitPane.setResizableWithParent(sidebar, false);

        // Refresh sidebar and select current session
        Platform.runLater(() -> {
            sidebar.refresh();
            String sid = runtime.getCurrentSessionId();
            if (sid != null) {
                sidebar.setActiveSession(sid);
            }
            inputField.requestFocus();
        });

        // Start team mailbox polling
        startMailboxPoller();
    }

    public void addMessage(ChatMessage message) {
        Platform.runLater(() -> {
            ChatBubble bubble = new ChatBubble(message);
            messageList.getChildren().add(bubble);
            scrollToBottom();
        });
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || streaming) return;

        inputField.clear();

        // Slash command dispatch
        if (text.startsWith("/")) {
            dispatchSlashCommand(text);
            return;
        }

        inputHistory.add(text);
        historyPos = inputHistory.size();

        ChatMessage userMsg = ChatMessage.user(text);
        ChatBubble userBubble = new ChatBubble(userMsg);
        messageList.getChildren().add(userBubble);
        scrollToBottom();

        streaming = true;
        currentStreamBubble = null;
        currentToolBubble = null;
        toolCallOccurred = false;
        streamTextBubbles.clear();
        thinkingBuf.setLength(0);
        currentThinkingBubble = null;
        currentUserQuestion = text;
        currentAssistantResponse.setLength(0);
        updateSendEnabled();

        StreamEventHandler handler = new StreamEventHandler(this);
        Thread.startVirtualThread(() -> {
            try {
                runtime.ask(text, handler);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    ChatBubble errorBubble = new ChatBubble(ChatMessage.error("Error: " + e.getMessage()));
                    messageList.getChildren().add(errorBubble);
                    finishStreaming();
                });
            }
        });
    }

    private void dispatchSlashCommand(String text) {
        String[] parts = text.substring(1).split("\\s+", 2);
        String cmdName = parts[0];
        String cmdArgs = parts.length > 1 ? parts[1] : "";

        var cmd = cmdRegistry.find(cmdName);
        if (cmd.isEmpty()) {
            addMessage(ChatMessage.system("Unknown command: " + text + " — type /help to see available commands"));
            return;
        }

        var command = cmd.get();
        switch (command.type()) {
            case LOCAL -> {
                var ctx = buildCommandContext(cmdArgs);
                String result = cmdRegistry.execute(command.name(), ctx);
                if (result != null && !result.isEmpty()) {
                    addMessage(ChatMessage.system(result));
                }
            }
            case LOCAL_UI -> {
                var ctx = buildCommandContext(cmdArgs);
                switch (command.name()) {
                    case "clear" -> {
                        messageList.getChildren().clear();
                        totalInputTokens = 0;
                        totalOutputTokens = 0;
                        updateStatus("ready");
                    }
                    case "compact" -> {
                        // Trigger compaction via runtime
                        addMessage(ChatMessage.system("Compacting conversation context..."));
                    }
                    case "plan" -> {
                        enterPlanMode();
                    }
                    case "do" -> {
                        exitPlanMode();
                    }
                    case "resume" -> {
                        addMessage(ChatMessage.system("Use the sidebar to select a session to resume."));
                    }
                }
            }
            case PROMPT -> {
                var ctx = buildCommandContext(cmdArgs);
                String prompt = cmdRegistry.execute(command.name(), ctx);
                if (prompt != null && !prompt.isEmpty()) {
                    addMessage(ChatMessage.user(text));
                    if (command.description() != null && command.description().endsWith("[skill]")) {
                        addMessage(ChatMessage.system("skill(" + command.name() + ") Successfully loaded skill"));
                    }
                    runtime.getConversation().addUserMessage(prompt);
                    if (cmdArgs != null && !cmdArgs.isBlank()) {
                        runtime.getConversation().addUserMessage(cmdArgs);
                    }
                    // Trigger streaming with the injected prompt
                    streaming = true;
                    currentStreamBubble = null;
                    currentToolBubble = null;
                    toolCallOccurred = false;
                    streamTextBubbles.clear();
                    thinkingBuf.setLength(0);
                    currentThinkingBubble = null;
                    currentUserQuestion = text;
                    currentAssistantResponse.setLength(0);
                    updateSendEnabled();
                    StreamEventHandler handler = new StreamEventHandler(this);
                    Thread.startVirtualThread(() -> {
                        try {
                            runtime.ask(null, handler);
                        } catch (Exception e) {
                            Platform.runLater(() -> {
                                ChatBubble errorBubble = new ChatBubble(ChatMessage.error("Error: " + e.getMessage()));
                                messageList.getChildren().add(errorBubble);
                                finishStreaming();
                            });
                        }
                    });
                }
            }
            case SKILL_FORK -> {
                // Fork skill: run in an isolated sub-agent, stream progress here.
                addMessage(ChatMessage.user(text));
                addMessage(ChatMessage.system("skill(" + command.name() + ") running in isolated sub-agent…"));
                streaming = true;
                currentStreamBubble = null;
                currentToolBubble = null;
                toolCallOccurred = false;
                streamTextBubbles.clear();
                thinkingBuf.setLength(0);
                currentThinkingBubble = null;
                currentUserQuestion = text;
                currentAssistantResponse.setLength(0);
                updateSendEnabled();
                StreamEventHandler handler = new StreamEventHandler(this);
                Thread.startVirtualThread(() -> {
                    try {
                        runtime.askForkSkill(command.name(), cmdArgs, handler);
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            ChatBubble errorBubble = new ChatBubble(ChatMessage.error("Error: " + e.getMessage()));
                            messageList.getChildren().add(errorBubble);
                            finishStreaming();
                        });
                    }
                });
            }
        }
    }

    private CommandContext buildCommandContext(String args) {
        return new CommandContext(
                args,
                System.getProperty("user.dir"),
                () -> provider.getModel(),
                () -> runtime.getPermissionChecker() != null
                        ? runtime.getPermissionChecker().getMode().name().toLowerCase()
                        : "default",
                () -> runtime.getToolRegistry() != null
                        ? runtime.getToolRegistry().listTools().size()
                        : 0,
                () -> totalInputTokens,
                () -> totalOutputTokens,
                () -> runtime.getMemoryManager() != null
                        ? runtime.getMemoryManager().getMemories()
                        : List.of(),
                () -> {
                    if (runtime.getMemoryManager() != null) runtime.getMemoryManager().clear();
                },
                () -> {
                    String sid = runtime.getCurrentSessionId();
                    int count = runtime.getConversation().getMessages().size();
                    return sid != null ? "Session: " + sid + " (" + count + " messages)" : "No active session";
                },
                () -> {
                    var skills = new ArrayList<String>();
                    if (runtime.getSkillCatalog() != null) {
                        for (var s : runtime.getSkillCatalog().list()) {
                            skills.add(s.name());
                        }
                    }
                    return skills;
                },
                () -> messageList.getChildren().clear(),
                () -> addMessage(ChatMessage.system("Compacting conversation context...")),
                this::enterPlanMode,
                this::exitPlanMode,
                () -> addMessage(ChatMessage.system("Use the sidebar to select a session to resume.")));
    }

    // ── Stream event callbacks ────────────────────────────────────────

    void onStreamTextDelta(String text) {
        currentAssistantResponse.append(text);
        Platform.runLater(() -> {
            // If tools were called before this text, finalize old bubble once
            // and start a new one below tools. Reset flag so subsequent deltas
            // continue appending to the same post-tool bubble.
            if (toolCallOccurred && currentStreamBubble != null) {
                currentStreamBubble = null;
                toolCallOccurred = false;
            }
            if (currentStreamBubble == null) {
                currentStreamBubble = new ChatBubble(ChatMessage.assistant(""));
                streamTextBubbles.add(currentStreamBubble);
                messageList.getChildren().add(currentStreamBubble);
            }
            currentStreamBubble.appendRawText(text);
            currentStreamBubble.getContentFlow().getChildren().addAll(ChatBubble.parseStyledText(text));
            scrollToBottom();
        });
    }

    void onStreamThinkingDelta(String text) {
        Platform.runLater(() -> {
            thinkingBuf.append(text);
            if (currentThinkingBubble == null) {
                currentThinkingBubble = new ChatBubble(ChatMessage.thinking(thinkingBuf.toString()));
                messageList.getChildren().add(currentThinkingBubble);
            } else {
                currentThinkingBubble.updateThinking(thinkingBuf.toString());
            }
            scrollToBottom();
        });
    }

    void onStreamThinkingComplete(String thinkingText) {
        Platform.runLater(() -> {
            if (currentThinkingBubble != null) {
                currentThinkingBubble.updateThinking(thinkingText);
                currentThinkingBubble.setThinkingExpanded(true);
                currentThinkingBubble = null;
            } else {
                ChatMessage thinkingMsg = ChatMessage.thinking(thinkingText);
                ChatBubble bubble = new ChatBubble(thinkingMsg);
                messageList.getChildren().add(bubble);
            }
            scrollToBottom();
        });
    }

    void onStreamToolCallStart(String toolCallId, String toolName) {
        toolCallOccurred = true;
        Platform.runLater(() -> {
            currentToolBubble = new ChatBubble(
                    ChatMessage.system("Running " + toolName + "..."));
            currentToolBubble.markAsTool();
            currentToolBubble.getContentFlow().getChildren().clear();
            Text runText = new Text("Running " + toolName + "...");
            runText.getStyleClass().add("text-tool");
            currentToolBubble.getContentFlow().getChildren().add(runText);
            messageList.getChildren().add(currentToolBubble);
            scrollToBottom();
        });
    }

    void onStreamToolCallComplete(String toolName, Map<String, Object> args) {
        Platform.runLater(() -> {
            if (currentToolBubble != null) {
                currentToolBubble.getContentFlow().getChildren().clear();
                StringBuilder sb = new StringBuilder(toolName);
                if (args != null && !args.isEmpty()) {
                    for (var entry : args.entrySet()) {
                        if (!"thinking".equals(entry.getKey()) && !"description".equals(entry.getKey())) {
                            sb.append(" ").append(entry.getValue());
                            break;
                        }
                    }
                }
                Text toolText = new Text(sb.toString());
                toolText.getStyleClass().add("text-tool");
                currentToolBubble.getContentFlow().getChildren().add(toolText);
            }
        });
    }

    void onStreamError(String message) {
        Platform.runLater(() -> {
            ChatBubble errorBubble = new ChatBubble(ChatMessage.error(message));
            messageList.getChildren().add(errorBubble);
            scrollToBottom();
            // Always release the input on error/timeout so the UI never gets stuck.
            finishStreaming();
        });
    }

    void onStreamComplete(String stopReason, int inputTokens, int outputTokens) {
        Platform.runLater(() -> {
            this.totalInputTokens += inputTokens;
            this.totalOutputTokens += outputTokens;
            updateStatus(stopReason);

            // Re-render all assistant text bubbles with full markdown
            for (ChatBubble bubble : streamTextBubbles) {
                bubble.reRenderContent();
            }

            // Trigger title generation after first complete round
            if (!titleGenerated && currentUserQuestion != null
                    && !currentAssistantResponse.isEmpty()) {
                String sessionId = runtime.getCurrentSessionId();
                if (sessionId != null) {
                    titleGenerated = true;
                    titleGenerator.generate(sessionId,
                            currentUserQuestion,
                            currentAssistantResponse.toString(),
                            sidebar.getTitleStore(),
                            () -> {
                                sidebar.updateTitle(sessionId, currentUserQuestion);
                                sidebar.refresh();
                            });
                }
            }
            finishStreaming();
        });
    }

    void onPermissionRequest(String toolId, String toolName, String description,
                             CompletableFuture<com.licode.permission.PermissionResponse> future) {
        Platform.runLater(() -> {
            PermissionDialog.show(stage, toolName, description, response -> future.complete(response));
        });
    }

    private void finishStreaming() {
        streaming = false;
        currentStreamBubble = null;
        currentToolBubble = null;
        toolCallOccurred = false;
        streamTextBubbles.clear();
        thinkingBuf.setLength(0);
        currentThinkingBubble = null;
        updateSendEnabled();
        Platform.runLater(() -> inputField.requestFocus());
    }

    private void onSendOrStop() {
        if (streaming) {
            stopStreaming();
        } else {
            sendMessage();
        }
    }

    private void stopStreaming() {
        runtime.cancel();
        addMessage(ChatMessage.system("Stopped."));
        finishStreaming();
    }

    private void updateSendEnabled() {
        // Input stays editable during streaming so the user can compose the next
        // message. The Send button doubles as a Stop button while a turn is running.
        inputField.setDisable(false);
        sendButton.setDisable(false);
        sendButton.setText(streaming ? "Stop" : "Send");
        if (streaming) {
            if (!sendButton.getStyleClass().contains("stop-button")) {
                sendButton.getStyleClass().add("stop-button");
            }
        } else {
            sendButton.getStyleClass().remove("stop-button");
        }
    }

    private void updateStatus(String stopReason) {
        String extra = (stopReason != null && !stopReason.isEmpty() && !"end_turn".equals(stopReason))
                ? " | " + stopReason : "";
        tokenLabel.setText("in: " + formatTokens(totalInputTokens)
                + "  out: " + formatTokens(totalOutputTokens) + extra);
    }

    private void refreshStatusBar() {
        modelLabel.setText(provider.getModel());

        // Mode
        if (planModeActive) {
            modeLabel.setText("PLAN");
            modeLabel.getStyleClass().remove("status-mode");
            modeLabel.getStyleClass().add("status-mode-plan");
        } else {
            modeLabel.setText("");
            modeLabel.getStyleClass().remove("status-mode-plan");
            modeLabel.getStyleClass().add("status-mode");
        }
        modeLabel.setVisible(planModeActive);
        modeLabel.setManaged(planModeActive);

        // MCP
        var mcp = runtime.getMcpManager();
        int mcpCount = mcp != null ? mcp.getServerCount() : 0;
        mcpLabel.setText("MCP " + mcpCount);
        mcpTooltip.setText(mcpCount > 0 && mcp != null
                ? "MCP servers:\n  " + String.join("\n  ", mcp.getServerNames())
                : "");
        mcpLabel.setVisible(mcpCount > 0);
        mcpLabel.setManaged(mcpCount > 0);

        // Hooks
        var hooks = runtime.getHookEngine();
        int hookCount = hooks != null ? hooks.getHookCount() : 0;
        hookLabel.setText("Hooks " + hookCount);
        hookTooltip.setText(hookCount > 0 && hooks != null
                ? "Hooks:\n  " + String.join("\n  ", hooks.getHookIds())
                : "");
        hookLabel.setVisible(hookCount > 0);
        hookLabel.setManaged(hookCount > 0);

        // Skills
        var skillCat = runtime.getSkillCatalog();
        int skillCount = skillCat != null ? skillCat.list().size() : 0;
        skillLabel.setText("Skills " + skillCount);
        skillTooltip.setText(skillCount > 0 && skillCat != null
                ? "Skills:\n  " + String.join("\n  ",
                        skillCat.list().stream().map(s -> s.name()).toList())
                : "");
        skillLabel.setVisible(skillCount > 0);
        skillLabel.setManaged(skillCount > 0);

        // Tools
        var registry = runtime.getToolRegistry();
        int toolCount = registry != null ? registry.listTools().size() : 0;
        toolLabel.setText("Tools " + toolCount);
        toolTooltip.setText(toolCount > 0 && registry != null
                ? "Tools:\n  " + String.join("\n  ",
                        registry.listTools().stream().map(t -> t.name()).sorted().toList())
                : "");
        toolLabel.setVisible(toolCount > 0);
        toolLabel.setManaged(toolCount > 0);
    }

    private void startMailboxPoller() {
        mailboxPoller.scheduleWithFixedDelay(() -> {
            if (streaming) return;
            var notes = runtime.drainExternalNotifications();
            if (notes.isEmpty()) return;
            Platform.runLater(() -> {
                for (String note : notes) {
                    addMessage(ChatMessage.system(note));
                }
            });
        }, 2, 2, TimeUnit.SECONDS);
    }

    private static Label statusChip(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("status-chip");
        return l;
    }

    private static String formatTokens(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 10000) return String.format("%.1fK", n / 1000.0);
        return (n / 1000) + "K";
    }

    private static String shortenPath(String path) {
        if (path == null) return "";
        String home = System.getProperty("user.home");
        if (path.startsWith(home)) path = "~" + path.substring(home.length());
        if (path.length() > 36) {
            int keep = 16;
            return path.substring(0, keep) + "..." + path.substring(path.length() - keep);
        }
        return path;
    }

    private void chooseWorkingDir() {
        java.io.File dir = new java.io.File(System.getProperty("user.dir"));
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle("Choose Working Directory");
        if (dir.exists()) dc.setInitialDirectory(dir);
        java.io.File chosen = dc.showDialog(stage);
        if (chosen != null) {
            String newPath = chosen.getAbsolutePath();
            System.setProperty("user.dir", newPath);
            runtime.setWorkDir(newPath);
            workDirLabel.setText(shortenPath(newPath));
            workDirLabel.setTooltip(new Tooltip("Working directory: " + newPath + "\nClick to change"));
        }
    }

    // ── Slash command suggestion popup ──────────────────────────────

    private boolean isSlashTyping() {
        String text = inputField.getText();
        return text.startsWith("/") && !text.contains(" ");
    }

    private void updateSlashPopup() {
        String text = inputField.getText().trim();
        if (!text.startsWith("/") || text.contains(" ")) {
            hideSlashPopup();
            return;
        }
        String prefix = text.substring(1).toLowerCase();
        var matches = cmdRegistry.search(prefix);
        if (matches.isEmpty()) {
            hideSlashPopup();
            return;
        }
        slashList.getItems().setAll(matches.stream()
                .map(c -> "/" + c.name() + " — " + c.description())
                .toList());
        slashList.getSelectionModel().select(0);
        if (!slashPopup.isShowing()) {
            var bounds = inputField.localToScreen(inputField.getBoundsInLocal());
            slashPopup.show(inputField, bounds.getMinX(), bounds.getMaxY());
        }
    }

    private void hideSlashPopup() {
        slashPopup.hide();
        slashList.getItems().clear();
    }

    private void completeSlashCommand() {
        String text = inputField.getText().trim();
        if (!text.startsWith("/")) return;
        String prefix = text.substring(1).toLowerCase();
        var matches = cmdRegistry.search(prefix);
        if (matches.isEmpty()) {
            hideSlashPopup();
            return;
        }
        // Use selected item from list, or first match
        int idx = slashList.getSelectionModel().getSelectedIndex();
        if (idx < 0) idx = 0;
        var cmd = matches.get(Math.min(idx, matches.size() - 1));
        inputField.setText("/" + cmd.name() + " ");
        inputField.positionCaret(inputField.getText().length());
        hideSlashPopup();
    }

    private void scrollToBottom() {
        Platform.runLater(() -> {
            scrollPane.layout();
            scrollPane.setVvalue(1.0);
        });
    }

    private void navigateHistory(int direction) {
        if (inputHistory.isEmpty()) return;
        int newPos = historyPos + direction;
        if (newPos < 0) newPos = 0;
        if (newPos > inputHistory.size()) newPos = inputHistory.size();
        if (newPos == inputHistory.size()) {
            inputField.clear();
        } else {
            inputField.setText(inputHistory.get(newPos));
            inputField.positionCaret(inputField.getText().length());
        }
        historyPos = newPos;
    }

    private static final int HISTORY_ROUND_LIMIT = 20;

    private void switchSession(String sessionId) {
        if (streaming) {
            runtime.cancel();
            finishStreaming();
        }
        String result = runtime.resumeSession(sessionId);
        messageList.getChildren().clear();
        totalInputTokens = 0;
        totalOutputTokens = 0;
        titleGenerated = sidebar.getTitleStore().loadAll().containsKey(sessionId);
        updateStatus("ready");

        // Load and render recent conversation history
        var conv = runtime.getConversation();
        boolean hasMessages = false;
        if (conv != null) {
            var messages = conv.getMessages();
            hasMessages = !messages.isEmpty();
            // Count assistant messages to determine rounds, show last N rounds
            int assistantCount = 0;
            int startIdx = 0;
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("assistant".equals(messages.get(i).getRole())) {
                    assistantCount++;
                    if (assistantCount >= HISTORY_ROUND_LIMIT) {
                        startIdx = i;
                        break;
                    }
                }
            }
            if (startIdx > 0) {
                addMessage(ChatMessage.system("[Showing last ~"
                        + HISTORY_ROUND_LIMIT + " rounds. Full history in .licode/sessions/]"));
            }

            for (int i = startIdx; i < messages.size(); i++) {
                var msg = messages.get(i);
                String role = msg.getRole();
                String content = msg.getContent();

                if ("user".equals(role)) {
                    // Skip system reminders and tool result messages
                    if (content != null && content.startsWith("<system-reminder>")) continue;
                    if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) continue;
                    if (content != null && !content.isBlank()) {
                        addMessage(ChatMessage.user(content));
                    }
                } else if ("assistant".equals(role)) {
                    StringBuilder thinking = new StringBuilder();
                    if (msg.getThinkingBlocks() != null) {
                        for (var tb : msg.getThinkingBlocks()) {
                            if (tb.thinking() != null && !tb.thinking().isBlank()) {
                                if (!thinking.isEmpty()) thinking.append("\n");
                                thinking.append(tb.thinking());
                            }
                        }
                    }
                    if (!thinking.isEmpty()) {
                        addMessage(ChatMessage.thinking(thinking.toString()));
                    }
                    if (content != null && !content.isBlank()) {
                        addMessage(ChatMessage.assistant(content));
                    }

                    if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
                        for (var tu : msg.getToolUses()) {
                            StringBuilder tsb = new StringBuilder(tu.toolName());
                            if (tu.arguments() != null) {
                                for (var entry : tu.arguments().entrySet()) {
                                    if (!"thinking".equals(entry.getKey()) && !"description".equals(entry.getKey())) {
                                        tsb.append(" ").append(entry.getValue());
                                        break;
                                    }
                                }
                            }
                            addMessage(ChatMessage.system(tsb.toString()));
                        }
                    }
                }
            }
            scrollToBottom();
        }

        if (result != null && !hasMessages) {
            addMessage(ChatMessage.system(result));
        }

        sidebar.setActiveSession(sessionId);
        sidebar.refresh();
    }

    private void newSession() {
        if (streaming) {
            runtime.cancel();
            finishStreaming();
        }
        runtime.shutdown(); // persist pending memory extraction
        runtime.clearConversation(); // reset conversation + session ID
        messageList.getChildren().clear();
        totalInputTokens = 0;
        totalOutputTokens = 0;
        planModeActive = false;
        titleGenerated = false;
        updateStatus("ready");
        refreshStatusBar();
        sidebar.setActiveSession(null);
        sidebar.refresh();
    }

    private void enterPlanMode() {
        if (planModeActive) return;
        if (runtime.getPermissionChecker() != null) {
            prePlanPermissionMode = runtime.getPermissionChecker().getMode();
        }
        runtime.setPlanOnlyMode(true);
        runtime.setPermissionMode(PermissionMode.PLAN);
        planModeActive = true;
        refreshStatusBar();
        addMessage(ChatMessage.system("Entered Plan mode — read-only tools only. Use /do to exit."));
    }

    private void exitPlanMode() {
        if (!planModeActive) return;
        runtime.exitPlanMode(prePlanPermissionMode);
        planModeActive = false;
        refreshStatusBar();
        addMessage(ChatMessage.system("Exited Plan mode. Tool execution restored to normal."));
    }

    public LiRuntime getRuntime() {
        return runtime;
    }

    // Package access for sidebar
    SessionTitleStore getTitleStore() {
        return sidebar.getTitleStore();
    }
}
