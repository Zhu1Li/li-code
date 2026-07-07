package com.licode.skill;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

public class SkillCatalog {

    private static final Logger LOG = Logger.getLogger(SkillCatalog.class.getName());
    private static final String BUILTINS_PATH = "skills/builtins";

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();

    // ── Registration ────────────────────────────────────────────────

    public void register(Skill skill, String source) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), source);
    }

    // ── Lookup ──────────────────────────────────────────────────────

    public Optional<Skill> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.get(name));
    }

    public Optional<Skill> getFull(String name) {
        Skill cached = skills.get(name);
        if (cached == null) return Optional.empty();

        Path sourceDir = cached.sourceDir();
        if (sourceDir == null) {
            // Builtin — return cached
            return Optional.of(cached);
        }

        // Hot-reload from disk
        try {
            Skill fresh = SkillParser.parseSkillDir(sourceDir);
            if (fresh != null) {
                fresh = new Skill(fresh.meta(), fresh.promptBody(), sourceDir,
                        fresh.isDirectory() || Files.isDirectory(sourceDir), true);
                skills.put(name, fresh);
                return Optional.of(fresh);
            }
        } catch (Exception e) {
            LOG.warning("Hot-reload failed for skill '" + name + "', using cached: " + e.getMessage());
        }
        return Optional.of(cached);
    }

    public List<SkillMeta> list() {
        List<SkillMeta> result = new ArrayList<>();
        for (var skill : skills.values()) {
            result.add(skill.meta());
        }
        return result;
    }

    public String source(String name) {
        return sources.getOrDefault(name, "unknown");
    }

    // ── Catalog loading ─────────────────────────────────────────────

    public void loadCatalog(Path workDir) {
        // Tier 1: builtins (lowest priority)
        loadBuiltins(workDir);

        // Tier 2: user directory ~/.licode/skills/
        Path userDir = Path.of(System.getProperty("user.home"), ".licode", "skills");
        loadTier(userDir, "user");

        // Tier 3: project directory .licode/skills/ (highest priority)
        Path projectDir = workDir.resolve(".licode").resolve("skills");
        loadTier(projectDir, "project");
    }

    public void reload(Path workDir) {
        skills.clear();
        sources.clear();
        loadCatalog(workDir);
    }

    void loadTier(Path dir, String source) {
        if (!Files.isDirectory(dir)) return;

        var entries = dir.toFile().listFiles();
        if (entries == null) return;

        for (var entry : entries) {
            try {
                Skill skill;
                Path entryPath = entry.toPath();
                if (entry.isDirectory()) {
                    skill = SkillParser.parseSkillDir(entryPath);
                } else if (entry.isFile() && entry.getName().endsWith(".md")) {
                    skill = SkillParser.parseSkillMD(entryPath);
                } else {
                    continue;
                }
                if (skill != null) {
                    register(skill, source);
                }
            } catch (Exception e) {
                LOG.warning("Skipping " + source + " skill '" + entry.getName()
                        + "': " + e.getMessage());
            }
        }
    }

    // ── Builtins (from classpath resources) ─────────────────────────

    private void loadBuiltins(Path workDir) {
        URL builtinsUrl = getClass().getClassLoader().getResource(BUILTINS_PATH);
        if (builtinsUrl != null && "file".equals(builtinsUrl.getProtocol())) {
            // Exploded classes / IDE: load directly from filesystem
            try {
                loadTier(Path.of(builtinsUrl.toURI()), "builtin");
                return;
            } catch (Exception e) {
                LOG.fine("Failed to load builtins from " + builtinsUrl + ": " + e.getMessage());
            }
        }

        // JAR or other non-file protocol — try filesystem fallback (dev environment)
        if (workDir != null) {
            Path devPath = workDir.resolve("src/main/resources").resolve(BUILTINS_PATH);
            if (Files.isDirectory(devPath)) {
                LOG.fine("Loading builtins from filesystem: " + devPath);
                loadTier(devPath, "builtin");
                return;
            }
        }
    }

    // ── Active skills context (for system prompt) ───────────────────

    public String buildActiveContext(Set<String> activeSkillNames) {
        if (activeSkillNames == null || activeSkillNames.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("## Active Skills\n\n");
        sb.append("The following skills are currently active. ")
                .append("Their SOPs are pinned to the environment context and ")
                .append("will be visible at the top of every subsequent turn.\n");

        for (String name : activeSkillNames) {
            Skill skill = skills.get(name);
            if (skill == null || skill.promptBody().isBlank()) continue;
            sb.append("\n### ").append(name).append("\n\n");
            sb.append(skill.promptBody()).append('\n');
        }

        return sb.toString().stripTrailing();
    }

    // ── Visible for tests ───────────────────────────────────────────

    int size() {
        return skills.size();
    }
}
