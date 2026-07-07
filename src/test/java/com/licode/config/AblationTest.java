package com.licode.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AblationTest {

    @Test
    void nullOrBlankMeansNothingDisabled() {
        assertEquals(Set.of(), Ablation.parse(null));
        assertEquals(Set.of(), Ablation.parse(""));
        assertEquals(Set.of(), Ablation.parse("   "));
    }

    @Test
    void singleModuleParsed() {
        assertEquals(Set.of("compaction"), Ablation.parse("compaction"));
    }

    @Test
    void commaSeparatedTrimmedAndLowercased() {
        assertEquals(Set.of("compaction", "memory"),
                Ablation.parse(" Compaction , MEMORY "));
    }

    @Test
    void emptyEntriesIgnored() {
        assertEquals(Set.of("compaction"), Ablation.parse("compaction,, ,"));
    }
}
