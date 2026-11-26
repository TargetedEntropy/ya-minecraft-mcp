package dev.yamc.mcp.client;

import dev.yamc.mcp.Config;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Which game events are pushed to connected MCP clients, with per-type throttling. */
public final class PushPolicy {
    /** Events that are pushed by default: rare and worth interrupting for. */
    public static final Set<String> DEFAULT_TYPES = Set.of("death", "dimension_change", "level_up",
            "join_world", "leave_world", "gamemode_change", "damage");

    /** Minimum milliseconds between two pushes of the same type (bursty event types). */
    private static final Map<String, Long> MIN_INTERVAL = Map.of(
            "damage", 5000L, "heal", 10000L, "item_gained", 3000L, "item_lost", 3000L,
            "actionbar", 5000L, "biome_change", 10000L, "screen_open", 1000L, "screen_close", 1000L);

    private static volatile Set<String> types;
    private static volatile boolean enabled = true;
    private static final Map<String, Long> lastPush = new HashMap<>();

    private PushPolicy() {}

    public static synchronized Set<String> types() {
        if (types == null) {
            Set<String> s = new LinkedHashSet<>(Config.PUSH_EVENT_TYPES.get());
            types = s.isEmpty() ? new LinkedHashSet<>(DEFAULT_TYPES) : s;
        }
        return Collections.unmodifiableSet(types);
    }

    public static synchronized void setTypes(Set<String> t) {
        types = new LinkedHashSet<>(t);
    }

    public static boolean enabled() {
        return enabled && Config.PUSH_ENABLED.get();
    }

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    /** True if this event should be pushed now (and records the push for throttling). */
    public static synchronized boolean shouldPush(String type) {
        if (!enabled() || !types().contains(type)) return false;
        long now = System.currentTimeMillis();
        Long min = MIN_INTERVAL.get(type);
        if (min != null) {
            Long last = lastPush.get(type);
            if (last != null && now - last < min) return false;
        }
        lastPush.put(type, now);
        return true;
    }
}
