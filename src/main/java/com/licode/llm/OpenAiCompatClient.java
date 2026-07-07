package com.licode.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.licode.config.ProviderConfig;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * LLM client targeting the OpenAI Chat Completions API ({@code /chat/completions})
 * via raw HTTP + manual SSE parsing. Compatible with any provider that exposes
 * a {@code /chat/completions} endpoint (DeepSeek, vLLM, Ollama, etc.).
 *
 * Adapted from MewCode's OpenAiCompatClient.
 */
public class OpenAiCompatClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    private int maxOutputTokens;
    private final boolean thinking;
    private volatile Thread streamThread;

    public OpenAiCompatClient(ProviderConfig cfg, String systemPrompt) {
        String key = cfg.resolvedApiKey();
        if (key == null || key.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "API key not found. Set it in config.yaml as api_key, " +
                    "or via OPENAI_API_KEY / deepseek-key env var.");
        }
        this.apiKey = key;
        this.baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
        this.model = cfg.getModel();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();
        this.thinking = cfg.isThinking();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
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

        String body = buildRequestBody(conv.getMessagesInternal(), tools);

        // Diagnostic: dump message summary
//        System.err.println("[OpenAiCompat] --- request messages (" + conv.size() + ") ---");
        for (var msg : conv.getMessagesInternal()) {
            boolean hasTU = msg.getToolUses() != null && !msg.getToolUses().isEmpty();
            boolean hasTR = msg.getToolResults() != null && !msg.getToolResults().isEmpty();
            boolean hasTH = msg.getThinkingBlocks() != null && !msg.getThinkingBlocks().isEmpty();
            String text = msg.getContent() != null ? msg.getContent() : "";
            var sb = new StringBuilder();
            if (hasTH) sb.append(" THINKING");
            if (hasTU) sb.append(" TOOL_USE");
            if (hasTR) sb.append(" TOOL_RESULT");
            if (!text.isEmpty()) sb.append(" text=").append(Math.min(text.length(), 60));
//            System.err.printf("[OpenAiCompat]   %s:%s%n", msg.getRole(), sb.toString());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        int statusCode = response.statusCode();
        if (statusCode != 200) {
            String errBody;
            try (var is = response.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("HTTP " + statusCode + ": " + errBody);
        }

        // Tool-call accumulation state (keyed by index)
        var toolNames = new HashMap<Integer, StringBuilder>();
        var toolArgs = new HashMap<Integer, StringBuilder>();
        var toolIds = new HashMap<Integer, String>();
        var reasoningBuilder = new StringBuilder();
        boolean streamEnded = false;

        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.startsWith(":")) continue;

                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();

                if ("[DONE]".equals(data)) {
                    flushReasoning(queue, reasoningBuilder);
                    flushPendingToolCalls(queue, toolNames, toolArgs, toolIds);
                    if (!streamEnded) {
                        queue.put(new StreamEvent.StreamEnd("end_turn", 0, 0));
                    }
                    break;
                }

                streamEnded = handleSseData(data, queue, toolNames, toolArgs, toolIds, reasoningBuilder);
            }
        }
    }

    // Set to true to dump raw SSE data to stderr for debugging provider issues
    private static final boolean DEBUG_SSE = true;

    private boolean handleSseData(String data, BlockingQueue<StreamEvent> queue,
                                  Map<Integer, StringBuilder> toolNames,
                                  Map<Integer, StringBuilder> toolArgs,
                                  Map<Integer, String> toolIds,
                                  StringBuilder reasoningBuilder) throws InterruptedException {
        JsonNode root;
        try {
            root = MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            if (DEBUG_SSE) System.err.println("[SSE] parse error: " + e.getMessage());
            return false;
        }

        if (DEBUG_SSE) {
            boolean hasTc = root.has("choices") && root.path("choices").isArray()
                    && !root.path("choices").isEmpty()
                    && root.path("choices").get(0).has("delta")
                    && root.path("choices").get(0).path("delta").has("tool_calls");
            String fr = root.has("choices") && root.path("choices").isArray()
                    && !root.path("choices").isEmpty()
                    && root.path("choices").get(0).has("finish_reason")
                    ? root.path("choices").get(0).get("finish_reason").asText() : null;
//            if (hasTc || fr != null) {
//                System.err.println("[SSE] finish=" + fr + " raw=" + data.substring(0, Math.min(data.length(), 500)));
//            }
        }

        if (root.has("error")) {
            var errNode = root.get("error");
            String errMsg = errNode.has("message") ? errNode.get("message").asText() : errNode.toString();
            queue.put(new StreamEvent.Error(errMsg));
            return false;
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return emitUsageIfPresent(root, queue, reasoningBuilder);
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");

        // Text content
        if (delta.has("content") && !delta.get("content").isNull()) {
            String text = delta.get("content").asText();
            if (!text.isEmpty()) {
                queue.put(new StreamEvent.TextDelta(text));
            }
        }

        // Reasoning / thinking content (DeepSeek extension)
        if (thinking && delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
            String reasoning = delta.get("reasoning_content").asText();
            if (!reasoning.isEmpty()) {
                reasoningBuilder.append(reasoning);
                queue.put(new StreamEvent.ThinkingDelta(reasoning));
            }
        }

        // Tool calls (deltas)
        if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
            for (JsonNode tc : delta.get("tool_calls")) {
                int idx = tc.path("index").asInt(0);

                if (tc.has("id") && !tc.get("id").isNull()) {
                    toolIds.put(idx, tc.get("id").asText());
                }

                JsonNode fn = tc.path("function");
                if (fn.has("name") && !fn.get("name").isNull()) {
                    String name = fn.get("name").asText();
                    if (!toolNames.containsKey(idx)) {
                        toolNames.put(idx, new StringBuilder(name));
                        String callId = toolIds.getOrDefault(idx, "call_" + idx);
                        queue.put(new StreamEvent.ToolCallStart(callId, name));
                    }
                }
                if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                    String argChunk = extractArgumentsText(fn.get("arguments"));
                    if (!argChunk.isEmpty()) {
                        toolArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(argChunk);
                        String callId = toolIds.getOrDefault(idx, "call_" + idx);
                        queue.put(new StreamEvent.ToolCallDelta(callId, argChunk));
                    }
                }
            }
        }

        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                ? choice.get("finish_reason").asText() : null;

        if ("tool_calls".equals(finishReason)) {
            flushReasoning(queue, reasoningBuilder);
            flushPendingToolCalls(queue, toolNames, toolArgs, toolIds);
            return false;
        } else if ("stop".equals(finishReason) || "length".equals(finishReason)) {
            String stopReason = "length".equals(finishReason) ? "max_tokens" : "end_turn";
            var usage = extractUsage(root);
            flushReasoning(queue, reasoningBuilder);
            queue.put(new StreamEvent.StreamEnd(stopReason, usage.promptTokens(), usage.outputTokens(),
                    usage.cacheReadTokens(), usage.cacheCreationTokens()));
            return true;
        }

        return false;
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

    private boolean emitUsageIfPresent(JsonNode root, BlockingQueue<StreamEvent> queue,
                                       StringBuilder reasoningBuilder) throws InterruptedException {
        var usage = extractUsage(root);
        if (usage.promptTokens() > 0 || usage.outputTokens() > 0) {
            flushReasoning(queue, reasoningBuilder);
            queue.put(new StreamEvent.StreamEnd("end_turn", usage.promptTokens(), usage.outputTokens(),
                    usage.cacheReadTokens(), usage.cacheCreationTokens()));
            return true;
        }
        return false;
    }

    private record UsageInfo(int promptTokens, int outputTokens,
                              long cacheReadTokens, long cacheCreationTokens) {}

    static UsageInfo extractUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode()) return new UsageInfo(0, 0, 0, 0);
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        long cacheCreation = usage.path("cache_creation_input_tokens").asLong(0);
        return new UsageInfo(promptTokens, output, cacheRead, cacheCreation);
    }

    // ------------------------------------------------------------------
    // Request body building
    // ------------------------------------------------------------------

    private String buildRequestBody(List<Message> messages, List<Map<String, Object>> tools)
            throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        if (maxOutputTokens > 0) {
            root.put("max_tokens", maxOutputTokens);
        }

        ObjectNode streamOpts = MAPPER.createObjectNode();
        streamOpts.put("include_usage", true);
        root.set("stream_options", streamOpts);

        root.set("messages", buildChatMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            root.set("tools", buildToolsArray(tools));
        }

        return MAPPER.writeValueAsString(root);
    }

    @SuppressWarnings("unchecked")
    private ArrayNode buildChatMessages(List<Message> messages) {
        ArrayNode arr = MAPPER.createArrayNode();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode sys = MAPPER.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            arr.add(sys);
        }

        for (var msg : messages) {
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();
            boolean hasToolResults = msg.getToolResults() != null && !msg.getToolResults().isEmpty();

            if ("assistant".equals(msg.getRole()) && hasToolUses) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("role", "assistant");
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    node.put("content", msg.getContent());
                } else {
                    node.putNull("content");
                }

                ArrayNode toolCallsArr = MAPPER.createArrayNode();
                for (var tu : msg.getToolUses()) {
                    ObjectNode tc = MAPPER.createObjectNode();
                    tc.put("id", tu.toolUseId());
                    tc.put("type", "function");
                    ObjectNode fn = MAPPER.createObjectNode();
                    fn.put("name", tu.toolName());
                    try {
                        fn.put("arguments", MAPPER.writeValueAsString(tu.arguments()));
                    } catch (JsonProcessingException e) {
                        fn.put("arguments", "{}");
                    }
                    tc.set("function", fn);
                    toolCallsArr.add(tc);
                }
                node.set("tool_calls", toolCallsArr);
                arr.add(node);

            } else if (hasToolResults) {
                for (var tr : msg.getToolResults()) {
                    ObjectNode node = MAPPER.createObjectNode();
                    node.put("role", "tool");
                    node.put("tool_call_id", tr.toolUseId());
                    node.put("content", tr.content());
                    arr.add(node);
                }
            } else {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("role", msg.getRole());
                node.put("content", msg.getContent() != null ? msg.getContent() : "");
                arr.add(node);
            }
        }

        return arr;
    }

    @SuppressWarnings("unchecked")
    private ArrayNode buildToolsArray(List<Map<String, Object>> tools) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (var schema : tools) {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("type", "function");

            ObjectNode fn = MAPPER.createObjectNode();
            fn.put("name", (String) schema.get("name"));
            if (schema.containsKey("description")) {
                fn.put("description", Objects.toString(schema.get("description"), ""));
            }

            var params = (Map<String, Object>) schema.getOrDefault("parameters",
                    schema.getOrDefault("input_schema", Map.of()));
            fn.set("parameters", MAPPER.valueToTree(params));

            tool.set("function", fn);
            arr.add(tool);
        }
        return arr;
    }

    // ------------------------------------------------------------------
    // Argument extraction
    // ------------------------------------------------------------------

    /**
     * Extract tool call arguments as a JSON string, handling both forms:
     * - TextNode: {@code "arguments": "{\\"command\\":\\"ls\\"}"} — standard OpenAI format
     * - ObjectNode: {@code "arguments": {"command": "ls"}} — pre-parsed by some proxies
     */
    private static String extractArgumentsText(JsonNode argsNode) {
        if (argsNode.isTextual()) {
            return argsNode.asText();
        }
        // Pre-parsed JSON object/array — re-serialize to string
        try {
            return MAPPER.writeValueAsString(argsNode);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Error classification
    // ------------------------------------------------------------------

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String lower = msg.toLowerCase();

        if (msg.startsWith("HTTP 401") || lower.contains("unauthorized") || lower.contains("invalid api key")) {
            return new LlmException.AuthenticationException("Invalid API key: " + msg);
        }
        if (msg.startsWith("HTTP 429") || lower.contains("rate limit")) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (lower.contains("context_length_exceeded") || lower.contains("prompt is too long")
                || lower.contains("too many tokens") || msg.startsWith("HTTP 413")) {
            return new LlmException.ContextTooLongException("Context too long: " + msg);
        }
        if (e instanceof IOException) {
            return new LlmException.NetworkException("Network error: " + msg, e);
        }
        if (msg.startsWith("HTTP 4") || msg.startsWith("HTTP 5")) {
            return new LlmException("API error: " + msg, e);
        }
        return new LlmException("Unexpected error: " + msg, e);
    }
}
