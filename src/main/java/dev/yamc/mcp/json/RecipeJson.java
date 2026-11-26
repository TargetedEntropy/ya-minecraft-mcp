package dev.yamc.mcp.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.recipe.Ing;
import dev.yamc.mcp.recipe.RecipeView;
import dev.yamc.mcp.util.Json;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON for recipes and ingredients. */
public final class RecipeJson {
    private RecipeJson() {}

    private static final int MAX_LISTED_OPTIONS = 16;

    public static JsonObject ingredient(Ing ing) {
        JsonObject o = new JsonObject();
        if (ing.custom() != null) {
            o.addProperty("custom_type", ing.custom().type());
        } else {
            List<String> tags = ing.tags();
            if (!tags.isEmpty()) o.add("tags", Json.strings(tags));
        }
        JsonArray arr = new JsonArray();
        for (Item i : ing.options()) {
            if (arr.size() >= MAX_LISTED_OPTIONS) break;
            arr.add(BuiltInRegistries.ITEM.getKey(i).toString());
        }
        o.add("items", arr);
        if (ing.options().size() > MAX_LISTED_OPTIONS) o.addProperty("item_count", ing.options().size());
        return o;
    }

    /** id, type, result, pattern/key or ingredients, cooking data; plus the codec JSON when raw. */
    public static JsonObject recipe(RecipeView r, HolderLookup.Provider regs, boolean raw) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id().toString());
        o.addProperty("type", r.type());
        o.addProperty("serializer", r.serializer());
        if (!r.group().isEmpty()) o.addProperty("group", r.group());
        if (!r.result().isEmpty()) o.add("result", Stacks.json(r.result(), false, regs));
        if (r.special()) o.addProperty("special", true);
        if (r.shaped()) {
            o.addProperty("width", r.width());
            o.addProperty("height", r.height());
            Map<String, Character> keyOf = new LinkedHashMap<>();
            JsonObject key = new JsonObject();
            JsonArray pattern = new JsonArray();
            for (int y = 0; y < r.height(); y++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < r.width(); x++) {
                    Ing ing = r.slots().get(y * r.width() + x);
                    if (ing.isEmpty()) {
                        row.append(' ');
                        continue;
                    }
                    row.append(keyOf.computeIfAbsent(ing.signature(), sig -> {
                        char k = (char) ('A' + keyOf.size());
                        key.add(String.valueOf(k), ingredient(ing));
                        return k;
                    }));
                }
                pattern.add(row.toString());
            }
            o.add("pattern", pattern);
            o.add("key", key);
        } else {
            JsonArray arr = new JsonArray();
            for (Ing ing : r.inputs()) arr.add(ingredient(ing));
            o.add("ingredients", arr);
        }
        if (r.cooking() != null) {
            o.addProperty("cooking_time_ticks", r.cooking().ticks());
            o.addProperty("experience", r.cooking().experience());
        }
        if (raw) o.add("raw", Json.encode(Recipe.CODEC, r.recipe(), regs));
        return o;
    }
}
