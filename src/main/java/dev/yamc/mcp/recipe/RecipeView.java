package dev.yamc.mcp.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Everything the recipe tools read from one recipe, extracted once by {@link Recipes} so the tools
 * never touch the recipe API (which changed completely in Minecraft 1.21.2).
 *
 * @param slots   shaped: the width x height grid, row by row, with {@link Ing#EMPTY} for empty cells;
 *                otherwise the ingredients in order
 * @param width   0 for shapeless and non-crafting recipes
 * @param result  the result stack, or EMPTY when the recipe has none or it cannot be computed
 * @param recipe  the recipe itself, for its codec JSON
 */
public record RecipeView(ResourceLocation id, String type, String serializer, String group, ItemStack result,
                         List<Ing> slots, int width, int height, boolean special, @Nullable Cooking cooking,
                         Recipe<?> recipe) {
    public record Cooking(int ticks, float experience) {}

    public boolean shaped() {
        return width > 0;
    }

    /** The non-empty ingredients, one per item consumed. */
    public List<Ing> inputs() {
        return slots.stream().filter(i -> !i.isEmpty()).toList();
    }

    /** Items produced per craft (at least 1). */
    public int yield() {
        return Math.max(1, result.getCount());
    }
}
