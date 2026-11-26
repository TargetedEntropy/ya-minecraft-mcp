package dev.yamc.mcp.recipe;

import dev.yamc.mcp.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One recipe ingredient slot, the same on every Minecraft version: the items it accepts, and for
 * loader custom ingredients their type. {@link #EMPTY} is an empty slot of a shaped recipe.
 */
public record Ing(List<Item> options, @Nullable Platform.CustomIngredient custom, @Nullable Ingredient source) {
    public static final Ing EMPTY = new Ing(List.of(), null, null);

    public boolean isEmpty() {
        return options.isEmpty() && custom == null;
    }

    public boolean accepts(Item item) {
        return options.contains(item);
    }

    /** The tags this ingredient accepts ("#namespace:path"); computed on demand from its codec. */
    public List<String> tags() {
        return source == null || custom != null ? List.of() : Recipes.tags(source);
    }

    /** Identity by options, for grouping equal ingredients. */
    public String signature() {
        StringBuilder b = new StringBuilder();
        if (custom != null) b.append("custom:").append(custom.identity()).append(';');
        options.stream().map(i -> BuiltInRegistries.ITEM.getKey(i).toString()).sorted().forEach(x -> b.append(x).append(','));
        return b.toString();
    }
}
