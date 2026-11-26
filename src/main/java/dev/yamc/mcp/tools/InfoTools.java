package dev.yamc.mcp.tools;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import dev.yamc.mcp.util.Mc;
import dev.yamc.mcp.platform.Platform;
import dev.yamc.mcp.YamcMcp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.ClientThread;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Paging;
import dev.yamc.mcp.json.Stacks;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class InfoTools {
    private InfoTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("mods_list").title("Loaded mods")
                .desc("Mods loaded in this client with id, name, version and description. Works on the title screen.")
                .schema(Schema.of().str("query", "Substring of mod id or name.").bool("include_description", "Include descriptions (default false).").paging(200))
                .handler(a -> {
                    String q = a.str("query", "").toLowerCase(Locale.ROOT);
                    boolean desc = a.bool("include_description", false);
                    List<Platform.ModInfo> mods = new ArrayList<>(YamcMcp.platform().mods());
                    mods.removeIf(m -> !q.isEmpty() && !m.id().toLowerCase(Locale.ROOT).contains(q) && !m.name().toLowerCase(Locale.ROOT).contains(q));
                    mods.sort(Comparator.comparing(Platform.ModInfo::id));
                    return ToolResult.json(Paging.page(mods, a, m -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", m.id());
                        o.addProperty("name", m.name());
                        o.addProperty("version", m.version());
                        if (desc) o.addProperty("description", m.description());
                        return o;
                    }, 200));
                }));

        r.add(Tool.builder("item_tooltip").title("Item tooltip")
                .desc("The tooltip of an item exactly as the game renders it, including lines added by mods (often where mods put key info). Give an item id, or an inventory slot (0-35 main, 36-39 armor feet..head, 40 offhand) to read an actual stack.")
                .schema(Schema.of().str("item", "Item id (uses a default stack).").integer("slot", "Player inventory slot instead of item id.")
                        .bool("advanced", "Advanced tooltips (F3+H style: ids, durability). Default false.")
                        .bool("include_stack", "Include the stack JSON (default true)."))
                .handler(a -> {
                    LocalPlayer p = Minecraft.getInstance().player;
                    ItemStack stack;
                    if (a.has("slot")) {
                        int s = a.reqInt("slot");
                        if (p == null) throw new ToolException("Not in a world.");
                        if (s < 0 || s >= p.getInventory().getContainerSize()) throw new ToolException("Slot out of range.");
                        stack = p.getInventory().getItem(s);
                    } else {
                        Item it = Ctx.item(a.reqStr("item"));
                        stack = new ItemStack(it);
                    }
                    var ctx = Minecraft.getInstance().level != null ? Item.TooltipContext.of(Minecraft.getInstance().level)
                            : Item.TooltipContext.of(dev.yamc.mcp.util.Regs.bestAccess());
                    List<Component> lines = stack.getTooltipLines(ctx, p, a.bool("advanced", false) ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL);
                    JsonObject o = new JsonObject();
                    JsonArray arr = new JsonArray();
                    for (Component c : lines) arr.add(c.getString());
                    o.add("lines", arr);
                    if (a.bool("include_stack", true)) o.add("stack", Stacks.json(stack, false, dev.yamc.mcp.util.Regs.bestAccess()));
                    return ToolResult.json(o);
                }));

        r.add(Tool.builder("advancements").title("Advancements")
                .desc("Advancement progress known to the client (the server sends visible advancements). Filter by status and query; shows title, description, type, progress and remaining criteria.")
                .schema(Schema.of().enumStr("status", "Filter (default all).", false, "all", "done", "in_progress", "not_started")
                        .str("query", "Substring of id or title.").bool("include_recipes", "Include recipe-unlock advancements (default false).")
                        .bool("include_criteria", "List remaining criteria (default true).").paging(50))
                .handler(a -> ToolResult.json(advancements(a))));

        r.add(Tool.builder("statistics").title("Statistics")
                .desc("Player statistics (blocks mined, items crafted/used/picked up, mobs killed, distance walked, deaths, play time, ...). Requests fresh values from the server first. Only non-zero stats are returned.")
                .schema(Schema.of().str("category", "Stat type, e.g. minecraft:custom, minecraft:mined, minecraft:crafted, minecraft:killed, minecraft:used, minecraft:killed_by, minecraft:picked_up.")
                        .str("query", "Substring of stat value id, e.g. 'diamond'.")
                        .bool("refresh", "Request updated stats from the server first (default true).").paging(100))
                .anyThread()
                .handler(InfoTools::statistics));

        r.add(Tool.builder("screenshot").title("Screenshot")
                .desc("Capture the current game frame as a PNG image so you can see what the player sees (including open GUIs).")
                .schema(Schema.of().integer("max_width", "Downscale to at most this width (default 1280, max 3840)."))
                .anyThread()
                .handler(a -> {
                    Minecraft mc = Minecraft.getInstance();
                    int maxW = a.integer("max_width", 1280, 64, 3840);
                    // Capture on the client thread; newer versions deliver the image after a GPU readback, so wait here.
                    CompletableFuture<NativeImage> shot = new CompletableFuture<>();
                    String screen = ClientThread.call(() -> {
                        Mc.screenshot(mc, shot::complete);
                        return Mc.screen(mc) == null ? "in-game" : Mc.screen(mc).getClass().getSimpleName();
                    });
                    try (NativeImage img = shot.get(10, TimeUnit.SECONDS)) {
                        NativeImage out = img;
                        boolean scaled = false;
                        if (img.getWidth() > maxW) {
                            int h = Math.max(1, img.getHeight() * maxW / img.getWidth());
                            out = new NativeImage(maxW, h, false);
                            img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), out);
                            scaled = true;
                        }
                        try {
                            byte[] png = Mc.png(out);
                            JsonObject meta = new JsonObject();
                            meta.addProperty("width", out.getWidth());
                            meta.addProperty("height", out.getHeight());
                            meta.addProperty("screen", screen);
                            return new ToolResult().image(Base64.getEncoder().encodeToString(png), "image/png").text(meta.toString());
                        } finally {
                            if (scaled) out.close();
                        }
                    } catch (java.util.concurrent.TimeoutException e) {
                        throw new ToolException("The game did not deliver the screenshot within 10s (is the window minimized?).");
                    }
                }));
    }

    static JsonObject advancements(Args a) {
        var c = Minecraft.getInstance().getConnection();
        if (c == null) throw new ToolException("Not in a world.");
        var adv = c.getAdvancements();
        String status = a.str("status", "all");
        boolean recipes = a.bool("include_recipes", false), crit = a.bool("include_criteria", true);
        IdFilter f = IdFilter.from(a, "-", "query", "-");
        Map<AdvancementHolder, AdvancementProgress> progress = adv.progress;
        List<AdvancementHolder> list = new ArrayList<>();
        for (var node : Mc.advancementNodes(adv)) {
            AdvancementHolder h = node.holder();
            if (!recipes && h.id().getPath().startsWith("recipes/")) continue;
            AdvancementProgress p = progress.get(h);
            String st = p == null || !p.hasProgress() ? "not_started" : p.isDone() ? "done" : "in_progress";
            if (!"all".equals(status) && !status.equals(st)) continue;
            String title = h.value().display().map(d -> Mc.title(d).getString()).orElse(null);
            if (!f.test(h.id(), () -> title)) continue;
            list.add(h);
        }
        list.sort(Comparator.comparing(AdvancementHolder::id));
        return Paging.page(list, a, h -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", h.id().toString());
            h.value().display().ifPresent(d -> {
                o.addProperty("title", Mc.title(d).getString());
                o.addProperty("description", Mc.description(d).getString());
                o.addProperty("frame", Mc.frame(d));
            });
            h.value().parent().ifPresent(pp -> o.addProperty("parent", pp.toString()));
            AdvancementProgress p = progress.get(h);
            if (p != null) {
                o.addProperty("done", p.isDone());
                o.addProperty("percent", Math.round(p.getPercent() * 100));
                if (crit && !p.isDone()) {
                    JsonArray rem = new JsonArray();
                    for (String s : p.getRemainingCriteria()) rem.add(s);
                    o.add("remaining", rem);
                }
            }
            return o;
        }, 50);
    }

    static ToolResult statistics(Args a) throws Exception {
        if (a.bool("refresh", true)) {
            ClientThread.call(() -> {
                var c = Minecraft.getInstance().getConnection();
                if (c == null) throw new ToolException("Not in a world.");
                c.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
                return null;
            });
            Thread.sleep(600);
        }
        return ClientThread.call(() -> {
            LocalPlayer p = Ctx.player();
            String cat = a.has("category") ? Args.parseId(a.str("category", "")).toString() : null;
            String q = a.str("query", "").toLowerCase(Locale.ROOT);
            record Row(String type, String value, int count, String formatted) {}
            List<Row> rows = new ArrayList<>();
            synchronized (p.getStats().stats) {
                for (var e : p.getStats().stats.object2IntEntrySet()) {
                    Stat<?> st = e.getKey();
                    int v = e.getIntValue();
                    if (v == 0) continue;
                    String t = String.valueOf(BuiltInRegistries.STAT_TYPE.getKey(st.getType())), val = String.valueOf(valueId(st));
                    if (cat != null && !cat.equals(t)) continue;
                    if (!q.isEmpty() && !val.toLowerCase(Locale.ROOT).contains(q)) continue;
                    rows.add(new Row(t, val, v, st.format(v)));
                }
            }
            rows.sort(Comparator.comparing(Row::type).thenComparing(Comparator.comparingInt(Row::count).reversed()));
            return ToolResult.json(Paging.page(rows, a, r -> {
                JsonObject o = new JsonObject();
                o.addProperty("type", r.type);
                o.addProperty("stat", r.value);
                o.addProperty("value", r.count);
                if (!r.formatted.equals(String.valueOf(r.count))) o.addProperty("formatted", r.formatted);
                return o;
            }, 100));
        });
    }

    private static <T> ResourceLocation valueId(Stat<T> st) {
        return st.getType().getRegistry().getKey(st.getValue());
    }
}
