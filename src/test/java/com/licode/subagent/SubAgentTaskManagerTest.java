package com.licode.subagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentTaskManagerTest {

    @Test
    void createTask_shouldStartInPendingState() {
        var manager = new SubAgentTaskManager();
        String id = manager.createTask("test task");
        var task = manager.getTask(id);
        assertNotNull(task);
        assertEquals("test task", task.name());
        assertEquals(SubAgentTaskManager.TaskStatus.PENDING, task.status());
    }

    @Test
    void setRunning_shouldTransitionToRunning() {
        var manager = new SubAgentTaskManager();
        String id = manager.createTask("test");
        manager.setRunning(id, Thread.currentThread());
        assertEquals(SubAgentTaskManager.TaskStatus.RUNNING, manager.getTask(id).status());
    }

    @Test
    void setCompleted_shouldTransitionToCompleted() {
        var manager = new SubAgentTaskManager();
        String id = manager.createTask("test");
        manager.setRunning(id, Thread.currentThread());
        manager.setCompleted(id, "done output", 100, 50, 5000);
        assertEquals(SubAgentTaskManager.TaskStatus.COMPLETED, manager.getTask(id).status());
        assertEquals("done output", manager.getTask(id).output());
        assertEquals(100, manager.getTask(id).inputTokens());
        assertEquals(50, manager.getTask(id).outputTokens());
        assertEquals(5000, manager.getTask(id).elapsedMs());
    }

    @Test
    void setFailed_shouldTransitionToFailed() {
        var manager = new SubAgentTaskManager();
        String id = manager.createTask("test");
        manager.setRunning(id, Thread.currentThread());
        manager.setFailed(id, "something went wrong");
        assertEquals(SubAgentTaskManager.TaskStatus.FAILED, manager.getTask(id).status());
        assertEquals("something went wrong", manager.getTask(id).error());
    }

    @Test
    void cancelTask_shouldCancelRunningTask() {
        var manager = new SubAgentTaskManager();
        String id = manager.createTask("test");
        manager.setRunning(id, Thread.currentThread());
        manager.cancelTask(id);
        assertEquals(SubAgentTaskManager.TaskStatus.CANCELLED, manager.getTask(id).status());
    }

    @Test
    void drainNotifications_shouldReturnAndClear() {
        var manager = new SubAgentTaskManager();
        String id = manager.createTask("test");
        manager.setRunning(id, Thread.currentThread());
        manager.setCompleted(id, "done", 0, 0, 100);

        var first = manager.drainNotifications();
        assertEquals(1, first.size());
        assertEquals("done", first.get(0).output());

        var second = manager.drainNotifications();
        assertTrue(second.isEmpty(), "Second drain should return empty list");
    }

    @Test
    void listTasks_shouldReturnAllTasks() {
        var manager = new SubAgentTaskManager();
        manager.createTask("task1");
        manager.createTask("task2");
        var list = manager.listTasks();
        assertEquals(2, list.size());
    }

    @Test
    void getTask_nonexistent_shouldReturnNull() {
        var manager = new SubAgentTaskManager();
        assertNull(manager.getTask("nonexistent"));
    }
}
