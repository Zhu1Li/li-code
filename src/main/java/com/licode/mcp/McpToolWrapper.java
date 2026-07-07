package com.licode.mcp;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

class McpToolWrapper implements Tool {

    private final String serverName;
    private final McpSyncClient client;
    private final McpSchema.Tool toolDef;

    McpToolWrapper(String serverName, McpSyncClient client, McpSchema.Tool toolDef) {
        this.serverName = serverName;
        this.client = client;
        this.toolDef = toolDef;
    }

    @Override
    public String name() {
        return "mcp__" + McpManager.sanitizeName(serverName) + "__" + McpManager.sanitizeName(toolDef.name());
    }

    @Override
    public String description() {
        return toolDef.description();
    }

    @Override
    public Map<String, Object> inputSchema() {
        var raw = toolDef.inputSchema();
        var input = new java.util.LinkedHashMap<String, Object>();
        if (raw != null && !raw.isEmpty()) {
            input.putAll(raw);
        }
        if (input.isEmpty()) {
            input.put("type", "object");
            input.put("properties", Map.of());
        }
        return Map.of("name", name(), "description", description(), "input_schema", input);
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            var request = McpSchema.CallToolRequest.builder(toolDef.name())
                    .arguments(args)
                    .build();
            var result = client.callTool(request);
            var text = extractText(result);
            return ToolResult.success(text);
        } catch (Exception e) {
            return ToolResult.error("MCP tool call failed: " + e.getMessage());
        }
    }

    private static String extractText(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "(no output)";
        }
        var sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(tc.text());
            }
        }
        if (sb.isEmpty()) {
            return "(no output)";
        }
        return sb.toString();
    }
}
