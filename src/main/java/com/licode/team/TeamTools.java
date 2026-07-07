package com.licode.team;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TeamTools {

    private TeamTools() {}

    // -- SendMessageTool --

    public static class SendMessageTool implements Tool {

        private final TeamManager teamManager;
        private final String senderName;

        public SendMessageTool(TeamManager teamManager, String senderName) {
            this.teamManager = teamManager;
            this.senderName = senderName;
        }

        @Override
        public String name() { return "SendMessage"; }

        @Override
        public String description() {
            return "Send a point-to-point message to another team member. "
                    + "Messages are delivered via the team mailbox and read by the recipient on their next poll cycle.";
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("to", Map.of(
                    "type", "string",
                    "description", "The recipient member name. Use \"lead\" to send to the team lead."
            ));
            properties.put("content", Map.of(
                    "type", "string",
                    "description", "The message content to send."
            ));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", name());
            schema.put("description", description());
            schema.put("input_schema", Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", List.of("to", "content")
            ));
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String to = getStringArg(args, "to");
            if (to == null || to.isBlank()) {
                return ToolResult.error("'to' is required");
            }
            String content = getStringArg(args, "content");
            if (content == null || content.isBlank()) {
                return ToolResult.error("'content' is required");
            }

            for (var team : teamManager.listTeams()) {
                if (team.hasMember(to) || TeamManager.LEAD_NAME.equals(to)) {
                    try {
                        team.sendMessage(senderName, to, content);
                        return ToolResult.success("Message sent to '" + to + "' in team '" + team.name() + "'.");
                    } catch (IOException e) {
                        return ToolResult.error("Failed to send message: " + e.getMessage());
                    }
                }
                if (team.hasMember(senderName)) {
                    try {
                        team.sendMessage(senderName, to, content);
                        return ToolResult.success("Message sent to '" + to + "'.");
                    } catch (IOException e) {
                        return ToolResult.error("Failed to send message: " + e.getMessage());
                    }
                }
            }
            return ToolResult.error("recipient '" + to + "' not found in any team");
        }
    }

    // -- TeamCreateTool --

    public static class TeamCreateTool implements Tool {

        private final TeamManager teamManager;

        public TeamCreateTool(TeamManager teamManager) {
            this.teamManager = teamManager;
        }

        @Override
        public String name() { return "TeamCreate"; }

        @Override
        public String description() {
            return "Create a new agent team. A team is a long-lived group of agents that can collaborate "
                    + "via shared tasks and point-to-point messaging. Use Agent tool with team_name to add members.";
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("team_name", Map.of(
                    "type", "string",
                    "description", "The name for the new team. Must be unique; a numeric suffix is appended if the name is taken."
            ));
            properties.put("description", Map.of(
                    "type", "string",
                    "description", "Optional description of the team's purpose."
            ));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", name());
            schema.put("description", description());
            schema.put("input_schema", Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", List.of("team_name")
            ));
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String teamName = getStringArg(args, "team_name");
            if (teamName == null || teamName.isBlank()) {
                return ToolResult.error("'team_name' is required");
            }

            String original = teamName;
            int suffix = 1;
            while (teamManager.getTeam(teamName) != null) {
                suffix++;
                teamName = original + "-" + suffix;
            }

            try {
                TeamManager.Team team = teamManager.createTeam(teamName);
                TeamManager.TeamMode mode = team.mode();
                return ToolResult.success(
                        "Team \"" + teamName + "\" created (mode: " + mode + "). "
                                + "Use Agent tool with team_name=\"" + teamName + "\" to add teammates.");
            } catch (Exception e) {
                return ToolResult.error("Failed to create team: " + e.getMessage());
            }
        }
    }

    // -- TeamDeleteTool --

    public static class TeamDeleteTool implements Tool {

        private final TeamManager teamManager;

        public TeamDeleteTool(TeamManager teamManager) {
            this.teamManager = teamManager;
        }

        @Override
        public String name() { return "TeamDelete"; }

        @Override
        public String description() {
            return "Delete a team and stop all its members. All team mailboxes and shared tasks "
                    + "are preserved on disk for later inspection.";
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("team_name", Map.of(
                    "type", "string",
                    "description", "The name of the team to delete."
            ));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", name());
            schema.put("description", description());
            schema.put("input_schema", Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", List.of("team_name")
            ));
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String teamName = getStringArg(args, "team_name");
            if (teamName == null || teamName.isBlank()) {
                return ToolResult.error("'team_name' is required");
            }

            TeamManager.Team team = teamManager.getTeam(teamName);
            if (team == null) {
                return ToolResult.error("Team '" + teamName + "' does not exist");
            }

            var memberNames = team.memberNames();
            int count = memberNames.size();
            teamManager.deleteTeam(teamName);

            String members = String.join(", ", memberNames);
            return ToolResult.success(
                    "Team \"" + teamName + "\" deleted. Stopped " + count + " member(s): " + members);
        }
    }

    private static String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
