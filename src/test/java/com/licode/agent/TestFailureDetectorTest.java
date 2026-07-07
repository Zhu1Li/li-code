package com.licode.agent;

import org.junit.jupiter.api.Test;

import static com.licode.agent.TestFailureDetector.looksLikeTestFailure;
import static org.junit.jupiter.api.Assertions.*;

class TestFailureDetectorTest {

    @Test
    void detectsMavenSurefireFailures() {
        assertTrue(looksLikeTestFailure("Tests run: 5, Failures: 2, Errors: 0, Skipped: 0"));
        assertTrue(looksLikeTestFailure("Tests run: 5, Failures: 0, Errors: 1, Skipped: 0"));
    }

    @Test
    void detectsBuildFailureMarkers() {
        assertTrue(looksLikeTestFailure("[INFO] BUILD FAILURE"));
        assertTrue(looksLikeTestFailure("BUILD FAILED in 3s"));
    }

    @Test
    void detectsPytestAndJestFailures() {
        assertTrue(looksLikeTestFailure("=========== 3 failed, 5 passed in 1.2s ==========="));
        assertTrue(looksLikeTestFailure("Tests:       2 failed, 10 passed, 12 total"));
    }

    @Test
    void detectsGoFail() {
        assertTrue(looksLikeTestFailure("--- FAIL: TestFoo (0.00s)"));
    }

    @Test
    void ignoresSuccessOutput() {
        assertFalse(looksLikeTestFailure("Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"));
        assertFalse(looksLikeTestFailure("[INFO] BUILD SUCCESS"));
        assertFalse(looksLikeTestFailure("=== 0 failed, 8 passed in 0.9s ==="));
    }

    @Test
    void ignoresEmptyOrNull() {
        assertFalse(looksLikeTestFailure(null));
        assertFalse(looksLikeTestFailure("   "));
        assertFalse(looksLikeTestFailure("just some normal build log output"));
    }
}
