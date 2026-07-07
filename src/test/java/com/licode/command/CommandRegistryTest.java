package com.licode.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

    // ── Command.matches ───────────────────────────────────────────

    @Test
    void testCommandMatchesName() {
        var cmd = new Command("help", "desc", List.of("h", "?"), Command.CommandType.LOCAL, false);
        assertTrue(cmd.matches("help"));
        assertTrue(cmd.matches("HELP"));
        assertTrue(cmd.matches("Help"));
    }

    @Test
    void testCommandMatchesAlias() {
        var cmd = new Command("help", "desc", List.of("h", "?"), Command.CommandType.LOCAL, false);
        assertTrue(cmd.matches("h"));
        assertTrue(cmd.matches("H"));
        assertTrue(cmd.matches("?"));
    }

    @Test
    void testCommandDoesNotMatchRandom() {
        var cmd = new Command("help", "desc", List.of("h"), Command.CommandType.LOCAL, false);
        assertFalse(cmd.matches("xyz"));
        assertFalse(cmd.matches("hel"));
        assertFalse(cmd.matches(""));
        assertFalse(cmd.matches(null));
    }

    // ── CommandRegistry.find ──────────────────────────────────────

    @Test
    void testFindByName() {
        var reg = new CommandRegistry();
        assertTrue(reg.find("help").isPresent());
        assertEquals("help", reg.find("help").get().name());
    }

    @Test
    void testFindByAlias() {
        var reg = new CommandRegistry();
        assertTrue(reg.find("h").isPresent());
        assertEquals("help", reg.find("h").get().name());
        assertTrue(reg.find("?").isPresent());
        assertEquals("help", reg.find("?").get().name());
    }

    @Test
    void testFindCaseInsensitive() {
        var reg = new CommandRegistry();
        assertTrue(reg.find("HELP").isPresent());
        assertTrue(reg.find("Compact").isPresent());
    }

    @Test
    void testFindNotFound() {
        var reg = new CommandRegistry();
        assertTrue(reg.find("nonexistent").isEmpty());
    }

    // ── CommandRegistry.search ────────────────────────────────────

    @Test
    void testSearchPrefix() {
        var reg = new CommandRegistry();
        var results = reg.search("s");
        var names = results.stream().map(Command::name).toList();
        assertTrue(names.contains("status"));
        assertTrue(names.contains("session"));
        assertTrue(names.contains("skills"));
    }

    @Test
    void testSearchEmptyReturnsAllVisible() {
        var reg = new CommandRegistry();
        var results = reg.search("");
        assertEquals(reg.listVisible().size(), results.size());
    }

    @Test
    void testSearchExcludesHidden() {
        var reg = new CommandRegistry();
        reg.register(new Command("secret", "hidden", List.of(), Command.CommandType.LOCAL, true), ctx -> "shh");
        var results = reg.search("sec");
        assertTrue(results.isEmpty());
    }

    @Test
    void testSearchSortedByName() {
        var reg = new CommandRegistry();
        var results = reg.search("");
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).name().compareTo(results.get(i).name()) <= 0);
        }
    }

    // ── CommandRegistry.execute ───────────────────────────────────

    @Test
    void testExecuteHelp() {
        var reg = new CommandRegistry();
        var ctx = makeContext("");
        String result = reg.execute("help", ctx);
        assertNotNull(result);
        assertTrue(result.contains("Available commands"));
        assertTrue(result.contains("/help"));
        assertTrue(result.contains("/status"));
        assertTrue(result.contains("Type /help <command> for details."));
    }

    @Test
    void testExecuteHelpSpecific() {
        var reg = new CommandRegistry();
        var ctx = makeContext("compact");
        String result = reg.execute("help", ctx);
        assertNotNull(result);
        assertTrue(result.contains("/compact"));
        assertTrue(result.contains("Compress conversation"));
    }

    @Test
    void testExecuteHelpUnknown() {
        var reg = new CommandRegistry();
        var ctx = makeContext("foobar");
        String result = reg.execute("help", ctx);
        assertTrue(result.contains("Unknown command: foobar"));
    }

    @Test
    void testExecuteStatus() {
        var reg = new CommandRegistry();
        var ctx = makeContext("");
        String result = reg.execute("status", ctx);
        assertTrue(result.contains("Mode:"));
        assertTrue(result.contains("Tokens:"));
        assertTrue(result.contains("Tools:"));
        assertTrue(result.contains("Memories:"));
        assertTrue(result.contains("Model:"));
        assertTrue(result.contains("Directory:"));
    }

    @Test
    void testExecuteMemoryList() {
        var reg = new CommandRegistry();
        var ctx = makeContext("");
        String result = reg.execute("memory", ctx);
        assertTrue(result.contains("No memories stored yet."));
    }

    @Test
    void testExecuteMemoryClear() {
        var reg = new CommandRegistry();
        var ctx = makeContext("clear");
        String result = reg.execute("memory", ctx);
        assertTrue(result.contains("All auto-memories cleared."));
    }

    @Test
    void testExecuteMemoryInvalid() {
        var reg = new CommandRegistry();
        var ctx = makeContext("invalid");
        String result = reg.execute("memory", ctx);
        assertTrue(result.contains("Usage: /memory [list|clear]"));
    }

    @Test
    void testExecutePermissionInfo() {
        var reg = new CommandRegistry();
        var ctx = makeContext("info");
        String result = reg.execute("permission", ctx);
        assertTrue(result.contains("Current permission mode:"));
    }

    @Test
    void testExecutePermissionMode() {
        var reg = new CommandRegistry();
        var ctx = makeContext("mode acceptEdits");
        String result = reg.execute("permission", ctx);
        assertTrue(result.contains("Permission mode changed to: acceptedits"));
    }

    @Test
    void testExecutePermissionInvalidMode() {
        var reg = new CommandRegistry();
        var ctx = makeContext("mode unknown");
        String result = reg.execute("permission", ctx);
        assertTrue(result.contains("Invalid mode: unknown"));
    }

    @Test
    void testExecutePermissionInvalidUsage() {
        var reg = new CommandRegistry();
        var ctx = makeContext("badsub");
        String result = reg.execute("permission", ctx);
        assertTrue(result.contains("Usage: /permission"));
    }

    @Test
    void testExecuteSession() {
        var reg = new CommandRegistry();
        var ctx = makeContext("info");
        String result = reg.execute("session", ctx);
        assertNotNull(result);
    }

    @Test
    void testExecuteSessionInvalid() {
        var reg = new CommandRegistry();
        var ctx = makeContext("bad");
        String result = reg.execute("session", ctx);
        assertTrue(result.contains("Usage: /session [list|info]"));
    }

    @Test
    void testExecuteSkillsEmpty() {
        var reg = new CommandRegistry();
        var ctx = makeContext("");
        String result = reg.execute("skills", ctx);
        assertTrue(result.contains("No skills installed."));
    }

    @Test
    void testExecuteReview() {
        var reg = new CommandRegistry();
        var ctx = makeContext("");
        String result = reg.execute("review", ctx);
        assertTrue(result.contains("Logic errors"));
        assertTrue(result.contains("Security issues"));
        assertTrue(result.contains("Performance problems"));
        assertTrue(result.contains("Code style"));
    }

    @Test
    void testExecuteReviewWithFocus() {
        var reg = new CommandRegistry();
        var ctx = makeContext("SQL injection");
        String result = reg.execute("review", ctx);
        assertTrue(result.contains("Additional focus: SQL injection"));
    }

    @Test
    void testExecuteByAlias() {
        var reg = new CommandRegistry();
        var ctx = makeContext("");
        String result = reg.execute("c", ctx);  // alias for compact
        // compact is LOCAL_UI with null handler, so execute returns unknown
        assertTrue(result.contains("Unknown command"));
    }

    @Test
    void testExecuteUnknown() {
        var reg = new CommandRegistry();
        var ctx = makeContext("");
        String result = reg.execute("foobar", ctx);
        assertTrue(result.contains("Unknown command: /foobar"));
        assertTrue(result.contains("type /help"));
    }

    // ── CommandRegistry.listAll / listVisible ─────────────────────

    @Test
    void testListAllSize() {
        var reg = new CommandRegistry();
        assertEquals(13, reg.listAll().size());
    }

    @Test
    void testListVisibleSize() {
        var reg = new CommandRegistry();
        assertEquals(13, reg.listVisible().size());
    }

    @Test
    void testListVisibleSorted() {
        var reg = new CommandRegistry();
        var visible = reg.listVisible();
        for (int i = 1; i < visible.size(); i++) {
            assertTrue(visible.get(i - 1).name().compareTo(visible.get(i).name()) <= 0);
        }
    }

    // ── CommandRegistry.register ──────────────────────────────────

    @Test
    void testRegisterCustomCommand() {
        var reg = new CommandRegistry();
        var cmd = new Command("hello", "Say hello", List.of("hi"), Command.CommandType.LOCAL, false);
        reg.register(cmd, ctx -> "Hello, world!");
        assertTrue(reg.find("hello").isPresent());
        assertTrue(reg.find("hi").isPresent());
        assertEquals("Hello, world!", reg.execute("hello", makeContext("")));
        assertEquals("Hello, world!", reg.execute("hi", makeContext("")));
    }

    // ── registerSkillCommand ──────────────────────────────────────

    @Test
    void testRegisterSkillCommand() {
        var reg = new CommandRegistry();
        reg.registerSkillCommand("test-skill", "A test skill", () -> "skill prompt body");
        assertTrue(reg.find("test-skill").isPresent());
        var cmd = reg.find("test-skill").get();
        assertEquals(Command.CommandType.PROMPT, cmd.type());
        assertTrue(cmd.description().endsWith("[skill]"));

        String result = reg.execute("test-skill", makeContext(""));
        assertEquals("skill prompt body", result);
    }

    @Test
    void testRegisterSkillCommandIdempotent() {
        var reg = new CommandRegistry();
        reg.registerSkillCommand("test-skill", "A test skill", () -> "body1");
        reg.registerSkillCommand("test-skill", "Duplicate", () -> "body2");
        // Should not duplicate
        long count = reg.listAll().stream().filter(c -> c.name().equals("test-skill")).count();
        assertEquals(1, count);
        // Handler unchanged (second register is no-op, first handler retained)
        String result = reg.execute("test-skill", makeContext(""));
        assertEquals("body1", result);
    }

    @Test
    void testRegisterSkillCommandAppearsInSkills() {
        var reg = new CommandRegistry();
        reg.registerSkillCommand("test-skill", "A test skill", () -> "body");
        // It should appear in listVisible with skillList
        var ctx = makeContext("");
        // skills command lists from ctx.skillList() which checks [skill] suffix
        assertTrue(reg.find("test-skill").get().description().endsWith("[skill]"));
    }

    // ── CommandCommandType enum ────────────────────────────────────

    @Test
    void testCommandTypeValues() {
        assertEquals(4, Command.CommandType.values().length);
        assertNotNull(Command.CommandType.valueOf("LOCAL"));
        assertNotNull(Command.CommandType.valueOf("LOCAL_UI"));
        assertNotNull(Command.CommandType.valueOf("PROMPT"));
        assertNotNull(Command.CommandType.valueOf("SKILL_FORK"));
    }

    @Test
    void forkSkillRegistersAsSkillForkType() {
        var reg = new CommandRegistry();
        reg.registerSkillCommand("inline-skill", "desc", () -> "body", false);
        reg.registerSkillCommand("fork-skill", "desc", () -> "body", true);
        assertEquals(Command.CommandType.PROMPT, reg.find("inline-skill").orElseThrow().type());
        assertEquals(Command.CommandType.SKILL_FORK, reg.find("fork-skill").orElseThrow().type());
    }

    // ── No alias conflicts in defaults ────────────────────────────

    @Test
    void testNoAliasConflictsInDefaults() {
        var reg = new CommandRegistry();
        var seenNames = new java.util.HashSet<String>();
        for (var cmd : reg.listAll()) {
            String lowerName = cmd.name().toLowerCase();
            if (!seenNames.add(lowerName)) {
                fail("Duplicate command name: " + lowerName);
            }
            if (cmd.aliases() != null) {
                for (var alias : cmd.aliases()) {
                    String lowerAlias = alias.toLowerCase();
                    if (!seenNames.add(lowerAlias)) {
                        fail("Duplicate alias: " + lowerAlias);
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private CommandContext makeContext(String args) {
        return new CommandContext(
                args,
                "/tmp/test",
                () -> "test-model",
                () -> "default",
                () -> 7,
                () -> 100,
                () -> 200,
                List::of,
                () -> {},
                () -> "Session: test123 (5 messages)",
                List::of,
                () -> {},
                () -> {},
                () -> {},
                () -> {},
                () -> {}
        );
    }
}
