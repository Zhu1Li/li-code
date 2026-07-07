package com.licode.tool.impl;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GlobTool implements Tool {

    private static final String DESCRIPTION = """
            Find files matching a glob pattern, returning relative paths sorted alphabetically.

            Usage notes:
            - Supports patterns like "**/*.py", "src/**/*.ts", "*.go".
            - Search from "." or a specific path, never from "/".
            - Automatically skips .git, node_modules, __pycache__, and similar directories.
            - Use this instead of find or ls commands via Bash.""";

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "pattern", Map.of("type", "string", "description", "Glob pattern to match (e.g. '**/*.py')"),
                                "path", Map.of("type", "string", "description", "Base directory to search from", "default", ".")
                        ),
                        "required", List.of("pattern")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String pattern = ReadFileTool.stringArg(args, "pattern", "");
        String basePath = ReadFileTool.stringArg(args, "path", ".");
        if (basePath.isEmpty()) {
            basePath = ".";
        }
        if (pattern.isEmpty()) {
            return ToolResult.error("Error: pattern is required");
        }

        Path root = Path.of(basePath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return ToolResult.error("Error: path not found: " + basePath);
        }

        // Recognize doublestar `**/` prefix and treat it as "match basePattern
        // at any depth". Java's PathMatcher glob behaves differently on Windows
        // (e.g. `**/name` won't match root-level files), so we strip the prefix
        // and match by filename instead.
        String basePattern = pattern;
        while (basePattern.startsWith("**/")) {
            basePattern = basePattern.substring(3);
        }
        final boolean recursive = !basePattern.equals(pattern);

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + basePattern);
        var matches = new ArrayList<String>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (ToolConstants.SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = root.relativize(file);
                    boolean matched;
                    if (recursive) {
                        // **/<basePattern> — match basePattern against file name at any depth
                        matched = matcher.matches(file.getFileName());
                    } else {
                        matched = matcher.matches(file.getFileName())
                                || matcher.matches(rel);
                    }
                    if (matched) {
                        matches.add(rel.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ToolResult.error("Error: " + e.getMessage());
        }

        Collections.sort(matches);
        if (matches.isEmpty()) {
            return ToolResult.success("No files matched the pattern.");
        }
        return ToolResult.success(String.join("\n", matches));
    }
}
