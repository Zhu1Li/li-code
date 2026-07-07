package com.licode.team;

import com.licode.config.AppConfig;

import java.util.Map;
import java.util.Set;

public final class Coordinator {

    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "Agent", "SendMessage", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "TeamCreate", "TeamDelete", "ReadFile", "Glob", "Grep", "Bash"
    );

    private Coordinator() {}

    public static boolean isCoordinatorEnabled() {
        return "true".equalsIgnoreCase(System.getenv("LI_CODE_COORDINATOR_MODE"));
    }

    public static boolean isCoordinatorEnabled(AppConfig config) {
        if (config != null) {
            Map<String, Object> features = config.getFeatures();
            if (features != null && Boolean.TRUE.equals(features.get("COORDINATOR_MODE"))) {
                return true;
            }
        }
        return isCoordinatorEnabled();
    }

    public static boolean isCoordinatorTool(String name) {
        return ALLOWED_TOOLS.contains(name);
    }
}
