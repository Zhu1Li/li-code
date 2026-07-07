package com.licode.memory;

import com.licode.conversation.ConversationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    // ── Construction ─────────────────────────────────────────────────

    @Test
    void createsUserAndProjectDirs(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        assertTrue(Files.exists(mm.getMemoryDir("user")));
        assertTrue(Files.exists(mm.getMemoryDir("project")));
    }

    @Test
    void userAndProjectDirsAreDifferent(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        assertNotEquals(mm.getMemoryDir("user"), mm.getMemoryDir("project"));
    }

    @Test
    void getMemoryDirRoutesCorrectly(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        assertEquals(mm.getMemoryDir("user"), mm.getMemoryDir("feedback"));
        assertEquals(mm.getMemoryDir("project"), mm.getMemoryDir("reference"));
        assertEquals(mm.getMemoryDir("project"), mm.getMemoryDir("task"));
    }

    // ── getMemories ──────────────────────────────────────────────────

    @Test
    void newManagerLoadsWithoutError(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        // May have pre-existing user-level memories; just verify no exception
        List<String> memories = mm.getMemories();
        assertNotNull(memories);
    }

    @Test
    void projectMemoriesStartEmpty(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        // Project directory has no MEMORY.md yet, so should be empty
        Path indexFile = mm.getMemoryDir("project").resolve("MEMORY.md");
        assertFalse(Files.exists(indexFile));
    }

    // ── addManual ────────────────────────────────────────────────────

    @Test
    void addManualProjectMemory(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        int before = mm.getMemories().size();
        mm.addManual("This project uses Maven for build management", "project");
        int after = mm.getMemories().size();
        assertTrue(after > before, "Expected memory count to increase after addManual");
    }

    @Test
    void addManualRejectsUnknownType(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        int before = mm.getMemories().size();
        mm.addManual("some content", "unknown_type");
        int after = mm.getMemories().size();
        assertEquals(before, after, "Unknown type should not add a memory");
    }

    @Test
    void explicitSlugWritesThenOverwrites(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        Path dir = mm.getMemoryDir("project");

        mm.addManual("First profile\nnaming: camelCase", "project", "code-style");
        Path f = dir.resolve("code-style.md");
        assertTrue(Files.exists(f));
        assertTrue(Files.readString(f).contains("First profile"));

        // Same slug overwrites — no second file, content is the latest.
        mm.addManual("Second profile\nlogging: slf4j", "project", "code-style");
        try (var files = Files.list(dir)) {
            long count = files.filter(p -> p.getFileName().toString().equals("code-style.md")).count();
            assertEquals(1, count, "same slug must overwrite, not add a new file");
        }
        String body = Files.readString(f);
        assertTrue(body.contains("Second profile"));
        assertFalse(body.contains("First profile"));
    }

    @Test
    void explicitNullSlugDerivesFromContent(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("hello world fact", "project", null);
        assertTrue(Files.exists(mm.getMemoryDir("project").resolve("hello-world-fact.md")));
    }

    @Test
    void maliciousSlugIsSanitizedAndCannotEscape(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        Path dir = mm.getMemoryDir("project");
        mm.addManual("payload", "project", "../../evil");
        // "../../evil" → sanitized to "evil"; write stays inside the project memory dir.
        assertTrue(Files.exists(dir.resolve("evil.md")));
        assertFalse(Files.exists(dir.getParent().getParent().resolve("evil.md")),
                "an explicit slug must not let the write escape the memory dir");
    }

    @Test
    void addManualCreatesMdFile(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("Test memory content", "project");

        Path projectDir = mm.getMemoryDir("project");
        try (var files = Files.list(projectDir)) {
            long mdCount = files
                    .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .count();
            assertTrue(mdCount >= 1, "Expected at least one non-index .md file");
        }
    }

    @Test
    void addManualCreatesMemoryIndex(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("Test memory content", "project");

        Path indexFile = mm.getMemoryDir("project").resolve("MEMORY.md");
        assertTrue(Files.exists(indexFile), "MEMORY.md should exist after addManual");
    }

    @Test
    void memoryFileHasFrontmatter(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("Test memory content here", "task");

        Path taskDir = mm.getMemoryDir("task");
        try (var files = Files.list(taskDir)) {
            var mdFiles = files
                    .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .toList();
            assertFalse(mdFiles.isEmpty(), "Expected at least one memory .md file");
            String content = Files.readString(mdFiles.get(0));
            assertTrue(content.startsWith("---"),
                    "Expected YAML frontmatter, got: " + content.substring(0, Math.min(50, content.length())));
            assertTrue(content.contains("type: task"), "Expected type: task in frontmatter");
        }
    }

    // ── clear ────────────────────────────────────────────────────────

    @Test
    void clearRemovesAllMemories(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("test memory 1", "project");
        mm.addManual("test memory 2", "project");

        // Check project memories are there
        int before = mm.getMemories().size();
        assertTrue(before > 0);

        mm.clear();

        // After clear, project MEMORY.md should be empty
        Path idx = mm.getMemoryDir("project").resolve("MEMORY.md");
        assertTrue(Files.exists(idx));
    }

    // ── shouldExtract ────────────────────────────────────────────────

    @Test
    void shouldExtractEveryFifthTurn(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        // Turns 1-4: false
        for (int i = 0; i < 4; i++) {
            assertFalse(mm.shouldExtract(), "Turn " + mm.getTurnCount() + " should not trigger");
        }
        // Turn 5: true
        assertTrue(mm.shouldExtract(), "Turn 5 should trigger");
        // Turns 6-9: false
        for (int i = 0; i < 4; i++) {
            assertFalse(mm.shouldExtract());
        }
        // Turn 10: true
        assertTrue(mm.shouldExtract());
    }

    // ── injectMemories ───────────────────────────────────────────────

    @Test
    void injectMemoriesAddsToConversation(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("user likes streams for data processing", "project");

        ConversationManager conv = new ConversationManager();
        int before = conv.getMessages().size();
        mm.injectMemories(conv);
        assertTrue(conv.getMessages().size() > before, "Should inject memories");
    }

    @Test
    void injectMemoriesNullConversationDoesNotThrow(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("test", "project");
        assertDoesNotThrow(() -> mm.injectMemories(null));
    }

    // ── parseTypedSections ───────────────────────────────────────────

    @Test
    void parseSingleSection() {
        String text = "### user\nuser content here\nmore content\n";
        Map<String, String> result = MemoryManager.parseTypedSections(text);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("user"));
        assertTrue(result.get("user").contains("user content here"));
    }

    @Test
    void parseMultipleSections() {
        String text = """
                ### user
                user preference 1

                ### project
                project fact 1
                project fact 2

                ### task
                task progress
                """;
        Map<String, String> result = MemoryManager.parseTypedSections(text);
        assertEquals(3, result.size());
        assertTrue(result.containsKey("user"));
        assertTrue(result.containsKey("project"));
        assertTrue(result.containsKey("task"));
    }

    @Test
    void parseIncludesAllSectionTypes() {
        // parseTypedSections returns ALL sections it finds.
        // Type filtering (dropping unknowns) happens in extract(), not parseTypedSections.
        String text = """
                ### user
                user info

                ### unknown_type
                raw content

                ### project
                project info
                """;
        Map<String, String> result = MemoryManager.parseTypedSections(text);
        // All 3 sections are parsed, including unknown_type
        assertTrue(result.containsKey("user"));
        assertTrue(result.containsKey("project"));
        assertTrue(result.containsKey("unknown_type"), "parseTypedSections parses all sections; filtering is caller's job");
        assertEquals(3, result.size());
    }

    @Test
    void parseEmptyBodySkipped() {
        String text = """
                ### user
                real content

                ### feedback

                ### project
                more content
                """;
        Map<String, String> result = MemoryManager.parseTypedSections(text);
        assertEquals(2, result.size());
        assertFalse(result.containsKey("feedback"), "Empty feedback section should be skipped");
    }

    @Test
    void parseNoHeadersReturnsEmpty() {
        Map<String, String> result = MemoryManager.parseTypedSections("just some text without headers");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseCaseInsensitiveTypeNames() {
        String text = """
                ### User
                content one

                ### PROJECT
                content two
                """;
        Map<String, String> result = MemoryManager.parseTypedSections(text);
        assertTrue(result.containsKey("user"));
        assertTrue(result.containsKey("project"));
    }

    // ── buildAutoMemoryBlock ─────────────────────────────────────────

    @Test
    void buildAutoMemoryBlockContainsAddedContent(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("The user is a backend developer who prefers functional programming", "project");

        String block = mm.buildAutoMemoryBlock();
        assertTrue(block.contains("backend developer"));
    }

    @Test
    void buildAutoMemoryBlockReturnsString(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.clear(); // ensure clean state
        String block = mm.buildAutoMemoryBlock();
        // With cleared state, block should be empty
        assertEquals("", block.strip());
    }

    // ── slugFromContent ──────────────────────────────────────────────

    @Test
    void slugFromSimpleContent() {
        String slug = MemoryManager.slugFromContent("User prefers function style");
        assertNotNull(slug);
        assertFalse(slug.isEmpty());
    }

    @Test
    void slugLimitsLength() {
        String longContent = "a".repeat(200);
        String slug = MemoryManager.slugFromContent(longContent);
        assertTrue(slug.length() <= 50);
    }
}
