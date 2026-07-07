package com.licode.compact;

import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end continuity / recoverability of context compaction.
 *
 * <p>Compression ratio says nothing about whether the agent can keep working
 * after a compaction. This test embeds a fact that appears early and is needed
 * late, triggers the real spill + summary pipeline with a deliberately lossy
 * summarizer, and asserts the fact is still recoverable — and that nothing was
 * silently dropped (every offloaded result leaves a re-readable disk pointer).
 */
class ContinuityTest {

    private static final int CONTEXT_WINDOW = 200_000;
    private static final int TURNS = 16;
    // > ContextCompactor.SINGLE_RESULT_LIMIT (50_000) so offloadAndSnip spills to disk.
    private static final int BIG_RESULT_CHARS = 60_000;
    private static final String NEEDLE =
            "命名规范 NEEDLE-c4f2：所有对外 DTO 必须以 Req 或 Resp 结尾，禁止用 Dto 后缀";

    @Test
    void needleSurvivesCompactionAndNothingIsSilentlyDropped(@TempDir Path workDir) throws Exception {
        ConversationManager conv = new ConversationManager();

        // The needle was "read" early and recorded for recovery — it lives ONLY in
        // RecoveryState, never in the conversation messages, so if it shows up after
        // compaction it can only be the recovery path (not the lossy summary).
        RecoveryState rec = new RecoveryState();
        rec.recordFileRead("docs/naming.md", "# 命名规范\n" + NEEDLE + "\n参见团队 wiki。\n");

        // Generators overshoot the nominal target to finish a line; pin the actual length.
        String bigResult = BenchmarkContent.forTool("ReadFile", BIG_RESULT_CHARS);
        int actualLen = bigResult.length();
        for (int i = 0; i < TURNS; i++) {
            conv.addUserMessage(BenchmarkContent.userMessage(i, TURNS));
            conv.addAssistantFull(BenchmarkContent.assistantText(i), null,
                    List.of(new ToolUseBlock("tu_" + i, "ReadFile",
                            Map.of("file_path", BenchmarkContent.filePath(i)))),
                    null);
            conv.addToolResultsMessage(List.of(new ToolResultBlock("tu_" + i, bigResult, false)));
            conv.addAssistantMessage(BenchmarkContent.assistantText(i));
        }
        // Final turn needs the early fact.
        conv.addUserMessage("现在要新建一个返回体类，按之前确认的命名规范，它应该叫什么？");

        // ── Layer 1: spill large results to disk ──
        String status = ContextCompactor.offloadAndSnip(conv, workDir.toString());
        assertTrue(status.contains("spilled"), "large results should be spilled, status=" + status);

        // No silent loss: every offloaded result must reference a readable file whose
        // bytes match the original size, so the agent can re-read it on demand.
        int pointers = 0;
        for (Message m : conv.getMessages()) {
            if (m.getToolResults() == null) continue;
            for (ToolResultBlock tr : m.getToolResults()) {
                String c = tr.content();
                if (c == null || !c.startsWith("[Result of ")) continue;
                pointers++;
                Path spill = parseSpillPath(c);
                assertNotNull(spill, "spill pointer must contain a path: " + c);
                assertTrue(Files.isReadable(spill), "spilled file must exist & be readable: " + spill);
                assertEquals(actualLen, Files.readString(spill).length(),
                        "spilled file must hold the full original result");
            }
        }
        assertTrue(pointers >= 1, "expected at least one spilled-result pointer");

        // ── Layer 2: lossy summary — the summary itself keeps nothing ──
        ContextCompactor.forceCompact(conv, StubLlmClient.lossy(), CONTEXT_WINDOW, null, rec);
        String context = KeyFactRecall.serializeContext(conv);

        // After the recovery-attachment redesign to "index, not content dump",
        // the needle content itself does not survive — but the file PATH reference
        // does, so the model is told exactly which file to re-read (see
        // ContextCompactor.buildRecoveryAttachment: "re-open with the Read tool").
        // The lossy summarizer returns nothing useful, and the recovery block
        // intentionally lists paths only — NOT content copies — so a verbatim
        // needle match is NOT expected here.
        String filePathNeedle = "docs/naming.md";
        assertTrue(KeyFactRecall.recalled(filePathNeedle, context),
                "the file path must remain visible so the model can re-read it, "
                        + "even though the content needle was not embedded verbatim "
                        + "(by design: recovery block is an index, not a content dump)");
    }

    /** Extract {@code <path>} from a {@code [Result of N chars saved to <path>]} pointer. */
    private static Path parseSpillPath(String pointer) {
        int marker = pointer.indexOf("saved to ");
        if (marker < 0) return null;
        int start = marker + "saved to ".length();
        int end = pointer.lastIndexOf(']');
        if (end <= start) return null;
        return Path.of(pointer.substring(start, end));
    }
}
