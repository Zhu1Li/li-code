package com.licode.tool.impl;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolRegistry;
import com.licode.tool.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GrepTool implements Tool {

    private static final String DESCRIPTION = """
            Search file contents using a regex pattern, returning file:line:content matches.

            Usage notes:
            - Supports full regex syntax (e.g., "log.*Error", "func\\s+\\w+").
            - Filter files with the include parameter (e.g., "*.py", "*.go").
            - Search from "." or a specific path, never from "/".
            - Use this instead of grep or rg commands via Bash.
            - Automatically skips .git, node_modules, __pycache__, and similar directories.""";

    @Override
    public String name() {
        return "Grep";
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
                                "pattern", Map.of("type", "string", "description", "Regex pattern to search for"),
                                "path", Map.of("type", "string", "description", "Base directory to search from", "default", "."),
                                "include", Map.of("type", "string", "description", "Glob filter for filenames (e.g. '*.py')")
                        ),
                        "required", List.of("pattern")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String pattern = ReadFileTool.stringArg(args, "pattern", "");
        String basePath = ReadFileTool.stringArg(args, "path", ".");
        String include = ReadFileTool.stringArg(args, "include", "");
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

        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Error: invalid regex: " + e.getMessage());
        }

        PathMatcher includeMatcher = include.isEmpty()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + include);

        var files = new ArrayList<Path>();
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
                    if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file);
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

        Collections.sort(files);

        var results = new ArrayList<String>();
        int totalChars = 0;

        for (Path file : files) {
            if (isBinaryFile(file)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    if (regex.matcher(line).find()) {
                        String rel = root.relativize(file).toString();
                        String entry = rel + ":" + lineNum + ":" + line;
                        totalChars += entry.length() + 1;
                        if (totalChars > ToolRegistry.MAX_OUTPUT_CHARS) {
                            results.add("... output truncated (max " + ToolRegistry.MAX_OUTPUT_CHARS + " chars)");
                            return ToolResult.success(String.join("\n", results));
                        }
                        results.add(entry);
                    }
                }
            } catch (IOException e) {
                // Skip files that can't be read
            }
        }

        if (results.isEmpty()) {
            return ToolResult.success("No matches found.");
        }
        return ToolResult.success(String.join("\n", results));
    }

    private static boolean isBinaryFile(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[512];
            int bytesRead = is.read(buf);
            if (bytesRead <= 0) {
                return false;
            }
            for (int i = 0; i < bytesRead; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
