package com.licode.app;

import com.licode.llm.StreamCallback;
import com.licode.permission.PermissionResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Headless {@link StreamCallback} for {@code --print} batch mode (Harbor / CI).
 *
 * <p>Accumulates streamed assistant text into a buffer (the final answer),
 * logs tool activity to stderr for debugging, and counts down a latch on
 * completion or error so the caller can block until the agent loop finishes.
 *
 * <p>There is no human in a container, so {@link #onPermissionRequest} auto-
 * approves (defense-in-depth; with {@code PermissionMode.BYPASS} it should not
 * fire, but the interface default is DENY which would deadlock the loop).
 */
public final class PrintCallback implements StreamCallback {

    private final StringBuilder finalText = new StringBuilder();
    private final CountDownLatch done;

    private volatile boolean errored;
    private volatile String errorMessage;
    private volatile String stopReason;

    public PrintCallback(CountDownLatch done) {
        this.done = done;
    }

    public String finalText() {
        return finalText.toString();
    }

    public boolean errored() {
        return errored;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String stopReason() {
        return stopReason;
    }

    @Override
    public void onTextDelta(String text) {
        finalText.append(text);
    }

    @Override
    public void onThinkingDelta(String text) {
        // Reasoning is not part of the final answer; ignore in headless output.
    }

    @Override
    public void onThinkingComplete(String thinking, String signature) {
        // No-op in headless mode.
    }

    @Override
    public void onToolCallStart(String toolCallId, String toolName) {
        System.err.println("[licode] tool -> " + toolName);
    }

    @Override
    public void onToolCallDelta(String toolCallId, String argumentsDelta) {
        // Argument streaming is noise in headless logs; ignore.
    }

    @Override
    public void onToolCallComplete(String toolCallId, String toolName, Map<String, Object> arguments) {
        // Full call is logged again via onToolResult with its outcome.
    }

    @Override
    public void onToolResult(String toolId, String toolName, String output,
                             boolean isError, double elapsedSeconds) {
        System.err.printf("[licode] tool <- %s (%s, %.1fs)%n",
                toolName, isError ? "error" : "ok", elapsedSeconds);
    }

    @Override
    public void onError(String message) {
        this.errored = true;
        this.errorMessage = message;
        System.err.println("[licode] error: " + message);
        done.countDown();
    }

    @Override
    public void onComplete(String stopReason, int inputTokens, int outputTokens) {
        this.stopReason = stopReason;
        System.err.printf("[licode] complete: stop=%s in=%d out=%d%n",
                stopReason, inputTokens, outputTokens);
        done.countDown();
    }

    @Override
    public void onPermissionRequest(String toolId, String toolName, String description,
                                    CompletableFuture<PermissionResponse> future) {
        // No human in a container — auto-approve (BYPASS should preempt this).
        future.complete(PermissionResponse.ALLOW);
    }
}
