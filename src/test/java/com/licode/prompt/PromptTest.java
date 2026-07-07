package com.licode.prompt;

import com.licode.agent.AgentEvent;
import com.licode.llm.StreamEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptTest {

    // ── T1-T3: PromptBuilder ──────────────────────────────────────

    @Test
    void testSectionRecord() {
        var s = new PromptBuilder.Section(42, "test content");
        assertEquals(42, s.priority());
        assertEquals("test content", s.content());
    }

    @Test
    void testBuildOptionsRecord() {
        var opts = new PromptBuilder.BuildOptions("custom", "skills", "memory", null);
        assertEquals("custom", opts.customInstructions());
        assertEquals("skills", opts.skillSection());
        assertEquals("memory", opts.memorySection());
        assertNull(opts.environmentContext());
    }

    @Test
    void testBuildSortsByPriority() {
        var builder = new PromptBuilder();
        builder.add(new PromptBuilder.Section(50, "middle"));
        builder.add(new PromptBuilder.Section(10, "first"));
        builder.add(new PromptBuilder.Section(90, "last"));
        String result = builder.build();
        // "first" before "middle" before "last"
        int firstPos = result.indexOf("first");
        int middlePos = result.indexOf("middle");
        int lastPos = result.indexOf("last");
        assertTrue(firstPos < middlePos);
        assertTrue(middlePos < lastPos);
    }

    @Test
    void testBuildSkipsEmptySections() {
        var builder = new PromptBuilder();
        builder.add(new PromptBuilder.Section(0, "  "));
        builder.add(new PromptBuilder.Section(10, "valid"));
        builder.add(new PromptBuilder.Section(20, ""));
        String result = builder.build();
        assertEquals("valid", result);
    }

    @Test
    void testSectionSeparatedByDoubleNewline() {
        var builder = new PromptBuilder();
        builder.add(new PromptBuilder.Section(0, "sectionA"));
        builder.add(new PromptBuilder.Section(10, "sectionB"));
        String result = builder.build();
        assertTrue(result.contains("\n\n"));
        assertFalse(result.contains("\n\n\n"));
    }

    @Test
    void testDetectEnvironment() {
        var env = PromptBuilder.detectEnvironment("test-model");
        assertNotNull(env.workDir());
        assertFalse(env.workDir().isEmpty());
        assertNotNull(env.os());
        assertNotNull(env.arch());
        assertNotNull(env.shell());
        assertNotNull(env.model());
        assertEquals("test-model", env.model());
        assertNotNull(env.date());
        assertFalse(env.date().isEmpty());
        // date should be ISO format YYYY-MM-DD
        assertTrue(env.date().matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void testDetectEnvironmentNonGitRepoDoesNotThrow() {
        // Should not throw even in non-git directories
        var env = PromptBuilder.detectEnvironment("any-model");
        assertNotNull(env);
        // isGitRepo is false when not in a git repo or git not available
        // (in test context, it may be true if project is a git repo)
    }

    // ── T3: Fingerprint ───────────────────────────────────────────

    @Test
    void testFingerprintChangesWithWorkDir() {
        var builder = new PromptBuilder();
        builder.computeFingerprint("gpt-4", "/path/a", null);
        assertTrue(builder.isStale("gpt-4", "/path/b", null));
    }

    @Test
    void testFingerprintChangesWithModel() {
        var builder = new PromptBuilder();
        builder.computeFingerprint("gpt-4", "/work", null);
        assertTrue(builder.isStale("gpt-5", "/work", null));
    }

    @Test
    void testFingerprintSameForSameInputs() {
        var builder = new PromptBuilder();
        builder.computeFingerprint("claude", "/dir", null);
        assertFalse(builder.isStale("claude", "/dir", null));
    }

    @Test
    void testFingerprintNullInitially() {
        var builder = new PromptBuilder();
        assertNull(builder.fingerprint());
        assertTrue(builder.isStale("any", "/path", null));
    }

    // ── T4: PromptSections ────────────────────────────────────────

    @Test
    void testAllSevenSectionsPresent() {
        var sections = new PromptBuilder.Section[]{
                PromptSections.identitySection(),
                PromptSections.systemSection(),
                PromptSections.doingTasksSection(),
                PromptSections.executingActionsSection(),
                PromptSections.usingToolsSection(),
                PromptSections.toneStyleSection(),
                PromptSections.textOutputSection(),
        };
        assertEquals(7, sections.length);
        // Verify priorities are 0,10,20,30,40,50,60
        assertEquals(0, sections[0].priority());
        assertEquals(10, sections[1].priority());
        assertEquals(20, sections[2].priority());
        assertEquals(30, sections[3].priority());
        assertEquals(40, sections[4].priority());
        assertEquals(50, sections[5].priority());
        assertEquals(60, sections[6].priority());
    }

    @Test
    void testIdentitySectionContainsLiCode() {
        String content = PromptSections.identitySection().content();
        assertTrue(content.contains("LiCode"));
    }

    @Test
    void testSystemSectionContainsSystemReminder() {
        String content = PromptSections.systemSection().content();
        assertTrue(content.contains("system-reminder"));
    }

    @Test
    void testToneStyleSectionContainsEmojiRule() {
        String content = PromptSections.toneStyleSection().content();
        assertTrue(content.contains("emoji"));
    }

    @Test
    void testToneStyleSectionContainsFilePathLineNumber() {
        String content = PromptSections.toneStyleSection().content();
        assertTrue(content.contains("file_path:line_number"));
    }

    @Test
    void testUsingToolsSectionContainsToolNamesAsBehavioralGuidance() {
        String content = PromptSections.usingToolsSection().content();
        // Tool names should appear as behavioral rules (use X instead of Y), per mewCode design
        assertTrue(content.contains("ReadFile"));
        assertTrue(content.contains("EditFile"));
        assertTrue(content.contains("WriteFile"));
        assertTrue(content.contains("Glob"));
        assertTrue(content.contains("Grep"));
        assertTrue(content.contains("Bash"));
        // But tool schema details (parameters, types) should NOT be in system prompt
        assertFalse(content.contains("file_path"));
        assertFalse(content.contains("old_string"));
        assertFalse(content.contains("pattern"));
    }

    @Test
    void testToneStyleNoHardWrapping() {
        String content = PromptSections.toneStyleSection().content();
        assertTrue(content.contains("Never insert manual line breaks"));
        assertTrue(content.contains("single continuous line of text"));
    }

    @Test
    void testEnvironmentSectionInSystemPrompt() {
        var env = new PromptBuilder.EnvironmentContext(
                "/home/user/project", "Linux", "amd64", "bash",
                true, "main", "claude-sonnet", "2026-06-19");
        var opts = new PromptBuilder.BuildOptions(null, null, null, env);
        String prompt = PromptBuilder.buildSystemPrompt(opts);
        // Environment is now a system prompt section (priority 70)
        assertTrue(prompt.contains("/home/user/project"));
        assertTrue(prompt.contains("Linux"));
        assertTrue(prompt.contains("claude-sonnet"));
    }

    @Test
    void testRenderEnvironment() {
        var env = new PromptBuilder.EnvironmentContext(
                "/home/user/project", "Linux", "amd64", "bash",
                true, "main", "claude-sonnet", "2026-06-19");
        String rendered = PromptSections.renderEnvironment(env);
        assertTrue(rendered.contains("/home/user/project"));
        assertTrue(rendered.contains("Linux"));
        assertTrue(rendered.contains("amd64"));
        assertTrue(rendered.contains("bash"));
        assertTrue(rendered.contains("main"));
        assertTrue(rendered.contains("claude-sonnet"));
        assertTrue(rendered.contains("2026-06-19"));
        // Linux should NOT show Windows shell warning
        assertFalse(rendered.contains("cmd.exe"));
        assertFalse(rendered.contains("NOT available"));
    }

    @Test
    void testRenderEnvironmentWindowsWarning() {
        var env = new PromptBuilder.EnvironmentContext(
                "D:\\work", "Windows 11", "amd64", "cmd",
                true, "master", "claude-sonnet", "2026-06-19");
        String rendered = PromptSections.renderEnvironment(env);
        assertTrue(rendered.contains("D:\\work"));
        assertTrue(rendered.contains("Windows 11"));
        assertTrue(rendered.contains("cmd"));
        // Windows must show the shell limitation warning
        assertTrue(rendered.contains("cmd.exe"));
        assertTrue(rendered.contains("NOT available"));
        assertTrue(rendered.contains("grep, wc, sed, awk, cat, head, tail"));
        assertTrue(rendered.contains("ReadFile, Grep, Glob, or EditFile"));
    }

    @Test
    void testExecutingActionsContainsGitSafety() {
        String content = PromptSections.executingActionsSection().content();
        assertTrue(content.contains("git") || content.contains("Git"));
    }

    // ── T4: buildSystemPrompt ─────────────────────────────────────

    @Test
    void testBuildSystemPromptAllFixedSections() {
        String prompt = PromptBuilder.buildSystemPrompt(
                new PromptBuilder.BuildOptions(null, null, null, null));
        assertTrue(prompt.contains("LiCode"));
        assertTrue(prompt.contains("system-reminder"));
        assertTrue(prompt.contains("emoji"));
        assertTrue(prompt.contains("file_path:line_number"));
    }

    @Test
    void testBuildSystemPromptWithCustomInstructions() {
        String prompt = PromptBuilder.buildSystemPrompt(
                new PromptBuilder.BuildOptions("CUSTOM RULE: always use tabs", null, null, null));
        assertTrue(prompt.contains("CUSTOM RULE: always use tabs"));
    }

    @Test
    void testBuildSystemPromptSkipsEmptyOptionals() {
        String prompt = PromptBuilder.buildSystemPrompt(
                new PromptBuilder.BuildOptions("", "", "", null));
        // Should still contain fixed sections but no empty optional blocks
        assertTrue(prompt.contains("LiCode"));
    }

    @Test
    void testBuildSystemPromptWithAllOptionals() {
        String prompt = PromptBuilder.buildSystemPrompt(
                new PromptBuilder.BuildOptions("CUSTOM", "SKILLS", "MEMORY", null));
        assertTrue(prompt.contains("CUSTOM"));
        assertTrue(prompt.contains("SKILLS"));
        assertTrue(prompt.contains("MEMORY"));
        // Custom(80) before Skills(90) before Memory(95)
        int customPos = prompt.indexOf("CUSTOM");
        int skillsPos = prompt.indexOf("SKILLS");
        int memoryPos = prompt.indexOf("MEMORY");
        assertTrue(customPos < skillsPos);
        assertTrue(skillsPos < memoryPos);
    }

    // ── T5: PlanModePrompt ────────────────────────────────────────

    @Test
    void testPlanModeFirstIterationFull() {
        String reminder = PlanModePrompt.buildReminder(1, false);
        assertTrue(reminder.contains("Plan-only mode is active"));
        assertTrue(reminder.contains("codebase")); // Full reminder has detailed steps
    }

    @Test
    void testPlanModeSparseIterations() {
        for (int i = 2; i <= 4; i++) {
            String reminder = PlanModePrompt.buildReminder(i, false);
            assertFalse(reminder.contains("Plan-only mode is active"));
            assertTrue(reminder.contains("Plan-only mode"));
            assertTrue(reminder.length() < 100); // Sparse is short
        }
    }

    @Test
    void testPlanModeIntervalRepeat() {
        String reminder = PlanModePrompt.buildReminder(6, false);
        assertTrue(reminder.contains("Plan-only mode is active"));
        assertTrue(reminder.contains("Work in this mode"));
    }

    @Test
    void testPlanModeIntervalEveryFive() {
        // iteration 6 = 1 + 5 → full (contains "codebase")
        assertTrue(PlanModePrompt.buildReminder(6, false).contains("codebase"));
        // iteration 7 → sparse (does NOT contain "codebase")
        assertFalse(PlanModePrompt.buildReminder(7, false).contains("codebase"));
        // iteration 11 = 1 + 10 → full
        assertTrue(PlanModePrompt.buildReminder(11, false).contains("codebase"));
        // iteration 12 → sparse
        assertFalse(PlanModePrompt.buildReminder(12, false).contains("codebase"));
    }

    // ── T6: Cache metrics flow ────────────────────────────────────

    @Test
    void testStreamEndCarriesCacheTokens() {
        var se = new StreamEvent.StreamEnd("end_turn", 100, 50, 30, 10);
        assertEquals("end_turn", se.stopReason());
        assertEquals(100, se.inputTokens());
        assertEquals(50, se.outputTokens());
        assertEquals(30, se.cacheReadTokens());
        assertEquals(10, se.cacheCreationTokens());
    }

    @Test
    void testStreamEndDefaultsCacheTokensToZero() {
        var se = new StreamEvent.StreamEnd("end_turn", 100, 50);
        assertEquals(0, se.cacheReadTokens());
        assertEquals(0, se.cacheCreationTokens());
    }

    @Test
    void testUsageEventCarriesCacheTokens() {
        var ue = new AgentEvent.UsageEvent(100, 50, 30, 10);
        assertEquals(100, ue.inputTokens());
        assertEquals(50, ue.outputTokens());
        assertEquals(30, ue.cacheReadTokens());
        assertEquals(10, ue.cacheCreationTokens());
    }

    @Test
    void testUsageEventDefaultsCacheTokensToZero() {
        var ue = new AgentEvent.UsageEvent(100, 50);
        assertEquals(0, ue.cacheReadTokens());
        assertEquals(0, ue.cacheCreationTokens());
    }
}
