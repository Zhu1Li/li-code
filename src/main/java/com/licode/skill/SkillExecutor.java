package com.licode.skill;

import com.licode.tool.ToolRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class SkillExecutor {

    private static final Logger LOG = Logger.getLogger(SkillExecutor.class.getName());
    static final String SYSTEM_TOOL_LOAD_SKILL = "LoadSkill";

    private final SkillCatalog catalog;

    public SkillExecutor(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    // ── Inline execution ────────────────────────────────────────────

    public void executeInline(Skill skill, String args, SkillHost host) {
        assertAllowedToolsExist(skill.meta().allowedTools(), host.toolRegistry(), skill.meta().name());

        String rendered = skill.render(args);
        host.activateSkill(skill.meta().name(), rendered);

        // Compute union tool filter across all active skills
        Predicate<String> filter = computeUnionToolFilter(host);
        host.setToolFilter(filter);
    }

    // Fork skills are executed by LiRuntime.askForkSkill (isolated sub-agent), not here.

    // ── Argument substitution ───────────────────────────────────────

    static String substituteArguments(String body, String args) {
        if (body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args != null ? args : "");
        }
        if (args == null || args.isBlank()) {
            return body;
        }
        return body + "\n\n## User Request\n\n" + args;
    }

    // ── Tool whitelist validation ───────────────────────────────────

    static void assertAllowedToolsExist(List<String> allowedTools, ToolRegistry registry,
                                        String skillName) {
        if (allowedTools == null || allowedTools.isEmpty()) return;

        for (String toolName : allowedTools) {
            if (SYSTEM_TOOL_LOAD_SKILL.equals(toolName)) continue;
            if (registry.get(toolName) == null) {
                throw new IllegalStateException(
                        "Skill '" + skillName + "' requires tool '" + toolName
                        + "' but it is not registered");
            }
        }
    }

    // ── Multi-skill tool filter union ───────────────────────────────

    Predicate<String> computeUnionToolFilter(SkillHost host) {
        Set<String> activeNames = host.getActiveSkillNames();
        if (activeNames.isEmpty()) return null;

        Set<String> union = new HashSet<>();
        for (String name : activeNames) {
            var skill = catalog.get(name);
            if (skill.isPresent() && !skill.get().meta().allowedTools().isEmpty()) {
                union.addAll(skill.get().meta().allowedTools());
            }
        }

        if (union.isEmpty()) return null; // no filtering needed
        return union::contains;
    }
}
