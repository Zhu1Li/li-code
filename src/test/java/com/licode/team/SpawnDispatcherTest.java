package com.licode.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnDispatcherTest {

    @Test
    void shellQuoteSimpleString() {
        assertEquals("hello", SpawnDispatcher.shellQuote("hello"));
        assertEquals("test-123", SpawnDispatcher.shellQuote("test-123"));
        assertEquals("path/to/file", SpawnDispatcher.shellQuote("path/to/file"));
    }

    @Test
    void shellQuotePathWithBackslash() {
        // Colon is not in safe-chars regex, so it gets quoted
        assertEquals("'C:/Users/test'", SpawnDispatcher.shellQuote("C:/Users/test"));
        assertEquals("./relative/path", SpawnDispatcher.shellQuote("./relative/path"));
    }

    @Test
    void shellQuoteStringWithSpaces() {
        String quoted = SpawnDispatcher.shellQuote("hello world");
        assertTrue(quoted.startsWith("'"));
        assertTrue(quoted.endsWith("'"));
        assertTrue(quoted.contains("hello world"));
    }

    @Test
    void shellQuoteStringWithSpecialChars() {
        String quoted = SpawnDispatcher.shellQuote("it's working");
        assertTrue(quoted.startsWith("'"));
        assertTrue(quoted.endsWith("'"));
    }

    @Test
    void shellQuoteNull() {
        assertEquals("''", SpawnDispatcher.shellQuote(null));
    }

    @Test
    void shellQuoteEmpty() {
        assertEquals("''", SpawnDispatcher.shellQuote(""));
    }

    @Test
    void buildTeammateCLIContainsRequiredArgs() {
        String cli = SpawnDispatcher.buildTeammateCLI("myteam", "worker1", "/home/user/project");
        assertTrue(cli.contains("--teammate"));
        assertTrue(cli.contains("--team-name"));
        assertTrue(cli.contains("myteam"));
        assertTrue(cli.contains("--agent-name"));
        assertTrue(cli.contains("worker1"));
        assertTrue(cli.contains("cd "));
    }

    @Test
    void buildTeammateCLIUsesWorkdir() {
        String cli = SpawnDispatcher.buildTeammateCLI("team1", "agent1", "/custom/workdir");
        assertTrue(cli.contains("/custom/workdir"));
    }

    @Test
    void buildTeammateCLIStartsWithCd() {
        String cli = SpawnDispatcher.buildTeammateCLI("team1", "agent1", "/tmp");
        assertTrue(cli.startsWith("cd "));
        assertTrue(cli.contains(" && "));
    }

    @Test
    void resolveExecutablePathDoesNotThrow() {
        assertDoesNotThrow(SpawnDispatcher::resolveExecutablePath);
        String path = SpawnDispatcher.resolveExecutablePath();
        assertNotNull(path);
        assertFalse(path.isEmpty());
    }
}
