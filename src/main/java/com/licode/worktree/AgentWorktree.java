package com.licode.worktree;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class AgentWorktree {

    private static final Logger log = Logger.getLogger(AgentWorktree.class.getName());

    public record Result(String worktreePath, String worktreeBranch, String headCommit, String gitRoot) {}

    private AgentWorktree() {}

    public static Result create(String slug, String repoRoot, List<String> symlinkDirs) throws Exception {
        SlugValidator.validate(slug);
        Path wtPath = Path.of(repoRoot, ".licode", "worktrees", SlugValidator.flatten(slug));
        String branch = SlugValidator.branchName(slug);

        if (Files.isDirectory(wtPath)) {
            Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now()));
            String head = readHead(wtPath.toString());
            return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
        }

        Files.createDirectories(wtPath.getParent());

        ProcessBuilder pb = new ProcessBuilder("git", "worktree", "add", "-B", branch, wtPath.toString(), "HEAD");
        pb.directory(Path.of(repoRoot).toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new IOException("Failed to create agent worktree: " + output);
        }

        PostCreationSetup.perform(repoRoot, wtPath.toString(), symlinkDirs);

        String head = readHead(wtPath.toString());
        return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
    }

    public static boolean remove(String worktreePath, String worktreeBranch, String gitRoot) {
        if (gitRoot == null || gitRoot.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove", "--force", worktreePath);
            pb.directory(Path.of(gitRoot).toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            proc.waitFor(30, TimeUnit.SECONDS);
            if (proc.exitValue() != 0) return false;

            if (worktreeBranch != null && !worktreeBranch.isBlank()) {
                Thread.sleep(100);
                ProcessBuilder delBranch = new ProcessBuilder("git", "branch", "-D", worktreeBranch);
                delBranch.directory(Path.of(gitRoot).toFile());
                delBranch.redirectErrorStream(true);
                Process branchProc = delBranch.start();
                branchProc.getInputStream().readAllBytes();
                branchProc.waitFor(30, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.fine("Failed to remove agent worktree: " + e.getMessage());
            return false;
        }
    }

    public static String buildNotice(String parentCwd, String worktreeCwd) {
        return "You've inherited the conversation context above from a parent agent working in %s. "
                .formatted(parentCwd)
                + "You are operating in an isolated git worktree at %s — same repository, same relative "
                .formatted(worktreeCwd)
                + "file structure, separate working copy. Paths in the inherited context refer to the "
                + "parent's working directory; translate them to your worktree root. Re-read files before "
                + "editing if the parent may have modified them since they appear in the context. Your "
                + "changes stay in this worktree and will not affect the parent's files.";
    }

    private static String readHead(String worktreePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(Path.of(worktreePath).toFile());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            try (InputStream in = proc.getInputStream()) {
                boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    return null;
                }
                String out = new String(in.readAllBytes()).strip();
                return proc.exitValue() == 0 ? out : null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
