package com.licode.team;

import com.licode.conversation.ConversationManager;
import com.licode.conversation.ThinkingBlock;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        var store = new ConversationStore(tempDir);
        var conv = new ConversationManager();
        conv.addUserMessage("Hello");
        conv.addAssistantFull("Hi there", null, null, null);

        store.save("agent1", conv);

        assertTrue(store.exists("agent1"));
        var loaded = store.load("agent1");
        assertNotNull(loaded);
        var messages = loaded.getMessages();
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("Hello", messages.get(0).getContent());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("Hi there", messages.get(1).getContent());
    }

    @Test
    void saveWithThinkingBlock() throws IOException {
        var store = new ConversationStore(tempDir);
        var conv = new ConversationManager();
        conv.addAssistantFull("response text",
                List.of(new ThinkingBlock("I need to think about this", "sig123")),
                null, null);

        store.save("thinker", conv);

        var loaded = store.load("thinker");
        var messages = loaded.getMessages();
        assertEquals(1, messages.size());
        assertEquals("assistant", messages.get(0).getRole());
        assertNotNull(messages.get(0).getThinkingBlocks());
        assertEquals(1, messages.get(0).getThinkingBlocks().size());
        assertEquals("I need to think about this",
                messages.get(0).getThinkingBlocks().get(0).thinking());
    }

    @Test
    void saveWithToolUseAndResultBlocks() throws IOException {
        var store = new ConversationStore(tempDir);
        var conv = new ConversationManager();
        conv.addAssistantFull(null, null,
                List.of(new ToolUseBlock("tc1", "Bash", Map.of("command", "ls"))), null);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tc1", "file1.txt\nfile2.txt", false)));

        store.save("tooler", conv);

        var loaded = store.load("tooler");
        var messages = loaded.getMessages();
        assertEquals(2, messages.size());
        assertEquals("assistant", messages.get(0).getRole());
        var toolUses = messages.get(0).getToolUses();
        assertNotNull(toolUses);
        assertEquals(1, toolUses.size());
        assertEquals("Bash", toolUses.get(0).toolName());
        var toolResults = messages.get(1).getToolResults();
        assertNotNull(toolResults);
        assertEquals(1, toolResults.size());
        assertEquals("file1.txt\nfile2.txt", toolResults.get(0).content());
    }

    @Test
    void saveOverwritesPrevious() throws IOException {
        var store = new ConversationStore(tempDir);
        var conv1 = new ConversationManager();
        conv1.addUserMessage("First");
        store.save("agent1", conv1);

        var conv2 = new ConversationManager();
        conv2.addUserMessage("Second");
        store.save("agent1", conv2);

        var loaded = store.load("agent1");
        assertEquals("Second", loaded.getMessages().get(0).getContent());
    }

    @Test
    void loadNonexistentReturnsEmpty() throws IOException {
        var store = new ConversationStore(tempDir);
        var conv = store.load("nonexistent");
        assertNotNull(conv);
        assertTrue(conv.getMessages().isEmpty());
    }

    @Test
    void existsReturnsFalseForNonexistent() {
        var store = new ConversationStore(tempDir);
        assertFalse(store.exists("nonexistent"));
    }

    @Test
    void deleteRemovesConversation() throws IOException {
        var store = new ConversationStore(tempDir);
        var conv = new ConversationManager();
        conv.addUserMessage("Hello");
        store.save("agent1", conv);
        assertTrue(store.exists("agent1"));

        store.delete("agent1");
        assertFalse(store.exists("agent1"));
    }

    @Test
    void deleteNonexistentDoesNotThrow() {
        var store = new ConversationStore(tempDir);
        assertDoesNotThrow(() -> store.delete("nonexistent"));
    }

    @Test
    void listMembersReturnsSavedMembers() throws IOException {
        var store = new ConversationStore(tempDir);
        var conv = new ConversationManager();
        conv.addUserMessage("Hello");
        store.save("alice", conv);
        store.save("bob", conv);

        var members = store.listMembers();
        assertEquals(2, members.size());
        assertTrue(members.contains("alice"));
        assertTrue(members.contains("bob"));
    }
}
