package com.licode.config;

import com.licode.hook.HookConfig;

import java.util.List;
import java.util.Map;

public class AppConfig {

    private List<ProviderConfig> providers;
    private List<McpServerConfig> mcpServers;
    private List<HookConfig> hooks;
    private Map<String, Object> features;

    public List<ProviderConfig> getProviders() { return providers; }
    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }

    public List<McpServerConfig> getMcpServers() { return mcpServers; }
    public void setMcpServers(List<McpServerConfig> mcpServers) { this.mcpServers = mcpServers; }

    public List<HookConfig> getHooks() { return hooks; }
    public void setHooks(List<HookConfig> hooks) { this.hooks = hooks; }

    public Map<String, Object> getFeatures() { return features; }
    public void setFeatures(Map<String, Object> features) { this.features = features; }
}
