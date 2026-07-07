package com.licode.worktree;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class WorktreeManager {

    public record WorktreeInfo(String path, String branch, Instant createdAt) {}

    private final String projectRoot;
    private final List<String> symlinkDirs;
    private final int staleCutoffHours;

    private final Map<String, WorktreeInfo> worktrees = new LinkedHashMap<>();

    public WorktreeManager(String projectRoot, List<String> symlinkDirs, int staleCutoffHours) {
        this.projectRoot = projectRoot;
        this.symlinkDirs = symlinkDirs != null ? symlinkDirs : List.of();
        this.staleCutoffHours = staleCutoffHours > 0 ? staleCutoffHours : 24;
    }

    public String getProjectRoot() { return projectRoot; }
    public List<String> getSymlinkDirs() { return symlinkDirs; }
    public int getStaleCutoffHours() { return staleCutoffHours; }

    public synchronized WorktreeInfo create(String branch, Path targetDir) throws Exception {
        Path wtDir = targetDir != null
                ? targetDir
                : Path.of(projectRoot, ".licode", "worktrees", branch);

        runGit(projectRoot, "git", "worktree", "add", "-B", branch, wtDir.toString());

        PostCreationSetup.perform(projectRoot, wtDir.toString(), symlinkDirs);

        var info = new WorktreeInfo(wtDir.toString(), branch, Instant.now());
        worktrees.put(branch, info);
        return info;
    }

    public synchronized void remove(String branch) throws Exception {
        WorktreeInfo info = worktrees.get(branch);
        if (info == null) {
            // Fallback: the worktree may have been created by a previous process;
            // reconstruct the path and attempt git removal directly.
            String fallbackPath = Path.of(projectRoot, ".licode", "worktrees", branch).toString();
            runGit(projectRoot, "git", "worktree", "remove", fallbackPath, "--force");
            return;
        }
        runGit(projectRoot, "git", "worktree", "remove", info.path(), "--force");
        worktrees.remove(branch);
    }

    public synchronized List<WorktreeInfo> list() {
        try {
            String output = runGit(projectRoot, "git", "worktree", "list", "--porcelain");
            List<WorktreeInfo> result = parsePorcelain(output);
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception ignored) {
            // fall through to in-memory map
        }
        return new ArrayList<>(worktrees.values());
    }

    public synchronized Optional<WorktreeInfo> get(String branch) {
        return Optional.ofNullable(worktrees.get(branch));
    }

    public synchronized int cleanupStale(int cutoffHours) {
        int hours = cutoffHours > 0 ? cutoffHours : staleCutoffHours;
        Instant cutoff = Instant.now().minusSeconds((long) hours * 3600);
        int removed = 0;

        var it = worktrees.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            WorktreeInfo info = entry.getValue();
            if (info.createdAt().isBefore(cutoff)) {
                try {
                    runGit(projectRoot, "git", "worktree", "remove", info.path(), "--force");
                    it.remove();
                    removed++;
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
        return removed;
    }

    public synchronized void removeAll() {
        var it = worktrees.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            try {
                runGit(projectRoot, "git", "worktree", "remove", entry.getValue().path(), "--force");
            } catch (Exception ignored) {
                // best-effort
            }
            it.remove();
        }
    }

    public static String detectChanges(String worktreePath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--stat");
        pb.directory(Path.of(worktreePath).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git diff timed out in " + worktreePath);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git diff failed: " + output);
        }
        return output.strip();
    }

    private static String runGit(String workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(workDir).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException(String.join(" ", command) + ": " + output);
        }
        return output;
    }

    private static List<WorktreeInfo> parsePorcelain(String output) {
        List<WorktreeInfo> result = new ArrayList<>();
        String currentPath = null;
        String currentBranch = null;

        for (String line : output.split("\n")) {
            if (line.startsWith("worktree ")) {
                currentPath = line.substring("worktree ".length()).strip();
            } else if (line.startsWith("branch ")) {
                String ref = line.substring("branch ".length()).strip();
                if (ref.startsWith("refs/heads/")) {
                    currentBranch = ref.substring("refs/heads/".length());
                } else {
                    currentBranch = ref;
                }
            } else if (line.isBlank()) {
                if (currentPath != null && currentBranch != null) {
                    result.add(new WorktreeInfo(currentPath, currentBranch, Instant.now()));
                }
                currentPath = null;
                currentBranch = null;
            }
        }
        if (currentPath != null && currentBranch != null) {
            result.add(new WorktreeInfo(currentPath, currentBranch, Instant.now()));
        }
        return result;
    }
}
