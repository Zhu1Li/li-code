package com.licode.team;

import com.licode.agent.Agent;
import com.licode.conversation.ConversationManager;
import com.licode.llm.LlmClient;
import com.licode.tool.ToolRegistry;

import java.io.File;
import java.io.IOException;

public final class SpawnDispatcher {

    public record SpawnConfig(
            TeamManager.Team team,
            String memberName,
            String task,
            String addendum,
            LlmClient client,
            ToolRegistry parentRegistry,
            String protocol,
            com.licode.config.ProviderConfig providerConfig,
            String workdir
    ) {}

    public record SpawnResult(TeamManager.TeamMode mode, String paneId) {}

    private SpawnDispatcher() {}

    public static SpawnResult spawnTeammate(SpawnConfig config) throws IOException {
        TeamManager.TeamMode mode = config.team().mode();

        return switch (mode) {
            case IN_PROCESS -> {
                ConversationManager conv = new ConversationManager();
                Agent agent = new Agent(config.client(), config.parentRegistry(), config.protocol());
                if (config.workdir() != null && !config.workdir().isBlank()) {
                    agent.setWorkDir(config.workdir());
                }
                TeamManager.Member member = config.team().addMember(config.memberName(), agent, conv);
                Thread vt = Thread.startVirtualThread(() ->
                        TeammateRunner.runInProcessTeammate(
                                config.team(), member, config.task(), config.addendum()));
                member.setThread(vt);
                yield new SpawnResult(TeamManager.TeamMode.IN_PROCESS, null);
            }
            case TMUX -> {
                config.team().sendMessage(TeamManager.LEAD_NAME, config.memberName(), config.task());
                String cli = buildTeammateCLI(config.team().name(), config.memberName(), config.workdir());
                String paneId = TmuxBackend.spawnTmuxTeammate(
                        config.team().name(), config.memberName(), cli);
                config.team().addExternalMember(config.memberName());
                yield new SpawnResult(TeamManager.TeamMode.TMUX, paneId);
            }
        };
    }

    public static String buildTeammateCLI(String teamName, String memberName, String workdir) {
        String exe = resolveExecutablePath();
        String wd = (workdir != null && !workdir.isBlank())
                ? workdir : System.getProperty("user.dir");
        return "cd " + shellQuote(wd) + " && "
                + shellQuote(exe) + " --teammate --team-name "
                + shellQuote(teamName) + " --agent-name " + shellQuote(memberName);
    }

    static String resolveExecutablePath() {
        try {
            String cmd = ProcessHandle.current().info().command().orElse(null);
            if (cmd != null) return cmd;
        } catch (Exception ignored) {
        }
        try {
            File jarFile = new File(SpawnDispatcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return "java -jar " + jarFile.getAbsolutePath();
        } catch (Exception ignored) {
        }
        return "java -jar li-code.jar";
    }

    public static String shellQuote(String s) {
        if (s == null || s.isEmpty()) return "''";
        if (s.matches("^[a-zA-Z0-9_./\\-]+$")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
