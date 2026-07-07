package com.licode.tui;

import com.licode.command.CommandContext;
import com.licode.command.CommandRegistry;
import com.licode.config.McpServerConfig;
import com.licode.config.ProviderConfig;
import com.licode.hook.HookConfig;
import com.licode.hook.HookEngine;
import com.licode.llm.StreamCallback;
import com.licode.llm.StreamEvent;
import com.licode.permission.PermissionMode;
import com.licode.permission.PermissionResponse;
import com.licode.runtime.LiRuntime;
import com.licode.tool.ToolRegistry;
import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import com.williamcallahan.tui4j.compat.bubbletea.Model;
import com.williamcallahan.tui4j.compat.bubbletea.QuitMessage;
import com.williamcallahan.tui4j.compat.bubbletea.UpdateResult;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import com.williamcallahan.tui4j.compat.bubbletea.input.MouseMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.MouseButton;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LiCodeModel implements Model, StreamCallback {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final int MAX_HISTORY = 100;
    private static final String VERSION = "LiCode v1.0.0";

    // Providers
    private final List<ProviderConfig> providers;
    private final List<McpServerConfig> mcpServers;
    private AppState state = AppState.PROVIDER_SELECT;
    private ProviderConfig selectedProvider;
    private LiRuntime runtime;
    private ScheduledExecutorService cleanupExecutor;
    private int providerCursor = 0;
    private String providerError = null;

    // Display
    private int width = detectTerminalWidth();
    private int height = 24;

    // Viewport for scrollable chat message area
    private Viewport viewport;
    private boolean autoScroll = true;
    private static final int BANNER_FIXED_LINES = 7;

    // Chat display
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private final StringBuilder streamBuf = new StringBuilder();
    private final StringBuilder thinkingBuf = new StringBuilder();
    private boolean streaming = false;

    // Input
    private final StringBuilder inputBuf = new StringBuilder();
    private int cursorPos = 0;

    // History
    private final List<String> inputHistory = new ArrayList<>();
    private int historyPos = -1;

    // Stats
    private int totalInputTokens = 0;
    private int totalOutputTokens = 0;

    // Thinking toggle
    private int lastThinkingMsgIndex = -1;

    // Session resume
    private String resumeSessionId = null;

    // MCP status: updated by connectMcpServers callback, read by view
    private volatile String mcpStatus = "";

    // Hook system
    private List<HookConfig> hookConfigs;

    // Slash command system
    private CommandRegistry cmdRegistry;
    private boolean slashMenuOpen = false;
    private List<com.licode.command.Command> slashMatches = new ArrayList<>();
    private int slashCursor = 0;
    private PermissionMode prePlanMode = PermissionMode.DEFAULT;

    // Thinking spinner (mewCode style)
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int spinnerFrame = 0;
    private long thinkingStartMs = 0;
    private String thinkingVerb = "";

    // Thread-safe event buffer: filled by StreamCallback (virtual thread), drained by update (main thread)
    private final List<StreamEvent> pendingEvents = new ArrayList<>();
    private final List<String> pendingNotices = new ArrayList<>();
    private final Object eventLock = new Object();

    // Permission waiting state: when a tool needs confirmation, the future is completed on user response
    private volatile CompletableFuture<PermissionResponse> activePermissionFuture;
    private volatile String activePermissionDesc = "";

    public LiCodeModel(List<ProviderConfig> providers, List<McpServerConfig> mcpServers) {
        this.providers = providers != null ? providers : List.of();
        this.mcpServers = mcpServers != null ? mcpServers : List.of();
        this.cmdRegistry = new CommandRegistry();
        this.viewport = new Viewport("", width, 1);
        this.viewport.setMouseWheelEnabled(true);
        if (this.providers.size() == 1) {
            this.selectedProvider = this.providers.get(0);
            initializeRuntime();
            this.state = AppState.CHAT;
        }
    }

    public void setResumeSessionId(String sessionId) {
        this.resumeSessionId = sessionId;
    }

    public void setHookConfigs(List<HookConfig> hookConfigs) {
        this.hookConfigs = hookConfigs;
        if (hookConfigs != null && !hookConfigs.isEmpty() && this.runtime != null) {
            this.runtime.setHookConfigs(hookConfigs);
        }
    }

    private void initializeRuntime() {
        var registry = ToolRegistry.createDefault();
        this.runtime = LiRuntime.create(selectedProvider, registry);
        // Wire hooks config
        if (hookConfigs != null && !hookConfigs.isEmpty()) {
            this.runtime.setHookConfigs(hookConfigs);
        }
        // Wire skills to command registry
        this.runtime.wireSkillsToCommands(cmdRegistry);
        // Initialize sub-agent system
        this.runtime.initSubAgentSystem(java.nio.file.Path.of(System.getProperty("user.dir")), providers);
        // Start stale worktree cleanup (default: 3600s interval, 24h cutoff)
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "licode-stale-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.runtime.startStaleCleanup(cleanupExecutor, 3600, 24);
        // Async MCP connection with status callback
        if (!mcpServers.isEmpty()) {
            mcpStatus = ""; // show nothing until connecting starts
            this.runtime.connectMcpServers(mcpServers, status -> {
                if (status.equals("connecting")) {
                    mcpStatus = "connecting";
                } else if (status.startsWith("connected:")) {
                    var parts = status.split(":");
                    mcpStatus = "connected:" + parts[1] + ":" + parts[2];
                } else if (status.startsWith("error:")) {
                    mcpStatus = "error:" + status.substring(6);
                }
            });
        }
        // Resume session if requested
        if (resumeSessionId != null) {
            String result = runtime.resumeSession(resumeSessionId);
            if (result != null) {
                chatMessages.add(ChatMessage.system(result));
            }
        }

        // SESSION_START hook (after runtime fully initialized)
        fireHook(HookEngine.EventName.SESSION_START, null, null);
        // STARTUP hook (once after first initialization)
        fireHook(HookEngine.EventName.STARTUP, null, null);
    }

    @Override
    public Command init() {
        if (!mcpServers.isEmpty()) {
            return Command.tick(Duration.ofMillis(200), t -> new McpStatusTickMessage());
        }
        return Command.checkWindowSize();
    }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        // MCP status polling — keep ticking until connection resolves
        if (msg instanceof McpStatusTickMessage) {
            if (mcpStatus.isEmpty() || "connecting".equals(mcpStatus)) {
                return UpdateResult.from(this, Command.tick(Duration.ofMillis(200), t -> new McpStatusTickMessage()));
            }
            return UpdateResult.from(this);
        }
        // Team mailbox polling — wake Lead when teammates send messages
        if (msg instanceof MailboxPollMessage) {
            return handleMailboxPoll();
        }
        // Window resize
        if (msg instanceof WindowSizeMessage wsm) {
            this.width = wsm.width();
            this.height = wsm.height();
            this.viewport.setWidth(wsm.width());
            return UpdateResult.from(this);
        }

        // Quit / Ctrl+C
        if (msg instanceof QuitMessage || (msg instanceof KeyPressMessage k && k.key().equals("ctrl+c"))) {
            if (state == AppState.CHAT && streaming) {
                streaming = false;
                savePartialResponse();
                runtime.cancel();
                return UpdateResult.from(this);
            }
            if (runtime != null) {
                // SESSION_END + SHUTDOWN hooks
                fireHook(HookEngine.EventName.SESSION_END, null, null);
                fireHook(HookEngine.EventName.SHUTDOWN, null, null);
                runtime.shutdownMcp();
                runtime.shutdownWorktree();
                if (cleanupExecutor != null) {
                    cleanupExecutor.shutdown();
                }
            }
            return UpdateResult.from(this, QuitMessage::new);
        }

        if (state == AppState.PROVIDER_SELECT) {
            return updateProviderSelect(msg);
        }
        return updateChat(msg);
    }

    private UpdateResult<LiCodeModel> updateProviderSelect(Message msg) {
        if (msg instanceof KeyPressMessage kpm) {
            return handleProviderSelectKey(kpm);
        }
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleProviderSelectKey(KeyPressMessage kpm) {
        String key = kpm.key();
        return switch (key) {
            case "up", "k" -> {
                if (providerCursor > 0) providerCursor--;
                providerError = null;
                yield UpdateResult.from(this);
            }
            case "down", "j" -> {
                if (providerCursor < providers.size() - 1) providerCursor++;
                providerError = null;
                yield UpdateResult.from(this);
            }
            case "enter" -> {
                if (providers.isEmpty()) yield UpdateResult.from(this);
                providerError = null;
                try {
                    selectedProvider = providers.get(providerCursor);
                    initializeRuntime();
                    state = AppState.CHAT;
                } catch (Exception e) {
                    providerError = e.getMessage();
                }
                yield UpdateResult.from(this);
            }
            default -> UpdateResult.from(this);
        };
    }

    private UpdateResult<LiCodeModel> updateChat(Message msg) {
        // RESUME state handling
        if (state == AppState.RESUME) {
            if (msg instanceof KeyPressMessage kpm) {
                String key = kpm.key();
                if (key.equals("esc")) {
                    state = AppState.CHAT;
                    return UpdateResult.from(this);
                }
                if (key.equals("enter")) {
                    // Placeholder: resume logic will be enhanced later
                    state = AppState.CHAT;
                    chatMessages.add(ChatMessage.system("Session resume will be implemented in a future update."));
                    return UpdateResult.from(this);
                }
            }
            return UpdateResult.from(this);
        }

        // Mouse wheel: delegate to viewport (Viewport.update() does not handle MouseMessage)
        if (msg instanceof MouseMessage mouseMsg && mouseMsg.isWheel()) {
            int delta = viewport.getMouseWheelDelta();
            switch (mouseMsg.getButton()) {
                case MouseButtonWheelUp -> viewport.scrollUp(delta);
                case MouseButtonWheelDown -> viewport.scrollDown(delta);
                default -> {}
            }
            autoScroll = viewport.atBottom();
            return UpdateResult.from(this);
        }

        // Streaming events
        if (msg instanceof StreamTickMessage) {
            return processStreamEvents();
        }

        // Key press
        if (msg instanceof KeyPressMessage kpm) {
            return handleKeyPress(kpm);
        }

        return UpdateResult.from(this);
    }

    @Override
    public String view() {
//        String clearScreen = "\033[2J\033[H"; // ANSI 清屏并移动光标到左上角
        if (state == AppState.PROVIDER_SELECT) {
            return viewProviderSelect();
        }
        if (state == AppState.RESUME) {
            return viewResume();
        }
        return viewChat();
    }

    // ═══════════════════════════════════════════════════════════
    // StreamCallback implementation (called from virtual thread)
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onTextDelta(String text) {
        synchronized (eventLock) {
            pendingEvents.add(new StreamEvent.TextDelta(text));
        }
    }

    @Override
    public void onThinkingDelta(String text) {
        synchronized (eventLock) {
            pendingEvents.add(new StreamEvent.ThinkingDelta(text));
        }
    }

    @Override
    public void onThinkingComplete(String thinking, String signature) {
        synchronized (eventLock) {
            pendingEvents.add(new StreamEvent.ThinkingComplete(thinking, signature));
        }
    }

    @Override
    public void onToolCallStart(String toolCallId, String toolName) {
        synchronized (eventLock) {
            pendingEvents.add(new StreamEvent.ToolCallStart(toolCallId, toolName));
        }
    }

    @Override
    public void onToolCallDelta(String toolCallId, String argumentsDelta) {
        synchronized (eventLock) {
            pendingEvents.add(new StreamEvent.ToolCallDelta(toolCallId, argumentsDelta));
        }
    }

    @Override
    public void onToolCallComplete(String toolCallId, String toolName, Map<String, Object> arguments) {
        synchronized (eventLock) {
            pendingEvents.add(new StreamEvent.ToolCallComplete(toolCallId, toolName, arguments));
        }
    }

    @Override
    public void onError(String message) {
        synchronized (eventLock) {
            pendingEvents.add(new StreamEvent.Error(message));
        }
    }

    @Override
    public void onNotice(String message) {
        synchronized (eventLock) {
            pendingNotices.add(message);
        }
    }

    @Override
    public void onComplete(String stopReason, int inputTokens, int outputTokens) {
        synchronized (eventLock) {
            pendingEvents.add(new StreamEvent.StreamEnd(stopReason, inputTokens, outputTokens));
        }
    }

    @Override
    public void onPermissionRequest(String toolId, String toolName, String description,
                                     CompletableFuture<PermissionResponse> future) {
        synchronized (eventLock) {
            activePermissionFuture = future;
            activePermissionDesc = toolName + ": " + description;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Banner & provider selection
    // ═══════════════════════════════════════════════════════════

    private String renderBanner() {
        String modelName = selectedProvider != null ? selectedProvider.getModel() : "";
        String workDir = System.getProperty("user.dir");
        String mcpLine = renderMcpStatus();

        String base = Styles.banner.render("  _       ___     ____    ___    ____    _____  ") + "\n"
                    + Styles.banner.render(" | |     |_ _|   / ___|  / _ \\  |  _ \\  | ____| ") + Styles.bannerDim.render(VERSION) + "\n"
                    + Styles.banner.render(" | |      | |   | |     | | | | | | | | |  _|   ") + Styles.bannerDim.render(modelName) + "\n"
                    + Styles.banner.render(" | |___   | |   | |___  | |_| | | |_| | | |___  ") + Styles.bannerDim.render(workDir) + "\n"
                    + Styles.banner.render(" |_____| |___|   \\____|  \\___/  |____/  |_____| ") + "\n\n";

        // MCP status line (appears on the "U" line)

        if (!mcpLine.isEmpty()) {
            base += "  " + mcpLine + "\n";
        } else {
            base += "\n";
        }
        return base;
    }

    private String renderMcpStatus() {
        if (mcpStatus == null || mcpStatus.isEmpty()) return "";
        if (mcpStatus.equals("connecting")) {
            return Styles.inlineCyan.render("MCP: connecting...");
        }
        if (mcpStatus.startsWith("connected:")) {
            var parts = mcpStatus.split(":");
            String servers = parts[1];
            String tools = parts[2];
            return Styles.inlineGreen.render("MCP: " + servers + " server(s) ✓ " + tools + " tools");
        }
        if (mcpStatus.startsWith("error:")) {
            return Styles.inlineRed.render("MCP: " + mcpStatus.substring(6));
        }
        return "";
    }

    private String viewProviderSelect() {
        var sb = new StringBuilder();
        sb.append("\n");
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(Styles.selectLabel.render("  Select a Provider"));
        sb.append("\n\n");

        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            String label = p.getName() + " (" + p.getModel() + ")";
            if (i == providerCursor) {
                sb.append(Styles.selectedItem.render("  ❯ " + label));
            } else {
                sb.append(Styles.normalItem.render("    " + label));
            }
            sb.append("\n");
        }
        if (providerError != null) {
            sb.append("\n");
            sb.append(Styles.errorText.render("  ✖ " + providerError));
            sb.append("\n");
        }
        sb.append("\n");
        sb.append(Styles.statusBar.render("  j/k to move · enter to select · ctrl+c to quit"));
        sb.append("\n");
        return sb.toString();
    }

    private String viewResume() {
        var sb = new StringBuilder();
        sb.append("\n");
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(Styles.selectLabel.render("  Resume Session"));
        sb.append("\n\n");
        sb.append(Styles.normalItem.render("  Press Enter to resume latest session, Esc to go back"));
        sb.append("\n\n");
        sb.append(Styles.statusBar.render("  enter to resume · esc to cancel"));
        sb.append("\n");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    // Key handling
    // ═══════════════════════════════════════════════════════════

    private UpdateResult<LiCodeModel> handleViewportScrollKey(String key) {
        return switch (key) {
            case "pgup" -> {
                viewport.pageUp();
                autoScroll = viewport.atBottom();
                yield UpdateResult.from(this);
            }
            case "pgdn" -> {
                viewport.pageDown();
                autoScroll = viewport.atBottom();
                yield UpdateResult.from(this);
            }
            case "ctrl+u" -> {
                int n = Math.max(1, viewport.visibleLineCount() / 2);
                viewport.scrollUp(n);
                autoScroll = viewport.atBottom();
                yield UpdateResult.from(this);
            }
            case "ctrl+d" -> {
                int n = Math.max(1, viewport.visibleLineCount() / 2);
                viewport.scrollDown(n);
                autoScroll = viewport.atBottom();
                yield UpdateResult.from(this);
            }
            case "ctrl+f" -> {
                viewport.pageDown();
                autoScroll = viewport.atBottom();
                yield UpdateResult.from(this);
            }
            case "ctrl+b" -> {
                viewport.pageUp();
                autoScroll = viewport.atBottom();
                yield UpdateResult.from(this);
            }
            default -> null;
        };
    }

    private UpdateResult<LiCodeModel> handleKeyPress(KeyPressMessage kpm) {
        String key = kpm.key();

        // Slash menu navigation (when menu is open, intercept navigation keys)
        if (slashMenuOpen) {
            return switch (key) {
                case "up" -> {
                    if (slashCursor > 0) slashCursor--;
                    yield UpdateResult.from(this);
                }
                case "down" -> {
                    if (slashCursor < slashMatches.size() - 1) slashCursor++;
                    yield UpdateResult.from(this);
                }
                case "enter", "tab" -> {
                    if (!slashMatches.isEmpty()) {
                        var cmd = slashMatches.get(slashCursor);
                        inputBuf.setLength(0);
                        inputBuf.append("/").append(cmd.name()).append(" ");
                        cursorPos = inputBuf.length();
                    }
                    slashMenuOpen = false;
                    slashMatches = new ArrayList<>();
                    yield UpdateResult.from(this);
                }
                case "esc" -> {
                    slashMenuOpen = false;
                    slashMatches = new ArrayList<>();
                    yield UpdateResult.from(this);
                }
                case "backspace", "ctrl+h" -> {
                    handleBackspace();
                    updateSlashMenu();
                    yield UpdateResult.from(this);
                }
                default -> {
                    // Continue typing: append character and refresh menu
                    char[] runes = kpm.runes();
                    if (runes != null && runes.length > 0) {
                        String text = new String(runes).replaceAll("[\\x00-\\x1f&&[^\\n]]", "");
                        if (!text.isEmpty()) {
                            int idx = Math.min(cursorPos, inputBuf.length());
                            inputBuf.insert(idx, text);
                            cursorPos += text.length();
                            updateSlashMenu();
                        }
                    }
                    yield UpdateResult.from(this);
                }
            };
        }

        // Viewport scroll keys (always intercepted, never consumed by input)
        var scrollResult = handleViewportScrollKey(key);
        if (scrollResult != null) return scrollResult;

        return switch (key) {
            case "enter" -> handleSend();
            case "tab" -> handleTabComplete();
            case "backspace", "ctrl+h" -> {
                var r = handleBackspace();
                updateSlashMenu();
                yield r;
            }
            case "up" -> {
                if (inputBuf.isEmpty()) {
                    viewport.scrollUp(1);
                    autoScroll = viewport.atBottom();
                    yield UpdateResult.from(this);
                }
                yield handleHistoryUp();
            }
            case "down" -> {
                if (inputBuf.isEmpty()) {
                    viewport.scrollDown(1);
                    autoScroll = viewport.atBottom();
                    yield UpdateResult.from(this);
                }
                yield handleHistoryDown();
            }
            case "left" -> handleCursorLeft();
            case "right" -> handleCursorRight();
            case "home" -> handleCursorHome();
            case "end" -> handleCursorEnd();
            case "ctrl+o" -> handleToggleThinking();
            case "ctrl+l" -> handleClearScreen();
            default -> {
                var r = handleTextInput(kpm);
                updateSlashMenu();
                yield r;
            }
        };
    }

    private UpdateResult<LiCodeModel> handleSend() {
        // Permission response: intercept even when streaming
        if (activePermissionFuture != null && !inputBuf.isEmpty()) {
            String text = inputBuf.toString().trim();
            inputBuf.setLength(0);
            cursorPos = 0;
            historyPos = -1;

            PermissionResponse response;
            String lower = text.toLowerCase();
            if (lower.equals("y") || lower.equals("yes")) {
                response = PermissionResponse.ALLOW;
                chatMessages.add(ChatMessage.system("✓ Allowed: " + activePermissionDesc));
            } else if (lower.equals("n") || lower.equals("no")) {
                response = PermissionResponse.DENY;
                chatMessages.add(ChatMessage.system("✖ Denied: " + activePermissionDesc));
            } else if (lower.equals("a") || lower.equals("always")) {
                response = PermissionResponse.ALLOW_ALWAYS;
                chatMessages.add(ChatMessage.system("✓ Allowed (always): " + activePermissionDesc));
            } else {
                // Treat unknown single-char responses as deny
                response = PermissionResponse.DENY;
                chatMessages.add(ChatMessage.system("✖ Denied: " + activePermissionDesc));
            }
            activePermissionFuture.complete(response);
            activePermissionFuture = null;
            activePermissionDesc = "";
            return UpdateResult.from(this);
        }

        if (streaming || inputBuf.isEmpty()) return UpdateResult.from(this);

        String text = inputBuf.toString().trim();
        inputBuf.setLength(0);
        cursorPos = 0;

        // Bypass detection — ! prefix skips pre_tool_use hooks
        boolean bypass = false;
        if (text.startsWith("!")) {
            bypass = true;
            text = text.substring(1).trim();
            if (text.isEmpty()) return UpdateResult.from(this);
        }

        // Save to history
        if (inputHistory.isEmpty() || !inputHistory.getLast().equals(text)) {
            inputHistory.add(text);
            if (inputHistory.size() > MAX_HISTORY) inputHistory.removeFirst();
        }
        historyPos = -1;

        // Slash command dispatch
        if (text.startsWith("/")) {
            String[] parts = text.substring(1).split("\\s+", 2);
            String cmdName = parts[0];
            String cmdArgs = parts.length > 1 ? parts[1] : "";
            var cmd = cmdRegistry.find(cmdName);
            if (cmd.isPresent()) {
                return executeSlashCommand(cmd.get(), cmdArgs);
            }
            chatMessages.add(ChatMessage.system(
                    "Unknown command: /" + cmdName + " — type /help to see available commands"));
            return UpdateResult.from(this);
        }

        // Add to display
        chatMessages.add(ChatMessage.user(text));

        // TURN_START hook (before agent starts)
        fireHook(HookEngine.EventName.TURN_START, null, null);

        // Send to LLM via runtime
        streaming = true;
        autoScroll = true;
        streamBuf.setLength(0);
        thinkingBuf.setLength(0);
        pendingEvents.clear();
        thinkingStartMs = System.currentTimeMillis();
        thinkingVerb = SpinnerVerbs.random();
        runtime.ask(text, this, bypass);

        var pollCmd = Command.tick(POLL_INTERVAL, t -> new StreamTickMessage());
        return UpdateResult.from(this, pollCmd);
    }

    // ═══════════════════════════════════════════════════════════
    // Tab completion
    // ═══════════════════════════════════════════════════════════

    private UpdateResult<LiCodeModel> handleTabComplete() {
        String text = inputBuf.toString();
        if (!text.startsWith("/") || text.contains(" ")) {
            return UpdateResult.from(this);
        }

        String prefix = text.substring(1);
        var matches = cmdRegistry.search(prefix);

        if (matches.isEmpty()) {
            return UpdateResult.from(this);
        }

        if (matches.size() == 1) {
            // Single match: complete directly to buffer
            inputBuf.setLength(0);
            inputBuf.append("/").append(matches.get(0).name()).append(" ");
            cursorPos = inputBuf.length();
            slashMenuOpen = false;
            slashMatches = new ArrayList<>();
        } else {
            // Multiple matches: show popup list
            slashMatches = new ArrayList<>(matches);
            slashCursor = 0;
            slashMenuOpen = true;
        }
        return UpdateResult.from(this);
    }

    private void updateSlashMenu() {
        String text = inputBuf.toString();
        if (text.startsWith("/") && !text.contains(" ")) {
            String prefix = text.substring(1);
            var matches = cmdRegistry.search(prefix);
            if (!matches.isEmpty()) {
                slashMatches = new ArrayList<>(matches);
                slashCursor = 0;
                slashMenuOpen = true;
                return;
            }
        }
        slashMenuOpen = false;
        slashMatches = new ArrayList<>();
    }

    // ═══════════════════════════════════════════════════════════
    // Slash command execution
    // ═══════════════════════════════════════════════════════════

    private UpdateResult<LiCodeModel> executeSlashCommand(com.licode.command.Command cmd, String args) {
        return switch (cmd.type()) {
            case LOCAL -> {
                var ctx = buildCommandContext(args);
                String result = cmdRegistry.execute(cmd.name(), ctx);
                if (result != null && !result.isEmpty()) {
                    chatMessages.add(ChatMessage.system(result));
                }
                yield UpdateResult.from(this);
            }
            case LOCAL_UI -> {
                var ctx = buildCommandContext(args);
                yield switch (cmd.name()) {
                    case "clear" -> {
                        ctx.clearChat().run();
                        yield UpdateResult.from(this);
                    }
                    case "compact" -> {
                        ctx.triggerCompact().run();
                        yield UpdateResult.from(this);
                    }
                    case "plan" -> {
                        ctx.switchToPlanMode().run();
                        chatMessages.add(ChatMessage.system(
                                "Entered Plan mode. Plan file will be created on first tool use."));
                        yield UpdateResult.from(this);
                    }
                    case "do" -> {
                        ctx.switchToDefaultMode().run();
                        chatMessages.add(ChatMessage.system(
                                "Exited Plan mode. Tool execution restored to normal."));
                        yield UpdateResult.from(this);
                    }
                    case "resume" -> {
                        ctx.enterResumeState().run();
                        yield UpdateResult.from(this);
                    }
                    default -> UpdateResult.from(this);
                };
            }
            case PROMPT -> {
                var ctx = buildCommandContext(args);
                String prompt = cmdRegistry.execute(cmd.name(), ctx);
                if (prompt != null && !prompt.isEmpty()) {
                    chatMessages.add(ChatMessage.user("/" + cmd.name()));
                    if (cmd.description() != null && cmd.description().endsWith("[skill]")) {
                        chatMessages.add(ChatMessage.system(
                                "skill(" + cmd.name() + ") Successfully loaded skill"));
                    }
                    // Inject prompt into conversation and trigger agent
                    runtime.getConversation().addUserMessage(prompt);
                    if (args != null && !args.isBlank()) {
                        runtime.getConversation().addUserMessage(args);
                    }
                    streaming = true;
                    autoScroll = true;
                    streamBuf.setLength(0);
                    thinkingBuf.setLength(0);
                    pendingEvents.clear();
                    thinkingStartMs = System.currentTimeMillis();
                    thinkingVerb = SpinnerVerbs.random();
                    runtime.ask(null, this);
                    var pollCmd = Command.tick(POLL_INTERVAL, t -> new StreamTickMessage());
                    yield UpdateResult.from(this, pollCmd);
                }
                yield UpdateResult.from(this);
            }
            case SKILL_FORK -> {
                // Fork skill: run in an isolated sub-agent, stream progress here.
                chatMessages.add(ChatMessage.user("/" + cmd.name()));
                chatMessages.add(ChatMessage.system(
                        "skill(" + cmd.name() + ") running in isolated sub-agent…"));
                streaming = true;
                autoScroll = true;
                streamBuf.setLength(0);
                thinkingBuf.setLength(0);
                pendingEvents.clear();
                thinkingStartMs = System.currentTimeMillis();
                thinkingVerb = SpinnerVerbs.random();
                runtime.askForkSkill(cmd.name(), args, this);
                var pollCmd = Command.tick(POLL_INTERVAL, t -> new StreamTickMessage());
                yield UpdateResult.from(this, pollCmd);
            }
        };
    }

    private CommandContext buildCommandContext(String args) {
        return new CommandContext(
                args,
                System.getProperty("user.dir"),
                () -> selectedProvider != null ? selectedProvider.getModel() : "unknown",
                () -> runtime != null && runtime.getPermissionChecker() != null
                        ? runtime.getPermissionChecker().getMode().name().toLowerCase()
                        : "default",
                () -> runtime != null && runtime.getToolRegistry() != null
                        ? runtime.getToolRegistry().listTools().size()
                        : 0,
                () -> totalInputTokens,
                () -> totalOutputTokens,
                () -> runtime != null && runtime.getMemoryManager() != null
                        ? runtime.getMemoryManager().getMemories()
                        : List.of(),
                () -> {
                    if (runtime != null && runtime.getMemoryManager() != null) {
                        runtime.getMemoryManager().clear();
                    }
                },
                () -> {
                    if (runtime != null) {
                        String sid = runtime.getCurrentSessionId();
                        int count = runtime.getConversation().getMessages().size();
                        if (sid != null) return "Session: " + sid + " (" + count + " messages)";
                    }
                    return "No active session";
                },
                () -> {
                    var skills = new ArrayList<String>();
                    for (var c : cmdRegistry.listVisible()) {
                        if (c.description() != null && c.description().endsWith("[skill]")) {
                            skills.add(c.name());
                        }
                    }
                    return skills;
                },
                // clearChat
                () -> {
                    chatMessages.clear();
                    lastThinkingMsgIndex = -1;
                    if (runtime != null) runtime.clearConversation();
                },
                // triggerCompact
                () -> {
                    if (runtime != null) {
                        String result = runtime.compact();
                        chatMessages.add(ChatMessage.system(result));
                    }
                },
                // switchToPlanMode
                () -> {
                    if (runtime != null) {
                        if (runtime.getPermissionChecker() != null) {
                            prePlanMode = runtime.getPermissionChecker().getMode();
                        }
                        runtime.setPlanOnlyMode(true);
                        runtime.setPermissionMode(PermissionMode.PLAN);
                    }
                },
                // switchToDefaultMode
                () -> {
                    if (runtime != null) {
                        runtime.exitPlanMode(prePlanMode);
                    }
                },
                // enterResumeState
                () -> {
                    state = AppState.RESUME;
                }
        );
    }

    private UpdateResult<LiCodeModel> handleBackspace() {
        if (inputBuf.isEmpty()) return UpdateResult.from(this);
        if (cursorPos <= 0) return UpdateResult.from(this);
        int idx = Math.min(cursorPos, inputBuf.length());
        if (idx > 0) {
            inputBuf.deleteCharAt(idx - 1);
            cursorPos--;
        }
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleHistoryUp() {
        if (inputHistory.isEmpty()) return UpdateResult.from(this);
        if (historyPos == -1) {
            historyPos = inputHistory.size() - 1;
        } else if (historyPos > 0) {
            historyPos--;
        }
        inputBuf.setLength(0);
        inputBuf.append(inputHistory.get(historyPos));
        cursorPos = inputBuf.length();
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleHistoryDown() {
        if (historyPos == -1) return UpdateResult.from(this);
        historyPos++;
        if (historyPos >= inputHistory.size()) {
            historyPos = -1;
            inputBuf.setLength(0);
            cursorPos = 0;
        } else {
            inputBuf.setLength(0);
            inputBuf.append(inputHistory.get(historyPos));
            cursorPos = inputBuf.length();
        }
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleCursorLeft() {
        if (cursorPos > 0) cursorPos--;
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleCursorRight() {
        if (cursorPos < inputBuf.length()) cursorPos++;
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleCursorHome() {
        cursorPos = 0;
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleCursorEnd() {
        cursorPos = inputBuf.length();
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleToggleThinking() {
        int idx = lastThinkingMsgIndex;
        if (idx < 0 || idx >= chatMessages.size()) return UpdateResult.from(this);
        ChatMessage msg = chatMessages.get(idx);
        if (msg.getThinkingContent() == null || msg.getThinkingContent().isEmpty()) return UpdateResult.from(this);
        msg.setThinkingExpanded(!msg.isThinkingExpanded());
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleClearScreen() {
        chatMessages.clear();
        lastThinkingMsgIndex = -1;
        runtime.clearConversation();
        return UpdateResult.from(this);
    }

    private UpdateResult<LiCodeModel> handleTextInput(KeyPressMessage kpm) {
        char[] runes = kpm.runes();
        if (runes == null || runes.length == 0) return UpdateResult.from(this);

        // Filter control characters
        String text = new String(runes).replaceAll("[\\x00-\\x1f&&[^\\n]]", "");
        if (text.isEmpty()) return UpdateResult.from(this);
        int idx = Math.min(cursorPos, inputBuf.length());
        inputBuf.insert(idx, text);
        cursorPos += text.length();
        return UpdateResult.from(this);
    }

    // ═══════════════════════════════════════════════════════════
    // Streaming event handling
    // ═══════════════════════════════════════════════════════════

    private UpdateResult<LiCodeModel> processStreamEvents() {
        if (!streaming) return UpdateResult.from(this);

        // Advance spinner frame
        spinnerFrame++;

        List<StreamEvent> events;
        List<String> notices;
        synchronized (eventLock) {
            events = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
            notices = new ArrayList<>(pendingNotices);
            pendingNotices.clear();
        }
        for (String notice : notices) {
            chatMessages.add(ChatMessage.system(notice));
        }

        for (var event : events) {
            switch (event) {
                case StreamEvent.TextDelta td -> streamBuf.append(td.text());
                case StreamEvent.ThinkingDelta td -> thinkingBuf.append(td.text());
                case StreamEvent.ThinkingComplete tc -> {
                    // thinking content stored in thinkingBuf; signature unused in TUI
                }
                case StreamEvent.StreamEnd se -> {
                    // Thinking-complete line (mewCode style: "✻ Thought for 12.3s")
                    double elapsed = (System.currentTimeMillis() - thinkingStartMs) / 1000.0;
                    String pastVerb = SpinnerVerbs.pastTense(thinkingVerb);
                    chatMessages.add(ChatMessage.thinkingStatus(
                            "✻ " + pastVerb + " for " + String.format("%.1f", elapsed) + "s"));
                    // Commit thinking
                    ChatMessage msg;
                    if (!thinkingBuf.isEmpty()) {
                        msg = ChatMessage.thinking(thinkingBuf.toString());
                        msg.setThinkingContent(thinkingBuf.toString());
                        chatMessages.add(msg);
                        lastThinkingMsgIndex = chatMessages.size() - 1;
                    }
                    // Commit streaming text
                    String text = streamBuf.toString();
                    if (!text.isEmpty()) {
                        msg = ChatMessage.assistant(text);
                        chatMessages.add(msg);
                    }
                    // Update stats
                    totalInputTokens += se.inputTokens();
                    totalOutputTokens += se.outputTokens();
                    streaming = false;
                    streamBuf.setLength(0);
                    thinkingBuf.setLength(0);
                }
                case StreamEvent.Error err -> {
                    chatMessages.add(ChatMessage.error(err.message()));
                    streaming = false;
                    streamBuf.setLength(0);
                    thinkingBuf.setLength(0);
                }
                // Tool events are no-ops in TUI for now
                case StreamEvent.ToolCallStart ignored -> {}
                case StreamEvent.ToolCallDelta ignored -> {}
                case StreamEvent.ToolCallComplete ignored -> {}
            }
        }

        if (streaming) {
            var pollCmd = Command.tick(POLL_INTERVAL, t -> new StreamTickMessage());
            return UpdateResult.from(this, pollCmd);
        }
        // Start team mailbox poll if active teams exist
        if (hasActiveTeams()) {
            var pollCmd = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
            return UpdateResult.from(this, pollCmd);
        }
        return UpdateResult.from(this);
    }

    // ═══════════════════════════════════════════════════════════
    // Team mailbox polling
    // ═══════════════════════════════════════════════════════════

    private UpdateResult<LiCodeModel> handleMailboxPoll() {
        if (streaming) {
            return UpdateResult.from(this, Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage()));
        }
        if (runtime == null || runtime.getTeamManager() == null) {
            return UpdateResult.from(this);
        }
        if (!hasActiveTeams()) {
            return UpdateResult.from(this);
        }
        var notes = runtime.drainExternalNotifications();
        if (notes.isEmpty()) {
            return UpdateResult.from(this, Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage()));
        }
        for (String note : notes) {
            runtime.getConversation().addSystemReminder(note);
        }
        streaming = true;
        autoScroll = true;
        streamBuf.setLength(0);
        thinkingBuf.setLength(0);
        pendingEvents.clear();
        thinkingStartMs = System.currentTimeMillis();
        thinkingVerb = SpinnerVerbs.random();
        runtime.ask(null, this);
        return UpdateResult.from(this, Command.tick(POLL_INTERVAL, t -> new StreamTickMessage()));
    }

    private boolean hasActiveTeams() {
        if (runtime == null || runtime.getTeamManager() == null) return false;
        for (var team : runtime.getTeamManager().listTeams()) {
            for (var member : team.getMembers()) {
                if (member.active()) return true;
            }
        }
        return false;
    }

    private void savePartialResponse() {
        String text = streamBuf.toString();
        String thinking = thinkingBuf.toString();
        if (!thinking.isEmpty()) {
            var msg = ChatMessage.thinking(thinking);
            msg.setThinkingContent(thinking);
            chatMessages.add(msg);
            lastThinkingMsgIndex = chatMessages.size() - 1;
        }
        if (!text.isEmpty()) {
            chatMessages.add(ChatMessage.assistant(text + " [interrupted]"));
        }
        streamBuf.setLength(0);
        thinkingBuf.setLength(0);
    }

    // ═══════════════════════════════════════════════════════════
    // View rendering
    // ═══════════════════════════════════════════════════════════

    private static final int MAX_VISIBLE_MESSAGES = 500;

    // ═══════════════════════════════════════════════════════════
    // Composable view rendering (Viewport-based layout)
    // ═══════════════════════════════════════════════════════════

    private String viewChat() {
        var sb = new StringBuilder();

        // Region 1: Banner (always 7 lines, fixed)
        sb.append(renderBanner());
        sb.append('\n');

        // Calculate bottom region height
        int inputLines = calculateInputVisualLines();
        int slashLines = calculateSlashMenuLines();
        int bottomHeight = 3 + inputLines + slashLines; // separator1(1) + input(N) + slash(M) + separator2(1) + status(1)

        // Region 2: Viewport — scrollable chat area fills remaining space
        int viewportHeight = Math.max(1, height - BANNER_FIXED_LINES - 1 - bottomHeight);
        String chatContent = buildChatContent();
        viewport.setWidth(width);
        viewport.setHeight(viewportHeight);
        viewport.setContent(chatContent);
        if (autoScroll) {
            viewport.gotoBottom();
        }
        sb.append(viewport.view());

        // Region 3: Bottom — separator + input + slash menu + separator + status bar
        sb.append(Styles.separator.render("─".repeat(Math.max(1, width))));
        sb.append('\n');

        renderInputArea(sb);
        renderSlashMenuArea(sb);

        sb.append(Styles.separator.render("─".repeat(Math.max(1, width))));
        sb.append('\n');

        renderStatusBarArea(sb);

        return sb.toString();
    }

    private String buildChatContent() {
        var sb = new StringBuilder();

        // Chat messages — render from a sliding window to keep view bounded
        int msgTotal = chatMessages.size();
        int msgStart = Math.max(0, msgTotal - MAX_VISIBLE_MESSAGES);

        if (msgStart > 0) {
            sb.append(Styles.systemText.render("  ... " + msgStart + " messages above ..."));
            sb.append('\n');
        }
        for (int i = msgStart; i < msgTotal; i++) {
            renderMessage(sb, chatMessages.get(i));
        }

        // Streaming content
        if (streaming) {
            String frame = SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length];
            double elapsed = (System.currentTimeMillis() - thinkingStartMs) / 1000.0;
            sb.append(Styles.thinkingLabel.render("  " + frame + " " + thinkingVerb + "…  (" + String.format("%.0f", elapsed) + "s)"));
            sb.append('\n');

            if (!thinkingBuf.isEmpty()) {
                sb.append(Styles.thinkingContent.render("  " + thinkingBuf.toString()));
                sb.append('\n');
            }
            if (!streamBuf.isEmpty()) {
                sb.append(Styles.aiMarker.render("● "));
                sb.append(Styles.aiText.render(streamBuf.toString()));
                sb.append(Styles.streamingDot.render(" ●"));
                sb.append('\n');
            }
        }

        // Permission prompt (below chat history and streaming content, above input)
        CompletableFuture<PermissionResponse> permFuture;
        String permDesc;
        synchronized (eventLock) {
            permFuture = activePermissionFuture;
            permDesc = activePermissionDesc;
        }
        if (permFuture != null) {
            sb.append('\n');
            sb.append(Styles.thinkingLabel.render("  ══ Permission Required ══"));
            sb.append('\n');
            sb.append(Styles.normalItem.render("  " + permDesc));
            sb.append('\n');
            sb.append(Styles.selectedItem.render("  [y] allow once  [n] deny  [a] allow always"));
            sb.append('\n');
            sb.append('\n');
        }

        return sb.toString();
    }

    private int calculateInputVisualLines() {
        if (inputBuf.isEmpty() && !streaming) {
            return 1; // placeholder "Send a message..."
        }
        String fullInput = inputBuf.toString();
        int wrapWidth = Math.max(width - 2, 20);
        String[] logicalLines = fullInput.split("\n", -1);
        int count = 0;
        for (String logLine : logicalLines) {
            if (logLine.isEmpty()) {
                count++;
            } else {
                count += wrapLines(logLine, wrapWidth).size();
            }
        }
        return Math.max(1, count);
    }

    private int calculateSlashMenuLines() {
        if (!slashMenuOpen || slashMatches.isEmpty()) return 0;
        int total = slashMatches.size();
        if (total <= 8) return total;
        int start = Math.max(0, Math.min(slashCursor - 3, total - 8));
        int end = Math.min(total, start + 8);
        int lines = end - start;
        if (start > 0) lines++;
        if (end < total) lines++;
        return lines;
    }

    private void renderInputArea(StringBuilder sb) {
        if (inputBuf.isEmpty() && !streaming) {
            sb.append(Styles.prompt.render("❯ "));
            sb.append(Styles.placeholder.render("Send a message..."));
            sb.append('\n');
        } else {
            String fullInput = inputBuf.toString();
            int wrapWidth = Math.max(width - 2, 20);

            String[] logicalLines = fullInput.split("\n", -1);
            var visualLines = new java.util.ArrayList<String>();
            var lineStartPos = new java.util.ArrayList<Integer>();
            int pos = 0;
            for (String logLine : logicalLines) {
                int start = pos;
                if (logLine.isEmpty()) {
                    visualLines.add("");
                    lineStartPos.add(start);
                    pos++;
                } else {
                    for (String wl : wrapLines(logLine, wrapWidth)) {
                        visualLines.add(wl);
                        lineStartPos.add(start);
                        start += wl.length();
                        pos = start;
                    }
                }
                pos++;
            }

            int cursorVisualLine = 0;
            for (int i = 0; i < lineStartPos.size(); i++) {
                int lineStart = lineStartPos.get(i);
                int nextStart = i + 1 < lineStartPos.size() ? lineStartPos.get(i + 1) : fullInput.length() + 1;
                if (cursorPos >= lineStart && cursorPos < nextStart) {
                    cursorVisualLine = i;
                    break;
                }
            }

            for (int vi = 0; vi < visualLines.size(); vi++) {
                String vLine = visualLines.get(vi);
                if (vi == 0) {
                    sb.append(Styles.prompt.render("❯ "));
                } else {
                    sb.append("  ");
                }

                if (vi == cursorVisualLine && !streaming) {
                    int colInLine = cursorPos - lineStartPos.get(vi);
                    colInLine = Math.min(colInLine, vLine.length());
                    String before = vLine.substring(0, colInLine);
                    String at = colInLine < vLine.length() ? vLine.substring(colInLine, colInLine + 1) : " ";
                    String after = colInLine + 1 < vLine.length() ? vLine.substring(colInLine + 1) : "";
                    sb.append(Styles.inputText.render(before));
                    sb.append(Styles.cursorBlock.render(at));
                    sb.append(Styles.inputText.render(after));
                } else {
                    sb.append(Styles.inputText.render(vLine));
                }
                sb.append('\n');
            }
        }
    }

    private void renderSlashMenuArea(StringBuilder sb) {
        if (!slashMenuOpen || slashMatches.isEmpty()) return;

        int total = slashMatches.size();
        if (total <= 8) {
            for (int i = 0; i < total; i++) {
                renderSlashMenuItem(sb, i);
            }
        } else {
            int start = Math.max(0, Math.min(slashCursor - 3, total - 8));
            int end = Math.min(total, start + 8);
            if (start > 0) {
                sb.append(Styles.normalItem.render("   ... " + start + " more above"));
                sb.append('\n');
            }
            for (int i = start; i < end; i++) {
                renderSlashMenuItem(sb, i);
            }
            if (end < total) {
                sb.append(Styles.normalItem.render("   ... and " + (total - end) + " more below"));
                sb.append('\n');
            }
        }
    }

    private void renderStatusBarArea(StringBuilder sb) {
        String model = selectedProvider != null ? selectedProvider.getModel() : "";
        String stats = "In: " + totalInputTokens + " Out: " + totalOutputTokens;
        String rightPart = model + "  " + stats;
        String leftPart;

        if (!viewport.atBottom()) {
            int hidden = viewport.totalLineCount() - viewport.getYOffset() - viewport.visibleLineCount();
            leftPart = "↑ " + Math.max(0, hidden) + " lines ↓";
        } else if (runtime != null && runtime.getPermissionChecker() != null
                && runtime.getPermissionChecker().getMode() == PermissionMode.PLAN) {
            leftPart = "[PLAN]";
        } else if (!streaming) {
            leftPart = "/help · /compact · /clear · /status";
        } else {
            leftPart = "";
        }

        int padSize = Math.max(1, width - leftPart.length() - rightPart.length());
        sb.append(Styles.statusBar.render(leftPart + " ".repeat(padSize) + rightPart));
        sb.append('\n');
    }

    private void renderSlashMenuItem(StringBuilder sb, int i) {
        var sc = slashMatches.get(i);
//        String marker = (i == slashCursor) ? " ❯ " : "   ";
        String marker = "  ";
        String line = marker + "/" + sc.name() + " — " + sc.description();
        if (i == slashCursor) {
            sb.append(Styles.selectedItem.render(line));
        } else {
            sb.append(Styles.normalItem.render(line));
        }
        sb.append('\n');
    }

    private void renderMessage(StringBuilder sb, ChatMessage msg) {
        switch (msg.getRole()) {
            case USER -> renderWrapped(sb, Styles.prompt, "❯ ", Styles.userText, msg.getContent());
            case ASSISTANT -> {
                sb.append(Styles.aiMarker.render("● "));
                MarkdownRenderer.render(sb, msg.getContent(), width - 4);
            }
            case THINKING -> {
                if (msg.getThinkingContent() != null && !msg.getThinkingContent().isEmpty()) {
                    // Thinking block: collapsible
                    if (msg.isThinkingExpanded()) {
                        renderWrapped(sb, Styles.thinkingLabel, "  ", Styles.thinkingContent, msg.getThinkingContent());
                    } else {
                        sb.append(Styles.thinkingLabel.render("  [思考完成] (ctrl+o 展开)"));
                        sb.append('\n');
                    }
                } else if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    // Thinking status line (e.g. "✻ Thought for 12.3s")
                    sb.append(Styles.thinkingContent.render("  " + msg.getContent()));
                    sb.append('\n');
                }
            }
            case ERROR -> {
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    renderWrappedPlain(sb, Styles.errorText, "✖ " + msg.getContent());
                }
            }
            case SYSTEM -> {
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    renderWrappedPlain(sb, Styles.systemText, msg.getContent());
                }
            }
        }
    }

    private void renderWrapped(StringBuilder sb, com.williamcallahan.tui4j.compat.bubbletea.lipgloss.Style prefixStyle, String prefix,
                                com.williamcallahan.tui4j.compat.bubbletea.lipgloss.Style contentStyle, String text) {
        if (text == null || text.isEmpty()) return;
        boolean firstLine = true;
        String indent = " ".repeat(prefix.length());
        String[] paragraphs = text.split("\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            String para = paragraphs[p];
            if (para.isEmpty()) continue;
            List<String> lines = wrapLines(para, width - 4);
            for (String line : lines) {
                sb.append(firstLine ? prefixStyle.render(prefix) : indent);
                sb.append(contentStyle.render(line));
                sb.append('\n');
                firstLine = false;
            }
        }
    }

    private void renderWrappedPlain(StringBuilder sb,
                                     com.williamcallahan.tui4j.compat.bubbletea.lipgloss.Style style,
                                     String text) {
        if (text == null || text.isEmpty()) return;
        String[] paragraphs = text.split("\n", -1);
        for (String para : paragraphs) {
            if (para.isEmpty()) continue;
            for (String line : wrapLines(para, width - 4)) {
                sb.append(style.render(line));
                sb.append('\n');
            }
        }
    }

    private List<String> wrapLines(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        if (maxWidth < 1) maxWidth = 1;

        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();

        for (String word : words) {
            int spaceNeeded = line.isEmpty() ? 0 : 1;
            int lineDw = displayWidth(line.toString());
            int wordDw = displayWidth(word);
            if (lineDw + spaceNeeded + wordDw <= maxWidth) {
                if (spaceNeeded > 0) line.append(' ');
                line.append(word);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                // Split long words by display width
                while (displayWidth(word) > maxWidth) {
                    int splitAt = splitAtDisplayWidth(word, maxWidth);
                    lines.add(word.substring(0, splitAt));
                    word = word.substring(splitAt);
                }
                line.append(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** Matches ANSI SGR escape sequences (e.g. \033[1m, \033[38;5;80m, \033[0m). */
    private static final java.util.regex.Pattern ANSI_ESCAPE =
            java.util.regex.Pattern.compile("\\[[;\\d]*m");

    private static String stripAnsi(String s) {
        return ANSI_ESCAPE.matcher(s).replaceAll("");
    }

    private static int displayWidth(String s) {
        String plain = stripAnsi(s);
        int w = 0;
        for (int i = 0; i < plain.length(); i++) {
            int cp = plain.codePointAt(i);
            if (Character.isSupplementaryCodePoint(cp)) i++;
            w += (cp >= 0x2E80 && cp <= 0xA4CF
                    || cp >= 0xA960 && cp <= 0xA97C
                    || cp >= 0xAC00 && cp <= 0xD7AF
                    || cp >= 0xF900 && cp <= 0xFAFF
                    || cp >= 0xFE10 && cp <= 0xFE19
                    || cp >= 0xFE30 && cp <= 0xFE6F
                    || cp >= 0xFF01 && cp <= 0xFF60
                    || cp >= 0xFFE0 && cp <= 0xFFE6
                    || cp >= 0x1F300 && cp <= 0x1F64F
                    || cp >= 0x1F900 && cp <= 0x1F9FF
                    || cp >= 0x20000 && cp <= 0x2FFFD
                    || cp >= 0x30000 && cp <= 0x3FFFD) ? 2 : 1;
        }
        return w;
    }

    private static int splitAtDisplayWidth(String s, int maxWidth) {
        String plain = stripAnsi(s);
        int w = 0;
        for (int i = 0; i < plain.length(); i++) {
            int cp = plain.codePointAt(i);
            int cw = (cp >= 0x2E80) ? 2 : 1;
            if (w + cw > maxWidth) return i;
            w += cw;
            if (Character.isSupplementaryCodePoint(cp)) i++;
        }
        return s.length();
    }

    private static int detectTerminalWidth() {
        // Check COLUMNS env var (common in terminals)
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try { return Integer.parseInt(columns); } catch (NumberFormatException ignored) {}
        }
        // Fallback: try tput
        try {
            Process p = new ProcessBuilder("tput", "cols")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            int w = Integer.parseInt(out);
            if (w > 0) return w;
        } catch (Exception ignored) {}
        return 80;
    }

    // ═══════════════════════════════════════════════════════════
    // Hook lifecycle helpers
    // ═══════════════════════════════════════════════════════════

    private void fireHook(HookEngine.EventName event, String toolName, Map<String, Object> args) {
        if (runtime == null || runtime.getHookEngine() == null) return;
        var ctx = new HookEngine.HookContext(event, toolName, args, null, null, null);
        var results = runtime.getHookEngine().runHooks(ctx);
        // Prompt injections → append as system reminders to conversation
        for (var r : results) {
            if (r.success() && r.output() != null && !r.output().isEmpty()) {
                // Only prompt-type results are injected; command/http results are side effects
                // The HookEngine currently doesn't distinguish action types in HookResult,
                // but prompt hooks return the message as output. We inject all non-empty outputs
                // that are not "(async)" placeholders.
                if (!r.output().equals("(async)")) {
                    runtime.getConversation().addSystemReminder(r.output());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Custom messages
    // ═══════════════════════════════════════════════════════════

    public record StreamTickMessage() implements Message {}
    public record McpStatusTickMessage() implements Message {}
    public record MailboxPollMessage() implements Message {}
}
