package com.licode.compact;

import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {

    @Test
    void estimateTokensEmpty() {
        assertEquals(0, ContextCompactor.estimateTokens(List.of()));
    }

    @Test
    void estimateTokensWithContent() {
        Message msg = Message.user("Hello, this is a test message");
        int tokens = ContextCompactor.estimateTokens(List.of(msg));
        assertTrue(tokens > 0, "Expected positive token count");
    }

    @Test
    void computeKeepStartIndex() {
        ConversationManager conv = new ConversationManager();
        // Add enough messages to trigger keep logic
        for (int i = 0; i < 10; i++) {
            conv.addUserMessage("User message " + i + " with some content to fill tokens");
            conv.addAssistantMessage("Assistant response " + i + " with more content");
        }

        int index = ContextCompactor.computeKeepStartIndex(conv.getMessages());
        // Should keep at least MIN_KEEP_MESSAGES (5)
        int kept = conv.size() - index;
        assertTrue(kept >= 5, "Expected at least 5 messages kept, got " + kept);
        // Index should be >= 0
        assertTrue(index >= 0);
    }

    @Test
    void offloadAndSnipNoop() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hello");
        conv.addAssistantMessage("hi");

        // No tool results — offloadAndSnip should do nothing
        String status = ContextCompactor.offloadAndSnip(conv, null);
        assertEquals("", status);
    }
}
