package com.licode.llm;

import java.util.Map;

public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {}

    record ThinkingDelta(String text) implements StreamEvent {}

    record ThinkingComplete(String thinking, String signature) implements StreamEvent {}

    record ToolCallStart(String toolCallId, String toolName) implements StreamEvent {}

    record ToolCallDelta(String toolCallId, String argumentsDelta) implements StreamEvent {}

    record ToolCallComplete(String toolCallId, String toolName, Map<String, Object> arguments) implements StreamEvent {}

    record StreamEnd(String stopReason, int inputTokens, int outputTokens,
                     long cacheReadTokens, long cacheCreationTokens) implements StreamEvent {
        public StreamEnd(String stopReason, int inputTokens, int outputTokens) {
            this(stopReason, inputTokens, outputTokens, 0, 0);
        }
        public StreamEnd {
            if (stopReason == null) stopReason = "end_turn";
        }
    }

    record Error(String message) implements StreamEvent {}
}
