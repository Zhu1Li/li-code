package com.licode.worktree;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeSessionStoreTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        WorktreeSessionStore.clearForTesting();
    }

    @Test
    void saveAndLoad_shouldRoundTrip() throws Exception {
        var session = new WorktreeSession(
                "/home/project", "/home/project/.licode/worktrees/demo",
                "demo", "worktree-demo", "main", "sess-001");

        WorktreeSessionStore.save(tempDir.toString(), session);
        Path sessionFile = tempDir.resolve(".licode/worktree_session.json");
        assertTrue(Files.exists(sessionFile));

        var loaded = WorktreeSessionStore.load(tempDir.toString());
        assertNotNull(loaded);
        assertEquals("demo", loaded.worktreeName());
        assertEquals("worktree-demo", loaded.worktreeBranch());
        assertEquals("/home/project", loaded.originalCwd());
    }

    @Test
    void saveNull_shouldDeleteFile() throws Exception {
        var session = new WorktreeSession(
                "/home/project", "/tmp/wt", "test", "worktree-test", "main", "sess");
        WorktreeSessionStore.save(tempDir.toString(), session);
        assertTrue(Files.exists(tempDir.resolve(".licode/worktree_session.json")));

        WorktreeSessionStore.save(tempDir.toString(), null);
        assertFalse(Files.exists(tempDir.resolve(".licode/worktree_session.json")));
    }

    @Test
    void load_nonexistent_shouldReturnNull() {
        var loaded = WorktreeSessionStore.load(tempDir.resolve("nonexistent").toString());
        assertNull(loaded);
    }

    @Test
    void getAndRestore_shouldMaintainSingleton() {
        var session = new WorktreeSession(
                "/cwd", "/wt", "test", "worktree-test", "main", "id");

        assertNull(WorktreeSessionStore.getCurrentSession());
        WorktreeSessionStore.restoreSession(session);
        assertSame(session, WorktreeSessionStore.getCurrentSession());
        WorktreeSessionStore.restoreSession(null);
        assertNull(WorktreeSessionStore.getCurrentSession());
    }
}
