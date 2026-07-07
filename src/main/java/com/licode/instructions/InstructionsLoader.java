package com.licode.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Loads project and user instruction files (LICODE.md) with three-layer
 * priority and {@code @include} resolution.
 *
 * <p>Priority (highest first):
 * <ol>
 *   <li>{@code ./LICODE.md} — project-level, checked into git</li>
 *   <li>{@code ./.licode/LICODE.md} — project-level, gitignore-able</li>
 *   <li>{@code ~/.licode/LICODE.md} — user-level, cross-project</li>
 * </ol>
 */
public final class InstructionsLoader {

    static final int MAX_INCLUDE_DEPTH = 3;

    private InstructionsLoader() {}

    /**
     * Loads and concatenates all three layers of LICODE.md.
     * Returns empty string when no files exist.
     */
    public static String load(Path workDir) {
        Path userHome = Path.of(System.getProperty("user.home"));
        var sb = new StringBuilder();

        appendFileContent(sb, workDir.resolve("LICODE.md"));
        appendFileContent(sb, workDir.resolve(".licode").resolve("LICODE.md"));
        appendFileContent(sb, userHome.resolve(".licode").resolve("LICODE.md"));

        String raw = sb.toString();
        if (raw.isEmpty()) return "";

        return resolveIncludes(raw, workDir, 0, new LinkedHashSet<>());
    }

    /**
     * Computes a SHA-256 fingerprint of the loaded instructions for staleness checks.
     */
    public static String fingerprint(Path workDir) {
        String content = load(workDir);
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    private static void appendFileContent(StringBuilder sb, Path file) {
        try {
            if (Files.exists(file)) {
                String content = Files.readString(file).strip();
                if (!content.isEmpty()) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(content);
                }
            }
        } catch (IOException ignored) {
            // Best-effort: silently skip unreadable files
        }
    }

    // ── @include resolution ──────────────────────────────────────────

    /**
     * Recursively resolves @include directives in the given content.
     *
     * @param content  the markdown text to scan for @include lines
     * @param baseDir  the directory against which relative paths resolve
     * @param depth    current nesting depth (0-based)
     * @param visited  set of already-included absolute paths for cycle detection
     * @return content with all @include lines replaced by included file contents
     */
    static String resolveIncludes(String content, Path baseDir, int depth, Set<Path> visited) {
        if (depth >= MAX_INCLUDE_DEPTH) return content;

        var lines = content.lines().toList();
        var sb = new StringBuilder(content.length() + 1024);

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("@include ")) {
                String includePath = trimmed.substring("@include ".length()).strip();
                String resolved = resolveInclude(includePath, baseDir, depth, visited);
                sb.append(resolved);
            } else {
                sb.append(line).append('\n');
            }
        }

        return sb.toString();
    }

    private static String resolveInclude(String includePath, Path baseDir, int depth, Set<Path> visited) {
        // Expand ~ to user home
        if (includePath.startsWith("~")) {
            includePath = System.getProperty("user.home") + includePath.substring(1);
        }

        Path target;
        String normalized = includePath.replace('\\', '/');
        if (normalized.startsWith("/") || (normalized.length() >= 2 && normalized.charAt(1) == ':')) {
            target = Path.of(includePath);
        } else {
            target = baseDir.resolve(includePath);
        }
        target = target.normalize();

        // Only .md files allowed
        if (!target.getFileName().toString().endsWith(".md")) {
            System.err.println("[LiCode] @include rejected: not a .md file — " + includePath);
            return "";
        }

        // Check path stays within project root
        if (!target.startsWith(baseDir) && !target.startsWith(Path.of(System.getProperty("user.home")))) {
            System.err.println("[LiCode] @include rejected: path escapes project — " + includePath);
            return "";
        }

        Path absoluteKey = target.toAbsolutePath().normalize();
        if (!visited.add(absoluteKey)) {
            System.err.println("[LiCode] @include rejected: circular reference — " + includePath);
            return "";
        }

        try {
            if (!Files.exists(target)) {
                System.err.println("[LiCode] @include file not found — " + target);
                return "";
            }
            String includedContent = Files.readString(target).strip();
            // Collect parent baseDir for nested includes
            Path newBaseDir = (target.getParent() != null) ? target.getParent() : baseDir;
            return resolveIncludes(includedContent, newBaseDir, depth + 1, visited);
        } catch (IOException e) {
            System.err.println("[LiCode] @include read error — " + target + ": " + e.getMessage());
            return "";
        }
    }
}
