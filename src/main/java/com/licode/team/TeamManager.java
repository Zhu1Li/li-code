package com.licode.team;

import com.licode.agent.Agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TeamManager {

    public enum TeamMode { IN_PROCESS, TMUX }

    public static final String LEAD_NAME = "lead";

    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Path teamsBaseDir;

    public TeamManager() {
        this.teamsBaseDir = Path.of(System.getProperty("user.dir"), ".licode", "teams");
    }

    public TeamManager(Path teamsBaseDir) {
        this.teamsBaseDir = teamsBaseDir;
    }

    public Path getTeamsBaseDir() { return teamsBaseDir; }

    public synchronized Team createTeam(String name) throws IOException {
        if (teams.containsKey(name)) {
            throw new IllegalArgumentException("Team '" + name + "' already exists");
        }
        Path teamDir = teamsBaseDir.resolve(name);
        Files.createDirectories(teamDir);
        Path inboxDir = teamDir.resolve("inboxes");
        FileMailBox mailBox = new FileMailBox(inboxDir);
        SharedTaskStore taskStore = new SharedTaskStore(teamDir);
        ConversationStore convStore = new ConversationStore(teamDir);
        Team team = new Team(name, detectBackend(), mailBox, taskStore, convStore);
        teams.put(name, team);
        return team;
    }

    public synchronized Team getTeam(String name) {
        return teams.get(name);
    }

    public synchronized void deleteTeam(String name) {
        Team team = teams.remove(name);
        if (team != null) {
            team.stopAll();
        }
    }

    public synchronized List<Team> listTeams() {
        return List.copyOf(teams.values());
    }

    public synchronized void closeAll() {
        for (Team team : List.copyOf(teams.values())) {
            team.stopAll();
        }
        teams.clear();
    }

    public static TeamMode detectBackend() {
        if (!System.getenv().getOrDefault("TMUX", "").isEmpty()) {
            return TeamMode.TMUX;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "tmux");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            if (proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) {
                return TeamMode.TMUX;
            }
        } catch (Exception ignored) {
        }
        return TeamMode.IN_PROCESS;
    }

    // -- Team --

    public class Team {
        private final String name;
        private final TeamMode mode;
        private final Map<String, Member> members = new LinkedHashMap<>();
        private final FileMailBox mailBox;
        private final SharedTaskStore sharedTaskStore;
        private final ConversationStore conversationStore;

        Team(String name, TeamMode mode, FileMailBox mailBox,
             SharedTaskStore sharedTaskStore, ConversationStore conversationStore) {
            this.name = name;
            this.mode = mode;
            this.mailBox = mailBox;
            this.sharedTaskStore = sharedTaskStore;
            this.conversationStore = conversationStore;
        }

        public String name() { return name; }
        public TeamMode mode() { return mode; }
        public FileMailBox mailBox() { return mailBox; }
        public SharedTaskStore sharedTaskStore() { return sharedTaskStore; }
        public ConversationStore conversationStore() { return conversationStore; }

        public synchronized Member addMember(String name, Agent agent,
                                              com.licode.conversation.ConversationManager conv) {
            Member member = new Member(name, agent, conv);
            member.active = true;
            members.put(name, member);
            return member;
        }

        public synchronized Member addExternalMember(String name) {
            Member member = new Member(name, null, null);
            member.active = true;
            members.put(name, member);
            return member;
        }

        public synchronized void removeMember(String name) {
            Member m = members.remove(name);
            if (m != null && m.thread != null) {
                m.thread.interrupt();
            }
        }

        public synchronized void stopAll() {
            for (Member m : List.copyOf(members.values())) {
                m.active = false;
                if (m.thread != null) {
                    m.thread.interrupt();
                }
            }
            members.clear();
        }

        public synchronized Member getMember(String name) {
            return members.get(name);
        }

        public synchronized boolean hasMember(String name) {
            return members.containsKey(name);
        }

        public synchronized List<String> memberNames() {
            return List.copyOf(members.keySet());
        }

        public synchronized List<Member> getMembers() {
            return List.copyOf(members.values());
        }

        public void sendMessage(String from, String to, String content) throws IOException {
            mailBox.send(to, new MailMessage(from, to, content));
        }
    }

    // -- Member --

    public static class Member {
        private final String name;
        private final Agent agent;
        private final com.licode.conversation.ConversationManager conv;
        private volatile boolean active;
        private volatile Thread thread;
        private TeammateProgress progress;

        Member(String name, Agent agent, com.licode.conversation.ConversationManager conv) {
            this.name = name;
            this.agent = agent;
            this.conv = conv;
        }

        public String name() { return name; }
        public Agent agent() { return agent; }
        public com.licode.conversation.ConversationManager conv() { return conv; }
        public boolean active() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Thread thread() { return thread; }
        public void setThread(Thread thread) { this.thread = thread; }
        public TeammateProgress progress() { return progress; }
        public void setProgress(TeammateProgress progress) { this.progress = progress; }
    }
}
