package com.licode.command;

import java.util.List;

public record Command(
        String name,
        String description,
        List<String> aliases,
        CommandType type,
        boolean hidden) {

    public enum CommandType {
        /** Synchronous handler returns text output; displayed as system message. */
        LOCAL,
        /** TUI side effect via CommandContext Runnable; no text output. */
        LOCAL_UI,
        /** Handler returns a preset prompt string; injected into conversation, triggers agent streaming. */
        PROMPT,
        /** Fork skill: runs the skill body in an isolated sub-agent (own conversation, own turn
         *  budget, shared workdir) whose progress streams to the UI. Dispatched via
         *  {@code LiRuntime.askForkSkill}, not injected into the main conversation. */
        SKILL_FORK
    }

    public boolean matches(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase();
        if (name.toLowerCase().equals(lower)) return true;
        if (aliases != null) {
            for (var alias : aliases) {
                if (alias.toLowerCase().equals(lower)) return true;
            }
        }
        return false;
    }
}
