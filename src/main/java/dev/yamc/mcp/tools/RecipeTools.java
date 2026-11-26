package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.json.RecipeJson;
import dev.yamc.mcp.recipe.Ing;
import dev.yamc.mcp.recipe.RecipeView;
import dev.yamc.mcp.json.Stacks;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Inventories;
import dev.yamc.mcp.util.Paging;
import dev.yamc.mcp.util.Regs;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class RecipeTools {
    private RecipeTools() {}

    static final String SOURCE_DESC = "auto (default: integrated server in singleplayer for full tag info, else the recipes the server synced to this client), client, or server.";

    static void register(ToolRegistry r) {
        r.add(Tool.builder("recipe_types").title("Recipe types")
                .desc("All recipe types with recipe counts (vanilla and modded machines, e.g. create:mixing, mekanism:crushing).")
                .schema(Schema.of().idFilters().source(SOURCE_DESC))
                .handler(a -> {
                    RecipeSource s = RecipeSource.from(a);
                    IdFilter f = IdFilter.from(a);
                    Map<ResourceLocation, Integer> counts = new TreeMap<>();
                    for (RecipeView v : s.recipes()) {
                        ResourceLocation t = ResourceLocation.tryParse(v.type());
                        if (t != null && f.test(t, null)) counts.merge(t, 1, Integer::sum);
                    }
                    JsonArray arr = new JsonArray();
                    counts.forEach((k, v) -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("type", k.toString());
                        o.addProperty("count", v);
                        arr.add(o);
                    });
                    JsonObject o = new JsonObject();
                    o.addProperty("source", s.source());
                    o.addProperty("total_recipes", s.recipes().size());
                    o.add("types", arr);
                    return ToolResult.json(o);
                }));

        r.add(Tool.builder("recipes_search").title("Search recipes")
                .desc("Search all recipes (vanilla and modded) by type, output item/tag, input item/tag ('what uses X'), recipe-id namespace, or query (matches recipe id and output name). Paged; each result has result stack and ingredients.")
                .schema(Schema.of()
                        .str("type", "Recipe type id, e.g. minecraft:crafting, minecraft:smelting, create:mixing.")
                        .str("output", "Item id or #tag the recipe produces.")
                        .str("input", "Item id or #tag the recipe consumes.")
                        .idFilters()
                        .bool("include_raw", "Include the full codec-encoded recipe JSON (default false).")
                        .source(SOURCE_DESC)
                        .paging(25))
                .handler(a -> ToolResult.json(search(a))));

        r.add(Tool.builder("recipe_get").title("Get recipe")
                .desc("Full details of one recipe by id: type, serializer, result, ingredients (with tags when known), shaped pattern, cooking time/xp, plus the raw codec-encoded JSON which covers modded recipe fields.")
                .schema(Schema.of().reqStr("id", "Recipe id, e.g. minecraft:iron_pickaxe.")
                        .bool("include_raw", "Include the raw codec JSON (default true).")
                        .source(SOURCE_DESC))
                .handler(a -> {
                    RecipeSource s = RecipeSource.from(a);
                    ResourceLocation id = a.reqId("id");
                    RecipeView v = s.byId(id).orElseThrow(() -> new ToolException("No recipe '" + id + "'. Use recipes_search."));
                    JsonObject o = RecipeJson.recipe(v, s.regs(), a.bool("include_raw", true));
                    o.addProperty("source", s.source());
                    return ToolResult.json(o);
                }));

        r.add(Tool.builder("craft_plan").title("Crafting plan")
                .desc("Recursive crafting plan for an item: chooses recipes, expands intermediate ingredients, subtracts what the player already has, and returns ordered craft steps, raw materials needed and what is missing. Reversible recipes (storage blocks, nuggets, compression/decompression tiers) are not used for intermediate steps unless the inventory already holds their input, so items like logs and ingots are treated as raw materials.")
                .schema(Schema.of().reqStr("item", "Item id to craft.")
                        .integer("count", "How many (default 1).")
                        .strArray("recipe_types", "Recipe types allowed for intermediate steps (default [minecraft:crafting]). Add minecraft:smelting etc. to include furnaces, or modded types.")
                        .bool("use_inventory", "Subtract items already in the player inventory (default true).")
                        .integer("max_depth", "Max recursion depth (default 8).")
                        .strArray("exclude_namespaces", "Ignore recipes whose id is in these namespaces, e.g. [\"allthecompressed\"].")
                        .strArray("raw_materials", "Items or #tags to always treat as raw (never expanded), e.g. [\"#c:ingots\"].")
                        .source(SOURCE_DESC))
                .handler(a -> ToolResult.json(CraftPlanner.plan(a, RecipeSource.from(a)))));

        r.add(Tool.builder("craftable_now").title("Craftable now")
                .desc("Recipes the player can craft right now from their inventory (ingredient counts only; reports whether a crafting table is needed).")
                .schema(Schema.of().str("type", "Recipe type (default minecraft:crafting).")
                        .idFilters()
                        .source(SOURCE_DESC)
                        .paging(50))
                .handler(a -> ToolResult.json(craftableNow(a))));
    }

    // ------------------------------------------------------------------ search

    /** Items named by an item id or #tag (validated). */
    static Set<Item> items(String spec) {
        return Regs.holderSet(BuiltInRegistries.ITEM, spec).stream().map(Holder::value).collect(Collectors.toSet());
    }

    /** Matches recipes with an ingredient option among the given items. Built once per search: big packs have 100k+ recipes. */
    static Predicate<RecipeView> consumesAny(Set<Item> items) {
        return r -> {
            for (Ing ing : r.inputs())
                for (Item i : ing.options()) if (items.contains(i)) return true;
            return false;
        };
    }

    static JsonObject search(Args a) {
        RecipeSource s = RecipeSource.from(a);
        String type = a.has("type") ? Args.parseId(a.str("type", "")).toString() : null;
        Set<Item> out = a.has("output") ? items(a.reqStr("output")) : null;
        Predicate<RecipeView> input = a.has("input") ? consumesAny(items(a.reqStr("input"))) : null;
        IdFilter f = IdFilter.from(a);
        boolean raw = a.bool("include_raw", false);
        List<RecipeView> list = new ArrayList<>();
        for (RecipeView r : s.recipes()) {
            if (type != null && !type.equals(r.type())) continue;
            ItemStack res = r.result();
            if (out != null && (res.isEmpty() || !out.contains(res.getItem()))) continue;
            if (input != null && !input.test(r)) continue;
            if (!f.test(r.id(), () -> res.isEmpty() ? null : res.getHoverName().getString())) continue;
            list.add(r);
        }
        list.sort(Comparator.comparing(RecipeView::id));
        JsonObject o = Paging.page(list, a, r -> RecipeJson.recipe(r, s.regs(), raw), 25);
        o.addProperty("source", s.source());
        return o;
    }

    // ------------------------------------------------------------------ craftable now

    /** Greedy check whether the ingredients can be taken from inv (mutates inv on success). */
    static boolean take(List<Ing> ings, Map<Item, Integer> inv) {
        Map<Item, Integer> copy = new HashMap<>(inv);
        List<Ing> sorted = new ArrayList<>(ings);
        sorted.sort(Comparator.comparingInt(i -> i.options().size()));
        for (Ing ing : sorted) {
            Item pick = null;
            for (var e : copy.entrySet()) {
                if (e.getValue() > 0 && ing.accepts(e.getKey())) {
                    pick = e.getKey();
                    break;
                }
            }
            if (pick == null) return false;
            copy.merge(pick, -1, Integer::sum);
        }
        inv.clear();
        inv.putAll(copy);
        return true;
    }

    static JsonObject craftableNow(Args a) {
        RecipeSource s = RecipeSource.from(a);
        LocalPlayer p = Ctx.player();
        String type = Args.parseId(a.str("type", "minecraft:crafting")).toString();
        IdFilter f = IdFilter.from(a);
        Map<Item, Integer> inv = Inventories.counts(p.getInventory(), false);
        record Craftable(RecipeView r, int times, boolean table) {}
        List<Craftable> out = new ArrayList<>();
        for (RecipeView r : s.recipes()) {
            if (!type.equals(r.type()) || r.special()) continue;
            ItemStack res = r.result();
            if (res.isEmpty()) continue;
            if (!f.test(r.id(), () -> res.getHoverName().getString())) continue;
            List<Ing> ings = r.inputs();
            if (ings.isEmpty()) continue;
            Map<Item, Integer> work = new HashMap<>(inv);
            int times = 0;
            while (times < 64 && take(ings, work)) times++;
            if (times == 0) continue;
            boolean table = r.shaped() ? (r.width() > 2 || r.height() > 2) : ings.size() > 4;
            out.add(new Craftable(r, times, table));
        }
        out.sort(Comparator.comparing(c -> c.r.id()));
        JsonObject o = Paging.page(out, a, c -> {
            JsonObject j = new JsonObject();
            j.addProperty("id", c.r.id().toString());
            j.add("result", Stacks.json(c.r.result(), false, s.regs()));
            j.addProperty("max_crafts", c.times);
            j.addProperty("needs_crafting_table", c.table);
            return j;
        }, 50);
        o.addProperty("source", s.source());
        return o;
    }
}
