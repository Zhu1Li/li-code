package com.licode.worktree;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class WorktreeChanges {

    public record ChangeSummary(int changedFiles, int commits) {}

    private WorktreeChanges() {}

    public static boolean hasChanges(String worktreePath, String headCommit) {
        try {
            String statusOut = runGit(worktreePath, "status", "--porcelain");
            if (statusOut == null || !statusOut.isBlank()) return true;

            String revOut = runGit(worktreePath, "rev-list", "--count", headCommit + "..HEAD");
            if (revOut == null) return true;
            return Integer.parseInt(revOut.strip()) > 0;
        } catch (Exception e) {
            return true;
        }
    }

    public static ChangeSummary countChanges(String worktreePath, String originalHeadCommit) {
        String statusOut = runGit(worktreePath, "status", "--porcelain");
        if (statusOut == null) return null;

        int changedFiles = 0;
        for (String line : statusOut.split("\n")) {
            if (!line.isBlank()) changedFiles++;
        }

        int commits = 0;
        if (originalHeadCommit != null && !originalHeadCommit.isBlank()) {
            String revOut = runGit(worktreePath, "rev-list", "--count", originalHeadCommit + "..HEAD");
            if (revOut == null) return null;
            try {
                commits = Integer.parseInt(revOut.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return new ChangeSummary(changedFiles, commits);
    }

    private static String runGit(String cwd, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(cwd).toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "");
            pb.redirectErrorStream(false);

            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            return proc.exitValue() == 0 ? stdout : null;
        } catch (Exception e) {
            return null;
        }
    }
}
