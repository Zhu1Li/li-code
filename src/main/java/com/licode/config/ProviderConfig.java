package com.licode.config;

import java.util.Map;

public class ProviderConfig {

    private static final Map<String, String> ENV_KEY_MAP = Map.of(
            "anthropic", "ANTHROPIC_API_KEY",
            "openai", "OPENAI_API_KEY",
            "openai-compat", "OPENAI_API_KEY"
    );

    private String name;
    private String protocol;
    private String baseUrl;
    private String model;
    private String apiKey;
    private boolean thinking;

    private int contextWindow;
    private int maxOutputTokens;

    private volatile Integer fetchedContextWindow;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public boolean isThinking() { return thinking; }
    public void setThinking(boolean thinking) { this.thinking = thinking; }

    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public void setFetchedContextWindow(int window) {
        if (window > 0) this.fetchedContextWindow = window;
    }

    public int resolvedContextWindow() {
        if (contextWindow > 0) return contextWindow;
        Integer fetched = fetchedContextWindow;
        if (fetched != null && fetched > 0) return fetched;
        return windowForModel(model);
    }

    public static int windowForModel(String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.contains("1m") || m.contains("-1m")) return 1_000_000;
        if (m.contains("gpt-4.1")) return 1_000_000;
        if (m.contains("gpt-4o")) return 128_000;
        if (m.contains("gpt-4-turbo")) return 128_000;
        if (m.contains("o1") || m.contains("o3") || m.contains("o4")) return 200_000;
        if (m.contains("gpt-3.5")) return 16_385;
        if (m.contains("claude")) return 200_000;
        return 128_000;
    }

    public int resolvedMaxOutputTokens() {
        if (maxOutputTokens > 0) return maxOutputTokens;
        return thinking ? 64_000 : 8192;
    }

    public String resolvedApiKey() {
        if (apiKey != null && !apiKey.isEmpty()) return apiKey;
        // Try protocol-specific env var first
        String envVar = ENV_KEY_MAP.get(protocol);
        if (envVar != null) {
            String val = System.getenv(envVar);
            if (val != null && !val.isEmpty()) return val;
        }
        // Fallback: DEEPSEEK_API_KEY (common for users connecting to DeepSeek via
        // either Anthropic-compatible or OpenAI-compatible endpoints)
        String fallback = System.getenv("deepseek-key");
        if (fallback != null && !fallback.isEmpty()) return fallback;
        return "";
    }
}
