package com.licode.subagent;

import com.licode.tool.Tool;
import com.licode.tool.ToolRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * Filters a {@link ToolRegistry} to produce a restricted registry suitable
 * for a sub-agent. The filtering layers (applied in order) are:
 * <ol>
 *   <li>MCP tools (prefixed with "mcp__") always pass through.</li>
 *   <li>{@code ALWAYS_DISALLOWED} — globally blocked tools
 *       (Agent, AskUserQuestion, TaskOutput, ExitPlanMode, EnterPlanMode, TaskStop).</li>
 *   <li>In async mode, only permit {@code ASYNC_ALLOWED} tools.</li>
 *   <li>Per-spec {@code disallowedTools} exclusion.</li>
 *   <li>Per-spec {@code tools} whitelist intersection
 *       (skipped if null/empty or contains only "*").</li>
 * </ol>
 */
public final class ToolFilter {

    /** Tools that are never available to any sub-agent. */
    static final Set<String> ALWAYS_DISALLOWED = Set.of(
            "Agent", "AskUserQuestion", "TaskOutput",
            "ExitPlanMode", "EnterPlanMode", "TaskStop"
    );

    /** Tools permitted for async (background) sub-agents. */
    static final Set<String> ASYNC_ALLOWED = Set.of(
            "ReadFile", "Grep", "Glob",
            "Bash", "WriteFile", "EditFile",
            "ToolSearch", "LoadSkill"
    );

    private ToolFilter() {}

    /**
     * Convenience overload: sync (foreground) filtering, no custom/teammate flags.
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec) {
        return filterForAgent(source, spec, false);
    }

    /**
     * Creates a new {@link ToolRegistry} containing only the tools that
     * the given sub-agent spec is allowed to use.
     *
     * @param source   the parent registry to filter from
     * @param spec     the sub-agent specification
     * @param isAsync  if {@code true}, restrict to the async allow-list
     * @return a new filtered registry
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec,
                                              boolean isAsync) {
        Set<String> disallowed = new HashSet<>(spec.disallowedTools());

        boolean hasWhitelist = spec.tools() != null && !spec.tools().isEmpty()
                && !(spec.tools().size() == 1 && "*".equals(spec.tools().get(0)));
        Set<String> allowed = hasWhitelist ? new HashSet<>(spec.tools()) : Set.of();

        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            String name = tool.name();

            // Layer 1: MCP tools always pass through
            if (name.startsWith("mcp__")) {
                filtered.register(tool);
                continue;
            }

            // Layer 2: Globally blocked tools
            if (ALWAYS_DISALLOWED.contains(name)) {
                continue;
            }

            // Layer 3: In async mode, only permit the allow-listed tools
            if (isAsync && !ASYNC_ALLOWED.contains(name)) {
                continue;
            }

            // Layer 4: Per-spec disallowed tools
            if (disallowed.contains(name)) {
                continue;
            }

            // Layer 5: Per-spec whitelist intersection
            if (hasWhitelist && !allowed.contains(name)) {
                continue;
            }

            filtered.register(tool);
        }
        return filtered;
    }
}
