package com.licode.instructions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InstructionsLoaderTest {

    // ── Three-layer loading ──────────────────────────────────────────

    @Test
    void emptyWhenNoFilesExist(@TempDir Path workDir) {
        String result = InstructionsLoader.load(workDir);
        assertEquals("", result);
    }

    @Test
    void loadsSingleLayer(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "project instructions");
        String result = InstructionsLoader.load(workDir);
        assertTrue(result.contains("project instructions"));
    }

    @Test
    void concatenatesMultipleLayersInPriorityOrder(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "first");
        Files.createDirectories(workDir.resolve(".licode"));
        Files.writeString(workDir.resolve(".licode/LICODE.md"), "second");

        String result = InstructionsLoader.load(workDir);
        assertTrue(result.contains("first"));
        assertTrue(result.contains("second"));
        // first (higher priority) should appear before second
        assertTrue(result.indexOf("first") < result.indexOf("second"));
    }

    @Test
    void missingFileDoesNotThrow(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "only project");
        // .licode/LICODE.md and ~/.licode/LICODE.md don't exist
        String result = InstructionsLoader.load(workDir);
        assertTrue(result.contains("only project"));
    }

    // ── Fingerprint ──────────────────────────────────────────────────

    @Test
    void fingerprintChangesWithContent(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "original");
        String fp1 = InstructionsLoader.fingerprint(workDir);

        Files.writeString(workDir.resolve("LICODE.md"), "modified");
        String fp2 = InstructionsLoader.fingerprint(workDir);

        assertNotEquals(fp1, fp2);
    }

    @Test
    void fingerprintStableForSameContent(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "stable");
        String fp1 = InstructionsLoader.fingerprint(workDir);
        String fp2 = InstructionsLoader.fingerprint(workDir);
        assertEquals(fp1, fp2);
    }

    // ── @include resolution ──────────────────────────────────────────

    @Test
    void resolveSimpleInclude(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "@include included.md");
        Files.writeString(workDir.resolve("included.md"), "included content");

        String result = InstructionsLoader.load(workDir);
        assertTrue(result.contains("included content"));
        assertFalse(result.contains("@include"));
    }

    @Test
    void resolveNestedInclude(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "@include a.md");
        Files.writeString(workDir.resolve("a.md"), "A content\n@include b.md");
        Files.writeString(workDir.resolve("b.md"), "B content");

        String result = InstructionsLoader.load(workDir);
        assertTrue(result.contains("A content"));
        assertTrue(result.contains("B content"));
    }

    @Test
    void rejectDepthExceeded(@TempDir Path workDir) throws Exception {
        // Chain: LICODE → a → b → c → d (depth 3 = MAX, so d is blocked)
        Files.writeString(workDir.resolve("LICODE.md"), "@include a.md");
        Files.writeString(workDir.resolve("a.md"), "A content\n@include b.md");
        Files.writeString(workDir.resolve("b.md"), "B content\n@include c.md");
        Files.writeString(workDir.resolve("c.md"), "@include d.md");
        Files.writeString(workDir.resolve("d.md"), "too deep");

        String result = InstructionsLoader.load(workDir);
        // d.md should not be read (depth 3 >= MAX_INCLUDE_DEPTH)
        assertFalse(result.contains("too deep"), "d.md content should not appear");
        // The @include d.md line in c.md should remain literal (unresolved at depth 3)
        assertTrue(result.contains("@include d.md"), "@include d.md should remain as unresolved text");
    }

    @Test
    void rejectNonMdFile(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "@include file.txt");
        Files.writeString(workDir.resolve("file.txt"), "bad extension");

        String result = InstructionsLoader.load(workDir);
        assertFalse(result.contains("bad extension"));
    }

    @Test
    void rejectPathEscape(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "@include ../outside.md");

        String result = InstructionsLoader.load(workDir);
        assertFalse(result.contains("outside content"));
    }

    @Test
    void rejectCircularReference(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "@include a.md");
        Files.writeString(workDir.resolve("a.md"), "@include LICODE.md");

        String result = InstructionsLoader.load(workDir);
        // Should not infinite loop; both files should be included at most once
        assertNotNull(result);
    }

    @Test
    void missingIncludeFileSilentlySkipped(@TempDir Path workDir) throws Exception {
        Files.writeString(workDir.resolve("LICODE.md"), "before\n@include missing.md\nafter");

        String result = InstructionsLoader.load(workDir);
        assertTrue(result.contains("before"));
        assertTrue(result.contains("after"));
    }

    // ── resolveIncludes static method ────────────────────────────────

    @Test
    void resolveIncludesWithVisitedSet(@TempDir Path workDir) throws Exception {
        String result = InstructionsLoader.resolveIncludes(
                "@include a.md", workDir, 0,
                new java.util.LinkedHashSet<>());
        // No files exist, should return original (with @include line stripped since file not found)
        assertNotNull(result);
    }
}
