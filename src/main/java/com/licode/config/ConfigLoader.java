package com.licode.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ConfigLoader {

    private static final Set<String> VALID_PROTOCOLS = Set.of("anthropic", "openai", "openai-compat");

    public static AppConfig load() throws ConfigLoadException {
        Path home = Path.of(System.getProperty("user.home"));
        Path cwd = Path.of(System.getProperty("user.dir"));

        List<Path> candidates = List.of(
                home.resolve(".licode").resolve("config.yaml"),
                cwd.resolve(".licode").resolve("config.yaml")
        );

        AppConfig merged = null;
        for (Path p : candidates) {
            if (!Files.exists(p)) continue;
            AppConfig layer = loadSingleFile(p);
            if (merged == null) {
                merged = layer;
            } else {
                mergeConfig(merged, layer);
            }
        }

        if (merged == null) {
            throw new ConfigLoadException(
                    "No config file found. Expected .licode/config.yaml in project or ~/.licode/config.yaml");
        }

        validate(merged);
        return merged;
    }

    public static AppConfig load(String path) throws ConfigLoadException {
        if (path != null && !path.isEmpty()) {
            AppConfig cfg = loadSingleFile(Path.of(path));
            validate(cfg);
            return cfg;
        }
        return load();
    }

    private static AppConfig loadSingleFile(Path configPath) throws ConfigLoadException {
        if (!Files.exists(configPath)) {
            throw new ConfigLoadException("Config file not found: " + configPath);
        }
        String content;
        try {
            content = Files.readString(configPath);
        } catch (IOException e) {
            throw new ConfigLoadException("Failed to read config " + configPath + ": " + e.getMessage());
        }
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(AppConfig.class, loaderOptions);
        constructor.setPropertyUtils(new SnakeCasePropertyUtils());
        Yaml yaml = new Yaml(constructor);
        AppConfig cfg;
        try {
            cfg = yaml.load(content);
        } catch (Exception e) {
            throw new ConfigLoadException("Failed to parse config " + configPath + ": " + e.getMessage());
        }
        if (cfg == null) cfg = new AppConfig();
        return cfg;
    }

    private static void mergeConfig(AppConfig base, AppConfig override) {
        if (override.getProviders() != null && !override.getProviders().isEmpty()) {
            base.setProviders(override.getProviders());
        }
        if (override.getMcpServers() != null && !override.getMcpServers().isEmpty()) {
            base.setMcpServers(override.getMcpServers());
        }
        if (override.getHooks() != null && !override.getHooks().isEmpty()) {
            base.setHooks(override.getHooks());
        }
    }

    private static void validate(AppConfig cfg) throws ConfigLoadException {
        if (cfg.getProviders() == null || cfg.getProviders().isEmpty()) {
            throw new ConfigLoadException("Config must contain at least one provider");
        }
        for (int i = 0; i < cfg.getProviders().size(); i++) {
            ProviderConfig p = cfg.getProviders().get(i);
            List<String> missing = new ArrayList<>();

            if (isBlank(p.getName())) missing.add("name");
            if (isBlank(p.getProtocol())) missing.add("protocol");
            if (isBlank(p.getBaseUrl())) missing.add("base_url");
            if (isBlank(p.getModel())) missing.add("model");

            if (!missing.isEmpty()) {
                throw new ConfigLoadException(
                        "Provider #%d: missing fields: %s".formatted(i + 1, String.join(", ", missing))
                );
            }

            if (!VALID_PROTOCOLS.contains(p.getProtocol())) {
                throw new ConfigLoadException(
                        "Provider #%d: invalid protocol '%s', must be one of: %s"
                                .formatted(i + 1, p.getProtocol(), String.join(", ", VALID_PROTOCOLS))
                );
            }
        }

        // Validate MCP servers
        if (cfg.getMcpServers() != null) {
            for (int i = 0; i < cfg.getMcpServers().size(); i++) {
                McpServerConfig s = cfg.getMcpServers().get(i);
                if (isBlank(s.getName())) {
                    throw new ConfigLoadException(
                            "MCP server #%d: name is required".formatted(i + 1));
                }
                boolean hasCommand = !isBlank(s.getCommand());
                boolean hasUrl = !isBlank(s.getUrl());
                if (hasCommand && hasUrl) {
                    throw new ConfigLoadException(
                            "MCP server '%s': cannot have both command and url".formatted(s.getName()));
                }
                if (!hasCommand && !hasUrl) {
                    throw new ConfigLoadException(
                            "MCP server '%s': must have either command or url".formatted(s.getName()));
                }
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static class SnakeCasePropertyUtils extends PropertyUtils {
        @Override
        public Property getProperty(Class<?> type, String name) {
            String camel = snakeToCamel(name);
            return super.getProperty(type, camel);
        }

        private static String snakeToCamel(String snake) {
            if (!snake.contains("_")) return snake;
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : snake.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    sb.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            return sb.toString();
        }
    }

    public static class ConfigLoadException extends Exception {
        public ConfigLoadException(String message) {
            super(message);
        }
    }
}
