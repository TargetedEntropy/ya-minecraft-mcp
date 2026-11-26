package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Paging;
import dev.yamc.mcp.util.Regs;
import dev.yamc.mcp.util.Stacks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

final class RecipeTools {
    private RecipeTools() {}

    static final String SOURCE_DESC = "auto (default: integrated server in singleplayer for full tag info, else the recipes the server synced to this client), client, or server.";

    record Src(RecipeManager manager, HolderLookup.Provider regs, String source) {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("recipe_types").title("Recipe types")
                .desc("All recipe types with recipe counts (vanilla and modded machines, e.g. create:mixing, mekanism:crushing).")
                .schema(Schema.of().idFilters().enumStr("source", SOURCE_DESC, false, "auto", "client", "server"))
                .handler(a -> {
                    Src s = src(a);
                    IdFilter f = IdFilter.from(a);
                    Map<ResourceLocation, Integer> counts = new TreeMap<>();
                    for (RecipeHolder<?> h : s.manager.getRecipes()) {
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
                    o.addProperty("source", s.source);
                    o.addProperty("total_recipes", s.manager.getRecipes().size());
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
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server")
                        .paging())
                .handler(a -> ToolResult.json(search(a))));

        r.add(Tool.builder("recipe_get").title("Get recipe")
                .desc("Full details of one recipe by id: type, serializer, result, ingredients (with tags when known), shaped pattern, cooking time/xp, plus the raw codec-encoded JSON which covers modded recipe fields.")
                .schema(Schema.of().reqStr("id", "Recipe id, e.g. minecraft:iron_pickaxe.")
                        .bool("include_raw", "Include the raw codec JSON (default true).")
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server"))
                .handler(a -> {
                    Src s = src(a);
                    ResourceLocation id = a.reqId("id");
                    RecipeHolder<?> h = s.manager.byKey(id).orElseThrow(() -> new ToolException("No recipe '" + id + "'. Use recipes_search."));
                    JsonObject o = recipeJson(h, s.regs, a.bool("include_raw", true));
                    o.addProperty("source", s.source);
                    return ToolResult.json(o);
                }));

        r.add(Tool.builder("craft_plan").title("Crafting plan")
                .desc("Recursive crafting plan for an item: chooses recipes, expands intermediate ingredients, subtracts what the player already has, and returns ordered craft steps, raw materials needed and what is missing.")
                .schema(Schema.of().reqStr("item", "Item id to craft.")
                        .integer("count", "How many (default 1).")
                        .strArray("recipe_types", "Recipe types allowed for intermediate steps (default [minecraft:crafting]). Add minecraft:smelting etc. to include furnaces, or modded types.")
                        .bool("use_inventory", "Subtract items already in the player inventory (default true).")
                        .integer("max_depth", "Max recursion depth (default 8).")
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server"))
                .handler(a -> ToolResult.json(CraftPlanner.plan(a, src(a)))));

        r.add(Tool.builder("craftable_now").title("Craftable now")
                .desc("Recipes the player can craft right now from their inventory (ingredient counts only; reports whether a crafting table is needed).")
                .schema(Schema.of().str("type", "Recipe type (default minecraft:crafting).")
                        .idFilters()
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server")
                        .paging())
                .handler(a -> ToolResult.json(craftableNow(a))));
    }

    static Src src(Args a) {
        String pref = a.str("source", "auto");
        MinecraftServer server = Regs.server();
        if ("server".equals(pref) || ("auto".equals(pref) && server != null)) {
            if (server == null) throw new ToolException("source=server requires singleplayer / LAN host.");
            return new Src(server.getRecipeManager(), server.reloadableRegistries().get(), "integrated_server");
        }
        var c = Regs.connection();
        if (c == null) throw new ToolException("Not in a world: recipes are only available after joining a world/server.");
        return new Src(c.getRecipeManager(), c.registryAccess(), "client");
    }

    // ------------------------------------------------------------------ JSON

    static JsonObject ingredientJson(Ingredient ing) {
        JsonObject o = new JsonObject();
        if (ing.isCustom()) {
            ResourceLocation t = NeoForgeRegistries.INGREDIENT_TYPES.getKey(ing.getCustomIngredient().getType());
            o.addProperty("custom_type", String.valueOf(t));
        } else {
            JsonArray tags = new JsonArray();
            for (Ingredient.Value v : ing.getValues()) if (v instanceof Ingredient.TagValue tv) tags.add("#" + tv.tag().location());
            if (!tags.isEmpty()) o.add("tags", tags);
        }
        ItemStack[] items = ing.getItems();
        JsonArray arr = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (ItemStack s : items) {
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (seen.add(id) && arr.size() < 16) arr.add(s.getCount() > 1 ? s.getCount() + "x " + id : id);
        }
        o.add("items", arr);
        if (seen.size() > 16) o.addProperty("item_count", seen.size());
        return o;
    }

    static String typeId(Recipe<?> r) {
        return String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()));
    }

    static JsonObject recipeJson(RecipeHolder<?> h, HolderLookup.Provider regs, boolean raw) {
        Recipe<?> r = h.value();
        JsonObject o = new JsonObject();
        o.addProperty("id", h.id().toString());
        o.addProperty("type", typeId(r));
        o.addProperty("serializer", String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(r.getSerializer())));
        if (!r.getGroup().isEmpty()) o.addProperty("group", r.getGroup());
        ItemStack result = safeResult(r, regs);
        if (!result.isEmpty()) o.add("result", Stacks.json(result, false, regs));
        if (r.isSpecial()) o.addProperty("special", true);
        List<Ingredient> ings = r.getIngredients();
        if (r instanceof ShapedRecipe sr) {
            o.addProperty("width", sr.getWidth());
            o.addProperty("height", sr.getHeight());
            Map<String, Character> keyOf = new LinkedHashMap<>();
            JsonObject key = new JsonObject();
            JsonArray pattern = new JsonArray();
            for (int y = 0; y < sr.getHeight(); y++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < sr.getWidth(); x++) {
                    Ingredient ing = ings.get(y * sr.getWidth() + x);
                    if (ing.isEmpty()) {
                        row.append(' ');
                        continue;
                    }
                    String sig = signature(ing);
                    Character c = keyOf.get(sig);
                    if (c == null) {
                        c = (char) ('A' + keyOf.size());
                        keyOf.put(sig, c);
                        key.add(String.valueOf(c), ingredientJson(ing));
                    }
                    row.append(c);
                }
                pattern.add(row.toString());
            }
            o.add("pattern", pattern);
            o.add("key", key);
        } else {
            JsonArray arr = new JsonArray();
            for (Ingredient ing : ings) if (!ing.isEmpty()) arr.add(ingredientJson(ing));
            o.add("ingredients", arr);
        }
        if (r instanceof AbstractCookingRecipe cr) {
            o.addProperty("cooking_time_ticks", cr.getCookingTime());
            o.addProperty("experience", cr.getExperience());
        }
        if (raw) o.add("raw", Json.encode(Recipe.CODEC, r, regs));
        return o;
    }

    static ItemStack safeResult(Recipe<?> r, HolderLookup.Provider regs) {
        try {
            ItemStack s = r.getResultItem(regs);
            return s == null ? ItemStack.EMPTY : s;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    static String signature(Ingredient ing) {
        StringBuilder b = new StringBuilder();
        if (ing.isCustom()) b.append("custom:").append(ing.getCustomIngredient().hashCode()).append(';');
        Arrays.stream(ing.getItems()).map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).sorted().forEach(x -> b.append(x).append(','));
        return b.toString();
    }

    // ------------------------------------------------------------------ search

    static Predicate<ItemStack> itemOrTag(String spec) {
        if (spec.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, Args.parseId(spec));
            return s -> s.is(tag);
        }
        Item it = Ctx.item(spec);
        return s -> s.is(it);
    }

    static boolean consumes(Recipe<?> r, String spec) {
        if (spec.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, Args.parseId(spec));
            List<Item> members = new ArrayList<>();
            BuiltInRegistries.ITEM.getTag(tag).ifPresent(set -> set.forEach(h -> members.add(h.value())));
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) continue;
                if (!ing.isCustom()) for (Ingredient.Value v : ing.getValues()) if (v instanceof Ingredient.TagValue tv && tv.tag().equals(tag)) return true;
                for (Item m : members) if (ing.test(new ItemStack(m))) return true;
            }
            return false;
        }
        ItemStack probe = new ItemStack(Ctx.item(spec));
        for (Ingredient ing : r.getIngredients()) if (!ing.isEmpty() && ing.test(probe)) return true;
        return false;
    }

    static JsonObject search(Args a) {
        Src s = src(a);
        String type = a.has("type") ? Args.parseId(a.str("type", "")).toString() : null;
        Predicate<ItemStack> out = a.has("output") ? itemOrTag(a.str("output", "")) : null;
        String input = a.str("input", null);
        IdFilter f = IdFilter.from(a);
        boolean raw = a.bool("include_raw", false);
        List<RecipeHolder<?>> list = new ArrayList<>();
        for (RecipeHolder<?> h : s.manager.getRecipes()) {
            Recipe<?> r = h.value();
            if (type != null && !type.equals(typeId(r))) continue;
            ItemStack res = (out != null || f.usesName()) ? safeResult(r, s.regs) : null;
            if (out != null && (res.isEmpty() || !out.test(res))) continue;
            if (input != null && !consumes(r, input)) continue;
            if (!f.test(h.id(), () -> res == null || res.isEmpty() ? null : res.getHoverName().getString())) continue;
            list.add(h);
        }
        list.sort(Comparator.comparing(RecipeHolder::id));
        JsonObject o = Paging.page(list, a, h -> recipeJson(h, s.regs, raw), 25);
        o.addProperty("source", s.source);
        return o;
    }

    // ------------------------------------------------------------------ craftable now

    static Map<Item, Integer> inventoryCounts(LocalPlayer p) {
        Map<Item, Integer> m = new HashMap<>();
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        for (ItemStack s : p.getInventory().offhand) if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        return m;
    }

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
        Src s = src(a);
        LocalPlayer p = Ctx.player();
        String type = Args.parseId(a.str("type", "minecraft:crafting")).toString();
        IdFilter f = IdFilter.from(a);
        Map<Item, Integer> inv = inventoryCounts(p);
        record Craftable(RecipeHolder<?> h, int times, boolean table) {}
        List<Craftable> out = new ArrayList<>();
        for (RecipeHolder<?> h : s.manager.getRecipes()) {
            Recipe<?> r = h.value();
            if (!type.equals(typeId(r)) || r.isSpecial()) continue;
            ItemStack res = safeResult(r, s.regs);
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
            j.add("result", Stacks.json(safeResult(c.h.value(), s.regs), false, s.regs));
            j.addProperty("max_crafts", c.times);
            j.addProperty("needs_crafting_table", c.table);
            return j;
        }, 50);
        o.addProperty("source", s.source);
        return o;
    }
}
