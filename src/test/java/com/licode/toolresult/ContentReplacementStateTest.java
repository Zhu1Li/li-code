package com.licode.toolresult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentReplacementStateTest {

    @Test
    void newReturnsEmpty() {
        ContentReplacementState state = new ContentReplacementState();
        assertTrue(state.seenIds().isEmpty());
        assertTrue(state.replacements().isEmpty());
    }

    @Test
    void copyIsIndependent() {
        ContentReplacementState original = new ContentReplacementState();
        original.seenIds().add("id1");
        original.replacements().put("id1", "[Result of 100 chars saved to /tmp]");

        ContentReplacementState copy = original.copy();
        assertEquals(original.seenIds(), copy.seenIds());
        assertEquals(original.replacements(), copy.replacements());

        // Mutate original — copy must not be affected
        original.seenIds().add("id2");
        original.replacements().put("id2", "new");

        assertFalse(copy.seenIds().contains("id2"));
        assertFalse(copy.replacements().containsKey("id2"));
    }
}
