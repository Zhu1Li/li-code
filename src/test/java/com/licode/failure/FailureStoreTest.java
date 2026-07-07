package com.licode.failure;

import com.licode.failure.FailureStore.FailureLesson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FailureStoreTest {

    private static long lessonFileCount(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("INDEX.md"))
                    .count();
        }
    }

    @Test
    void recordsDistinctLessonsWithoutOverwriting(@TempDir Path work) throws Exception {
        var store = new FailureStore(work);
        store.record(new FailureLesson(List.of("NullPointerException", "UserService", "login"),
                "npe in login", "add null check", "UserServiceTest.login", "NullPointerException", Instant.now()));
        store.record(new FailureLesson(List.of("timeout", "OrderService"),
                "slow query", "add index", "OrderTest", "TimeoutException", Instant.now().plusSeconds(1)));

        assertEquals(2, lessonFileCount(store.dir()), "distinct bugs must not overwrite each other");
    }

    @Test
    void recallRanksByKeywordOverlap(@TempDir Path work) {
        var store = new FailureStore(work);
        store.record(new FailureLesson(List.of("alpha", "beta", "gamma"),
                "cause1", "fix1", null, null, Instant.now()));
        store.record(new FailureLesson(List.of("alpha", "delta"),
                "cause2", "fix2", null, null, Instant.now().plusSeconds(1)));

        var hits = store.recall("alpha beta gamma", 3);
        assertFalse(hits.isEmpty());
        // The 3-overlap lesson (has "beta") must rank first over the 1-overlap one.
        assertTrue(hits.get(0).keywords().contains("beta"),
                "highest keyword overlap should rank first");
    }

    @Test
    void recallNoOverlapReturnsEmpty(@TempDir Path work) {
        var store = new FailureStore(work);
        store.record(new FailureLesson(List.of("NullPointerException", "login"),
                "c", "f", null, null, Instant.now()));
        assertTrue(store.recall("completely unrelated xyzzy", 3).isEmpty());
    }

    @Test
    void recallEmptyStoreReturnsEmpty(@TempDir Path work) {
        assertTrue(new FailureStore(work).recall("anything", 3).isEmpty());
    }

    @Test
    void recallLimitClampedToMax(@TempDir Path work) {
        var store = new FailureStore(work);
        for (int i = 0; i < 8; i++) {
            store.record(new FailureLesson(List.of("shared", "k" + i),
                    "c" + i, "f" + i, null, null, Instant.now().plusSeconds(i)));
        }
        // All share "shared" → all match; but limit clamps to MAX_RECALL_LIMIT.
        assertTrue(store.recall("shared", 100).size() <= FailureStore.MAX_RECALL_LIMIT);
    }

    @Test
    void roundTripsFieldsThroughDisk(@TempDir Path work) {
        var store = new FailureStore(work);
        store.record(new FailureLesson(List.of("npe", "login"),
                "root cause text", "the fix text", "MyTest.foo", "NullPointerException", Instant.now()));
        var hits = store.recall("npe", 1);
        assertEquals(1, hits.size());
        var l = hits.get(0);
        assertEquals("root cause text", l.rootCause());
        assertEquals("the fix text", l.fix());
        assertEquals("MyTest.foo", l.testName());
        assertEquals("NullPointerException", l.exception());
    }
}
