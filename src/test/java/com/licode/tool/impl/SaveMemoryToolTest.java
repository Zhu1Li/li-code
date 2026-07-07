package com.licode.tool.impl;

import com.licode.memory.MemoryManager;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SaveMemoryToolTest {

    @Test
    void isWriteCategoryNamedSaveMemory(@TempDir Path work) {
        var tool = new SaveMemoryTool(new MemoryManager(work));
        assertEquals("SaveMemory", tool.name());
        assertEquals(ToolCategory.WRITE, tool.category());
    }

    @Test
    void invalidTypeReturnsError(@TempDir Path work) {
        var tool = new SaveMemoryTool(new MemoryManager(work));
        ToolResult r = tool.execute(Map.of("type", "bogus", "content", "x"));
        assertTrue(r.isError());
    }

    @Test
    void missingTypeReturnsError(@TempDir Path work) {
        var tool = new SaveMemoryTool(new MemoryManager(work));
        ToolResult r = tool.execute(Map.of("content", "x"));
        assertTrue(r.isError());
    }

    @Test
    void emptyContentReturnsError(@TempDir Path work) {
        var tool = new SaveMemoryTool(new MemoryManager(work));
        ToolResult r = tool.execute(Map.of("type", "project", "content", "   "));
        assertTrue(r.isError());
    }

    @Test
    void validSaveWritesFileWithGivenSlug(@TempDir Path work) {
        var mm = new MemoryManager(work);
        var tool = new SaveMemoryTool(mm);
        ToolResult r = tool.execute(Map.of(
                "type", "project", "content", "naming: camelCase", "slug", "code-style"));
        assertFalse(r.isError());
        assertTrue(Files.exists(mm.getMemoryDir("project").resolve("code-style.md")));
    }

    @Test
    void validSaveWithoutSlugDerivesName(@TempDir Path work) {
        var mm = new MemoryManager(work);
        var tool = new SaveMemoryTool(mm);
        ToolResult r = tool.execute(Map.of("type", "project", "content", "uses maven build"));
        assertFalse(r.isError());
        assertTrue(Files.exists(mm.getMemoryDir("project").resolve("uses-maven-build.md")));
    }
}
