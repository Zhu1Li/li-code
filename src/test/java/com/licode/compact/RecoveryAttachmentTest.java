package com.licode.compact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryAttachmentTest {

    @Test
    void emptyWhenNothingRecorded() {
        RecoveryState state = new RecoveryState();
        String attachment = ContextCompactor.buildRecoveryAttachment(state, null);
        assertEquals("", attachment);
    }

    @Test
    void emptyWhenNullState() {
        String attachment = ContextCompactor.buildRecoveryAttachment(null, null);
        assertEquals("", attachment);
    }

    @Test
    void emitsFileSectionWithPathsOnly() {
        RecoveryState state = new RecoveryState();
        state.recordFileRead("/path/to/file.txt", "file content here");

        String attachment = ContextCompactor.buildRecoveryAttachment(state, null);

        assertTrue(attachment.contains("/path/to/file.txt"), "should list file path");
        assertTrue(attachment.contains("Re-open with the Read tool"),
                "should instruct to re-read");
        assertTrue(attachment.contains("## Note"), "should include guidance");
        // Content body is no longer dumped — path + timestamp only.
        assertFalse(attachment.contains("file content here"),
                "should NOT dump file content");
    }

    @Test
    void fileLimitAndNewestFirst() {
        RecoveryState state = new RecoveryState();
        for (int i = 1; i <= 7; i++) {
            state.recordFileRead("/file" + i + ".txt", "content" + i);
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }

        String attachment = ContextCompactor.buildRecoveryAttachment(state, null);

        // file7 (newest) should appear before file3
        int pos7 = attachment.indexOf("/file7.txt");
        int pos3 = attachment.indexOf("/file3.txt");
        assertTrue(pos7 > 0, "file7 should be present");
        assertTrue(pos3 > 0, "file3 should be present");
        assertTrue(pos7 < pos3, "file7 (newest) should appear before file3");

        // file1 and file2 (oldest) should not appear (limit 5)
        assertFalse(attachment.contains("/file1.txt"));
        assertFalse(attachment.contains("/file2.txt"));
    }

    @Test
    void emitsSkillSectionWithNamesOnly() {
        RecoveryState state = new RecoveryState();
        state.recordSkillInvocation("code-review", "Full SOP body text here...");

        String attachment = ContextCompactor.buildRecoveryAttachment(state, null);

        assertTrue(attachment.contains("code-review"), "should list skill name");
        assertTrue(attachment.contains("Re-invoke with LoadSkill"),
                "should instruct to reload");
        // SOP body is no longer dumped — name only.
        assertFalse(attachment.contains("Full SOP body text here"),
                "should NOT dump SOP body");
    }

    @Test
    void skipsFileSectionWhenNoFilesRecorded() {
        RecoveryState state = new RecoveryState();
        // Only skills, no files
        state.recordSkillInvocation("test-skill", "body");

        String attachment = ContextCompactor.buildRecoveryAttachment(state, null);

        assertFalse(attachment.contains("Recently read files"));
        assertTrue(attachment.contains("Active skills"));
    }
}
