package com.licode.tui;

public class ChatMessage {

    public enum Role {
        USER, ASSISTANT, SYSTEM, ERROR, THINKING
    }

    private final Role role;
    private final String content;
    private String thinkingContent;
    private boolean thinkingExpanded;

    private ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
        this.thinkingExpanded = false;
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage error(String content) {
        return new ChatMessage(Role.ERROR, content);
    }

    public static ChatMessage thinking(String thinkingContent) {
        ChatMessage msg = new ChatMessage(Role.THINKING, "");
        msg.thinkingContent = thinkingContent;
        return msg;
    }

    /** Lightweight THINKING message with only status text (e.g. "✻ Thought for 12.3s"). */
    public static ChatMessage thinkingStatus(String content) {
        return new ChatMessage(Role.THINKING, content);
    }

    public Role getRole() { return role; }
    public String getContent() { return content; }
    public String getThinkingContent() { return thinkingContent; }

    public void setThinkingContent(String thinkingContent) { this.thinkingContent = thinkingContent; }

    public boolean isThinkingExpanded() { return thinkingExpanded; }
    public void setThinkingExpanded(boolean thinkingExpanded) { this.thinkingExpanded = thinkingExpanded; }
}
