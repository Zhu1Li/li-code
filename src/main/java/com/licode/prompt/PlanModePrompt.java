package com.licode.prompt;

public final class PlanModePrompt {

    private PlanModePrompt() {}

    public static final int REMINDER_INTERVAL = 5;

    public static final String PLAN_MODE_FULL_REMINDER = """
            Plan-only mode is active. The user wants to review and approve a plan before any code changes \
            are made.

            Work in this mode:
            1. Explore the codebase to understand the current architecture and relevant files.
            2. Design an implementation approach with specific file paths, function signatures, and \
            architectural decisions.
            3. Present your plan to the user for approval. Do NOT make any file changes until the user \
            explicitly approves the plan.

            Write tools (WriteFile, EditFile) and Bash are blocked in this mode. Use Read, Glob, and Grep \
            to explore. If you need to demonstrate code, show it inline in your text response.

            When the user approves, they will exit plan mode. You will then receive a re-entry reminder with \
            the approved plan context.""".stripIndent();

    public static final String PLAN_MODE_SPARSE_REMINDER =
            "Plan-only mode — explore and design only, no file changes.";

    public static final String PLAN_MODE_REENTRY_REMINDER = """
            Plan mode re-entry. The user has approved a plan and exited plan mode. You should now implement \
            the approved plan.

            Refer back to the plan discussion above for the specific file paths, design decisions, and \
            implementation steps that were agreed upon. Work through the plan methodically.""".stripIndent();

    public static final String PLAN_MODE_EXIT_REMINDER = """
            Plan mode has been exited. Write tools and Bash are now available. You may now implement the \
            approved changes.""".stripIndent();

    /**
     * Builds the plan-mode reminder for a given iteration.
     *
     * @param iteration  current agent loop iteration (1-based)
     * @param planExists whether a plan file already exists on disk (re-entry scenario)
     * @return the reminder string to inject via addSystemReminder
     */
    public static String buildReminder(int iteration, boolean planExists) {
        if (iteration == 1) {
            return PLAN_MODE_FULL_REMINDER;
        }
        if ((iteration - 1) % REMINDER_INTERVAL == 0) {
            return PLAN_MODE_FULL_REMINDER;
        }
        return PLAN_MODE_SPARSE_REMINDER;
    }
}
