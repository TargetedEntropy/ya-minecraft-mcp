package dev.yamc.mcp.client;

import com.google.gson.JsonObject;
import dev.yamc.mcp.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Thread-safe ring buffer of game events with monotonically increasing sequence numbers. */
public final class EventLog {
    public record Event(long seq, long time, GameEvent type, JsonObject data) {
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("seq", seq);
            o.addProperty("time", time);
            o.addProperty("type", type.id);
            for (var e : data.entrySet()) o.add(e.getKey(), e.getValue());
            return o;
        }
    }

    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    private static long seq;
    private static long lastPolled;
    private static final List<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();

    private EventLog() {}

    /** Calls the listener (on the recording thread) for every event added from now on. Run the returned handle to unsubscribe. */
    public static Runnable subscribe(Consumer<Event> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    public static void add(GameEvent type, JsonObject data) {
        Event ev;
        synchronized (EVENTS) {
            ev = new Event(++seq, System.currentTimeMillis(), type, data);
            EVENTS.addLast(ev);
            int max = Config.EVENT_BUFFER_SIZE.get();
            while (EVENTS.size() > max) EVENTS.removeFirst();
            EVENTS.notifyAll();
        }
        for (Consumer<Event> l : LISTENERS) l.accept(ev);
    }

    public static long latest() {
        synchronized (EVENTS) {
            return seq;
        }
    }

    public static long lastPolled() {
        synchronized (EVENTS) {
            return lastPolled;
        }
    }

    public static List<Event> since(long after, Set<GameEvent> types, int limit, boolean markPolled) {
        synchronized (EVENTS) {
            List<Event> out = new ArrayList<>();
            for (Event e : EVENTS) {
                if (e.seq <= after) continue;
                if (!types.isEmpty() && !types.contains(e.type)) continue;
                out.add(e);
            }
            if (out.size() > limit) out = new ArrayList<>(out.subList(out.size() - limit, out.size()));
            if (markPolled) lastPolled = seq;
            return out;
        }
    }

    /** Blocks until an event after {@code after} matches, or the timeout elapses. Returns null on timeout. */
    public static Event waitFor(long after, Predicate<Event> match, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (EVENTS) {
            long cursor = after;
            while (true) {
                for (Event e : EVENTS) {
                    if (e.seq <= cursor) continue;
                    if (match.test(e)) return e;
                }
                cursor = seq;
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                EVENTS.wait(left);
            }
        }
    }
}
