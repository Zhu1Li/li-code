package com.licode.hook;

import org.junit.jupiter.api.Test;

import com.licode.hook.HookEngine.HookValidationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HookEngineTest {

    @Test
    void commandHook_shouldExecuteAndWriteOutput() throws Exception {
        var engine = new HookEngine();
        Path logFile = Path.of("target", "hook-test-output.log");

        var hook = new HookEngine.Hook(
                "test-cmd",
                HookEngine.EventName.TURN_START,
                null,
                new HookEngine.Action(HookEngine.ActionType.COMMAND,
                        "echo hello > target/hook-test-output.log", null, null, null, null, null, 0),
                false, false, false, 5
        );
        engine.loadHooks(List.of(hook));

        var ctx = new HookEngine.HookContext(HookEngine.EventName.TURN_START, null, null, null, null, null);
        var results = engine.runHooks(ctx);

        assertEquals(1, results.size());
        assertTrue(results.get(0).success(), "Hook failed: " + results.get(0).output());

        String content = Files.readString(logFile).strip();
        assertEquals("hello", content);
        Files.deleteIfExists(logFile);
    }

    @Test
    void templateVariable_shouldResolveToolName() throws Exception {
        var engine = new HookEngine();
        Path logFile = Path.of("target", "hook-template-test.log");

        var hook = new HookEngine.Hook(
                "test-template",
                HookEngine.EventName.POST_TOOL_USE,
                null,
                new HookEngine.Action(HookEngine.ActionType.COMMAND,
                        "echo tool={{tool}} > target/hook-template-test.log", null, null, null, null, null, 0),
                false, false, false, 5
        );
        engine.loadHooks(List.of(hook));

        var ctx = new HookEngine.HookContext(HookEngine.EventName.POST_TOOL_USE, "bash",
                Map.of("command", "ls"), null, null, null);
        engine.runHooks(ctx);

        String content = Files.readString(logFile).strip();
        assertTrue(content.contains("tool=bash"), "Expected 'tool=bash' but got: " + content);
        Files.deleteIfExists(logFile);
    }

    @Test
    void conditionAll_shouldMatchWhenAllRulesPass() throws HookValidationException {
        var engine = new HookEngine();
        var hook = new HookEngine.Hook(
                "test-cond",
                HookEngine.EventName.PRE_TOOL_USE,
                new HookEngine.ConditionGroup("all", List.of(
                        new HookEngine.Condition("tool", "==", "bash"),
                        new HookEngine.Condition("args.command", "=~", ".*rm.*")
                )),
                new HookEngine.Action(HookEngine.ActionType.PROMPT, null, "matched", null, null, null, null, 0),
                false, false, false, 5
        );
        engine.loadHooks(List.of(hook));

        var matchCtx = new HookEngine.HookContext(HookEngine.EventName.PRE_TOOL_USE, "bash",
                Map.of("command", "rm -rf /"), null, null, null);
        var matchResults = engine.runHooks(matchCtx);
        assertEquals(1, matchResults.size());
        assertEquals("matched", matchResults.get(0).output());

        var noMatchCtx = new HookEngine.HookContext(HookEngine.EventName.PRE_TOOL_USE, "read",
                Map.of(), null, null, null);
        var noMatchResults = engine.runHooks(noMatchCtx);
        assertEquals(0, noMatchResults.size());
    }

    @Test
    void conditionAny_shouldMatchWhenAnyRulePasses() throws HookValidationException {
        var engine = new HookEngine();
        var hook = new HookEngine.Hook(
                "test-any",
                HookEngine.EventName.TURN_START,
                new HookEngine.ConditionGroup("any", List.of(
                        new HookEngine.Condition("tool", "==", "bash"),
                        new HookEngine.Condition("tool", "==", "read")
                )),
                new HookEngine.Action(HookEngine.ActionType.PROMPT, null, "matched-any", null, null, null, null, 0),
                false, false, false, 5
        );
        engine.loadHooks(List.of(hook));

        var ctx = new HookEngine.HookContext(HookEngine.EventName.TURN_START, "read", null, null, null, null);
        var results = engine.runHooks(ctx);
        assertEquals(1, results.size());
    }

    @Test
    void preToolReject_shouldReturnRejectedResult() throws HookValidationException {
        var engine = new HookEngine();
        var hook = new HookEngine.Hook(
                "block-bash",
                HookEngine.EventName.PRE_TOOL_USE,
                null,
                new HookEngine.Action(HookEngine.ActionType.PROMPT, null, "Bash is blocked", null, null, null, null, 0),
                true, false, false, 5
        );
        engine.loadHooks(List.of(hook));

        var result = engine.runPreToolHooks("bash", Map.of(), false);
        assertTrue(result.rejected());
        assertEquals("Bash is blocked", result.message());
    }

    @Test
    void bypass_shouldSkipPreToolHooks() throws HookValidationException {
        var engine = new HookEngine();
        var hook = new HookEngine.Hook(
                "block-bash",
                HookEngine.EventName.PRE_TOOL_USE,
                null,
                new HookEngine.Action(HookEngine.ActionType.PROMPT, null, "blocked", null, null, null, null, 0),
                true, false, false, 5
        );
        engine.loadHooks(List.of(hook));

        var result = engine.runPreToolHooks("bash", Map.of(), true); // bypass=true
        assertFalse(result.rejected());
    }

    @Test
    void onceHook_shouldFireOnlyOnce() throws HookValidationException {
        var engine = new HookEngine();
        var hook = new HookEngine.Hook(
                "once-test",
                HookEngine.EventName.TURN_START,
                null,
                new HookEngine.Action(HookEngine.ActionType.PROMPT, null, "fired", null, null, null, null, 0),
                false, true, false, 5
        );
        engine.loadHooks(List.of(hook));

        var ctx = new HookEngine.HookContext(HookEngine.EventName.TURN_START, null, null, null, null, null);
        assertEquals(1, engine.runHooks(ctx).size());
        assertEquals(0, engine.runHooks(ctx).size()); // second call: skipped
    }

    @Test
    void asyncHook_shouldReturnAsyncPlaceholder() throws HookValidationException {
        var engine = new HookEngine();
        var hook = new HookEngine.Hook(
                "async-test",
                HookEngine.EventName.TURN_START,
                null,
                new HookEngine.Action(HookEngine.ActionType.COMMAND, "sleep 1", null, null, null, null, null, 0),
                false, false, true, 5
        );
        engine.loadHooks(List.of(hook));

        var ctx = new HookEngine.HookContext(HookEngine.EventName.TURN_START, null, null, null, null, null);
        var results = engine.runHooks(ctx);
        assertEquals(1, results.size());
        assertEquals("(async)", results.get(0).output());
    }

    @Test
    void validation_shouldRejectInvalidHook() {
        // Missing event
        var badHook = new HookEngine.Hook(
                "bad1", null, null,
                new HookEngine.Action(HookEngine.ActionType.COMMAND, "echo hi", null, null, null, null, null, 0),
                false, false, false, 5
        );
        var errors = HookEngine.validate(List.of(badHook));
        assertTrue(errors.stream().anyMatch(e -> e.contains("event is required")));

        // Missing command for COMMAND type
        var badHook2 = new HookEngine.Hook(
                "bad2", HookEngine.EventName.TURN_START, null,
                new HookEngine.Action(HookEngine.ActionType.COMMAND, null, null, null, null, null, null, 0),
                false, false, false, 5
        );
        errors = HookEngine.validate(List.of(badHook2));
        assertTrue(errors.stream().anyMatch(e -> e.contains("command must be non-empty")));
    }

    @Test
    void globOperator_shouldMatchFilePath() throws HookValidationException {
        var engine = new HookEngine();
        var hook = new HookEngine.Hook(
                "glob-test",
                HookEngine.EventName.PRE_TOOL_USE,
                new HookEngine.ConditionGroup("all", List.of(
                        new HookEngine.Condition("file_path", "=*", "**/.env")
                )),
                new HookEngine.Action(HookEngine.ActionType.PROMPT, null, "matched-glob", null, null, null, null, 0),
                false, false, false, 5
        );
        engine.loadHooks(List.of(hook));

        var matchCtx = new HookEngine.HookContext(HookEngine.EventName.PRE_TOOL_USE, "read", null,
                "project/.env", null, null);
        var matchResults = engine.runHooks(matchCtx);
        assertEquals(1, matchResults.size());

        var noMatchCtx = new HookEngine.HookContext(HookEngine.EventName.PRE_TOOL_USE, "read", null,
                "project/config.yaml", null, null);
        var noMatchResults = engine.runHooks(noMatchCtx);
        assertEquals(0, noMatchResults.size());
    }
}
