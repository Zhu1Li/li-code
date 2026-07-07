package com.licode.tool.impl;

import com.licode.memory.MemoryManager;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 让 Agent 主动把一段内容写入长期记忆（跨会话），供后续会话开局自动注入复用。
 * 通用工具，不写死用途——项目风格 profile、约定、决策等都可存；/style-scan 用它
 * 落盘风格 profile，后续功能（如失败记忆）也可复用。
 */
public class SaveMemoryTool implements Tool {

    /** 与 MemoryManager 的固定 taxonomy 对齐。 */
    private static final Set<String> VALID_TYPES =
            Set.of("user", "feedback", "project", "reference", "task");

    private final MemoryManager memoryManager;

    public SaveMemoryTool(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Override
    public String name() {
        return "SaveMemory";
    }

    @Override
    public String description() {
        return "Persist a short, durable fact to long-term memory so future sessions inherit it. "
                + "Use for lasting project conventions, decisions, or a code-style profile — not transient notes.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "name", "SaveMemory",
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "type", Map.of(
                                        "type", "string",
                                        "enum", List.of("user", "feedback", "project", "reference", "task"),
                                        "description", "Memory category. Project-scoped facts use \"project\"."),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "The memory body (markdown). Keep it short — it is injected every session."),
                                "slug", Map.of(
                                        "type", "string",
                                        "description", "Optional stable file name. Reusing a slug overwrites that memory (e.g. \"code-style\").")),
                        "required", List.of("type", "content")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object typeObj = args.get("type");
        Object contentObj = args.get("content");
        Object slugObj = args.get("slug");

        String type = typeObj != null ? typeObj.toString().trim() : "";
        String content = contentObj != null ? contentObj.toString() : "";
        String slug = slugObj != null ? slugObj.toString().trim() : null;

        // 错误一律以 error 结果回灌，绝不抛异常打断主循环。
        if (type.isEmpty() || !VALID_TYPES.contains(type)) {
            return ToolResult.error("SaveMemory: invalid or missing 'type'. Must be one of " + VALID_TYPES + ".");
        }
        if (content.isBlank()) {
            return ToolResult.error("SaveMemory: 'content' must not be empty.");
        }

        memoryManager.addManual(content, type, slug);
        String where = (slug != null && !slug.isBlank()) ? type + "/" + slug : type;
        return ToolResult.success("Saved memory to " + where + ".");
    }
}
