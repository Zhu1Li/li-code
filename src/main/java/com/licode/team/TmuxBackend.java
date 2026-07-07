package com.licode.team;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class TmuxBackend {

    private TmuxBackend() {}

    public static String spawnTmuxTeammate(String teamName, String memberName,
                                            String cliCommand) throws IOException {
        String paneName = teamName + "-" + memberName;
        ProcessBuilder pb = new ProcessBuilder(
                "tmux", "new-window", "-d", "-n", paneName, cliCommand);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try {
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("tmux new-window timed out for " + paneName);
            }
            if (proc.exitValue() != 0) {
                String err = new String(proc.getInputStream().readAllBytes());
                throw new IOException("Failed to spawn tmux window for " + paneName + ": " + err);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Interrupted while spawning tmux window for " + paneName);
        }
        return paneName;
    }

    public static void stopTmuxTeammate(String paneName) {
        try {
            new ProcessBuilder("tmux", "send-keys", "-t", paneName, "C-c")
                    .start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(200);
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("tmux", "kill-window", "-t", paneName)
                    .start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
