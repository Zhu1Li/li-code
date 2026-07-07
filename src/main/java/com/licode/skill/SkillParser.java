package com.licode.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class SkillParser {

    private static final Logger LOG = Logger.getLogger(SkillParser.class.getName());
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9\\-]*$");
    private static final Yaml YAML = new Yaml();

    private SkillParser() {}

    // ── Public entry points ─────────────────────────────────────────

    /** Parse a skill directory. Priority: skill.yaml+prompt.md > SKILL.md. */
    public static Skill parseSkillDir(Path dir) {
        String dirName = dir.getFileName().toString().toLowerCase().replace(' ', '-');

        // 1) Try skill.yaml + prompt.md
        Path yamlPath = dir.resolve("skill.yaml");
        Path promptPath = dir.resolve("prompt.md");
        if (Files.isRegularFile(yamlPath) && Files.isRegularFile(promptPath)) {
            try {
                Skill s = parseYamlAndPrompt(dirName, yamlPath, promptPath, dir);
                return new Skill(s.meta(), s.promptBody(), dir, true, s.bodyLoaded());
            } catch (SkillParseException e) {
                LOG.warning("Failed to parse skill.yaml+prompt.md in " + dir + ": " + e.getMessage());
            }
        }

        // 2) Fallback to SKILL.md
        Path mdPath = dir.resolve("SKILL.md");
        if (Files.isRegularFile(mdPath)) {
            try {
                Skill s = parseSkillMD(dirName, mdPath, dir);
                return new Skill(s.meta(), s.promptBody(), dir, true, s.bodyLoaded());
            } catch (SkillParseException e) {
                LOG.warning("Failed to parse SKILL.md in " + dir + ": " + e.getMessage());
            }
        }

        return null;
    }

    /** Parse a SKILL.md file (YAML frontmatter + Markdown body). */
    public static Skill parseSkillMD(Path file) throws SkillParseException {
        String dirName = file.getParent() != null
                ? file.getParent().getFileName().toString().toLowerCase().replace(' ', '-')
                : file.getFileName().toString().replaceAll("\\.[^.]+$", "").toLowerCase().replace(' ', '-');
        return parseSkillMD(dirName, file, file.getParent());
    }

    // ── Internal implementations ────────────────────────────────────

    static Skill parseSkillMD(String defaultName, Path file, Path sourceDir) throws SkillParseException {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            throw new SkillParseException("Cannot read " + file + ": " + e.getMessage());
        }

        var parseResult = parseFrontmatter(raw, file.toString());
        SkillMeta meta = metaFromMap(parseResult.meta, defaultName, sourceDir.toString());
        return new Skill(meta, parseResult.body, sourceDir, false, false);
    }

    static Skill parseYamlAndPrompt(String defaultName, Path yamlPath, Path promptPath, Path sourceDir)
            throws SkillParseException {
        Map<String, Object> metaMap;
        try {
            String raw = Files.readString(yamlPath);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = YAML.load(raw);
            metaMap = parsed;
        } catch (IOException e) {
            throw new SkillParseException("Cannot read " + yamlPath + ": " + e.getMessage());
        } catch (Exception e) {
            throw new SkillParseException("Invalid YAML in " + yamlPath + ": " + e.getMessage());
        }

        String body;
        try {
            body = Files.readString(promptPath);
        } catch (IOException e) {
            throw new SkillParseException("Cannot read " + promptPath + ": " + e.getMessage());
        }

        SkillMeta meta = metaFromMap(metaMap, defaultName, sourceDir.toString());
        return new Skill(meta, body, sourceDir, false, false);
    }

    // ── Frontmatter parsing ────────────────────────────────────────

    record ParseResult(Map<String, Object> meta, String body) {}

    static ParseResult parseFrontmatter(String raw, String sourceLabel) throws SkillParseException {
        String stripped = raw.stripLeading();
        if (!stripped.startsWith("---")) {
            throw new SkillParseException("Missing YAML frontmatter (must start with ---) in " + sourceLabel);
        }

        int end = stripped.indexOf("---", 3);
        if (end == -1) {
            throw new SkillParseException("Unclosed YAML frontmatter (missing closing ---) in " + sourceLabel);
        }

        String yamlBlock = stripped.substring(3, end);
        String body = stripped.substring(end + 3).stripLeading();

        @SuppressWarnings("unchecked")
        Map<String, Object> metaMap;
        try {
            Object loaded = YAML.load(yamlBlock);
            if (!(loaded instanceof Map)) {
                throw new SkillParseException("Frontmatter must be a YAML mapping in " + sourceLabel);
            }
            metaMap = (Map<String, Object>) loaded;
        } catch (SkillParseException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillParseException("Invalid YAML in frontmatter of " + sourceLabel + ": " + e.getMessage());
        }

        return new ParseResult(metaMap, body);
    }

    // ── Meta construction ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static SkillMeta metaFromMap(Map<String, Object> map, String defaultName, String sourceLabel) {
        // name
        String name = stringOrNull(map.get("name"));
        if (name == null || name.isBlank()) {
            name = defaultName;
        }
        if (!VALID_NAME.matcher(name).matches()) {
            LOG.warning("Invalid skill name '" + name + "' in " + sourceLabel + ", using '" + defaultName + "'");
            name = defaultName;
        }

        // description
        String description = stringOrNull(map.get("description"));
        if (description == null) description = "";

        // whenToUse — support both snake_case and camelCase
        String whenToUse = firstString(map, "when_to_use", "whenToUse");
        if (whenToUse == null) whenToUse = "";

        // tags
        List<String> tags = new ArrayList<>();
        Object tagsObj = map.get("tags");
        if (tagsObj instanceof List<?> l) {
            for (var t : l) {
                if (t instanceof String s) tags.add(s);
            }
        }

        // allowedTools — support both snake_case and camelCase
        List<String> allowedTools = new ArrayList<>();
        Object toolsObj = firstNotNull(map.get("allowed_tools"), map.get("allowedTools"));
        if (toolsObj instanceof List<?> l) {
            for (var t : l) {
                if (t instanceof String s) allowedTools.add(s);
            }
        }

        // mode — default "inline"
        String mode = stringOrNull(map.get("mode"));
        if (mode == null || mode.isBlank()) {
            mode = "inline";
        }

        // context field: double duty — "fork" triggers fork mode, otherwise sets fork_context
        String context = stringOrNull(map.get("context"));
        if ("fork".equals(context)) {
            mode = "fork";
            context = null; // don't use as fork_context value
        }
        if (!Set.of("inline", "fork").contains(mode)) {
            LOG.warning("Invalid mode '" + mode + "' in " + sourceLabel + ", using 'inline'");
            mode = "inline";
        }

        // model
        String model = stringOrNull(map.get("model"));
        if (model == null) model = "";

        // fork_context — support snake_case and camelCase; also accept legacy "context" value
        String forkContext = firstString(map, "fork_context", "forkContext");
        if (forkContext == null || forkContext.isBlank()) {
            // Fall back to context field if it wasn't "fork"
            if (context != null && !context.isBlank()) {
                forkContext = context;
            } else {
                forkContext = "none";
            }
        }
        if (!Set.of("full", "recent", "none").contains(forkContext)) {
            LOG.warning("Invalid fork_context '" + forkContext + "' in " + sourceLabel + ", using 'none'");
            forkContext = "none";
        }

        return new SkillMeta(name, description, whenToUse, tags, allowedTools, mode, model, forkContext);
    }

    /** Pick the first non-null value among candidates. */
    private static Object firstNotNull(Object... candidates) {
        for (var c : candidates) {
            if (c != null) return c;
        }
        return null;
    }

    /** Pick the first non-null trimmed string among named keys. */
    private static String firstString(Map<String, Object> map, String... keys) {
        for (var k : keys) {
            String v = stringOrNull(map.get(k));
            if (v != null) return v;
        }
        return null;
    }

    private static String stringOrNull(Object obj) {
        if (obj instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }
}
