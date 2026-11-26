package dev.yamc.mcp.push;

import com.google.gson.JsonObject;
import dev.yamc.mcp.client.EventLog;
import dev.yamc.mcp.mcp.SseHub;

/**
 * Turns {@link EventLog} events into Claude Code channel notifications
 * ({@code notifications/claude/channel}) and broadcasts them on the SSE stream.
 */
public final class PushNotifier {
    private PushNotifier() {}

    public static void install() {
        EventLog.subscribe(PushNotifier::onEvent);
    }

    private static void onEvent(EventLog.Event e) {
        if (SseHub.openStreams() == 0 || !PushPolicy.shouldPush(e.type())) return;
        JsonObject params = new JsonObject();
        params.addProperty("content", e.type().describe(e.data()));
        JsonObject meta = new JsonObject();
        meta.addProperty("event", e.type().id);
        meta.addProperty("seq", String.valueOf(e.seq()));
        if (e.type().untrustedText) meta.addProperty("trust", "untrusted_game_text");
        params.add("meta", meta);
        JsonObject n = new JsonObject();
        n.addProperty("jsonrpc", "2.0");
        n.addProperty("method", "notifications/claude/channel");
        n.add("params", params);
        SseHub.broadcast(n);
    }
}
