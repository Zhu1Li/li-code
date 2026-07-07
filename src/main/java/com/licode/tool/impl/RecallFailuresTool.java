package com.licode.tool.impl;

import com.licode.failure.FailureStore;
import com.licode.failure.FailureStore.FailureLesson;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 让 Agent 遇到 bug 时先查历史：按关键词模糊匹配失败记忆，返回最相关的 top-k lesson。
 * 只读，不触发写权限。与 {@link RecordFailureTool} 对称（一读一写）。
 */
public class RecallFailuresTool implements Tool {

    private final FailureStore store;

    public RecallFailuresTool(FailureStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "RecallFailures";
    }

    @Override
    public String description() {
        return "Look up past failures similar to the current one (keyword fuzzy match) before "
                + "debugging, to reuse a known root cause / fix. Pass keywords from the failing "
                + "test name, exception type, and error message.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "name", "RecallFailures",
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "keywords", Map.of("type", "string",
                                        "description", "Query keywords: failing test name, exception type, error terms."),
                                "limit", Map.of("type", "integer",
                                        "description", "Max lessons to return (default 3).")),
                        "required", List.of("keywords")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String keywords = args.get("keywords") == null ? "" : args.get("keywords").toString().trim();
        if (keywords.isBlank()) return ToolResult.error("RecallFailures: 'keywords' is required.");
        int limit = parseLimit(args.get("limit"));

        List<FailureLesson> hits = store.recall(keywords, limit);
        if (hits.isEmpty()) {
            return ToolResult.success("No similar past failures recorded.");
        }

        var sb = new StringBuilder("Similar past failures (" + hits.size() + "):\n");
        int i = 1;
        for (FailureLesson h : hits) {
            sb.append('\n').append(i++).append(". keywords: ").append(String.join(", ", h.keywords())).append('\n');
            if (h.testName() != null) sb.append("   test: ").append(h.testName()).append('\n');
            if (h.exception() != null) sb.append("   exception: ").append(h.exception()).append('\n');
            sb.append("   root cause: ").append(h.rootCause()).append('\n');
            sb.append("   fix: ").append(h.fix()).append('\n');
        }
        return ToolResult.success(sb.toString().stripTrailing());
    }

    private static int parseLimit(Object o) {
        if (o == null) return FailureStore.DEFAULT_RECALL_LIMIT;
        try {
            if (o instanceof Number n) return n.intValue();
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return FailureStore.DEFAULT_RECALL_LIMIT;
        }
    }
}
