package com.licode.tool.impl;

import com.licode.failure.FailureStore;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailureToolsTest {

    @Test
    void recordFailureCategoryAndName(@TempDir Path work) {
        var t = new RecordFailureTool(new FailureStore(work));
        assertEquals("RecordFailure", t.name());
        assertEquals(ToolCategory.WRITE, t.category());
    }

    @Test
    void recallFailuresIsReadOnly(@TempDir Path work) {
        var t = new RecallFailuresTool(new FailureStore(work));
        assertEquals("RecallFailures", t.name());
        assertEquals(ToolCategory.READ, t.category());
    }

    @Test
    void recordRejectsMissingRequired(@TempDir Path work) {
        var t = new RecordFailureTool(new FailureStore(work));
        assertTrue(t.execute(Map.of("root_cause", "c", "fix", "f")).isError()); // no keywords
        assertTrue(t.execute(Map.of("keywords", "k", "fix", "f")).isError());   // no root_cause
        assertTrue(t.execute(Map.of("keywords", "k", "root_cause", "c")).isError()); // no fix
    }

    @Test
    void recordThenRecallRoundTrip(@TempDir Path work) {
        var store = new FailureStore(work);
        var rec = new RecordFailureTool(store);
        var rcl = new RecallFailuresTool(store);

        ToolResult w = rec.execute(Map.of(
                "keywords", "NullPointerException, login",
                "root_cause", "npe in login",
                "fix", "add null check",
                "test_name", "UserServiceTest.login",
                "exception", "NullPointerException"));
        assertFalse(w.isError());

        ToolResult r = rcl.execute(Map.of("keywords", "NullPointerException login"));
        assertFalse(r.isError());
        assertTrue(r.output().contains("add null check"), "recall should surface the recorded fix");
    }

    @Test
    void recallNoHitIsNotError(@TempDir Path work) {
        var rcl = new RecallFailuresTool(new FailureStore(work));
        ToolResult r = rcl.execute(Map.of("keywords", "nothing recorded yet"));
        assertFalse(r.isError());
        assertTrue(r.output().toLowerCase().contains("no similar"));
    }

    @Test
    void recallRejectsMissingKeywords(@TempDir Path work) {
        var rcl = new RecallFailuresTool(new FailureStore(work));
        assertTrue(rcl.execute(Map.of()).isError());
    }
}
