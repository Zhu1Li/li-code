package com.licode.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskList {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<List<Task>> LIST_TYPE = new TypeReference<>() {};

    private final Path filePath;
    private final List<Task> tasks;
    private final AtomicInteger nextId = new AtomicInteger(1);

    public TaskList(String listId, Path workDir) {
        Path taskDir = workDir.resolve(".licode").resolve("tasks");
        this.filePath = taskDir.resolve(listId + ".json");
        this.tasks = load();
        int maxId = 0;
        for (Task t : tasks) {
            if (t.id > maxId) maxId = t.id;
        }
        nextId.set(maxId + 1);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Task {
        public int id;
        public String subject;
        public String description;
        public String status;  // PENDING, IN_PROGRESS, COMPLETED
        public String activeForm;
        public Map<String, Object> metadata;
        public List<Integer> blocks;
        public List<Integer> blockedBy;

        public Task() {}

        public Task(int id, String subject, String description, String status, String activeForm) {
            this.id = id;
            this.subject = subject;
            this.description = description;
            this.status = status;
            this.activeForm = activeForm;
            this.metadata = new LinkedHashMap<>();
            this.blocks = new ArrayList<>();
            this.blockedBy = new ArrayList<>();
        }

        public int id() { return id; }
        public String subject() { return subject; }
        public String description() { return description; }
        public String status() { return status; }
        public String activeForm() { return activeForm; }
        public Map<String, Object> metadata() { return metadata; }
        public List<Integer> blocks() { return blocks; }
        public List<Integer> blockedBy() { return blockedBy; }
    }

    public synchronized Task create(String subject, String description) {
        int id = nextId.getAndIncrement();
        Task task = new Task(id, subject, description, "PENDING", subject);
        tasks.add(task);
        save();
        return task;
    }

    public synchronized Task get(int id) {
        return tasks.stream().filter(t -> t.id == id).findFirst().orElse(null);
    }

    public synchronized List<Task> list() {
        return List.copyOf(tasks);
    }

    public record UpdateResult(int id, List<String> changedFields) {}

    public synchronized UpdateResult update(int id, String status, String subject,
                                              String description, String activeForm,
                                              List<Integer> addBlocks, List<Integer> addBlockedBy,
                                              Map<String, Object> mergeMetadata) {
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t.id == id) {
                var changed = new ArrayList<String>();
                if (status != null) { t.status = status; changed.add("status"); }
                if (subject != null) { t.subject = subject; changed.add("subject"); }
                if (description != null) { t.description = description; changed.add("description"); }
                if (activeForm != null) { t.activeForm = activeForm; changed.add("activeForm"); }
                if (addBlocks != null && !addBlocks.isEmpty()) {
                    t.blocks.addAll(addBlocks); changed.add("blocks");
                }
                if (addBlockedBy != null && !addBlockedBy.isEmpty()) {
                    t.blockedBy.addAll(addBlockedBy); changed.add("blockedBy");
                }
                if (mergeMetadata != null && !mergeMetadata.isEmpty()) {
                    t.metadata.putAll(mergeMetadata); changed.add("metadata");
                }
                if ("deleted".equals(status)) {
                    tasks.remove(i);
                }
                save();
                return new UpdateResult(id, changed);
            }
        }
        return null;
    }

    private List<Task> load() {
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
