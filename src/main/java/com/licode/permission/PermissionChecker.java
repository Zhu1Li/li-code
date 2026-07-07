package com.licode.permission;

import com.licode.tool.Tool;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class PermissionChecker {

    private PermissionMode mode;
    private final Path projectRoot;

    private final Set<String> allowAlwaysRules = new HashSet<>();
    private final ArrayList<PermissionRule> fileRules;
    private String planFilePath;

    // ── Layer 1a: Safe command whitelist ─────────────────────────────

    private static final Set<String> SAFE_COMMANDS = Set.of(
            "ls", "dir", "pwd", "echo", "cat", "head", "tail", "wc",
            "find", "which", "whereis", "whoami", "hostname", "uname",
            "date", "cal", "uptime", "df", "du", "free", "env", "printenv",
            "file", "stat", "readlink", "realpath", "basename", "dirname",
            "sort", "uniq", "tr", "cut", "awk", "sed", "grep", "egrep", "fgrep",
            "diff", "comm", "tee", "xargs", "true", "false", "test",
            "git status", "git log", "git diff", "git show", "git branch",
            "git tag", "git remote", "git rev-parse", "git ls-files",
            "git blame", "git stash list", "go version", "go env",
            "node -v", "npm -v", "npx", "python --version", "pip list",
            "cargo --version", "rustc --version", "java -version", "java --version"
    );

    // ── Layer 1b: Dangerous command patterns ─────────────────────────

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("rm\\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\\s+/"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+if=.*of=/dev/"),
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile(">\\s*/dev/sd")
    );

    // ── Layer 0: Plan mode exemptions ────────────────────────────────

    private static final Set<String> PLAN_MODE_ALLOWED_TOOLS = Set.of(
            "Agent", "ToolSearch", "AskUserQuestion", "ExitPlanMode"
    );

    // ── Content field extraction ─────────────────────────────────────

    private static final Map<String, String> CONTENT_FIELDS = Map.of(
            "Bash", "command",
            "ReadFile", "file_path",
            "WriteFile", "file_path",
            "EditFile", "file_path",
            "Glob", "pattern",
            "Grep", "pattern"
    );

    // ── Rule parsing ─────────────────────────────────────────────────

    private static final Pattern RULE_PATTERN = Pattern.compile("^(\\w+)\\((.+)\\)$");

    private record PermissionRule(String toolName, String pattern, RuleEffect effect) {
        boolean matches(String toolName, String content) {
            if (!this.toolName.equals(toolName)) return false;
            try {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                return matcher.matches(Path.of(content));
            } catch (Exception e) {
                return content.equals(pattern);
            }
        }
    }

    private enum RuleEffect { ALLOW, DENY }

    // ── Constructor ──────────────────────────────────────────────────

    public PermissionChecker(PermissionMode mode, Path projectRoot) {
        this.mode = mode;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.fileRules = new ArrayList<>(loadRules());
    }

    public PermissionMode getMode() { return mode; }
    public void setMode(PermissionMode mode) { this.mode = mode; }
    public void setPlanFilePath(String path) { this.planFilePath = path; }

    // ── CheckResult ──────────────────────────────────────────────────

    public record CheckResult(PermissionMode.Decision decision, String reason) {
        public static CheckResult allow() { return new CheckResult(PermissionMode.Decision.ALLOW, ""); }
        public static CheckResult deny(String reason) { return new CheckResult(PermissionMode.Decision.DENY, reason); }
        public static CheckResult ask() { return new CheckResult(PermissionMode.Decision.ASK, ""); }
    }

    // ── Main check entry point (T7) ──────────────────────────────────

    public CheckResult check(Tool tool, Map<String, Object> args) {
        String toolName = tool.name();
        String content = extractContent(toolName, args);

        // Layer 0: Plan mode exceptions — must come before sandbox
        if (mode == PermissionMode.PLAN) {
            if (PLAN_MODE_ALLOWED_TOOLS.contains(toolName)) {
                return CheckResult.allow();
            }
            if ("WriteFile".equals(toolName) || "EditFile".equals(toolName)) {
                String path = stringArg(args, "file_path", "");
                if (path.replace('\\', '/').contains(".licode/plans/")) {
                    return CheckResult.allow();
                }
            }
        }

        // Layer 1a: Safe commands (auto-allow)
        if ("Bash".equals(toolName) && content != null && isSafeCommand(content)) {
            return CheckResult.allow();
        }

        // Layer 1b: Dangerous command detection
        if ("Bash".equals(toolName) && content != null) {
            for (var pattern : DANGEROUS_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    return CheckResult.deny("Dangerous command detected: " + pattern.pattern());
                }
            }
        }

        // Layer 2: Path sandbox
        if (content != null && isPathTool(toolName)) {
            if (!isPathAllowed(content)) {
                return CheckResult.deny("Path outside allowed sandbox: " + content);
            }
        }

        // Layer 3: File-based permission rules (LIFO)
        if (content != null) {
            for (int i = fileRules.size() - 1; i >= 0; i--) {
                PermissionRule rule = fileRules.get(i);
                if (rule.matches(toolName, content)) {
                    return switch (rule.effect) {
                        case ALLOW -> CheckResult.allow();
                        case DENY -> CheckResult.deny("Denied by rule: " + rule.toolName + "(" + rule.pattern + ")");
                    };
                }
            }
        }

        // Layer 3b: Allow-always rules (session-level)
        // Use prefix matching: "allow always" for "git status" also covers "git status --porcelain"
        // but not "git push" — the current command must start with the stored rule.
        String key = toolName + ":" + content;
        if (allowAlwaysRules.contains(key)) {
            return CheckResult.allow();
        }
        for (String rule : allowAlwaysRules) {
            if (key.startsWith(rule)) {
                return CheckResult.allow();
            }
        }

        // Layer 4: Permission mode matrix
        var decision = mode.decide(tool.category());
        return switch (decision) {
            case ALLOW -> CheckResult.allow();
            case DENY -> CheckResult.deny("Denied by permission mode: " + mode);
            case ASK -> CheckResult.ask();
        };
    }

    public void addAllowAlwaysRule(String toolName, String content) {
        allowAlwaysRules.add(toolName + ":" + content);
        appendLocalRule(toolName, content);
    }

    // ── Rule loading from YAML files (T5) ────────────────────────────

    /**
     * Load permission rules from three layers:
     *   1. ~/.licode/permissions.yaml  (user global)
     *   2. {projectRoot}/.licode/permissions.yaml (project)
     *   3. {projectRoot}/.licode/permissions.local.yaml (session-persistent)
     */
    private List<PermissionRule> loadRules() {
        var rules = new ArrayList<PermissionRule>();

        Path userHome = Path.of(System.getProperty("user.home"));
        Path userFile = userHome.resolve(".licode").resolve("permissions.yaml");
        rules.addAll(loadRulesFile(userFile));

        if (projectRoot != null) {
            Path projectFile = projectRoot.resolve(".licode").resolve("permissions.yaml");
            rules.addAll(loadRulesFile(projectFile));

            Path localFile = projectRoot.resolve(".licode").resolve("permissions.local.yaml");
            rules.addAll(loadRulesFile(localFile));
        }

        return new ArrayList<>(rules);
    }

    public void appendLocalRule(String toolName, String pattern) {
        if (projectRoot == null) return;
        Path localFile = projectRoot.resolve(".licode").resolve("permissions.local.yaml");
        try {
            Files.createDirectories(localFile.getParent());
            var rules = new ArrayList<>(loadRulesFile(localFile));
            rules.add(new PermissionRule(toolName, pattern, RuleEffect.ALLOW));

            var entries = new ArrayList<Map<String, String>>();
            for (var r : rules) {
                entries.add(Map.of("rule", r.toolName + "(" + r.pattern + ")", "effect",
                        r.effect == RuleEffect.ALLOW ? "allow" : "deny"));
            }
            var yaml = new Yaml();
            Files.writeString(localFile, yaml.dump(entries));
            // Reload
            fileRules.clear();
            fileRules.addAll(loadRules());
        } catch (IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private List<PermissionRule> loadRulesFile(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return List.of();
        }

        Yaml yaml = new Yaml();
        Object parsed;
        try {
            parsed = yaml.load(content);
        } catch (Exception e) {
            return List.of();
        }

        if (!(parsed instanceof List<?> entries)) {
            return List.of();
        }

        var rules = new ArrayList<PermissionRule>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object ruleObj = map.get("rule");
            Object effectObj = map.get("effect");
            if (!(ruleObj instanceof String ruleStr) || !(effectObj instanceof String effectStr)) {
                continue;
            }

            RuleEffect effect;
            if ("allow".equals(effectStr)) {
                effect = RuleEffect.ALLOW;
            } else if ("deny".equals(effectStr)) {
                effect = RuleEffect.DENY;
            } else {
                continue;
            }

            java.util.regex.Matcher m = RULE_PATTERN.matcher(ruleStr.trim());
            if (!m.matches()) {
                continue;
            }
            rules.add(new PermissionRule(m.group(1), m.group(2), effect));
        }
        return rules;
    }

    // ── Safe command check (T3) ──────────────────────────────────────

    private boolean isSafeCommand(String command) {
        String trimmed = command.trim();
        if (trimmed.contains("|") || trimmed.contains(";") || trimmed.contains("&&")
                || trimmed.contains(">") || trimmed.contains("$(") || trimmed.contains("`")) {
            return false;
        }
        for (var safe : SAFE_COMMANDS) {
            if (trimmed.equals(safe) || trimmed.startsWith(safe + " ")) {
                return true;
            }
        }
        return false;
    }

    // ── Path sandbox helpers (T4) ────────────────────────────────────

    private boolean isPathTool(String toolName) {
        return "ReadFile".equals(toolName) || "WriteFile".equals(toolName) || "EditFile".equals(toolName);
    }

    private boolean isPathAllowed(String pathStr) {
        try {
            Path p = Path.of(pathStr).toAbsolutePath().normalize();
            Path tmp = Path.of("/tmp").toAbsolutePath().normalize();
            return p.startsWith(projectRoot) || p.startsWith(tmp);
        } catch (Exception e) {
            return true; // conservative: don't block on malformed paths
        }
    }

    // ── Content extraction (T6) ──────────────────────────────────────

    public static String extractContent(String toolName, Map<String, Object> args) {
        String field = CONTENT_FIELDS.get(toolName);
        if (field == null) return null;
        var v = args.get(field);
        if (!(v instanceof String s)) return null;
        // Normalize Bash commands: strip "cd <path> &&" / "cd <path> ;" prefixes
        // so that "cd /d D:\\project && git status" and "cd D:\\project && git status"
        // both normalize to "git status" for matching purposes.
        if ("Bash".equals(toolName)) {
            s = normalizeBashCommand(s);
        }
        return s;
    }

    /** Strip leading cd/chdir prefix so the actual command is what gets matched. */
    private static String normalizeBashCommand(String command) {
        String trimmed = command.trim();
        // Match "cd <anything> &&" or "cd <anything> ;" or "chdir <anything> &&"
        // On Windows, "cd /d <path>" is also common
        String withoutCd = trimmed
                .replaceFirst("^(cd|chdir)\\s+/d\\s+\\S+\\s*&&\\s*", "")
                .replaceFirst("^(cd|chdir)\\s+\\S+\\s*&&\\s*", "")
                .replaceFirst("^(cd|chdir)\\s+/d\\s+\\S+\\s*;\\s*", "")
                .replaceFirst("^(cd|chdir)\\s+\\S+\\s*;\\s*", "");
        return withoutCd.isEmpty() ? trimmed : withoutCd;
    }

    static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    public String describeToolAction(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "Bash" -> stringArg(args, "command", "");
            case "ReadFile" -> "Read: " + stringArg(args, "file_path", "");
            case "WriteFile" -> "Write: " + stringArg(args, "file_path", "");
            case "EditFile" -> "Edit: " + stringArg(args, "file_path", "");
            case "Glob" -> "Glob: " + stringArg(args, "pattern", "");
            case "Grep" -> "Grep: " + stringArg(args, "pattern", "");
            default -> toolName;
        };
    }

    // ── Allow-always normalization ─────────────────────────────────

    /**
     * Normalize content before storing an allow-always rule so that the same
     * operation on different files matches a single rule.
     *
     * <p>For Bash: strips trailing path-like arguments (e.g.
     * "git add src/main/Foo.java" → "git add"). For path tools: normalizes
     * to the parent directory so any file in that directory matches.
     */
    public static String normalizeForAllowAlways(String toolName, String content) {
        if (content == null) return null;
        if ("Bash".equals(toolName)) {
            return normalizeBashForRule(content);
        }
        if ("ReadFile".equals(toolName) || "WriteFile".equals(toolName) || "EditFile".equals(toolName)) {
            return normalizePathForRule(content);
        }
        return content;
    }

    private static String normalizeBashForRule(String command) {
        String[] parts = command.split("\\s+");
        int end = parts.length;
        while (end > 1 && isFilePathLike(parts[end - 1])) {
            end--;
        }
        if (end == parts.length) return command;
        return String.join(" ", java.util.Arrays.copyOf(parts, end));
    }

    private static boolean isFilePathLike(String arg) {
        if (arg.startsWith("-")) return false;
        if (arg.contains("/") || arg.contains("\\")) return true;
        if (arg.matches(".*\\.[a-zA-Z0-9]{1,10}$")) return true;
        if (arg.contains("*") || arg.contains("?")) return true;
        return false;
    }

    private static String normalizePathForRule(String path) {
        Path p = Path.of(path);
        Path parent = p.getParent();
        if (parent != null) {
            String result = parent.toString().replace('\\', '/');
            if (!result.endsWith("/")) result += "/";
            return result;
        }
        return path;
    }
}
