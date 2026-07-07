package com.licode.llm;

import com.licode.permission.PermissionResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface StreamCallback {

    void onTextDelta(String text);

    void onThinkingDelta(String text);

    void onThinkingComplete(String thinking, String signature);

    void onToolCallStart(String toolCallId, String toolName);

    void onToolCallDelta(String toolCallId, String argumentsDelta);

    void onToolCallComplete(String toolCallId, String toolName, Map<String, Object> arguments);

    /** Called after a tool finishes executing, carrying its actual output. */
    default void onToolResult(String toolId, String toolName, String output,
                              boolean isError, double elapsedSeconds) {}

    /** A UI-only informational notice (not part of the model conversation). */
    default void onNotice(String message) {}

    void onError(String message);

    void onComplete(String stopReason, int inputTokens, int outputTokens);

    /**
     * Called when a tool needs user confirmation.
     * The callback should display the prompt to the user and complete the future
     * with ALLOW, ALLOW_ALWAYS, or DENY once the user responds.
     */
    default void onPermissionRequest(String toolId, String toolName, String description,
                                     CompletableFuture<PermissionResponse> future) {
        future.complete(PermissionResponse.DENY);
    }
}
