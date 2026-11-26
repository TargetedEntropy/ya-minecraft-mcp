package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Pair;
import dev.yamc.mcp.json.RegistryJson;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Names;
import dev.yamc.mcp.util.Paging;
import dev.yamc.mcp.util.Source;
import dev.yamc.mcp.util.Regs;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class RegistryTools {
    private RegistryTools() {}

    static final String SOURCE_DESC = "Where to read the registry: auto (default: client view, falling back to the integrated server for server-only registries), client, or server (singleplayer/LAN host only; full fidelity incl. structures, worldgen, loot tables).";

    static void register(ToolRegistry r) {
        r.add(Tool.builder("registry_list").title("List registries")
                .desc("List every registry visible to the client: static (blocks, items, entity types, ... incl. modded registries), synced data-driven (biome, damage_type, enchantment, ...) and - in singleplayer - server-only data-driven ones (worldgen/structure, loot_table, ...). Shows entry count, tag count, source and whether entries can be dumped as JSON.")
                .schema(Schema.of().idFilters().paging(200))
                .handler(a -> {
                    IdFilter f = IdFilter.from(a);
                    List<Map.Entry<ResourceLocation, Regs.Resolved<?>>> list = Regs.all().entrySet().stream()
                            .filter(e -> f.test(e.getKey(), null)).toList();
                    return ToolResult.json(Paging.page(list, a, e -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", e.getKey().toString());
                        o.addProperty("source", e.getValue().source());
                        o.addProperty("size", e.getValue().registry().size());
                        o.addProperty("tags", e.getValue().registry().getTagNames().count());
                        o.addProperty("data_driven", Regs.elementCodec(e.getValue().registry().key()) != null);
                        return o;
                    }, 200));
                }));

        r.add(Tool.builder("registry_entries").title("List registry entries")
                .desc("List entry ids of any registry with filtering and paging. Works for blocks, items, biomes, damage types, enchantments, entity types, structures (singleplayer), loot tables (singleplayer) and any modded registry. Filter by namespace (mod id), query (matches id or localized display name), regex, or tag membership.")
                .schema(Schema.of().reqStr("registry", "Registry id, e.g. minecraft:block, minecraft:item, minecraft:worldgen/biome, minecraft:damage_type, minecraft:worldgen/structure, minecraft:loot_table.")
                        .idFilters()
                        .str("tag", "Only entries in this tag, e.g. #minecraft:logs.")
                        .bool("include_names", "Include localized display names (default true).")
                        .bool("include_tags", "Include the tags of each entry (default false).")
                        .source(SOURCE_DESC)
                        .paging(100))
                .handler(a -> ToolResult.json(withRegistry(a, RegistryTools::entries))));

        r.add(Tool.builder("registry_entry").title("Registry entry details")
                .desc("Full details of one registry entry. Data-driven entries (biomes, damage types, enchantments, structures, worldgen features, loot tables, modded datapack registries, ...) are encoded to JSON with their own codec. Static entries get a type-specific summary (blocks: hardness, states, loot table; items: default components; entities: size, category; effects; attributes; ...). Always includes tags.")
                .schema(Schema.of().reqStr("registry", "Registry id, e.g. minecraft:block.")
                        .reqStr("id", "Entry id, e.g. minecraft:oak_log.")
                        .bool("include_data", "Include the codec-encoded data for data-driven entries (default true).")
                        .source(SOURCE_DESC))
                .handler(a -> ToolResult.json(withRegistry(a, RegistryTools::entry))));

        r.add(Tool.builder("tags_list").title("List tags")
                .desc("List tags of a registry (with member counts), filtered by namespace/query/regex on the tag id. Common convention tags live in the 'c' namespace (e.g. #c:ingots).")
                .schema(Schema.of().reqStr("registry", "Registry id, e.g. minecraft:item.")
                        .idFilters()
                        .source(SOURCE_DESC)
                        .paging(100))
                .handler(a -> ToolResult.json(withRegistry(a, RegistryTools::tagsList))));

        r.add(Tool.builder("tag_members").title("Tag members")
                .desc("Resolve a tag to its member entries (nested tags are already flattened).")
                .schema(Schema.of().reqStr("registry", "Registry id, e.g. minecraft:block.")
                        .reqStr("tag", "Tag id, e.g. #minecraft:logs or c:ingots.")
                        .bool("include_names", "Include localized names (default true).")
                        .source(SOURCE_DESC)
                        .paging(200))
                .handler(a -> ToolResult.json(withRegistry(a, RegistryTools::tagMembers))));

        r.add(Tool.builder("tags_of").title("Tags of an entry")
                .desc("Reverse lookup: every tag that contains the given entry.")
                .schema(Schema.of().reqStr("registry", "Registry id.").reqStr("id", "Entry id.")
                        .source(SOURCE_DESC))
                .handler(a -> ToolResult.json(withRegistry(a, RegistryTools::tagsOf))));
    }

    // ------------------------------------------------------------------ helpers

    /** A tool body over one resolved registry. Generic, so each body sees a single element type T. */
    private interface Body {
        <T> JsonObject apply(Args a, Regs.Resolved<T> res);
    }

    /** Resolves the "registry"/"source" arguments, runs the body, and tags the result with the registry and source. */
    private static JsonObject withRegistry(Args a, Body body) {
        Regs.Resolved<?> res = Regs.resolve(a.reqId("registry"), Source.from(a));
        JsonObject o = body.apply(a, res);
        o.addProperty("registry", res.registry().key().location().toString());
        o.addProperty("source", res.source());
        return o;
    }

    static <T> Holder.Reference<T> holder(Registry<T> reg, ResourceLocation id) {
        return reg.getHolder(id).orElseThrow(() -> new ToolException("No entry '" + id + "' in registry " + reg.key().location()
                + ". Use registry_entries with query= to search."));
    }

    static <T> Set<ResourceLocation> tagMemberIds(Registry<T> reg, ResourceLocation tagId) {
        HolderSet.Named<T> set = reg.getTag(TagKey.create(reg.key(), tagId)).orElseThrow(() ->
                new ToolException("Unknown tag #" + tagId + " in registry " + reg.key().location() + ". Use tags_list."));
        return set.stream().flatMap(h -> h.unwrapKey().stream()).map(ResourceKey::location).collect(Collectors.toSet());
    }

    private static <T> JsonObject entries(Args a, Regs.Resolved<T> res) {
        Registry<T> reg = res.registry();
        IdFilter f = IdFilter.from(a);
        boolean names = a.bool("include_names", true), tags = a.bool("include_tags", false);
        Set<ResourceLocation> tagSet = a.has("tag") ? tagMemberIds(reg, a.reqId("tag")) : null;
        List<Holder.Reference<T>> list = reg.holders().filter(h -> {
            ResourceLocation id = h.key().location();
            return (tagSet == null || tagSet.contains(id)) && f.test(id, () -> Names.of(reg.key(), id, h.value()));
        }).sorted(Comparator.comparing(h -> h.key().location())).toList();
        return Paging.page(list, a, h -> names || tags
                ? RegistryJson.entry(reg, h, res.access(), names, tags, false)
                : new JsonPrimitive(h.key().location().toString()), 100);
    }

    private static <T> JsonObject entry(Args a, Regs.Resolved<T> res) {
        Holder.Reference<T> h = holder(res.registry(), a.reqId("id"));
        JsonObject o = RegistryJson.entry(res.registry(), h, res.access(), true, true, a.bool("include_data", true));
        o.addProperty("class", h.value().getClass().getName());
        JsonObject details = RegistryJson.details(h.value(), res.access());
        if (details != null) o.add("details", details);
        return o;
    }

    private static <T> JsonObject tagsOf(Args a, Regs.Resolved<T> res) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.reqId("id").toString());
        o.add("tags", RegistryJson.tags(holder(res.registry(), a.reqId("id"))));
        return o;
    }

    private static <T> JsonObject tagsList(Args a, Regs.Resolved<T> res) {
        IdFilter f = IdFilter.from(a);
        List<HolderSet.Named<T>> tags = res.registry().getTags().map(Pair::getSecond).filter(s -> f.test(s.key().location(), null))
                .sorted(Comparator.comparing(s -> s.key().location())).toList();
        return Paging.page(tags, a, s -> {
            JsonObject e = new JsonObject();
            e.addProperty("tag", "#" + s.key().location());
            e.addProperty("size", s.size());
            return e;
        }, 100);
    }

    private static <T> JsonObject tagMembers(Args a, Regs.Resolved<T> res) {
        Registry<T> reg = res.registry();
        ResourceLocation tagId = a.reqId("tag");
        boolean names = a.bool("include_names", true);
        List<ResourceLocation> ids = tagMemberIds(reg, tagId).stream().sorted().toList();
        JsonObject o = Paging.page(ids, a, id -> {
            if (!names) return new JsonPrimitive(id.toString());
            JsonObject e = new JsonObject();
            e.addProperty("id", id.toString());
            String n = Names.of(reg.key(), id, reg.get(id));
            if (n != null) e.addProperty("name", n);
            return e;
        }, 200);
        o.addProperty("tag", "#" + tagId);
        return o;
    }
}
