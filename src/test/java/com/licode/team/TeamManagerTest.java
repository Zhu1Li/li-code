package com.licode.team;

import com.licode.agent.Agent;
import com.licode.conversation.ConversationManager;
import com.licode.llm.LlmClient;
import com.licode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TeamManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createTeam() throws IOException {
        var tm = new TeamManager(tempDir);
        var team = tm.createTeam("test-team");
        assertNotNull(team);
        assertEquals("test-team", team.name());
        assertEquals(TeamManager.TeamMode.IN_PROCESS, team.mode());
        assertTrue(tm.listTeams().contains(team));
    }

    @Test
    void createDuplicateTeamThrows() throws IOException {
        var tm = new TeamManager(tempDir);
        tm.createTeam("test-team");
        assertThrows(IllegalArgumentException.class, () -> tm.createTeam("test-team"));
    }

    @Test
    void getTeam() throws IOException {
        var tm = new TeamManager(tempDir);
        tm.createTeam("test-team");
        var team = tm.getTeam("test-team");
        assertNotNull(team);
        assertEquals("test-team", team.name());
    }

    @Test
    void getNonexistentTeamReturnsNull() {
        var tm = new TeamManager(tempDir);
        assertNull(tm.getTeam("nonexistent"));
    }

    @Test
    void deleteTeam() throws IOException {
        var tm = new TeamManager(tempDir);
        tm.createTeam("test-team");
        tm.deleteTeam("test-team");
        assertNull(tm.getTeam("test-team"));
        assertTrue(tm.listTeams().isEmpty());
    }

    @Test
    void deleteNonexistentTeamDoesNotThrow() {
        var tm = new TeamManager(tempDir);
        assertDoesNotThrow(() -> tm.deleteTeam("nonexistent"));
    }

    @Test
    void addAndGetMember() throws IOException {
        var tm = new TeamManager(tempDir);
        var team = tm.createTeam("test-team");
        var conv = new ConversationManager();
        // Agent requires non-null client and registry; use null for unit test
        // TeamManager.Member can exist without a real agent
        var member = team.addMember("worker1", null, conv);
        assertNotNull(member);
        assertEquals("worker1", member.name());
        assertTrue(member.active());
        assertEquals(conv, member.conv());
    }

    @Test
    void removeMember() throws IOException {
        var tm = new TeamManager(tempDir);
        var team = tm.createTeam("test-team");
        var conv = new ConversationManager();
        team.addMember("worker1", null, conv);
        assertTrue(team.hasMember("worker1"));

        team.removeMember("worker1");
        assertFalse(team.hasMember("worker1"));
    }

    @Test
    void stopAllDeactivatesAllMembers() throws IOException {
        var tm = new TeamManager(tempDir);
        var team = tm.createTeam("test-team");
        var conv = new ConversationManager();
        team.addMember("worker1", null, conv);
        team.addMember("worker2", null, conv);

        team.stopAll();
        assertTrue(team.getMembers().isEmpty());
    }

    @Test
    void sendMessageRoutesToRecipient() throws IOException {
        var tm = new TeamManager(tempDir);
        var team = tm.createTeam("test-team");
        team.sendMessage("lead", "worker1", "Hello worker1");
        var unread = team.mailBox().readUnread("worker1");
        assertEquals(1, unread.size());
        assertEquals("lead", unread.get(0).from());
        assertEquals("Hello worker1", unread.get(0).text());
    }

    @Test
    void memberNamesReturnsAll() throws IOException {
        var tm = new TeamManager(tempDir);
        var team = tm.createTeam("test-team");
        var conv = new ConversationManager();
        team.addMember("alice", null, conv);
        team.addMember("bob", null, conv);
        var names = team.memberNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("alice"));
        assertTrue(names.contains("bob"));
    }

    @Test
    void closeAllStopsAllTeams() throws IOException {
        var tm = new TeamManager(tempDir);
        tm.createTeam("team1");
        tm.createTeam("team2");
        assertEquals(2, tm.listTeams().size());

        tm.closeAll();
        assertTrue(tm.listTeams().isEmpty());
    }

    @Test
    void detectBackendDefaultsToInProcess() {
        assertEquals(TeamManager.TeamMode.IN_PROCESS, TeamManager.detectBackend());
    }

    @Test
    void externalMemberHasNullAgent() throws IOException {
        var tm = new TeamManager(tempDir);
        var team = tm.createTeam("test-team");
        var member = team.addExternalMember("remote1");
        assertNotNull(member);
        assertEquals("remote1", member.name());
        assertNull(member.agent());
        assertNull(member.conv());
        assertTrue(member.active());
    }
}
