package com.licode.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class CommandRegistry {

    private final List<Command> commands = new ArrayList<>();
    private final Map<String, Function<CommandContext, String>> handlers = new HashMap<>();

    public CommandRegistry() {
        registerDefaults();
    }

    // ── Registration ──────────────────────────────────────────────

    public void register(Command cmd, Function<CommandContext, String> handler) {
        commands.add(cmd);
        if (handler != null) {
            handlers.put(cmd.name().toLowerCase(), handler);
            if (cmd.aliases() != null) {
                for (var alias : cmd.aliases()) {
                    handlers.put(alias.toLowerCase(), handler);
                }
            }
        }
    }

    // ── Search & Find ─────────────────────────────────────────────

    public List<Command> search(String prefix) {
        String lower = prefix != null ? prefix.toLowerCase() : "";
        return commands.stream()
                .filter(c -> !c.hidden())
                .filter(c -> c.name().toLowerCase().startsWith(lower)
                        || (c.aliases() != null && c.aliases().stream().anyMatch(a -> a.toLowerCase().startsWith(lower))))
                .sorted(Comparator.comparing(Command::name))
                .toList();
    }

    public Optional<Command> find(String name) {
        if (name == null) return Optional.empty();
        return commands.stream()
                .filter(c -> c.matches(name))
                .findFirst();
    }

    // ── Execute ───────────────────────────────────────────────────

    public String execute(String name, CommandContext ctx) {
        if (name == null) return "Unknown command: — type /help to see available commands";

        String lower = name.toLowerCase();
        Function<CommandContext, String> handler = handlers.get(lower);

        if (handler == null) {
            var cmd = find(name);
            if (cmd.isPresent()) {
                handler = handlers.get(cmd.get().name().toLowerCase());
            }
        }

        if (handler == null) {
            return "Unknown command: /" + name + " — type /help to see available commands";
        }

        return handler.apply(ctx);
    }

    // ── Listing ───────────────────────────────────────────────────

    public List<Command> listAll() {
        return List.copyOf(commands);
    }

    public List<Command> listVisible() {
        return commands.stream()
                .filter(c -> !c.hidden())
                .sorted(Comparator.comparing(Command::name))
                .toList();
    }

    // ── Skill registration ────────────────────────────────────────

    public void registerSkillCommand(String name, String description, Supplier<String> promptBody) {
        registerSkillCommand(name, description, promptBody, false);
    }

    /**
     * Registers a skill as a slash command. Inline skills use {@code PROMPT} (body injected into
     * the main conversation). Fork skills use {@code SKILL_FORK} — the dispatch runs the skill in
     * an isolated sub-agent via {@code LiRuntime.askForkSkill} instead of injecting the body.
     */
    public void registerSkillCommand(String name, String description, Supplier<String> promptBody, boolean fork) {
        var existing = find(name);
        if (existing.isPresent()) {
            // If the existing command is already a skill command, keep it
            if (existing.get().description() != null && existing.get().description().endsWith("[skill]")) {
                return;
            }
            // Otherwise, replace the default command with the skill version
            commands.removeIf(c -> c.matches(name));
            if (existing.get().aliases() != null) {
                for (var alias : existing.get().aliases()) {
                    handlers.remove(alias.toLowerCase());
                }
            }
            handlers.remove(name.toLowerCase());
        }

        var type = fork ? Command.CommandType.SKILL_FORK : Command.CommandType.PROMPT;
        var cmd = new Command(name, description + " [skill]", List.of(), type, false);
        register(cmd, ctx -> {
            String body = promptBody.get();
            if (body == null) return "[skill error] not found: " + name;
            return body;
        });
    }

    // ── Default commands ──────────────────────────────────────────

    private void registerDefaults() {
        registerHelp();
        registerClear();
        registerCompact();
        registerStatus();
        registerMemory();
        registerPlan();
        registerDo();
        registerSession();
        registerPermission();
        registerResume();
        registerSkills();
        registerReview();
        registerStyleScan();
    }

    // ── /help [name] ──────────────────────────────────────────────

    private void registerHelp() {
        var cmd = new Command("help", "Show available commands or details for a specific command",
                java.util.List.of("h", "?"), Command.CommandType.LOCAL, false);
        register(cmd, ctx -> {
            String target = ctx.args();
            if (target != null && !target.isBlank()) {
                var found = find(target.trim());
                if (found.isPresent()) {
                    var c = found.get();
                    var sb = new StringBuilder();
                    sb.append("/").append(c.name());
                    if (c.aliases() != null && !c.aliases().isEmpty()) {
                        sb.append(" (aliases: ").append(String.join(", ", c.aliases())).append(")");
                    }
                    sb.append("\n  ").append(c.description());
                    sb.append("\n  Type: ").append(c.type().name().toLowerCase());
                    return sb.toString();
                }
                return "Unknown command: " + target.trim();
            }

            var sb = new StringBuilder();
            sb.append("Available commands:\n\n");
            for (var c : listVisible()) {
                sb.append("/").append(c.name());
                if (c.aliases() != null && !c.aliases().isEmpty()) {
                    sb.append(" (").append(String.join(", ", c.aliases())).append(")");
                }
                sb.append(" — ").append(c.description()).append("\n");
            }
            sb.append("\nType /help <command> for details.");
            return sb.toString();
        });
    }

    // ── /clear ────────────────────────────────────────────────────

    private void registerClear() {
        var cmd = new Command("clear", "Clear the conversation and start fresh",
                List.of(), Command.CommandType.LOCAL_UI, false);
        register(cmd, null);
    }

    // ── /compact ──────────────────────────────────────────────────

    private void registerCompact() {
        var cmd = new Command("compact", "Compress conversation context to save tokens",
                List.of("c"), Command.CommandType.LOCAL_UI, false);
        register(cmd, null);
    }

    // ── /status ───────────────────────────────────────────────────

    private void registerStatus() {
        var cmd = new Command("status", "Show current session status and statistics",
                List.of("s"), Command.CommandType.LOCAL, false);
        register(cmd, ctx -> {
            var sb = new StringBuilder();
            sb.append("Mode: ").append(ctx.permissionMode().get()).append("\n");
            sb.append("Tokens: In ").append(ctx.totalInputTokens().getAsInt())
                    .append(" Out ").append(ctx.totalOutputTokens().getAsInt()).append("\n");
            sb.append("Tools: ").append(ctx.toolCount().getAsInt()).append(" registered\n");
            int memCount = ctx.memoryList().get().size();
            sb.append("Memories: ").append(memCount).append(memCount == 1 ? " entry" : " entries").append("\n");
            sb.append("Model: ").append(ctx.model().get()).append("\n");
            sb.append("Directory: ").append(ctx.workDir());
            return sb.toString();
        });
    }

    // ── /memory [list|clear] ──────────────────────────────────────

    private void registerMemory() {
        var cmd = new Command("memory", "List or clear auto-memories",
                List.of(), Command.CommandType.LOCAL, false);
        register(cmd, ctx -> {
            String sub = ctx.args() != null ? ctx.args().trim() : "";
            if (sub.isEmpty() || sub.equals("list")) {
                var memories = ctx.memoryList().get();
                if (memories.isEmpty()) return "No memories stored yet.";
                var sb = new StringBuilder();
                for (var m : memories) {
                    sb.append(m).append("\n");
                }
                return sb.toString().stripTrailing();
            }
            if (sub.equals("clear")) {
                ctx.memoryClear().run();
                return "All auto-memories cleared.";
            }
            return "Usage: /memory [list|clear]";
        });
    }

    // ── /plan ─────────────────────────────────────────────────────

    private void registerPlan() {
        var cmd = new Command("plan", "Enter plan-only mode (tools are read-only)",
                List.of("p"), Command.CommandType.LOCAL_UI, false);
        register(cmd, null);
    }

    // ── /do ────────────────────────────────────────────────────────

    private void registerDo() {
        var cmd = new Command("do", "Exit plan-only mode and restore normal execution",
                List.of(), Command.CommandType.LOCAL_UI, false);
        register(cmd, null);
    }

    // ── /session [list|info] ──────────────────────────────────────

    private void registerSession() {
        var cmd = new Command("session", "Show current session info or list past sessions",
                List.of(), Command.CommandType.LOCAL, false);
        register(cmd, ctx -> {
            String sub = ctx.args() != null ? ctx.args().trim() : "";
            if (sub.isEmpty() || sub.equals("info") || sub.equals("list")) {
                return ctx.sessionInfo().get();
            }
            return "Usage: /session [list|info]";
        });
    }

    // ── /permission [info|mode <m>] ───────────────────────────────

    private void registerPermission() {
        var cmd = new Command("permission", "View or change permission mode",
                List.of("perm"), Command.CommandType.LOCAL, false);
        register(cmd, ctx -> {
            String sub = ctx.args() != null ? ctx.args().trim() : "";
            if (sub.isEmpty() || sub.equals("info")) {
                return "Current permission mode: " + ctx.permissionMode().get();
            }
            if (sub.startsWith("mode ")) {
                String modeName = sub.substring(5).trim().toLowerCase();
                var valid = java.util.Set.of("default", "acceptedits", "plan", "bypass");
                if (!valid.contains(modeName)) {
                    return "Invalid mode: " + modeName + "\nUsage: /permission mode <default|acceptEdits|plan|bypass>";
                }
                return "Permission mode changed to: " + modeName;
            }
            return "Usage: /permission [info|mode <default|acceptEdits|plan|bypass>]";
        });
    }

    // ── /resume ────────────────────────────────────────────────────

    private void registerResume() {
        var cmd = new Command("resume", "Resume a previous session",
                List.of("r"), Command.CommandType.LOCAL_UI, false);
        register(cmd, null);
    }

    // ── /skills ────────────────────────────────────────────────────

    private void registerSkills() {
        var cmd = new Command("skills", "List available skill commands",
                List.of(), Command.CommandType.LOCAL, false);
        register(cmd, ctx -> {
            var skills = ctx.skillList().get();
            if (skills.isEmpty()) return "No skills installed.";
            var sb = new StringBuilder();
            for (var s : skills) {
                sb.append("/").append(s).append("\n");
            }
            return sb.toString().stripTrailing();
        });
    }

    // ── /review [focus] ───────────────────────────────────────────

    private void registerReview() {
        var cmd = new Command("review", "Review the current git diff for issues",
                List.of(), Command.CommandType.PROMPT, false);
        register(cmd, ctx -> {
            var sb = new StringBuilder();
            sb.append("Please review the current git diff for the following:\n");
            sb.append("- Logic errors\n");
            sb.append("- Security issues\n");
            sb.append("- Performance problems\n");
            sb.append("- Code style\n");
            sb.append("\nFocus on correctness and safety.");
            if (ctx.args() != null && !ctx.args().isBlank()) {
                sb.append("\n\nAdditional focus: ").append(ctx.args().trim());
            }
            return sb.toString();
        });
    }

    // ── /style-scan ───────────────────────────────────────────────
    // PROMPT 命令：注入一段扫描指令，驱动主 Agent 归纳项目风格 profile 并用
    // SaveMemory 工具落盘到 project 记忆（固定 slug code-style），之后每次开局自动注入。

    private void registerStyleScan() {
        var cmd = new Command("style-scan",
                "Scan the codebase and save a project code-style profile to memory",
                List.of(), Command.CommandType.PROMPT, false);
        register(cmd, ctx -> """
                Build a concise **code-style profile** of THIS project so future sessions write \
                code that matches it. Work in these steps:

                1. Use Glob to find representative `.java` source files ACROSS the main modules \
                   (main code and tests), then Read a small, diverse sample. Do NOT read the whole \
                   repository — a handful of representative files is enough.
                2. Summarize the project's conventions along exactly these four dimensions:
                   - **Naming**: class / method / field / constant casing and any prefix-suffix habits.
                   - **Logging**: which logging framework/facility is used and how (levels, style).
                   - **Exception handling**: how errors are raised vs. swallowed/returned; custom \
                     exception patterns; how failures are surfaced.
                   - **Testing**: test class/method naming, assertion style, and test structure.
                3. Keep it SHORT — a few bullet points per dimension. Do NOT paste code snippets. \
                   Record only conventions you actually observed; do not invent rules.
                4. Write the profile in **English**.
                5. Finally, call the **SaveMemory** tool with:
                   - `type` = "project"
                   - `slug` = "code-style"
                   - `content` = the four-dimension profile (markdown)
                   Reusing the "code-style" slug overwrites any previous profile, so this is safe to re-run.
                """);
    }
}
