package com.licode.gui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists session titles to .licode/sessions/titles.json.
 * sessionId → title, read-only from SessionManager's sessions dir.
 */
class SessionTitleStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path file;

    SessionTitleStore(Path sessionsDir) {
        this.file = sessionsDir.resolve("titles.json");
        try { Files.createDirectories(sessionsDir); } catch (IOException ignored) {}
    }

    Map<String, String> loadAll() {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            String raw = Files.readString(file);
            if (raw.isBlank()) return new LinkedHashMap<>();
            return MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    synchronized void save(String sessionId, String title) {
        Map<String, String> all = loadAll();
        all.put(sessionId, title);
        try {
            Files.writeString(file, MAPPER.writeValueAsString(all));
        } catch (IOException ignored) {}
    }

    synchronized void remove(String sessionId) {
        Map<String, String> all = loadAll();
        all.remove(sessionId);
        try {
            Files.writeString(file, MAPPER.writeValueAsString(all));
        } catch (IOException ignored) {}
    }
}
