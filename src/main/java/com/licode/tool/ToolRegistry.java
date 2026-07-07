package com.licode.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class ToolRegistry {

    public static final int MAX_OUTPUT_CHARS = 10_000;

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();
    private final Set<String> discoveredTools = ConcurrentHashMap.newKeySet();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> listTools() {
        return List.copyOf(tools.values());
    }

    public List<Map<String, Object>> toApiSchemas(String protocol) {
        return toApiSchemas(protocol, t -> true);
    }

    public List<Map<String, Object>> toApiSchemas(String protocol, Predicate<Tool> filter) {
        var schemas = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (tool.shouldDefer() && !discoveredTools.contains(tool.name())) continue;
            if (!filter.test(tool)) continue;
            schemas.add(tool.inputSchema());
        }
        return schemas;
    }

    public List<Tool> getDeferredTools() {
        return tools.values().stream().filter(Tool::shouldDefer).toList();
    }

    public List<String> getDeferredToolNames() {
        return tools.values().stream()
                .filter(t -> t.shouldDefer() && !discoveredTools.contains(t.name()))
                .map(Tool::name)
                .toList();
    }

    public List<Tool> getImmediateTools() {
        return tools.values().stream().filter(t -> !t.shouldDefer()).toList();
    }

    public void markDiscovered(String name) {
        discoveredTools.add(name);
    }

    public boolean isDiscovered(String name) {
        return discoveredTools.contains(name);
    }

    public List<Map<String, Object>> searchDeferred(String query, int maxResults, String protocol) {
        String lower = query.toLowerCase();
        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (!tool.shouldDefer()) continue;
            if (tool.name().toLowerCase().contains(lower)
                    || tool.description().toLowerCase().contains(lower)) {
                var base = tool.inputSchema();
                if ("openai".equals(protocol)) {
                    matches.add(Map.of(
                            "type", "function",
                            "name", base.get("name"),
                            "description", base.get("description"),
                            "parameters", base.get("input_schema")
                    ));
                } else {
                    matches.add(base);
                }
                if (matches.size() >= maxResults) break;
            }
        }
        return matches;
    }

    public List<Map<String, Object>> findDeferredByNames(List<String> names, String protocol) {
        var nameSet = new HashSet<String>();
        for (var n : names) nameSet.add(n.toLowerCase());

        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (nameSet.contains(tool.name().toLowerCase())) {
                var base = tool.inputSchema();
                if ("openai".equals(protocol)) {
                    matches.add(Map.of(
                            "type", "function",
                            "name", base.get("name"),
                            "description", base.get("description"),
                            "parameters", base.get("input_schema")
                    ));
                } else {
                    matches.add(base);
                }
            }
        }
        return matches;
    }

    public static ToolRegistry createDefault() {
        var cache = new FileStateCache();
        var readFile = new com.licode.tool.impl.ReadFileTool();
        readFile.setFileStateCache(cache);
        var writeFile = new com.licode.tool.impl.WriteFileTool();
        writeFile.setFileStateCache(cache);
        var editFile = new com.licode.tool.impl.EditFileTool();
        editFile.setFileStateCache(cache);

        var reg = new ToolRegistry();
        reg.register(readFile);
        reg.register(writeFile);
        reg.register(editFile);
        reg.register(new com.licode.tool.impl.BashTool());
        reg.register(new com.licode.tool.impl.GlobTool());
        reg.register(new com.licode.tool.impl.GrepTool());
        reg.register(new com.licode.tool.impl.ToolSearchTool(reg));
        return reg;
    }

    public static String toPrettyJson(Object obj) {
        var sb = new StringBuilder();
        toPrettyJson(sb, obj, 0);
        return sb.toString();
    }

    private static void toPrettyJson(StringBuilder sb, Object obj, int indent) {
        String pad = "  ".repeat(indent);
        String innerPad = "  ".repeat(indent + 1);
        switch (obj) {
            case Map<?, ?> m -> {
                sb.append("{\n");
                var iter = m.entrySet().iterator();
                while (iter.hasNext()) {
                    var e = iter.next();
                    sb.append(innerPad).append('"').append(e.getKey()).append("\": ");
                    toPrettyJson(sb, e.getValue(), indent + 1);
                    if (iter.hasNext()) sb.append(',');
                    sb.append('\n');
                }
                sb.append(pad).append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(", ");
                    toPrettyJson(sb, l.get(i), indent);
                }
                sb.append(']');
            }
            case String s -> sb.append('"').append(s.replace("\"", "\\\"").replace("\n", "\\n")).append('"');
            case null -> sb.append("null");
            default -> sb.append(obj);
        }
    }
}
