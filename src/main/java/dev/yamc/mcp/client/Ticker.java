package dev.yamc.mcp.client;

import java.util.ArrayList;
import java.util.List;

/** Runs scheduled per-tick tasks on the client thread (used by multi-tick actions such as move/mine). */
public final class Ticker {
    /** Returns true when finished. */
    public interface Task {
        boolean tick();
    }

    private static final List<Task> TASKS = new ArrayList<>();

    private Ticker() {}

    /** Must be called on the client thread. */
    public static void schedule(Task t) {
        TASKS.add(t);
    }

    static void tick() {
        if (TASKS.isEmpty()) return;
        List<Task> done = new ArrayList<>();
        for (Task t : new ArrayList<>(TASKS)) {
            try {
                if (t.tick()) done.add(t);
            } catch (Throwable e) {
                done.add(t);
            }
        }
        TASKS.removeAll(done);
    }

    public static void cancelAll() {
        TASKS.clear();
    }
}
