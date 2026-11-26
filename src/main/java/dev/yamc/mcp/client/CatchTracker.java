package dev.yamc.mcp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;

/**
 * Fishing events. Records fish_bite when the bobber's (synced) bite flag goes up, and turns the items gained
 * shortly after the line is reeled in on a bite into fish_caught, by listening to the event log's item_gained.
 */
final class CatchTracker {
    /** How long after a bite-time reel-in gained items count as the catch (it flies to the player first). */
    private static final long CATCH_WINDOW_MS = 3000;

    enum Line { NONE, CAST, BITING }

    private volatile long catchUntilMs;

    static Line line(LocalPlayer p) {
        if (p.fishing == null || p.fishing.isRemoved()) return Line.NONE;
        return p.fishing.biting ? Line.BITING : Line.CAST;
    }

    void lineChanged(Line before, Line now, LocalPlayer p) {
        if (now == Line.BITING) EventLog.add(GameEvent.FISH_BITE, new JsonObject());
        if (before == Line.BITING && now == Line.NONE) catchUntilMs = System.currentTimeMillis() + CATCH_WINDOW_MS;
    }

    void onEvent(EventLog.Event e) {
        if (e.type() != GameEvent.ITEM_GAINED || e.time() > catchUntilMs) return;
        JsonObject c = new JsonObject();
        c.add("item", e.data().get("item"));
        c.add("count", e.data().get("delta"));
        EventLog.add(GameEvent.FISH_CAUGHT, c);
    }
}
