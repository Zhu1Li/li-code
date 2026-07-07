package com.licode.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConversationManager {

    private final List<Message> history = new ArrayList<>();

    public void addUserMessage(String content) {
        history.add(Message.user(content));
    }

    public void addAssistantMessage(String content) {
        history.add(Message.assistant(content));
    }

    public void addAssistantFull(String text, List<ThinkingBlock> thinking,
                                  List<ToolUseBlock> toolUses, List<ToolResultBlock> toolResults) {
        Message msg = Message.assistant(text);
        msg.setThinkingBlocks(thinking);
        msg.setToolUses(toolUses);
        msg.setToolResults(toolResults);
        history.add(msg);
    }

    public void addToolResultsMessage(List<ToolResultBlock> results) {
        Message msg = new Message("user", "");
        msg.setToolResults(results);
        history.add(msg);
    }

    public void addSystemReminder(String content) {
        String wrapped = "<system-reminder>\n" + content + "\n</system-reminder>";
        history.add(Message.user(wrapped));
    }

    public List<Message> getMessages() {
        return List.copyOf(history);
    }

    public List<Message> getMessagesInternal() {
        return new ArrayList<>(history);
    }

    public List<Message> getMessagesMutable() {
        return history;
    }

    public int size() {
        return history.size();
    }

    public int estimateTokens() {
        int chars = 0;
        for (Message msg : history) {
            if (msg.getContent() != null) chars += msg.getContent().length();
            if (msg.getThinkingBlocks() != null) {
                for (ThinkingBlock tb : msg.getThinkingBlocks()) {
                    chars += tb.thinking() != null ? tb.thinking().length() : 0;
                }
            }
        }
        return Math.max(1, chars / 3);
    }

    public void trimToWindow(int maxTokens) {
        int threshold = (int) (maxTokens * 0.8);
        while (estimateTokens() > threshold && history.size() > 2) {
            history.removeFirst();
        }
    }

    public void clear() {
        history.clear();
    }

    /**
     * Repair orphaned tool_use blocks in conversation history that would cause
     * API rejection ("tool_use ids were found without tool_result blocks immediately
     * after"). Strips tool_use blocks from the last assistant message that has
     * unmatched tool calls, converting it to a plain text message.
     *
     * <p>Called when the Agent receives a 400 error about orphaned tool_use,
     * and on session rebuild via SessionManager.
     */
    public void repairLastOrphanedToolUses() {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (!"assistant".equals(msg.getRole())) continue;
            if (msg.getToolUses() == null || msg.getToolUses().isEmpty()) continue;

            // Check if next message has matching tool_results for ALL tool_use ids
            boolean allCovered = false;
            if (i + 1 < history.size()) {
                Message next = history.get(i + 1);
                if (next.getToolResults() != null && !next.getToolResults().isEmpty()) {
                    int matchCount = 0;
                    for (var tu : msg.getToolUses()) {
                        for (var tr : next.getToolResults()) {
                            if (tu.toolUseId().equals(tr.toolUseId())) {
                                matchCount++;
                                break;
                            }
                        }
                    }
                    allCovered = matchCount == msg.getToolUses().size();
                }
            }

            if (!allCovered) {
                msg.setToolUses(null);
                return;
            }
        }
    }
}
