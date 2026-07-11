package com.licode.agent;

import com.licode.conversation.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoalCheckpointTest {

    @Test
    void shouldCheckFiresOnEveryInterval() {
        assertFalse(GoalCheckpoint.shouldCheck(0));
        assertFalse(GoalCheckpoint.shouldCheck(9));
        assertTrue(GoalCheckpoint.shouldCheck(10));
        assertFalse(GoalCheckpoint.shouldCheck(11));
        assertTrue(GoalCheckpoint.shouldCheck(20));
    }

    @Test
    void captureGoalTakesLatestRealUserMessage() {
        var msgs = List.of(
                Message.user("Fix the login NPE in UserService"),
                Message.assistant("ok"),
                Message.user("<system-reminder>\n<goal-check>...</goal-check>\n</system-reminder>"));
        assertEquals("Fix the login NPE in UserService", GoalCheckpoint.captureGoal(msgs));
    }

    @Test
    void captureGoalSkipsSystemRemindersAndEmpty() {
        var toolResult = new Message("user", ""); // tool-result style: empty content
        var msgs = List.of(
                Message.user("Refactor the payment module"),
                Message.user("<system-reminder>\n## Auto Memory\n</system-reminder>"),
                toolResult);
        assertEquals("Refactor the payment module", GoalCheckpoint.captureGoal(msgs));
    }

    @Test
    void captureGoalReturnsNullWhenNoRealUserMessage() {
        var msgs = List.of(
                Message.assistant("hi"),
                Message.user("<system-reminder>\nx\n</system-reminder>"));
        assertNull(GoalCheckpoint.captureGoal(msgs));
    }

    @Test
    void captureGoalTruncatesLongGoal() {
        String longGoal = "a".repeat(1000);
        String captured = GoalCheckpoint.captureGoal(List.of(Message.user(longGoal)));
        assertNotNull(captured);
        assertTrue(captured.length() <= GoalCheckpoint.MAX_GOAL_CHARS + 4, "should truncate to ~MAX_GOAL_CHARS");
        assertTrue(captured.endsWith("…"));
    }

    @Test
    void buildReminderContainsTagGoalAndIteration() {
        String r = GoalCheckpoint.buildReminder("Fix the NPE", 10);
        assertTrue(r.contains("<goal-check>"));
        assertTrue(r.contains("</goal-check>"));
        assertTrue(r.contains("Fix the NPE"));
        assertTrue(r.contains("10"));
    }
}
