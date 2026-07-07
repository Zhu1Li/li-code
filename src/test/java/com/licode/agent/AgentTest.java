package com.licode.agent;

import com.licode.conversation.ConversationManager;
import com.licode.llm.LlmClient;
import com.licode.llm.StreamEvent;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolRegistry;
import com.licode.tool.ToolResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest {

    // ── Test helpers ──────────────────────────────────────────────────

    /** A tool that echoes its args back as output. */
    static class EchoTool implements Tool {
        @Override public String name() { return "Echo"; }
        @Override public String description() { return "Echo tool for testing"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }
        @Override public Map<String, Object> inputSchema() {
            return Map.of(
                    "name", "Echo",
                    "description", "Echo tool for testing",
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", Map.of("msg", Map.of("type", "string")),
                            "required", List.of("msg")
                    )
            );
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            return ToolResult.success("Echo: " + args.getOrDefault("msg", ""));
        }
    }

    /** A tool that always fails — for exercising the stagnation-loop detector. */
    static class FailTool implements Tool {
        @Override public String name() { return "Fail"; }
        @Override public String description() { return "Always-failing tool for testing"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }
        @Override public Map<String, Object> inputSchema() {
            return Map.of(
                    "name", "Fail",
                    "description", "Always fails",
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", Map.of("path", Map.of("type", "string"))
                    )
            );
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            return ToolResult.error("Fail: intentional failure for " + args.getOrDefault("path", ""));
        }
    }

    /** LlmClient that returns pre-built response queues in order. */
    static class FakeLlmClient implements LlmClient {
        private final List<BlockingQueue<StreamEvent>> responses = new ArrayList<>();
        private int callCount;
        int maxOutputTokensSet;
        /** Number of tool schemas passed to each stream() call, in order. */
        final List<Integer> toolCountPerCall = new ArrayList<>();

        void addResponse(StreamEvent... events) {
            var q = new LinkedBlockingQueue<StreamEvent>();
            for (var e : events) q.add(e);
            responses.add(q);
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
            callCount++;
            toolCountPerCall.add(tools == null ? 0 : tools.size());
            if (callCount <= responses.size()) {
                return responses.get(callCount - 1);
            }
            var q = new LinkedBlockingQueue<StreamEvent>();
            q.add(new StreamEvent.StreamEnd("end_turn", 0, 0));
            return q;
        }

        @Override
        public void setMaxOutputTokens(int tokens) {
            maxOutputTokensSet = tokens;
        }

        @Override
        public void cancelStream() {
            // no-op for fake
        }
    }

    /** Drain all events from the agent queue until LoopComplete or ErrorEvent. */
    static List<AgentEvent> drain(BlockingQueue<AgentEvent> queue, long timeoutSeconds) {
        var events = new ArrayList<AgentEvent>();
        try {
            while (true) {
                AgentEvent e = queue.poll(timeoutSeconds, TimeUnit.SECONDS);
                if (e == null) break;
                events.add(e);
                if (e instanceof AgentEvent.LoopComplete || e instanceof AgentEvent.ErrorEvent) {
                    break;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return events;
    }

    // ── Tests ─────────────────────────────────────────────────────────

    @Test
    void testSimpleTextResponse() {
        var conv = new ConversationManager();
        conv.addUserMessage("hello");

        var fake = new FakeLlmClient();
        fake.addResponse(
                new StreamEvent.TextDelta("Hello!"),
                new StreamEvent.StreamEnd("end_turn", 10, 5));

        var reg = new ToolRegistry(); // no tools
        var agent = new Agent(fake, reg, "anthropic");
        var events = drain(agent.run(conv), 10);

        // Should have text + usage + turn + loop
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.StreamText
                && ((AgentEvent.StreamText) e).text().equals("Hello!")));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.UsageEvent));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.TurnComplete));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete));
    }

    @Test
    void testToolCallAndContinue() {
        var conv = new ConversationManager();
        conv.addUserMessage("echo hi");

        var fake = new FakeLlmClient();
        // First stream: model calls Echo
        fake.addResponse(
                new StreamEvent.ToolCallStart("call_1", "Echo"),
                new StreamEvent.ToolCallDelta("call_1", "{\"msg\":\"hi\"}"),
                new StreamEvent.ToolCallComplete("call_1", "Echo", Map.of("msg", "hi")),
                new StreamEvent.StreamEnd("tool_use", 20, 10));
        // Second stream: model responds to tool result
        fake.addResponse(
                new StreamEvent.TextDelta("Echo says: hi"),
                new StreamEvent.StreamEnd("end_turn", 15, 8));

        var reg = new ToolRegistry();
        reg.register(new EchoTool());
        var agent = new Agent(fake, reg, "anthropic");
        var events = drain(agent.run(conv), 10);

        // Should have tool use events
        var toolStarts = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolUseEvent
                        && "Echo".equals(((AgentEvent.ToolUseEvent) e).toolName()))
                .count();
        assertTrue(toolStarts >= 2, "Expected at least 2 ToolUseEvent (start + complete): got " + toolStarts);

        // Should have tool result
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResultEvent
                && ((AgentEvent.ToolResultEvent) e).output().contains("Echo: hi")));

        // Should complete normally
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete));
    }

    @Test
    void testTextLessIterationsGuard() {
        var conv = new ConversationManager();
        conv.addUserMessage("find pom.xml");

        var fake = new FakeLlmClient();
        // MAX_TEXT_LESS_ITERATIONS=8: textLessIterations starts at 0, increments each
        // text-less turn. Guard checks at TOP of each iteration.
        // Iter 1-9: textLessIterations 0→1→...→8, each turn emits TurnComplete
        // Iter 10: check 9 > 8 → ErrorEvent
        // So we need ≥10 queued responses; 12 gives margin.
        for (int i = 0; i < 12; i++) {
            fake.addResponse(
                    new StreamEvent.ToolCallStart("call_" + i, "Echo"),
                    new StreamEvent.ToolCallDelta("call_" + i, "{\"msg\":\"search\"}"),
                    new StreamEvent.ToolCallComplete("call_" + i, "Echo", Map.of("msg", "search")),
                    new StreamEvent.StreamEnd("tool_use", 10, 5));
        }

        var reg = new ToolRegistry();
        reg.register(new EchoTool());
        var agent = new Agent(fake, reg, "anthropic");
        var events = drain(agent.run(conv), 15);

        // Should have ErrorEvent about tool loop
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ErrorEvent
                && ((AgentEvent.ErrorEvent) e).message().contains("tool-calling loop")),
                "Expected tool-calling loop error, got: " + events);
        assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete));

        // Should have processed exactly 9 iterations before the guard fired
        var turnCompletes = events.stream()
                .filter(e -> e instanceof AgentEvent.TurnComplete)
                .count();
        assertEquals(9, turnCompletes);
    }

    @Test
    void testMaxTokensEscalation() throws Exception {
        var conv = new ConversationManager();
        conv.addUserMessage("long answer please");

        var fake = new FakeLlmClient();
        // First stream: cut off by max_tokens
        fake.addResponse(
                new StreamEvent.TextDelta("Partial answer..."),
                new StreamEvent.StreamEnd("max_tokens", 100, 50));
        // Second stream: completes (resume after escalation)
        fake.addResponse(
                new StreamEvent.TextDelta("...and the rest"),
                new StreamEvent.StreamEnd("end_turn", 50, 30));

        var reg = new ToolRegistry();
        var agent = new Agent(fake, reg, "anthropic");
        var queue = agent.run(conv);

        // Drain all events (don't stop early on ErrorEvent)
        var events = new ArrayList<AgentEvent>();
        AgentEvent e;
        while ((e = queue.poll(15, TimeUnit.SECONDS)) != null) {
            events.add(e);
            if (e instanceof AgentEvent.LoopComplete) break;
        }

        // Should have escalated max tokens
        assertTrue(fake.maxOutputTokensSet > 0, "Expected setMaxOutputTokens to be called");
        assertEquals(Agent.MAX_TOKENS_CEILING, fake.maxOutputTokensSet);

        // Should have escalation error message
        assertTrue(events.stream().anyMatch(ev -> ev instanceof AgentEvent.ErrorEvent
                && ((AgentEvent.ErrorEvent) ev).message().contains("Escalating")));

        // Should complete with LoopComplete (2 iterations)
        var loopOpt = events.stream()
                .filter(ev -> ev instanceof AgentEvent.LoopComplete)
                .findFirst();
        assertTrue(loopOpt.isPresent());
        assertEquals(2, ((AgentEvent.LoopComplete) loopOpt.get()).iterations());
    }

    @Test
    void testPlanOnlyModeBlocksWriteTool() {
        var conv = new ConversationManager();
        conv.addUserMessage("write a file");

        var fake = new FakeLlmClient();
        fake.addResponse(
                new StreamEvent.ToolCallStart("call_1", "WriteFile"),
                new StreamEvent.ToolCallDelta("call_1", "{\"file_path\":\"/tmp/x.txt\"}"),
                new StreamEvent.ToolCallComplete("call_1", "WriteFile", Map.of("file_path", "/tmp/x.txt")),
                new StreamEvent.StreamEnd("tool_use", 20, 10));
        fake.addResponse(
                new StreamEvent.TextDelta("OK"),
                new StreamEvent.StreamEnd("end_turn", 10, 5));

        var reg = new ToolRegistry();
        reg.register(new WriteFakeTool());
        var agent = new Agent(fake, reg, "anthropic");
        agent.setPlanOnlyMode(true);
        var events = drain(agent.run(conv), 10);

        // Write tool should be blocked
        var toolResult = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolResultEvent)
                .findFirst();
        assertTrue(toolResult.isPresent());
        assertTrue(((AgentEvent.ToolResultEvent) toolResult.get()).output().contains("blocked"));
    }

    static class WriteFakeTool implements Tool {
        @Override public String name() { return "WriteFile"; }
        @Override public String description() { return "Write tool"; }
        @Override public ToolCategory category() { return ToolCategory.WRITE; }
        @Override public Map<String, Object> inputSchema() {
            return Map.of("name", "WriteFile", "description", "Write", "input_schema", Map.of("type", "object"));
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            return ToolResult.success("wrote");
        }
    }

    @Test
    void testCancel() {
        var conv = new ConversationManager();
        conv.addUserMessage("slow query");

        var fake = new FakeLlmClient();
        // A stream that never ends (the agent will be cancelled)
        fake.addResponse(
                new StreamEvent.TextDelta("thinking..."));
        // No StreamEnd — stream hangs

        var reg = new ToolRegistry();
        var agent = new Agent(fake, reg, "anthropic");
        var queue = agent.run(conv);

        // Give agent a moment to start processing
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        agent.cancel();

        var events = drain(queue, 5);
        assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete));
    }

    // ── stagnation-loop detection ─────────────────────────────────────

    @Test
    void testStagnationLoopAborts() {
        var conv = new ConversationManager();
        conv.addUserMessage("fix the bug");

        var fake = new FakeLlmClient();
        // Every turn produces text (so textLessIterations never trips) AND the SAME
        // failing tool call with identical args. Detector needs 3 fails → NUDGE, then
        // 3 more → ABORT, so ~6 failing turns — the loop textLess can't catch.
        for (int i = 0; i < 10; i++) {
            fake.addResponse(
                    new StreamEvent.TextDelta("Let me try that again."),
                    new StreamEvent.ToolCallStart("call_" + i, "Fail"),
                    new StreamEvent.ToolCallDelta("call_" + i, "{\"path\":\"x.txt\"}"),
                    new StreamEvent.ToolCallComplete("call_" + i, "Fail", Map.of("path", "x.txt")),
                    new StreamEvent.StreamEnd("tool_use", 10, 5));
        }

        var reg = new ToolRegistry();
        reg.register(new FailTool());
        var agent = new Agent(fake, reg, "anthropic");
        var events = drain(agent.run(conv), 15);

        // Aborted with the loop message
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ErrorEvent
                        && ((AgentEvent.ErrorEvent) e).message().contains("suspected infinite loop")),
                "Expected suspected-infinite-loop abort, got: " + events);
        // Did NOT complete normally
        assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete));
        // Aborted far earlier than the 50-iteration ceiling
        long turns = events.stream().filter(e -> e instanceof AgentEvent.TurnComplete).count();
        assertTrue(turns < 10, "Expected abort well before 50 turns, got " + turns);
        // A loop-warning was injected before the abort
        assertTrue(conv.getMessages().stream().anyMatch(m ->
                        m.getContent() != null && m.getContent().contains("<loop-warning>")),
                "Expected a <loop-warning> reminder injected into the conversation");
    }

    @Test
    void testDistinctFailingArgsDoNotAbort() {
        var conv = new ConversationManager();
        conv.addUserMessage("investigate");

        var fake = new FakeLlmClient();
        // Fails every turn but with DIFFERENT args → distinct signatures → never
        // 3 identical → must not trigger the loop abort.
        for (int i = 0; i < 5; i++) {
            fake.addResponse(
                    new StreamEvent.TextDelta("Trying path " + i),
                    new StreamEvent.ToolCallStart("call_" + i, "Fail"),
                    new StreamEvent.ToolCallDelta("call_" + i, "{\"path\":\"file" + i + ".txt\"}"),
                    new StreamEvent.ToolCallComplete("call_" + i, "Fail", Map.of("path", "file" + i + ".txt")),
                    new StreamEvent.StreamEnd("tool_use", 10, 5));
        }
        // Wrap-up turn response (max iterations = 5 → turn 6 wraps up).
        fake.addResponse(
                new StreamEvent.TextDelta("Handing off."),
                new StreamEvent.StreamEnd("end_turn", 5, 3));

        var reg = new ToolRegistry();
        reg.register(new FailTool());
        var agent = new Agent(fake, reg, "anthropic");
        agent.setMaxIterations(5);
        var events = drain(agent.run(conv), 15);

        assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.ErrorEvent
                        && ((AgentEvent.ErrorEvent) e).message().contains("suspected infinite loop")),
                "Distinct args must not trigger loop abort, got: " + events);
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete),
                "Expected graceful completion via wrap-up turn");
    }

    // ── wrap-up turn on max iterations ────────────────────────────────

    @Test
    void testWrapUpTurnOnMaxIterations() {
        var conv = new ConversationManager();
        conv.addUserMessage("do a big task");

        var fake = new FakeLlmClient();
        // Model keeps calling a tool every turn (never plain text) up to the limit.
        fake.addResponse(
                new StreamEvent.ToolCallStart("call_1", "Echo"),
                new StreamEvent.ToolCallDelta("call_1", "{\"msg\":\"a\"}"),
                new StreamEvent.ToolCallComplete("call_1", "Echo", Map.of("msg", "a")),
                new StreamEvent.StreamEnd("tool_use", 10, 5));
        fake.addResponse(
                new StreamEvent.ToolCallStart("call_2", "Echo"),
                new StreamEvent.ToolCallDelta("call_2", "{\"msg\":\"b\"}"),
                new StreamEvent.ToolCallComplete("call_2", "Echo", Map.of("msg", "b")),
                new StreamEvent.StreamEnd("tool_use", 10, 5));
        // Wrap-up turn: with tools stripped the model can only produce text.
        fake.addResponse(
                new StreamEvent.TextDelta("Handoff: did A and B; remaining C; blocked on D."),
                new StreamEvent.StreamEnd("end_turn", 8, 4));

        var reg = new ToolRegistry();
        reg.register(new EchoTool());
        var agent = new Agent(fake, reg, "anthropic");
        agent.setMaxIterations(2);
        var events = drain(agent.run(conv), 15);

        // Ended gracefully with LoopComplete (NOT the max-iterations error)
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete),
                "Expected graceful LoopComplete from the wrap-up turn, got: " + events);
        assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.ErrorEvent
                        && ((AgentEvent.ErrorEvent) e).message().contains("reached max iterations")),
                "Wrap-up turn should replace the hard max-iterations error");
        // Handoff text streamed
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.StreamText
                && ((AgentEvent.StreamText) e).text().contains("Handoff:")));
        // Budget-exhausted instruction was injected
        assertTrue(conv.getMessages().stream().anyMatch(m ->
                        m.getContent() != null && m.getContent().contains("<budget-exhausted>")),
                "Expected a <budget-exhausted> instruction injected");
        // The wrap-up turn (3rd stream call) had tools stripped, normal turns did not
        assertEquals(3, fake.toolCountPerCall.size(), "Expected exactly 3 stream calls");
        assertEquals(0, (int) fake.toolCountPerCall.get(2), "Wrap-up turn must pass zero tools");
        assertTrue(fake.toolCountPerCall.get(0) >= 1, "Normal turns must have tools available");
    }
}
