package dev.yamc.mcp.push;

import dev.yamc.mcp.Config;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.client.GameEvent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Which game events are pushed to connected MCP clients, with per-type throttling ({@link GameEvent#minPushIntervalMs}). */
public final class PushPolicy {
    private static EnumSet<GameEvent> types;
    private static volatile boolean enabled = true;
    private static final Map<GameEvent, Long> lastPush = new EnumMap<>(GameEvent.class);

    private PushPolicy() {}

    public static synchronized Set<GameEvent> types() {
        if (types == null) types = fromConfig();
        return Collections.unmodifiableSet(EnumSet.copyOf(types));
    }

    private static EnumSet<GameEvent> fromConfig() {
        EnumSet<GameEvent> s = EnumSet.noneOf(GameEvent.class);
        for (String id : Config.PUSH_EVENT_TYPES.get()) {
            GameEvent e = GameEvent.byId(id);
            if (e != null) s.add(e);
            else YamcMcp.LOGGER.warn("Ignoring unknown event type '{}' in push.eventTypes", id);
        }
        if (s.isEmpty()) for (GameEvent e : GameEvent.values()) if (e.pushedByDefault) s.add(e);
        return s;
    }

    public static synchronized void setTypes(Set<GameEvent> t) {
        types = t.isEmpty() ? EnumSet.noneOf(GameEvent.class) : EnumSet.copyOf(t);
    }

    public static boolean enabled() {
        return enabled && Config.PUSH_ENABLED.get();
    }

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    /** True if this event should be pushed now (and records the push for throttling). */
    static synchronized boolean shouldPush(GameEvent type) {
        if (!enabled() || !types().contains(type)) return false;
        long now = System.currentTimeMillis();
        Long last = lastPush.get(type);
        if (type.minPushIntervalMs > 0 && last != null && now - last < type.minPushIntervalMs) return false;
        lastPush.put(type, now);
        return true;
    }
}
