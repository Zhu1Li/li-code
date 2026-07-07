package com.licode.team;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentNameRegistry {

    private static final AgentNameRegistry INSTANCE = new AgentNameRegistry();

    private final Map<String, String> nameToId = new LinkedHashMap<>();

    private AgentNameRegistry() {}

    public static AgentNameRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void register(String name, String agentId) {
        nameToId.put(name, agentId);
    }

    public synchronized String resolve(String nameOrId) {
        String id = nameToId.get(nameOrId);
        if (id != null) return id;
        if (nameToId.containsValue(nameOrId)) return nameOrId;
        return null;
    }

    public synchronized void unregister(String name) {
        nameToId.remove(name);
    }

    public synchronized Map<String, String> listAll() {
        return new LinkedHashMap<>(nameToId);
    }
}
