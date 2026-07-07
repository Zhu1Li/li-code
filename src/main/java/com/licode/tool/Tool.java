package com.licode.tool;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    ToolCategory category();

    ToolResult execute(Map<String, Object> args);

    default boolean shouldDefer() { return false; }

    default boolean isSystemTool() { return false; }
}
