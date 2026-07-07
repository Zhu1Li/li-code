package com.licode.subagent;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolRegistry;
import com.licode.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolFilterTest {

    private ToolRegistry source;

    @BeforeEach
    void setUp() {
        source = new ToolRegistry();
        // Register a variety of tools
        source.register(stub("Agent"));
        source.register(stub("ReadFile"));
        source.register(stub("Grep"));
        source.register(stub("Glob"));
        source.register(stub("Bash"));
        source.register(stub("WriteFile"));
        source.register(stub("EditFile"));
        source.register(stub("ToolSearch"));
        source.register(stub("AskUserQuestion"));
        source.register(stub("TaskOutput"));
        source.register(stub("ExitPlanMode"));
        source.register(stub("EnterPlanMode"));
        source.register(stub("TaskStop"));
        source.register(stub("mcp__server_resource"));
    }

    @Test
    void agentTool_shouldBeBlockedFromSubAgent() {
        var filtered = ToolFilter.filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE);
        var names = filtered.listTools().stream().map(Tool::name).toList();
        assertFalse(names.contains("Agent"), "Agent tool must not be available to sub-agents");
    }

    @Test
    void mcpTools_shouldPassThrough() {
        var filtered = ToolFilter.filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE);
        var names = filtered.listTools().stream().map(Tool::name).toList();
        assertTrue(names.contains("mcp__server_resource"), "MCP tools should pass through");
    }

    @Test
    void alwaysDisallowed_toolsShouldBeRemoved() {
        var filtered = ToolFilter.filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE);
        var names = filtered.listTools().stream().map(Tool::name).toList();
        assertFalse(names.contains("Agent"), "Agent should be disallowed");
        assertFalse(names.contains("AskUserQuestion"), "AskUserQuestion should be disallowed");
        assertFalse(names.contains("TaskOutput"), "TaskOutput should be disallowed");
        assertFalse(names.contains("ExitPlanMode"), "ExitPlanMode should be disallowed");
        assertFalse(names.contains("EnterPlanMode"), "EnterPlanMode should be disallowed");
        assertFalse(names.contains("TaskStop"), "TaskStop should be disallowed");
    }

    @Test
    void asyncMode_shouldRestrictToAsyncAllowList() {
        var filtered = ToolFilter.filterForAgent(source, SubAgentSpec.GENERAL_PURPOSE, true);
        var names = filtered.listTools().stream().map(Tool::name).toList();
        // Async mode blocks everything except the 8 allow-listed tools + MCP
        assertTrue(names.contains("ReadFile"));
        assertTrue(names.contains("Grep"));
        assertTrue(names.contains("Glob"));
        assertTrue(names.contains("Bash"));
        assertTrue(names.contains("WriteFile"));
        assertTrue(names.contains("EditFile"));
        assertTrue(names.contains("ToolSearch"));
        assertFalse(names.contains("AskUserQuestion")); // already blocked by layer 2
    }

    @Test
    void specWhitelist_shouldOnlyRetainWhitelistedTools() {
        var spec = new SubAgentSpec("limited", "test", List.of("ReadFile", "Grep"), List.of(), null, 0, null);
        var filtered = ToolFilter.filterForAgent(source, spec);
        var names = filtered.listTools().stream().map(Tool::name).toList();
        assertTrue(names.contains("ReadFile"));
        assertTrue(names.contains("Grep"));
        assertFalse(names.contains("Glob"));
        assertFalse(names.contains("Bash"));
    }

    @Test
    void wildcardWhitelist_shouldNotRestrict() {
        var spec = new SubAgentSpec("wild", "test", List.of("*"), List.of(), null, 0, null);
        var filtered = ToolFilter.filterForAgent(source, spec);
        var names = filtered.listTools().stream().map(Tool::name).toList();
        // Should contain all non-disallowed tools
        assertTrue(names.contains("ReadFile"));
        assertTrue(names.contains("Grep"));
    }

    @Test
    void specDisallowedTools_shouldRemoveExtraTools() {
        var spec = new SubAgentSpec("no-bash", "test", List.of(),
                List.of("Bash", "Glob"), null, 0, null);
        var filtered = ToolFilter.filterForAgent(source, spec);
        var names = filtered.listTools().stream().map(Tool::name).toList();
        assertFalse(names.contains("Bash"));
        assertFalse(names.contains("Glob"));
        assertTrue(names.contains("ReadFile"));
    }

    /** Minimal tool stub for filter testing. */
    private static Tool stub(String name) {
        return new Tool() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return "stub"; }
            @Override
            public ToolCategory category() { return ToolCategory.READ; }
            @Override
            public Map<String, Object> inputSchema() { return Map.of(); }
            @Override
            public ToolResult execute(Map<String, Object> args) { return ToolResult.success("ok"); }
        };
    }
}
