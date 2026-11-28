package dev.yamc.mcp.platform;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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

    /**
     * What a block exposes through the loader's storage API (NeoForge capabilities, Fabric Transfer API), seen from one
     * side (null: no particular side). Integrated server thread.
     */
    Storage storage(ServerLevel level, BlockPos pos, @Nullable Direction side);

    /** A mod's configs registered with the loader's config system (NeoForge's ModConfigSpec); empty where there is none. */
    List<ConfigSpec> modConfigs(String modId);

    /**
     * Sets one registered config value, already converted to the type of its default, and saves it; returns the value now
     * in effect. Throws ToolException where unsupported or not allowed.
     */
    Object setModConfig(String modId, String path, Object value);

    record ModInfo(String id, String name, String version, String description) {}

    /**
     * @param items  each slot, or null if the block has no item storage on that side; likewise fluids and energy
     * @param fluidPerBucket the loader's fluid unit: 1000 (millibuckets) on NeoForge, 81000 (droplets) on Fabric
     */
    record Storage(@Nullable List<Slot> items, @Nullable List<Tank> fluids, @Nullable Energy energy, long fluidPerBucket) {
        public boolean any() {
            return items != null || fluids != null || energy != null;
        }
    }

    /**
     * One registered config file.
     * @param values every value, or null when they cannot be read ({@code note} says why)
     */
    record ConfigSpec(String file, String type, @Nullable List<ConfigValue> values, @Nullable String note) {}

    /**
     * A config value as the loader holds it: Boolean, Number, String, an enum constant or a List of those.
     * @param restart what must restart for a change to apply, or null for nothing
     */
    record ConfigValue(String path, @Nullable Object value, @Nullable Object def, @Nullable String range, @Nullable String comment,
                       @Nullable String restart) {}

    record Slot(ItemStack stack, long amount, long capacity) {}

    record Tank(@Nullable ResourceLocation fluid, long amount, long capacity) {}

    record Energy(long stored, long capacity) {}

    /** A loader custom ingredient: its type id and an identity for grouping equal ingredients. */
    record CustomIngredient(String type, int identity) {}
}
