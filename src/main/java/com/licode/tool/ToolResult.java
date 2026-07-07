package com.licode.tool;

public record ToolResult(String output, boolean isError) {

    public static ToolResult success(String output) {
        return new ToolResult(output, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
