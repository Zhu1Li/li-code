package com.licode.team;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SharedTaskStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<List<SharedTask>> LIST_TYPE = new TypeReference<>() {};

    private final Path filePath;
    private final List<SharedTask> tasks;
    private final AtomicInteger nextId = new AtomicInteger(1);

    public SharedTaskStore(Path teamDir) {
        this.filePath = teamDir.resolve("tasks.json");
        this.tasks = load();
        int maxId = 0;
        for (SharedTask t : tasks) {
            if (t.id() > maxId) maxId = t.id();
        }
        nextId.set(maxId + 1);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SharedTask(
            int id,
            String title,
            String description,
            String status,
            String assignee,
            List<Integer> blocks,
            List<Integer> blockedBy,
            String createdBy
    ) {
        public SharedTask withStatus(String newStatus) {
            return new SharedTask(id, title, description, newStatus, assignee, blocks, blockedBy, createdBy);
        }

        public SharedTask withAssignee(String newAssignee) {
            return new SharedTask(id, title, description, status, newAssignee, blocks, blockedBy, createdBy);
        }

        public SharedTask withAddedBlocks(List<Integer> newBlocks) {
            List<Integer> merged = new ArrayList<>(blocks != null ? blocks : List.of());
            if (newBlocks != null) merged.addAll(newBlocks);
            return new SharedTask(id, title, description, status, assignee, merged, blockedBy, createdBy);
        }

        public SharedTask withAddedBlockedBy(List<Integer> newBlockedBy) {
            List<Integer> merged = new ArrayList<>(blockedBy != null ? blockedBy : List.of());
            if (newBlockedBy != null) merged.addAll(newBlockedBy);
            return new SharedTask(id, title, description, status, assignee, blocks, merged, createdBy);
        }
    }

    public synchronized SharedTask create(String title, String description, String assignee, String createdBy) {
        int id = nextId.getAndIncrement();
        SharedTask task = new SharedTask(id, title, description, "pending", assignee, List.of(), List.of(), createdBy);
        tasks.add(task);
        save();
        return task;
    }

    public synchronized SharedTask get(int id) {
        return tasks.stream().filter(t -> t.id() == id).findFirst().orElse(null);
    }

    public synchronized List<SharedTask> listTasks() {
        return List.copyOf(tasks);
    }

    public synchronized List<SharedTask> listTasks(String status, String assignee) {
        return tasks.stream()
                .filter(t -> status == null || status.equals(t.status()))
                .filter(t -> assignee == null || assignee.equals(t.assignee()))
                .toList();
    }

    public synchronized SharedTask update(int id, String status, String assignee,
                                           List<Integer> addBlocks, List<Integer> addBlockedBy) {
        for (int i = 0; i < tasks.size(); i++) {
            SharedTask t = tasks.get(i);
            if (t.id() == id) {
                SharedTask updated = t;
                if (status != null) updated = updated.withStatus(status);
                if (assignee != null) updated = updated.withAssignee(assignee);
                if (addBlocks != null && !addBlocks.isEmpty()) updated = updated.withAddedBlocks(addBlocks);
                if (addBlockedBy != null && !addBlockedBy.isEmpty()) updated = updated.withAddedBlockedBy(addBlockedBy);
                tasks.set(i, updated);
                save();
                return updated;
            }
        }
        return null;
    }

    private List<SharedTask> load() {
        if (!Files.exists(filePath)) return new ArrayList<>();
        try {
            byte[] data = Files.readAllBytes(filePath);
            if (data.length == 0) return new ArrayList<>();
            return MAPPER.readValue(data, LIST_TYPE);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            MAPPER.writeValue(filePath.toFile(), tasks);
        } catch (IOException ignored) {
        }
    }
}
