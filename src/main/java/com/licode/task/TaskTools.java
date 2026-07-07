package com.licode.task;

import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskTools {

    private TaskTools() {}

    public static class TaskCreateTool implements Tool {

        private final TaskList taskList;

        public TaskCreateTool(TaskList taskList) {
            this.taskList = taskList;
        }

        @Override
        public String name() { return "TaskCreate"; }

        @Override
        public String description() {
            return "Create a new task in the shared task list. Use this to track work items "
                    + "with optional dependency fields (blocks/blockedBy).";
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("subject", Map.of(
                    "type", "string",
                    "description", "A brief, actionable title for the task in imperative form."
            ));
            properties.put("description", Map.of(
                    "type", "string",
                    "description", "What needs to be done."
            ));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", name());
            schema.put("description", description());
            schema.put("input_schema", Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", List.of("subject", "description")
            ));
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String subject = getStringArg(args, "subject");
            if (subject == null || subject.isBlank()) return ToolResult.error("'subject' is required");
            String description = getStringArg(args, "description");
            if (description == null || description.isBlank()) return ToolResult.error("'description' is required");

            var task = taskList.create(subject, description);
            return ToolResult.success("Task #" + task.id() + " created: " + task.subject());
        }
    }

    public static class TaskGetTool implements Tool {

        private final TaskList taskList;

        public TaskGetTool(TaskList taskList) {
            this.taskList = taskList;
        }

        @Override
        public String name() { return "TaskGet"; }

        @Override
        public String description() {
            return "Retrieve a task by its ID from the shared task list. "
                    + "Returns full task details including dependencies.";
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("taskId", Map.of(
                    "type", "string",
                    "description", "The ID of the task to retrieve."
            ));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", name());
            schema.put("description", description());
            schema.put("input_schema", Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", List.of("taskId")
            ));
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String taskId = getStringArg(args, "taskId");
            if (taskId == null || taskId.isBlank()) return ToolResult.error("'taskId' is required");
            try {
                int id = Integer.parseInt(taskId);
                var task = taskList.get(id);
                if (task == null) return ToolResult.error("Task #" + id + " not found");
                return ToolResult.success(
                        "Task #" + task.id() + " [" + task.status() + "]: " + task.subject()
                                + "\nDescription: " + task.description()
                                + "\nBlocks: " + task.blocks() + "  BlockedBy: " + task.blockedBy());
            } catch (NumberFormatException e) {
                return ToolResult.error("Invalid task ID: " + taskId);
            }
        }
    }

    public static class TaskListTool implements Tool {

        private final TaskList taskList;

        public TaskListTool(TaskList taskList) {
            this.taskList = taskList;
        }

        @Override
        public String name() { return "TaskList"; }

        @Override
        public String description() {
            return "List all tasks in the shared task list with their status and dependencies.";
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", name());
            schema.put("description", description());
            schema.put("input_schema", Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of()
            ));
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            var tasks = taskList.list();
            if (tasks.isEmpty()) return ToolResult.success("No tasks.");
            var sb = new StringBuilder();
            for (var t : tasks) {
                sb.append("#").append(t.id()).append(" [").append(t.status()).append("] ")
                        .append(t.subject());
                if (!t.blocks().isEmpty()) sb.append(" (blocks: ").append(t.blocks()).append(")");
                if (!t.blockedBy().isEmpty()) sb.append(" (blockedBy: ").append(t.blockedBy()).append(")");
                sb.append("\n");
            }
            return ToolResult.success(sb.toString().stripTrailing());
        }
    }

    public static class TaskUpdateTool implements Tool {

        private final TaskList taskList;

        public TaskUpdateTool(TaskList taskList) {
            this.taskList = taskList;
        }

        @Override
        public String name() { return "TaskUpdate"; }

        @Override
        public String description() {
            return "Update a task's status, dependencies, or metadata. "
                    + "Use status=\"deleted\" to permanently remove a task. "
                    + "addBlocks and addBlockedBy append to existing lists (they don't replace).";
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("taskId", Map.of(
                    "type", "string",
                    "description", "The ID of the task to update."
            ));
            properties.put("status", Map.of(
                    "type", "string",
                    "description", "New status: PENDING, IN_PROGRESS, COMPLETED, or \"deleted\" to remove."
            ));
            properties.put("subject", Map.of(
                    "type", "string",
                    "description", "New subject for the task."
            ));
            properties.put("description", Map.of(
                    "type", "string",
                    "description", "New description for the task."
            ));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", name());
            schema.put("description", description());
            schema.put("input_schema", Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", List.of("taskId")
            ));
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String taskId = getStringArg(args, "taskId");
            if (taskId == null || taskId.isBlank()) return ToolResult.error("'taskId' is required");
            try {
                int id = Integer.parseInt(taskId);
                String status = getStringArg(args, "status");
                String subject = getStringArg(args, "subject");
                String description = getStringArg(args, "description");

                @SuppressWarnings("unchecked")
                List<Integer> addBlocks = (List<Integer>) args.get("addBlocks");
                @SuppressWarnings("unchecked")
                List<Integer> addBlockedBy = (List<Integer>) args.get("addBlockedBy");
                @SuppressWarnings("unchecked")
                Map<String, Object> mergeMetadata = (Map<String, Object>) args.get("metadata");

                var result = taskList.update(id, status, subject, description, null,
                        addBlocks, addBlockedBy, mergeMetadata);
                if (result == null) return ToolResult.error("Task #" + id + " not found");
                if ("deleted".equals(status)) {
                    return ToolResult.success("Task #" + id + " deleted.");
                }
                return ToolResult.success("Task #" + id + " updated. Changed: " + result.changedFields());
            } catch (NumberFormatException e) {
                return ToolResult.error("Invalid task ID: " + taskId);
            }
        }
    }

    private static String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
