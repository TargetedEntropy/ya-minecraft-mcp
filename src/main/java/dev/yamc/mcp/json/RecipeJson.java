package dev.yamc.mcp.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.util.Json;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSON for recipes and ingredients, plus the small recipe accessors the serializers and planner share. */
public final class RecipeJson {
    private RecipeJson() {}

    public static String type(Recipe<?> r) {
        return String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()));
    }

    /** The result stack, or EMPTY when the recipe has none or computing it throws (some modded recipes do). */
    public static ItemStack result(Recipe<?> r, HolderLookup.Provider regs) {
        try {
            ItemStack s = r.getResultItem(regs);
            return s == null ? ItemStack.EMPTY : s;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** Identity of an ingredient by its options, for grouping equal ingredients. */
    public static String signature(Ingredient ing) {
        StringBuilder b = new StringBuilder();
        if (ing.isCustom()) b.append("custom:").append(ing.getCustomIngredient().hashCode()).append(';');
        Arrays.stream(ing.getItems()).map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).sorted().forEach(x -> b.append(x).append(','));
        return b.toString();
    }

    public static JsonObject ingredient(Ingredient ing) {
        JsonObject o = new JsonObject();
        if (ing.isCustom()) {
            ResourceLocation t = NeoForgeRegistries.INGREDIENT_TYPES.getKey(ing.getCustomIngredient().getType());
            o.addProperty("custom_type", String.valueOf(t));
        } else {
            JsonArray tags = new JsonArray();
            for (Ingredient.Value v : ing.getValues()) if (v instanceof Ingredient.TagValue tv) tags.add("#" + tv.tag().location());
            if (!tags.isEmpty()) o.add("tags", tags);
        }
        JsonArray arr = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (ItemStack s : ing.getItems()) {
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (seen.add(id) && arr.size() < 16) arr.add(s.getCount() > 1 ? s.getCount() + "x " + id : id);
        }
        o.add("items", arr);
        if (seen.size() > 16) o.addProperty("item_count", seen.size());
        return o;
    }

    /** id, type, result, pattern/key or ingredients, cooking data; plus the codec JSON when raw. */
    public static JsonObject recipe(RecipeHolder<?> h, HolderLookup.Provider regs, boolean raw) {
        Recipe<?> r = h.value();
        JsonObject o = new JsonObject();
        o.addProperty("id", h.id().toString());
        o.addProperty("type", type(r));
        o.addProperty("serializer", String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(r.getSerializer())));
        if (!r.getGroup().isEmpty()) o.addProperty("group", r.getGroup());
        ItemStack result = result(r, regs);
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
                    Character c = keyOf.computeIfAbsent(signature(ing), sig -> {
                        char k = (char) ('A' + keyOf.size());
                        key.add(String.valueOf(k), ingredient(ing));
                        return k;
                    });
                    row.append(c);
                }
                pattern.add(row.toString());
            }
            o.add("pattern", pattern);
            o.add("key", key);
        } else {
            JsonArray arr = new JsonArray();
            for (Ingredient ing : ings) if (!ing.isEmpty()) arr.add(ingredient(ing));
            o.add("ingredients", arr);
        }
        if (r instanceof AbstractCookingRecipe cr) {
            o.addProperty("cooking_time_ticks", cr.getCookingTime());
            o.addProperty("experience", cr.getExperience());
        }
        if (raw) o.add("raw", Json.encode(Recipe.CODEC, r, regs));
        return o;
    }
}
