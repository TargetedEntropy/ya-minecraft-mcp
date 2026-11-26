package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.json.RecipeJson;
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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

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
                    Regs.Recipes s = src(a);
                    IdFilter f = IdFilter.from(a);
                    Map<ResourceLocation, Integer> counts = new TreeMap<>();
                    for (RecipeHolder<?> h : s.manager().getRecipes()) {
                        ResourceLocation t = BuiltInRegistries.RECIPE_TYPE.getKey(h.value().getType());
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
                    o.addProperty("total_recipes", s.manager().getRecipes().size());
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
                    Regs.Recipes s = src(a);
                    ResourceLocation id = a.reqId("id");
                    RecipeHolder<?> h = s.manager().byKey(id).orElseThrow(() -> new ToolException("No recipe '" + id + "'. Use recipes_search."));
                    JsonObject o = RecipeJson.recipe(h, s.regs(), a.bool("include_raw", true));
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
                .handler(a -> ToolResult.json(CraftPlanner.plan(a, src(a)))));

        r.add(Tool.builder("craftable_now").title("Craftable now")
                .desc("Recipes the player can craft right now from their inventory (ingredient counts only; reports whether a crafting table is needed).")
                .schema(Schema.of().str("type", "Recipe type (default minecraft:crafting).")
                        .idFilters()
                        .source(SOURCE_DESC)
                        .paging(50))
                .handler(a -> ToolResult.json(craftableNow(a))));
    }

    static Regs.Recipes src(Args a) {
        return Regs.recipes(Regs.Source.from(a));
    }

    // ------------------------------------------------------------------ search

    /** Items named by an item id or #tag (validated). */
    static Set<Item> items(String spec) {
        return Regs.holderSet(BuiltInRegistries.ITEM, spec).stream().map(Holder::value).collect(Collectors.toSet());
    }

    /** Matches recipes with an ingredient option among the given items. Built once per search: big packs have 100k+ recipes. */
    static Predicate<Recipe<?>> consumesAny(Set<Item> items) {
        return r -> {
            for (Ingredient ing : r.getIngredients())
                for (ItemStack s : ing.getItems()) if (items.contains(s.getItem())) return true;
            return false;
        };
    }

    static JsonObject search(Args a) {
        Regs.Recipes s = src(a);
        String type = a.has("type") ? Args.parseId(a.str("type", "")).toString() : null;
        Set<Item> out = a.has("output") ? items(a.reqStr("output")) : null;
        Predicate<Recipe<?>> input = a.has("input") ? consumesAny(items(a.reqStr("input"))) : null;
        IdFilter f = IdFilter.from(a);
        boolean raw = a.bool("include_raw", false);
        List<RecipeHolder<?>> list = new ArrayList<>();
        for (RecipeHolder<?> h : s.manager().getRecipes()) {
            Recipe<?> r = h.value();
            if (type != null && !type.equals(RecipeJson.type(r))) continue;
            ItemStack res = (out != null || f.usesName()) ? RecipeJson.result(r, s.regs()) : null;
            if (out != null && (res.isEmpty() || !out.contains(res.getItem()))) continue;
            if (input != null && !input.test(r)) continue;
            if (!f.test(h.id(), () -> res == null || res.isEmpty() ? null : res.getHoverName().getString())) continue;
            list.add(h);
        }
        list.sort(Comparator.comparing(RecipeHolder::id));
        JsonObject o = Paging.page(list, a, h -> RecipeJson.recipe(h, s.regs(), raw), 25);
        o.addProperty("source", s.source());
        return o;
    }

    // ------------------------------------------------------------------ craftable now

    /** Greedy check whether the ingredients can be taken from inv (mutates inv on success). */
    static boolean take(List<Ingredient> ings, Map<Item, Integer> inv) {
        Map<Item, Integer> copy = new HashMap<>(inv);
        List<Ingredient> sorted = new ArrayList<>();
        for (Ingredient i : ings) if (!i.isEmpty()) sorted.add(i);
        sorted.sort(Comparator.comparingInt(i -> i.getItems().length));
        for (Ingredient ing : sorted) {
            Item pick = null;
            for (var e : copy.entrySet()) {
                if (e.getValue() > 0 && ing.test(new ItemStack(e.getKey()))) {
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
        Regs.Recipes s = src(a);
        LocalPlayer p = Ctx.player();
        String type = Args.parseId(a.str("type", "minecraft:crafting")).toString();
        IdFilter f = IdFilter.from(a);
        Map<Item, Integer> inv = Inventories.counts(p.getInventory(), false);
        record Craftable(RecipeHolder<?> h, int times, boolean table) {}
        List<Craftable> out = new ArrayList<>();
        for (RecipeHolder<?> h : s.manager().getRecipes()) {
            Recipe<?> r = h.value();
            if (!type.equals(RecipeJson.type(r)) || r.isSpecial()) continue;
            ItemStack res = RecipeJson.result(r, s.regs());
            if (res.isEmpty()) continue;
            if (!f.test(h.id(), () -> res.getHoverName().getString())) continue;
            List<Ingredient> ings = r.getIngredients();
            if (ings.stream().allMatch(Ingredient::isEmpty)) continue;
            Map<Item, Integer> work = new HashMap<>(inv);
            int times = 0;
            while (times < 64 && take(ings, work)) times++;
            if (times == 0) continue;
            boolean table = r instanceof ShapedRecipe sr ? (sr.getWidth() > 2 || sr.getHeight() > 2)
                    : ings.stream().filter(i -> !i.isEmpty()).count() > 4;
            out.add(new Craftable(h, times, table));
        }
        out.sort(Comparator.comparing(c -> c.h.id()));
        JsonObject o = Paging.page(out, a, c -> {
            JsonObject j = new JsonObject();
            j.addProperty("id", c.h.id().toString());
            j.add("result", Stacks.json(RecipeJson.result(c.h.value(), s.regs()), false, s.regs()));
            j.addProperty("max_crafts", c.times);
            j.addProperty("needs_crafting_table", c.table);
            return j;
        }, 50);
        o.addProperty("source", s.source());
        return o;
    }
}
