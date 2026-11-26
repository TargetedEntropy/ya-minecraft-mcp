package dev.yamc.mcp.util;

import com.mojang.serialization.Codec;
import dev.yamc.mcp.Config;
import dev.yamc.mcp.mcp.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolves registries across the three places a client can find them:
 * <ul>
 *   <li>static  - BuiltInRegistries (vanilla + modded), available even on the title screen</li>
 *   <li>synced  - data-driven registries the server sent to this client (biome, damage_type, enchantment, ...)</li>
 *   <li>integrated_server - every registry of the singleplayer/LAN-host server, incl. structures, worldgen and loot tables</li>
 * </ul>
 */
public final class Regs {
    private Regs() {}

    public record Resolved(Registry<?> registry, String source, RegistryAccess access) {}

    private static volatile Map<ResourceKey<?>, Codec<?>> codecs;

    @Nullable
    public static ClientPacketListener connection() {
        return Minecraft.getInstance().getConnection();
    }

    @Nullable
    public static RegistryAccess clientAccess() {
        ClientPacketListener c = connection();
        return c == null ? null : c.registryAccess();
    }

    @Nullable
    public static MinecraftServer server() {
        if (!Config.ENABLE_INTEGRATED_SERVER_TOOLS.get()) return null;
        return Minecraft.getInstance().getSingleplayerServer();
    }

    @Nullable
    public static RegistryAccess serverAccess() {
        MinecraftServer s = server();
        if (s == null) return null;
        try {
            return s.reloadableRegistries().get();
        } catch (Throwable t) {
            return s.registryAccess();
        }
    }

    /** Registry access used for codec encoding: the richest one available. */
    public static RegistryAccess bestAccess() {
        RegistryAccess s = serverAccess();
        if (s != null) return s;
        RegistryAccess c = clientAccess();
        if (c != null) return c;
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    public static boolean isStatic(ResourceKey<? extends Registry<?>> key) {
        return BuiltInRegistries.REGISTRY.containsKey(key.location());
    }

    /** All known registries: id -> resolved (preferring client view for synced ones). */
    public static Map<ResourceLocation, Resolved> all() {
        Map<ResourceLocation, Resolved> out = new TreeMap<>();
        RegistryAccess server = serverAccess();
        if (server != null) server.registries().forEach(e -> out.put(e.key().location(), new Resolved(e.value(), sourceFor(e.key(), false), server)));
        RegistryAccess client = clientAccess();
        if (client != null) client.registries().forEach(e -> out.put(e.key().location(), new Resolved(e.value(), sourceFor(e.key(), true), client)));
        RegistryAccess stat = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (Registry<?> r : BuiltInRegistries.REGISTRY) out.putIfAbsent(r.key().location(), new Resolved(r, "static", stat));
        return out;
    }

    private static String sourceFor(ResourceKey<? extends Registry<?>> key, boolean client) {
        if (isStatic(key)) return "static";
        return client ? "synced" : "integrated_server";
    }

    /**
     * @param pref "auto" (client view first, then integrated server, then static), "client" or "server".
     */
    public static Resolved resolve(ResourceLocation id, String pref) {
        ResourceKey<Registry<Object>> key = ResourceKey.createRegistryKey(id);
        RegistryAccess client = clientAccess();
        RegistryAccess server = serverAccess();
        if ("server".equals(pref)) {
            if (server == null) throw new ToolException("No integrated server: source=server only works in singleplayer or when hosting LAN (and with enableIntegratedServerTools).");
            return server.registry(key).map(r -> new Resolved(r, sourceFor(key, false), server))
                    .orElseThrow(() -> new ToolException("Registry '" + id + "' not found on the integrated server."));
        }
        if (client != null) {
            var r = client.registry(key);
            if (r.isPresent()) return new Resolved(r.get(), sourceFor(key, true), client);
        }
        if (!"client".equals(pref) && server != null) {
            var r = server.registry(key);
            if (r.isPresent()) return new Resolved(r.get(), sourceFor(key, false), server);
        }
        Registry<?> stat = BuiltInRegistries.REGISTRY.get(id);
        if (stat != null) return new Resolved(stat, "static", RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        String hint = client == null ? " Not in a world, so only static registries are available." :
                (server == null ? " This may be a server-only registry (structures, worldgen, loot tables): those are only readable in singleplayer/LAN-host." : "");
        throw new ToolException("Unknown registry '" + id + "'." + hint + " Use registry_list to see available registries.");
    }

    /** Element codec for a data-driven registry, or null for static registries without one. */
    @SuppressWarnings("unchecked")
    @Nullable
    public static <T> Codec<T> elementCodec(ResourceKey<? extends Registry<T>> key) {
        Map<ResourceKey<?>, Codec<?>> m = codecs;
        if (m == null) {
            m = new HashMap<>();
            for (var d : DataPackRegistriesHooks.getDataPackRegistriesWithDimensions().toList()) m.put(d.key(), d.elementCodec());
            for (var lt : LootDataType.values().toList()) m.put(lt.registryKey(), lt.codec());
            codecs = m;
        }
        return (Codec<T>) m.get(key);
    }

    public static List<String> sourcesDescription() {
        return List.of("static", "synced", "integrated_server");
    }
}
