package dev.yamc.mcp.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEventTest {
    @Test
    void idsAreUniqueAndResolve() {
        assertEquals(GameEvent.values().length, new HashSet<>(GameEvent.ids()).size());
        for (GameEvent e : GameEvent.values()) assertEquals(e, GameEvent.byId(e.id));
        assertNull(GameEvent.byId("damge"));
    }

    @Test
    void defaultPushSetIsTheRareEvents() {
        assertEquals(List.of("damage", "death", "dimension_change", "level_up", "gamemode_change", "join_world", "leave_world"),
                GameEvent.defaultPushIds());
        assertFalse(GameEvent.CHAT.pushedByDefault, "chat must never be pushed by default");
    }

    @Test
    void traits() {
        assertEquals(5000, GameEvent.DAMAGE.minPushIntervalMs);
        assertTrue(GameEvent.CHAT.untrustedText);
        assertTrue(GameEvent.SYSTEM_MESSAGE.untrustedText);
        assertFalse(GameEvent.DAMAGE.untrustedText);
    }

    @Test
    void describeHandlesMissingFields() {
        JsonObject full = new JsonObject();
        full.addProperty("delta", -3);
        full.addProperty("health", 17);
        assertEquals("Player took 3.0 damage, health now 17.", GameEvent.DAMAGE.describe(full));
        for (GameEvent e : GameEvent.values()) assertNotNull(e.describe(full));
    }
}
