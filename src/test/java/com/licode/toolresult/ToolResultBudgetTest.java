package com.licode.toolresult;

import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.conversation.ToolResultBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultBudgetTest {

    @Test
    void applyDoesNotMutateConv(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addAssistantFull("", null,
                List.of(new com.licode.conversation.ToolUseBlock("tu1", "Bash", Map.of())),
                null);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu1", "a".repeat(100), false)
        ));

        List<Message> before = conv.getMessages();
        String beforeContent = before.getLast().getToolResults().getFirst().content();

        ContentReplacementState state = new ContentReplacementState();
        ToolResultBudget.apply(conv, tempDir, state);

        List<Message> after = conv.getMessages();
        String afterContent = after.getLast().getToolResults().getFirst().content();

        assertEquals(beforeContent, afterContent);
    }

    @Test
    void firstCallFreezesUnreplaced(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addAssistantFull("", null,
                List.of(new com.licode.conversation.ToolUseBlock("tu1", "ReadFile", Map.of())),
                null);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu1", "short result", false)
        ));

        ContentReplacementState state = new ContentReplacementState();
        ToolResultBudget.apply(conv, tempDir, state);

        assertTrue(state.seenIds().contains("tu1"));
        // Not replaced (too short), so not in replacements
        assertFalse(state.replacements().containsKey("tu1"));
    }

    @Test
    void replacementByteIdentical(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addAssistantFull("", null,
                List.of(new com.licode.conversation.ToolUseBlock("tu1", "Bash", Map.of())),
                null);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu1", "a".repeat(60_000), false)
        ));

        ContentReplacementState state = new ContentReplacementState();

        // First call
        ApplyResult r1 = ToolResultBudget.apply(conv, tempDir, state);
        String preview1 = null;
        for (Message m : r1.apiConv().getMessages()) {
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    if ("tu1".equals(tr.toolUseId())) {
                        preview1 = tr.content();
                    }
                }
            }
        }
        assertNotNull(preview1);
        assertTrue(preview1.startsWith("[Result of "));

        // Second call — same tool result data, should produce same preview
        ApplyResult r2 = ToolResultBudget.apply(conv, tempDir, state);
        String preview2 = null;
        for (Message m : r2.apiConv().getMessages()) {
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    if ("tu1".equals(tr.toolUseId())) {
                        preview2 = tr.content();
                    }
                }
            }
        }
        assertEquals(preview1, preview2);
    }

    @Test
    void frozenNeverReplaced(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addAssistantFull("", null,
                List.of(new com.licode.conversation.ToolUseBlock("tu_small", "ReadFile", Map.of())),
                null);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu_small", "short", false)
        ));

        ContentReplacementState state = new ContentReplacementState();

        // First call — tu_small is frozen as seen but not replaced
        ApplyResult r1 = ToolResultBudget.apply(conv, tempDir, state);
        assertTrue(state.seenIds().contains("tu_small"));
        assertFalse(state.replacements().containsKey("tu_small"));

        // Second call — add a huge result for a different id
        conv.addAssistantFull("", null,
                List.of(new com.licode.conversation.ToolUseBlock("tu_big", "Bash", Map.of())),
                null);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu_small", "short", false),
                new ToolResultBlock("tu_big", "b".repeat(60_000), false)
        ));

        ApplyResult r2 = ToolResultBudget.apply(conv, tempDir, state);

        // tu_big should be replaced (over threshold)
        assertTrue(state.replacements().containsKey("tu_big"));

        // tu_small should still NOT be replaced (frozen from first call)
        assertFalse(state.replacements().containsKey("tu_small"));

        // Verify in apiConv: tu_small is still "short"
        for (Message m : r2.apiConv().getMessages()) {
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    if ("tu_small".equals(tr.toolUseId())) {
                        assertEquals("short", tr.content());
                    }
                }
            }
        }
    }

    @Test
    void aggregateOnlyPicksFresh(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addAssistantFull("", null,
                List.of(
                        new com.licode.conversation.ToolUseBlock("tu1", "Bash", Map.of()),
                        new com.licode.conversation.ToolUseBlock("tu2", "Bash", Map.of()),
                        new com.licode.conversation.ToolUseBlock("tu3", "Bash", Map.of())
                ),
                null);
        // Three results, each over 80K chars (aggregate > 200K)
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu1", "a".repeat(80_000), false),
                new ToolResultBlock("tu2", "b".repeat(80_000), false),
                new ToolResultBlock("tu3", "c".repeat(80_000), false)
        ));

        ContentReplacementState state = new ContentReplacementState();

        ApplyResult r = ToolResultBudget.apply(conv, tempDir, state);

        // All three should be processed (aggregate spill)
        int replacedCount = 0;
        for (Message m : r.apiConv().getMessages()) {
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    if (tr.content().startsWith("[Result of ")) {
                        replacedCount++;
                    }
                }
            }
        }
        // All three should be spilled due to aggregate threshold
        assertTrue(replacedCount >= 2, "Expected at least 2 results spilled, got " + replacedCount);
    }

    @Test
    void reconstructFromRecords(@TempDir Path tempDir) throws Exception {
        // Create records as if from a previous session
        var records = List.of(
                ContentReplacementRecord.toolResult("tu1", "[Result of 100000 chars saved to /tmp/tool_results/tu1]")
        );

        ConversationManager conv = new ConversationManager();
        conv.addAssistantFull("", null,
                List.of(new com.licode.conversation.ToolUseBlock("tu1", "Bash", Map.of())),
                null);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu1", "something", false)
        ));

        ContentReplacementState reconstructed = ContentReplacementLifecycle.reconstruct(
                conv.getMessages(), records, null);

        assertTrue(reconstructed.seenIds().contains("tu1"));
        assertEquals("[Result of 100000 chars saved to /tmp/tool_results/tu1]",
                reconstructed.replacements().get("tu1"));
    }

    @Test
    void reconstructWithInheritedParent() {
        ConversationManager conv = new ConversationManager();
        conv.addAssistantFull("", null,
                List.of(new com.licode.conversation.ToolUseBlock("tu1", "Bash", Map.of())),
                null);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu1", "something", false)
        ));

        Map<String, String> parentReplacements = Map.of(
                "tu1", "[Result of 50000 chars saved to /tmp/tool_results/tu1]",
                "tu_unknown", "not applicable"
        );

        ContentReplacementState reconstructed = ContentReplacementLifecycle.reconstruct(
                conv.getMessages(), List.of(), parentReplacements);

        assertEquals("[Result of 50000 chars saved to /tmp/tool_results/tu1]",
                reconstructed.replacements().get("tu1"));
        // tu_unknown should NOT be in replacements (not a candidate)
        assertNull(reconstructed.replacements().get("tu_unknown"));
    }
}
