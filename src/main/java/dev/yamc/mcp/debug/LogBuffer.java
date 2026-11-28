package dev.yamc.mcp.debug;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Recent log lines in a bounded ring buffer, plus every distinct exception seen, grouped by where it was thrown.
 * Thread-safe: loggers call {@link #add} from any thread. Independent of the logging framework (see {@link LogCapture}).
 */
public final class LogBuffer {
    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL;

        /** The level with this name (case-insensitive), or null. */
        public static @Nullable Level parse(String name) {
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** A thrown exception, flattened: its class and message, and its stack with "Caused by:" lines for causes. */
    public record Thrown(String type, @Nullable String message, List<String> stack) {
        private static final int MAX_STACK_LINES = 80;
        private static final int MAX_FRAMES_PER_CAUSE = 30;

        /** Flattens a throwable and its causes into a bounded list of lines. */
        public static @Nullable Thrown of(@Nullable Throwable t) {
            if (t == null) return null;
            List<String> stack = new ArrayList<>();
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable c = t; c != null && seen.add(c) && stack.size() < MAX_STACK_LINES; c = c.getCause()) {
                if (c != t) stack.add("Caused by: " + c.getClass().getName() + (c.getMessage() == null ? "" : ": " + c.getMessage()));
                StackTraceElement[] frames = c.getStackTrace();
                int n = Math.min(frames.length, MAX_FRAMES_PER_CAUSE);
                for (int i = 0; i < n && stack.size() < MAX_STACK_LINES; i++) stack.add(frame(frames[i]));
                if (frames.length > n) stack.add("... " + (frames.length - n) + " more");
            }
            return new Thrown(t.getClass().getName(), t.getMessage(), List.copyOf(stack));
        }

        /** class.method(File.java:line), without the class loader / module prefix, so frames start with the package. */
        private static String frame(StackTraceElement e) {
            String where = e.isNativeMethod() ? "Native Method"
                    : e.getFileName() == null ? "Unknown Source"
                    : e.getLineNumber() >= 0 ? e.getFileName() + ":" + e.getLineNumber() : e.getFileName();
            return e.getClassName() + "." + e.getMethodName() + "(" + where + ")";
        }

        /** Frames of the thrown exception itself (before any "Caused by:"). */
        List<String> ownFrames() {
            List<String> out = new ArrayList<>();
            for (String s : stack) {
                if (s.startsWith("Caused by:")) break;
                out.add(s);
            }
            return out;
        }

        /** Where it was thrown: the type plus its top frames, so the same bug groups together whatever the message. */
        String signature() {
            List<String> own = ownFrames();
            return type + "@" + String.join("|", own.subList(0, Math.min(4, own.size())));
        }

        public String headline() {
            return message == null ? type : type + ": " + message;
        }
    }

    public record Line(long seq, long time, Level level, String logger, String thread, String message, @Nullable Thrown thrown) {}

    /** One distinct exception: how often it was logged, when, and a sample. */
    public static final class Problem {
        public final String signature;
        public final Thrown sample;
        public final String logger;
        public final String logMessage;
        public final Level level;
        public final long firstSeq, firstTime;
        public long lastSeq, lastTime;
        public int count;

        Problem(Line l) {
            signature = l.thrown().signature();
            sample = l.thrown();
            logger = l.logger();
            logMessage = l.message();
            level = l.level();
            firstSeq = lastSeq = l.seq();
            firstTime = lastTime = l.time();
            count = 1;
        }
    }

    /** Most distinct exceptions remembered; the least recently seen are dropped first. */
    static final int MAX_PROBLEMS = 200;

    private final IntSupplier capacity;
    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private final Map<String, Problem> problems = new LinkedHashMap<>(16, 0.75f, true); // access order: LRU
    private long seq;

    /** @param capacity how many lines to keep (read on each add, so a config change applies at once) */
    public LogBuffer(IntSupplier capacity) {
        this.capacity = capacity;
    }

    /** Records a line and returns it (with its sequence number). */
    public synchronized Line add(long time, Level level, String logger, String thread, String message, @Nullable Thrown thrown) {
        Line l = new Line(++seq, time, level, logger, thread, message, thrown);
        lines.addLast(l);
        int max = Math.max(1, capacity.getAsInt());
        while (lines.size() > max) lines.removeFirst();
        if (thrown != null) {
            Problem p = problems.get(thrown.signature());
            if (p == null) {
                problems.put(thrown.signature(), new Problem(l));
                if (problems.size() > MAX_PROBLEMS) problems.remove(problems.keySet().iterator().next());
            } else {
                p.count++;
                p.lastSeq = l.seq();
                p.lastTime = l.time();
            }
        }
        return l;
    }

    public synchronized long latest() {
        return seq;
    }

    /** The newest {@code limit} lines after {@code afterSeq} that match, oldest first. */
    public synchronized List<Line> query(long afterSeq, Predicate<Line> match, int limit) {
        List<Line> out = new ArrayList<>();
        var it = lines.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            Line l = it.next();
            if (l.seq() <= afterSeq) break;
            if (match.test(l)) out.add(l);
        }
        return out.reversed();
    }

    /** Distinct exceptions that match, most recently seen first. */
    public synchronized List<Problem> problems(Predicate<Problem> match) {
        return problems.values().stream().filter(match).sorted(Comparator.comparingLong((Problem p) -> p.lastSeq).reversed()).toList();
    }

    public synchronized void clear() {
        lines.clear();
        problems.clear();
    }
}
