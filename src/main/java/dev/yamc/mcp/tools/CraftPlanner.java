package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.ToolException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Recursive crafting planner. */
final class CraftPlanner {
    private final Map<Item, List<RecipeHolder<?>>> byOutput;
    private final Map<Item, Integer> inv;
    private final RecipeTools.Src src;
    private final int maxDepth;
    private final Map<String, Integer> missing = new TreeMap<>();
    private final Map<String, Integer> fromInventory = new TreeMap<>();
    private final List<JsonObject> steps = new ArrayList<>();
    private final Map<String, Integer> leftovers = new TreeMap<>();
    private int nodes;

    /** Recipe index for one recipe set + type selection, cached because big packs have 100k+ recipes. */
    private static final class Index {
        final Map<Item, List<RecipeHolder<?>>> byOutput = new HashMap<>();
        /** item -> every item that appears as an ingredient option in a recipe producing it */
        final Map<Item, Set<Item>> inputsOf = new HashMap<>();
    }

    private static Object cacheKey;
    private static Index cacheIndex;

    private static synchronized Index index(RecipeTools.Src src, Set<String> types) {
        var recipes = src.manager().getRecipes();
        List<Object> key = List.of(System.identityHashCode(recipes), recipes.size(), new java.util.TreeSet<>(types), src.source());
        if (key.equals(cacheKey)) return cacheIndex;
        Index idx = new Index();
        for (RecipeHolder<?> h : recipes) {
            Recipe<?> r = h.value();
            if (!types.contains(RecipeTools.typeId(r)) || r.isSpecial()) continue;
            ItemStack res = RecipeTools.safeResult(r, src.regs());
            if (res.isEmpty() || r.getIngredients().stream().allMatch(Ingredient::isEmpty)) continue;
            idx.byOutput.computeIfAbsent(res.getItem(), k -> new ArrayList<>()).add(h);
            Set<Item> ins = idx.inputsOf.computeIfAbsent(res.getItem(), k -> new HashSet<>());
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) continue;
                ItemStack[] opts = ing.getItems();
                if (opts.length > 16) continue; // broad tag ingredients don't indicate a storage/compression pair
                for (ItemStack o : opts) ins.add(o.getItem());
            }
        }
        // Deterministic preference: vanilla namespace first, then by id
        for (List<RecipeHolder<?>> l : idx.byOutput.values())
            l.sort((x, y) -> {
                int a = x.id().getNamespace().equals("minecraft") ? 0 : 1, b = y.id().getNamespace().equals("minecraft") ? 0 : 1;
                return a != b ? Integer.compare(a, b) : x.id().compareTo(y.id());
            });
        cacheKey = key;
        cacheIndex = idx;
        return idx;
    }

    private final Set<String> excludeNamespaces;
    private final java.util.function.Predicate<Item> raw;
    private final Map<String, Integer> skippedReversible = new TreeMap<>();

    private CraftPlanner(RecipeTools.Src src, Set<String> types, Map<Item, Integer> inv, int maxDepth,
                         Set<String> excludeNamespaces, java.util.function.Predicate<Item> raw) {
        this.src = src;
        this.inv = inv;
        this.maxDepth = maxDepth;
        this.excludeNamespaces = excludeNamespaces;
        this.raw = raw;
        this.idx = index(src, types);
        this.byOutput = idx.byOutput;
    }

    private final Index idx;

    /**
     * A recipe making X is "reversible" when one of its ingredients Y can itself be crafted from X
     * (storage blocks, nuggets, compression/decompression ladders). Using such recipes for intermediate
     * steps makes the planner climb compression tiers instead of treating X as a raw material.
     */
    private boolean reversible(RecipeHolder<?> h, Item out) {
        for (Ingredient ing : h.value().getIngredients()) {
            if (ing.isEmpty()) continue;
            ItemStack[] opts = ing.getItems();
            if (opts.length > 16) continue;
            for (ItemStack o : opts) {
                Set<Item> ins = idx.inputsOf.get(o.getItem());
                if (ins != null && ins.contains(out)) return true;
            }
        }
        return false;
    }

    /** True if every ingredient of the recipe (for the given number of crafts) is already in the inventory. */
    private boolean coveredByInventory(RecipeHolder<?> h, int crafts) {
        Map<Item, Integer> need = new HashMap<>();
        for (Ingredient ing : h.value().getIngredients()) {
            if (ing.isEmpty()) continue;
            Item best = null;
            for (ItemStack o : ing.getItems()) if (inv.getOrDefault(o.getItem(), 0) > 0) { best = o.getItem(); break; }
            if (best == null) return false;
            need.merge(best, crafts, Integer::sum);
        }
        for (var e : need.entrySet()) if (inv.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        return true;
    }

    static JsonObject plan(Args a, RecipeTools.Src src) {
        Item target = Ctx.item(a.reqStr("item"));
        int count = a.integer("count", 1, 1, 100000);
        List<String> types = a.strList("recipe_types");
        Set<String> typeSet = new HashSet<>();
        if (types.isEmpty()) typeSet.add("minecraft:crafting");
        else for (String t : types) typeSet.add(Args.parseId(t).toString());
        Map<Item, Integer> inv = a.bool("use_inventory", true) && Ctx.mc().player != null
                ? RecipeTools.inventoryCounts(Ctx.player()) : new HashMap<>();
        Set<String> exNs = new HashSet<>(a.strList("exclude_namespaces"));
        List<java.util.function.Predicate<Item>> rawPreds = new ArrayList<>();
        for (String spec : a.strList("raw_materials")) {
            if (spec.startsWith("#")) {
                var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, Args.parseId(spec));
                rawPreds.add(it -> it.builtInRegistryHolder().is(tag));
            } else {
                Item it = Ctx.item(spec);
                rawPreds.add(x -> x == it);
            }
        }
        CraftPlanner p = new CraftPlanner(src, typeSet, inv, a.integer("max_depth", 8, 1, 20), exNs,
                it -> rawPreds.stream().anyMatch(pr -> pr.test(it)));
        if (!p.byOutput.containsKey(target)) {
            throw new ToolException("No " + typeSet + " recipe produces " + BuiltInRegistries.ITEM.getKey(target)
                    + ". Try recipes_search output=<item> to see other recipe types, then pass recipe_types.");
        }
        JsonObject tree = p.expand(target, count, 0, new HashSet<>(), true);
        JsonObject o = new JsonObject();
        o.addProperty("item", BuiltInRegistries.ITEM.getKey(target).toString());
        o.addProperty("count", count);
        o.addProperty("source", src.source());
        o.addProperty("complete", p.missing.isEmpty());
        o.add("missing", map(p.missing));
        o.add("from_inventory", map(p.fromInventory));
        JsonArray st = new JsonArray();
        p.steps.forEach(st::add);
        o.add("steps", st);
        if (!p.leftovers.isEmpty()) o.add("leftovers", map(p.leftovers));
        if (!p.skippedReversible.isEmpty()) {
            o.add("treated_as_raw", map(p.skippedReversible));
            o.addProperty("note", "Items in treated_as_raw only had reversible recipes (storage blocks, nuggets, compression tiers) and were treated as raw materials. Ask for them directly to plan those.");
        }
        o.add("tree", tree);
        return o;
    }

    private static JsonObject map(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    private JsonObject expand(Item item, int needed, int depth, Set<Item> path, boolean root) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        JsonObject node = new JsonObject();
        node.addProperty("item", id);
        node.addProperty("needed", needed);
        if (++nodes > 2000) {
            node.addProperty("truncated", true);
            missing.merge(id, needed, Integer::sum);
            return node;
        }
        if (!root) {
            int have = inv.getOrDefault(item, 0);
            int take = Math.min(have, needed);
            if (take > 0) {
                inv.put(item, have - take);
                needed -= take;
                fromInventory.merge(id, take, Integer::sum);
                node.addProperty("from_inventory", take);
            }
        }
        if (needed == 0) return node;
        List<RecipeHolder<?>> recipes = candidates(item, needed, root);
        if (recipes.isEmpty() || depth >= maxDepth || path.contains(item) || (!root && raw.test(item))) {
            node.addProperty("missing", needed);
            missing.merge(id, needed, Integer::sum);
            return node;
        }
        RecipeHolder<?> chosen = choose(recipes, path, item);
        Recipe<?> r = chosen.value();
        int per = Math.max(1, RecipeTools.safeResult(r, src.regs()).getCount());
        int crafts = (needed + per - 1) / per;
        node.addProperty("recipe", chosen.id().toString());
        node.addProperty("crafts", crafts);
        // group identical ingredients
        Map<String, Ingredient> ingBySig = new LinkedHashMap<>();
        Map<String, Integer> qty = new LinkedHashMap<>();
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            String sig = RecipeTools.signature(ing);
            ingBySig.putIfAbsent(sig, ing);
            qty.merge(sig, 1, Integer::sum);
        }
        Set<Item> sub = new HashSet<>(path);
        sub.add(item);
        JsonArray children = new JsonArray();
        for (var e : ingBySig.entrySet()) {
            Item pick = pickItem(e.getValue(), qty.get(e.getKey()) * crafts, sub);
            if (pick == null) continue;
            children.add(expand(pick, qty.get(e.getKey()) * crafts, depth + 1, sub, false));
        }
        node.add("ingredients", children);
        JsonObject step = new JsonObject();
        step.addProperty("recipe", chosen.id().toString());
        step.addProperty("type", RecipeTools.typeId(r));
        step.addProperty("output", id);
        step.addProperty("crafts", crafts);
        step.addProperty("produces", crafts * per);
        steps.add(step);
        int extra = crafts * per - needed;
        if (extra > 0) {
            inv.merge(item, extra, Integer::sum);
            leftovers.merge(id, extra, Integer::sum);
        }
        return node;
    }

    /** Usable recipes for an item: excluded namespaces removed; reversible ones only at the root or when the inventory covers them. */
    private List<RecipeHolder<?>> candidates(Item item, int needed, boolean root) {
        List<RecipeHolder<?>> all = byOutput.get(item);
        List<RecipeHolder<?>> out = new ArrayList<>();
        if (all == null) return out;
        int skipped = 0;
        for (RecipeHolder<?> h : all) {
            if (excludeNamespaces.contains(h.id().getNamespace())) continue;
            if (!root && reversible(h, item)) {
                int per = Math.max(1, RecipeTools.safeResult(h.value(), src.regs()).getCount());
                if (!coveredByInventory(h, (needed + per - 1) / per)) {
                    skipped++;
                    continue;
                }
            }
            out.add(h);
        }
        if (out.isEmpty() && skipped > 0) skippedReversible.merge(BuiltInRegistries.ITEM.getKey(item).toString(), needed, Integer::sum);
        return out;
    }

    /** Prefer a recipe whose ingredients are all in the inventory, then one without cyclic ingredients. */
    private RecipeHolder<?> choose(List<RecipeHolder<?>> recipes, Set<Item> path, Item item) {
        RecipeHolder<?> best = recipes.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (RecipeHolder<?> h : recipes) {
            int score = 0;
            for (Ingredient ing : h.value().getIngredients()) {
                if (ing.isEmpty()) continue;
                boolean inInv = false, cyclic = true;
                for (ItemStack s : ing.getItems()) {
                    if (inv.getOrDefault(s.getItem(), 0) > 0) inInv = true;
                    if (!path.contains(s.getItem()) && s.getItem() != item) cyclic = false;
                }
                if (inInv) score += 3;
                if (cyclic) score -= 100;
            }
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return best;
    }

    private Item pickItem(Ingredient ing, int amount, Set<Item> path) {
        ItemStack[] opts = ing.getItems();
        if (opts.length == 0) return null;
        Item craftable = null, any = null;
        for (ItemStack s : opts) {
            Item it = s.getItem();
            if (path.contains(it)) continue;
            if (inv.getOrDefault(it, 0) >= amount) return it;
            if (any == null) any = it;
            if (craftable == null && byOutput.containsKey(it)) craftable = it;
        }
        for (ItemStack s : opts) if (inv.getOrDefault(s.getItem(), 0) > 0 && !path.contains(s.getItem())) return s.getItem();
        if (craftable != null) return craftable;
        return any != null ? any : opts[0].getItem();
    }
}
