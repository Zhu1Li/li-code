package com.licode.agent;

import com.licode.agent.ToolLoopDetector.Verdict;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolLoopDetectorTest {

    private static Map<String, Object> args(String path) {
        return Map.of("path", path);
    }

    @Test
    void signatureIsKeyOrderIndependent() {
        var a = new LinkedHashMap<String, Object>();
        a.put("a", 1);
        a.put("b", 2);
        var b = new LinkedHashMap<String, Object>();
        b.put("b", 2);
        b.put("a", 1);

        assertEquals(ToolLoopDetector.signature("Edit", a),
                ToolLoopDetector.signature("Edit", b),
                "signature must not depend on argument key order");
        assertNotEquals(ToolLoopDetector.signature("Edit", a),
                ToolLoopDetector.signature("Read", a),
                "different tool names must produce different signatures");
    }

    @Test
    void signatureIsFixedLengthHex() {
        String sig = ToolLoopDetector.signature("Bash", args("x.txt"));
        assertEquals(16, sig.length());
        assertTrue(sig.matches("[0-9a-f]{16}"));
    }

    @Test
    void successfulCallsAreNeverCounted() {
        var d = new ToolLoopDetector();
        for (int i = 0; i < 100; i++) {
            assertEquals(Verdict.OK, d.record("Read", args("same.txt"), false));
        }
    }

    @Test
    void threeIdenticalFailuresNudge() {
        var d = new ToolLoopDetector();
        assertEquals(Verdict.OK, d.record("Edit", args("f.txt"), true));
        assertEquals(Verdict.OK, d.record("Edit", args("f.txt"), true));
        assertEquals(Verdict.NUDGE, d.record("Edit", args("f.txt"), true));
    }

    @Test
    void oscillatingFailuresAreCaughtByWindowCount() {
        var d = new ToolLoopDetector();
        // A B A B A — A appears 3 times within the window → NUDGE on A's 3rd.
        assertEquals(Verdict.OK, d.record("Edit", args("A"), true));   // A#1
        assertEquals(Verdict.OK, d.record("Edit", args("B"), true));   // B#1
        assertEquals(Verdict.OK, d.record("Edit", args("A"), true));   // A#2
        assertEquals(Verdict.OK, d.record("Edit", args("B"), true));   // B#2
        assertEquals(Verdict.NUDGE, d.record("Edit", args("A"), true)); // A#3 → NUDGE
    }

    @Test
    void nudgeThenReAccumulateAborts() {
        var d = new ToolLoopDetector();
        assertEquals(Verdict.OK, d.record("Edit", args("f.txt"), true));
        assertEquals(Verdict.OK, d.record("Edit", args("f.txt"), true));
        assertEquals(Verdict.NUDGE, d.record("Edit", args("f.txt"), true));
        // Window occupancy for this signature was cleared → must re-accumulate.
        assertEquals(Verdict.OK, d.record("Edit", args("f.txt"), true));
        assertEquals(Verdict.OK, d.record("Edit", args("f.txt"), true));
        assertEquals(Verdict.ABORT, d.record("Edit", args("f.txt"), true));
    }

    @Test
    void distinctSignaturesNeverTrip() {
        var d = new ToolLoopDetector();
        for (int i = 0; i < 20; i++) {
            assertEquals(Verdict.OK, d.record("Edit", args("file" + i + ".txt"), true),
                    "distinct args must never trip the detector");
        }
    }
}
