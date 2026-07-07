package com.licode.worktree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentWorktreeTest {

    @Test
    void buildNotice_shouldContainKeyPhrases() {
        String notice = AgentWorktree.buildNotice("/home/parent", "/home/worktree/agent-a1234567");
        assertNotNull(notice);
        assertTrue(notice.contains("isolated git worktree"));
        assertTrue(notice.contains("translate them"));
        assertTrue(notice.contains("Re-read files before editing"));
        assertTrue(notice.contains("will not affect the parent's files"));
        assertTrue(notice.contains("/home/parent"));
        assertTrue(notice.contains("/home/worktree/agent-a1234567"));
    }

    @Test
    void remove_noGitRoot_shouldReturnFalse() {
        assertFalse(AgentWorktree.remove("/tmp/wt", "worktree-test", null));
        assertFalse(AgentWorktree.remove("/tmp/wt", "worktree-test", ""));
    }
}
