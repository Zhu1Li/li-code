package com.licode.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.licode.config.ProviderConfig;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.*;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * LLM client using the OpenAI Java SDK Chat Completions API (streaming).
 * Best for genuine OpenAI endpoints. For third-party / compat endpoints,
 * use {@link OpenAiCompatClient}.
 *
 * Adapted from MewCode's OpenAiClient.
 */
public class OpenAIClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.openai.client.OpenAIClient sdkClient;
    private final String model;
    private final String systemPrompt;
    private int maxOutputTokens;
    private final boolean thinking;
    private volatile Thread streamThread;

    public OpenAIClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "API key not found. Set it in config.yaml as api_key, " +
                    "or via OPENAI_API_KEY / deepseek-key env var.");
        }
        this.sdkClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(cfg.getBaseUrl())
                .build();
        this.model = cfg.getModel();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();
        this.thinking = cfg.isThinking();
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        this.maxOutputTokens = tokens;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        streamThread = Thread.startVirtualThread(() -> {
            try {
                doStream(conv, tools, queue);
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

    private void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                          BlockingQueue<StreamEvent> queue) throws Exception {

        var paramsBuilder = ChatCompletionCreateParams.builder()
                .model(model)
                .putAdditionalBodyProperty("stream", JsonValue.from(true));

        if (maxOutputTokens > 0) {
            paramsBuilder.maxTokens((long) maxOutputTokens);
        }

        paramsBuilder.streamOptions(
                ChatCompletionStreamOptions.builder().includeUsage(true).build());

        if (thinking) {
            paramsBuilder.reasoningEffort(ReasoningEffort.HIGH);
        }

        // Messages
        paramsBuilder.addSystemMessage(systemPrompt);
        for (var msg : conv.getMessagesInternal()) {
            if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
                String content = msg.getContent() != null && !msg.getContent().isEmpty()
                        ? msg.getContent() : "";
                var assistantBuilder = ChatCompletionAssistantMessageParam.builder()
                        .content(content);
                for (var tu : msg.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (JsonProcessingException e) {
                        argsJson = "{}";
                    }
                    assistantBuilder.addToolCall(
                            ChatCompletionMessageFunctionToolCall.builder()
                                    .id(tu.toolUseId())
                                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                            .name(tu.toolName())
                                            .arguments(argsJson)
                                            .build())
                                    .build());
                }
                paramsBuilder.addMessage(assistantBuilder.build());
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                for (var tr : msg.getToolResults()) {
                    paramsBuilder.addMessage(ChatCompletionToolMessageParam.builder()
                            .toolCallId(tr.toolUseId())
                            .content(tr.content())
                            .build());
                }
            } else {
                String role = msg.getRole();
                String content = msg.getContent() != null ? msg.getContent() : "";
                if ("assistant".equals(role)) {
                    paramsBuilder.addAssistantMessage(content);
                } else {
                    paramsBuilder.addUserMessage(content);
                }
            }
        }

        // Tools
        if (tools != null && !tools.isEmpty()) {
            for (var schema : tools) {
                paramsBuilder.addFunctionTool(buildFunctionDef(schema));
            }
        }

        var toolNames = new HashMap<Integer, StringBuilder>();
        var toolArgs = new HashMap<Integer, StringBuilder>();
        var toolIds = new HashMap<Integer, String>();
        var reasoningBuilder = new StringBuilder();

        try (StreamResponse<ChatCompletionChunk> streamResponse =
                     sdkClient.chat().completions().createStreaming(paramsBuilder.build())) {

            var iterator = streamResponse.stream().iterator();
            while (iterator.hasNext() && !Thread.currentThread().isInterrupted()) {
                var chunk = iterator.next();

                // Usage (stream_options.include_usage)
                chunk.usage().ifPresent(usage -> {
                    // Usage chunks come at the end — handled in stream close
                });

                var choices = chunk.choices();
                if (choices == null || choices.isEmpty()) continue;

                var choice = choices.get(0);
                var delta = choice.delta();

                // Text content
                delta.content().ifPresent(text -> {
                    if (!text.isEmpty()) {
                        try {
                            queue.put(new StreamEvent.TextDelta(text));
                        } catch (InterruptedException ignored) {}
                    }
                });

                // Tool calls
                delta.toolCalls().ifPresent(toolCalls -> {
                    for (var tc : toolCalls) {
                        int idx = (int) tc.index();
                        tc.id().ifPresent(id -> toolIds.put(idx, id));
                        tc.function().ifPresent(fn -> {
                            fn.name().ifPresent(name -> {
                                // Only emit ToolCallStart on first occurrence
                                if (!toolNames.containsKey(idx)) {
                                    toolNames.put(idx, new StringBuilder(name));
                                    String callId = toolIds.getOrDefault(idx, "call_" + idx);
                                    try {
                                        queue.put(new StreamEvent.ToolCallStart(callId, name));
                                    } catch (InterruptedException ignored) {}
                                }
                            });
                            fn.arguments().ifPresent(args -> {
                                toolArgs.computeIfAbsent(idx, k -> new StringBuilder())
                                        .append(args);
                                String callId = toolIds.getOrDefault(idx, "call_" + idx);
                                try {
                                    queue.put(new StreamEvent.ToolCallDelta(callId, args));
                                } catch (InterruptedException ignored) {}
                            });
                        });
                    }
                });

                // Finish reason
                var finishReason = choice.finishReason();
                if (finishReason.isPresent()) {
                    var fr = finishReason.get();
                    if (fr == ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS) {
                        flushReasoning(queue, reasoningBuilder);
                        flushPendingToolCalls(queue, toolNames, toolArgs, toolIds);
                    } else if (fr == ChatCompletionChunk.Choice.FinishReason.STOP
                            || fr == ChatCompletionChunk.Choice.FinishReason.LENGTH) {
                        String stopReason = fr == ChatCompletionChunk.Choice.FinishReason.LENGTH
                                ? "max_tokens" : "end_turn";
                        flushReasoning(queue, reasoningBuilder);
                        queue.put(new StreamEvent.StreamEnd(stopReason,
                                chunk.usage().map(u -> (int) u.promptTokens()).orElse(0),
                                chunk.usage().map(u -> (int) u.completionTokens()).orElse(0)));
                    }
                }
            }
        }

        // If stream ended without explicit finish_reason, emit StreamEnd
        if (!Thread.currentThread().isInterrupted()) {
            flushReasoning(queue, reasoningBuilder);
            flushPendingToolCalls(queue, toolNames, toolArgs, toolIds);
        }
    }

    private void flushReasoning(BlockingQueue<StreamEvent> queue, StringBuilder reasoningBuilder)
            throws InterruptedException {
        if (!reasoningBuilder.isEmpty()) {
            queue.put(new StreamEvent.ThinkingComplete(reasoningBuilder.toString(), ""));
            reasoningBuilder.setLength(0);
        }
    }

    private void flushPendingToolCalls(BlockingQueue<StreamEvent> queue,
                                       Map<Integer, StringBuilder> toolNames,
                                       Map<Integer, StringBuilder> toolArgs,
                                       Map<Integer, String> toolIds) throws InterruptedException {
        if (toolNames.isEmpty()) return;

        var sorted = new ArrayList<>(toolNames.keySet());
        Collections.sort(sorted);

        for (int idx : sorted) {
            String name = toolNames.get(idx).toString();
            String callId = toolIds.getOrDefault(idx, "call_" + idx);
            String rawArgs = toolArgs.containsKey(idx) ? toolArgs.get(idx).toString() : "{}";

            Map<String, Object> args;
            try {
                @SuppressWarnings("unchecked")
                var parsed = MAPPER.readValue(rawArgs, Map.class);
                args = parsed;
            } catch (Exception e) {
                args = Map.of();
            }
            queue.put(new StreamEvent.ToolCallComplete(callId, name, args));
        }

        toolNames.clear();
        toolArgs.clear();
        toolIds.clear();
    }

    @SuppressWarnings("unchecked")
    private FunctionDefinition buildFunctionDef(Map<String, Object> schema) {
        var builder = FunctionDefinition.builder()
                .name((String) schema.get("name"));
        if (schema.containsKey("description")) {
            builder.description(Objects.toString(schema.get("description"), ""));
        }
        var params = (Map<String, Object>) schema.getOrDefault("parameters",
                schema.getOrDefault("input_schema", Map.of()));
        builder.parameters(FunctionParameters.builder()
                .putAllAdditionalProperties(toJsonValueMap(params))
                .build());
        return builder.build();
    }

    private Map<String, JsonValue> toJsonValueMap(Map<String, Object> map) {
        var result = new LinkedHashMap<String, JsonValue>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return result;
    }

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String lower = msg.toLowerCase();

        if (e instanceof com.openai.errors.UnauthorizedException) {
            return new LlmException.AuthenticationException("Invalid API key: " + msg);
        }
        if (e instanceof com.openai.errors.RateLimitException) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (e instanceof com.openai.errors.BadRequestException) {
            if (lower.contains("context_length_exceeded") || lower.contains("prompt is too long")) {
                return new LlmException.ContextTooLongException("Context too long: " + msg);
            }
            return new LlmException("Bad request: " + msg, e);
        }
        if (e instanceof com.openai.errors.OpenAIServiceException se) {
            if (se.statusCode() == 413) {
                return new LlmException.ContextTooLongException("Context too long: " + msg);
            }
            return new LlmException("API error (" + se.statusCode() + "): " + msg, se);
        }
        if (e instanceof com.openai.errors.OpenAIIoException) {
            return new LlmException.NetworkException("Network error: " + msg, e);
        }
        return new LlmException("Unexpected error: " + msg, e);
    }
}
