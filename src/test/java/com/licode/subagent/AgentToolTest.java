package com.licode.subagent;

import com.licode.conversation.ConversationManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolTest {

    @Test
    void forkBoilerplate_shouldContainForkTag() {
        assertTrue(AgentTool.FORK_BOILERPLATE.contains(AgentTool.FORK_BOILERPLATE_TAG),
                "FORK_BOILERPLATE should contain the fork tag for nested fork detection");
    }

    @Test
    void buildForkedConversation_shouldInjectBoilerplateInUserMessage() {
        var parent = new ConversationManager();
        parent.addUserMessage("original request");

        var forked = AgentTool.buildForkedConversation(parent, "<fork_boilerplate>\n\nYour task:\ndo something");

        var messages = forked.getMessages();
        // First message should be the original user message
        assertEquals("user", messages.get(0).getRole());
        assertEquals("original request", messages.get(0).getContent());

        // Last message should be the task user message with boilerplate
        var lastMsg = messages.get(messages.size() - 1);
        assertEquals("user", lastMsg.getRole());
        assertTrue(lastMsg.getContent().contains("<fork_boilerplate>"),
                "Last message should contain fork boilerplate");
        assertTrue(lastMsg.getContent().contains("do something"),
                "Last message should contain the task");
    }

    @Test
    void buildForkedConversation_shouldPatchPendingToolUses() {
        var parent = new ConversationManager();
        parent.addUserMessage("run a command");
        // Add assistant message with tool_use but no tool_result
        parent.addAssistantFull(null, null,
                List.of(new com.licode.conversation.ToolUseBlock("tu_001", "Bash",
                        java.util.Map.of("command", "echo hello"))), null);

        var forked = AgentTool.buildForkedConversation(parent, "<fork_boilerplate>\n\ntask");
        var messages = forked.getMessages();

        // There should be a tool results message patching the pending tool_use
        boolean hasPlaceholder = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .flatMap(m -> m.getToolResults() != null ? m.getToolResults().stream() : java.util.stream.Stream.empty())
                .anyMatch(tr -> tr.content().contains("tool execution interrupted by fork"));
        assertTrue(hasPlaceholder, "Pending tool_use should be patched with placeholder");
    }

    @Test
    void buildSubAgentSystemPromptStatic_shouldReturnNonEmpty() {
        String prompt = AgentTool.buildSubAgentSystemPromptStatic();
        assertNotNull(prompt);
        assertFalse(prompt.isBlank(), "System prompt should not be blank");
    }
}
