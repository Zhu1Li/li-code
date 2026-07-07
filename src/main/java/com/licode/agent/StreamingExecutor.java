package com.licode.agent;

import com.licode.compact.RecoveryState;
import com.licode.hook.HookEngine;
import com.licode.permission.PermissionChecker;
import com.licode.permission.PermissionMode;
import com.licode.permission.PermissionResponse;
import com.licode.subagent.AgentTool;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolRegistry;
import com.licode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class StreamingExecutor {

    private StreamingExecutor() {}

    public record ToolExecResult(String toolId, String toolName, String output,
                                  boolean isError, double elapsedSeconds) {}

    public static List<ToolExecResult> executeAll(List<Agent.ToolCallInfo> calls,
                                                   ToolRegistry registry,
                                                   boolean planOnlyMode,
                                                   BlockingQueue<AgentEvent> queue,
                                                   PermissionChecker checker) {
        return executeAll(calls, registry, planOnlyMode, queue, checker, null, null, false);
    }

    public static List<ToolExecResult> executeAll(List<Agent.ToolCallInfo> calls,
                                                   ToolRegistry registry,
                                                   boolean planOnlyMode,
                                                   BlockingQueue<AgentEvent> queue,
                                                   PermissionChecker checker,
                                                   RecoveryState recoveryState) {
        return executeAll(calls, registry, planOnlyMode, queue, checker, recoveryState, null, false);
    }

    public static List<ToolExecResult> executeAll(List<Agent.ToolCallInfo> calls,
                                                   ToolRegistry registry,
                                                   boolean planOnlyMode,
                                                   BlockingQueue<AgentEvent> queue,
                                                   PermissionChecker checker,
                                                   RecoveryState recoveryState,
                                                   HookEngine hookEngine,
                                                   boolean bypass) {
        var readCalls = new ArrayList<Agent.ToolCallInfo>();
        var otherCalls = new ArrayList<Agent.ToolCallInfo>();
        for (var call : calls) {
            var tool = registry.get(call.toolName());
            if (tool != null && tool.category() == ToolCategory.READ) {
                readCalls.add(call);
            } else {
                otherCalls.add(call);
            }
        }

        var results = new ArrayList<ToolExecResult>();

        if (readCalls.size() > 1) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = readCalls.stream()
                        .map(call -> executor.submit(() -> executeSingle(call, registry, planOnlyMode, queue, checker, recoveryState, hookEngine, bypass)))
                        .toList();
                for (var future : futures) {
                    try {
                        results.add(future.get(5, TimeUnit.MINUTES));
                    } catch (Exception ignored) {
                        results.add(new ToolExecResult("", "", "Internal error: tool execution timeout", true, 0));
                    }
                }
            }
        } else {
            for (var call : readCalls) {
                results.add(executeSingle(call, registry, planOnlyMode, queue, checker, recoveryState, hookEngine, bypass));
            }
        }

        for (var call : otherCalls) {
            results.add(executeSingle(call, registry, planOnlyMode, queue, checker, recoveryState, hookEngine, bypass));
        }

        return results;
    }

    private static ToolExecResult executeSingle(Agent.ToolCallInfo call,
                                                 ToolRegistry registry,
                                                 boolean planOnlyMode,
                                                 BlockingQueue<AgentEvent> queue,
                                                 PermissionChecker checker) {
        return executeSingle(call, registry, planOnlyMode, queue, checker, null, null, false);
    }

    private static ToolExecResult executeSingle(Agent.ToolCallInfo call,
                                                 ToolRegistry registry,
                                                 boolean planOnlyMode,
                                                 BlockingQueue<AgentEvent> queue,
                                                 PermissionChecker checker,
                                                 RecoveryState recoveryState) {
        return executeSingle(call, registry, planOnlyMode, queue, checker, recoveryState, null, false);
    }

    private static ToolExecResult executeSingle(Agent.ToolCallInfo call,
                                                 ToolRegistry registry,
                                                 boolean planOnlyMode,
                                                 BlockingQueue<AgentEvent> queue,
                                                 PermissionChecker checker,
                                                 RecoveryState recoveryState,
                                                 HookEngine hookEngine,
                                                 boolean bypass) {
        Tool tool = registry.get(call.toolName());
        if (tool == null) {
            var event = new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(),
                    "Unknown tool: " + call.toolName(), true, 0);
            Agent.putSafe(queue, event);
            return new ToolExecResult(call.toolId(), call.toolName(),
                    "Unknown tool: " + call.toolName(), true, 0);
        }

        // Plan-only mode: block write/command tools (legacy, superseded by PermissionChecker.PLAN)
        if (planOnlyMode && (tool.category() == ToolCategory.WRITE || tool.category() == ToolCategory.COMMAND)) {
            String msg = "Plan-only mode is active. Write tool '" + call.toolName()
                    + "' blocked. Use /plan to approve and exit plan mode.";
            var event = new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0);
            Agent.putSafe(queue, event);
            return new ToolExecResult(call.toolId(), call.toolName(), msg, true, 0);
        }

        // T6: Permission check
        if (checker != null) {
            var checkResult = checker.check(tool, call.args());
            switch (checkResult.decision()) {
                case DENY -> {
                    String msg = "Permission denied: " + checkResult.reason();
                    var event = new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0);
                    Agent.putSafe(queue, event);
                    return new ToolExecResult(call.toolId(), call.toolName(), msg, true, 0);
                }
                case ASK -> {
                    String desc = checker.describeToolAction(call.toolName(), call.args());
                    var future = new CompletableFuture<PermissionResponse>();
                    var reqEvent = new AgentEvent.PermissionRequestEvent(
                            call.toolId(), call.toolName(), desc, future);
                    Agent.putSafe(queue, reqEvent);

                    PermissionResponse response;
                    try {
                        response = future.get(5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        String msg = "Permission request timed out for: " + desc;
                        var event = new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0);
                        Agent.putSafe(queue, event);
                        return new ToolExecResult(call.toolId(), call.toolName(), msg, true, 0);
                    }

                    if (response == PermissionResponse.DENY) {
                        String msg = "User denied: " + desc;
                        var event = new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0);
                        Agent.putSafe(queue, event);
                        return new ToolExecResult(call.toolId(), call.toolName(), msg, true, 0);
                    }

                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        String content = PermissionChecker.extractContent(call.toolName(), call.args());
                        if (content != null) {
                            content = PermissionChecker.normalizeForAllowAlways(call.toolName(), content);
                            checker.addAllowAlwaysRule(call.toolName(), content);
                        }
                    }
                    // ALLOW or ALLOW_ALWAYS: fall through to execute
                }
                case ALLOW -> { /* fall through to execute */ }
            }
        }

        // Pre-tool hook (interceptable)
        if (hookEngine != null) {
            var preResult = hookEngine.runPreToolHooks(call.toolName(), call.args(), bypass);
            if (preResult.rejected()) {
                String msg = "Rejected by hook: " + preResult.message();
                var event = new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0);
                Agent.putSafe(queue, event);
                return new ToolExecResult(call.toolId(), call.toolName(), msg, true, 0);
            }
        }

        // Wire parent event queue to sub-agents so they can emit progress
        // during synchronous execution, preventing parent stream timeout.
        if (tool instanceof AgentTool agentTool) {
            agentTool.setParentQueue(queue);
        }

        long start = System.nanoTime();
        ToolResult result;
        try {
            result = tool.execute(call.args());
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());

            // Error hook
            if (hookEngine != null) {
                String errorFilePath = stringArg(call.args(), "file_path");
                var errorCtx = new HookEngine.HookContext(
                        HookEngine.EventName.ERROR, call.toolName(), call.args(), errorFilePath, null,
                        e.getMessage());
                hookEngine.runHooks(errorCtx);
            }
        } finally {
            if (tool instanceof AgentTool agentTool) {
                agentTool.setParentQueue(null);
            }
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        // Snapshot ReadFile results for recovery after compaction
        if (recoveryState != null && !result.isError() && "ReadFile".equals(call.toolName())) {
            Object filePathObj = call.args().get("file_path");
            if (filePathObj instanceof String filePath && !filePath.isEmpty()) {
                try {
                    String content = Files.readString(Path.of(filePath));
                    recoveryState.recordFileRead(filePath, content);
                } catch (IOException e) {
                    // silently ignore — best-effort snapshot
                }
            }
        }

        String output = result.output();
        // Safety cap only — real spill/snip is handled by ToolResultBudget and
        // ContextCompactor.offloadAndSnip before the next API request. Threshold
        // is high enough (500K) that normal tool outputs pass through intact so
        // the budget can decide per-result fate (spill to disk vs keep inline).
        if (output.length() > 500_000) {
            output = output.substring(0, 500_000) + "\n... (truncated to 500K for memory safety)";
        }

        // Post-tool hook
        if (hookEngine != null) {
            String postFilePath = stringArg(call.args(), "file_path");
            var postCtx = new HookEngine.HookContext(
                    HookEngine.EventName.POST_TOOL_USE, call.toolName(), call.args(), postFilePath, null, null);
            hookEngine.runHooks(postCtx);
        }

        var event = new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(),
                output, result.isError(), elapsed);
        Agent.putSafe(queue, event);

        return new ToolExecResult(call.toolId(), call.toolName(), output, result.isError(), elapsed);
    }

    private static String stringArg(Map<String, Object> args, String key) {
        if (args == null) return null;
        Object val = args.get(key);
        return val != null ? String.valueOf(val) : null;
    }
}
