package dev.yamc.mcp.fabric;

//? if fabric {
/*import com.mojang.serialization.Codec;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.platform.FileSettings;
import dev.yamc.mcp.platform.Platform;
import dev.yamc.mcp.platform.Settings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FabricPlatform implements Platform {
    private final FabricLoader loader = FabricLoader.getInstance();
    private Settings settings;

    @Override
    public Settings settings() {
        if (settings == null) settings = FileSettings.load(configDir().resolve("yamcmcp.properties"));
        return settings;
    }

    @Override
    public Path configDir() {
        return loader.getConfigDir();
    }

    @Override
    public Path gameDir() {
        return loader.getGameDir();
    }

    @Override
    public @Nullable Path modJar() {
        return loader.getModContainer(YamcMcp.MOD_ID)
                .flatMap(c -> c.getOrigin().getPaths().stream().filter(Files::isRegularFile).findFirst())
                .orElse(null);
    }

    @Override
    public List<ModInfo> mods() {
        return loader.getAllMods().stream().map(c -> c.getMetadata())
                .map(m -> new ModInfo(m.getId(), m.getName(), m.getVersion().getFriendlyString(), m.getDescription().trim()))
                .toList();
    }

    @Override
    public boolean isFishingRod(ItemStack s) {
        return s.getItem() instanceof FishingRodItem;
    }

    @Override
    public Map<ResourceKey<?>, Codec<?>> dataPackCodecs() {
        // Fabric API appends mod registries to these vanilla lists.
        Map<ResourceKey<?>, Codec<?>> m = new HashMap<>();
        for (var d : RegistryDataLoader.WORLDGEN_REGISTRIES) m.put(d.key(), d.elementCodec());
        for (var d : RegistryDataLoader.DIMENSION_REGISTRIES) m.put(d.key(), d.elementCodec());
        return m;
    }

    @Override
    public @Nullable CustomIngredient customIngredient(Ingredient ing) {
        var custom = ing.getCustomIngredient();
        return custom == null ? null : new CustomIngredient(custom.getSerializer().getIdentifier().toString(), custom.hashCode());
    }
}
*///?}
