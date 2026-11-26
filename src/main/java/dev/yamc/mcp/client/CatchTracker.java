package dev.yamc.mcp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Fishing events, ticked every client tick by {@link GameWatcher}.
 * <ul>
 *   <li>fish_bite: the bobber's synced bite flag went up.</li>
 *   <li>fish_caught: after the line is reeled in on a bite, the server spawns each loot item at the bobber and
 *       launches it at the player. Every item entity that newly appears on the bobber side of that path within
 *       {@link #CATCH_WINDOW_TICKS} is that catch. Inventory changes are not used, so unrelated pickups never count.</li>
 * </ul>
 */
public final class CatchTracker {
    /** Ticks after a bite-time reel-in during which spawned loot is attributed to it. */
    public static final int CATCH_WINDOW_TICKS = 20;
    /** Loot starts at the bobber and arcs upward toward the player; accept entities this close to that path. */
    private static final double PATH_TOLERANCE = 3.0;
    /** Client tickCount of an entity that was just added (loot is seen within a couple of ticks of spawning). */
    private static final int FRESH_TICKS = 3;
    /**
     * Loot moves a tenth of the way to the player per tick, so when first seen it is still in the bobber half of the
     * path. Items appearing near the player (drops, other pickups) are never attributed.
     */
    private static final double MAX_PATH_FRACTION = 0.6;

    private enum Line { NONE, CAST, BITING }

    private Line last = Line.NONE;
    private Vec3 bobber;
    /** Bobber position at the bite-time reel-in, while its catch window is open; else null. */
    private Vec3 reeledAt;
    private int windowLeft;
    private final Set<Integer> attributed = new HashSet<>();

    void tick(LocalPlayer p, ClientLevel level) {
        Line now = p.fishing == null || p.fishing.isRemoved() ? Line.NONE : p.fishing.biting ? Line.BITING : Line.CAST;
        if (now != Line.NONE) bobber = p.fishing.position();
        if (now == Line.BITING && last != Line.BITING) EventLog.add(GameEvent.FISH_BITE, new JsonObject());
        if (last == Line.BITING && now == Line.NONE && bobber != null) {
            reeledAt = bobber;
            windowLeft = CATCH_WINDOW_TICKS;
            attributed.clear();
        }
        last = now;
        if (reeledAt != null) collect(p, level);
    }

    private void collect(LocalPlayer p, ClientLevel level) {
        Vec3 to = p.position();
        AABB area = new AABB(reeledAt, to).inflate(PATH_TOLERANCE);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, area)) {
            if (e.tickCount > FRESH_TICKS || !onLootPath(e.position(), reeledAt, to) || !attributed.add(e.getId())) continue;
            JsonObject c = new JsonObject();
            c.addProperty("item", BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString());
            c.addProperty("count", e.getItem().getCount());
            EventLog.add(GameEvent.FISH_CAUGHT, c);
        }
        if (--windowLeft <= 0) reeledAt = null;
    }

    /** True if x is within {@link #PATH_TOLERANCE} of the bobber-side part of the segment from bobber a to player b. */
    static boolean onLootPath(Vec3 x, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 == 0 ? 0 : x.subtract(a).dot(ab) / len2;
        if (t > MAX_PATH_FRACTION) return false;
        t = Math.max(0, t);
        return x.distanceTo(a.add(ab.scale(t))) <= PATH_TOLERANCE;
    }

    void reset() {
        last = Line.NONE;
        bobber = null;
        reeledAt = null;
        attributed.clear();
    }
}
