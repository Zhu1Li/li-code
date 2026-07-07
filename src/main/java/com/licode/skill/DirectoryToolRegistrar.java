package com.licode.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolRegistry;
import com.licode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class DirectoryToolRegistrar {

    private static final Logger LOG = Logger.getLogger(DirectoryToolRegistrar.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DirectoryToolRegistrar() {}

    /**
     * Scan skillDir/tool.json and register each tool into the registry.
     *
     * @return number of tools successfully registered
     */
    public static int register(Path skillDir, ToolRegistry registry) {
        Path toolJson = skillDir.resolve("tool.json");
        if (!Files.isRegularFile(toolJson)) return 0;

        List<?> toolDefs;
        try {
            String raw = Files.readString(toolJson);
            toolDefs = MAPPER.readValue(raw, List.class);
        } catch (IOException e) {
            LOG.warning("Failed to read tool.json in " + skillDir + ": " + e.getMessage());
            return 0;
        }

        int count = 0;
        for (Object obj : toolDefs) {
            if (!(obj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> def = (Map<String, Object>) obj;
            try {
                Tool tool = buildTool(def, skillDir);
                if (tool != null) {
                    registry.register(tool);
                    count++;
                }
            } catch (Exception e) {
                LOG.warning("Skipping tool in " + toolJson + ": " + e.getMessage());
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Tool buildTool(Map<String, Object> def, Path skillDir) {
        String name = (String) def.get("name");
        String description = (String) def.get("description");
        Map<String, Object> parameters = (Map<String, Object>) def.get("parameters");
        String script = (String) def.get("script");

        if (name == null || name.isBlank()) {
            LOG.warning("Skipping tool with no name in " + skillDir);
            return null;
        }
        if (script == null || script.isBlank()) {
            LOG.warning("Skipping tool '" + name + "' with no script in " + skillDir);
            return null;
        }

        Path scriptPath = skillDir.resolve(script);
        if (!Files.isRegularFile(scriptPath)) {
            LOG.warning("Script not found for tool '" + name + "': " + scriptPath);
            return null;
        }

        return new SkillDirectoryTool(name,
                description != null ? description : "",
                parameters != null ? parameters : Map.of(),
                scriptPath);
    }

    /** A tool backed by a shell script in a skill directory. */
    private record SkillDirectoryTool(
            String name,
            String description,
            Map<String, Object> inputSchemaParams,
            Path scriptPath) implements Tool {

        @Override
        public String name() { return name; }

        @Override
        public String description() { return description; }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                    "name", name,
                    "description", description,
                    "input_schema", inputSchemaParams
            );
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            try {
                var pb = new ProcessBuilder(scriptPath.toString());
                pb.directory(scriptPath.getParent().toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                int exitCode = p.waitFor();
                if (exitCode != 0) {
                    return ToolResult.error(output);
                }
                return ToolResult.success(output);
            } catch (Exception e) {
                return ToolResult.error("Tool '" + name + "' failed: " + e.getMessage());
            }
        }
    }
}
