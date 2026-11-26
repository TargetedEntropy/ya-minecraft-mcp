package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import dev.yamc.mcp.client.EventLog;
import dev.yamc.mcp.client.GameEvent;
import dev.yamc.mcp.client.Ticker;
import dev.yamc.mcp.mcp.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The {@code fish} action: cast, wait for the bite flag, reel in, wait for the catch (the fish_caught event),
 * repeat. One phase method per state; each returns the final result to stop, or null to keep ticking.
 */
final class FishingJob implements Ticker.Job<JsonObject> {
    /** Ticks to wait for a cast bobber to settle in water. */
    private static final int LAND_TICKS = 100;
    /** Ticks to wait for the catch to reach the inventory after reeling in (full inventory: it never does). */
    private static final int COLLECT_TICKS = 100;

    private enum Phase { CAST, LANDING, WAITING, COLLECTING }

    private final Minecraft mc = Minecraft.getInstance();
    private final int casts, timeoutTicks, minDurability;
    private final long startSeq = EventLog.latest();

    private Phase phase = Phase.CAST;
    private int ticksInPhase, completed, bites;
    private long reelSeq;

    /** Runs on the client thread: validates that fishing can start. */
    FishingJob(int casts, int timeoutSeconds, int minDurability) {
        Ctx.player();
        Ctx.requireUnpaused();
        if (FishingTools.rodHand(Ctx.player()) == null)
            throw new ToolException("Not holding a fishing rod (main or off hand). Use select_slot first.");
        this.casts = casts;
        this.timeoutTicks = timeoutSeconds * 20;
        this.minDurability = minDurability;
    }

    @Override
    public JsonObject step() {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return result("left world");
        ticksInPhase++;
        return switch (phase) {
            case CAST -> cast(p);
            case LANDING -> landing(p);
            case WAITING -> waiting(p);
            case COLLECTING -> collecting();
        };
    }

    private JsonObject cast(LocalPlayer p) {
        if (completed >= casts) return result("done");
        InteractionHand hand = FishingTools.rodHand(p);
        if (hand == null) return result("rod gone (broke or was moved)");
        ItemStack rod = p.getItemInHand(hand);
        int usesLeft = rod.getMaxDamage() - rod.getDamageValue();
        if (minDurability > 0 && rod.isDamageableItem() && usesLeft <= minDurability)
            return result("rod durability at " + usesLeft + " (min_durability " + minDurability + ")");
        if (p.fishing == null) use(p);
        return next(Phase.LANDING);
    }

    private JsonObject landing(LocalPlayer p) {
        FishingHook hook = p.fishing;
        if (hook != null && hook.currentState == FishingHook.FishHookState.BOBBING) return next(Phase.WAITING);
        if (hook != null && hook.currentState == FishingHook.FishHookState.HOOKED_IN_ENTITY) return reelAndStop(p, "the line hooked an entity");
        if (ticksInPhase > LAND_TICKS) return reelAndStop(p, "the bobber did not land in water (face open water and try again)");
        return null;
    }

    private JsonObject waiting(LocalPlayer p) {
        FishingHook hook = p.fishing;
        if (hook == null) return next(Phase.CAST); // line broke or was reeled in by hand: recast
        if (hook.biting) {
            bites++;
            reelSeq = EventLog.latest();
            use(p);
            return next(Phase.COLLECTING);
        }
        if (ticksInPhase > timeoutTicks) return reelAndStop(p, "no bite within " + timeoutTicks / 20 + "s");
        return null;
    }

    private JsonObject collecting() {
        boolean caught = !EventLog.since(reelSeq, Set.of(GameEvent.FISH_CAUGHT), 1, false).isEmpty();
        if (!caught && ticksInPhase < COLLECT_TICKS) return null;
        completed++;
        return next(Phase.CAST);
    }

    @Override
    public void end(boolean finished) {
        LocalPlayer p = mc.player;
        if (!finished && p != null && p.fishing != null) use(p); // failed or cancelled mid-cast: reel the line in
    }

    private JsonObject next(Phase ph) {
        phase = ph;
        ticksInPhase = 0;
        return null;
    }

    private void use(LocalPlayer p) {
        InteractionHand hand = FishingTools.rodHand(p);
        if (hand == null) return;
        if (mc.gameMode.useItem(p, hand).shouldSwing()) p.swing(hand);
    }

    private JsonObject reelAndStop(LocalPlayer p, String why) {
        if (p.fishing != null) use(p);
        return result(why);
    }

    private JsonObject result(String why) {
        Map<String, Integer> caught = new TreeMap<>();
        for (EventLog.Event e : EventLog.since(startSeq, Set.of(GameEvent.FISH_CAUGHT), 10_000, false))
            caught.merge(e.data().get("item").getAsString(), e.data().get("count").getAsInt(), Integer::sum);
        JsonObject o = new JsonObject();
        o.addProperty("casts_completed", completed);
        o.addProperty("bites", bites);
        o.addProperty("stopped", why);
        JsonObject c = new JsonObject();
        caught.forEach(c::addProperty);
        o.add("caught", c);
        if (caught.isEmpty() && bites > 0) o.addProperty("note", "Bites were reeled in but no items arrived (full inventory?).");
        return o;
    }
}
