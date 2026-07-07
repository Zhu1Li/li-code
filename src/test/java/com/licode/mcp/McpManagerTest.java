package com.licode.mcp;

import com.licode.config.McpServerConfig;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpManagerTest {

    // ── sanitizeName ──────────────────────────────────────────────

    @Test
    void testSanitizeNameNormal() {
        assertEquals("github", McpManager.sanitizeName("github"));
        assertEquals("context7", McpManager.sanitizeName("context7"));
        assertEquals("my_server", McpManager.sanitizeName("my-server"));
    }

    @Test
    void testSanitizeNameDots() {
        assertEquals("com_example", McpManager.sanitizeName("com.example"));
    }

    @Test
    void testSanitizeNameSpecialChars() {
        assertEquals("hello_world_", McpManager.sanitizeName("hello-world!"));
        assertEquals("a_b_c", McpManager.sanitizeName("a@b#c"));
    }

    @Test
    void testSanitizeNameUnderscorePassthrough() {
        assertEquals("abc_123", McpManager.sanitizeName("abc_123"));
    }

    // ── resolveEnvVars ────────────────────────────────────────────

    @Test
    void testResolveEnvVarsExisting() {
        String home = System.getenv("USERPROFILE") != null
                ? System.getenv("USERPROFILE")
                : System.getenv("HOME");
        assertNotNull(home);
        String result = McpManager.resolveEnvVars("${USERPROFILE}");
        if (System.getenv("USERPROFILE") != null) {
            assertEquals(System.getenv("USERPROFILE"), result);
        }
    }

    @Test
    void testResolveEnvVarsMissing() {
        assertEquals("${NONEXISTENT_XYZ_999}", McpManager.resolveEnvVars("${NONEXISTENT_XYZ_999}"));
    }

    @Test
    void testResolveEnvVarsMultiple() {
        String home = System.getenv("USERPROFILE") != null
                ? System.getenv("USERPROFILE")
                : System.getenv("HOME");
        if (System.getenv("USERPROFILE") != null) {
            String result = McpManager.resolveEnvVars("${USERPROFILE}/app");
            assertEquals(home + "/app", result);
        }
    }

    @Test
    void testResolveEnvVarsPlainText() {
        assertEquals("no-placeholder", McpManager.resolveEnvVars("no-placeholder"));
    }

    @Test
    void testResolveEnvVarsNull() {
        assertNull(McpManager.resolveEnvVars(null));
        assertEquals("", McpManager.resolveEnvVars(""));
    }

    // ── McpServerConfig ───────────────────────────────────────────

    @Test
    void testMcpServerConfigIsStdio() {
        var cfg = new McpServerConfig();
        cfg.setCommand("npx");
        assertTrue(cfg.isStdio());
        assertFalse(cfg.isHttp());
    }

    @Test
    void testMcpServerConfigIsHttp() {
        var cfg = new McpServerConfig();
        cfg.setUrl("https://api.example.com/mcp");
        assertFalse(cfg.isStdio());
        assertTrue(cfg.isHttp());
    }

    @Test
    void testMcpServerConfigBlankCommandNotStdio() {
        var cfg = new McpServerConfig();
        cfg.setCommand("  ");
        assertFalse(cfg.isStdio());
    }

    @Test
    void testMcpServerConfigNeither() {
        var cfg = new McpServerConfig();
        assertFalse(cfg.isStdio());
        assertFalse(cfg.isHttp());
    }

    // ── McpManager constructor ────────────────────────────────────

    @Test
    void testMcpManagerNullSafe() {
        var mgr = new McpManager(null);
        var result = mgr.connectAll();
        assertTrue(result.tools().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testMcpManagerNeitherCommandNorUrl() {
        var cfg = new McpServerConfig();
        cfg.setName("bad-server");
        var mgr = new McpManager(List.of(cfg));
        var result = mgr.connectAll();
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("neither command nor url"));
        assertTrue(result.errors().get(0).contains("bad-server"));
    }

    @Test
    void testMcpManagerSkipsNullName() {
        var cfg = new McpServerConfig();
        cfg.setName(null);
        var mgr = new McpManager(List.of(cfg));
        var result = mgr.connectAll();
        assertTrue(result.tools().isEmpty());
    }

    // ── ConnectResult ─────────────────────────────────────────────

    @Test
    void testConnectResultImmutability() {
        var result = new McpManager.ConnectResult(List.of(), List.of(), List.of());
        assertNotNull(result.tools());
        assertNotNull(result.servers());
        assertNotNull(result.errors());
    }

    // ── ServerInfo ────────────────────────────────────────────────

    @Test
    void testServerInfoRecord() {
        var info = new McpManager.ServerInfo("test", "instructions here");
        assertEquals("test", info.name());
        assertEquals("instructions here", info.instructions());
    }

    // ── Tool.shouldDefer + ToolRegistry deferred ──────────────────

    @Test
    void testShouldDeferDefaultFalse() {
        Tool tool = new Tool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "desc"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("name", "test"); }
            @Override public ToolCategory category() { return ToolCategory.READ; }
            @Override public com.licode.tool.ToolResult execute(Map<String, Object> args) {
                return com.licode.tool.ToolResult.success("ok");
            }
        };
        assertFalse(tool.shouldDefer());
    }

    @Test
    void testToolRegistryDeferredTools() {
        var reg = new ToolRegistry();
        var deferredTool = new Tool() {
            @Override public String name() { return "deferred_one"; }
            @Override public String description() { return "A deferred tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("name", "deferred_one"); }
            @Override public ToolCategory category() { return ToolCategory.COMMAND; }
            @Override public boolean shouldDefer() { return true; }
            @Override public com.licode.tool.ToolResult execute(Map<String, Object> args) {
                return com.licode.tool.ToolResult.success("ok");
            }
        };
        reg.register(deferredTool);

        assertEquals(1, reg.getDeferredTools().size());
        assertEquals("deferred_one", reg.getDeferredTools().get(0).name());
        assertEquals(0, reg.getImmediateTools().size());
    }

    @Test
    void testToApiSchemasDeferredOnlyNameDescription() {
        var reg = new ToolRegistry();
        var deferredTool = new Tool() {
            @Override public String name() { return "mcp__srv__search"; }
            @Override public String description() { return "Search things"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("name", "mcp__srv__search", "description", "Search things",
                        "input_schema", Map.of("type", "object"));
            }
            @Override public ToolCategory category() { return ToolCategory.COMMAND; }
            @Override public boolean shouldDefer() { return true; }
            @Override public com.licode.tool.ToolResult execute(Map<String, Object> args) {
                return com.licode.tool.ToolResult.success("ok");
            }
        };
        reg.register(deferredTool);

        // Undiscovered deferred tools are hidden from toApiSchemas
        var schemas = reg.toApiSchemas("anthropic");
        assertEquals(0, schemas.size());

        // After discovery: full schema appears
        reg.markDiscovered("mcp__srv__search");
        var discoveredSchemas = reg.toApiSchemas("anthropic");
        assertEquals(1, discoveredSchemas.size());
        var schema = discoveredSchemas.get(0);
        assertEquals("mcp__srv__search", schema.get("name"));
        assertEquals("Search things", schema.get("description"));
        assertTrue(schema.containsKey("input_schema"));
    }

    @Test
    void testToApiSchemasImmediateHasFullSchema() {
        var reg = new ToolRegistry();
        var immTool = new Tool() {
            @Override public String name() { return "ReadFile"; }
            @Override public String description() { return "Read a file"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("name", "ReadFile", "description", "Read a file",
                        "input_schema", Map.of("type", "object", "properties", Map.of("file_path", Map.of("type", "string"))));
            }
            @Override public ToolCategory category() { return ToolCategory.READ; }
            @Override public com.licode.tool.ToolResult execute(Map<String, Object> args) {
                return com.licode.tool.ToolResult.success("ok");
            }
        };
        reg.register(immTool);

        var schemas = reg.toApiSchemas("anthropic");
        assertEquals(1, schemas.size());
        assertTrue(schemas.get(0).containsKey("input_schema"));
    }

    @Test
    void testToApiSchemasOpenAIFormatDeferred() {
        var reg = new ToolRegistry();
        var deferredTool = new Tool() {
            @Override public String name() { return "mcp__srv__tool"; }
            @Override public String description() { return "Does things"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("name", "mcp__srv__tool", "description", "Does things",
                        "input_schema", Map.of("type", "object"));
            }
            @Override public ToolCategory category() { return ToolCategory.COMMAND; }
            @Override public boolean shouldDefer() { return true; }
            @Override public com.licode.tool.ToolResult execute(Map<String, Object> args) {
                return com.licode.tool.ToolResult.success("ok");
            }
        };
        reg.register(deferredTool);

        // Undiscovered deferred tools are hidden
        var schemas = reg.toApiSchemas("openai");
        assertEquals(0, schemas.size());

        // After discovery: schema appears in flat format (clients handle their own wrapping)
        reg.markDiscovered("mcp__srv__tool");
        var discoveredSchemas = reg.toApiSchemas("openai");
        assertEquals(1, discoveredSchemas.size());
        var openaiSchema = discoveredSchemas.get(0);
        assertEquals("mcp__srv__tool", openaiSchema.get("name"));
    }

    // ── McpManager shutdown idempotency ───────────────────────────

    @Test
    void testShutdownIdempotent() {
        var cfg = new McpServerConfig();
        cfg.setName("dummy");
        cfg.setCommand("echo");
        cfg.setArgs(List.of("hello"));
        var mgr = new McpManager(List.of(cfg));

        // Call shutdown twice; should not throw
        mgr.shutdown();
        mgr.shutdown();
    }

    // ── ConfigLoader MCP validation (unit test via AppConfig) ─────

    @Test
    void testMcpServerConfigName() {
        var cfg = new McpServerConfig();
        cfg.setName("context7");
        cfg.setCommand("npx");
        cfg.setArgs(List.of("-y", "@upstash/context7-mcp"));
        assertEquals("context7", cfg.getName());
        assertEquals("npx", cfg.getCommand());
        assertEquals(2, cfg.getArgs().size());
    }

    @Test
    void testMcpServerConfigUrlWithHeaders() {
        var cfg = new McpServerConfig();
        cfg.setName("remote");
        cfg.setUrl("https://api.example.com/mcp");
        cfg.setHeaders(Map.of("Authorization", "Bearer ${TOKEN}"));
        assertEquals("remote", cfg.getName());
        assertEquals("https://api.example.com/mcp", cfg.getUrl());
        assertEquals("Bearer ${TOKEN}", cfg.getHeaders().get("Authorization"));
    }
}
