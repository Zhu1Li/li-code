package com.licode.hook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class HookEngine {

    // ---- Event names ----

    public enum EventName {
        STARTUP("startup"),
        SHUTDOWN("shutdown"),
        SESSION_START("session_start"),
        SESSION_END("session_end"),
        TURN_START("turn_start"),
        TURN_END("turn_end"),
        PRE_SEND("pre_send"),
        POST_RECEIVE("post_receive"),
        PRE_TOOL_USE("pre_tool_use"),
        POST_TOOL_USE("post_tool_use"),
        ERROR("error"),
        COMPACT("compact");

        private final String value;
        EventName(String value) { this.value = value; }
        public String value() { return value; }
    }

    // ---- Action types ----

    public enum ActionType {
        COMMAND("command"),
        PROMPT("prompt"),
        HTTP("http"),
        AGENT("agent");

        private final String value;
        ActionType(String value) { this.value = value; }
        public String value() { return value; }
    }

    // ---- Data records ----

    public record Condition(String variable, String operator, String value) {}

    public record ConditionGroup(String mode, List<Condition> rules) {}

    public record Action(ActionType type, String command, String message,
                         String url, String method, Map<String, String> headers, String body,
                         int timeout) {}

    public record Hook(String id, EventName event, ConditionGroup condition, Action action,
                       boolean reject, boolean once, boolean async, int timeout) {}

    public record HookContext(EventName event, String toolName, Map<String, Object> toolArgs,
                              String filePath, String message, String error) {}

    public record HookResult(String hookId, String output, boolean success, boolean reject) {}

    public record PreToolResult(boolean rejected, String message) {}

    // ---- Validation exception ----

    public static class HookValidationException extends Exception {
        public HookValidationException(String message) {
            super(message);
        }
    }

    // ---- Engine state ----

    private final List<Hook> hooks = new ArrayList<>();
    private final List<HookResult> notifications = new ArrayList<>();
    private final Set<String> firedOnce = ConcurrentHashMap.newKeySet();

    // ---- Hook registration ----

    public int getHookCount() { return hooks.size(); }

    public List<String> getHookIds() { return hooks.stream().map(Hook::id).toList(); }

    public void addHook(Hook hook) {
        hooks.add(hook);
    }

    public void loadHooks(List<Hook> hookList) throws HookValidationException {
        List<String> errors = validate(hookList);
        if (!errors.isEmpty()) {
            throw new HookValidationException(String.join("\n", errors));
        }
        hooks.clear();
        hooks.addAll(hookList);
        firedOnce.clear();
    }

    // ---- Hook execution ----

    public List<HookResult> runHooks(HookContext ctx) {
        List<HookResult> results = new ArrayList<>();
        for (Hook h : hooks) {
            if (h.event() != ctx.event()) continue;
            if (!shouldFire(h, ctx)) continue;
            if (h.async()) {
                Thread.startVirtualThread(() -> {
                    HookResult result = executeAction(h, ctx);
                    synchronized (notifications) {
                        notifications.add(result);
                    }
                });
                results.add(new HookResult(h.id(), "(async)", true, h.reject()));
                continue;
            }
            HookResult result = executeAction(h, ctx);
            results.add(result);
            synchronized (notifications) {
                notifications.add(result);
            }
        }
        return results;
    }

    public PreToolResult runPreToolHooks(String toolName, Map<String, Object> args, boolean bypass) {
        if (bypass) return new PreToolResult(false, "");

        String filePath = extractStringArg(args, "file_path");
        HookContext ctx = new HookContext(
                EventName.PRE_TOOL_USE, toolName, args, filePath, null, null);
        for (Hook h : hooks) {
            if (h.event() != EventName.PRE_TOOL_USE) continue;
            if (!h.reject()) continue; // only interested in reject hooks
            if (!shouldFire(h, ctx)) {
//                System.err.println("[LiCode] Hook '" + h.id() + "' skipped: condition not met " +
//                        "(tool=" + ctx.toolName() + ", args=" + ctx.toolArgs() + ")");
                continue;
            }
            HookResult result = executeAction(h, ctx);
            synchronized (notifications) {
                notifications.add(result);
            }
            System.err.println("[LiCode] Hook '" + h.id() + "' REJECTED tool=" + toolName);
            return new PreToolResult(true, result.output());
        }
        return new PreToolResult(false, "");
    }

    // ---- Notifications ----

    public List<HookResult> drainNotifications() {
        synchronized (notifications) {
            List<HookResult> result = List.copyOf(notifications);
            notifications.clear();
            return result;
        }
    }

    // ---- Fire control ----

    private boolean shouldFire(Hook h, HookContext ctx) {
        if (h.once()) {
            if (h.id() != null && !h.id().isEmpty() && !firedOnce.add(h.id())) {
                return false;
            }
        }
        if (h.condition() != null) {
            return evaluateCondition(h.condition(), ctx);
        }
        return true;
    }

    // ---- Condition evaluation ----

    private boolean evaluateCondition(ConditionGroup group, HookContext ctx) {
        if (group == null || group.rules() == null || group.rules().isEmpty()) return true;
        boolean all = "all".equals(group.mode());
        for (Condition leaf : group.rules()) {
            boolean r = evaluateLeaf(leaf, ctx);
            if (all && !r) return false;
            if (!all && r) return true;
        }
        return all; // all: all passed → true; any: none passed → false
    }

    private boolean evaluateLeaf(Condition leaf, HookContext ctx) {
        String resolved = resolveVar(leaf.variable(), ctx);
        return switch (leaf.operator()) {
            case "==" -> resolved.equals(leaf.value());
            case "!=" -> !resolved.equals(leaf.value());
            case "=~" -> {
                try {
                    yield Pattern.matches(leaf.value(), resolved);
                } catch (PatternSyntaxException e) {
                    yield false;
                }
            }
            case "=*" -> {
                try {
                    var matcher = FileSystems.getDefault().getPathMatcher("glob:" + leaf.value());
                    yield matcher.matches(Path.of(resolved));
                } catch (Exception e) {
                    yield false;
                }
            }
            default -> true; // unknown operator — truthy fallback
        };
    }

    private static String extractStringArg(Map<String, Object> args, String key) {
        if (args == null) return null;
        Object val = args.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    static String resolveVar(String name, HookContext ctx) {
        return switch (name) {
            case "tool"      -> ctx.toolName()  != null ? ctx.toolName()  : "";
            case "event"     -> ctx.event()     != null ? ctx.event().value() : "";
            case "file_path" -> ctx.filePath()  != null ? ctx.filePath()  : "";
            case "message"   -> ctx.message()   != null ? ctx.message()   : "";
            case "error"     -> ctx.error()     != null ? ctx.error()     : "";
            default -> {
                if (name.startsWith("args.") && ctx.toolArgs() != null) {
                    String key = name.substring("args.".length());
                    Object v = ctx.toolArgs().get(key);
                    yield v != null ? String.valueOf(v) : "";
                }
                yield "";
            }
        };
    }

    // ---- Template rendering ----

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(.+?)\\}\\}");

    static String renderTemplate(String template, HookContext ctx) {
        if (template == null) return null;
        var matcher = TEMPLATE_PATTERN.matcher(template);
        var sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1).strip();
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(resolveVar(varName, ctx)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ---- Action execution ----

    private HookResult executeAction(Hook h, HookContext ctx) {
        return switch (h.action().type()) {
            case COMMAND -> executeCommand(h, ctx);
            case PROMPT  -> new HookResult(h.id(), renderTemplate(h.action().message(), ctx), true, h.reject());
            case HTTP    -> executeHttp(h, ctx);
            case AGENT   -> new HookResult(h.id(), "Agent action not yet implemented", false, false);
        };
    }

    private HookResult executeCommand(Hook h, HookContext ctx) {
        try {
            String rendered = renderTemplate(h.action().command(), ctx);
            long timeoutMs = h.timeout() > 0 ? h.timeout() * 1000L : 600_000L; // default 10min

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", rendered);
            Map<String, String> env = pb.environment();
            env.put("LICODE_EVENT", ctx.event() != null ? ctx.event().value() : "");
            env.put("LICODE_TOOL", ctx.toolName() != null ? ctx.toolName() : "");
            env.put("LICODE_FILE_PATH", ctx.filePath() != null ? ctx.filePath() : "");
            pb.redirectErrorStream(false);

            Process proc = pb.start();
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(5, TimeUnit.SECONDS);
                String msg = "command timed out after " + h.timeout() + "s";
                System.err.println("[LiCode] Hook '" + h.id() + "' " + msg);
                return new HookResult(h.id(), msg, false, h.reject());
            }

            String stdout = new String(proc.getInputStream().readAllBytes()).strip();
            String stderr = new String(proc.getErrorStream().readAllBytes()).strip();
            int code = proc.exitValue();

//            if (code != 0) {
//                String info = stderr.isEmpty() ? stdout : stderr;
//                System.err.println("[LiCode] Hook '" + h.id() + "' FAIL (exit " + code + "): " + info);
//            } else {
//                System.err.println("[LiCode] Hook '" + h.id() + "' OK (exit 0)" +
//                        (stdout.isEmpty() ? "" : " stdout=" + stdout));
//            }

            String output = stdout;
            if (!stderr.isEmpty()) {
                output = output.isEmpty() ? stderr : output + "\n" + stderr;
            }
            return new HookResult(h.id(), output, code == 0, h.reject());
        } catch (IOException e) {
            System.err.println("[LiCode] Hook '" + h.id() + "' I/O error: " + e.getMessage());
            return new HookResult(h.id(), "Failed to execute hook: " + e.getMessage(), false, h.reject());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookResult(h.id(), "Hook command interrupted", false, h.reject());
        }
    }

    private HookResult executeHttp(Hook h, HookContext ctx) {
        try {
            String renderedUrl = renderTemplate(h.action().url(), ctx);
            String renderedBody = renderTemplate(h.action().body(), ctx);
            String method = h.action().method() != null && !h.action().method().isEmpty()
                    ? h.action().method().toUpperCase() : "POST";
            long timeoutSecs = h.timeout() > 0 ? h.timeout() : 10;

            // Auto-generate body if empty
            if (renderedBody == null || renderedBody.isEmpty()) {
                renderedBody = "{\"event\":\"" + ctx.event().value()
                        + "\",\"tool\":\"" + (ctx.toolName() != null ? ctx.toolName() : "")
                        + "\",\"message\":\"" + (ctx.message() != null ? ctx.message() : "") + "\"}";
            }

            var bodyPublisher = HttpRequest.BodyPublishers.ofString(renderedBody);
            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(renderedUrl))
                    .timeout(Duration.ofSeconds(timeoutSecs))
                    .method(method, bodyPublisher);

            // Apply headers from action config
            Map<String, String> hdrs = h.action().headers();
            if (hdrs != null) {
                for (var entry : hdrs.entrySet()) {
                    requestBuilder.header(entry.getKey(), renderTemplate(entry.getValue(), ctx));
                }
            }
            if (requestBuilder.build().headers().firstValue("Content-Type").isEmpty() && !renderedBody.isEmpty()) {
                requestBuilder.header("Content-Type", "application/json");
            }

            var request = requestBuilder.build();
            try (var httpClient = HttpClient.newHttpClient()) {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
                return new HookResult(h.id(),
                        "HTTP " + response.statusCode() + ": " + response.body().strip(),
                        ok, h.reject());
            }
        } catch (HttpTimeoutException e) {
            return new HookResult(h.id(), "HTTP request timed out", false, h.reject());
        } catch (IOException e) {
            return new HookResult(h.id(), "HTTP request failed: " + e.getMessage(), false, h.reject());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookResult(h.id(), "HTTP request interrupted", false, h.reject());
        }
    }

    // ---- Validation ----

    static List<String> validate(List<Hook> hooks) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < hooks.size(); i++) {
            Hook h = hooks.get(i);
            String label = h.id() != null && !h.id().isEmpty()
                    ? "hook[" + i + "] (id=\"" + h.id() + "\")"
                    : "hook[" + i + "]";

            // Validate event
            if (h.event() == null) {
                errors.add(label + ": event is required");
            }

            // Validate action type
            if (h.action() == null || h.action().type() == null) {
                errors.add(label + ": action.type is required");
            } else {
                switch (h.action().type()) {
                    case COMMAND -> {
                        if (h.action().command() == null || h.action().command().isBlank()) {
                            errors.add(label + ": action.command must be non-empty for type \"command\"");
                        }
                    }
                    case PROMPT -> {
                        if (h.action().message() == null || h.action().message().isBlank()) {
                            errors.add(label + ": action.message must be non-empty for type \"prompt\"");
                        }
                    }
                    case HTTP -> {
                        String url = h.action().url();
                        if (url == null || url.isBlank()) {
                            errors.add(label + ": action.url must be non-empty for type \"http\"");
                        } else {
                            try {
                                URI u = URI.create(url);
                                if (u.getScheme() == null || (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme()))) {
                                    errors.add(label + ": action.url must be a valid http(s) URL (got \"" + url + "\")");
                                }
                            } catch (IllegalArgumentException e) {
                                errors.add(label + ": action.url must be a valid URL (got \"" + url + "\")");
                            }
                        }
                    }
                    case AGENT -> {
                        if ((h.action().message() == null || h.action().message().isBlank())
                                && (h.action().command() == null || h.action().command().isBlank())) {
                            errors.add(label + ": action.message must be non-empty for type \"agent\"");
                        }
                    }
                }
            }

            // Async + reject on pre_tool_use is forbidden
            if (h.async() && h.reject() && h.event() == EventName.PRE_TOOL_USE) {
                errors.add(label + ": async cannot be true for pre_tool_use reject hooks (intercept hooks must be synchronous)");
            }

            // Timeout must be non-negative
            if (h.timeout() < 0) {
                errors.add(label + ": timeout must be >= 0 (got " + h.timeout() + ")");
            }

            // Validate condition if present
            if (h.condition() != null) {
                ConditionGroup cg = h.condition();
                if (cg.mode() != null && !"all".equals(cg.mode()) && !"any".equals(cg.mode())) {
                    errors.add(label + ": condition.mode must be \"all\" or \"any\" (got \"" + cg.mode() + "\")");
                }
                if (cg.rules() != null) {
                    for (int j = 0; j < cg.rules().size(); j++) {
                        Condition leaf = cg.rules().get(j);
                        if (leaf.operator() != null
                                && !"==".equals(leaf.operator())
                                && !"!=".equals(leaf.operator())
                                && !"=~".equals(leaf.operator())
                                && !"=*".equals(leaf.operator())) {
                            errors.add(label + ": condition.rules[" + j + "].operator must be ==, !=, =~, or =* (got \"" + leaf.operator() + "\")");
                        }
                    }
                }
            }
        }
        return errors;
    }
}
