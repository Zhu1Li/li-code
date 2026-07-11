package com.licode.agent;

import com.licode.compact.ContextCompactor;
import com.licode.compact.RecoveryState;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.ThinkingBlock;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;
import com.licode.hook.HookEngine;
import com.licode.llm.LlmClient;
import com.licode.llm.StreamEvent;
import com.licode.permission.PermissionChecker;
import com.licode.prompt.PlanModePrompt;
import com.licode.tool.Tool;
import com.licode.tool.ToolRegistry;
import com.licode.toolresult.ApplyResult;
import com.licode.toolresult.ContentReplacementState;
import com.licode.toolresult.ReplacementRecordsIO;
import com.licode.toolresult.ToolResultBudget;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class Agent {

    public static final int DEFAULT_MAX_ITERATIONS = 50;
    public static final int MAX_TOKENS_CEILING = 64_000;
    public static final int QUEUE_CAPACITY = 64;

    // 撞到迭代上限时强制"收尾轮"注入的指令：本轮剥掉全部工具，模型只能产文本，
    // 交代"已完成 / 未完成 / 卡在哪"，再由无工具调用的自然终结路径优雅结束。
    static final String WRAP_UP_INSTRUCTION = "<budget-exhausted>\n"
            + "You have reached the maximum tool-iteration budget. You cannot call any more "
            + "tools. Write a concise handoff for the user: (1) what you accomplished, (2) what "
            + "still remains to be done, and (3) any blocker or uncertainty. Do not attempt any "
            + "further actions.\n"
            + "</budget-exhausted>";

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;

    private int maxIterations = DEFAULT_MAX_ITERATIONS;
    private boolean planOnlyMode;
    private String workDir;

    // Extension points (null-guarded until wired by the runtime)
    private HookEngine hookEngine;      // HookEngine
    private PermissionChecker permissionChecker;
    private boolean bypass;             // ! prefix bypass for pre_tool_use hooks
    private java.util.function.Supplier<List<String>> notificationSupplier; // task notifications

    // Context management
    private final ContentReplacementState replacementState = new ContentReplacementState();
    private final RecoveryState recoveryState = new RecoveryState();

    // task-scoped stagnation-loop detector (lives with this Agent instance)
    private final ToolLoopDetector loopDetector = new ToolLoopDetector();
    private ContextCompactor.UsageAnchor usageAnchor;
    private final ContextCompactor.AutoCompactTrackingState compactTracking =
            new ContextCompactor.AutoCompactTrackingState();
    private int contextWindow;
    private int maxOutput;

    // Optional tool filter for skill whitelists
    private Predicate<Tool> toolFilter;

    // Context error recovery retry counter
    private int contextErrorRetries;
    private static final int MAX_CONTEXT_ERROR_RETRIES = 3;

    private volatile boolean cancelled;
    private Thread agentThread;

    record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}

    public Agent(LlmClient client, ToolRegistry registry, String protocol) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public void setPlanOnlyMode(boolean planOnlyMode) {
        this.planOnlyMode = planOnlyMode;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    public HookEngine getHookEngine() {
        return hookEngine;
    }

    public void setBypass(boolean bypass) {
        this.bypass = bypass;
    }

    public void setPermissionChecker(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    // Context management accessors
    public ContentReplacementState getReplacementState() {
        return replacementState;
    }

    public RecoveryState getRecoveryState() {
        return recoveryState;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public void setMaxOutput(int maxOutput) {
        this.maxOutput = maxOutput;
    }

    public void setToolFilter(Predicate<Tool> toolFilter) {
        this.toolFilter = toolFilter;
    }

    public Predicate<Tool> getToolFilter() {
        return toolFilter;
    }

    public void setNotificationSupplier(java.util.function.Supplier<List<String>> supplier) {
        this.notificationSupplier = supplier;
    }

    public ContextCompactor.UsageAnchor getUsageAnchor() {
        return usageAnchor;
    }

    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        LinkedBlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        agentThread = Thread.startVirtualThread(() -> {
            try {
                agentLoop(conv, queue);
            } catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            }
        });
        return queue;
    }

    public void cancel() {
        cancelled = true;
        if (agentThread != null) {
            agentThread.interrupt();
        }
        client.cancelStream();
    }

    /** True while the agent worker thread is still running. Used to distinguish a
     *  genuinely stalled agent from a long-running tool that simply hasn't emitted
     *  an event yet. */
    public boolean isAlive() {
        Thread t = agentThread;
        return t != null && t.isAlive();
    }

    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        boolean maxTokensEscalated = false;
        int textLessIterations = 0;
        final int MAX_TEXT_LESS_ITERATIONS = 8;
        boolean wrapUpDone = false;   // forced wrap-up turn used at most once
        // 目标漂移检查点：循环前锚定"最初的任务"，长任务里周期性重锚。
        String taskGoal = GoalCheckpoint.captureGoal(conv.getMessages());

        for (int iteration = 1; ; iteration++) {
            if (Thread.currentThread().isInterrupted() || cancelled) break;

            // this iteration strips tools (set on the wrap-up turn only).
            boolean forceNoTools = false;

            if (iteration > maxIterations) {
                if (!wrapUpDone) {
                    // 撞上限不硬 kill：只走一次收尾轮——置标志、注入收尾指令、本轮剥掉工具，
                    // 让模型产出交接摘要，随后由无工具调用的自然终结路径发 LoopComplete。
                    wrapUpDone = true;
                    conv.addSystemReminder(WRAP_UP_INSTRUCTION);
                    forceNoTools = true;
                } else {
                    // Wrap-up turn already ran (model kept calling tools) — hard stop.
                    putSafe(queue, new AgentEvent.ErrorEvent("Agent reached max iterations (" + maxIterations + ")"));
                    break;
                }
            }

            if (textLessIterations > MAX_TEXT_LESS_ITERATIONS) {
//                System.err.println("[LiCode Agent] " + textLessIterations
//                        + " consecutive tool-only iterations — model may not see results. "
//                        + "Try switching protocol (e.g., openai-compat).");
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Model appears stuck in a tool-calling loop after " + textLessIterations
                        + " tool-only iterations. This provider may not support tool results via "
                        + protocol + " protocol. Try openai-compat protocol."));
                break;
            }

            if (planOnlyMode) {
                conv.addSystemReminder(PlanModePrompt.buildReminder(iteration, false));
            }

            // 目标重锚：每隔 CHECK_INTERVAL 轮把最初的任务贴回，抵抗 goal drift。
            if (taskGoal != null && GoalCheckpoint.shouldCheck(iteration)) {
                conv.addSystemReminder(GoalCheckpoint.buildReminder(taskGoal, iteration));
            }

            // Inject background task notifications
            if (notificationSupplier != null) {
                List<String> notes = notificationSupplier.get();
                if (notes != null) {
                    for (String note : notes) {
                        conv.addSystemReminder("<task-notification>\n" + note + "\n</task-notification>");
                    }
                }
            }

            List<Map<String, Object>> toolSchemas = forceNoTools || registry == null || registry.listTools().isEmpty()
                    ? List.of()
                    : (toolFilter != null ? registry.toApiSchemas(protocol, toolFilter) : registry.toApiSchemas(protocol));

            // Layer 1+2 context management before each API request.
            // Benchmark ablation (LICODE_ABLATE=compaction) skips both layers so the
            // full raw history is sent every turn — the controlled-variable baseline.
            ConversationManager apiConv;
            if (com.licode.config.Ablation.compactionDisabled()) {
                apiConv = conv;
            } else {
                ContextCompactor.manage(conv, client, contextWindow, maxOutput, workDir,
                        compactTracking, recoveryState, usageAnchor);

                // ToolResultBudget — produce independent apiConv with spill/snip applied
                Path sessionDir = workDir != null ? Path.of(workDir) : Path.of(System.getProperty("user.dir"));
                ApplyResult applied = ToolResultBudget.apply(conv, sessionDir, replacementState);
                if (!applied.newRecords().isEmpty()) {
                    try {
                        ReplacementRecordsIO.append(sessionDir, applied.newRecords());
                    } catch (IOException e) {
                        // silently ignore — best-effort transcript
                    }
                }
                apiConv = applied.apiConv();
            }

            BlockingQueue<StreamEvent> streamQueue = client.stream(apiConv, toolSchemas);

            var textBuilder = new StringBuilder();
            var thinkingBuilder = new StringBuilder();
            String thinkingSignature = "";
            var toolCalls = new LinkedHashMap<String, ToolCallAccum>();
            int inputTokens = 0;
            int outputTokens = 0;
            String stopReason = "end_turn";
            boolean shouldBreak = false;
            boolean shouldContinue = false;

            try {
                while (!Thread.currentThread().isInterrupted() && !cancelled) {
                    // Generous deadline: extended-thinking models can go silent for a while
                    // before the first token. Only a truly dead stream should trip this.
                    StreamEvent event = streamQueue.poll(180, TimeUnit.SECONDS);
                    if (event == null) {
                        putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                        return;
                    }

                    switch (event) {
                        case StreamEvent.TextDelta td -> {
                            textBuilder.append(td.text());
                            putSafe(queue, new AgentEvent.StreamText(td.text()));
                        }
                        case StreamEvent.ThinkingDelta td -> {
                            thinkingBuilder.append(td.text());
                            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                        }
                        case StreamEvent.ThinkingComplete tc -> {
                            thinkingSignature = tc.signature();
                            putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                        }
                        case StreamEvent.ToolCallStart tcs -> {
                            var existing = toolCalls.get(tcs.toolCallId());
                            if (existing == null) {
                                toolCalls.put(tcs.toolCallId(),
                                        new ToolCallAccum(tcs.toolCallId(), tcs.toolName(), new StringBuilder()));
                            } else if (existing.name.startsWith("call_") && !tcs.toolName().startsWith("call_")) {
                                // Duplicate ToolCallStart from proxy: update placeholder name, keep args
                                existing = new ToolCallAccum(existing.id, tcs.toolName(), existing.argsBuilder);
                                existing.resolvedArgs = null;
                                toolCalls.put(tcs.toolCallId(), existing);
                            }
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolCallId(), tcs.toolName(), Map.of()));
                        }
                        case StreamEvent.ToolCallDelta tcd -> {
                            var accum = toolCalls.get(tcd.toolCallId());
                            if (accum == null) {
                                // Arguments arrived before ToolCallStart — create placeholder accum
                                accum = new ToolCallAccum(tcd.toolCallId(), "call_" + tcd.toolCallId(), new StringBuilder());
                                toolCalls.put(tcd.toolCallId(), accum);
                            }
                            accum.argsBuilder.append(tcd.argumentsDelta());
                            putSafe(queue, new AgentEvent.ToolCallDelta(tcd.toolCallId(), tcd.argumentsDelta()));
                        }
                        case StreamEvent.ToolCallComplete tcc -> {
                            var accum = toolCalls.get(tcc.toolCallId());
                            if (accum != null && tcc.arguments() != null) {
                                // Prefer completed event's parsed args; fall back to accumulated builder
                                if (!tcc.arguments().isEmpty()) {
                                    accum.resolvedArgs = tcc.arguments();
                                }
                            }
                            // Resolve: first completed args, then accumulated builder, then empty
                            Map<String, Object> args;
                            if (accum != null && accum.resolvedArgs != null) {
                                args = accum.resolvedArgs;
                            } else if (accum != null && !accum.argsBuilder.isEmpty()) {
                                args = parseJson(accum.argsBuilder.toString());
                                if (args == null) args = Map.of();
                            } else {
                                args = tcc.arguments() != null ? tcc.arguments() : Map.of();
                            }
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcc.toolCallId(), tcc.toolName(), args));
                        }
                        case StreamEvent.StreamEnd se -> {
                            stopReason = se.stopReason();
                            inputTokens = se.inputTokens();
                            outputTokens = se.outputTokens();
                            totalInputTokens += inputTokens;
                            totalOutputTokens += outputTokens;
                            putSafe(queue, new AgentEvent.UsageEvent(inputTokens, outputTokens,
                                    se.cacheReadTokens(), se.cacheCreationTokens()));

                            // Update usage anchor for accurate token estimation
                            usageAnchor = new ContextCompactor.UsageAnchor(
                                    inputTokens + outputTokens + (int) se.cacheReadTokens() + (int) se.cacheCreationTokens(),
                                    conv.size());

                            if ("max_tokens".equals(stopReason) && !maxTokensEscalated) {
                                maxTokensEscalated = true;
                                client.setMaxOutputTokens(MAX_TOKENS_CEILING);
                                putSafe(queue, new AgentEvent.ErrorEvent(
                                        "Output token limit hit. Escalating max output tokens and resuming..."));
                                writePartialAssistant(conv, textBuilder.toString(),
                                        thinkingBuilder, thinkingSignature, toolCalls);
                                conv.addUserMessage("Output token limit hit. Resume directly from where you stopped.");
                                shouldContinue = true;
                            }
                            break;
                        }
                        case StreamEvent.Error err -> {
                            String msg = err.message();
                            putSafe(queue, new AgentEvent.ErrorEvent(msg));
                            if (msg != null) {
                                String lower = msg.toLowerCase();
                                if (lower.contains("rate limit") || lower.contains("rate_limit")) {
                                    putSafe(queue, new AgentEvent.ErrorEvent("Rate limited, waiting 5s..."));
                                    try {
                                        Thread.sleep(5000);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                    shouldContinue = true;
                                    break;
                                }
                                if (lower.contains("tool_use") && lower.contains("tool_result")) {
                                    // Orphaned tool_use blocks — repair and retry
                                    putSafe(queue, new AgentEvent.ErrorEvent(
                                            "Detected orphaned tool_use blocks. Repairing conversation..."));
                                    conv.repairLastOrphanedToolUses();
                                    shouldContinue = true;
                                    break;
                                }
                                if (lower.contains("context") || lower.contains("too long")
                                        || lower.contains("prompt")) {
                                    // Try force compact and retry (up to 3 times)
                                    if (contextErrorRetries < MAX_CONTEXT_ERROR_RETRIES) {
                                        contextErrorRetries++;
                                        putSafe(queue, new AgentEvent.ErrorEvent(
                                                "Context too long, compacting..."));
                                        try {
                                            String result = ContextCompactor.forceCompact(
                                                    conv, client, contextWindow, workDir, recoveryState);
                                            putSafe(queue, new AgentEvent.StreamText("\n" + result + "\n"));
                                        } catch (Exception compactErr) {
                                            putSafe(queue, new AgentEvent.ErrorEvent(
                                                    "Compact failed: " + compactErr.getMessage()));
                                        }
                                        shouldContinue = true;
                                        break;
                                    }
                                    shouldBreak = true;
                                }
                            } else {
                                shouldBreak = true;
                            }
                        }
                    }

                    if (shouldContinue || (event instanceof StreamEvent.StreamEnd)) break;
                    if (shouldBreak) return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                putSafe(queue, new AgentEvent.ErrorEvent("Stream interrupted"));
                return;
            }

            if (shouldContinue) continue;
            if (shouldBreak) return;

            // Build tool call info list from collected accumulators
            var toolCallInfos = new ArrayList<ToolCallInfo>();
            for (var accum : toolCalls.values()) {
                Map<String, Object> args = accum.resolvedArgs;
                if (args == null && !accum.argsBuilder.isEmpty()) {
                    args = parseJson(accum.argsBuilder.toString());
                }
                if (args == null) args = Map.of();
                toolCallInfos.add(new ToolCallInfo(accum.id, accum.name, args));
            }

//            System.err.printf("[LiCode Agent] iteration=%d text=%d chars thinking=%d chars toolCalls=%d stop=%s textLess=%d%n",
//                    iteration, textBuilder.length(), thinkingBuilder.length(), toolCallInfos.size(), stopReason, textLessIterations);

            if (toolCallInfos.isEmpty()) {
                writeFinalAssistant(conv, textBuilder.toString(), thinkingBuilder, thinkingSignature);
                putSafe(queue, new AgentEvent.TurnComplete(iteration));
                putSafe(queue, new AgentEvent.LoopComplete(iteration, totalInputTokens, totalOutputTokens, stopReason));
                return;
            }

            for (var tc : toolCallInfos) {
                // Print args keys with values, truncating long values for readability
                var sb = new StringBuilder();
                sb.append(tc.toolName()).append("(");
                var entries = new ArrayList<>(tc.args().entrySet());
                for (int i = 0; i < entries.size(); i++) {
                    if (i > 0) sb.append(", ");
                    var e = entries.get(i);
                    Object v = e.getValue();
                    String vs = v instanceof String s && s.length() > 80 ? s.substring(0, 80) + "..." : String.valueOf(v);
                    sb.append(e.getKey()).append("=").append(vs);
                }
                sb.append(")");
//                System.err.printf("[LiCode Agent]   -> %s%n", sb.toString());
            }

            // Execute tools via StreamingExecutor (with hooks)
            var results = StreamingExecutor.executeAll(toolCallInfos, registry, planOnlyMode, queue,
                    permissionChecker, recoveryState, hookEngine, bypass);

            // T8: Write assistant message THEN tool results back to conversation
            var toolUseBlocks = new ArrayList<ToolUseBlock>();
            for (var accum : toolCalls.values()) {
                Map<String, Object> args = accum.resolvedArgs;
                if (args == null && !accum.argsBuilder.isEmpty()) {
                    args = parseJson(accum.argsBuilder.toString());
                }
                if (args == null) args = Map.of();
                toolUseBlocks.add(new ToolUseBlock(accum.id, accum.name, args));
            }

            writeAssistantWithTools(conv, textBuilder.toString(), thinkingBuilder, thinkingSignature, toolUseBlocks);

            // If cancelled during tool execution, write synthetic error results to prevent
            // orphaned tool_use blocks that will cause API rejection on resume.
            if (cancelled || Thread.currentThread().isInterrupted()) {
                var synthetic = new ArrayList<ToolResultBlock>();
                for (var tc : toolCallInfos) {
                    synthetic.add(new ToolResultBlock(tc.toolId(),
                            "Tool execution was interrupted by user.", true));
                }
                conv.addToolResultsMessage(synthetic);
                putSafe(queue, new AgentEvent.TurnComplete(iteration));
                return;
            }

            var toolResultBlocks = new ArrayList<ToolResultBlock>();
            for (var r : results) {
                toolResultBlocks.add(new ToolResultBlock(r.toolId(), r.output(), r.isError()));
            }
            conv.addToolResultsMessage(toolResultBlocks);

            // 把失败的工具调用喂给打转检测器：同一签名反复失败 → 先 NUDGE（本轮注入一条提示），
            // 提示后仍打转 → ABORT 终止。成功调用不记录，正常的重复读不会被误判为打转。
            var callById = new LinkedHashMap<String, ToolCallInfo>();
            for (var tc : toolCallInfos) callById.put(tc.toolId(), tc);
            boolean nudgedThisTurn = false;
            for (var r : results) {
                if (!r.isError()) continue;
                ToolCallInfo tc = callById.get(r.toolId());
                if (tc == null) continue;
                ToolLoopDetector.Verdict verdict = loopDetector.record(tc.toolName(), tc.args(), true);
                if (verdict == ToolLoopDetector.Verdict.ABORT) {
                    putSafe(queue, new AgentEvent.ErrorEvent(loopDetector.buildAbort(tc.toolName())));
                    return;
                }
                if (verdict == ToolLoopDetector.Verdict.NUDGE && !nudgedThisTurn) {
                    conv.addSystemReminder(loopDetector.buildNudge(tc.toolName(), ToolLoopDetector.TRIP_THRESHOLD));
                    nudgedThisTurn = true;
                }
            }

//            System.err.printf("[LiCode Agent] iteration=%d done: %d results written, conv now %d msgs%n",
//                    iteration, results.size(), conv.size());

            // T9: Increment text-less counter only if model produced neither text nor thinking
            if (textBuilder.toString().isBlank() && thinkingBuilder.toString().isBlank()) {
                textLessIterations++;
            } else {
                textLessIterations = 0;
            }

            putSafe(queue, new AgentEvent.TurnComplete(iteration));
        }
    }

    private void writePartialAssistant(ConversationManager conv, String text,
                                        StringBuilder thinkingBuilder, String thinkingSignature,
                                        LinkedHashMap<String, ToolCallAccum> toolCalls) {
        var thinkingBlocks = new ArrayList<ThinkingBlock>();
        if (!thinkingBuilder.isEmpty()) {
            thinkingBlocks.add(new ThinkingBlock(thinkingBuilder.toString(), thinkingSignature));
        }
        var toolUseBlocks = new ArrayList<ToolUseBlock>();
        for (var accum : toolCalls.values()) {
            Map<String, Object> args = accum.resolvedArgs;
            if (args == null && !accum.argsBuilder.isEmpty()) {
                args = parseJson(accum.argsBuilder.toString());
            }
            if (args == null) args = Map.of();
            toolUseBlocks.add(new ToolUseBlock(accum.id, accum.name, args));
        }
        if (!thinkingBlocks.isEmpty() || !text.isEmpty() || !toolUseBlocks.isEmpty()) {
            conv.addAssistantFull(text, thinkingBlocks.isEmpty() ? null : thinkingBlocks,
                    toolUseBlocks.isEmpty() ? null : toolUseBlocks, null);
        }
    }

    private void writeFinalAssistant(ConversationManager conv, String text,
                                      StringBuilder thinkingBuilder, String thinkingSignature) {
        if (!thinkingBuilder.isEmpty()) {
            conv.addAssistantFull(text,
                    List.of(new ThinkingBlock(thinkingBuilder.toString(), thinkingSignature)),
                    null, null);
        } else if (!text.isEmpty()) {
            conv.addAssistantFull(text, null, null, null);
        }
    }

    private void writeAssistantWithTools(ConversationManager conv, String text,
                                          StringBuilder thinkingBuilder, String thinkingSignature,
                                          List<ToolUseBlock> toolUseBlocks) {
        List<ThinkingBlock> thinkingBlocks = null;
        if (!thinkingBuilder.isEmpty()) {
            thinkingBlocks = List.of(new ThinkingBlock(thinkingBuilder.toString(), thinkingSignature));
        }
        conv.addAssistantFull(text.isEmpty() ? null : text, thinkingBlocks,
                toolUseBlocks.isEmpty() ? null : toolUseBlocks, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static class ToolCallAccum {
        final String id;
        final String name;
        final StringBuilder argsBuilder;
        Map<String, Object> resolvedArgs;

        ToolCallAccum(String id, String name, StringBuilder argsBuilder) {
            this.id = id;
            this.name = name;
            this.argsBuilder = argsBuilder;
        }
    }

    static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
