package com.licode.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controlled-variable benchmark switch, driven by the {@code LICODE_ABLATE}
 * environment variable (comma-separated module names, case-insensitive).
 *
 * <p>Used by the SWE-bench-Live ablation study to disable exactly one module
 * per run while holding everything else constant. When {@code LICODE_ABLATE}
 * is unset or blank (the default), every module is ON and behaviour is
 * identical to normal operation.
 *
 * <p>Recognized module names:
 * <ul>
 *   <li>{@code compaction} — skip Layer-1 ({@code ToolResultBudget}) and
 *       Layer-2 ({@code ContextCompactor}) context management in the agent loop.</li>
 * </ul>
 *
 * <p>Example: {@code LICODE_ABLATE=compaction java -jar licode.jar --print "..."}.
 */
public final class Ablation {

    private static final Set<String> DISABLED = parse(System.getenv("LICODE_ABLATE"));

    private Ablation() {}

    /** Parse a comma-separated module list into a normalized set. Visible for tests. */
    static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True if {@code module} was named in {@code LICODE_ABLATE} and should be disabled. */
    public static boolean disabled(String module) {
        return DISABLED.contains(module);
    }

    /** Convenience: is Layer-1 + Layer-2 context compression disabled for this run? */
    public static boolean compactionDisabled() {
        return disabled("compaction");
    }
}
