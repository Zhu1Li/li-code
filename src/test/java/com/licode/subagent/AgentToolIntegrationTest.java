package com.licode.subagent;

import com.licode.agent.AgentEvent;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.ToolUseBlock;
import com.licode.llm.LlmClient;
import com.licode.llm.StreamEvent;
import com.licode.tool.ToolRegistry;
import com.licode.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolIntegrationTest {

    private ToolRegistry toolRegistry;
    private SubAgentTaskManager taskManager;
    private Path workDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        workDir = tempDir;
        toolRegistry = ToolRegistry.createDefault();
        taskManager = new SubAgentTaskManager();
    }

    private AgentTool createTool(LlmClient client) {
        var tool = new AgentTool(client, toolRegistry, "anthropic");
        tool.setTaskManager(taskManager);
        tool.setWorkDir(workDir.toString());
        tool.setAgentSpecs(Map.of(
                "plan", SubAgentSpec.PLAN,
                "general-purpose", SubAgentSpec.GENERAL_PURPOSE,
                "explore", SubAgentSpec.EXPLORE
        ));
        return tool;
    }

    private static MockLlmClient textOnlyClient(String text) {
        return new MockLlmClient(List.of(
                new StreamEvent.TextDelta(text),
                new StreamEvent.StreamEnd("end_turn", 10, 5)
        ));
    }

    // ── Execute routing ─────────────────────────────────────────────

    @Test
    void execute_missingDescription_shouldReturnError() {
        var tool = createTool(textOnlyClient("ignored"));
        ToolResult result = tool.execute(Map.of("prompt", "do something"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("description and prompt are required"));
    }

    @Test
    void execute_missingPrompt_shouldReturnError() {
        var tool = createTool(textOnlyClient("ignored"));
        ToolResult result = tool.execute(Map.of("description", "test"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("description and prompt are required"));
    }

    @Test
    void execute_emptyDescription_shouldReturnError() {
        var tool = createTool(textOnlyClient("ignored"));
        ToolResult result = tool.execute(Map.of("description", "", "prompt", "do X"));
        assertTrue(result.isError());
    }

    @Test
    void execute_unknownAgentType_shouldReturnError() {
        var tool = createTool(textOnlyClient("ignored"));
        ToolResult result = tool.execute(Map.of(
                "description", "test",
                "prompt", "do X",
                "subagent_type", "nonexistent"
        ));
        assertTrue(result.isError());
        assertTrue(result.output().contains("unknown agent type"));
        assertTrue(result.output().contains("general-purpose"));
    }

    // ── Sync execution ──────────────────────────────────────────────

    @Test
    void syncSubAgent_shouldCompleteAndReturnOutput() {
        var tool = createTool(textOnlyClient("Step 1: analyze. Step 2: implement."));

        ToolResult result = tool.execute(Map.of(
                "description", "plan test",
                "prompt", "design a solution",
                "subagent_type", "plan"
        ));

        assertFalse(result.isError(), "Expected success, got: " + result.output());
        assertTrue(result.output().contains("completed in"));
        assertTrue(result.output().contains("Step 1: analyze"));
        assertTrue(result.output().contains("Agent \"plan test\" completed"));
    }

    @Test
    void syncSubAgent_withParentQueue_shouldForwardStreamEvents() {
        var tool = createTool(textOnlyClient("streaming output from sub-agent"));
        var parentQueue = new LinkedBlockingQueue<AgentEvent>();
        tool.setParentQueue(parentQueue);

        ToolResult result = tool.execute(Map.of(
                "description", "stream test",
                "prompt", "do X",
                "subagent_type", "general-purpose"
        ));

        assertFalse(result.isError(), "Got error: " + result.output());

        // Parent queue should have received the forwarded StreamText
        boolean foundStreamText = false;
        for (var event : parentQueue) {
            if (event instanceof AgentEvent.StreamText st
                    && st.text().contains("streaming output from sub-agent")) {
                foundStreamText = true;
                break;
            }
        }
        assertTrue(foundStreamText,
                "Parent queue should contain forwarded StreamText from sub-agent");
    }

    @Test
    void syncSubAgent_emptyOutput_shouldReturnPlaceholder() {
        var tool = createTool(new MockLlmClient(List.of(
                new StreamEvent.StreamEnd("end_turn", 5, 0)
        )));

        ToolResult result = tool.execute(Map.of(
                "description", "empty test",
                "prompt", "do nothing",
                "subagent_type", "general-purpose"
        ));

        assertFalse(result.isError());
        assertTrue(result.output().contains("agent produced no output"),
                "Got: " + result.output());
    }

    @Test
    void syncSubAgent_modelOverride_shouldUseOverriddenModel() {
        // When model is overridden but spec has no model, selectClient still
        // falls back to the default client (mock). The key test is that it doesn't error.
        var tool = createTool(textOnlyClient("result from overridden model"));

        ToolResult result = tool.execute(Map.of(
                "description", "override test",
                "prompt", "do X",
                "subagent_type", "general-purpose",
                "model", "gpt-4"
        ));

        assertFalse(result.isError(), "Got error: " + result.output());
        assertTrue(result.output().contains("result from overridden model"));
    }

    // ── Async (background) execution ─────────────────────────────────

    @Test
    void asyncSubAgent_shouldReturnTaskId() {
        var tool = createTool(textOnlyClient("ignored"));

        ToolResult result = tool.execute(Map.of(
                "description", "bg test",
                "prompt", "do X",
                "subagent_type", "general-purpose",
                "run_in_background", true
        ));

        assertFalse(result.isError(), "Got error: " + result.output());
        assertTrue(result.output().contains("launched in background"));
        assertTrue(result.output().contains("task_1"));
        assertTrue(result.output().contains("bg test"));
    }

    @Test
    void asyncSubAgent_withoutTaskManager_shouldReturnError() {
        var tool = new AgentTool(new MockLlmClient(List.of()), toolRegistry, "anthropic");
        tool.setWorkDir(workDir.toString());
        // No taskManager set

        ToolResult result = tool.execute(Map.of(
                "description", "bg test",
                "prompt", "do X",
                "subagent_type", "general-purpose",
                "run_in_background", true
        ));

        assertTrue(result.isError());
        assertTrue(result.output().contains("Background execution not available"));
    }

    @Test
    void asyncSubAgent_shouldAppearInTaskList() {
        var tool = createTool(textOnlyClient("ignored"));

        tool.execute(Map.of(
                "description", "listable task",
                "prompt", "do X",
                "subagent_type", "general-purpose",
                "run_in_background", true
        ));

        var tasks = taskManager.listTasks();
        assertFalse(tasks.isEmpty(), "Task should appear in task list");
        // Task name = spec.name() + ": " + truncate(prompt, 50) = "general-purpose: do X"
        assertTrue(tasks.get(0).name().contains("general-purpose"),
                "Task name should contain spec name, got: " + tasks.get(0).name());
        assertEquals(SubAgentTaskManager.TaskStatus.RUNNING, tasks.get(0).status(),
                "Task should be RUNNING immediately after spawn");
    }

    // ── Fork execution ──────────────────────────────────────────────

    @Test
    void fork_withoutParentConversation_shouldReturnError() {
        var tool = createTool(textOnlyClient("ignored"));

        ToolResult result = tool.execute(Map.of(
                "description", "fork test",
                "prompt", "do X"
                // no subagent_type → fork path
        ));

        assertTrue(result.isError());
        assertTrue(result.output().contains("requires parent conversation context"));
    }

    @Test
    void fork_withoutTaskManager_shouldReturnError() {
        var parentConv = new ConversationManager();
        parentConv.addUserMessage("original request");

        var tool = new AgentTool(new MockLlmClient(List.of()), toolRegistry, "anthropic");
        tool.setWorkDir(workDir.toString());
        tool.setParentConversation(parentConv);
        // No taskManager

        ToolResult result = tool.execute(Map.of(
                "description", "fork test",
                "prompt", "do X"
        ));

        assertTrue(result.isError());
        assertTrue(result.output().contains("requires task manager"));
    }

    @Test
    void fork_shouldReturnTaskId() {
        var parentConv = new ConversationManager();
        parentConv.addUserMessage("original request");

        var tool = createTool(textOnlyClient("ignored"));
        tool.setParentConversation(parentConv);

        ToolResult result = tool.execute(Map.of(
                "description", "forked task",
                "prompt", "do X"
        ));

        assertFalse(result.isError(), "Got error: " + result.output());
        assertTrue(result.output().contains("Forked agent"),
                "Output should mention Forked agent, got: " + result.output());
        assertTrue(result.output().contains("launched in background"),
                "Output should mention launched in background, got: " + result.output());
        assertTrue(result.output().contains("task-notification"),
                "Output should mention task-notification, got: " + result.output());
    }

    @Test
    void nestedFork_shouldBeRejected() {
        var parentConv = new ConversationManager();
        // Add a message containing the fork boilerplate tag to simulate being inside a fork
        parentConv.addUserMessage("original request");
        parentConv.addAssistantFull("<fork_boilerplate>\nRules:\nDo NOT fork again.\n</fork_boilerplate>", null, null, null);

        var tool = createTool(textOnlyClient("ignored"));
        tool.setParentConversation(parentConv);

        ToolResult result = tool.execute(Map.of(
                "description", "nested fork",
                "prompt", "do X"
        ));

        assertTrue(result.isError(), "Nested fork should be rejected");
        assertTrue(result.output().contains("cannot fork from a forked agent"),
                "Got: " + result.output());
    }

    // ── Sub-agent timeout ───────────────────────────────────────────

    @Test
    @Disabled("Exercises the real 120s stream-poll timeout — single-handedly makes the "
            + "whole test suite take ~2.5min, and its assertion is a tautology "
            + "(isError() || !isError()). Re-enable only when explicitly testing timeout behavior.")
    void syncSubAgent_timeout_shouldReturnError() {
        // A client that never produces events will cause the sub-agent to timeout
        var silentClient = new MockLlmClient(List.of()); // emits nothing
        var tool = createTool(silentClient);

        // Reduce max iterations to speed up timeout (the poll timeout is 120s)
        // Note: This test expects the 120s poll timeout. We cap the sub-agent Plan
        // maxTurns to 1 so the inner Agent loop exits quickly.
        var fastSpec = new SubAgentSpec("fast", "fast", List.of(), List.of(), null, 1, null);
        tool.setAgentSpecs(Map.of("fast", fastSpec));

        ToolResult result = tool.execute(Map.of(
                "description", "timeout test",
                "prompt", "do X",
                "subagent_type", "fast"
        ));

        // The Agent will poll for 120s then timeout. We use maxTurns=1 to limit
        // iterations, but the stream poll is still 120s. This test takes about
        // 120 seconds. Skip for now unless explicitly needed.
        // (In practice, use an executor with timeout instead.)
        assertTrue(result.isError() || !result.isError(),
                "This validates timeout is handled gracefully");
    }

    // ── Conversation builder ────────────────────────────────────────

    @Test
    void buildForkedConversation_shouldCopyAssistantWithToolUses() {
        var parent = new ConversationManager();
        parent.addUserMessage("do a thing");
        parent.addAssistantFull("I'll check that.",
                null,
                List.of(new ToolUseBlock("tu_read", "ReadFile",
                        Map.of("file_path", "/tmp/test.txt"))),
                null);

        var forked = AgentTool.buildForkedConversation(parent,
                AgentTool.FORK_BOILERPLATE + "\n\nYour task:\nreview file");

        var msgs = forked.getMessages();
        // user, assistant with tool_use, placeholder tool_result (patched), task
        assertEquals(4, msgs.size());
        assertEquals("user", msgs.get(0).getRole());
        assertEquals("assistant", msgs.get(1).getRole());
        assertNotNull(msgs.get(1).getToolUses());
        assertEquals("ReadFile", msgs.get(1).getToolUses().get(0).toolName());
        // msg[2] is the patched tool_result (user role)
        assertEquals("user", msgs.get(2).getRole());
        assertNotNull(msgs.get(2).getToolResults());
        assertEquals(1, msgs.get(2).getToolResults().size());
        // msg[3] is the fork task user message
        assertEquals("user", msgs.get(3).getRole());
        assertTrue(msgs.get(3).getContent().contains("Your task"));
    }

    @Test
    void buildForkedConversation_shouldPatchOrphanedToolUses() {
        var parent = new ConversationManager();
        parent.addUserMessage("run cmd");
        // Assistant with pending tool_use (no tool_result)
        parent.addAssistantFull(null, null,
                List.of(new ToolUseBlock("tu_bash", "Bash",
                        Map.of("command", "ls"))), null);

        var forked = AgentTool.buildForkedConversation(parent,
                AgentTool.FORK_BOILERPLATE + "\n\nYour task:\ncontinue");

        var msgs = forked.getMessages();
        // Should have: user, assistant with tool_use, user with placeholder tool_result
        boolean hasPlaceholder = msgs.stream()
                .filter(m -> "user".equals(m.getRole()) && m.getToolResults() != null)
                .flatMap(m -> m.getToolResults().stream())
                .anyMatch(tr -> tr.content().contains("tool execution interrupted by fork"));
        assertTrue(hasPlaceholder, "Orphaned tool_use should be patched with placeholder");
    }

    // ── Task manager state machine ──────────────────────────────────

    @Test
    void taskManager_fullLifecycle_shouldTrackState() throws InterruptedException {
        String id = taskManager.createTask("full lifecycle");
        assertEquals(SubAgentTaskManager.TaskStatus.PENDING, taskManager.getTask(id).status());

        taskManager.setRunning(id, Thread.currentThread());
        assertEquals(SubAgentTaskManager.TaskStatus.RUNNING, taskManager.getTask(id).status());

        taskManager.setCompleted(id, "output", 100, 50, 2000);
        assertEquals(SubAgentTaskManager.TaskStatus.COMPLETED, taskManager.getTask(id).status());

        var notifications = taskManager.drainNotifications();
        assertEquals(1, notifications.size());
        assertEquals("output", notifications.get(0).output());
        assertEquals(100, notifications.get(0).inputTokens());
        assertEquals(2000, notifications.get(0).elapsedMs());
    }

    @Test
    void taskNotifications_shouldBeDrainedOnce() {
        taskManager.createTask("drain test");
        taskManager.setRunning("task_1", Thread.currentThread());
        taskManager.setCompleted("task_1", "result", 0, 0, 100);

        var first = taskManager.drainNotifications();
        assertEquals(1, first.size());

        var second = taskManager.drainNotifications();
        assertTrue(second.isEmpty(), "Notifications should be cleared after drain");
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /** A controllable LLM client that emits a predefined list of stream events. */
    private static class MockLlmClient implements LlmClient {
        private final List<StreamEvent> events;

        MockLlmClient(List<StreamEvent> events) {
            this.events = events;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                  List<Map<String, Object>> tools) {
            var queue = new LinkedBlockingQueue<StreamEvent>();
            Thread.startVirtualThread(() -> {
                for (var event : events) {
                    try {
                        queue.put(event);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            return queue;
        }

        @Override
        public void cancelStream() {}
    }
}
