package com.licode.mcp;

import com.licode.config.McpServerConfig;
import com.licode.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class McpManager {

    static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");
    static final Pattern ENV_VAR = Pattern.compile("\\$\\{(.+?)\\}");

    private final Map<String, McpServerConfig> configs = new LinkedHashMap<>();
    final Map<String, io.modelcontextprotocol.client.McpSyncClient> clients = new LinkedHashMap<>();

    public record ServerInfo(String name, String instructions) {}
    public record ConnectResult(List<Tool> tools, List<ServerInfo> servers, List<String> errors) {}

    public McpManager(List<McpServerConfig> configs) {
        if (configs != null) {
            for (var cfg : configs) {
                if (cfg.getName() != null) {
                    this.configs.put(cfg.getName(), cfg);
                }
            }
        }
    }

    public int getServerCount() { return configs.size(); }

    public List<String> getServerNames() { return List.copyOf(configs.keySet()); }

    // ── Shared utilities ──────────────────────────────────────────

    public static String sanitizeName(String name) {
        return NON_ALNUM.matcher(name).replaceAll("_");
    }

    public static String resolveEnvVars(String value) {
        if (value == null || value.isEmpty()) return value;
        var m = ENV_VAR.matcher(value);
        var sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String resolved = System.getenv(varName);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    resolved != null ? resolved : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── connectAll ────────────────────────────────────────────────

    public ConnectResult connectAll() {
        var tools = new ArrayList<Tool>();
        var servers = new ArrayList<ServerInfo>();
        var errors = new ArrayList<String>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            McpServerConfig cfg = entry.getValue();
//            System.err.println("[LiCode MCP] Connecting to '" + name + "'...");
            try {
                io.modelcontextprotocol.client.McpSyncClient client;
                if (cfg.isStdio()) {
//                    System.err.println("[LiCode MCP]   Transport: stdio (command=" + cfg.getCommand() + ")");
                    client = connectStdio(cfg);
                } else if (cfg.isHttp()) {
//                    System.err.println("[LiCode MCP]   Transport: HTTP (url=" + cfg.getUrl() + ")");
                    client = connectHttp(cfg);
                } else {
                    errors.add("MCP server '" + name + "': neither command nor url configured");
                    continue;
                }
                clients.put(name, client);

//                System.err.println("[LiCode MCP]   Initializing...");
                var initResult = client.initialize();
                String instructions = initResult != null ? initResult.instructions() : null;
                servers.add(new ServerInfo(name, instructions != null ? instructions : ""));

//                System.err.println("[LiCode MCP]   Listing tools...");
                var listResult = client.listTools();
                if (listResult != null && listResult.tools() != null) {
//                    System.err.println("[LiCode MCP]   Found " + listResult.tools().size() + " tools");
                    for (var toolDef : listResult.tools()) {
                        tools.add(new McpToolWrapper(name, client, toolDef));
                    }
                }
            } catch (Exception e) {
                errors.add("MCP server '" + name + "': " + e.getMessage());
            }
        }
        return new ConnectResult(List.copyOf(tools), List.copyOf(servers), List.copyOf(errors));
    }

    // ── Transport factories ───────────────────────────────────────

    private io.modelcontextprotocol.client.McpSyncClient connectStdio(McpServerConfig cfg) {
        String command = cfg.getCommand();
        List<String> args = cfg.getArgs();

        // On Windows, .cmd scripts need to run via cmd.exe /c
        if (isWindows() && needsCmdWrapper(command)) {
            var wrappedArgs = new ArrayList<String>();
            wrappedArgs.add("/c");
            wrappedArgs.add(command);
            if (args != null) {
                wrappedArgs.addAll(args);
            }
            command = "cmd.exe";
            args = wrappedArgs;
        }

        var paramsBuilder = io.modelcontextprotocol.client.transport.ServerParameters
                .builder(command);
        if (args != null) {
            paramsBuilder.args(args.toArray(new String[0]));
        }
        if (cfg.getEnv() != null && !cfg.getEnv().isEmpty()) {
            var resolvedEnv = new java.util.HashMap<String, String>();
            for (var e : cfg.getEnv().entrySet()) {
                resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
            }
            paramsBuilder.env(resolvedEnv);
        }
        var params = paramsBuilder.build();

        var transport = new io.modelcontextprotocol.client.transport.StdioClientTransport(
                params, io.modelcontextprotocol.json.McpJsonDefaults.getMapper());
        return io.modelcontextprotocol.client.McpClient.sync(transport)
                .requestTimeout(java.time.Duration.ofSeconds(60))
                .initializationTimeout(java.time.Duration.ofSeconds(120))
                .build();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean needsCmdWrapper(String command) {
        // Absolute paths to .exe/.cmd/.bat don't need wrapping
        if (command.contains("/") || command.contains("\\")) return false;
        String lower = command.toLowerCase();
        if (lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat")) return false;
        // Simple command names like "npx", "node", "python" need wrapping on Windows
        return true;
    }

    private io.modelcontextprotocol.client.McpSyncClient connectHttp(McpServerConfig cfg) {
        var builder = io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
                .builder(cfg.getUrl());

        if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
            builder.httpRequestCustomizer((requestBuilder, method, uri, body, ctx) -> {
                for (var e : cfg.getHeaders().entrySet()) {
                    requestBuilder.header(e.getKey(), resolveEnvVars(e.getValue()));
                }
            });
        }

        var transport = builder.build();
        return io.modelcontextprotocol.client.McpClient.sync(transport)
                .requestTimeout(java.time.Duration.ofSeconds(60))
                .initializationTimeout(java.time.Duration.ofSeconds(120))
                .build();
    }

    // ── registerAllTools ──────────────────────────────────────────

    public List<String> registerAllTools(com.licode.tool.ToolRegistry registry) {
        var result = connectAll();
        for (var tool : result.tools()) {
            registry.register(tool);
        }
        return result.errors();
    }

    // ── shutdown ──────────────────────────────────────────────────

    public void shutdown() {
        for (var client : clients.values()) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
    }
}
