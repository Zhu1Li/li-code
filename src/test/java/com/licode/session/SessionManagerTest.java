package com.licode.session;

import com.licode.conversation.Message;
import com.licode.conversation.ThinkingBlock;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    // ── Session lifecycle ────────────────────────────────────────────

    @Test
    void createSessionReturnsTimestampFormat(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String id = sm.createSession();
        assertTrue(id.matches("\\d{8}-\\d{6}-\\d+"), "Expected yyyyMMdd-HHmmss-millis, got: " + id);
    }

    @Test
    void createsSessionsDir(@TempDir Path workDir) {
        new SessionManager(workDir);
        assertTrue(Files.exists(workDir.resolve(".licode/sessions")));
    }

    // ── Save / Load ──────────────────────────────────────────────────

    @Test
    void saveAndLoadMessage(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String sid = sm.createSession();
        Message msg = Message.user("hello world");
        sm.saveMessage(sid, msg);

        List<SessionManager.SessionMessage> loaded = sm.loadSession(sid);
        assertEquals(1, loaded.size());
        assertEquals("user", loaded.get(0).role());
        assertEquals("hello world", loaded.get(0).content());
    }

    @Test
    void saveMessageWithToolUseAndResult(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String sid = sm.createSession();

        Message assistant = new Message("assistant", null);
        assistant.setToolUses(List.of(new ToolUseBlock("tu1", "ReadFile",
                Map.of("file_path", "/test.txt"))));
        sm.saveMessage(sid, assistant);

        Message toolResult = Message.user(null);
        toolResult.setToolResults(List.of(new ToolResultBlock("tu1", "file content", false)));
        sm.saveMessage(sid, toolResult);

        List<SessionManager.SessionMessage> loaded = sm.loadSession(sid);
        assertEquals(2, loaded.size());
        assertEquals(1, loaded.get(0).toolUses().size());
        assertEquals("ReadFile", loaded.get(0).toolUses().get(0).name());
    }

    @Test
    void saveMessageWithThinkingBlock(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String sid = sm.createSession();

        Message assistant = new Message("assistant", "final answer");
        assistant.setThinkingBlocks(List.of(
                new ThinkingBlock("step 1: analyze", "sig1"),
                new ThinkingBlock("step 2: verify", "sig2")));
        sm.saveMessage(sid, assistant);

        List<SessionManager.SessionMessage> loaded = sm.loadSession(sid);
        assertEquals(1, loaded.size());
        assertEquals("assistant", loaded.get(0).role());
        assertNotNull(loaded.get(0).thinkingBlocks());
        assertEquals(2, loaded.get(0).thinkingBlocks().size());
        assertEquals("step 1: analyze", loaded.get(0).thinkingBlocks().get(0).thinking());
        assertEquals("sig2", loaded.get(0).thinkingBlocks().get(1).signature());
    }

    @Test
    void loadEmptyJsonlReturnsEmptyList(@TempDir Path workDir) throws Exception {
        SessionManager sm = new SessionManager(workDir);
        String sid = sm.createSession();
        // Create empty .jsonl file
        Files.createFile(workDir.resolve(".licode/sessions/" + sid + ".jsonl"));

        List<SessionManager.SessionMessage> loaded = sm.loadSession(sid);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void loadNonExistentSessionReturnsEmptyList(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        List<SessionManager.SessionMessage> loaded = sm.loadSession("nonexistent");
        assertTrue(loaded.isEmpty());
    }

    // ── Meta ─────────────────────────────────────────────────────────

    @Test
    void saveAndGetMeta(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String sid = sm.createSession();
        var info = new SessionManager.SessionInfo(
                sid, "first message here", 10, 500L,
                "main", java.time.Instant.now()
        );
        sm.saveMeta(sid, info);

        SessionManager.SessionInfo loaded = sm.getSession(sid);
        assertNotNull(loaded);
        assertEquals("first message here", loaded.firstMessage());
        assertEquals(10, loaded.messageCount());
    }

    @Test
    void getSessionNonExistentReturnsNull(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        assertNull(sm.getSession("nonexistent"));
    }

    // ── List sessions ────────────────────────────────────────────────

    @Test
    void listSessionsReturnsAll(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String sid1 = sm.createSession();
        String sid2 = sm.createSession();
        sm.saveMeta(sid1, new SessionManager.SessionInfo(
                sid1, "first", 1, 100L, "main", java.time.Instant.now()));
        sm.saveMeta(sid2, new SessionManager.SessionInfo(
                sid2, "second", 1, 100L, "main", java.time.Instant.now()));

        var list = sm.listSessions(null, null, null);
        assertEquals(2, list.size());
    }

    @Test
    void listSessionsKeywordFilter(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String sid = sm.createSession();
        sm.saveMeta(sid, new SessionManager.SessionInfo(
                sid, "fix authentication bug", 1, 100L, "main", java.time.Instant.now()));

        var list = sm.listSessions("authentication", null, null);
        assertEquals(1, list.size());

        var empty = sm.listSessions("nonexistent_keyword", null, null);
        assertEquals(0, empty.size());
    }

    // ── Delete ───────────────────────────────────────────────────────

    @Test
    void deleteSessionRemovesFiles(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String sid = sm.createSession();
        sm.saveMeta(sid, new SessionManager.SessionInfo(
                sid, "test", 1, 100L, "main", java.time.Instant.now()));
        sm.saveMessage(sid, Message.user("hello"));

        assertTrue(sm.deleteSession(sid));
        assertNull(sm.getSession(sid));
        assertTrue(sm.loadSession(sid).isEmpty());
    }

    // ── Compact boundary ─────────────────────────────────────────────

    @Test
    void saveAndLoadCompactBoundary(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        String sid = sm.createSession();
        sm.saveCompactBoundary(sid, "summary text", List.of(0, 2));

        List<SessionManager.SessionMessage> loaded = sm.loadSession(sid);
        assertEquals(1, loaded.size());
        assertEquals("compact_boundary", loaded.get(0).type());
        assertEquals("summary text", loaded.get(0).content());
        assertEquals(List.of(0, 2), loaded.get(0).keepIndices());
    }

    // ── Rebuild: truncation ──────────────────────────────────────────

    @Test
    void rebuildTruncatesUnpairedToolUse(@TempDir Path workDir) {
        var messages = List.of(
                new SessionManager.SessionMessage("user", "hello", "2026-01-01 00:00:01", null, null, null, null, null),
                new SessionManager.SessionMessage("assistant", null, "2026-01-01 00:00:02", null, null,
                        List.of(new SessionManager.ToolUseSer("tu1", "Bash", Map.of("command", "ls"))),
                        null, null)
        );
        // No matching tool result — should truncate the last assistant message

        SessionManager sm = new SessionManager(workDir);
        var result = sm.rebuildConversation(messages);
        assertEquals(1, result.messages().size());
    }

    @Test
    void rebuildKeepsPairedToolUse(@TempDir Path workDir) {
        var messages = List.of(
                new SessionManager.SessionMessage("user", "hello", "2026-01-01 00:00:01", null, null, null, null, null),
                new SessionManager.SessionMessage("assistant", null, "2026-01-01 00:00:02", null, null,
                        List.of(new SessionManager.ToolUseSer("tu1", "Bash", Map.of("command", "ls"))),
                        null, null),
                new SessionManager.SessionMessage("user", null, "2026-01-01 00:00:03", null, null, null,
                        List.of(new SessionManager.ToolResultSer("tu1", "output", false)), null)
        );
        // Tool result matches tool use — should keep all

        SessionManager sm = new SessionManager(workDir);
        var result = sm.rebuildConversation(messages);
        assertEquals(3, result.messages().size());
    }

    // ── Rebuild: compaction boundary ─────────────────────────────────

    @Test
    void rebuildWithCompactionBoundary(@TempDir Path workDir) {
        var messages = List.of(
                new SessionManager.SessionMessage("user", "old message", "2026-01-01 00:00:01", null, null, null, null, null),
                new SessionManager.SessionMessage("user", "compaction summary", "2026-01-01 00:00:02",
                        "compact_boundary", List.of(0), null, null, null),
                new SessionManager.SessionMessage("user", "new message", "2026-01-01 00:00:03", null, null, null, null, null)
        );
        // Boundary at index 1 with keep index 0 → should inject summary + keep message 0 + message 2

        SessionManager sm = new SessionManager(workDir);
        var result = sm.rebuildConversation(messages);
        assertTrue(result.compacted());
        // Summary (user) + ack (assistant) + kept(0) + post-boundary(1)
        assertEquals(4, result.messages().size());
    }

    @Test
    void rebuildEmptyMessages(@TempDir Path workDir) {
        SessionManager sm = new SessionManager(workDir);
        var result = sm.rebuildConversation(List.of());
        assertTrue(result.messages().isEmpty());
        assertFalse(result.compacted());
        assertNull(result.lastActiveTime());
    }

    // ── daysSince ────────────────────────────────────────────────────

    @Test
    void daysSinceZeroForNow() {
        int days = SessionManager.daysSince(java.time.Instant.now());
        assertEquals(0, days);
    }

    @Test
    void daysSincePositiveForPast() {
        int days = SessionManager.daysSince(java.time.Instant.now().minus(java.time.Duration.ofDays(3)));
        assertTrue(days >= 2, "Expected at least 2 days, got: " + days);
    }

    @Test
    void daysSinceZeroForNull() {
        assertEquals(0, SessionManager.daysSince(null));
    }
}
