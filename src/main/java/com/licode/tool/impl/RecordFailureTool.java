package com.licode.tool.impl;

import com.licode.failure.FailureStore;
import com.licode.failure.FailureStore.FailureLesson;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 让 Agent 把一次"修好的失败"沉淀成失败记忆：根因 + 修复 + 关键词，供下次相似 bug 参考。
 * 与 {@link RecallFailuresTool} 对称（一写一读）。
 */
public class RecordFailureTool implements Tool {

    private final FailureStore store;

    public RecordFailureTool(FailureStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "RecordFailure";
    }

    @Override
    public String description() {
        return "Record a resolved failure (root cause + fix + keywords) into the project's failure "
                + "memory, so a similar bug can be recalled next time. Call after fixing a test/bug.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "name", "RecordFailure",
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "keywords", Map.of("type", "string",
                                        "description", "Comma-separated keywords: test name, exception type, symbols, error terms."),
                                "root_cause", Map.of("type", "string", "description", "What actually caused the failure."),
                                "fix", Map.of("type", "string", "description", "What fixed it."),
                                "test_name", Map.of("type", "string", "description", "Optional failing test name."),
                                "exception", Map.of("type", "string", "description", "Optional exception/error type.")),
                        "required", List.of("keywords", "root_cause", "fix")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String keywords = str(args.get("keywords"));
        String rootCause = str(args.get("root_cause"));
        String fix = str(args.get("fix"));
        String testName = str(args.get("test_name"));
        String exception = str(args.get("exception"));

        if (keywords.isBlank()) return ToolResult.error("RecordFailure: 'keywords' is required.");
        if (rootCause.isBlank()) return ToolResult.error("RecordFailure: 'root_cause' is required.");
        if (fix.isBlank()) return ToolResult.error("RecordFailure: 'fix' is required.");

        List<String> kw = Arrays.stream(keywords.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        store.record(new FailureLesson(kw, rootCause, fix,
                testName.isBlank() ? null : testName,
                exception.isBlank() ? null : exception,
                Instant.now()));
        return ToolResult.success("Recorded failure lesson (" + kw.size() + " keywords).");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
