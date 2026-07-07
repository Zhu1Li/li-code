package com.licode.team;

import com.licode.agent.AgentEvent;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class TeammateRunner {

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";
    public static final long IDLE_POLL_MS = 500;

    private TeammateRunner() {}

    public static void runInProcessTeammate(TeamManager.Team team, TeamManager.Member member,
                                             String initialPrompt, String addendum) {
        var progress = new TeammateProgress(team.name(), member.name());
        member.setProgress(progress);

        try {
            if (addendum != null && !addendum.isBlank()) {
                member.conv().addSystemReminder(addendum);
            }

            injectPendingMessages(team, member.name(), member.conv());

            member.conv().addUserMessage(initialPrompt);

            if (member.agent() == null) {
                member.setActive(false);
                progress.setStatus(TeammateProgress.Status.FAILED);
                return;
            }

            BlockingQueue<AgentEvent> agentQueue = member.agent().run(member.conv());

            drainAgentEvents(agentQueue, progress);

            progress.setStatus(TeammateProgress.Status.IDLE);
            team.sendMessage(member.name(), LEAD_NAME, createIdleNotification(member.name(), "task completed"));
            team.conversationStore().save(member.name(), member.conv());

            while (!Thread.currentThread().isInterrupted() && member.active()) {
                List<MailMessage> unread;
                try {
                    unread = team.mailBox().readUnread(member.name());
                } catch (IOException e) {
                    break;
                }

                boolean hasNewMessage = false;
                for (MailMessage msg : unread) {
                    if (isShutdownRequest(msg.text())) {
                        team.mailBox().markAllRead(member.name());
                        progress.setStatus(TeammateProgress.Status.STOPPED);
                        member.setActive(false);
                        return;
                    }
                    if (msg.isApprovalRequest()) {
                        team.mailBox().markAllRead(member.name());
                        String response = waitForApprovalResponse(team, member.name());
                        member.conv().addSystemReminder("Approval response: " + response);
                        hasNewMessage = true;
                        break;
                    }
                    member.conv().addUserMessage(
                            "Message from " + msg.from() + ":\n" + msg.text());
                    hasNewMessage = true;
                }

                if (hasNewMessage) {
                    team.mailBox().markAllRead(member.name());
                    progress.setStatus(TeammateProgress.Status.RUNNING);
                    agentQueue = member.agent().run(member.conv());
                    drainAgentEvents(agentQueue, progress);
                    progress.setStatus(TeammateProgress.Status.IDLE);
                    team.sendMessage(member.name(), LEAD_NAME,
                            createIdleNotification(member.name(), "follow-up completed"));
                    team.conversationStore().save(member.name(), member.conv());
                }

                try {
                    Thread.sleep(IDLE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            progress.setStatus(TeammateProgress.Status.FAILED);
            try {
                team.sendMessage(member.name(), LEAD_NAME,
                        MailMessage.error(member.name(), e.getMessage()).text());
            } catch (IOException ignored) {
            }
        } finally {
            member.setActive(false);
        }
    }

    private static String waitForApprovalResponse(TeamManager.Team team, String memberName) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<MailMessage> unread = team.mailBox().readUnread(memberName);
                for (MailMessage msg : unread) {
                    if (msg.isApprovalResponse()) {
                        team.mailBox().markAllRead(memberName);
                        return msg.text();
                    }
                }
            } catch (IOException ignored) {
            }
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Interrupted while waiting for approval";
            }
        }
        return "Approval timeout (120s)";
    }

    public static List<String> drainLeadMailbox(TeamManager teamManager) {
        if (teamManager == null) return List.of();
        var notes = new ArrayList<String>();
        for (var team : teamManager.listTeams()) {
            try {
                List<MailMessage> unread = team.mailBox().readUnread(LEAD_NAME);
                if (unread.isEmpty()) continue;
                var sb = new StringBuilder();
                sb.append("<team-notification team=\"").append(team.name()).append("\">\n");
                for (MailMessage msg : unread) {
                    sb.append("from=").append(msg.from()).append(": ")
                            .append(msg.text()).append("\n");
                }
                sb.append("</team-notification>");
                notes.add(sb.toString());
                team.mailBox().markAllRead(LEAD_NAME);
            } catch (IOException ignored) {
            }
        }
        return notes;
    }

    public static String buildTeammateAddendum(String teamName, String memberName,
                                                 List<String> otherMembers) {
        var sb = new StringBuilder();
        sb.append("You are a member of team \"").append(teamName).append("\".\n");
        sb.append("Your name is \"").append(memberName).append("\".\n");
        if (!otherMembers.isEmpty()) {
            sb.append("Other team members: ");
            sb.append(String.join(", ", otherMembers.stream()
                    .filter(n -> !n.equals(memberName)).toList()));
            sb.append("\n");
        }
        sb.append("\nTeam rules:\n");
        sb.append("1. Communicate with teammates ONLY through the SendMessage tool. ")
                .append("Text replies in your final output are NOT visible to them.\n");
        sb.append("2. When you finish your task (stop calling tools), ")
                .append("an idle notification will be sent to the lead automatically.\n");
        sb.append("3. Stay focused on your assigned task scope.\n");
        return sb.toString();
    }

    static void injectPendingMessages(TeamManager.Team team, String memberName,
                                       com.licode.conversation.ConversationManager conv) throws IOException {
        List<MailMessage> unread = team.mailBox().readUnread(memberName);
        if (unread.isEmpty()) return;
        var sb = new StringBuilder("You have new messages:\n\n");
        for (MailMessage msg : unread) {
            sb.append("From ").append(msg.from()).append(":\n");
            if (msg.type() != null) {
                sb.append("[").append(msg.type()).append("] ");
            }
            sb.append(msg.text()).append("\n\n");
        }
        conv.addSystemReminder(sb.toString());
        team.mailBox().markAllRead(memberName);
    }

    public static boolean isShutdownRequest(String text) {
        return text != null && text.strip().startsWith(SHUTDOWN_PREFIX);
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] " + memberName + ": " + reason + " (at " + Instant.now() + ")";
    }

    private static void drainAgentEvents(BlockingQueue<AgentEvent> queue,
                                           TeammateProgress progress) {
        try {
            while (true) {
                AgentEvent event = queue.poll(120, TimeUnit.SECONDS);
                if (event == null) break;
                switch (event) {
                    case AgentEvent.ToolUseEvent tue -> {
                        progress.incrementToolCount();
                        progress.setLastActivity(tue.toolName());
                    }
                    case AgentEvent.ToolResultEvent tre -> {
                        progress.setLastActivity("result: " + tre.toolName());
                    }
                    case AgentEvent.LoopComplete lc -> {
                        progress.setInputTokens(lc.totalInputTokens());
                        progress.setOutputTokens(lc.totalOutputTokens());
                        progress.addTokens(lc.totalInputTokens() + lc.totalOutputTokens());
                        return;
                    }
                    case AgentEvent.ErrorEvent ee -> {
                        progress.setStatus(TeammateProgress.Status.FAILED);
                        return;
                    }
                    default -> {}
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
