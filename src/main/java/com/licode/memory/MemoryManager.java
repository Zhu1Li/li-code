package com.licode.memory;

import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.llm.LlmClient;
import com.licode.llm.StreamEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-memory system with 5-type classification, dual-path storage,
 * LLM extraction, and staleness tracking.
 *
 * <p>Memory types and their storage scopes:
 * <ul>
 *   <li>{@code user} / {@code feedback} → user-level ({@code ~/.licode/memory/})</li>
 *   <li>{@code project} / {@code reference} / {@code task} → project-level ({@code <workDir>/.licode/memory/})</li>
 * </ul>
 *
 * <p>Each memory is a standalone {@code .md} file with YAML frontmatter.
 * {@code MEMORY.md} in each directory serves as the index.
 */
public class MemoryManager {

    // ── Constants ────────────────────────────────────────────────────

    static final int EXTRACTION_INTERVAL = 5;
    static final String MEMORY_DIR = ".licode/memory";
    static final Set<String> USER_TYPES = Set.of("user", "feedback");
    static final Set<String> PROJECT_TYPES = Set.of("project", "reference", "task");
    static final Set<String> ALL_TYPES = Set.of("user", "feedback", "project", "reference", "task");
    static final int MAX_STALE_DAYS = 30;

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    // ── Fields ───────────────────────────────────────────────────────

    private final Path userMemoryDir;
    private final Path projectMemoryDir;
    private int turnCount;

    public MemoryManager(Path workDir) {
        Path userHome = Path.of(System.getProperty("user.home"));
        this.userMemoryDir = userHome.resolve(MEMORY_DIR);
        this.projectMemoryDir = workDir.resolve(MEMORY_DIR);
        this.turnCount = 0;
        ensureDirs();
    }

    // ── Public accessors ─────────────────────────────────────────────

    public Path getMemoryDir(String type) {
        return USER_TYPES.contains(type) ? userMemoryDir : projectMemoryDir;
    }

    public List<String> getMemories() {
        var summaries = new ArrayList<String>();
        for (var entry : loadIndex(userMemoryDir)) summaries.add(summaryLine(entry));
        for (var entry : loadIndex(projectMemoryDir)) summaries.add(summaryLine(entry));
        return summaries;
    }

    public boolean shouldExtract() {
        turnCount++;
        return turnCount % EXTRACTION_INTERVAL == 0;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public void clear() {
        try {
            clearDir(userMemoryDir);
            clearDir(projectMemoryDir);
        } catch (IOException ignored) {}
    }

    public void addManual(String content, String type) {
        addManual(content, type, null);
    }

    /**
     * 手动写入一条记忆。给定 slug 时以该 slug 作文件名（同 slug 覆盖更新，便于像
     * code-style profile 这样"重扫即覆盖"）；slug 为空则按内容首行派生。slug 会被
     * 清洗成安全字符（防止越权写到目录外）。类型必须属于既有 taxonomy。
     */
    public void addManual(String content, String type, String slug) {
        if (!ALL_TYPES.contains(type)) {
            System.err.println("[LiCode] Memory add rejected: unknown type -- " + type);
            return;
        }
        String name = (slug != null && !slug.isBlank()) ? sanitizeSlug(slug) : slugFromContent(content);
        writeMemoryFile(getMemoryDir(type), name, content, type);
    }

    // ── Injection ────────────────────────────────────────────────────

    /**
     * Injects auto-memory as a {@code ## Auto Memory} system-reminder block
     * at the start of the conversation. Only fires when conversation is empty.
     */
    public void injectMemories(ConversationManager conv) {
        if (conv == null) return;
        if (buildAutoMemoryBlock().isEmpty()) return;

        String block = buildAutoMemoryBlock();
        if (block.isEmpty()) return;

        conv.addSystemReminder("## Auto Memory\n\n" + block);
    }

    /**
     * Builds the full auto-memory text block for injection or display.
     */
    public String buildAutoMemoryBlock() {
        var sb = new StringBuilder();

        var userEntries = loadIndex(userMemoryDir);
        var projectEntries = loadIndex(projectMemoryDir);

        if (!userEntries.isEmpty()) {
            sb.append("### User Memories\n\n");
            for (var entry : userEntries) {
                appendEntryContent(sb, userMemoryDir, entry);
            }
        }
        if (!projectEntries.isEmpty()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("### Project Memories\n\n");
            for (var entry : projectEntries) {
                appendEntryContent(sb, projectMemoryDir, entry);
            }
        }

        return sb.toString().strip();
    }

    // ── Extraction ───────────────────────────────────────────────────

    /**
     * Queries the LLM to extract new memories from the current conversation.
     * Runs synchronously; caller should wrap in a virtual thread.
     */
    public void extract(LlmClient client, ConversationManager conv) {
        List<Message> messages = conv.getMessages();
        if (messages.size() < 4) return;

        String transcript = buildTranscript(messages);
        String prompt = buildExtractionPrompt();

        // Use a side conversation with just the extraction prompt
        var sideConv = new ConversationManager();
        sideConv.addUserMessage(prompt + "\n\n" + transcript);

        StringBuilder result = new StringBuilder();
        BlockingQueue<StreamEvent> queue = client.stream(sideConv, List.of());

        try {
            while (true) {
                StreamEvent event = queue.poll(120, TimeUnit.SECONDS);
                if (event == null) break;
                switch (event) {
                    case StreamEvent.TextDelta td -> result.append(td.text());
                    case StreamEvent.StreamEnd se -> { /* done */ }
                    case StreamEvent.Error e -> {
                        System.err.println("[LiCode] Memory extraction error: " + e.message());
                        return;
                    }
                    default -> {}
                }
                if (event instanceof StreamEvent.StreamEnd) break;
                if (event instanceof StreamEvent.Error) return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        String text = result.toString().strip();
        if (text.isEmpty()) return;

        Map<String, String> sections = parseTypedSections(text);
        if (sections.isEmpty()) return;

        boolean updated = false;
        for (var entry : sections.entrySet()) {
            String type = entry.getKey();
            String content = entry.getValue();
            if (!ALL_TYPES.contains(type)) {
                System.err.println("[LiCode] Memory: dropping unknown type — " + type);
                continue;
            }
            String name = slugFromContent(content);
            writeMemoryFile(getMemoryDir(type), name, content, type);
            updated = true;
        }

        if (updated) {
            // Refresh staleness marks
            refreshStaleness();
        }
    }

    // ── Staleness ────────────────────────────────────────────────────

    /**
     * Scans all memory files and marks stale entries in MEMORY.md indices.
     * Called after loading and after saving new memories.
     */
    void refreshStaleness() {
        refreshStalenessFor(userMemoryDir);
        refreshStalenessFor(projectMemoryDir);
    }

    // ── Internal: index management ───────────────────────────────────

    record IndexEntry(String title, String fileName, String hook, String type, Instant updatedAt) {}

    private List<IndexEntry> loadIndex(Path dir) {
        Path indexFile = dir.resolve("MEMORY.md");
        if (!Files.exists(indexFile)) return List.of();
        try {
            var entries = new ArrayList<IndexEntry>();
            for (String line : Files.readAllLines(indexFile)) {
                IndexEntry entry = parseIndexLine(line);
                if (entry != null) entries.add(entry);
            }
            return entries;
        } catch (IOException e) {
            return List.of();
        }
    }

    private IndexEntry parseIndexLine(String line) {
        // Format: - [Title](file.md) — one-line hook (stale)
        if (!line.startsWith("- [")) return null;
        int bracketEnd = line.indexOf("](");
        if (bracketEnd < 0) return null;
        int parenEnd = line.indexOf(")", bracketEnd);
        if (parenEnd < 0) return null;

        String title = line.substring(3, bracketEnd);
        String fileName = line.substring(bracketEnd + 2, parenEnd);
        String hook = "";
        int emDash = line.indexOf(" — ", parenEnd);
        if (emDash > 0) {
            hook = line.substring(emDash + 3).replace(" (stale)", "").strip();
        }
        return new IndexEntry(title, fileName, hook, null, null);
    }

    private void writeIndex(Path dir, List<IndexEntry> entries) {
        Path indexFile = dir.resolve("MEMORY.md");
        try {
            Files.createDirectories(dir);
            var sb = new StringBuilder();
            // Group by type
            for (String type : new String[]{"user", "feedback", "project", "reference", "task"}) {
                var typed = entries.stream()
                        .filter(e -> type.equals(e.type))
                        .toList();
                if (typed.isEmpty()) continue;
                sb.append("## ").append(capitalize(type)).append("\n\n");
                for (var e : typed) {
                    sb.append("- [").append(e.title).append("](").append(e.fileName).append(")");
                    if (e.hook != null && !e.hook.isEmpty()) {
                        sb.append(" — ").append(e.hook);
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
            Files.writeString(indexFile, sb.toString().strip() + "\n");
        } catch (IOException e) {
            System.err.println("[LiCode] Memory index write error: " + e.getMessage());
        }
    }

    // ── Internal: memory file I/O ────────────────────────────────────

    private void writeMemoryFile(Path dir, String name, String content, String type) {
        try {
            Files.createDirectories(dir);
            String fileName = name + ".md";
            String now = ISO.format(Instant.now());

            String body = "---\n"
                    + "name: " + name + "\n"
                    + "description: " + content.lines().findFirst().orElse("") + "\n"
                    + "metadata:\n"
                    + "  type: " + type + "\n"
                    + "  updated_at: " + now + "\n"
                    + "---\n\n"
                    + content;

            Files.writeString(dir.resolve(fileName), body);

            // Update MEMORY.md index
            var entries = new ArrayList<>(loadIndex(dir));
            // Remove existing entry with same fileName
            entries.removeIf(e -> e.fileName.equals(fileName));
            String hook = content.lines().findFirst().orElse("").strip();
            if (hook.length() > 100) hook = hook.substring(0, 100) + "...";
            entries.add(new IndexEntry(name, fileName, hook, type, Instant.now()));
            writeIndex(dir, entries);
        } catch (IOException e) {
            System.err.println("[LiCode] Memory file write error: " + e.getMessage());
        }
    }

    private void appendEntryContent(StringBuilder sb, Path dir, IndexEntry entry) {
        Path file = dir.resolve(entry.fileName);
        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file);
                // Strip YAML frontmatter
                Matcher m = FRONTMATTER_PATTERN.matcher(raw);
                if (m.find()) {
                    String body = m.group(2).strip();
                    // Check staleness from frontmatter
                    String fm = m.group(1);
                    boolean stale = false;
                    if (fm.contains("updated_at:")) {
                        String ts = fm.substring(fm.indexOf("updated_at:") + 12).trim();
                        ts = ts.split("\\s")[0]; // take first token (ISO timestamp)
                        try {
                            Instant updated = Instant.parse(ts);
                            long days = Duration.between(updated, Instant.now()).toDays();
                            if (days > MAX_STALE_DAYS) stale = true;
                        } catch (Exception ignored) {}
                    }
                    sb.append(body.strip());
                    if (stale) {
                        sb.append("\n> This memory is over ").append(MAX_STALE_DAYS)
                          .append(" days old and may be outdated.");
                    }
                } else {
                    sb.append(raw.strip());
                }
                sb.append("\n\n");
            }
        } catch (IOException ignored) {}
    }

    // ── Internal: staleness refresh ──────────────────────────────────

    private void refreshStalenessFor(Path dir) {
        var entries = loadIndex(dir);
        if (entries.isEmpty()) return;
        boolean changed = false;
        var updated = new ArrayList<IndexEntry>();
        Instant cutoff = Instant.now().minus(Duration.ofDays(MAX_STALE_DAYS));

        for (var entry : entries) {
            Path file = dir.resolve(entry.fileName);
            try {
                if (Files.exists(file)) {
                    String raw = Files.readString(file);
                    Matcher m = FRONTMATTER_PATTERN.matcher(raw);
                    if (m.find()) {
                        String fm = m.group(1);
                        Instant ts = extractTimestamp(fm);
                        IndexEntry newEntry = new IndexEntry(
                                entry.title, entry.fileName, entry.hook, entry.type, ts
                        );
                        if (ts != null && ts.isBefore(cutoff)) {
                            // Mark stale in the hook portion — hook already stripped of (stale)
                        }
                        updated.add(newEntry);
                    } else {
                        updated.add(entry);
                    }
                } else {
                    changed = true; // file gone, skip entry
                }
            } catch (IOException e) {
                updated.add(entry);
            }
        }
        writeIndex(dir, updated);
    }

    private Instant extractTimestamp(String frontmatter) {
        for (String line : frontmatter.split("\n")) {
            if (line.contains("updated_at:")) {
                String ts = line.substring(line.indexOf("updated_at:") + 12).trim();
                try { return Instant.parse(ts); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    // ── Internal: helpers ────────────────────────────────────────────

    private void ensureDirs() {
        try { Files.createDirectories(userMemoryDir); } catch (IOException ignored) {}
        try { Files.createDirectories(projectMemoryDir); } catch (IOException ignored) {}
    }

    private void clearDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var files = Files.list(dir)) {
                for (var f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            // Write empty MEMORY.md
            Files.writeString(dir.resolve("MEMORY.md"), "");
        }
    }

    private String summaryLine(IndexEntry entry) {
        return entry.title + ": " + (entry.hook != null ? entry.hook : "");
    }

    static String slugFromContent(String content) {
        String firstLine = content.lines().findFirst().orElse("memory");
        return sanitizeSlug(firstLine);
    }

    /** 清洗成安全的文件名 slug：只留小写字母数字和连字符，兜底成 "memory"。 */
    static String sanitizeSlug(String raw) {
        String slug = raw.toLowerCase().replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-").replaceAll("-+", "-");
        if (slug.length() > 50) slug = slug.substring(0, 50);
        if (slug.isEmpty()) slug = "memory";
        return slug;
    }

    private String buildTranscript(List<Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            sb.append('[').append(msg.getRole()).append("]: ");
            if (msg.getContent() != null) {
                sb.append(msg.getContent());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String buildExtractionPrompt() {
        return """
                You are a memory curator for a coding agent. Your job is to read the conversation
                transcript and extract NEW or UPDATED facts worth remembering for future sessions.

                First, read the existing memories (if any) to avoid duplicates. Only output entries
                that are truly new or have changed since the existing records.

                Output format — exactly 5 sections (skip empty ones):

                ### user
                [Facts about the user: role, preferences, knowledge, goals]

                ### feedback
                [Corrections the user has given about how to approach work — both what to avoid and what to keep doing]

                ### project
                [Facts about the project: architecture, conventions, ongoing work, bugs, decisions]

                ### reference
                [Pointers to external resources: dashboards, docs, Slack channels, repos]

                ### task
                [Cross-session task progress: what was started, where it left off, next steps]

                Output nothing else. Do not repeat existing entries. Skip empty categories entirely.
                """;
    }

    /**
     * Parses LLM output into a map of type → content.
     * Scans for {@code ### type} headers and collects body text until the next header or EOF.
     * Unknown types are silently dropped.
     */
    static Map<String, String> parseTypedSections(String text) {
        var map = new LinkedHashMap<String, String>();
        String currentType = null;
        StringBuilder currentBody = new StringBuilder();

        for (String line : text.lines().toList()) {
            if (line.startsWith("### ")) {
                // Flush previous section
                if (currentType != null) {
                    String body = currentBody.toString().strip();
                    if (!body.isEmpty()) map.put(currentType, body);
                }
                currentType = line.substring(4).strip().toLowerCase();
                currentBody = new StringBuilder();
            } else if (currentType != null) {
                currentBody.append(line).append('\n');
            }
        }
        // Flush last section
        if (currentType != null) {
            String body = currentBody.toString().strip();
            if (!body.isEmpty()) map.put(currentType, body);
        }

        return map;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
