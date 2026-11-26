package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.json.Stacks;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.Comparator;

/** The open GUI / container and the HUD. */
final class ScreenTools {
    private ScreenTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("open_container").title("Open screen / container")
                .desc("The currently open GUI. For container screens (chests, furnaces, crafting, villager trades, anvils, modded machines) lists every slot with its index (use with container_click), plus furnace progress, brewing, enchanting costs and villager offers.")
                .schema(Schema.of().bool("include_empty", "Include empty slots (default false).")
                        .bool("include_player_inventory", "Include the player's inventory slots of the menu (default false).")
                        .bool("include_components", "Full item components (default false)."))
                .handler(a -> ToolResult.json(openContainer(a))));
        r.add(Tool.builder("hud").title("HUD info")
                .desc("Text shown on the HUD: scoreboard sidebar, boss bars, current title/subtitle and action bar message. Elements that are not currently shown are omitted (boss_bars is always present, possibly empty).")
                .handler(a -> ToolResult.json(hud())));
    }

    static JsonObject openContainer(Args a) {
        Minecraft mc = Ctx.mc();
        LocalPlayer p = Ctx.player();
        JsonObject o = new JsonObject();
        var screen = mc.screen;
        if (screen == null) {
            o.addProperty("open", false);
            o.addProperty("note", "No screen is open. The player inventory is container_id 0 (use open_inventory or player_inventory).");
            return o;
        }
        o.addProperty("open", true);
        o.addProperty("screen", screen.getClass().getName());
        o.addProperty("title", screen.getTitle().getString());
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return o;
        AbstractContainerMenu menu = cs.getMenu();
        o.addProperty("container_id", menu.containerId);
        o.addProperty("menu_class", menu.getClass().getName());
        try {
            o.addProperty("menu_type", String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType())));
        } catch (Exception ignored) {
            o.addProperty("menu_type", "minecraft:player_inventory");
        }
        boolean empty = a.bool("include_empty", false), inv = a.bool("include_player_inventory", false), full = a.bool("include_components", false);
        JsonArray slots = new JsonArray();
        for (Slot s : menu.slots) {
            boolean isInv = s.container instanceof net.minecraft.world.entity.player.Inventory;
            if (isInv && !inv) continue;
            if (!s.hasItem() && !empty) continue;
            JsonObject so = Stacks.json(s.getItem(), full, p.registryAccess());
            so.addProperty("slot", s.index);
            if (isInv) so.addProperty("player_inventory_slot", s.getContainerSlot());
            slots.add(so);
        }
        o.add("slots", slots);
        o.addProperty("total_slots", menu.slots.size());
        if (!menu.getCarried().isEmpty()) o.add("carried", Stacks.json(menu.getCarried(), full, p.registryAccess()));
        if (menu instanceof AbstractFurnaceMenu fm) {
            o.addProperty("cook_progress", Json.round(fm.getBurnProgress()));
            o.addProperty("fuel_remaining", Json.round(fm.getLitProgress()));
            o.addProperty("lit", fm.isLit());
        }
        if (menu instanceof BrewingStandMenu bm) {
            o.addProperty("brew_ticks", bm.getBrewingTicks());
            o.addProperty("fuel", bm.getFuel());
        }
        if (menu instanceof EnchantmentMenu em) {
            JsonArray opts = new JsonArray();
            var reg = p.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            for (int i = 0; i < 3; i++) {
                JsonObject x = new JsonObject();
                x.addProperty("level_cost", em.costs[i]);
                if (em.enchantClue[i] >= 0) {
                    reg.getHolder(em.enchantClue[i]).ifPresent(h -> x.addProperty("clue", h.getRegisteredName()));
                    x.addProperty("clue_level", em.levelClue[i]);
                }
                opts.add(x);
            }
            o.add("enchant_options", opts);
        }
        if (menu instanceof AnvilMenu am) o.addProperty("repair_cost", am.getCost());
        if (menu instanceof MerchantMenu mm) {
            o.addProperty("trader_level", mm.getTraderLevel());
            JsonArray offers = new JsonArray();
            int i = 0;
            for (MerchantOffer of : mm.getOffers()) {
                JsonObject x = new JsonObject();
                x.addProperty("index", i++);
                x.add("cost_a", Stacks.json(of.getCostA(), false, p.registryAccess()));
                if (!of.getCostB().isEmpty()) x.add("cost_b", Stacks.json(of.getCostB(), false, p.registryAccess()));
                x.add("result", Stacks.json(of.getResult(), false, p.registryAccess()));
                x.addProperty("uses", of.getUses());
                x.addProperty("max_uses", of.getMaxUses());
                x.addProperty("out_of_stock", of.isOutOfStock());
                offers.add(x);
            }
            o.add("offers", offers);
        }
        return o;
    }

    static JsonObject hud() {
        Minecraft mc = Ctx.mc();
        ClientLevel l = Ctx.level();
        JsonObject o = new JsonObject();
        var sb = l.getScoreboard();
        var obj = sb.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
        if (obj != null) {
            JsonObject s = new JsonObject();
            s.addProperty("title", obj.getDisplayName().getString());
            JsonArray lines = new JsonArray();
            sb.listPlayerScores(obj).stream().filter(e -> !e.isHidden())
                    .sorted(Comparator.comparingInt(net.minecraft.world.scores.PlayerScoreEntry::value).reversed()).limit(15)
                    .forEach(e -> {
                        JsonObject x = new JsonObject();
                        var team = sb.getPlayersTeam(e.owner());
                        String name = e.display() != null ? e.display().getString()
                                : net.minecraft.world.scores.PlayerTeam.formatNameForTeam(team, net.minecraft.network.chat.Component.literal(e.owner())).getString();
                        x.addProperty("text", name);
                        x.addProperty("score", e.value());
                        lines.add(x);
                    });
            s.add("lines", lines);
            o.add("sidebar", s);
        }
        JsonArray bars = new JsonArray();
        for (var be : mc.gui.getBossOverlay().events.values()) {
            JsonObject b = new JsonObject();
            b.addProperty("name", be.getName().getString());
            b.addProperty("progress", Json.round(be.getProgress()));
            b.addProperty("color", be.getColor().getName());
            bars.add(b);
        }
        o.add("boss_bars", bars);
        if (mc.gui.titleTime > 0) {
            if (mc.gui.title != null) o.addProperty("title", mc.gui.title.getString());
            if (mc.gui.subtitle != null) o.addProperty("subtitle", mc.gui.subtitle.getString());
        }
        if (mc.gui.overlayMessageTime > 0 && mc.gui.overlayMessageString != null)
            o.addProperty("action_bar", mc.gui.overlayMessageString.getString());
        return o;
    }
}
