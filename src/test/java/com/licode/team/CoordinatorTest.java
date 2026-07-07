package com.licode.team;

import com.licode.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoordinatorTest {

    @Test
    void allowedToolsContainsAllTwelve() {
        assertEquals(12, Coordinator.ALLOWED_TOOLS.size());
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("Agent"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("SendMessage"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("TaskCreate"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("TaskGet"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("TaskList"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("TaskUpdate"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("TeamCreate"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("TeamDelete"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("ReadFile"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("Glob"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("Grep"));
        assertTrue(Coordinator.ALLOWED_TOOLS.contains("Bash"));
    }

    @Test
    void isCoordinatorToolHitsAllowed() {
        assertTrue(Coordinator.isCoordinatorTool("Agent"));
        assertTrue(Coordinator.isCoordinatorTool("Bash"));
        assertTrue(Coordinator.isCoordinatorTool("SendMessage"));
    }

    @Test
    void isCoordinatorToolMissesDisallowed() {
        assertFalse(Coordinator.isCoordinatorTool("Write"));
        assertFalse(Coordinator.isCoordinatorTool("Edit"));
        assertFalse(Coordinator.isCoordinatorTool("Read"));
        assertFalse(Coordinator.isCoordinatorTool("ToolSearch"));
        assertFalse(Coordinator.isCoordinatorTool(""));
        assertFalse(Coordinator.isCoordinatorTool("nonexistent"));
    }

    @Test
    void isCoordinatorEnabledEnvVarOnly() {
        // Default: env var not set → disabled
        assertFalse(Coordinator.isCoordinatorEnabled());
    }

    @Test
    void isCoordinatorEnabledWithConfigFlag() {
        AppConfig config = new AppConfig();
        config.setFeatures(Map.of("COORDINATOR_MODE", true));
        // Config flag true + env var not set → enabled (OR logic)
        assertTrue(Coordinator.isCoordinatorEnabled(config));
    }

    @Test
    void isCoordinatorEnabledWithConfigFlagFalse() {
        AppConfig config = new AppConfig();
        config.setFeatures(Map.of("COORDINATOR_MODE", false));
        // Config flag false + env var not set → disabled
        assertFalse(Coordinator.isCoordinatorEnabled(config));
    }

    @Test
    void isCoordinatorEnabledConfigNull() {
        // config null → falls back to env var check only
        assertFalse(Coordinator.isCoordinatorEnabled(null));
    }

    @Test
    void isCoordinatorEnabledFeaturesNull() {
        AppConfig config = new AppConfig();
        config.setFeatures(null);
        assertFalse(Coordinator.isCoordinatorEnabled(config));
    }
}
