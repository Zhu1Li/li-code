package com.licode.worktree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlugValidatorTest {

    @Test
    void validate_empty_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(""));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(null));
    }

    @Test
    void validate_tooLong_shouldThrow() {
        String longName = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(longName));
    }

    @Test
    void validate_withDotSegment_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("."));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(".."));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a/../b"));
    }

    @Test
    void validate_withIllegalChars_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("has space"));
    }

    @Test
    void validate_withIllegalCharsInSegment_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("hello world"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("hello@world"));
    }

    @Test
    void validate_validSlug_shouldPass() {
        assertDoesNotThrow(() -> SlugValidator.validate("my-worktree"));
        assertDoesNotThrow(() -> SlugValidator.validate("feature.branch_v2"));
        assertDoesNotThrow(() -> SlugValidator.validate("a/b/c")); // nested via /
        assertDoesNotThrow(() -> SlugValidator.validate("agent-aabcdef1"));
        assertDoesNotThrow(() -> SlugValidator.validate("CodeReview-2024"));
    }

    @Test
    void flatten_shouldReplaceSlashWithPlus() {
        assertEquals("a+b+c", SlugValidator.flatten("a/b/c"));
        assertEquals("simple", SlugValidator.flatten("simple"));
        assertEquals("nested+path+here", SlugValidator.flatten("nested/path/here"));
    }

    @Test
    void branchName_shouldAddPrefix() {
        assertEquals("worktree-my-branch", SlugValidator.branchName("my-branch"));
        assertEquals("worktree-a+b+c", SlugValidator.branchName("a/b/c"));
    }
}
