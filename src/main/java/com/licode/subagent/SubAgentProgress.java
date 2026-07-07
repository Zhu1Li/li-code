package com.licode.subagent;

/**
 * Progress event emitted while a sub-agent is running.
 * Consumers (e.g., TUI) can subscribe via {@link AgentTool#setProgressListener}.
 */
public record SubAgentProgress(
        String agentType,
        String description,
        String toolName,
        String toolOutput,
        boolean toolError,
        boolean done,
        int toolCount,
        double totalTimeSeconds
) {}
