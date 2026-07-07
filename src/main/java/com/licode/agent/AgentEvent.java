package com.licode.agent;

import com.licode.permission.PermissionResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public sealed interface AgentEvent {

    record StreamText(String text) implements AgentEvent {}

    record ThinkingText(String text) implements AgentEvent {}

    record ThinkingComplete(String thinking, String signature) implements AgentEvent {}

    record ToolUseEvent(String toolId, String toolName, Map<String, Object> args) implements AgentEvent {}

    record ToolCallDelta(String toolId, String argumentsDelta) implements AgentEvent {}

    record ToolResultEvent(String toolId, String toolName, String output,
                           boolean isError, double elapsedSeconds) implements AgentEvent {}

    record TurnComplete(int iteration) implements AgentEvent {}

    record LoopComplete(int iterations, int totalInputTokens, int totalOutputTokens,
                        String stopReason) implements AgentEvent {}

    record UsageEvent(int inputTokens, int outputTokens,
                      long cacheReadTokens, long cacheCreationTokens) implements AgentEvent {
        public UsageEvent(int inputTokens, int outputTokens) {
            this(inputTokens, outputTokens, 0, 0);
        }
    }

    record ErrorEvent(String message) implements AgentEvent {}

    record PermissionRequestEvent(String toolId, String toolName, String description,
                                  CompletableFuture<PermissionResponse> future) implements AgentEvent {}
}
