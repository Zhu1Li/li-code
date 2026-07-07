package com.licode.tool.impl;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;
import com.licode.worktree.SlugValidator;
import com.licode.worktree.WorktreeManager;
import com.licode.worktree.WorktreeSession;
import com.licode.worktree.WorktreeSessionStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

public class EnterWorktreeTool implements Tool {

    private final WorktreeManager worktreeManager;
    private final Consumer<String> onEnterWorktree;
    private final String sessionPrefix;
    private final Random random = new Random();

    public EnterWorktreeTool(WorktreeManager worktreeManager, Consumer<String> onEnterWorktree, String sessionPrefix) {
        this.worktreeManager = worktreeManager;
        this.onEnterWorktree = onEnterWorktree;
        this.sessionPrefix = sessionPrefix != null ? sessionPrefix : "wt";
    }

    @Override
    public String name() {
        return "EnterWorktree";
    }

    @Override
    public String description() {
        return "Use this tool ONLY when explicitly instructed to work in a worktree — either by the user directly, or by project instructions (CLAUDE.md / memory). "
                + "This tool creates an isolated git worktree and switches the current session into it.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "Optional name for a new worktree. Each \"/\"-separated segment may contain only letters, digits, dots, underscores, and dashes; max 64 chars total. A random name is generated if not provided."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name());
        schema.put("description", description());
        schema.put("input_schema", Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of()
        ));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        if (WorktreeSessionStore.getCurrentSession() != null) {
            return ToolResult.error("Already in a worktree session. Use ExitWorktree to leave the current session before creating a new one.");
        }

        String name = getStringArg(args, "name");
        if (name == null || name.isBlank()) {
            name = sessionPrefix + "-" + Integer.toHexString(random.nextInt());
        }

        try {
            SlugValidator.validate(name);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        String branch = SlugValidator.branchName(name);
        String originalCwd = System.getProperty("user.dir");

        // Capture HEAD before creating the worktree for later change detection
        String originalHead = captureHead(worktreeManager.getProjectRoot());

        WorktreeManager.WorktreeInfo info;
        try {
            info = worktreeManager.create(branch, null);
        } catch (Exception e) {
            return ToolResult.error("Failed to create worktree: " + e.getMessage());
        }

        var session = new WorktreeSession(
                originalCwd,
                info.path(),
                name,
                info.branch(),
                originalHead,
                sessionPrefix + "-" + System.currentTimeMillis()
        );

        WorktreeSessionStore.restoreSession(session);
        try {
            WorktreeSessionStore.save(worktreeManager.getProjectRoot(), session);
        } catch (IOException e) {
            return ToolResult.error("Failed to persist worktree session: " + e.getMessage());
        }

        // Switch working directory
        if (onEnterWorktree != null) {
            onEnterWorktree.accept(info.path());
        }

        return ToolResult.success(
                "Created worktree at %s on branch %s. The session is now working in the worktree. "
                        + "Use ExitWorktree to leave mid-session."
                        .formatted(info.path(), info.branch()));
    }

    private static String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }

    private static String captureHead(String projectRoot) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(java.nio.file.Path.of(projectRoot).toFile());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes());
            if (proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) {
                return stdout.strip();
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
