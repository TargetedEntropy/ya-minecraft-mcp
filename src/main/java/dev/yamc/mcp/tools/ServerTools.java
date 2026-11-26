package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.ClientThread;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Regs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.Set;
import java.util.TreeSet;

/** Tools that need the integrated (singleplayer / LAN host) server. */
final class ServerTools {
    private ServerTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("loot_table").title("Loot table")
                .desc("Singleplayer only. A loot table (blocks/..., entities/..., chests/..., gameplay/...) with a flattened list of possible item drops and tag drops, plus the full table JSON. Find ids with registry_entries registry=minecraft:loot_table.")
                .schema(Schema.of().reqStr("id", "Loot table id, e.g. minecraft:chests/simple_dungeon or minecraft:blocks/diamond_ore.")
                        .bool("include_raw", "Include full table JSON (default true)."))
                .kind(Tool.Kind.SERVER)
                .handler(a -> ToolResult.json(lootTable(a))));

        r.add(Tool.builder("structure_info").title("Structure info")
                .desc("Singleplayer only. A structure's biomes it can generate in, generation step, the structure sets that place it (spacing/separation/salt) and the full JSON.")
                .schema(Schema.of().reqStr("id", "Structure id, e.g. minecraft:village_plains or minecraft:ancient_city.")
                        .bool("include_raw", "Include full structure JSON (default false)."))
                .kind(Tool.Kind.SERVER)
                .handler(a -> ToolResult.json(structureInfo(a))));

        r.add(Tool.builder("locate_structure").title("Locate structure")
                .desc("Singleplayer only (like /locate structure). Nearest structure (id or #tag) to the player or given x/z in the player's current dimension.")
                .schema(Schema.of().reqStr("structure", "Structure id or #tag, e.g. minecraft:stronghold, #minecraft:village.")
                        .integer("x", "Search origin X (default player).").integer("z", "Search origin Z (default player).")
                        .integer("radius_chunks", "Search radius in chunks (default 100, max 300)."))
                .kind(Tool.Kind.SERVER).anyThread()
                .handler(ServerTools::locateStructure));

        r.add(Tool.builder("locate_biome").title("Locate biome")
                .desc("Singleplayer only (like /locate biome). Nearest biome (id or #tag) in the player's current dimension.")
                .schema(Schema.of().reqStr("biome", "Biome id or #tag, e.g. minecraft:cherry_grove, #minecraft:is_ocean.")
                        .integer("x", "Origin X (default player).").integer("z", "Origin Z (default player).")
                        .integer("radius", "Search radius in blocks (default 6400, max 12800)."))
                .kind(Tool.Kind.SERVER).anyThread()
                .handler(ServerTools::locateBiome));
    }

    static MinecraftServer server() {
        MinecraftServer s = Regs.server();
        if (s == null) throw new ToolException("This tool needs the integrated server: it only works in singleplayer or when hosting a LAN world"
                + " (server-only data like structures and loot tables is never sent to clients).");
        return s;
    }

    static JsonObject lootTable(Args a) {
        MinecraftServer s = server();
        ResourceLocation id = a.reqId("id");
        RegistryAccess access = s.reloadableRegistries().get();
        Registry<LootTable> reg = access.registryOrThrow(Registries.LOOT_TABLE);
        LootTable t = reg.get(id);
        if (t == null) throw new ToolException("No loot table '" + id + "'. Use registry_entries registry=minecraft:loot_table query=...");
        JsonElement raw = Json.encode(LootTable.DIRECT_CODEC, t, access);
        Set<String> items = new TreeSet<>(), tags = new TreeSet<>(), refs = new TreeSet<>();
        walk(raw, items, tags, refs);
        JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        if (raw.isJsonObject() && raw.getAsJsonObject().has("pools")) o.addProperty("pools", raw.getAsJsonObject().getAsJsonArray("pools").size());
        o.add("possible_items", Json.strings(items));
        if (!tags.isEmpty()) o.add("possible_tags", Json.strings(tags));
        if (!refs.isEmpty()) o.add("nested_loot_tables", Json.strings(refs));
        if (a.bool("include_raw", true)) o.add("table", raw);
        return o;
    }

    private static void walk(JsonElement e, Set<String> items, Set<String> tags, Set<String> refs) {
        if (e == null) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) walk(x, items, tags, refs);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.get("type").isJsonPrimitive() && o.has("name") && o.get("name").isJsonPrimitive()) {
                String type = o.get("type").getAsString(), name = o.get("name").getAsString();
                switch (type) {
                    case "minecraft:item" -> items.add(name);
                    case "minecraft:tag" -> tags.add("#" + name);
                    case "minecraft:loot_table" -> refs.add(name);
                    default -> {}
                }
            }
            if (o.has("type") && "minecraft:loot_table".equals(o.get("type").getAsString()) && o.has("value") && o.get("value").isJsonPrimitive())
                refs.add(o.get("value").getAsString());
            for (var en : o.entrySet()) walk(en.getValue(), items, tags, refs);
        }
    }

    static JsonObject structureInfo(Args a) {
        MinecraftServer s = server();
        ResourceLocation id = a.reqId("id");
        RegistryAccess access = s.registryAccess();
        Registry<Structure> reg = access.registryOrThrow(Registries.STRUCTURE);
        Structure st = reg.get(id);
        if (st == null) throw new ToolException("No structure '" + id + "'. Use registry_entries registry=minecraft:worldgen/structure.");
        JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        o.addProperty("step", st.step().getSerializedName());
        o.addProperty("terrain_adaptation", st.terrainAdaptation().getSerializedName());
        JsonArray biomes = new JsonArray();
        st.biomes().stream().map(Holder::getRegisteredName).sorted().forEach(biomes::add);
        o.add("biomes", biomes);
        st.biomes().unwrapKey().ifPresent(tag -> o.addProperty("biome_tag", "#" + tag.location()));
        JsonArray sets = new JsonArray();
        Registry<StructureSet> setReg = access.registryOrThrow(Registries.STRUCTURE_SET);
        for (var e : setReg.entrySet()) {
            StructureSet set = e.getValue();
            boolean contains = set.structures().stream().anyMatch(se -> se.structure().is(id));
            if (!contains) continue;
            JsonObject so = new JsonObject();
            so.addProperty("id", e.getKey().location().toString());
            so.add("placement", Json.encode(StructurePlacement.CODEC, set.placement(), access));
            JsonArray members = new JsonArray();
            for (var se : set.structures()) {
                JsonObject m = new JsonObject();
                m.addProperty("structure", se.structure().getRegisteredName());
                m.addProperty("weight", se.weight());
                members.add(m);
            }
            so.add("structures", members);
            sets.add(so);
        }
        o.add("structure_sets", sets);
        JsonArray tagsArr = new JsonArray();
        reg.getHolder(id).ifPresent(h -> h.tags().map(t -> "#" + t.location()).sorted().forEach(tagsArr::add));
        o.add("tags", tagsArr);
        if (a.bool("include_raw", false)) o.add("raw", Json.encode(Structure.DIRECT_CODEC, st, access));
        return o;
    }

    record Origin(ResourceKey<Level> dim, BlockPos pos) {}

    static Origin origin(Args a) throws Exception {
        return ClientThread.call(() -> {
            var p = Ctx.player();
            BlockPos b = p.blockPosition();
            return new Origin(p.level().dimension(), new BlockPos(a.integer("x", b.getX()), b.getY(), a.integer("z", b.getZ())));
        });
    }

    static ToolResult locateStructure(Args a) throws Exception {
        MinecraftServer s = ClientThread.call(ServerTools::server);
        String spec = a.reqStr("structure");
        int radius = a.integer("radius_chunks", 100, 1, 300);
        Origin org = origin(a);
        JsonObject res = ClientThread.callServer(s, () -> {
            ServerLevel lvl = s.getLevel(org.dim);
            if (lvl == null) throw new ToolException("Dimension not loaded on server.");
            Registry<Structure> reg = lvl.registryAccess().registryOrThrow(Registries.STRUCTURE);
            HolderSet<Structure> set;
            if (spec.startsWith("#")) {
                set = reg.getTag(TagKey.create(Registries.STRUCTURE, Args.parseId(spec)))
                        .orElseThrow(() -> new ToolException("Unknown structure tag " + spec));
            } else {
                ResourceLocation id = Args.parseId(spec);
                var h = reg.getHolder(id).orElseThrow(() -> new ToolException("Unknown structure '" + id + "'."));
                set = HolderSet.direct(h);
            }
            long t0 = System.currentTimeMillis();
            Pair<BlockPos, Holder<Structure>> found = lvl.getChunkSource().getGenerator().findNearestMapStructure(lvl, set, org.pos, radius, false);
            JsonObject o = new JsonObject();
            o.addProperty("found", found != null);
            o.addProperty("dimension", org.dim.location().toString());
            o.add("origin", Json.pos(org.pos));
            if (found != null) {
                o.addProperty("structure", found.getSecond().getRegisteredName());
                o.add("pos", Json.pos(found.getFirst()));
                double dx = found.getFirst().getX() - org.pos.getX(), dz = found.getFirst().getZ() - org.pos.getZ();
                o.addProperty("horizontal_distance", Math.round(Math.sqrt(dx * dx + dz * dz)));
                o.addProperty("note", "Y is approximate (structure start height may differ).");
            }
            o.addProperty("search_ms", System.currentTimeMillis() - t0);
            return o;
        }, 90_000);
        return ToolResult.json(res);
    }

    static ToolResult locateBiome(Args a) throws Exception {
        MinecraftServer s = ClientThread.call(ServerTools::server);
        String spec = a.reqStr("biome");
        int radius = a.integer("radius", 6400, 64, 12800);
        Origin org = origin(a);
        JsonObject res = ClientThread.callServer(s, () -> {
            ServerLevel lvl = s.getLevel(org.dim);
            if (lvl == null) throw new ToolException("Dimension not loaded on server.");
            java.util.function.Predicate<Holder<Biome>> pred;
            if (spec.startsWith("#")) {
                TagKey<Biome> tag = TagKey.create(Registries.BIOME, Args.parseId(spec));
                pred = h -> h.is(tag);
            } else {
                ResourceLocation id = Args.parseId(spec);
                if (!lvl.registryAccess().registryOrThrow(Registries.BIOME).containsKey(id)) throw new ToolException("Unknown biome '" + id + "'.");
                pred = h -> h.is(id);
            }
            long t0 = System.currentTimeMillis();
            Pair<BlockPos, Holder<Biome>> found = lvl.findClosestBiome3d(pred, org.pos, radius, 32, 64);
            JsonObject o = new JsonObject();
            o.addProperty("found", found != null);
            o.addProperty("dimension", org.dim.location().toString());
            if (found != null) {
                o.addProperty("biome", found.getSecond().getRegisteredName());
                o.add("pos", Json.pos(found.getFirst()));
                double dx = found.getFirst().getX() - org.pos.getX(), dz = found.getFirst().getZ() - org.pos.getZ();
                o.addProperty("horizontal_distance", Math.round(Math.sqrt(dx * dx + dz * dz)));
            }
            o.addProperty("search_ms", System.currentTimeMillis() - t0);
            return o;
        }, 90_000);
        return ToolResult.json(res);
    }

}
