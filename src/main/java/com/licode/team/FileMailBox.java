package com.licode.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class FileMailBox {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<MailMessage>> LIST_TYPE = new TypeReference<>() {};
    private static final int MAX_RETRIES = 20;
    private static final long MIN_SLEEP_MS = 2;
    private static final long MAX_SLEEP_MS = 15;
    private static final long STALE_LOCK_MS = 10_000;

    private static final ConcurrentHashMap<String, Object> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    private final Path baseDir;
    private final Random random = new Random();

    public FileMailBox(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {
        }
    }

    public void send(String recipient, MailMessage message) throws IOException {
        MailMessage stored = message.read() ? message.withRead(false) : message;
        withLock(recipient, inbox -> {
            inbox.add(stored);
            return inbox;
        });
    }

    public List<MailMessage> readUnread(String agentId) throws IOException {
        List<MailMessage> inbox = readInbox(agentId);
        if (inbox.isEmpty()) return List.of();
        return inbox.stream().filter(m -> !m.read()).toList();
    }

    public void markAllRead(String agentId) throws IOException {
        withLock(agentId, inbox -> {
            List<MailMessage> updated = new ArrayList<>();
            for (MailMessage msg : inbox) {
                updated.add(msg.withRead(true));
            }
            return updated;
        });
    }

    Path inboxPath(String agentId) {
        return baseDir.resolve(agentId + ".json");
    }

    Path lockPath(String agentId) {
        return baseDir.resolve(agentId + ".json.lock");
    }

    private void withLock(String agentId, Function<List<MailMessage>, List<MailMessage>> fn) throws IOException {
        Object inProcessLock = IN_PROCESS_LOCKS.computeIfAbsent(agentId, k -> new Object());
        synchronized (inProcessLock) {
            java.io.File lockFile = lockPath(agentId).toFile();
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                if (lockFile.createNewFile()) {
                    try {
                        List<MailMessage> inbox = readInbox(agentId);
                        List<MailMessage> updated = fn.apply(inbox);
                        writeInbox(agentId, updated);
                        return;
                    } finally {
                        lockFile.delete();
                    }
                }
                if (isStaleLock(lockPath(agentId))) {
                    lockFile.delete();
                }
                try {
                    Thread.sleep(MIN_SLEEP_MS + random.nextLong(MAX_SLEEP_MS - MIN_SLEEP_MS));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for lock on " + agentId);
                }
            }
            throw new IOException("Failed to acquire lock for " + agentId + " after " + MAX_RETRIES + " retries");
        }
    }

    private boolean isStaleLock(Path lockFile) {
        try {
            return Files.getLastModifiedTime(lockFile).toInstant()
                    .isBefore(Instant.now().minusMillis(STALE_LOCK_MS));
        } catch (IOException e) {
            return true;
        }
    }

    List<MailMessage> readInbox(String agentId) throws IOException {
        Path file = inboxPath(agentId);
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length == 0) return new ArrayList<>();
            return MAPPER.readValue(data, LIST_TYPE);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    void writeInbox(String agentId, List<MailMessage> inbox) throws IOException {
        MAPPER.writeValue(inboxPath(agentId).toFile(), inbox);
    }
}
