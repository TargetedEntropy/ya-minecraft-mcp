package dev.yamc.mcp.tools;

import dev.yamc.mcp.recipe.Ing;
import dev.yamc.mcp.recipe.RecipeView;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
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

    /** Identity of a recipe set + type selection. The recipe list is compared by identity: a reload replaces it. */
    private record Key(List<RecipeView> recipes, Set<String> types) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.recipes == recipes && k.types.equals(types);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(recipes) * 31 + types.hashCode();
        }
    }

    private static Key cachedKey;
    private static RecipeGraph cached;

    /** Recipes per output item, vanilla first, then by id. */
    private final Map<Item, List<RecipeView>> byOutput = new HashMap<>();
    /** item -> every item that is a (narrow) ingredient option of a recipe producing it */
    private final Map<Item, Set<Item>> inputsOf = new HashMap<>();

    static synchronized RecipeGraph of(RecipeSource src, Set<String> types) {
        Key key = new Key(src.recipes(), Set.copyOf(types));
        if (!key.equals(cachedKey)) {
            cached = new RecipeGraph(src.recipes(), types);
            cachedKey = key;
        }
        return cached;
    }

    private RecipeGraph(List<RecipeView> recipes, Set<String> types) {
        for (RecipeView r : recipes) {
            if (!types.contains(r.type()) || r.special() || r.result().isEmpty() || r.inputs().isEmpty()) continue;
            Item out = r.result().getItem();
            byOutput.computeIfAbsent(out, k -> new ArrayList<>()).add(r);
            Set<Item> ins = inputsOf.computeIfAbsent(out, k -> new HashSet<>());
            for (Ing ing : r.inputs()) ins.addAll(narrowOptions(ing));
        }
        Comparator<RecipeView> vanillaFirst = Comparator.comparing(r -> !r.id().getNamespace().equals("minecraft"));
        for (List<RecipeView> l : byOutput.values()) l.sort(vanillaFirst.thenComparing(RecipeView::id));
    }

    private static List<Item> narrowOptions(Ing ing) {
        return ing.options().size() > MAX_PAIR_OPTIONS ? List.of() : ing.options();
    }

    boolean craftable(Item item) {
        return byOutput.containsKey(item);
    }

    List<RecipeView> recipesFor(Item item) {
        return byOutput.getOrDefault(item, List.of());
    }

    /**
     * A recipe making X is "reversible" when one of its ingredients Y can itself be crafted from X
     * (storage blocks, nuggets, compression/decompression ladders). Using such recipes for intermediate
     * steps makes the planner climb compression tiers instead of treating X as a raw material.
     */
    boolean reversible(RecipeView r, Item out) {
        for (Ing ing : r.inputs()) {
            for (Item y : narrowOptions(ing)) {
                Set<Item> ins = inputsOf.get(y);
                if (ins != null && ins.contains(out)) return true;
            }
        }
        return false;
    }
}
