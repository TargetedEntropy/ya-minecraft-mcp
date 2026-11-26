package dev.yamc.mcp.tools;

import dev.yamc.mcp.json.RecipeJson;
import dev.yamc.mcp.util.Regs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable index of the recipes of some types, by output item. Built once per recipe set and type selection
 * and cached, because big packs have 100k+ recipes.
 */
final class RecipeGraph {
    /** Ingredients with more options than this are broad tags, not one half of a storage/compression pair. */
    private static final int MAX_PAIR_OPTIONS = 16;

    /** Identity of a recipe set + type selection. The recipe collection is compared by identity: a reload replaces it. */
    private record Key(Collection<RecipeHolder<?>> recipes, int size, Set<String> types, String source) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.recipes == recipes && k.size == size && k.types.equals(types) && k.source.equals(source);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(recipes) * 31 + types.hashCode();
        }
    }

    private static Key cachedKey;
    private static RecipeGraph cached;

    private final Regs.Recipes src;
    /** Recipes per output item, vanilla first, then by id. */
    private final Map<Item, List<RecipeHolder<?>>> byOutput = new HashMap<>();
    /** item -> every item that is a (narrow) ingredient option of a recipe producing it */
    private final Map<Item, Set<Item>> inputsOf = new HashMap<>();

    static synchronized RecipeGraph of(Regs.Recipes src, Set<String> types) {
        Collection<RecipeHolder<?>> recipes = src.manager().getRecipes();
        Key key = new Key(recipes, recipes.size(), Set.copyOf(types), src.source());
        if (!key.equals(cachedKey)) {
            cached = new RecipeGraph(src, recipes, types);
            cachedKey = key;
        }
        return cached;
    }

    private RecipeGraph(Regs.Recipes src, Collection<RecipeHolder<?>> recipes, Set<String> types) {
        this.src = src;
        for (RecipeHolder<?> h : recipes) {
            Recipe<?> r = h.value();
            if (!types.contains(RecipeJson.type(r)) || r.isSpecial()) continue;
            ItemStack res = RecipeJson.result(r, src.regs());
            if (res.isEmpty() || r.getIngredients().stream().allMatch(Ingredient::isEmpty)) continue;
            byOutput.computeIfAbsent(res.getItem(), k -> new ArrayList<>()).add(h);
            Set<Item> ins = inputsOf.computeIfAbsent(res.getItem(), k -> new HashSet<>());
            for (Ingredient ing : r.getIngredients()) for (Item o : narrowOptions(ing)) ins.add(o);
        }
        Comparator<RecipeHolder<?>> vanillaFirst = Comparator.comparing(h -> !h.id().getNamespace().equals("minecraft"));
        for (List<RecipeHolder<?>> l : byOutput.values()) l.sort(vanillaFirst.thenComparing(RecipeHolder::id));
    }

    private static List<Item> narrowOptions(Ingredient ing) {
        ItemStack[] opts = ing.getItems();
        if (opts.length > MAX_PAIR_OPTIONS) return List.of();
        List<Item> out = new ArrayList<>(opts.length);
        for (ItemStack o : opts) out.add(o.getItem());
        return out;
    }

    boolean craftable(Item item) {
        return byOutput.containsKey(item);
    }

    List<RecipeHolder<?>> recipesFor(Item item) {
        return byOutput.getOrDefault(item, List.of());
    }

    /** Items produced per craft (at least 1). */
    int yield(RecipeHolder<?> h) {
        return Math.max(1, RecipeJson.result(h.value(), src.regs()).getCount());
    }

    /**
     * A recipe making X is "reversible" when one of its ingredients Y can itself be crafted from X
     * (storage blocks, nuggets, compression/decompression ladders). Using such recipes for intermediate
     * steps makes the planner climb compression tiers instead of treating X as a raw material.
     */
    boolean reversible(RecipeHolder<?> h, Item out) {
        for (Ingredient ing : h.value().getIngredients()) {
            for (Item y : narrowOptions(ing)) {
                Set<Item> ins = inputsOf.get(y);
                if (ins != null && ins.contains(out)) return true;
            }
        }
        return false;
    }
}
