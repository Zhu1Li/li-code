package com.licode.memory;

import com.licode.conversation.ConversationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    @TempDir
    Path fakeHome;
    private String realUserHome;

    /**
     * MemoryManager resolves the user-level memory dir from {@code user.home} in its
     * constructor. Without redirecting it, these tests read — and via clear(), delete —
     * the developer's own ~/.licode/memory.
     */
    @BeforeEach
    void isolateUserHome() {
        realUserHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", realUserHome);
    }

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

    // ── Index round-trip ─────────────────────────────────────────────

    @Test
    void refreshStalenessKeepsEveryTypeInIndexAndBlock(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("user prefers tabs", "user");
        mm.addManual("licode builds with maven", "project");
        mm.addManual("api docs on confluence", "reference");

        mm.refreshStaleness();

        String userIndex = Files.readString(mm.getMemoryDir("user").resolve("MEMORY.md"));
        String projectIndex = Files.readString(mm.getMemoryDir("project").resolve("MEMORY.md"));
        assertTrue(userIndex.contains("user prefers tabs"), "user entry dropped from index:\n" + userIndex);
        assertTrue(projectIndex.contains("licode builds with maven"),
                "project entry dropped from index:\n" + projectIndex);
        assertTrue(projectIndex.contains("api docs on confluence"),
                "reference entry dropped from index:\n" + projectIndex);

        String block = mm.buildAutoMemoryBlock();
        assertTrue(block.contains("user prefers tabs"), "user memory missing from block:\n" + block);
        assertTrue(block.contains("licode builds with maven"), "project memory missing from block:\n" + block);
        assertTrue(block.contains("api docs on confluence"), "reference memory missing from block:\n" + block);
    }

    @Test
    void writingAMemoryKeepsThePreviousOnes(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("first fact", "project");
        mm.addManual("second fact", "reference");
        mm.addManual("third fact", "task");

        String index = Files.readString(mm.getMemoryDir("project").resolve("MEMORY.md"));
        assertTrue(index.contains("first fact"), "earlier entry dropped on rewrite:\n" + index);
        assertTrue(index.contains("second fact"), "earlier entry dropped on rewrite:\n" + index);
        assertTrue(index.contains("third fact"), index);
    }

    @Test
    void indexGroupsEntriesUnderTypeHeaders(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("a project fact", "project");
        mm.addManual("a reference fact", "reference");

        String index = Files.readString(mm.getMemoryDir("project").resolve("MEMORY.md"));
        assertTrue(index.contains("## Project"), index);
        assertTrue(index.contains("## Reference"), index);
        // The header is the only record of an entry's type, so it must survive a rewrite.
        mm.refreshStaleness();
        String after = Files.readString(mm.getMemoryDir("project").resolve("MEMORY.md"));
        assertTrue(after.contains("## Project"), after);
        assertTrue(after.contains("## Reference"), after);
    }

    @Test
    void injectMemoriesStillWorksAfterStalenessRefresh(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("prefers small commits", "project");
        mm.refreshStaleness();

        ConversationManager conv = new ConversationManager();
        mm.injectMemories(conv);
        assertEquals(1, conv.getMessages().size(), "refreshStaleness must not empty the index");
        assertTrue(conv.getMessages().get(0).getContent().contains("prefers small commits"));
    }

    /**
     * Pins the section header as a type source on its own. The memory file has no
     * frontmatter, so recovering the type from the file cannot rescue this entry —
     * only the {@code ## Project} header can keep it out of the catch-all section.
     */
    @Test
    void sectionHeaderAloneRecoversType(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        Path dir = mm.getMemoryDir("project");
        Files.writeString(dir.resolve("legacy.md"), "a legacy memory body\n");
        Files.writeString(dir.resolve("MEMORY.md"), "## Project\n\n- [legacy](legacy.md) — a legacy note\n");

        mm.refreshStaleness();

        String index = Files.readString(dir.resolve("MEMORY.md"));
        assertTrue(index.contains("## Project"), "type not recovered from the section header:\n" + index);
        assertTrue(index.contains("- [legacy](legacy.md) — a legacy note"), index);
    }

    /** A type we cannot recover is not a reason to drop the entry — that was the wipe. */
    @Test
    void entryWithUnrecoverableTypeIsKept(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        Path dir = mm.getMemoryDir("project");
        Files.writeString(dir.resolve("orphan.md"), "no frontmatter, no header\n");
        Files.writeString(dir.resolve("MEMORY.md"), "- [orphan](orphan.md) — an orphan note\n");

        mm.refreshStaleness();

        String index = Files.readString(dir.resolve("MEMORY.md"));
        assertTrue(index.contains("orphan.md"), "untyped entry was dropped:\n" + index);
        assertTrue(index.contains("an orphan note"), index);
    }

    // ── Staleness marks ──────────────────────────────────────────────

    @Test
    void oldMemoryIsMarkedStaleInIndex(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("ancient caching decision", "project");
        Path dir = mm.getMemoryDir("project");
        backdate(dir.resolve("ancient-caching-decision.md"), MemoryManager.MAX_STALE_DAYS + 1);

        mm.refreshStaleness();

        String index = Files.readString(dir.resolve("MEMORY.md"));
        assertTrue(index.contains("ancient caching decision (stale)"), "expected a stale mark:\n" + index);

        // Re-running must not stack a second mark onto the same entry.
        mm.refreshStaleness();
        String again = Files.readString(dir.resolve("MEMORY.md"));
        assertFalse(again.contains("(stale) (stale)"), "stale mark stacked on rewrite:\n" + again);
        assertTrue(again.contains("ancient caching decision (stale)"), again);
    }

    @Test
    void freshMemoryIsNotMarkedStale(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("recent decision", "project");
        mm.refreshStaleness();

        String index = Files.readString(mm.getMemoryDir("project").resolve("MEMORY.md"));
        assertFalse(index.contains("(stale)"), index);
    }

    @Test
    void refreshStalenessDropsEntriesWhoseFileIsGone(@TempDir Path workDir) throws Exception {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("keep me", "project");
        mm.addManual("delete me", "project");
        Files.delete(mm.getMemoryDir("project").resolve("delete-me.md"));

        mm.refreshStaleness();

        String index = Files.readString(mm.getMemoryDir("project").resolve("MEMORY.md"));
        assertTrue(index.contains("keep me"), index);
        assertFalse(index.contains("delete me"), "index still points at a deleted file:\n" + index);
    }

    /** Rewrites a memory file's frontmatter timestamp to N days ago. */
    private static void backdate(Path memoryFile, int daysAgo) throws Exception {
        Instant old = Instant.now().minus(Duration.ofDays(daysAgo));
        String body = Files.readString(memoryFile).replaceAll("updated_at: .*", "updated_at: " + old);
        Files.writeString(memoryFile, body);
    }

    // ── Extraction prompt ────────────────────────────────────────────

    @Test
    void extractionPromptCarriesExistingMemories(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        mm.addManual("licode targets java 21", "project");

        String prompt = mm.buildExtractionPrompt(mm.buildAutoMemoryBlock());
        assertTrue(prompt.contains("licode targets java 21"),
                "the prompt tells the model to read existing memories, so they must be in it:\n" + prompt);
    }

    @Test
    void extractionPromptWithoutMemoriesSaysNone(@TempDir Path workDir) {
        MemoryManager mm = new MemoryManager(workDir);
        assertTrue(mm.buildExtractionPrompt("").contains("(none)"));
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
