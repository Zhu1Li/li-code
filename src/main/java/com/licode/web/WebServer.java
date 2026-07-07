package com.licode.web;

import com.licode.config.AppConfig;
import com.licode.config.McpServerConfig;
import com.licode.config.ProviderConfig;
import com.licode.llm.StreamCallback;
import com.licode.permission.PermissionResponse;
import com.licode.runtime.LiRuntime;
import com.licode.tool.ToolRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Minimal local web UI for LiCode. Reuses the same {@link LiRuntime} streaming
 * path as the GUI ({@code runtime.ask(text, StreamCallback)}) and bridges each
 * callback to a Server-Sent Events frame. Single user, single conversation —
 * this is a spike, not a multi-tenant server.
 */
public class WebServer {

    private final LiRuntime runtime;
    private final int port;
    private final String modelName;
    private final Map<String, CompletableFuture<PermissionResponse>> pendingPermissions =
            new ConcurrentHashMap<>();

    private WebServer(LiRuntime runtime, int port, String modelName) {
        this.runtime = runtime;
        this.port = port;
        this.modelName = modelName;
    }

    /** Bootstrap a runtime from config (mirrors LiCodeApp.initializeRuntime) and start serving. */
    public static void start(AppConfig config, ProviderConfig provider, int port) throws IOException {
        var registry = ToolRegistry.createDefault();
        LiRuntime runtime = LiRuntime.create(provider, registry);

        List<McpServerConfig> mcpServers = config.getMcpServers();
        if (mcpServers != null && !mcpServers.isEmpty()) {
            runtime.connectMcpServers(mcpServers, status -> {});
        }
        Path workDir = Path.of(System.getProperty("user.dir"));
        runtime.initSubAgentSystem(workDir, config.getProviders());
        if (config.getHooks() != null && !config.getHooks().isEmpty()) {
            runtime.setHookConfigs(config.getHooks());
        }

        new WebServer(runtime, port, provider.getModel()).run();
    }

    private void run() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // Virtual-thread-per-request so SSE handlers can block on a latch without
        // starving other requests (the default executor handles requests serially).
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/", this::handleIndex);
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/permission", this::handlePermission);
        server.createContext("/api/stop", this::handleStop);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/sessions", this::handleSessions);
        server.createContext("/api/session", this::handleSession);
        server.createContext("/api/workdir", this::handleWorkdir);
        server.createContext("/api/workdir/pick", this::handleWorkdirPick);
        server.createContext("/api/files", this::handleFiles);

        server.start();
        String url = "http://127.0.0.1:" + port + "/";
        System.out.println("LiCode web UI: " + url);
        openBrowser(url);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runtime.shutdown();
            server.stop(0);
        }));
    }

    // ── Routes ────────────────────────────────────────────────────────

    private void handleIndex(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] body;
        try (var in = getClass().getResourceAsStream("/web/index.html")) {
            body = in == null ? "index.html not found".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void handleChat(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        String message = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0); // 0 → chunked, write until close

        OutputStream out = ex.getResponseBody();
        CountDownLatch done = new CountDownLatch(1);
        StreamCallback cb = new SseCallback(out, done);

        try {
            runtime.ask(message, cb);
            done.await();
        } catch (Exception e) {
            sendEvent(out, "error", Map.of("message", String.valueOf(e.getMessage())));
        } finally {
            try { out.close(); } catch (IOException ignored) {}
            ex.close();
        }
    }

    private void handlePermission(HttpExchange ex) throws IOException {
        String id = queryParam(ex, "id");
        String decision = queryParam(ex, "decision");
        var future = id == null ? null : pendingPermissions.remove(id);
        if (future != null) {
            PermissionResponse resp = switch (decision == null ? "" : decision) {
                case "allow" -> PermissionResponse.ALLOW;
                case "always" -> PermissionResponse.ALLOW_ALWAYS;
                default -> PermissionResponse.DENY;
            };
            future.complete(resp);
        }
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private void handleStop(HttpExchange ex) throws IOException {
        runtime.cancel();
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        var status = new java.util.LinkedHashMap<String, Object>();
        status.put("model", modelName == null ? "" : modelName);
        status.put("workDir", System.getProperty("user.dir"));
        status.put("sessionId", runtime.getCurrentSessionId() == null ? "" : runtime.getCurrentSessionId());

        var mcp = runtime.getMcpManager();
        status.put("mcp", Map.of(
                "count", mcp != null ? mcp.getServerCount() : 0,
                "names", mcp != null ? mcp.getServerNames() : List.of()));

        var registry = runtime.getToolRegistry();
        List<String> toolNames = registry != null
                ? registry.listTools().stream().map(t -> t.name()).sorted().toList() : List.of();
        status.put("tools", Map.of("count", toolNames.size(), "names", toolNames));

        var skills = runtime.getSkillCatalog();
        List<String> skillNames = skills != null
                ? skills.list().stream().map(s -> s.name()).toList() : List.of();
        status.put("skills", Map.of("count", skillNames.size(), "names", skillNames));

        var hooks = runtime.getHookEngine();
        status.put("hooks", Map.of(
                "count", hooks != null ? hooks.getHookCount() : 0,
                "names", hooks != null ? hooks.getHookIds() : List.of()));

        sendJson(ex, status);
    }

    private void handleSessions(HttpExchange ex) throws IOException {
        sendJson(ex, sessionsList());
    }

    private List<Map<String, Object>> sessionsList() {
        var mgr = runtime.getSessionManager();
        var out = new ArrayList<Map<String, Object>>();
        if (mgr == null) return out;
        String current = runtime.getCurrentSessionId();
        for (var info : mgr.listSessions(null, null, null)) {
            String title = info.firstMessage() != null && !info.firstMessage().isBlank()
                    ? info.firstMessage() : "New session";
            if (title.length() > 60) title = title.substring(0, 60) + "…";
            out.add(Map.of(
                    "id", info.id(),
                    "title", title,
                    "messageCount", info.messageCount(),
                    "modTime", info.modTime() != null ? info.modTime().toString() : "",
                    "active", info.id().equals(current)));
        }
        return out;
    }

    private void handleSession(HttpExchange ex) throws IOException {
        String action = queryParam(ex, "action");
        String id = queryParam(ex, "id");
        switch (action == null ? "" : action) {
            case "new" -> {
                runtime.shutdown();
                runtime.clearConversation();
                sendJson(ex, Map.of("ok", true));
            }
            case "delete" -> {
                if (id != null) runtime.getSessionManager().deleteSession(id);
                sendJson(ex, Map.of("ok", true));
            }
            case "switch" -> {
                runtime.resumeSession(id);
                sendJson(ex, Map.of("messages", sessionHistory()));
            }
            default -> {
                ex.sendResponseHeaders(400, -1);
                ex.close();
            }
        }
    }

    /** Render the resumed conversation into displayable messages (mirrors the GUI's
     *  switchSession: drop system reminders and tool-result-only turns). */
    private List<Map<String, Object>> sessionHistory() {
        var out = new ArrayList<Map<String, Object>>();
        var conv = runtime.getConversation();
        if (conv == null) return out;
        for (var msg : conv.getMessages()) {
            String role = msg.getRole();
            String content = msg.getContent();
            if ("user".equals(role)) {
                if (content != null && content.startsWith("<system-reminder>")) continue;
                if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) continue;
                if (content != null && !content.isBlank()) {
                    out.add(Map.of("role", "user", "content", content));
                }
            } else if ("assistant".equals(role)) {
                if (msg.getThinkingBlocks() != null) {
                    var think = new StringBuilder();
                    for (var tb : msg.getThinkingBlocks()) {
                        if (tb.thinking() != null && !tb.thinking().isBlank()) {
                            if (think.length() > 0) think.append('\n');
                            think.append(tb.thinking());
                        }
                    }
                    if (think.length() > 0) out.add(Map.of("role", "thinking", "content", think.toString()));
                }
                if (content != null && !content.isBlank()) {
                    out.add(Map.of("role", "assistant", "content", content));
                }
                if (msg.getToolUses() != null) {
                    for (var tu : msg.getToolUses()) {
                        out.add(Map.of("role", "tool", "content", tu.toolName()));
                    }
                }
            }
        }
        return out;
    }

    /** Opens a native OS folder picker on the local machine (the web UI is local-only).
     *  Returns the chosen path; the client then applies it via /api/workdir. */
    private void handleWorkdirPick(HttpExchange ex) throws IOException {
        String path;
        try {
            path = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? pickFolderWindows() : pickFolderSwing();
        } catch (Throwable t) {
            // Native picker failed to launch — try the Swing chooser, else give up.
            try { path = pickFolderSwing(); }
            catch (Throwable t2) { sendJson(ex, Map.of("error", "no native picker available")); return; }
        }
        sendJson(ex, path != null ? Map.of("path", path) : Map.of("cancelled", true));
    }

    /** Native Windows "Browse For Folder" dialog via PowerShell + .NET Forms. */
    private static String pickFolderWindows() throws Exception {
        String cur = System.getProperty("user.dir", "").replace("'", "''");
        String script =
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;" +
                "Add-Type -AssemblyName System.Windows.Forms;" +
                "Add-Type -AssemblyName System.Drawing;" +
                // A visible, topmost 1px owner forces the dialog to the foreground
                // instead of opening hidden behind the browser window.
                "$o=New-Object System.Windows.Forms.Form;$o.StartPosition='CenterScreen';" +
                "$o.Size=New-Object System.Drawing.Size(1,1);$o.TopMost=$true;$o.ShowInTaskbar=$false;" +
                "$o.Show();$o.Activate();$o.BringToFront();" +
                "$d=New-Object System.Windows.Forms.FolderBrowserDialog;" +
                "$d.Description='Select working folder';$d.ShowNewFolderButton=$true;" +
                "$d.SelectedPath='" + cur + "';" +
                "$r=$d.ShowDialog($o);$o.Dispose();" +
                "if($r -eq [System.Windows.Forms.DialogResult]::OK){[Console]::Out.Write($d.SelectedPath)}";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-Command", script)
                .redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out.isEmpty() ? null : out;
    }

    /** Cross-platform fallback: Swing chooser using the system look-and-feel. */
    private static String pickFolderSwing() throws Exception {
        final String[] picked = {null};
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            javax.swing.JFrame parent = new javax.swing.JFrame();
            parent.setAlwaysOnTop(true);
            parent.setUndecorated(true);
            parent.setSize(1, 1);
            parent.setLocationRelativeTo(null);
            parent.setVisible(true);
            parent.toFront();
            var fc = new javax.swing.JFileChooser();
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Select working folder");
            String cur = System.getProperty("user.dir");
            if (cur != null) fc.setCurrentDirectory(new java.io.File(cur));
            int r = fc.showDialog(parent, "Select");
            if (r == javax.swing.JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                picked[0] = fc.getSelectedFile().getAbsolutePath();
            }
            parent.dispose();
        });
        return picked[0];
    }

    /** List directory contents for the file explorer panel. */
    private void handleFiles(HttpExchange ex) throws IOException {
        String sub = queryParam(ex, "path");
        Path dir = sub != null && !sub.isBlank()
                ? Path.of(sub).toAbsolutePath()
                : Path.of(System.getProperty("user.dir")).toAbsolutePath();

        if (!Files.isDirectory(dir)) {
            sendJson(ex, Map.of("error", "not a directory: " + dir));
            return;
        }

        // Security: only serve under the current working directory
        Path wd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!dir.startsWith(wd)) {
            // Allow the parent chain up to and including the workdir itself
            // (so ".." from the workdir root returns a harmless listing)
            sendJson(ex, Map.of("error", "outside working directory"));
            return;
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.sorted((a, b) -> {
                boolean ad = Files.isDirectory(a), bd = Files.isDirectory(b);
                if (ad != bd) return ad ? -1 : 1;       // directories first
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).toList()) {
                String name = p.getFileName().toString();
                boolean isDir = Files.isDirectory(p);
                long size = isDir ? -1 : Files.size(p);
                entries.add(Map.of("name", name, "type", isDir ? "dir" : "file", "size", size));
            }
        } catch (Exception e) {
            entries = List.of();
        }

        Path parent = dir.equals(wd) ? null : dir.getParent();
        // NB: Map.of rejects null values — use a map that allows them.
        var resp = new java.util.LinkedHashMap<String, Object>();
        resp.put("path", dir.toString());
        resp.put("parent", parent != null ? parent.toString() : null);
        resp.put("entries", entries);
        sendJson(ex, resp);
    }

    private void handleWorkdir(HttpExchange ex) throws IOException {
        String path = queryParam(ex, "path");
        if (path == null || path.isBlank()) {
            sendJson(ex, Map.of("error", "path is required")); return;
        }
        Path p = Path.of(path);
        if (!Files.isDirectory(p)) {
            sendJson(ex, Map.of("error", "not a directory: " + path)); return;
        }
        String abs = p.toAbsolutePath().toString();
        System.setProperty("user.dir", abs);
        runtime.setWorkDir(abs);
        runtime.clearConversation(); // sessions are per-folder; start fresh in the new one
        sendJson(ex, Map.of("ok", true, "workDir", abs));
    }

    private static void sendJson(HttpExchange ex, Object body) throws IOException {
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ── SSE callback ──────────────────────────────────────────────────

    private final class SseCallback implements StreamCallback {
        private final OutputStream out;
        private final CountDownLatch done;
        // Per-turn, keyed by toolId. The callback instance lives for one chat request.
        private final Map<String, String> toolNames = new ConcurrentHashMap<>();
        private final Map<String, StringBuilder> rawArgs = new ConcurrentHashMap<>();
        private final Map<String, Integer> sentLen = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Object>> finalArgs = new ConcurrentHashMap<>();

        SseCallback(OutputStream out, CountDownLatch done) {
            this.out = out;
            this.done = done;
        }

        @Override public void onTextDelta(String text) {
            send("text", Map.of("text", text));
        }

        @Override public void onThinkingDelta(String text) {
            send("thinking", Map.of("text", text));
        }

        @Override public void onThinkingComplete(String thinking, String signature) {
            send("thinking_done", Map.of());
        }

        @Override public void onToolCallStart(String toolCallId, String toolName) {
            toolNames.put(toolCallId, toolName);
            rawArgs.put(toolCallId, new StringBuilder());
            sentLen.put(toolCallId, 0);
            send("tool", Map.of("id", toolCallId, "phase", "start", "name", toolName));
        }

        @Override public void onToolCallDelta(String toolCallId, String argumentsDelta) {
            // Feature 1: live tool-input streaming. The model emits the entire file
            // body as JSON args; show it materializing instead of a frozen gap.
            StringBuilder raw = rawArgs.computeIfAbsent(toolCallId, k -> new StringBuilder());
            raw.append(argumentsDelta);
            String name = toolNames.get(toolCallId);
            String live = liveContent(name, raw.toString());
            if (live == null) return;
            int last = sentLen.getOrDefault(toolCallId, 0);
            if (live.length() - last < 120) return; // throttle: avoid O(n^2) frame spam
            sentLen.put(toolCallId, live.length());
            send("tool_stream", Map.of("id", toolCallId, "name", name == null ? "" : name, "text", live));
        }

        @Override public void onToolCallComplete(String toolCallId, String toolName, Map<String, Object> arguments) {
            if (arguments != null && !arguments.isEmpty()) {
                // Full args resolved (fires before the permission request). Capture them
                // and push a clean preview/diff that supersedes the live raw stream.
                finalArgs.put(toolCallId, arguments);
                Map<String, Object> preview = buildPreview(toolName, arguments);
                if (preview != null) {
                    var ev = new java.util.LinkedHashMap<String, Object>();
                    ev.put("id", toolCallId);
                    ev.put("name", toolName);
                    ev.putAll(preview);
                    send("tool_preview", ev);
                }
            }
            // Empty args = the post-execution echo; the real result arrives via onToolResult.
        }

        @Override public void onToolResult(String toolId, String toolName, String output,
                                           boolean isError, double elapsedSeconds) {
            String out = output == null ? "" : output;
            if (out.length() > 8000) out = out.substring(0, 8000) + "\n… (" + (output.length() - 8000) + " more chars)";
            send("tool_result", Map.of(
                    "id", toolId, "name", toolName, "output", out,
                    "isError", isError, "elapsed", Math.round(elapsedSeconds * 10) / 10.0));
        }

        @Override public void onNotice(String message) {
            send("notice", Map.of("message", message == null ? "" : message));
        }

        @Override public void onError(String message) {
            send("error", Map.of("message", message == null ? "" : message));
            done.countDown();
        }

        @Override public void onComplete(String stopReason, int inputTokens, int outputTokens) {
            send("done", Map.of(
                    "stopReason", stopReason == null ? "" : stopReason,
                    "inputTokens", inputTokens,
                    "outputTokens", outputTokens));
            done.countDown();
        }

        @Override public void onPermissionRequest(String toolId, String toolName, String description,
                                                  CompletableFuture<PermissionResponse> future) {
            pendingPermissions.put(toolId, future);
            // Feature 2: show the diff/preview in the approval card so the user
            // approves with context, not just a file path.
            var ev = new java.util.LinkedHashMap<String, Object>();
            ev.put("id", toolId);
            ev.put("name", toolName);
            ev.put("description", description == null ? "" : description);
            Map<String, Object> preview = buildPreview(toolName, finalArgs.get(toolId));
            if (preview != null) ev.putAll(preview);
            send("permission", ev);
        }

        private void send(String type, Map<String, Object> data) {
            try {
                sendEvent(out, type, data);
            } catch (IOException e) {
                // Client disconnected — abort the turn and unblock the handler.
                runtime.cancel();
                done.countDown();
            }
        }
    }

    // ── SSE / JSON helpers ────────────────────────────────────────────

    private static synchronized void sendEvent(OutputStream out, String type, Map<String, Object> data)
            throws IOException {
        var full = new java.util.LinkedHashMap<String, Object>();
        full.put("type", type);
        full.putAll(data);
        out.write(("data: " + toJson(full) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> map) {
            var sb = new StringBuilder("{");
            boolean first = true;
            for (var e : ((Map<String, Object>) map).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonStr(e.getKey())).append(':').append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof List<?> list) {
            var sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(toJson(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return jsonStr(String.valueOf(o));
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    // ── Preview / diff construction ───────────────────────────────────

    private static final int MAX_DIFF_LINES = 500;

    /** Best-effort extraction of the dominant string field from partially-streamed
     *  args JSON, so the file body can be shown as it arrives. */
    private static String liveContent(String toolName, String raw) {
        String marker = "WriteFile".equals(toolName) ? "\"content\""
                : "EditFile".equals(toolName) ? "\"new_string\"" : null;
        if (marker == null) return null;
        int k = raw.indexOf(marker);
        if (k < 0) return null;
        int colon = raw.indexOf(':', k + marker.length());
        if (colon < 0) return null;
        int q = raw.indexOf('"', colon + 1);
        if (q < 0) return null;
        return unescapeJsonPartial(raw.substring(q + 1));
    }

    /** Decode a JSON string body that may be truncated mid-escape. */
    private static String unescapeJsonPartial(String s) {
        var sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') break; // closing quote of a complete value
            if (c == '\\') {
                if (i + 1 >= s.length()) break; // incomplete escape at the tail
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); }
                            catch (NumberFormatException ignored) {}
                            i += 4;
                        } else { i = s.length(); }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Build a clean preview payload for Write/Edit: a line-level diff for the UI. */
    private static Map<String, Object> buildPreview(String toolName, Map<String, Object> args) {
        if (args == null) return null;
        if ("WriteFile".equals(toolName)) {
            String path = String.valueOf(args.getOrDefault("file_path", ""));
            String content = String.valueOf(args.getOrDefault("content", ""));
            String existing = "";
            boolean isNew = true;
            try {
                Path p = Path.of(path);
                if (Files.exists(p)) { existing = Files.readString(p); isNew = false; }
            } catch (Exception ignored) {}
            var diff = LineDiffToPayload(com.licode.diff.LineDiff.diff(existing, content));
            return Map.of("kind", "write", "path", path, "isNew", isNew, "diff", diff);
        }
        if ("EditFile".equals(toolName)) {
            String path = String.valueOf(args.getOrDefault("file_path", ""));
            String oldStr = String.valueOf(args.getOrDefault("old_string", ""));
            String newStr = String.valueOf(args.getOrDefault("new_string", ""));
            var diff = LineDiffToPayload(com.licode.diff.LineDiff.diff(oldStr, newStr));
            return Map.of("kind", "edit", "path", path, "diff", diff);
        }
        // Generic tools: show their arguments so the user sees what's running.
        String argsText = formatArgs(args);
        if (argsText.isEmpty()) return null;
        return Map.of("kind", "tool", "argsText", argsText);
    }

    private static String formatArgs(Map<String, Object> args) {
        var sb = new StringBuilder();
        for (var e : args.entrySet()) {
            if ("thinking".equals(e.getKey())) continue;
            String v = String.valueOf(e.getValue());
            if (v.length() > 400) v = v.substring(0, 400) + "…";
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append(": ").append(v);
        }
        return sb.toString();
    }

    private static List<Map<String, Object>> LineDiffToPayload(List<com.licode.diff.LineDiff.Line> lines) {
        var out = new ArrayList<Map<String, Object>>();
        int n = Math.min(lines.size(), MAX_DIFF_LINES);
        for (int i = 0; i < n; i++) {
            var l = lines.get(i);
            String sign = switch (l.type()) { case ADD -> "+"; case DEL -> "-"; default -> " "; };
            out.add(Map.of("t", sign, "text", l.text()));
        }
        if (lines.size() > MAX_DIFF_LINES) {
            out.add(Map.of("t", " ", "text", "… " + (lines.size() - MAX_DIFF_LINES) + " more lines"));
        }
        return out;
    }

    private static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", "start", "", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
        } catch (IOException ignored) {
            // Browser auto-open is best-effort; the URL is printed above.
        }
    }
}
