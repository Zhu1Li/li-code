package com.licode.tool.impl;

import com.licode.tool.FileStateCache;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class EditFileTool implements Tool {

    private FileStateCache fileStateCache;

    public void setFileStateCache(FileStateCache cache) {
        this.fileStateCache = cache;
    }

    private static final String DESCRIPTION = """
            Replace an exact string in a file. The old_string must appear exactly once in the file.

            Usage notes:
            - You MUST read the file with ReadFile before editing. This tool will fail otherwise.
            - When editing text from ReadFile output, preserve the exact indentation (tabs/spaces) as shown.
            - ALWAYS prefer editing existing files over creating new ones.
            - The edit will FAIL if old_string is not unique in the file. Provide more surrounding context to make it unique.
            - Use the smallest old_string that is clearly unique — 2-4 adjacent lines is usually sufficient.
            - The new_string must be different from old_string.
            - Use replace_all to replace and rename strings across the file.""";

    @Override
    public String name() {
        return "EditFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Path to the file to edit"),
                                "old_string", Map.of("type", "string", "description", "The exact string to find and replace (must be unique in file)"),
                                "new_string", Map.of("type", "string", "description", "The replacement string")
                        ),
                        "required", List.of("file_path", "old_string", "new_string")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = ReadFileTool.stringArg(args, "file_path", "");
        String oldStr = ReadFileTool.stringArg(args, "old_string", "");
        String newStr = ReadFileTool.stringArg(args, "new_string", "");

        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        Path path = Path.of(filePath);

        // Read-before-edit enforcement
        if (fileStateCache != null) {
            String absPath = path.toAbsolutePath().toString();
            String err = fileStateCache.validate(absPath);
            if (err != null) return ToolResult.error(err);
        }

        if (!Files.exists(path)) {
            return ToolResult.error("Error: file not found: " + filePath);
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }

        int count = countOccurrences(content, oldStr);
        if (count == 0) {
            return ToolResult.error("Error: old_string not found in file");
        }
        if (count > 1) {
            return ToolResult.error("Error: old_string found " + count + " times, must be unique");
        }

        String newContent = content.replace(oldStr, newStr);

        try {
            Files.writeString(path, newContent);
        } catch (IOException e) {
            return ToolResult.error("Error writing file: " + e.getMessage());
        }

        if (fileStateCache != null) {
            fileStateCache.update(path.toAbsolutePath().toString(), newContent);
        }

        return ToolResult.success("Successfully edited " + filePath);
    }

    private static int countOccurrences(String text, String sub) {
        if (sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
