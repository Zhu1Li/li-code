package com.licode.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.*;
import com.licode.config.ProviderConfig;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.conversation.ThinkingBlock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AnthropicClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.anthropic.client.AnthropicClient sdkClient;
    private final String systemPrompt;
    private final ProviderConfig config;
    private int maxOutputTokensOverride = -1;
    private volatile Thread streamThread;

    public AnthropicClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "API key not found. Set it in config.yaml as api_key, " +
                    "or via ANTHROPIC_API_KEY / DEEPSEEK_API_KEY env var.");
        }
        this.config = cfg;
        this.systemPrompt = systemPrompt;
        this.sdkClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(cfg.getBaseUrl())
                .build();
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        this.maxOutputTokensOverride = tokens;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        String model = ModelResolver.resolve(config.getModel());
        boolean thinking = config.isThinking();
        int maxOutputTokens = maxOutputTokensOverride > 0
                ? maxOutputTokensOverride : config.resolvedMaxOutputTokens();

        // Best-effort context window fetch
        config.setFetchedContextWindow(fetchModelContextWindow(model));

        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        streamThread = Thread.startVirtualThread(() -> {
            try {
                doStream(model, thinking, maxOutputTokens, conv.getMessagesInternal(), tools, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try {
                    queue.put(new StreamEvent.Error(classifyError(e).getMessage()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        return queue;
    }

    @Override
    public void cancelStream() {
        Thread t = streamThread;
        if (t != null) {
            streamThread = null;
            t.interrupt();
        }
    }

    private void doStream(String model, boolean thinking, int maxOutputTokens,
                          List<Message> messages, List<Map<String, Object>> tools,
                          BlockingQueue<StreamEvent> queue) throws Exception {

        var systemBlock = TextBlockParam.builder()
                .text(systemPrompt)
                .build();
        var messageParams = buildMessages(messages);

        // Diagnostic: dump message structure
//        System.err.println("[Anthropic] --- request messages (" + messageParams.size() + ") ---");
        for (int i = 0; i < messageParams.size(); i++) {
            var mp = messageParams.get(i);
            var content = mp.content();
            String desc;
            if (content.isString()) {
                desc = "text(" + Math.min(content.asString().length(), 80) + " chars)";
            } else if (content.isBlockParams()) {
                var sb = new StringBuilder();
                for (var block : content.asBlockParams()) {
                    if (block.isText()) sb.append("text,");
                    else if (block.isToolUse()) sb.append("tool_use,");
                    else if (block.isToolResult()) sb.append("tool_result,");
                    else if (block.isThinking()) sb.append("thinking,");
                    else sb.append("?,");
                }
                desc = "[" + sb.toString() + "]";
            } else {
                desc = "?";
            }
//            System.err.printf("[Anthropic]   [%d] %s: %s%n", i, mp.role(), desc);
        }

        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxOutputTokens)
                .system(MessageCreateParams.System.ofTextBlockParams(List.of(systemBlock)))
                .messages(messageParams);

        // Wire up tools
        if (tools != null && !tools.isEmpty()) {
            for (var toolSchema : tools) {
                try {
                    paramsBuilder.addTool(buildSdkTool(toolSchema));
                } catch (Exception e) {
                    // Skip malformed tool schemas
                }
            }
        }

        if (thinking) {
            if (ModelResolver.supportsAdaptiveThinking(model)) {
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build());
            } else {
                paramsBuilder.thinking(ThinkingConfigEnabled.builder()
                        .budgetTokens(maxOutputTokens - 1)
                        .build());
            }
        }

        var thinkingAccum = new StringBuilder();
        String thinkingSignature = "";
        boolean inThinking = false;
        String currentToolUseId = "";
        String currentToolUseName = "";
        var currentToolArgs = new StringBuilder();
        int inputTokens = 0, outputTokens = 0;
        long[] cacheReadTokens = {0}, cacheCreationTokens = {0};
        String stopReason = "end_turn";

        try (StreamResponse<RawMessageStreamEvent> streamResponse =
                     sdkClient.messages().createStreaming(paramsBuilder.build())) {

            var iterator = streamResponse.stream().iterator();
            while (iterator.hasNext() && !Thread.currentThread().isInterrupted()) {
                try {
                    var event = iterator.next();

                    if (event.isContentBlockStart()) {
                        var block = event.asContentBlockStart().contentBlock();
                        if (block.isThinking()) {
                            inThinking = true;
                            thinkingAccum.setLength(0);
                            thinkingSignature = "";
                        } else if (block.isToolUse()) {
                            var toolUse = block.asToolUse();
                            currentToolUseId = toolUse.id();
                            currentToolUseName = toolUse.name();
                            currentToolArgs.setLength(0);
                            queue.put(new StreamEvent.ToolCallStart(
                                    currentToolUseId, currentToolUseName));
                        }
                    } else if (event.isContentBlockDelta()) {
                        var delta = event.asContentBlockDelta().delta();
                        if (delta.isThinking()) {
                            String text = delta.asThinking().thinking();
                            thinkingAccum.append(text);
                            queue.put(new StreamEvent.ThinkingDelta(text));
                        } else if (delta.isSignature()) {
                            thinkingSignature = delta.asSignature().signature();
                        } else if (delta.isText()) {
                            queue.put(new StreamEvent.TextDelta(delta.asText().text()));
                        } else if (delta.isInputJson()) {
                            String partial = delta.asInputJson().partialJson();
                            currentToolArgs.append(partial);
                            queue.put(new StreamEvent.ToolCallDelta(
                                    currentToolUseId, partial));
                        }
                    } else if (event.isContentBlockStop()) {
                        if (inThinking) {
                            queue.put(new StreamEvent.ThinkingComplete(
                                    thinkingAccum.toString(), thinkingSignature));
                            inThinking = false;
                        }
                        if (!currentToolUseName.isEmpty()) {
                            Map<String, Object> parsedArgs = parseToolArgs(currentToolArgs.toString());
                            queue.put(new StreamEvent.ToolCallComplete(
                                    currentToolUseId, currentToolUseName, parsedArgs));
                            currentToolUseName = "";
                            currentToolUseId = "";
                            currentToolArgs.setLength(0);
                        }
                    } else if (event.isMessageDelta()) {
                        var msgDelta = event.asMessageDelta();
                        var sr = msgDelta.delta().stopReason();
                        if (sr.isPresent()) stopReason = sr.get().asString();
                        var usage = msgDelta.usage();
                        outputTokens = (int) usage.outputTokens();
                        if (usage.inputTokens().isPresent()) {
                            inputTokens = usage.inputTokens().get().intValue();
                        }
                        usage.cacheReadInputTokens().ifPresent(v -> cacheReadTokens[0] = v);
                        usage.cacheCreationInputTokens().ifPresent(v -> cacheCreationTokens[0] = v);
                    } else if (event.isMessageStart()) {
                        var msg = event.asMessageStart().message();
                        var usage = msg.usage();
                        inputTokens = (int) usage.inputTokens();
                        usage.cacheReadInputTokens().ifPresent(v -> cacheReadTokens[0] = v);
                        usage.cacheCreationInputTokens().ifPresent(v -> cacheCreationTokens[0] = v);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (!Thread.currentThread().isInterrupted()) {
            queue.put(new StreamEvent.StreamEnd(stopReason, inputTokens, outputTokens,
                    cacheReadTokens[0], cacheCreationTokens[0]));
        }
    }

    @SuppressWarnings("unchecked") // JsonValue.from(Map) is a generic call the compiler can't verify
    private List<MessageParam> buildMessages(List<Message> messages) {
        var result = new ArrayList<MessageParam>();
        for (var msg : messages) {
            String role = msg.getRole();

            boolean hasToolResults = msg.getToolResults() != null && !msg.getToolResults().isEmpty();

            // Tool result: send as user message with tool_result content block
            if ("tool_result".equals(role) || ("user".equals(role) && hasToolResults)) {
                var blocks = new ArrayList<ContentBlockParam>();
                if (msg.getToolResults() != null) {
                    for (var tr : msg.getToolResults()) {
                        blocks.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(tr.toolUseId())
                                        .content(tr.content())
                                        .isError(tr.isError())
                                        .build()));
                    }
                }
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(blocks)
                        .build());
                continue;
            }

            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();
            boolean hasThinking = msg.getThinkingBlocks() != null && !msg.getThinkingBlocks().isEmpty();

            if ("assistant".equals(role) && (hasThinking || hasToolUses)) {
                var content = new ArrayList<ContentBlockParam>();
                for (var tb : msg.getThinkingBlocks() != null ? msg.getThinkingBlocks() : List.<ThinkingBlock>of()) {
                    content.add(ContentBlockParam.ofThinking(
                            ThinkingBlockParam.builder()
                                    .thinking(tb.thinking())
                                    .signature(tb.signature())
                                    .build()));
                }
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    content.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(msg.getContent()).build()));
                }
                if (msg.getToolUses() != null) {
                    for (var tu : msg.getToolUses()) {
                        content.add(ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                        .id(tu.toolUseId())
                                        .name(tu.toolName())
                                        .input(com.anthropic.core.JsonValue.from(tu.arguments()))
                                        .build()));
                    }
                }
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(content)
                        .build());
            } else {
                var builder = MessageParam.builder()
                        .content(msg.getContent() != null ? msg.getContent() : "");
                if ("assistant".equals(role)) {
                    builder.role(MessageParam.Role.ASSISTANT);
                } else {
                    builder.role(MessageParam.Role.USER);
                }
                result.add(builder.build());
            }
        }
        return mergeConsecutiveSameRole(repairOrphanedToolUses(result));
    }

    /**
     * Scan for orphaned tool_use blocks (without corresponding tool_result in the next
     * message) and insert synthetic error tool_results so the API doesn't reject the request
     * with "tool_use ids were found without tool_result blocks immediately after".
     *
     * <p>This is a safety net for sessions resumed after an unclean interruption (Ctrl+C
     * during tool execution). The primary fix happens in SessionManager.truncateToLastCompleteRound
     * and Agent writing synthetic results on cancel; this catches any remaining edge cases.
     */
    private List<MessageParam> repairOrphanedToolUses(List<MessageParam> messages) {
        var repaired = new ArrayList<MessageParam>();
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            repaired.add(msg);

            // Only check assistant messages that contain tool_use blocks
            if (msg.role() != MessageParam.Role.ASSISTANT) continue;
            var content = msg.content();
            if (!content.isBlockParams()) continue;
            var blocks = content.asBlockParams();
            var toolUseIds = new ArrayList<String>();
            for (var block : blocks) {
                if (block.isToolUse()) {
                    toolUseIds.add(block.asToolUse().id());
                }
            }
            if (toolUseIds.isEmpty()) continue;

            // Check if next message has matching tool_result blocks for ALL ids
            boolean allCovered = false;
            if (i + 1 < messages.size()) {
                var next = messages.get(i + 1);
                if (next.content().isBlockParams()) {
                    var nextBlocks = next.content().asBlockParams();
                    var coveredIds = new java.util.HashSet<String>();
                    for (var block : nextBlocks) {
                        if (block.isToolResult()) {
                            coveredIds.add(block.asToolResult().toolUseId());
                        }
                    }
                    allCovered = coveredIds.containsAll(toolUseIds);
                }
            }

            if (!allCovered) {
                // Insert synthetic tool_result message
                var resultBlocks = new ArrayList<ContentBlockParam>();
                for (var id : toolUseIds) {
                    resultBlocks.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(id)
                                    .content("Tool execution was interrupted by user.")
                                    .isError(true)
                                    .build()));
                }
                repaired.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(resultBlocks)
                        .build());
            }
        }
        return repaired;
    }

    private List<MessageParam> mergeConsecutiveSameRole(List<MessageParam> messages) {
        if (messages.size() <= 1) return messages;
        var merged = new ArrayList<MessageParam>();
        merged.add(messages.getFirst());
        for (int i = 1; i < messages.size(); i++) {
            var prev = merged.getLast();
            var curr = messages.get(i);
            if (prev.role().equals(curr.role())) {
                var prevContent = prev.content();
                var currContent = curr.content();
                if (prevContent.isString() && currContent.isString()) {
                    merged.set(merged.size() - 1, MessageParam.builder()
                            .role(prev.role())
                            .content(prevContent.asString() + "\n\n" + currContent.asString())
                            .build());
                } else {
                    merged.add(curr);
                }
            } else {
                merged.add(curr);
            }
        }
        return merged;
    }

    private int fetchModelContextWindow(String model) {
        try {
            var info = sdkClient.models().retrieve(
                    model,
                    com.anthropic.core.RequestOptions.builder()
                            .timeout(java.time.Duration.ofSeconds(5))
                            .build());
            return info.maxInputTokens()
                    .map(Long::intValue)
                    .filter(v -> v > 0)
                    .orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseToolArgs(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Tool buildSdkTool(Map<String, Object> schema) {
        var builder = Tool.builder()
                .name((String) schema.get("name"));
        if (schema.containsKey("description")) {
            builder.description((String) schema.get("description"));
        }
        var inputSchema = (Map<String, Object>) schema.getOrDefault("input_schema",
                Map.of("type", "object", "properties", Map.of()));

        try {
            // Jackson → JsonNode → SDK JsonValue ensures nested Maps (e.g. properties
            // like {"command": {"type":"string",...}}) are fully serialised.  The raw
            // JsonValue.from(Map) path does not always recurse into nested Maps.
            String json = MAPPER.writeValueAsString(inputSchema);
            var node = MAPPER.readTree(json);
            var jsonValue = com.anthropic.core.JsonValue.fromJsonNode(node);
            java.util.Optional<java.util.Map<String, com.anthropic.core.JsonValue>> opt =
                    jsonValue.asObject();
            if (opt.isEmpty()) return builder.build();
            var additional = new java.util.LinkedHashMap<>(opt.get());
            com.anthropic.core.JsonValue type = additional.remove("type");

            builder.inputSchema(com.anthropic.models.messages.Tool.InputSchema.builder()
                    .type(type != null ? type : com.anthropic.core.JsonValue.from("object"))
                    .putAllAdditionalProperties(additional)
                    .build());
        } catch (Exception e) {
            // Fall back to a minimal schema if serialisation fails
            builder.inputSchema(com.anthropic.models.messages.Tool.InputSchema.builder()
                    .type(com.anthropic.core.JsonValue.from("object"))
                    .build());
        }
        return builder.build();
    }

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        if (e instanceof com.anthropic.errors.UnauthorizedException ue) {
            return new LlmException.AuthenticationException("Invalid API key: " + ue.getMessage());
        }
        if (e instanceof com.anthropic.errors.RateLimitException) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (e instanceof com.anthropic.errors.AnthropicIoException) {
            return new LlmException.NetworkException("Network error: " + e.getMessage(), e);
        }
        if (e instanceof com.anthropic.errors.AnthropicServiceException se) {
            int status = se.statusCode();
            String body = se.getMessage();
            if (status == 413 || (body != null && body.contains("prompt is too long"))) {
                return new LlmException.ContextTooLongException("Context too long: " + body);
            }
            return new LlmException("API error (" + status + "): " + body, se);
        }
        return new LlmException("Unexpected error: " + e.getMessage(), e);
    }
}
