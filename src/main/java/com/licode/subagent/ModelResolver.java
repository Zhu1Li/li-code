package com.licode.subagent;

import com.licode.config.ProviderConfig;
import com.licode.llm.LlmClient;

import java.util.List;
import java.util.logging.Logger;

/**
 * Resolves a model key (e.g. "deepseek:deepseek-chat" or "claude-haiku-4-5")
 * into an {@link LlmClient} by looking up provider configuration.
 *
 * <p>Format:
 * <ul>
 *   <li>{@code "provider_name:model_id"} — looks up the named provider in
 *       {@code allProviders}, creates a client with that provider's protocol/baseUrl/apiKey
 *       but overrides the model.</li>
 *   <li>{@code "model_id"} (no colon) — reuses the parent provider's protocol/baseUrl/apiKey,
 *       only replacing the model.</li>
 *   <li>{@code null} or empty — returns the parent client unchanged.</li>
 * </ul>
 */
@FunctionalInterface
public interface ModelResolver {

    /**
     * Resolve a model key to an LLM client.
     *
     * @param modelKey  the model selection string (may be null/empty)
     * @param systemPrompt  system prompt for the new client (only used when creating a new client)
     * @return the resolved LLM client, or null if resolution failed
     */
    LlmClient resolve(String modelKey, String systemPrompt);

    /**
     * Create a ModelResolver backed by a list of configured providers.
     *
     * @param allProviders  all available provider configs from config.yaml
     * @param parentConfig  the parent (main) provider config for fallback
     * @param parentClient  the parent LLM client for fallback
     * @return a ModelResolver instance
     */
    static ModelResolver create(List<ProviderConfig> allProviders,
                                ProviderConfig parentConfig,
                                LlmClient parentClient) {
        Logger log = Logger.getLogger(ModelResolver.class.getName());
        return (modelKey, systemPrompt) -> {
            if (modelKey == null || modelKey.isBlank()) {
                return parentClient;
            }

            int colonIdx = modelKey.indexOf(':');
            if (colonIdx > 0) {
                // Format: "provider_name:model_id"
                String providerName = modelKey.substring(0, colonIdx);
                String modelId = modelKey.substring(colonIdx + 1);

                ProviderConfig match = null;
                if (allProviders != null) {
                    for (var p : allProviders) {
                        if (providerName.equals(p.getName())) {
                            match = p;
                            break;
                        }
                    }
                }

                if (match != null) {
                    ProviderConfig childCfg = new ProviderConfig();
                    childCfg.setName(match.getName());
                    childCfg.setProtocol(match.getProtocol());
                    childCfg.setBaseUrl(match.getBaseUrl());
                    childCfg.setModel(modelId);
                    childCfg.setApiKey(match.getApiKey());
                    childCfg.setThinking(match.isThinking());
                    if (match.getContextWindow() > 0) {
                        childCfg.setContextWindow(match.getContextWindow());
                    }
                    if (match.getMaxOutputTokens() > 0) {
                        childCfg.setMaxOutputTokens(match.getMaxOutputTokens());
                    }
                    try {
                        return LlmClient.create(childCfg, systemPrompt);
                    } catch (Exception e) {
                        log.warning("Failed to create client for provider '"
                                + providerName + "': " + e.getMessage());
                    }
                } else {
                    log.warning("Provider '" + providerName + "' not found in config, "
                            + "falling back to parent client");
                }
            } else {
                // Plain model ID: reuse parent provider config, only change model
                ProviderConfig childCfg = new ProviderConfig();
                childCfg.setName(parentConfig.getName());
                childCfg.setProtocol(parentConfig.getProtocol());
                childCfg.setBaseUrl(parentConfig.getBaseUrl());
                childCfg.setModel(modelKey);
                childCfg.setApiKey(parentConfig.getApiKey());
                childCfg.setThinking(parentConfig.isThinking());
                if (parentConfig.getContextWindow() > 0) {
                    childCfg.setContextWindow(parentConfig.getContextWindow());
                }
                if (parentConfig.getMaxOutputTokens() > 0) {
                    childCfg.setMaxOutputTokens(parentConfig.getMaxOutputTokens());
                }
                try {
                    return LlmClient.create(childCfg, systemPrompt);
                } catch (Exception e) {
                    log.warning("Failed to create client for model '"
                            + modelKey + "': " + e.getMessage());
                }
            }

            return parentClient;
        };
    }
}
