package dev.yamc.mcp.platform;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything the mod needs from the mod loader. One implementation per loader, handed to
 * {@link dev.yamc.mcp.YamcMcp#init} by that loader's entrypoint. Code outside the loader packages
 * uses only this and vanilla Minecraft.
 */
public interface Platform {
    Settings.Source settings();

    Path configDir();

    Path gameDir();

    /** The mod's own jar (for the stdio bridge command line), or null when unknown (e.g. a dev run). */
    @Nullable Path modJar();

    List<ModInfo> mods();

    /** Vanilla fishing rods, plus anything the loader lets cast a fishing line. */
    boolean isFishingRod(ItemStack s);

    /** Element codecs of the data-driven (datapack) registries, including dimensions. */
    Map<ResourceKey<?>, Codec<?>> dataPackCodecs();

    /** The loader-defined custom ingredient behind this ingredient, or null for a vanilla one. */
    @Nullable CustomIngredient customIngredient(Ingredient ing);

    record ModInfo(String id, String name, String version, String description) {}

    /** A loader custom ingredient: its type id and an identity for grouping equal ingredients. */
    record CustomIngredient(String type, int identity) {}
}
