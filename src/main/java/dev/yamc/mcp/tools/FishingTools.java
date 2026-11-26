package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import dev.yamc.mcp.client.Ticker;
import dev.yamc.mcp.json.Stacks;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.ClientThread;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.IntegratedServer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbilities;

import java.util.Locale;
import java.util.UUID;

/** Fishing: line/bobber state and an automatic cast-wait-reel action. */
final class FishingTools {
    private FishingTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("fishing_status").title("Fishing status")
                .desc("The player's fishing state: which hand holds a rod (durability, enchantments like Lure / Luck of the Sea), whether a line is cast, "
                        + "the bobber's position and state (flying, bobbing, hooked_in_entity), whether it is in water, whether a fish is biting right now, "
                        + "and open-water fishing (needed for treasure; singleplayer only). Fish bites are also recorded as fish_bite events and catches as fish_caught.")
                .anyThread()
                .handler(a -> ToolResult.json(status())));

        r.add(Gates.action("fish").title("Fish")
                .desc("Fish automatically with the held rod: cast, wait for a bite, reel in at the right moment, and collect the catch. Repeats for 'casts' catches. "
                        + "Stops early if the rod is about to break, the line lands outside water, or no fish bites within timeout_seconds. "
                        + "One call runs at most 8 minutes (it then returns what it caught so far; call again to continue). Only one fish call runs at a time. "
                        + "Face water first (look_at). In singleplayer the game must not be paused (F3+P disables pause on focus loss).")
                .schema(Schema.of().integer("casts", "Number of catches to attempt (default 1, max 64).")
                        .integer("timeout_seconds", "Max wait for a bite per cast (default 60, max 300).")
                        .integer("min_durability", "Stop when the rod has this many uses left or fewer (default 2; 0 = use it until it breaks)."))
                .anyThread()
                .handler(a -> {
                    int casts = a.integer("casts", 1, 1, 64), timeout = a.integer("timeout_seconds", 60, 5, 300);
                    int minDurability = a.integer("min_durability", 2, 0, 100000);
                    long budgetMs = Math.min(BATCH_LIMIT_MS, casts * (timeout + 15) * 1000L);
                    return ToolResult.json(Ticker.run(() -> new FishingJob(casts, timeout, minDurability, budgetMs), budgetMs + 30_000));
                }));
    }

    /** One fish call's time budget: below Ticker.MAX_JOB_MS so the call returns its own result before any transport gives up. */
    private static final long BATCH_LIMIT_MS = 8 * 60_000L;

    static boolean isRod(ItemStack s) {
        return !s.isEmpty() && (s.getItem() instanceof FishingRodItem || s.canPerformAction(ItemAbilities.FISHING_ROD_CAST));
    }

    /** The hand holding a rod (main hand preferred), or null. */
    static InteractionHand rodHand(LocalPlayer p) {
        if (isRod(p.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (isRod(p.getOffhandItem())) return InteractionHand.OFF_HAND;
        return null;
    }

    /** Client view of the rod and line; open water comes from the integrated server's copy of the hook (the client cannot compute it). */
    static JsonObject status() throws Exception {
        record Seen(JsonObject json, UUID player, UUID hook) {}
        Seen seen = ClientThread.call(() -> {
            LocalPlayer p = Ctx.player();
            JsonObject o = new JsonObject();
            InteractionHand hand = rodHand(p);
            o.addProperty("holding_rod", hand != null);
            if (hand != null) {
                o.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
                o.add("rod", Stacks.json(p.getItemInHand(hand), false, p.registryAccess()));
            }
            FishingHook hook = p.fishing;
            boolean out = hook != null && !hook.isRemoved();
            o.addProperty("line_cast", out);
            if (out) {
                JsonObject h = new JsonObject();
                h.addProperty("state", hook.currentState.name().toLowerCase(Locale.ROOT));
                h.addProperty("biting", hook.biting);
                h.add("pos", Json.vec(hook.position()));
                h.addProperty("distance", Json.round(hook.distanceTo(p)));
                h.addProperty("in_water", hook.level().getFluidState(hook.blockPosition()).is(FluidTags.WATER));
                if (hook.getHookedIn() != null) h.addProperty("hooked_entity", hook.getHookedIn().getName().getString());
                o.add("bobber", h);
            }
            return new Seen(o, p.getUUID(), out ? hook.getUUID() : null);
        });
        MinecraftServer server = IntegratedServer.get();
        if (seen.hook() != null && server != null) {
            Boolean open = ClientThread.callServer(server, () -> {
                var sp = server.getPlayerList().getPlayer(seen.player());
                // Only the same hook the client sampled: the player may have recast in between.
                return sp == null || sp.fishing == null || !sp.fishing.getUUID().equals(seen.hook()) ? null : sp.fishing.isOpenWaterFishing();
            }, 5000);
            if (open != null) seen.json().getAsJsonObject("bobber").addProperty("open_water", open);
        }
        return seen.json();
    }
}
