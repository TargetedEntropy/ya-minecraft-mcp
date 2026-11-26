package dev.yamc.mcp.client;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Last N chat/system messages (action bar excluded). */
public final class ChatHistory {
    public record Line(long time, GameEvent type, JsonObject data) {}

    private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
    private static final int MAX = 500;

    private ChatHistory() {}

    public static synchronized void add(GameEvent type, JsonObject data) {
        if (type == GameEvent.ACTIONBAR) return;
        LINES.addLast(new Line(System.currentTimeMillis(), type, data));
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    public static synchronized List<Line> snapshot() {
        return new ArrayList<>(LINES);
    }
}
