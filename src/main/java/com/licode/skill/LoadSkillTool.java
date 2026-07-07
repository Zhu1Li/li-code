package com.licode.skill;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.util.Map;

public class LoadSkillTool implements Tool {

    private final SkillCatalog catalog;
    private final SkillExecutor executor;
    private final SkillHost host;

    public LoadSkillTool(SkillCatalog catalog, SkillExecutor executor, SkillHost host) {
        this.catalog = catalog;
        this.executor = executor;
        this.host = host;
    }

    @Override
    public String name() {
        return "LoadSkill";
    }

    @Override
    public String description() {
        return "Activate a Skill by name. The Skill's SOP gets pinned to the environment "
                + "context so it's visible at the top of every subsequent turn, and any "
                + "specialized tools the Skill declares get registered in the current "
                + "session. Call this when the user's request matches one of the available "
                + "Skills listed in the system prompt. Pass the Skill name without a "
                + "leading slash.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "name", "LoadSkill",
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description", "The Skill name to activate "
                                                + "(e.g. \"commit\", \"review\")."
                                )
                        ),
                        "required", java.util.List.of("name")
                )
        );
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean isSystemTool() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String name = args != null ? (String) args.get("name") : null;
        if (name == null || name.isBlank()) {
            return ToolResult.error("name is required");
        }
        if (catalog == null || host == null) {
            return ToolResult.error("LoadSkill not wired (Catalog or Host is null)");
        }

        var skillOpt = catalog.getFull(name.trim());
        if (skillOpt.isEmpty()) {
            var available = catalog.list().stream()
                    .map(SkillMeta::name)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
            return ToolResult.error("Unknown skill: " + name.trim()
                    + ". Available skills: " + available);
        }

        Skill skill = skillOpt.get();
        if (skill.promptBody().isBlank()) {
            return ToolResult.error("Skill \"" + name.trim() + "\" has empty body — cannot activate");
        }

        int registeredTools = 0;
        try {
            if (skill.isDirectory() && skill.sourceDir() != null) {
                registeredTools = DirectoryToolRegistrar.register(skill.sourceDir(),
                        host.toolRegistry());
            }

            if (executor != null) {
                executor.executeInline(skill, "", host);
            } else {
                host.activateSkill(skill.meta().name(), skill.promptBody());
            }
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        }

        var sb = new StringBuilder();
        sb.append("Skill \"").append(skill.meta().name())
                .append("\" activated. SOP pinned to environment context.");
        if (registeredTools > 0) {
            sb.append(' ').append(registeredTools).append(" specialized tool(s) registered.");
        }
        return ToolResult.success(sb.toString());
    }
}
