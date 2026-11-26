package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import dev.yamc.mcp.client.CatchTracker;
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
import java.util.TreeMap;

/**
 * The {@code fish} action: cast, wait for the bite flag, reel in, collect the catch, repeat.
 * One method per phase; each returns the final result to stop, or null to keep ticking.
 * <p>
 * Ownership: only one fishing job runs at a time ({@link #active}), and the job only ever reels in the hook it
 * cast or adopted, so a line the player replaces by hand is left alone. Catches are tallied on the job as
 * {@link CatchTracker} reports them, independent of the size of the shared event buffer.
 */
final class FishingJob implements Ticker.Job<JsonObject> {
    /** Ticks to wait for a cast bobber to settle in water. */
    private static final int LAND_TICKS = 100;

    /** The running fishing job; touched only on the client thread. */
    private static FishingJob active;

    private enum Phase { CAST, LANDING, WAITING, COLLECTING }

    private final Minecraft mc = Minecraft.getInstance();
    private final int casts, timeoutTicks, minDurability;
    private final long deadlineMs;
    private final Map<String, Integer> caught = new TreeMap<>();
    private final Runnable unsubscribe;

    private Phase phase = Phase.CAST;
    private int ticksInPhase, completed, bites, emptyReels, caughtBeforeReel;
    /** The hook this job cast or adopted; the only one it will reel in. */
    private FishingHook hook;

    /** Runs on the client thread during setup: validates and reserves fishing for this job. */
    FishingJob(int casts, int timeoutSeconds, int minDurability, long deadlineMs) {
        LocalPlayer p = Ctx.player();
        Ctx.requireUnpaused();
        if (FishingTools.rodHand(p) == null) throw new ToolException("Not holding a fishing rod (main or off hand). Use select_slot first.");
        if (active != null) throw new ToolException("Already fishing: another fish call is running. Wait for it to finish.");
        this.casts = casts;
        this.timeoutTicks = timeoutSeconds * 20;
        this.minDurability = minDurability;
        this.deadlineMs = System.currentTimeMillis() + deadlineMs;
        this.unsubscribe = EventLog.subscribe(e -> {
            if (e.type() == GameEvent.FISH_CAUGHT)
                caught.merge(e.data().get("item").getAsString(), e.data().get("count").getAsInt(), Integer::sum);
        });
        active = this;
    }

    @Override
    public JsonObject step() {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return result("left world");
        if (phase != Phase.COLLECTING && System.currentTimeMillis() > deadlineMs) return reelAndStop(p, "time limit for one call reached; call fish again to continue");
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
        FishingHook h = p.fishing;
        if (h != null) hook = h; // our cast, or the player's line that was already out
        if (h != null && h.currentState == FishingHook.FishHookState.BOBBING) return next(Phase.WAITING);
        if (h != null && h.currentState == FishingHook.FishHookState.HOOKED_IN_ENTITY) return reelAndStop(p, "the line hooked an entity");
        if (ticksInPhase > LAND_TICKS) return reelAndStop(p, "the bobber did not land in water (face open water and try again)");
        return null;
    }

    private JsonObject waiting(LocalPlayer p) {
        // The player (or the game) took the line back: stop instead of fighting over the rod.
        if (p.fishing == null) return result("the line was reeled in or broke outside this action");
        if (p.fishing != hook) return result("the line was recast by hand; stopped and left it alone");
        if (hook.biting) {
            bites++;
            caughtBeforeReel = total();
            use(p);
            hook = null;
            return next(Phase.COLLECTING);
        }
        if (ticksInPhase > timeoutTicks) return reelAndStop(p, "no bite within " + timeoutTicks / 20 + "s");
        return null;
    }

    /** Waits out the tracker's catch window so every item of this catch is tallied before the next cast. */
    private JsonObject collecting() {
        if (ticksInPhase <= CatchTracker.CATCH_WINDOW_TICKS + 1) return null;
        if (total() == caughtBeforeReel) emptyReels++;
        completed++;
        return next(Phase.CAST);
    }

    @Override
    public void end(boolean finished) {
        unsubscribe.run();
        if (active == this) active = null;
        LocalPlayer p = mc.player;
        if (!finished && p != null && hook != null && p.fishing == hook) use(p); // failed or cancelled: reel in our own line only
    }

    private int total() {
        return caught.values().stream().mapToInt(Integer::intValue).sum();
    }

    private JsonObject next(Phase ph) {
        phase = ph;
        ticksInPhase = 0;
        return null;
    }

    private void use(LocalPlayer p) {
        InteractionHand hand = FishingTools.rodHand(p);
        if (hand != null && mc.gameMode.useItem(p, hand).shouldSwing()) p.swing(hand);
    }

    private JsonObject reelAndStop(LocalPlayer p, String why) {
        if (hook != null && p.fishing == hook) use(p);
        hook = null;
        return result(why);
    }

    private JsonObject result(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("casts_completed", completed);
        o.addProperty("bites", bites);
        o.addProperty("stopped", why);
        JsonObject c = new JsonObject();
        caught.forEach(c::addProperty);
        o.add("caught", c);
        if (emptyReels > 0) o.addProperty("reels_without_catch", emptyReels);
        return o;
    }
}
