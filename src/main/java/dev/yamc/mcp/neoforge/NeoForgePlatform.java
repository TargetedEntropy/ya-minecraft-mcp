package dev.yamc.mcp.neoforge;

//? if neoforge {
import com.mojang.serialization.Codec;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.platform.Platform;
import dev.yamc.mcp.platform.Settings;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class NeoForgePlatform implements Platform {
    @Override
    public Settings.Source settings() {
        return NeoForgeConfig.INSTANCE;
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public @Nullable Path modJar() {
        try {
            return ModList.get().getModFileById(YamcMcp.MOD_ID).getFile().getFilePath();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<ModInfo> mods() {
        return ModList.get().getMods().stream()
                .map(m -> new ModInfo(m.getModId(), m.getDisplayName(), m.getVersion().toString(), m.getDescription().trim()))
                .toList();
    }

    @Override
    public boolean isFishingRod(ItemStack s) {
        return s.getItem() instanceof FishingRodItem || s.canPerformAction(ItemAbilities.FISHING_ROD_CAST);
    }

    @Override
    public Map<ResourceKey<?>, Codec<?>> dataPackCodecs() {
        Map<ResourceKey<?>, Codec<?>> m = new HashMap<>();
        //? if >=26.3 {
        /*DataPackRegistriesHooks.getWorldRegistriesWithDimensions().forEach(d -> m.put(d.key(), d.elementCodec()));
        *///?} else
        DataPackRegistriesHooks.getDataPackRegistriesWithDimensions().forEach(d -> m.put(d.key(), d.elementCodec()));
        return m;
    }

    @Override
    public @Nullable CustomIngredient customIngredient(Ingredient ing) {
        if (!ing.isCustom()) return null;
        var custom = ing.getCustomIngredient();
        return new CustomIngredient(String.valueOf(NeoForgeRegistries.INGREDIENT_TYPES.getKey(custom.getType())), custom.hashCode());
    }
}
//?}
