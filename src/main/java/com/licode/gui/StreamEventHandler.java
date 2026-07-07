package com.licode.gui;

import com.licode.llm.StreamCallback;
import com.licode.permission.PermissionResponse;
import com.licode.tui.ChatMessage;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bridges LiRuntime (virtual threads) to JavaFX Application Thread.
 * Implements StreamCallback so it can be passed directly to LiRuntime.ask().
 */
public class StreamEventHandler implements StreamCallback {

    private final ChatView chatView;
    private final StringBuilder thinkingBuf;

    public StreamEventHandler(ChatView chatView) {
        this.chatView = chatView;
        this.thinkingBuf = new StringBuilder();
    }

    @Override
    public void onTextDelta(String text) {
        chatView.onStreamTextDelta(text);
    }

    @Override
    public void onThinkingDelta(String text) {
        thinkingBuf.append(text);
        chatView.onStreamThinkingDelta(text);
    }

    @Override
    public void onThinkingComplete(String thinking, String signature) {
        chatView.onStreamThinkingComplete(thinking);
    }

    @Override
    public void onToolCallStart(String toolCallId, String toolName) {
        chatView.onStreamToolCallStart(toolCallId, toolName);
    }

    @Override
    public void onToolCallDelta(String toolCallId, String argumentsDelta) {
        // Arguments arrive as partial JSON — shown on complete
    }

    @Override
    public void onToolCallComplete(String toolCallId, String toolName, Map<String, Object> arguments) {
        chatView.onStreamToolCallComplete(toolName, arguments);
    }

    @Override
    public void onError(String message) {
        chatView.onStreamError(message);
    }

    @Override
    public void onNotice(String message) {
        chatView.addMessage(ChatMessage.system(message));
    }

    @Override
    public void onComplete(String stopReason, int inputTokens, int outputTokens) {
        chatView.onStreamComplete(stopReason, inputTokens, outputTokens);
    }

    @Override
    public void onPermissionRequest(String toolId, String toolName, String description,
                                     CompletableFuture<PermissionResponse> future) {
        chatView.onPermissionRequest(toolId, toolName, description, future);
    }
}
