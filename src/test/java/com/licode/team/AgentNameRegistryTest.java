package com.licode.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentNameRegistryTest {

    @Test
    void registerAndResolve() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("alice", "agent-1");
        assertEquals("agent-1", reg.resolve("alice"));
        reg.unregister("alice");
    }

    @Test
    void resolveUnknownReturnsNull() {
        var reg = AgentNameRegistry.getInstance();
        assertNull(reg.resolve("nonexistent"));
    }

    @Test
    void resolveByIdReturnsId() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("alice", "agent-1");
        assertEquals("agent-1", reg.resolve("agent-1"));
        reg.unregister("alice");
    }

    @Test
    void unregisterRemovesEntry() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("alice", "agent-1");
        reg.unregister("alice");
        assertNull(reg.resolve("alice"));
    }

    @Test
    void listAllReturnsAllNames() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("alice", "agent-1");
        reg.register("bob", "agent-2");
        var all = reg.listAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("alice"));
        assertTrue(all.containsKey("bob"));
        reg.unregister("alice");
        reg.unregister("bob");
    }

    @Test
    void registerDuplicateOverwrites() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("alice", "agent-1");
        reg.register("alice", "agent-1-updated");
        assertEquals("agent-1-updated", reg.resolve("alice"));
        reg.unregister("alice");
    }
}
