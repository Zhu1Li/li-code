package com.licode.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.licode.conversation.Message;
import com.licode.conversation.ThinkingBlock;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * Manages session persistence: JSONL append-only writes, per-session
 * .meta.json files, compaction boundaries, and session recovery.
 */
public class SessionManager {

    static final String SESSIONS_DIR = ".licode/sessions";
    static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.INDENT_OUTPUT, false);

    private final Path sessionsDir;

    public SessionManager(Path workDir) {
        this.sessionsDir = workDir.resolve(SESSIONS_DIR);
        try { Files.createDirectories(sessionsDir); } catch (IOException ignored) {}
    }

    // ── Records ──────────────────────────────────────────────────────

    public record SessionInfo(
            @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
            @com.fasterxml.jackson.annotation.JsonProperty("firstMessage") String firstMessage,
            @com.fasterxml.jackson.annotation.JsonProperty("messageCount") int messageCount,
            @com.fasterxml.jackson.annotation.JsonProperty("fileSize") long fileSize,
            @com.fasterxml.jackson.annotation.JsonProperty("gitBranch") String gitBranch,
            @com.fasterxml.jackson.annotation.JsonProperty("modTime") Instant modTime
    ) {}

    public record SessionMessage(
            String role,
            String content,
            String timestamp,
            String type,
            List<Integer> keepIndices,
            List<ToolUseSer> toolUses,
            List<ToolResultSer> toolResults,
            List<ThinkingBlockSer> thinkingBlocks
    ) {
        public SessionMessage {
            if (type == null || type.isEmpty()) type = null;
        }
    }

    public record ToolUseSer(String id, String name, java.util.Map<String, Object> arguments) {}
    public record ToolResultSer(String toolUseId, String content, boolean isError) {}
    public record ThinkingBlockSer(String thinking, String signature) {}

    public record RebuildResult(List<Message> messages, boolean compacted, Instant lastActiveTime) {}

    // ── Session lifecycle ────────────────────────────────────────────

    private long sessionCounter = System.currentTimeMillis();

    public String createSession() {
        long ts = System.currentTimeMillis();
        // Ensure monotonically increasing IDs even within the same millisecond
        if (ts <= sessionCounter) ts = sessionCounter + 1;
        sessionCounter = ts;
        return java.time.Instant.ofEpochMilli(ts)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + (ts % 1000);
    }

    // ── Write ────────────────────────────────────────────────────────

    public void saveMessage(String sessionId, Message msg) {
        var sm = toSessionMessage(msg);
        appendJsonLine(sessionId, sm);
    }

    public void saveCompactBoundary(String sessionId, String summary, List<Integer> keepIndices) {
        var sm = new SessionMessage(
                "user", summary, LocalDateTime.now().format(TIMESTAMP_FMT),
                "compact_boundary", keepIndices, null, null, null
        );
        appendJsonLine(sessionId, sm);
    }

    public void saveMeta(String sessionId, SessionInfo info) {
        Path metaFile = sessionsDir.resolve(sessionId + ".meta.json");
        try {
            Files.createDirectories(metaFile.getParent());
            String json = toMetaJson(info);
            Files.writeString(metaFile, json);
        } catch (IOException ignored) {}
    }

    public void updateMeta(String sessionId, int messageCount, long fileSize) {
        Path metaFile = sessionsDir.resolve(sessionId + ".meta.json");
        try {
            SessionInfo old = readMetaJson(metaFile);
            if (old == null) return;
            var updated = new SessionInfo(
                    old.id, old.firstMessage, messageCount, fileSize,
                    old.gitBranch, Instant.now()
            );
            Files.writeString(metaFile, toMetaJson(updated));
        } catch (IOException ignored) {}
    }

    // ── Read ─────────────────────────────────────────────────────────

    public List<SessionInfo> listSessions(String keyword, LocalDate from, LocalDate to) {
        var results = new ArrayList<SessionInfo>();
        try (var files = Files.list(sessionsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".meta.json"))
                    .forEach(p -> {
                        try {
                            var info = readMetaJson(p);
                            if (info == null) return;
                            if (keyword != null && !keyword.isBlank()) {
                                String fm = info.firstMessage != null ? info.firstMessage : "";
                                if (!fm.toLowerCase().contains(keyword.toLowerCase())) return;
                            }
                            if (from != null && info.modTime != null) {
                                LocalDate modDate = info.modTime.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                                if (modDate.isBefore(from)) return;
                            }
                            if (to != null && info.modTime != null) {
                                LocalDate modDate = info.modTime.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                                if (modDate.isAfter(to)) return;
                            }
                            results.add(info);
                        } catch (Exception ignored) {}
                    });
        } catch (IOException ignored) {}
        results.sort(Comparator.comparing(SessionInfo::modTime, Comparator.nullsLast(Comparator.reverseOrder())));
        return results;
    }

    public SessionInfo getSession(String sessionId) {
        Path metaFile = sessionsDir.resolve(sessionId + ".meta.json");
        return readMetaJson(metaFile);
    }

    // ── Manual JSON for SessionInfo (avoids Jackson record compat) ──

    private static String toMetaJson(SessionInfo info) {
        return String.format(
                "{\"id\":\"%s\",\"firstMessage\":\"%s\",\"messageCount\":%d,\"fileSize\":%d,\"gitBranch\":\"%s\",\"modTime\":\"%s\"}",
                esc(info.id()), esc(info.firstMessage()), info.messageCount(), info.fileSize(),
                esc(info.gitBranch()), info.modTime() != null ? info.modTime().toString() : ""
        );
    }

    private static SessionInfo readMetaJson(Path file) {
        try {
            String raw = Files.readString(file).strip();
            var node = MAPPER.readTree(raw);
            String id = node.has("id") ? node.get("id").asText() : "";
            String firstMsg = node.has("firstMessage") ? node.get("firstMessage").asText() : "";
            int msgCount = node.has("messageCount") ? node.get("messageCount").asInt() : 0;
            long fileSize = node.has("fileSize") ? node.get("fileSize").asLong() : 0L;
            String branch = node.has("gitBranch") ? node.get("gitBranch").asText() : "";
            Instant modTime = null;
            if (node.has("modTime") && !node.get("modTime").asText().isEmpty()) {
                try { modTime = Instant.parse(node.get("modTime").asText()); } catch (Exception ignored) {}
            }
            return new SessionInfo(id, firstMsg, msgCount, fileSize, branch, modTime);
        } catch (IOException e) {
            return null;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public List<SessionMessage> loadSession(String sessionId) {
        Path jsonlFile = sessionsDir.resolve(sessionId + ".jsonl");
        var messages = new ArrayList<SessionMessage>();
        if (!Files.exists(jsonlFile)) return messages;

        try {
            var lines = Files.readAllLines(jsonlFile);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty()) continue;
                try {
                    var sm = MAPPER.readValue(line, SessionMessage.class);
                    if (sm != null) messages.add(sm);
                } catch (JsonProcessingException e) {
                    System.err.println("[LiCode] Session JSONL parse error at line "
                            + (i + 1) + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[LiCode] Session read error: " + e.getMessage());
        }
        return messages;
    }

    public boolean deleteSession(String sessionId) {
        try {
            Files.deleteIfExists(sessionsDir.resolve(sessionId + ".jsonl"));
            Files.deleteIfExists(sessionsDir.resolve(sessionId + ".meta.json"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── Rebuild ──────────────────────────────────────────────────────

    /**
     * Rebuilds a conversation from raw session messages.
     * Handles: incomplete tool_use truncation, compaction boundary reconstruction.
     */
    public RebuildResult rebuildConversation(List<SessionMessage> rawMessages) {
        if (rawMessages.isEmpty()) return new RebuildResult(List.of(), false, null);

        var messages = new ArrayList<>(rawMessages);

        // Truncate: remove last line if it ends with unpaired tool_use
        messages = truncateToLastCompleteRound(messages);

        // Find last compaction boundary
        int boundaryIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("compact_boundary".equals(messages.get(i).type)) {
                boundaryIdx = i;
                break;
            }
        }

        Instant lastActive = null;
        if (!messages.isEmpty()) {
            SessionMessage last = messages.get(messages.size() - 1);
            lastActive = LocalDateTime.parse(last.timestamp, TIMESTAMP_FMT)
                    .atZone(ZoneId.systemDefault()).toInstant();
        }

        List<Message> conversation;
        if (boundaryIdx >= 0) {
            conversation = rebuildWithBoundary(messages, boundaryIdx);
            return new RebuildResult(conversation, true, lastActive);
        } else {
            conversation = messagesToConversation(messages);
            return new RebuildResult(conversation, false, lastActive);
        }
    }

    private ArrayList<SessionMessage> truncateToLastCompleteRound(ArrayList<SessionMessage> messages) {
        if (messages.isEmpty()) return messages;
        int end = messages.size();

        // Check if last message is an assistant with toolUses that lack toolResult.
        // Must verify ALL tool_use ids have matching tool_results — not just any one.
        for (int i = end - 1; i >= 0; i--) {
            SessionMessage sm = messages.get(i);
            if (sm.toolUses != null && !sm.toolUses.isEmpty()) {
                boolean allMatched = false;
                for (int j = i + 1; j < messages.size(); j++) {
                    SessionMessage next = messages.get(j);
                    if (next.toolResults != null && !next.toolResults.isEmpty()) {
                        int matchCount = 0;
                        for (var tu : sm.toolUses) {
                            for (var tr : next.toolResults) {
                                if (tu.id.equals(tr.toolUseId)) {
                                    matchCount++;
                                    break;
                                }
                            }
                        }
                        if (matchCount == sm.toolUses.size()) {
                            allMatched = true;
                            break;
                        }
                    }
                }
                if (!allMatched) {
                    end = i;
                    break;
                }
            }
        }

        return new ArrayList<>(messages.subList(0, end));
    }

    private List<Message> rebuildWithBoundary(ArrayList<SessionMessage> messages, int boundaryIdx) {
        SessionMessage boundary = messages.get(boundaryIdx);
        var result = new ArrayList<Message>();

        // Insert summary as user message + assistant ack
        result.add(Message.user(boundary.content));
        result.add(Message.assistant("Understood, I'll continue from this summary."));

        // Kept tail: messages at keepIndices positions (from the original message list)
        if (boundary.keepIndices != null && !boundary.keepIndices.isEmpty()) {
            for (int idx : boundary.keepIndices) {
                if (idx >= 0 && idx < boundaryIdx) {
                    SessionMessage kept = messages.get(idx);
                    result.addAll(messagesToConversation(List.of(kept)));
                }
            }
        }

        // Replay messages after boundary (excluding the boundary line itself)
        var postBoundary = messages.subList(boundaryIdx + 1, messages.size());
        result.addAll(messagesToConversation(postBoundary));

        return result;
    }

    private List<Message> messagesToConversation(List<SessionMessage> sessionMessages) {
        var result = new ArrayList<Message>();
        for (var sm : sessionMessages) {
            if ("compact_boundary".equals(sm.type)) continue;
            Message msg = new Message(sm.role, sm.content);
            if (sm.toolUses != null) {
                msg.setToolUses(sm.toolUses.stream()
                        .map(tu -> new ToolUseBlock(tu.id, tu.name, tu.arguments))
                        .toList());
            }
            if (sm.toolResults != null) {
                msg.setToolResults(sm.toolResults.stream()
                        .map(tr -> new ToolResultBlock(tr.toolUseId, tr.content, tr.isError))
                        .toList());
            }
            if (sm.thinkingBlocks != null) {
                msg.setThinkingBlocks(sm.thinkingBlocks.stream()
                        .map(tb -> new ThinkingBlock(tb.thinking(), tb.signature()))
                        .toList());
            }
            result.add(msg);
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    public static int daysSince(Instant instant) {
        if (instant == null) return 0;
        return (int) java.time.Duration.between(instant, Instant.now()).toDays();
    }

    SessionMessage toSessionMessage(Message msg) {
        List<ToolUseSer> uses = null;
        if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
            uses = msg.getToolUses().stream()
                    .map(tu -> new ToolUseSer(tu.toolUseId(), tu.toolName(), tu.arguments()))
                    .toList();
        }
        List<ToolResultSer> results = null;
        if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
            results = msg.getToolResults().stream()
                    .map(tr -> new ToolResultSer(tr.toolUseId(), tr.content(), tr.isError()))
                    .toList();
        }
        List<ThinkingBlockSer> thinkings = null;
        if (msg.getThinkingBlocks() != null && !msg.getThinkingBlocks().isEmpty()) {
            thinkings = msg.getThinkingBlocks().stream()
                    .map(tb -> new ThinkingBlockSer(tb.thinking(), tb.signature()))
                    .toList();
        }
        return new SessionMessage(
                msg.getRole(), msg.getContent(),
                LocalDateTime.now().format(TIMESTAMP_FMT), null, null, uses, results, thinkings
        );
    }

    private void appendJsonLine(String sessionId, Object obj) {
        String json = toJsonLine(obj);
        if (json != null) appendRawLine(sessionId, json);
    }

    private void appendRawLine(String sessionId, String jsonLine) {
        try {
            Files.writeString(
                    sessionsDir.resolve(sessionId + ".jsonl"),
                    jsonLine + "\n", CREATE, APPEND
            );
        } catch (IOException ignored) {}
    }

    private String toJsonLine(Object obj) {
        if (obj instanceof SessionMessage sm) {
            return sessionMessageToJson(sm);
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            System.err.println("[LiCode] Session serialize error: " + e.getMessage());
            return null;
        }
    }

    private String sessionMessageToJson(SessionMessage sm) {
        var sb = new StringBuilder();
        sb.append("{\"role\":\"").append(esc(sm.role())).append("\"");
        if (sm.content() != null) {
            sb.append(",\"content\":\"").append(esc(sm.content())).append("\"");
        }
        sb.append(",\"timestamp\":\"").append(esc(sm.timestamp())).append("\"");
        if (sm.type() != null) {
            sb.append(",\"type\":\"").append(esc(sm.type())).append("\"");
        }
        if (sm.keepIndices() != null && !sm.keepIndices().isEmpty()) {
            sb.append(",\"keepIndices\":");
            sb.append(sm.keepIndices().toString().replace(" ", ""));
        }
        if (sm.toolUses() != null && !sm.toolUses().isEmpty()) {
            sb.append(",\"toolUses\":[");
            for (int i = 0; i < sm.toolUses().size(); i++) {
                if (i > 0) sb.append(",");
                var tu = sm.toolUses().get(i);
                sb.append("{\"id\":\"").append(esc(tu.id()));
                sb.append("\",\"name\":\"").append(esc(tu.name()));
                sb.append("\",\"arguments\":");
                try {
                    sb.append(MAPPER.writeValueAsString(tu.arguments()));
                } catch (JsonProcessingException e) {
                    sb.append("{}");
                }
                sb.append("}");
            }
            sb.append("]");
        }
        if (sm.toolResults() != null && !sm.toolResults().isEmpty()) {
            sb.append(",\"toolResults\":[");
            for (int i = 0; i < sm.toolResults().size(); i++) {
                if (i > 0) sb.append(",");
                var tr = sm.toolResults().get(i);
                sb.append("{\"toolUseId\":\"").append(esc(tr.toolUseId()));
                sb.append("\",\"content\":\"").append(esc(tr.content()));
                sb.append("\",\"isError\":").append(tr.isError()).append("}");
            }
            sb.append("]");
        }
        if (sm.thinkingBlocks() != null && !sm.thinkingBlocks().isEmpty()) {
            sb.append(",\"thinkingBlocks\":[");
            for (int i = 0; i < sm.thinkingBlocks().size(); i++) {
                if (i > 0) sb.append(",");
                var tb = sm.thinkingBlocks().get(i);
                sb.append("{\"thinking\":\"").append(esc(tb.thinking()));
                sb.append("\",\"signature\":\"").append(esc(tb.signature())).append("\"}");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }
}
