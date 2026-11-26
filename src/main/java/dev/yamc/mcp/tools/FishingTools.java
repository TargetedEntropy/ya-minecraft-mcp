package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.client.EventLog;
import dev.yamc.mcp.client.GameEvent;
import dev.yamc.mcp.client.Ticker;
import dev.yamc.mcp.json.Stacks;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Regs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbilities;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Fishing: line/bobber state and an automatic cast-wait-reel action. */
final class FishingTools {
    private FishingTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("fishing_status").title("Fishing status")
                .desc("The player's fishing state: which hand holds a rod (durability, enchantments like Lure / Luck of the Sea), whether a line is cast, "
                        + "the bobber's position and state (flying, bobbing, hooked_in_entity), whether it is in water, whether a fish is biting right now, "
                        + "and open-water fishing (needed for treasure; singleplayer only). Fish bites are also recorded as fish_bite events and catches as fish_caught.")
                .handler(a -> ToolResult.json(status())));

        r.add(Gates.action("fish").title("Fish")
                .desc("Fish automatically with the held rod: cast, wait for a bite, reel in at the right moment, and collect the catch. Repeats for 'casts' catches. "
                        + "Stops early if the rod is about to break, the line lands outside water, or no fish bites within timeout_seconds. "
                        + "Face water first (look_at). In singleplayer the game must not be paused (F3+P disables pause on focus loss).")
                .schema(Schema.of().integer("casts", "Number of catches to attempt (default 1, max 64).")
                        .integer("timeout_seconds", "Max wait for a bite per cast (default 60, max 300).")
                        .integer("min_durability", "Stop when the rod has this many uses left or fewer (default 2; 0 = use it until it breaks)."))
                .anyThread()
                .handler(FishingTools::fish));
    }

    static boolean isRod(ItemStack s) {
        return !s.isEmpty() && (s.getItem() instanceof FishingRodItem || s.canPerformAction(ItemAbilities.FISHING_ROD_CAST));
    }

    /** The hand holding a rod (main hand preferred), or null. */
    static InteractionHand rodHand(LocalPlayer p) {
        if (isRod(p.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (isRod(p.getOffhandItem())) return InteractionHand.OFF_HAND;
        return null;
    }

    static JsonObject status() {
        LocalPlayer p = Ctx.player();
        JsonObject o = new JsonObject();
        InteractionHand hand = rodHand(p);
        o.addProperty("holding_rod", hand != null);
        if (hand != null) {
            o.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
            o.add("rod", Stacks.json(p.getItemInHand(hand), false, p.registryAccess()));
        }
        FishingHook hook = p.fishing;
        o.addProperty("line_cast", hook != null && !hook.isRemoved());
        if (hook != null && !hook.isRemoved()) {
            JsonObject h = new JsonObject();
            h.addProperty("state", hook.currentState.name().toLowerCase(Locale.ROOT));
            h.addProperty("biting", hook.biting);
            h.add("pos", Json.vec(hook.position()));
            h.addProperty("distance", Json.round(hook.distanceTo(p)));
            h.addProperty("in_water", hook.level().getFluidState(hook.blockPosition()).is(FluidTags.WATER));
            if (hook.getHookedIn() != null) h.addProperty("hooked_entity", hook.getHookedIn().getName().getString());
            Boolean open = serverOpenWater(p);
            if (open != null) h.addProperty("open_water", open);
            o.add("bobber", h);
        }
        return o;
    }

    /** Open-water state from the integrated server's copy of the hook; the client cannot compute it. Null if unknown. */
    private static Boolean serverOpenWater(LocalPlayer p) {
        MinecraftServer s = Regs.server();
        if (s == null) return null;
        var sp = s.getPlayerList().getPlayer(p.getUUID());
        return sp == null || sp.fishing == null ? null : sp.fishing.isOpenWaterFishing();
    }

    private enum Phase { CAST, WAIT_HOOK, WAIT_BITE, COLLECT }

    static ToolResult fish(Args a) throws Exception {
        int casts = a.integer("casts", 1, 1, 64);
        int timeout = a.integer("timeout_seconds", 60, 5, 300);
        int minDurability = a.integer("min_durability", 2, 0, 100000);
        long total = Math.min(30 * 60_000L, casts * (timeout + 15) * 1000L);
        return ToolResult.json(Ticker.run(() -> {
            LocalPlayer p0 = Ctx.player();
            ActionTools.requireUnpaused();
            if (rodHand(p0) == null) throw new ToolException("Not holding a fishing rod (main or off hand). Use select_slot first.");
            Minecraft mc = Ctx.mc();
            long startSeq = EventLog.latest();
            return new Ticker.Job<JsonObject>() {
                Phase phase = Phase.CAST;
                int done, ticksInPhase, bites;
                String stop;

                @Override
                public JsonObject step() {
                    LocalPlayer p = mc.player;
                    if (p == null || mc.level == null) return finish("left world");
                    InteractionHand hand = rodHand(p);
                    ticksInPhase++;
                    switch (phase) {
                        case CAST -> {
                            if (done >= casts) return finish("done");
                            if (hand == null) return finish("rod gone (broke or was moved)");
                            ItemStack rod = p.getItemInHand(hand);
                            if (minDurability > 0 && rod.isDamageableItem() && rod.getMaxDamage() - rod.getDamageValue() <= minDurability)
                                return finish("rod durability at " + (rod.getMaxDamage() - rod.getDamageValue()) + " (min_durability " + minDurability + ")");
                            if (p.fishing == null) use(p, hand);
                            next(Phase.WAIT_HOOK);
                        }
                        case WAIT_HOOK -> {
                            FishingHook hook = p.fishing;
                            if (hook != null && hook.currentState == FishingHook.FishHookState.BOBBING) next(Phase.WAIT_BITE);
                            else if (hook != null && hook.currentState == FishingHook.FishHookState.HOOKED_IN_ENTITY) return reelAndStop(p, hand, "the line hooked an entity");
                            else if (ticksInPhase > 100) return reelAndStop(p, hand, "the bobber did not land in water (face open water and try again)");
                        }
                        case WAIT_BITE -> {
                            FishingHook hook = p.fishing;
                            if (hook == null) {
                                next(Phase.CAST); // line broke or was reeled in by someone else; recast
                            } else if (hook.biting) {
                                bites++;
                                use(p, hand);
                                next(Phase.COLLECT);
                            } else if (ticksInPhase > timeout * 20) {
                                return reelAndStop(p, hand, "no bite within " + timeout + "s");
                            }
                        }
                        case COLLECT -> {
                            if (ticksInPhase >= 40) { // the catch flies to the player and the inventory watcher records it
                                done++;
                                next(Phase.CAST);
                            }
                        }
                    }
                    return null;
                }

                @Override
                public void end() {
                    LocalPlayer p = mc.player;
                    if (p != null && p.fishing != null && stop == null) use(p, rodHand(p)); // cancelled or failed mid-cast: reel in
                }

                private void next(Phase ph) {
                    phase = ph;
                    ticksInPhase = 0;
                }

                private void use(LocalPlayer p, InteractionHand hand) {
                    if (hand == null) return;
                    InteractionResult res = mc.gameMode.useItem(p, hand);
                    if (res.shouldSwing()) p.swing(hand);
                }

                private JsonObject reelAndStop(LocalPlayer p, InteractionHand hand, String why) {
                    if (p.fishing != null) use(p, hand);
                    return finish(why);
                }

                private JsonObject finish(String why) {
                    stop = why;
                    Map<String, Integer> caught = new TreeMap<>();
                    for (EventLog.Event e : EventLog.since(startSeq, java.util.Set.of(GameEvent.FISH_CAUGHT), 10_000, false))
                        caught.merge(e.data().get("item").getAsString(), e.data().get("count").getAsInt(), Integer::sum);
                    JsonObject o = new JsonObject();
                    o.addProperty("casts_completed", done);
                    o.addProperty("bites", bites);
                    o.addProperty("stopped", why);
                    JsonObject c = new JsonObject();
                    caught.forEach(c::addProperty);
                    o.add("caught", c);
                    JsonArray note = new JsonArray();
                    if (caught.isEmpty() && bites > 0) note.add("Bites were reeled in but no items arrived (full inventory, or the catch is still flying).");
                    if (!note.isEmpty()) o.add("notes", note);
                    return o;
                }
            };
        }, total));
    }
}
