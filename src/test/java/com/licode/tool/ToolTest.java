package com.licode.tool;

import com.licode.tool.impl.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolTest {

    // ── ReadFileTool ──────────────────────────────────────────────

    @Test
    void testReadFileText(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3");

        var tool = new ReadFileTool();
        var result = tool.execute(Map.of("file_path", file.toString()));

        assertFalse(result.isError());
        assertEquals("1\tline1\n2\tline2\n3\tline3", result.output());
    }

    @Test
    void testReadFileBinary(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("bin.bin");
        Files.write(file, new byte[]{0, 1, 2, 3});

        var tool = new ReadFileTool();
        var result = tool.execute(Map.of("file_path", file.toString()));

        assertTrue(result.isError());
        assertTrue(result.output().contains("Binary file"));
    }

    @Test
    void testReadFileOffset(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("lines.txt");
        Files.writeString(file, "a\nb\nc\nd\ne");

        var tool = new ReadFileTool();
        var result = tool.execute(Map.of("file_path", file.toString(), "offset", 2, "limit", 2));

        assertFalse(result.isError());
        assertEquals("3\tc\n4\td", result.output());
    }

    @Test
    void testReadFileNotFound() {
        var tool = new ReadFileTool();
        var result = tool.execute(Map.of("file_path", "/nonexistent/file.txt"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("not found"));
    }

    // ── WriteFileTool ─────────────────────────────────────────────

    @Test
    void testWriteFileCreate(@TempDir Path tempDir) {
        var file = tempDir.resolve("new.txt");
        var tool = new WriteFileTool();
        var result = tool.execute(Map.of("file_path", file.toString(), "content", "hello world"));

        assertFalse(result.isError());
        assertTrue(Files.exists(file));
        assertTrue(result.output().contains("Successfully wrote"));
    }

    @Test
    void testWriteFileOverwriteWithoutRead(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("existing.txt");
        Files.writeString(file, "original");

        var cache = new FileStateCache();
        var tool = new WriteFileTool();
        tool.setFileStateCache(cache);

        // File exists but hasn't been read — should fail
        var result = tool.execute(Map.of("file_path", file.toString(), "content", "overwrite"));

        assertTrue(result.isError());
        assertTrue(result.output().contains("not been read"));
    }

    @Test
    void testWriteFileAfterRead(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("readfirst.txt");
        Files.writeString(file, "original");

        var cache = new FileStateCache();

        // Read first
        var readTool = new ReadFileTool();
        readTool.setFileStateCache(cache);
        readTool.execute(Map.of("file_path", file.toString()));

        // Then overwrite — should succeed
        var writeTool = new WriteFileTool();
        writeTool.setFileStateCache(cache);
        var result = writeTool.execute(Map.of("file_path", file.toString(), "content", "overwritten"));

        assertFalse(result.isError());
        assertEquals("overwritten", Files.readString(file));
    }

    @Test
    void testWriteFileCreatesParentDirs(@TempDir Path tempDir) {
        var file = tempDir.resolve("sub/deep/new.txt");
        var tool = new WriteFileTool();
        var result = tool.execute(Map.of("file_path", file.toString(), "content", "nested"));

        assertFalse(result.isError());
        assertTrue(Files.exists(file));
    }

    // ── EditFileTool ──────────────────────────────────────────────

    @Test
    void testEditFileSuccess(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("edit.txt");
        Files.writeString(file, "hello world");

        var cache = new FileStateCache();
        var readTool = new ReadFileTool();
        readTool.setFileStateCache(cache);
        readTool.execute(Map.of("file_path", file.toString()));

        var editTool = new EditFileTool();
        editTool.setFileStateCache(cache);
        var result = editTool.execute(Map.of(
                "file_path", file.toString(),
                "old_string", "hello",
                "new_string", "hi"
        ));

        assertFalse(result.isError());
        assertEquals("hi world", Files.readString(file));
        assertTrue(result.output().contains("Successfully edited"));
    }

    @Test
    void testEditFileNotFound(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("edit2.txt");
        Files.writeString(file, "hello world");

        var cache = new FileStateCache();
        var readTool = new ReadFileTool();
        readTool.setFileStateCache(cache);
        readTool.execute(Map.of("file_path", file.toString()));

        var editTool = new EditFileTool();
        editTool.setFileStateCache(cache);
        var result = editTool.execute(Map.of(
                "file_path", file.toString(),
                "old_string", "nonexistent",
                "new_string", "replacement"
        ));

        assertTrue(result.isError());
        assertTrue(result.output().contains("not found"));
    }

    @Test
    void testEditFileMultipleMatch(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("edit3.txt");
        Files.writeString(file, "the cat and the cat sat");

        var cache = new FileStateCache();
        var readTool = new ReadFileTool();
        readTool.setFileStateCache(cache);
        readTool.execute(Map.of("file_path", file.toString()));

        var editTool = new EditFileTool();
        editTool.setFileStateCache(cache);
        var result = editTool.execute(Map.of(
                "file_path", file.toString(),
                "old_string", "cat",
                "new_string", "dog"
        ));

        assertTrue(result.isError());
        assertTrue(result.output().contains("2 times"));
    }

    @Test
    void testEditFileWithoutRead(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("noread.txt");
        Files.writeString(file, "content");

        var cache = new FileStateCache();
        var editTool = new EditFileTool();
        editTool.setFileStateCache(cache);
        var result = editTool.execute(Map.of(
                "file_path", file.toString(),
                "old_string", "content",
                "new_string", "new"
        ));

        assertTrue(result.isError());
        assertTrue(result.output().contains("not been read"));
    }

    // ── BashTool ──────────────────────────────────────────────────

    @Test
    void testBashCommand() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "echo hello"));

        assertFalse(result.isError());
        assertTrue(result.output().startsWith("$ echo hello"));
        assertTrue(result.output().contains("hello"));
        assertTrue(result.output().contains("(exit code 0)"));
    }

    @Test
    void testBashWithStderr() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "echo error >&2"));

        assertTrue(result.output().contains("STDERR: error"));
        assertTrue(result.output().contains("(exit code 0)"));
    }

    @Test
    void testBashExitCode() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "exit 42"));

        assertTrue(result.isError());
        assertTrue(result.output().contains("(exit code 42)"));
    }

    @Test
    void testBashTimeout() {
        var tool = new BashTool();
        // Sleep longer than timeout
        var result = tool.execute(Map.of("command", "sleep 10", "timeout", 1));

        assertTrue(result.isError());
        assertTrue(result.output().contains("timed out"));
    }

    // ── GlobTool ──────────────────────────────────────────────────

    @Test
    void testGlobPattern(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");
        Files.writeString(tempDir.resolve("c.md"), "c");

        var tool = new GlobTool();
        var result = tool.execute(Map.of("pattern", "*.txt", "path", tempDir.toString()));

        assertFalse(result.isError());
        // Should be sorted and contain both .txt files
        var lines = result.output().split("\n");
        assertEquals(2, lines.length);
        assertEquals("a.txt", lines[0]);
        assertEquals("b.txt", lines[1]);
    }

    @Test
    void testGlobSkipsDotGit(@TempDir Path tempDir) throws IOException {
        var dotGit = tempDir.resolve(".git");
        Files.createDirectory(dotGit);
        Files.writeString(dotGit.resolve("config"), "ignored");

        Files.writeString(tempDir.resolve("readme.txt"), "readme");

        var tool = new GlobTool();
        var result = tool.execute(Map.of("pattern", "**/*", "path", tempDir.toString()));

        assertFalse(result.isError());
        assertFalse(result.output().contains(".git"));
    }

    @Test
    void testGlobNoMatch(@TempDir Path tempDir) {
        var tool = new GlobTool();
        var result = tool.execute(Map.of("pattern", "*.xyz", "path", tempDir.toString()));

        assertFalse(result.isError());
        assertTrue(result.output().contains("No files matched"));
    }

    // ── GrepTool ──────────────────────────────────────────────────

    @Test
    void testGrepRegex(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("code.java"),
                "public class Foo {\n    private int x;\n    public void bar() {}\n}");

        var tool = new GrepTool();
        var result = tool.execute(Map.of("pattern", "public", "path", tempDir.toString()));

        assertFalse(result.isError());
        var output = result.output();
        assertTrue(output.contains("code.java:1:public class Foo {"));
        assertTrue(output.contains("code.java:3:    public void bar() {}"));
    }

    @Test
    void testGrepNoMatch(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello world");

        var tool = new GrepTool();
        var result = tool.execute(Map.of("pattern", "nonexistent", "path", tempDir.toString()));

        assertFalse(result.isError());
        assertTrue(result.output().contains("No matches found"));
    }

    @Test
    void testGrepInvalidRegex() {
        var tool = new GrepTool();
        var result = tool.execute(Map.of("pattern", "[invalid", "path", "."));

        assertTrue(result.isError());
        assertTrue(result.output().contains("invalid regex"));
    }

    // ── ToolRegistry ──────────────────────────────────────────────

    @Test
    void testToolRegistryCreateDefault() {
        var registry = ToolRegistry.createDefault();

        assertNotNull(registry.get("ReadFile"));
        assertNotNull(registry.get("WriteFile"));
        assertNotNull(registry.get("EditFile"));
        assertNotNull(registry.get("Bash"));
        assertNotNull(registry.get("Glob"));
        assertNotNull(registry.get("Grep"));
        assertNotNull(registry.get("ToolSearch"));
        assertEquals(7, registry.listTools().size());
    }

    @Test
    void testToolRegistryToApiSchemas() {
        var registry = ToolRegistry.createDefault();
        var schemas = registry.toApiSchemas("anthropic");

        assertEquals(7, schemas.size());
        var first = schemas.get(0);
        assertTrue(first.containsKey("name"));
        assertTrue(first.containsKey("description"));
        assertTrue(first.containsKey("input_schema"));
    }

    @Test
    void testToolRegistryOpenAIFormat() {
        var registry = ToolRegistry.createDefault();
        var schemas = registry.toApiSchemas("openai");

        assertEquals(7, schemas.size());
        var first = schemas.get(0);
        assertTrue(first.containsKey("name"));
        assertTrue(first.containsKey("description"));
        assertTrue(first.containsKey("input_schema"));
    }

    // ── FileStateCache ────────────────────────────────────────────

    @Test
    void testFileStateCacheValidateNotRecorded() {
        var cache = new FileStateCache();
        String err = cache.validate("/nonexistent/file.txt");
        assertNotNull(err);
        assertTrue(err.contains("not been read"));
    }

    @Test
    void testFileStateCacheRecordAndValidate(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");

        var cache = new FileStateCache();
        cache.record(file.toAbsolutePath().toString(), "content",
                Files.getLastModifiedTime(file).toMillis());

        String err = cache.validate(file.toAbsolutePath().toString());
        assertNull(err);
    }

    @Test
    void testFileStateCacheUpdate(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "old");

        var cache = new FileStateCache();
        cache.record(file.toAbsolutePath().toString(), "old",
                Files.getLastModifiedTime(file).toMillis());
        cache.update(file.toAbsolutePath().toString(), "new");

        String err = cache.validate(file.toAbsolutePath().toString());
        assertNull(err);
    }
}
