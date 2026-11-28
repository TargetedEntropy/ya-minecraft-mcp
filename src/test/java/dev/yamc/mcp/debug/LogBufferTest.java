package dev.yamc.mcp.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogBufferTest {
    private static LogBuffer.Thrown thrown(String type, String msg, String... frames) {
        return new LogBuffer.Thrown(type, msg, List.of(frames));
    }

    @Test
    void keepsOnlyTheNewestLines() {
        LogBuffer b = new LogBuffer(() -> 3);
        for (int i = 1; i <= 5; i++) b.add(i, LogBuffer.Level.INFO, "log", "main", "line " + i, null);
        List<LogBuffer.Line> all = b.query(0, l -> true, 100);
        assertEquals(List.of("line 3", "line 4", "line 5"), all.stream().map(LogBuffer.Line::message).toList());
        assertEquals(5, b.latest());
    }

    @Test
    void queryReturnsNewestMatchesOldestFirstAfterSeq() {
        LogBuffer b = new LogBuffer(() -> 100);
        for (int i = 1; i <= 10; i++) b.add(i, i % 2 == 0 ? LogBuffer.Level.WARN : LogBuffer.Level.INFO, "log", "main", "m" + i, null);
        List<LogBuffer.Line> warns = b.query(3, l -> l.level() == LogBuffer.Level.WARN, 2);
        assertEquals(List.of("m8", "m10"), warns.stream().map(LogBuffer.Line::message).toList());
        assertEquals(List.of("m4", "m6", "m8", "m10"), b.query(3, l -> l.level() == LogBuffer.Level.WARN, 50).stream().map(LogBuffer.Line::message).toList());
    }

    @Test
    void groupsExceptionsByTypeAndTopFramesNotMessage() {
        LogBuffer b = new LogBuffer(() -> 100);
        b.add(1, LogBuffer.Level.ERROR, "a", "t", "x", thrown("java.lang.NullPointerException", "one", "a.B.c(B.java:1)", "a.B.d(B.java:2)"));
        b.add(2, LogBuffer.Level.ERROR, "a", "t", "y", thrown("java.lang.NullPointerException", "two", "a.B.c(B.java:1)", "a.B.d(B.java:2)"));
        b.add(3, LogBuffer.Level.ERROR, "a", "t", "z", thrown("java.lang.NullPointerException", "one", "a.B.e(B.java:9)"));
        // A different cause does not split the group: only the exception's own frames count.
        b.add(4, LogBuffer.Level.WARN, "a", "t", "w", thrown("java.lang.NullPointerException", "three", "a.B.c(B.java:1)", "a.B.d(B.java:2)",
                "Caused by: java.io.IOException", "x.Y.z(Y.java:3)"));
        List<LogBuffer.Problem> p = b.problems(x -> true);
        assertEquals(2, p.size());
        LogBuffer.Problem first = p.stream().filter(x -> x.count == 3).findFirst().orElseThrow();
        assertEquals(1, first.firstSeq);
        assertEquals(4, first.lastSeq);
        assertEquals("one", first.sample.message());
        assertEquals(4, p.getFirst().lastSeq, "most recently seen first");
    }

    @Test
    void forgetsTheLeastRecentlySeenExceptionWhenFull() {
        LogBuffer b = new LogBuffer(() -> 10);
        for (int i = 0; i <= LogBuffer.MAX_PROBLEMS; i++)
            b.add(i, LogBuffer.Level.ERROR, "a", "t", "m", thrown("E" + i, null, "f(F.java:1)"));
        List<LogBuffer.Problem> p = b.problems(x -> true);
        assertEquals(LogBuffer.MAX_PROBLEMS, p.size());
        assertTrue(p.stream().noneMatch(x -> x.sample.type().equals("E0")));
    }

    @Test
    void flattensCauses() {
        Exception e = new IllegalStateException("outer", new java.io.IOException("inner"));
        LogBuffer.Thrown t = LogBuffer.Thrown.of(e);
        assertEquals("java.lang.IllegalStateException: outer", t.headline());
        assertTrue(t.stack().stream().anyMatch(s -> s.equals("Caused by: java.io.IOException: inner")));
    }
}
