package com.licode.subagent;

import java.util.List;

public record SubAgentSpec(
        String name,
        String description,
        List<String> tools,
        List<String> disallowedTools,
        String systemPrompt,
        int maxTurns,
        String model
) {

    public SubAgentSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (description == null) description = "";
        if (tools == null) tools = List.of();
        if (disallowedTools == null) disallowedTools = List.of();
        if (systemPrompt == null || systemPrompt.isBlank()) systemPrompt = null;
        if (model == null || model.isBlank()) model = null;
    }

    private static final String PLAN_AGENT_SYSTEM_PROMPT = """
            You are a software architect and planning specialist.

            === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
            You are STRICTLY PROHIBITED from creating, modifying, or deleting any files.
            Your role is EXCLUSIVELY to explore code and design implementation plans.

            ## Your Process

            1. **Understand Requirements**: Analyze the user's request carefully.

            2. **Explore Thoroughly**:
               - Read files with ReadFile to understand current architecture
               - Use Grep to find patterns, function definitions, and references
               - Use Glob to discover file structure
               - Use Bash ONLY for read-only operations (ls, find, grep, cat, head, tail)
               - NEVER use Bash for: mkdir, touch, rm, cp, mv, git add/commit, npm install

            3. **Design Solution**:
               - Create a concrete implementation approach
               - Consider trade-offs and explain your reasoning
               - Follow existing patterns in the codebase

            4. **Detail the Plan**:
               - Provide step-by-step implementation strategy
               - Identify file dependencies and sequencing
               - Anticipate potential challenges

            ## Required Output
            End your response with:

            ### Critical Files for Implementation
            List the most critical files for implementing this change:
            - path/to/file1 -- reason
            - path/to/file2 -- reason""";

    public static final SubAgentSpec GENERAL_PURPOSE = new SubAgentSpec(
            "general-purpose",
            "General-purpose agent for research and multi-step tasks",
            List.of(),
            List.of(),
            null,
            200,
            null
    );

    public static final SubAgentSpec PLAN = new SubAgentSpec(
            "plan",
            "Software architect for designing implementation plans. Returns step-by-step plans, "
                    + "identifies critical files, and considers architectural trade-offs.",
            List.of(),
            List.of("EditFile", "WriteFile"),
            PLAN_AGENT_SYSTEM_PROMPT,
            15,
            null
    );

    public static final SubAgentSpec EXPLORE = new SubAgentSpec(
            "explore",
            "Fast read-only search agent for locating code",
            List.of(),
            List.of("EditFile", "WriteFile"),
            null,
            30,
            "haiku"
    );
}
