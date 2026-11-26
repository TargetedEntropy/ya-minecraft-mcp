package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.recipe.Ing;
import dev.yamc.mcp.recipe.RecipeView;
import dev.yamc.mcp.util.Inventories;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** One recursive crafting plan: the working inventory and what the plan has used, crafted and is missing. */
final class CraftPlanner {
    private static final int MAX_NODES = 2000;

    private final RecipeGraph graph;
    private final Map<Item, Integer> inv;
    private final int maxDepth;
    private final Set<String> excludeNamespaces;
    private final Set<Item> raw;

    private final Map<String, Integer> missing = new TreeMap<>();
    private final Map<String, Integer> fromInventory = new TreeMap<>();
    private final List<JsonObject> steps = new ArrayList<>();
    private final Map<String, Integer> leftovers = new TreeMap<>();
    private final Map<String, Integer> skippedReversible = new TreeMap<>();
    private int nodes;

    private CraftPlanner(RecipeGraph graph, Map<Item, Integer> inv, int maxDepth, Set<String> excludeNamespaces, Set<Item> raw) {
        this.graph = graph;
        this.inv = inv;
        this.maxDepth = maxDepth;
        this.excludeNamespaces = excludeNamespaces;
        this.raw = raw;
    }

    /** True if every ingredient of the recipe (for the given number of crafts) is already in the inventory. */
    private boolean coveredByInventory(RecipeView r, int crafts) {
        Map<Item, Integer> need = new HashMap<>();
        for (Ing ing : r.inputs()) {
            Item best = null;
            for (Item o : ing.options()) if (inv.getOrDefault(o, 0) > 0) { best = o; break; }
            if (best == null) return false;
            need.merge(best, crafts, Integer::sum);
        }
        for (var e : need.entrySet()) if (inv.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        return true;
    }

    static JsonObject plan(Args a, RecipeSource src) {
        Item target = Ctx.item(a.reqStr("item"));
        int count = a.integer("count", 1, 1, 100000);
        List<String> types = a.strList("recipe_types");
        Set<String> typeSet = new HashSet<>();
        if (types.isEmpty()) typeSet.add("minecraft:crafting");
        else for (String t : types) typeSet.add(Args.parseId(t).toString());
        Map<Item, Integer> inv = a.bool("use_inventory", true) && Ctx.mc().player != null
                ? Inventories.counts(Ctx.player().getInventory(), false) : new HashMap<>();
        Set<Item> raw = new HashSet<>();
        for (String spec : a.strList("raw_materials")) raw.addAll(RecipeTools.items(spec));
        RecipeGraph graph = RecipeGraph.of(src, typeSet);
        CraftPlanner p = new CraftPlanner(graph, inv, a.integer("max_depth", 8, 1, 20), new HashSet<>(a.strList("exclude_namespaces")), raw);
        if (!graph.craftable(target)) {
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
        if (++nodes > MAX_NODES) {
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
        List<RecipeView> recipes = candidates(item, needed, root);
        if (recipes.isEmpty() || depth >= maxDepth || path.contains(item) || (!root && raw.contains(item))) {
            node.addProperty("missing", needed);
            missing.merge(id, needed, Integer::sum);
            return node;
        }
        RecipeView chosen = choose(recipes, path, item);
        int per = chosen.yield();
        int crafts = (needed + per - 1) / per;
        node.addProperty("recipe", chosen.id().toString());
        node.addProperty("crafts", crafts);
        // group identical ingredients
        Map<String, Ing> ingBySig = new LinkedHashMap<>();
        Map<String, Integer> qty = new LinkedHashMap<>();
        for (Ing ing : chosen.inputs()) {
            String sig = ing.signature();
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
        step.addProperty("type", chosen.type());
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
    private List<RecipeView> candidates(Item item, int needed, boolean root) {
        List<RecipeView> out = new ArrayList<>();
        int skipped = 0;
        for (RecipeView h : graph.recipesFor(item)) {
            if (excludeNamespaces.contains(h.id().getNamespace())) continue;
            if (!root && graph.reversible(h, item)) {
                int per = h.yield();
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

    /**
     * Prefer a recipe whose ingredients are in the inventory, and avoid ones with an ingredient that only cycles
     * back into the current path. The reversible filter in {@link #candidates} only catches two-step cycles
     * (X from Y from X); this also covers longer ones.
     */
    private RecipeView choose(List<RecipeView> recipes, Set<Item> path, Item item) {
        RecipeView best = recipes.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (RecipeView h : recipes) {
            int score = 0;
            for (Ing ing : h.inputs()) {
                boolean inInv = false, cyclic = true;
                for (Item s : ing.options()) {
                    if (inv.getOrDefault(s, 0) > 0) inInv = true;
                    if (!path.contains(s) && s != item) cyclic = false;
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

    private Item pickItem(Ing ing, int amount, Set<Item> path) {
        List<Item> opts = ing.options();
        if (opts.isEmpty()) return null;
        Item craftable = null, any = null;
        for (Item it : opts) {
            if (path.contains(it)) continue;
            if (inv.getOrDefault(it, 0) >= amount) return it;
            if (any == null) any = it;
            if (craftable == null && graph.craftable(it)) craftable = it;
        }
        for (Item it : opts) if (inv.getOrDefault(it, 0) > 0 && !path.contains(it)) return it;
        if (craftable != null) return craftable;
        return any != null ? any : opts.get(0);
    }
}
