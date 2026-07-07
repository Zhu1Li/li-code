package com.licode.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public class PromptBuilder {

    public record Section(int priority, String content) {}

    public record EnvironmentContext(
            String workDir, String os, String arch, String shell,
            boolean isGitRepo, String gitBranch, String model, String date) {}

    public record BuildOptions(String customInstructions, String skillSection,
                               String memorySection, EnvironmentContext environmentContext) {}

    private final List<Section> sections = new ArrayList<>();

    public PromptBuilder add(Section section) {
        sections.add(section);
        return this;
    }

    public String build() {
        sections.sort(Comparator.comparingInt(Section::priority));
        var parts = new ArrayList<String>();
        for (var section : sections) {
            String content = section.content().strip();
            if (!content.isEmpty()) {
                parts.add(content);
            }
        }
        return String.join("\n\n", parts);
    }

    // ── Fingerprint for cache invalidation ──────────────────────────

    private String fingerprint;

    public PromptBuilder computeFingerprint(String model, String workDir, BuildOptions opts) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(model.getBytes(StandardCharsets.UTF_8));
            md.update(workDir.getBytes(StandardCharsets.UTF_8));
            if (opts != null && opts.customInstructions() != null) {
                md.update(opts.customInstructions().getBytes(StandardCharsets.UTF_8));
            }
            this.fingerprint = HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            this.fingerprint = model + ":" + workDir;
        }
        return this;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public boolean isStale(String model, String workDir, BuildOptions opts) {
        if (fingerprint == null) return true;
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(model.getBytes(StandardCharsets.UTF_8));
            md.update(workDir.getBytes(StandardCharsets.UTF_8));
            if (opts != null && opts.customInstructions() != null) {
                md.update(opts.customInstructions().getBytes(StandardCharsets.UTF_8));
            }
            return !HexFormat.of().formatHex(md.digest()).equals(fingerprint);
        } catch (NoSuchAlgorithmException e) {
            return !(model + ":" + workDir).equals(fingerprint);
        }
    }

    // ── Environment detection ───────────────────────────────────────

    public static EnvironmentContext detectEnvironment(String model) {
        String workDir = System.getProperty("user.dir");
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String shell = defaultShell();
        String date = LocalDate.now().toString();
        boolean isGitRepo = false;
        String gitBranch = "";

        try {
            Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--is-inside-work-tree")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if ("true".equals(out)) {
                isGitRepo = true;
                Process b = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                        .redirectErrorStream(true)
                        .start();
                gitBranch = new String(b.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                b.waitFor();
            }
        } catch (Exception ignored) {
            // Non-git repo or git not available — silently degrade
        }

        return new EnvironmentContext(workDir, os, arch, shell, isGitRepo, gitBranch, model, date);
    }

    private static String defaultShell() {
        // Report the shell the Bash tool will actually invoke, so the system prompt
        // doesn't tell the model "cmd.exe" while commands really run under Git Bash.
        return com.licode.tool.impl.BashTool.activeShellName();
    }

    // ── Convenience static builder ──────────────────────────────────

    public static String buildSystemPrompt(BuildOptions opts) {
        var builder = new PromptBuilder();

        // 7 fixed sections
        builder.add(PromptSections.identitySection());
        builder.add(PromptSections.systemSection());
        builder.add(PromptSections.doingTasksSection());
        builder.add(PromptSections.executingActionsSection());
        builder.add(PromptSections.usingToolsSection());
        builder.add(PromptSections.toneStyleSection());
        builder.add(PromptSections.textOutputSection());

        if (opts != null) {
            // Environment: priority 70, between tools (40-60) and custom instructions (80)
            if (opts.environmentContext() != null) {
                builder.add(new Section(70,
                        PromptSections.renderEnvironment(opts.environmentContext())));
            }
            if (opts.customInstructions() != null && !opts.customInstructions().isBlank()) {
                builder.add(new Section(80, opts.customInstructions().strip()));
            }
            if (opts.skillSection() != null && !opts.skillSection().isBlank()) {
                builder.add(new Section(90, opts.skillSection().strip()));
            }
            if (opts.memorySection() != null && !opts.memorySection().isBlank()) {
                builder.add(new Section(95, opts.memorySection().strip()));
            }
        }

        return builder.build();
    }
}
