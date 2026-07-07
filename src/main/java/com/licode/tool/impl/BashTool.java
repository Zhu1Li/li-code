package com.licode.tool.impl;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {

    private static final int MAX_TIMEOUT = 600;

    private static final String DESCRIPTION = """
            Execute a shell command and return stdout and stderr.

            IMPORTANT: Avoid using this tool to run cat, head, tail, sed, awk, or echo commands. \
            Instead use the dedicated ReadFile, EditFile, or WriteFile tools which provide a better experience.

            Usage notes:
            - The working directory persists between commands, but shell state does not.
            - Always quote file paths containing spaces with double quotes.
            - Try to maintain your current working directory using absolute paths; avoid cd unless the user explicitly requests it.
            - Optional timeout in seconds (max 600). Default is 120s.
            - When issuing multiple independent commands, make separate parallel tool calls instead of chaining with &&.
            - Use && to chain sequential dependent commands. Use ; only when you don't care if earlier commands fail.
            - DO NOT use newlines to separate commands.

            Git Safety Protocol:
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., clean -f, branch -D) unless the user explicitly requests it.
            - NEVER skip hooks (--no-verify) unless the user explicitly requests it.
            - Prefer creating a new commit rather than amending an existing one.
            - Before running destructive operations, consider safer alternatives.

            Avoid unnecessary sleep commands. Do not retry failing commands in a sleep loop — diagnose the root cause instead.
            When using find, search from "." or a specific path, not "/" — scanning the full filesystem is too expensive.""";

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "Shell command to execute"),
                                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = ReadFileTool.stringArg(args, "command", "");
        if (command.isEmpty()) {
            return ToolResult.error("Error: command is required");
        }

        int timeout = ReadFileTool.intArg(args, "timeout", 120);
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }

        try {
            ProcessBuilder pb = buildProcess(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read stdout and stderr concurrently
            var stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
            var stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Error: command timed out after " + timeout + "s");
            }

            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            int exitCode = process.exitValue();

            var sb = new StringBuilder();
            sb.append("$ ").append(command).append('\n');
            if (!stdout.isEmpty()) {
                sb.append(stdout);
                if (!stdout.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            if (!stderr.isEmpty()) {
                sb.append("STDERR: ").append(stderr);
                if (!stderr.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            sb.append("(exit code ").append(exitCode).append(')');

            String output = sb.toString();
            if (output.length() > 100_000) {
                output = output.substring(0, 100_000) + "\n... output truncated";
            }

            return new ToolResult(output, exitCode != 0);

        } catch (IOException e) {
            return ToolResult.error("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: command interrupted");
        } catch (Exception e) {
            return ToolResult.error("Error: " + e.getMessage());
        }
    }

    private static volatile Boolean bashAvailable;

    private static ProcessBuilder buildProcess(String command) {
        if (isWindows() && !isBashAvailable()) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("bash", "-c", command);
    }

    /** The shell the Bash tool actually invokes on this machine: "bash" or "cmd.exe". */
    public static String activeShellName() {
        return (isWindows() && !isBashAvailable()) ? "cmd.exe" : "bash";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isBashAvailable() {
        Boolean available = bashAvailable;
        if (available != null) return available;
        try {
            new ProcessBuilder("bash", "--version").start().destroy();
            bashAvailable = true;
        } catch (IOException e) {
            bashAvailable = false;
        }
        return bashAvailable;
    }

    private static String readStream(InputStream stream) {
        try {
            return new String(stream.readAllBytes());
        } catch (IOException e) {
            return "";
        }
    }
}
