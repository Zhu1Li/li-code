package com.licode.toolresult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplacementRecordsIOTest {

    @Test
    void appendAndLoadRoundtrip(@TempDir Path tempDir) throws Exception {
        var records = List.of(
                ContentReplacementRecord.toolResult("id1", "[Result of 100 chars saved to /tmp]"),
                ContentReplacementRecord.toolResult("id2", "[Result of 200 chars saved to /tmp]")
        );

        ReplacementRecordsIO.append(tempDir, records);
        List<ContentReplacementRecord> loaded = ReplacementRecordsIO.load(tempDir);

        assertEquals(2, loaded.size());
        assertEquals("tool-result", loaded.get(0).kind());
        assertEquals("id1", loaded.get(0).toolUseId());
        assertEquals("[Result of 100 chars saved to /tmp]", loaded.get(0).replacement());
        assertEquals("id2", loaded.get(1).toolUseId());
    }

    @Test
    void loadMissingFile(@TempDir Path tempDir) throws Exception {
        List<ContentReplacementRecord> loaded = ReplacementRecordsIO.load(tempDir);
        assertTrue(loaded.isEmpty());
    }
}
