package com.licode.app;

import com.licode.agent.Agent;
import com.licode.conversation.ConversationManager;
import com.licode.config.AppConfig;
import com.licode.config.ConfigLoader;
import com.licode.config.McpServerConfig;
import com.licode.llm.LlmClient;
import com.licode.gui.LiCodeApp;
import com.licode.permission.PermissionMode;
import com.licode.runtime.LiRuntime;
import com.licode.team.MailMessage;
import com.licode.team.TeamManager;
import com.licode.team.TeammateRunner;
import com.licode.tool.ToolRegistry;
import com.licode.tui.LiCodeModel;
import com.williamcallahan.tui4j.compat.bubbletea.Program;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LiCode {

    public static void main(String[] args) {
        // Parse CLI arguments
        String configPath = null;
        String resumeId = null;
        boolean isTeammate = false;
        boolean useGui = false;
        boolean useWeb = false;
        int webPort = 8420;
        String teamName = null;
        String agentName = null;
        String printInstruction = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--resume" -> {
                    if (i + 1 < args.length) {
                        resumeId = args[++i];
                    } else {
                        resumeId = "latest";
                    }
                }
                case "--print" -> {
                    if (i + 1 < args.length) {
                        printInstruction = args[++i];
                    } else {
                        System.err.println("Usage: --print \"<instruction>\" [config]");
                        System.exit(1);
                    }
                }
                case "--gui" -> useGui = true;
                case "--web" -> useWeb = true;
                case "--port" -> {
                    if (i + 1 < args.length) webPort = Integer.parseInt(args[++i]);
                }
                case "--teammate" -> isTeammate = true;
                case "--team-name" -> {
                    if (i + 1 < args.length) teamName = args[++i];
                }
                case "--agent-name" -> {
                    if (i + 1 < args.length) agentName = args[++i];
                }
                default -> configPath = args[i];
            }
        }

        if (isTeammate) {
            if (teamName == null || agentName == null) {
                System.err.println("Usage: --teammate --team-name <name> --agent-name <name> [config]");
                System.exit(1);
            }
            runTeammate(teamName, agentName, configPath);
            return;
        }

        // Headless batch mode: run one instruction to completion, print result, exit.
        if (printInstruction != null) {
            runHeadless(printInstruction, configPath);
            return;
        }

        var config = loadConfig(configPath);

        // Web mode (local browser UI over SSE)
        if (useWeb) {
            if (config.getProviders() == null || config.getProviders().isEmpty()) {
                System.err.println("No providers configured. Add a provider to ~/.licode/config.yaml");
                System.exit(1);
            }
            var provider = config.getProviders().get(0);
            try {
                com.licode.web.WebServer.start(config, provider, webPort);
                Thread.currentThread().join(); // keep the process alive
            } catch (Exception e) {
                System.err.println("Web server error: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        // GUI mode
        if (useGui) {
            LiCodeApp.setConfig(config);
            if (resumeId != null) {
                LiCodeApp.setResumeId(resumeId);
            }
            javafx.application.Application.launch(LiCodeApp.class, args);
            return;
        }

        // Create TUI model (handles auto-select for single provider, selection UI for multiple)
        LiCodeModel model = new LiCodeModel(config.getProviders(), config.getMcpServers());
        if (config.getHooks() != null && !config.getHooks().isEmpty()) {
            model.setHookConfigs(config.getHooks());
        }
        if (resumeId != null) {
            model.setResumeSessionId(resumeId);
        }

        // Start TUI
        System.out.print("\033[?25l"); // hide cursor

        try {
            new Program(model).withMouseAllMotion().run();
        } finally {
            System.out.print("\033[?25h"); // show cursor
            System.out.flush();
        }
    }

    private static void runTeammate(String teamName, String agentName, String configPath) {
        try {
            AppConfig config = ConfigLoader.load(configPath);
            if (config.getProviders().isEmpty()) {
                System.err.println("No providers configured");
                System.exit(1);
            }
            var providerConfig = config.getProviders().get(0);

            Path workDir = Path.of(System.getProperty("user.dir"));
            String systemPrompt = "You are an AI coding assistant working as part of a team. "
                    + "Complete assigned tasks and report back to the lead via SendMessage.";

            var client = LlmClient.create(providerConfig, systemPrompt);
            var registry = ToolRegistry.createDefault();

            Path teamsBaseDir = workDir.resolve(".licode").resolve("teams");
            var teamManager = new TeamManager(teamsBaseDir);

            TeamManager.Team team = teamManager.getTeam(teamName);
            if (team == null) {
                System.err.println("Team not found: " + teamName
                        + ". The lead must create it first with TeamCreate.");
                System.exit(1);
            }

            // Register SendMessage tool for team communication
            registry.register(new com.licode.team.TeamTools.SendMessageTool(teamManager, agentName));

            // Load or create conversation
            ConversationManager conv;
            if (team.conversationStore().exists(agentName)) {
                conv = team.conversationStore().load(agentName);
                conv.addSystemReminder("Resumed teammate session for team '" + teamName + "' as '" + agentName + "'.");
            } else {
                conv = new ConversationManager();
            }

            var agent = new Agent(client, registry, providerConfig.getProtocol());
            agent.setWorkDir(workDir.toString());
            agent.setContextWindow(providerConfig.resolvedContextWindow());
            agent.setMaxOutput(providerConfig.resolvedMaxOutputTokens());

            TeamManager.Member member = team.addMember(agentName, agent, conv);

            List<String> otherMembers = team.memberNames().stream()
                    .filter(n -> !n.equals(agentName))
                    .toList();
            String addendum = TeammateRunner.buildTeammateAddendum(teamName, agentName, otherMembers);

            // Read initial task from mailbox (placed there by the lead before spawning)
            // Mark as read so injectPendingMessages won't duplicate it
            String initialTask = "You are a member of team '" + teamName + "'. "
                    + "Check your inbox for the assigned task and complete it.";
            List<MailMessage> unread = team.mailBox().readUnread(agentName);
            for (MailMessage msg : unread) {
                if (msg.from().equals(TeamManager.LEAD_NAME)) {
                    initialTask = msg.text();
                    break;
                }
            }
            team.mailBox().markAllRead(agentName);

            TeammateRunner.runInProcessTeammate(team, member, initialTask, addendum);

            // Cleanup
            team.removeMember(agentName);
            System.exit(0);
        } catch (Exception e) {
            System.err.println("Teammate error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Headless batch mode ({@code --print}). Runs one instruction to completion
     * with no TUI and no human approval (container / CI / Harbor benchmark).
     * Mirrors the full runtime assembly of {@link com.licode.web.WebServer#start}
     * so MCP / Skills / sub-agents / hooks all behave as in interactive mode.
     * Prints the final assistant text to stdout; logs tool activity + the
     * trajectory path to stderr; exits 0 on success, 1 on error/timeout.
     */
    private static void runHeadless(String instruction, String configPath) {
        var config = loadConfig(configPath);
        if (config.getProviders() == null || config.getProviders().isEmpty()) {
            System.err.println("No providers configured. Add a provider to ~/.licode/config.yaml");
            System.exit(1);
        }
        var provider = config.getProviders().get(0);

        var registry = ToolRegistry.createDefault();
        LiRuntime runtime = LiRuntime.create(provider, registry);

        List<McpServerConfig> mcpServers = config.getMcpServers();
        if (mcpServers != null && !mcpServers.isEmpty()) {
            runtime.connectMcpServers(mcpServers, status -> {});
        }
        Path workDir = Path.of(System.getProperty("user.dir"));
        runtime.initSubAgentSystem(workDir, config.getProviders());
        if (config.getHooks() != null && !config.getHooks().isEmpty()) {
            runtime.setHookConfigs(config.getHooks());
        }

        // No human to approve tool calls in headless mode: auto-allow everything.
        // (PermissionChecker L1b dangerous-command + L2 path-sandbox still apply.)
        runtime.setPermissionMode(PermissionMode.BYPASS);

        CountDownLatch done = new CountDownLatch(1);
        PrintCallback cb = new PrintCallback(done);

        int exitCode = 0;
        try {
            runtime.ask(instruction, cb);
            // Hard wall-clock cap so a hung task can't block the harness forever.
            boolean finished = done.await(30, TimeUnit.MINUTES);
            if (!finished) {
                System.err.println("[licode] timeout: agent did not finish within 30 minutes");
                exitCode = 1;
            } else if (cb.errored()) {
                exitCode = 1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitCode = 1;
        } catch (Exception e) {
            System.err.println("[licode] headless error: " + e.getMessage());
            exitCode = 1;
        }

        // The internal streaming thread persists the session RIGHT AFTER firing
        // onComplete (which released our latch), so wait for the trajectory to
        // flush before we exit — otherwise System.exit() truncates it.
        String sessionId = runtime.getCurrentSessionId();
        if (sessionId != null) {
            awaitSessionPersisted(workDir.resolve(".licode/sessions").resolve(sessionId + ".jsonl"));
        }

        // Final assistant text is the answer → stdout. Trajectory path → stderr.
        String answer = cb.finalText().strip();
        if (!answer.isEmpty()) {
            System.out.println(answer);
        }
        if (sessionId != null) {
            System.err.println("[licode] trajectory: .licode/sessions/" + sessionId + ".jsonl");
        }

        runtime.shutdown();
        System.exit(exitCode);
    }

    /** Wait (up to ~5s) for the session JSONL to be flushed by the streaming thread. */
    private static void awaitSessionPersisted(Path trajectory) {
        for (int i = 0; i < 50; i++) {
            try {
                if (java.nio.file.Files.exists(trajectory) && java.nio.file.Files.size(trajectory) > 0) {
                    return;
                }
            } catch (java.io.IOException ignored) {
                // fall through and retry
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static AppConfig loadConfig(String configPath) {
        try {
            return ConfigLoader.load(configPath);
        } catch (ConfigLoader.ConfigLoadException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }
    }
}
