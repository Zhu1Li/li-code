package com.licode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedTaskStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void createTask() throws IOException {
        var store = new SharedTaskStore(tempDir);
        var task = store.create("Test task", "A test description", "alice", "lead");
        assertEquals(1, task.id());
        assertEquals("Test task", task.title());
        assertEquals("A test description", task.description());
        assertEquals("pending", task.status());
        assertEquals("alice", task.assignee());
        assertEquals("lead", task.createdBy());
        assertTrue(task.blocks().isEmpty());
        assertTrue(task.blockedBy().isEmpty());
    }

    @Test
    void autoIncrementIds() throws IOException {
        var store = new SharedTaskStore(tempDir);
        var t1 = store.create("Task 1", "desc", "alice", "lead");
        var t2 = store.create("Task 2", "desc", "bob", "lead");
        var t3 = store.create("Task 3", "desc", "charlie", "lead");
        assertEquals(1, t1.id());
        assertEquals(2, t2.id());
        assertEquals(3, t3.id());
    }

    @Test
    void getTask() throws IOException {
        var store = new SharedTaskStore(tempDir);
        var created = store.create("Test", "desc", "alice", "lead");
        var retrieved = store.get(created.id());
        assertNotNull(retrieved);
        assertEquals(created.id(), retrieved.id());
        assertEquals("Test", retrieved.title());
    }

    @Test
    void getNonexistentTask() throws IOException {
        var store = new SharedTaskStore(tempDir);
        assertNull(store.get(999));
    }

    @Test
    void listTasks() throws IOException {
        var store = new SharedTaskStore(tempDir);
        store.create("Task 1", "desc", "alice", "lead");
        store.create("Task 2", "desc", "bob", "lead");
        var all = store.listTasks();
        assertEquals(2, all.size());
    }

    @Test
    void updateStatus() throws IOException {
        var store = new SharedTaskStore(tempDir);
        var task = store.create("Test", "desc", "alice", "lead");
        var updated = store.update(task.id(), "in_progress", null, null, null);
        assertNotNull(updated);
        assertEquals("in_progress", updated.status());
    }

    @Test
    void updateAssignee() throws IOException {
        var store = new SharedTaskStore(tempDir);
        var task = store.create("Test", "desc", "alice", "lead");
        var updated = store.update(task.id(), null, "bob", null, null);
        assertNotNull(updated);
        assertEquals("bob", updated.assignee());
    }

    @Test
    void addDependencies() throws IOException {
        var store = new SharedTaskStore(tempDir);
        var t1 = store.create("Task 1", "desc", "alice", "lead");
        var t2 = store.create("Task 2", "desc", "bob", "lead");
        store.update(t2.id(), null, null, null, List.of(t1.id()));
        store.update(t1.id(), null, null, List.of(t2.id()), null);
        var t2After = store.get(t2.id());
        assertTrue(t2After.blockedBy().contains(t1.id()));
    }

    @Test
    void updateNonexistentReturnsNull() throws IOException {
        var store = new SharedTaskStore(tempDir);
        assertNull(store.update(999, "completed", null, null, null));
    }

    @Test
    void persistenceSurvivesReload() throws IOException {
        var store1 = new SharedTaskStore(tempDir);
        var task = store1.create("Persist", "test persistence", "alice", "lead");
        store1.update(task.id(), "in_progress", null, null, null);

        var store2 = new SharedTaskStore(tempDir);
        var reloaded = store2.get(task.id());
        assertNotNull(reloaded, "task should survive reload from disk");
        assertEquals("Persist", reloaded.title());
        assertEquals("in_progress", reloaded.status());
    }
}
