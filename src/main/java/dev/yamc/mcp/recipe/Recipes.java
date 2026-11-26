package dev.yamc.mcp.recipe;

import dev.yamc.mcp.util.Mc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.yamc.mcp.YamcMcp;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads recipes into {@link RecipeView}s. The only place that knows the recipe API of the Minecraft
 * version being built; everything version-specific about recipes is in this file.
 */
public final class Recipes {
    private Recipes() {}

    /** Recipes are synced to clients only before 1.21.2; later, only the integrated server has them. */
    //? if >=1.21.2 {
    /*public static final boolean CLIENT_HAS_RECIPES = false;
    *///?} else
    public static final boolean CLIENT_HAS_RECIPES = true;

    private static Object cacheKey;
    private static List<RecipeView> cache;

    /** Every recipe of a manager as a view. Cached until the manager's recipe set changes (big packs have 100k+). */
    public static synchronized List<RecipeView> all(RecipeManager manager, HolderLookup.Provider regs, @Nullable Level level) {
        Collection<RecipeHolder<?>> recipes = manager.getRecipes();
        List<Object> key = List.of(new Identity(manager), new Identity(recipes), recipes.size(), new Identity(regs));
        if (!key.equals(cacheKey)) {
            List<RecipeView> out = new ArrayList<>(recipes.size());
            for (RecipeHolder<?> h : recipes) out.add(view(h, regs, level));
            cache = List.copyOf(out);
            cacheKey = key;
        }
        return cache;
    }

    private record Identity(Object o) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Identity i && i.o == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(o);
        }
    }

    /** The codec of a recipe value (Recipe.CODEC encodes a holder reference since 26.3). */
    @SuppressWarnings("unchecked")
    public static com.mojang.serialization.Codec<Recipe<?>> codec() {
        //? if >=26.3 {
        /*return Recipe.DIRECT_CODEC;
        *///?} else
        return Recipe.CODEC;
    }

    static RecipeView view(RecipeHolder<?> h, HolderLookup.Provider regs, @Nullable Level level) {
        Recipe<?> r = h.value();
        String type = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()));
        String serializer = String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(r.getSerializer()));
        List<Ing> slots = new ArrayList<>();
        int width = 0, height = 0;
        //? if >=1.21.2 {
        /*var id = Mc.id(h.id());
        String group = r.group();
        if (r instanceof ShapedRecipe sr) {
            width = sr.getWidth();
            height = sr.getHeight();
            for (var o : sr.getIngredients()) slots.add(o.map(Recipes::ing).orElse(Ing.EMPTY));
        } else {
            for (Ingredient i : r.placementInfo().ingredients()) slots.add(ing(i));
        }
        RecipeView.Cooking cooking = r instanceof AbstractCookingRecipe cr ? new RecipeView.Cooking(cr.cookingTime(), cr.experience()) : null;
        *///?} else {
        var id = h.id();
        String group = r.getGroup();
        if (r instanceof ShapedRecipe sr) {
            width = sr.getWidth();
            height = sr.getHeight();
        }
        for (Ingredient i : r.getIngredients()) slots.add(ing(i));
        if (width == 0) slots.removeIf(Ing::isEmpty);
        RecipeView.Cooking cooking = r instanceof AbstractCookingRecipe cr ? new RecipeView.Cooking(cr.getCookingTime(), cr.getExperience()) : null;
        //?}
        return new RecipeView(id, type, serializer, group, result(r, regs, level), List.copyOf(slots), width, height, r.isSpecial(), cooking, r);
    }

    /** The result stack, or EMPTY when the recipe has none or computing it throws (some modded recipes do). */
    private static ItemStack result(Recipe<?> r, HolderLookup.Provider regs, @Nullable Level level) {
        try {
            //? if >=1.21.2 {
            /*if (level == null || r.display().isEmpty()) return ItemStack.EMPTY;
            ItemStack s = r.display().get(0).result().resolveForFirstStack(net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(level));
            *///?} else
            ItemStack s = r.getResultItem(regs);
            return s == null ? ItemStack.EMPTY : s;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    static Ing ing(Ingredient i) {
        //? if >=1.21.2 {
        /*if (i.isEmpty()) return Ing.EMPTY;
        List<Item> options = i.items().map(net.minecraft.core.Holder::value).distinct().toList();
        *///?} else {
        if (i.isEmpty()) return Ing.EMPTY;
        List<Item> options = java.util.Arrays.stream(i.getItems()).map(ItemStack::getItem).distinct().toList();
        //?}
        return new Ing(options, YamcMcp.platform().customIngredient(i), i);
    }

    /** Tags in an ingredient's codec form: "#tag" strings (1.21.2+) or {"tag": ...} objects (before). */
    static List<String> tags(Ingredient i) {
        List<String> out = new ArrayList<>();
        try {
            Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, i).result().ifPresent(j -> collectTags(j, out));
        } catch (Exception ignored) {
            // custom or unencodable ingredient: no tag information
        }
        return out;
    }

    private static void collectTags(JsonElement j, List<String> out) {
        if (j.isJsonPrimitive() && j.getAsString().startsWith("#")) out.add(j.getAsString());
        else if (j.isJsonArray()) for (JsonElement e : (JsonArray) j) collectTags(e, out);
        else if (j.isJsonObject() && ((JsonObject) j).has("tag")) out.add("#" + ((JsonObject) j).get("tag").getAsString());
    }
}
