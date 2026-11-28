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
    private Settings.Source settings;

    @Override
    public Settings.Source settings() {
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
        //? if >=26.3 {
        /^for (var d : RegistryDataLoader.WORLD_REGISTRIES) m.put(d.key(), d.elementCodec());
        ^///?} else
        for (var d : RegistryDataLoader.WORLDGEN_REGISTRIES) m.put(d.key(), d.elementCodec());
        for (var d : RegistryDataLoader.DIMENSION_REGISTRIES) m.put(d.key(), d.elementCodec());
        return m;
    }

    @Override
    public @Nullable CustomIngredient customIngredient(Ingredient ing) {
        var custom = ing.getCustomIngredient();
        return custom == null ? null : new CustomIngredient(custom.getSerializer().getIdentifier().toString(), custom.hashCode());
    }

    @Override
    public Storage storage(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, @Nullable net.minecraft.core.Direction side) {
        if (!loader.isModLoaded("fabric-transfer-api-v1"))
            throw new dev.yamc.mcp.mcp.ToolException("Needs the Fabric Transfer API (part of Fabric API), which is not installed.");
        return FabricStorage.read(level, pos, side);
    }

    // Fabric has no shared config system: show the mod's files under config/ instead.
    @Override
    public com.google.gson.JsonArray modConfigs(String modId) {
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        String id = modId.toLowerCase(java.util.Locale.ROOT);
        try (var files = Files.walk(configDir(), 2)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                Path rel = configDir().relativize(f);
                String name = rel.toString().toLowerCase(java.util.Locale.ROOT);
                if (!name.startsWith(id)) continue;
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("file", rel.toString().replace('\\', '/'));
                if (mayHoldCredentials(name)) {
                    o.addProperty("note", "Not shown: may hold credentials.");
                    out.add(o);
                    continue;
                }
                long size = Files.size(f);
                o.addProperty("size", size);
                if (size <= 32_768) o.addProperty("text", Files.readString(f));
                else o.addProperty("note", "Too large to show.");
                out.add(o);
            }
        } catch (java.io.IOException e) {
            throw new dev.yamc.mcp.mcp.ToolException("Cannot list the config folder: " + e.getMessage());
        }
        return out;
    }

    // Tokens, passwords and keys (this mod's own token and connect files included) stay out of the AI's context.
    private static boolean mayHoldCredentials(String name) {
        return name.startsWith("yamcmcp-") || java.util.List.of("token", "secret", "password", "credential", "apikey", "api_key", ".key")
                .stream().anyMatch(name::contains);
    }

    @Override
    public com.google.gson.JsonElement setModConfig(String modId, String path, com.google.gson.JsonElement value) {
        throw new dev.yamc.mcp.mcp.ToolException("Fabric has no shared config system, so values cannot be set here: edit the mod's file under "
                + "config/ (shown by mod_config) and restart, or use the mod's own reload command if it has one.");
    }
}
*///?}
