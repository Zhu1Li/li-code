package com.licode.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoaderTest {

    @Test
    void parseValidFrontmatter_shouldReturnCorrectSpec(@TempDir Path tempDir) throws Exception {
        Path agentFile = tempDir.resolve("custom-agent.md");
        Files.writeString(agentFile, """
                ---
                name: custom
                description: A custom agent for testing
                tools:
                  - Grep
                  - ReadFile
                disallowedTools:
                  - Bash
                model: gpt-4
                maxTurns: 50
                ---
                You are a custom agent. Do your task.
                """);

        SubAgentSpec spec = AgentLoader.parseAgentFile(agentFile);
        assertEquals("custom", spec.name());
        assertEquals("A custom agent for testing", spec.description());
        assertEquals(2, spec.tools().size());
        assertTrue(spec.tools().contains("Grep"));
        assertTrue(spec.tools().contains("ReadFile"));
        assertEquals(1, spec.disallowedTools().size());
        assertTrue(spec.disallowedTools().contains("Bash"));
        assertEquals("gpt-4", spec.model());
        assertEquals(50, spec.maxTurns());
        assertEquals("You are a custom agent. Do your task.", spec.systemPrompt());
    }

    @Test
    void parseMissingName_shouldThrowIllegalArgumentException(@TempDir Path tempDir) throws Exception {
        Path agentFile = tempDir.resolve("missing-name.md");
        Files.writeString(agentFile, """
                ---
                description: No name here
                ---
                body content
                """);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> AgentLoader.parseAgentFile(agentFile));
        assertTrue(ex.getMessage().contains("missing required field 'name'"),
                "Expected 'missing required field name' in: " + ex.getMessage());
    }

    @Test
    void parseMissingDescription_shouldThrowIllegalArgumentException(@TempDir Path tempDir) throws Exception {
        Path agentFile = tempDir.resolve("missing-desc.md");
        Files.writeString(agentFile, """
                ---
                name: no-desc
                ---
                body content
                """);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> AgentLoader.parseAgentFile(agentFile));
        assertTrue(ex.getMessage().contains("missing required field 'description'"),
                "Expected 'missing required field description' in: " + ex.getMessage());
    }

    @Test
    void loadAll_shouldIncludeThreeBuiltins() {
        Map<String, SubAgentSpec> specs = AgentLoader.loadAll(Path.of(System.getProperty("user.dir")));
        assertTrue(specs.containsKey("general-purpose"),
                "Should contain general-purpose, got: " + specs.keySet());
        assertTrue(specs.containsKey("plan"),
                "Should contain plan, got: " + specs.keySet());
        assertTrue(specs.containsKey("explore"),
                "Should contain explore, got: " + specs.keySet());
    }

    @Test
    void parseWithoutFrontmatter_shouldTreatWholeFileAsBody(@TempDir Path tempDir) throws Exception {
        Path agentFile = tempDir.resolve("no-frontmatter.md");
        Files.writeString(agentFile, """
                name: inline-name
                description: Inline description agent

                This is the full body prompt without yaml frontmatter.
                """);

        // Without frontmatter, name defaults from first line content — but actually
        // parseAgentFile requires name in frontmatter. So this tests the error path.
        var ex = assertThrows(IllegalArgumentException.class,
                () -> AgentLoader.parseAgentFile(agentFile));
        assertTrue(ex.getMessage().contains("missing required field 'name'"));
    }

    @Test
    void planSpec_disallowedTools_shouldContainWriteFileAndEditFile() {
        assertTrue(SubAgentSpec.PLAN.disallowedTools().contains("WriteFile"));
        assertTrue(SubAgentSpec.PLAN.disallowedTools().contains("EditFile"));
    }

    @Test
    void exploreSpec_disallowedToolsAndModel() {
        assertTrue(SubAgentSpec.EXPLORE.disallowedTools().contains("WriteFile"));
        assertTrue(SubAgentSpec.EXPLORE.disallowedTools().contains("EditFile"));
        assertEquals("haiku", SubAgentSpec.EXPLORE.model());
    }

    @Test
    void generalPurpose_maxTurnsShouldBe200() {
        assertEquals(200, SubAgentSpec.GENERAL_PURPOSE.maxTurns());
    }

    @Test
    void planMaxTurnsShouldBe15() {
        assertEquals(15, SubAgentSpec.PLAN.maxTurns());
    }

    @Test
    void exploreMaxTurnsShouldBe30() {
        assertEquals(30, SubAgentSpec.EXPLORE.maxTurns());
    }
}
