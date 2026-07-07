package com.licode.tool.impl;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;
import com.licode.worktree.WorktreeChanges;
import com.licode.worktree.WorktreeManager;
import com.licode.worktree.WorktreeSessionStore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExitWorktreeTool implements Tool {

    private final WorktreeManager worktreeManager;
    private final Runnable onExitWorktree;

    public ExitWorktreeTool(WorktreeManager worktreeManager, Runnable onExitWorktree) {
        this.worktreeManager = worktreeManager;
        this.onExitWorktree = onExitWorktree;
    }

    @Override
    public String name() {
        return "ExitWorktree";
    }

    @Override
    public String description() {
        return "Exit a worktree session created by EnterWorktree. Use action=\"keep\" to preserve the worktree "
                + "directory and branch, or action=\"remove\" to delete both. When removing, pass "
                + "discard_changes=true to force deletion even with uncommitted changes.";
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
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("keep", "remove"),
                "description", "\"keep\" leaves the worktree directory and branch on disk; \"remove\" deletes both."
        ));
        properties.put("discard_changes", Map.of(
                "type", "boolean",
                "description", "Required true when action is \"remove\" and the worktree has uncommitted files or unmerged commits. The tool will refuse and list them otherwise."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name());
        schema.put("description", description());
        schema.put("input_schema", Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("action")
        ));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        var session = WorktreeSessionStore.getCurrentSession();
        if (session == null) {
            return ToolResult.error("No-op: there is no active EnterWorktree session to exit. "
                    + "This tool only operates on worktrees created by EnterWorktree in the current session.");
        }

        String action = getStringArg(args, "action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("action is required (\"keep\" or \"remove\")");
        }

        boolean discardChanges = Boolean.TRUE.equals(args.get("discard_changes"));

        if ("remove".equals(action) && !discardChanges) {
            var summary = WorktreeChanges.countChanges(session.worktreePath(), session.originalBranch());
            if (summary == null) {
                return ToolResult.error(
                        "Could not verify worktree state. Refusing to remove without explicit confirmation. "
                                + "Re-invoke with discard_changes: true, or use action: \"keep\".");
            }
            if (summary.changedFiles() > 0 || summary.commits() > 0) {
                var parts = new java.util.ArrayList<String>();
                if (summary.changedFiles() > 0) {
                    parts.add(summary.changedFiles() + " uncommitted "
                            + (summary.changedFiles() == 1 ? "file" : "files"));
                }
                if (summary.commits() > 0) {
                    parts.add(summary.commits() + " unpushed "
                            + (summary.commits() == 1 ? "commit" : "commits"));
                }
                return ToolResult.error(
                        "Worktree has " + String.join(" and ", parts)
                                + ". Refusing to remove without explicit confirmation. "
                                + "Re-invoke with discard_changes: true to force deletion, "
                                + "or use action: \"keep\" to preserve your work.");
            }
        }

        // Handle action BEFORE clearing session so failure leaves state intact
        if ("remove".equals(action)) {
            try {
                worktreeManager.remove(session.worktreeBranch());
            } catch (Exception e) {
                return ToolResult.error("Failed to remove worktree: " + e.getMessage());
            }
        }

        // Clear session
        String originalCwd = session.originalCwd();
        WorktreeSessionStore.restoreSession(null);
        try {
            WorktreeSessionStore.save(worktreeManager.getProjectRoot(), null);
        } catch (IOException ignored) {
            // best-effort
        }

        // Restore working directory
        if (onExitWorktree != null) {
            onExitWorktree.run();
        }

        if ("remove".equals(action)) {
            return ToolResult.success(
                    "Exited and removed worktree at %s. Session is now back in %s."
                            .formatted(session.worktreePath(), originalCwd));
        }
        return ToolResult.success(
                "Exited worktree. Your work is preserved at %s. Session is now back in %s."
                        .formatted(session.worktreePath(), originalCwd));
    }

    private static String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
