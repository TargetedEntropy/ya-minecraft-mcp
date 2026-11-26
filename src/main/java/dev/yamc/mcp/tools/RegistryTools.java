package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Names;
import dev.yamc.mcp.util.Paging;
import dev.yamc.mcp.util.Regs;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
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
                    List<Map.Entry<ResourceLocation, Regs.Resolved>> list = Regs.all().entrySet().stream()
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
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server")
                        .paging(100))
                .handler(a -> ToolResult.json(entries(a))));

        r.add(Tool.builder("registry_entry").title("Registry entry details")
                .desc("Full details of one registry entry. Data-driven entries (biomes, damage types, enchantments, structures, worldgen features, loot tables, modded datapack registries, ...) are encoded to JSON with their own codec. Static entries get a type-specific summary (blocks: hardness, states, loot table; items: default components; entities: size, category; effects; attributes; ...). Always includes tags.")
                .schema(Schema.of().reqStr("registry", "Registry id, e.g. minecraft:block.")
                        .reqStr("id", "Entry id, e.g. minecraft:oak_log.")
                        .bool("include_data", "Include the codec-encoded data for data-driven entries (default true).")
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server"))
                .handler(a -> ToolResult.json(entry(a))));

        r.add(Tool.builder("tags_list").title("List tags")
                .desc("List tags of a registry (with member counts), filtered by namespace/query/regex on the tag id. Common convention tags live in the 'c' namespace (e.g. #c:ingots).")
                .schema(Schema.of().reqStr("registry", "Registry id, e.g. minecraft:item.")
                        .idFilters()
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server")
                        .paging(100))
                .handler(a -> ToolResult.json(tagsList(a))));

        r.add(Tool.builder("tag_members").title("Tag members")
                .desc("Resolve a tag to its member entries (nested tags are already flattened).")
                .schema(Schema.of().reqStr("registry", "Registry id, e.g. minecraft:block.")
                        .reqStr("tag", "Tag id, e.g. #minecraft:logs or c:ingots.")
                        .bool("include_names", "Include localized names (default true).")
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server")
                        .paging(200))
                .handler(a -> ToolResult.json(tagMembers(a))));

        r.add(Tool.builder("tags_of").title("Tags of an entry")
                .desc("Reverse lookup: every tag that contains the given entry.")
                .schema(Schema.of().reqStr("registry", "Registry id.").reqStr("id", "Entry id.")
                        .enumStr("source", SOURCE_DESC, false, "auto", "client", "server"))
                .handler(a -> {
                    Regs.Resolved res = Regs.resolve(a.reqId("registry"), a.str("source", "auto"));
                    Holder.Reference<?> h = holder(res.registry(), a.reqId("id"));
                    JsonObject o = new JsonObject();
                    o.addProperty("registry", res.registry().key().location().toString());
                    o.addProperty("id", a.reqId("id").toString());
                    o.add("tags", Json.strings(h.tags().map(t -> "#" + t.location()).sorted().toList()));
                    return ToolResult.json(o);
                }));
    }

    // ------------------------------------------------------------------ helpers

    static Holder.Reference<?> holder(Registry<?> reg, ResourceLocation id) {
        return reg.getHolder(id).orElseThrow(() -> new ToolException("No entry '" + id + "' in registry " + reg.key().location()
                + ". Use registry_entries with query= to search."));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Set<ResourceLocation> tagMemberIds(Registry reg, ResourceLocation tagId) {
        TagKey tk = TagKey.create(reg.key(), tagId);
        java.util.Optional<HolderSet.Named<?>> opt = reg.getTag(tk);
        if (opt.isEmpty()) throw new ToolException("Unknown tag #" + tagId + " in registry " + reg.key().location() + ". Use tags_list.");
        HolderSet.Named<?> set = opt.get();
        return set.stream().map(h -> ((Holder<?>) h).unwrapKey().map(k -> ((ResourceKey<?>) k).location()).orElse(null))
                .filter(x -> x != null).collect(Collectors.toSet());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static JsonObject entries(Args a) {
        Regs.Resolved res = Regs.resolve(a.reqId("registry"), a.str("source", "auto"));
        Registry reg = res.registry();
        IdFilter f = IdFilter.from(a);
        boolean names = a.bool("include_names", true), tags = a.bool("include_tags", false);
        Set<ResourceLocation> tagSet = a.has("tag") ? tagMemberIds(reg, a.reqId("tag")) : null;
        List<Holder.Reference<?>> list = new ArrayList<>();
        ((java.util.stream.Stream<Holder.Reference<?>>) reg.holders()).forEach(h -> {
            ResourceLocation id = h.key().location();
            if (tagSet != null && !tagSet.contains(id)) return;
            if (!f.test(id, () -> Names.of(reg.key(), id, h.value()))) return;
            list.add(h);
        });
        list.sort(Comparator.comparing(h -> h.key().location()));
        JsonObject o = Paging.page(list, a, h -> {
            ResourceLocation id = h.key().location();
            if (!names && !tags) return new com.google.gson.JsonPrimitive(id.toString());
            JsonObject e = new JsonObject();
            e.addProperty("id", id.toString());
            if (names) {
                String n = Names.of(reg.key(), id, h.value());
                if (n != null) e.addProperty("name", n);
            }
            if (tags) e.add("tags", Json.strings(h.tags().map(t -> "#" + t.location()).sorted().toList()));
            return e;
        }, 100);
        o.addProperty("registry", reg.key().location().toString());
        o.addProperty("source", res.source());
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static JsonObject entry(Args a) {
        Regs.Resolved res = Regs.resolve(a.reqId("registry"), a.str("source", "auto"));
        Registry reg = res.registry();
        ResourceLocation id = a.reqId("id");
        Holder.Reference h = holder(reg, id);
        Object v = h.value();
        JsonObject o = new JsonObject();
        o.addProperty("registry", reg.key().location().toString());
        o.addProperty("id", id.toString());
        o.addProperty("source", res.source());
        String n = Names.of(reg.key(), id, v);
        if (n != null) o.addProperty("name", n);
        o.addProperty("class", v.getClass().getName());
        o.add("tags", Json.strings(((java.util.stream.Stream<TagKey<?>>) h.tags()).map(t -> "#" + t.location()).sorted().toList()));
        JsonObject details = describeStatic(v, res);
        if (details != null) o.add("details", details);
        Codec codec = Regs.elementCodec(reg.key());
        if (codec != null && a.bool("include_data", true)) o.add("data", Json.encode(codec, v, res.access()));
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static JsonObject describeStatic(Object v, Regs.Resolved res) {
        JsonObject d = new JsonObject();
        if (v instanceof Block b) {
            BlockState s = b.defaultBlockState();
            d.addProperty("hardness", b.defaultDestroyTime());
            d.addProperty("blast_resistance", b.getExplosionResistance());
            d.addProperty("requires_correct_tool", s.requiresCorrectToolForDrops());
            d.addProperty("light_emission", s.getLightEmission());
            d.addProperty("friction", b.getFriction());
            if (b.getSpeedFactor() != 1f) d.addProperty("speed_factor", b.getSpeedFactor());
            if (b.getJumpFactor() != 1f) d.addProperty("jump_factor", b.getJumpFactor());
            d.addProperty("item", BuiltInRegistries.ITEM.getKey(b.asItem()).toString());
            d.addProperty("loot_table", b.getLootTable().location().toString());
            d.addProperty("state_count", b.getStateDefinition().getPossibleStates().size());
            JsonObject props = new JsonObject();
            for (Property<?> p : b.getStateDefinition().getProperties()) {
                JsonArray vals = new JsonArray();
                for (Comparable c : p.getPossibleValues()) vals.add(((Property) p).getName(c));
                props.add(p.getName(), vals);
            }
            if (!props.isEmpty()) d.add("properties", props);
            d.add("default_state", WorldTools.stateJson(s));
            d.addProperty("has_block_entity", s.hasBlockEntity());
            return d;
        }
        if (v instanceof Item i) {
            d.addProperty("max_stack", i.getDefaultMaxStackSize());
            if (i instanceof BlockItem bi) d.addProperty("block", BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString());
            Item rem = i.getCraftingRemainingItem();
            if (rem != null) d.addProperty("crafting_remainder", BuiltInRegistries.ITEM.getKey(rem).toString());
            d.add("default_components", Json.encode(DataComponentMap.CODEC, i.components(), res.access()));
            return d;
        }
        if (v instanceof EntityType<?> e) {
            d.addProperty("category", e.getCategory().getName());
            d.addProperty("width", e.getDimensions().width());
            d.addProperty("height", e.getDimensions().height());
            d.addProperty("fire_immune", e.fireImmune());
            d.addProperty("summonable", e.canSummon());
            d.addProperty("tracking_range_chunks", e.clientTrackingRange());
            d.addProperty("loot_table", e.getDefaultLootTable().location().toString());
            return d;
        }
        if (v instanceof MobEffect m) {
            d.addProperty("category", m.getCategory().name().toLowerCase());
            d.addProperty("beneficial", m.isBeneficial());
            d.addProperty("color", String.format("#%06X", m.getColor() & 0xFFFFFF));
            return d;
        }
        if (v instanceof Potion p) {
            JsonArray fx = new JsonArray();
            for (MobEffectInstance i : p.getEffects()) {
                JsonObject x = new JsonObject();
                x.addProperty("effect", i.getEffect().getRegisteredName());
                x.addProperty("amplifier", i.getAmplifier());
                x.addProperty("duration_ticks", i.getDuration());
                fx.add(x);
            }
            d.add("effects", fx);
            return d;
        }
        if (v instanceof Attribute at) {
            d.addProperty("default", at.getDefaultValue());
            d.addProperty("synced", at.isClientSyncable());
            if (at instanceof RangedAttribute ra) {
                d.addProperty("min", ra.getMinValue());
                d.addProperty("max", ra.getMaxValue());
            }
            return d;
        }
        if (v instanceof Fluid f) {
            d.addProperty("bucket", BuiltInRegistries.ITEM.getKey(f.getBucket()).toString());
            d.addProperty("source", f.isSource(f.defaultFluidState()));
            return d;
        }
        if (v instanceof SoundEvent se) {
            d.addProperty("location", se.getLocation().toString());
            return d;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static JsonObject tagsList(Args a) {
        Regs.Resolved res = Regs.resolve(a.reqId("registry"), a.str("source", "auto"));
        Registry reg = res.registry();
        IdFilter f = IdFilter.from(a);
        List<com.mojang.datafixers.util.Pair<TagKey<?>, HolderSet.Named<?>>> tags = new ArrayList<>();
        ((java.util.stream.Stream<com.mojang.datafixers.util.Pair<TagKey<?>, HolderSet.Named<?>>>) reg.getTags())
                .filter(p -> f.test(p.getFirst().location(), null)).forEach(tags::add);
        tags.sort(Comparator.comparing(p -> p.getFirst().location()));
        JsonObject o = Paging.page(tags, a, p -> {
            JsonObject e = new JsonObject();
            e.addProperty("tag", "#" + p.getFirst().location());
            e.addProperty("size", p.getSecond().size());
            return e;
        }, 100);
        o.addProperty("registry", reg.key().location().toString());
        o.addProperty("source", res.source());
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static JsonObject tagMembers(Args a) {
        Regs.Resolved res = Regs.resolve(a.reqId("registry"), a.str("source", "auto"));
        Registry reg = res.registry();
        ResourceLocation tagId = a.reqId("tag");
        boolean names = a.bool("include_names", true);
        List<ResourceLocation> ids = new ArrayList<>(tagMemberIds(reg, tagId));
        ids.sort(Comparator.naturalOrder());
        JsonObject o = Paging.page(ids, a, id -> {
            if (!names) return new com.google.gson.JsonPrimitive(id.toString());
            JsonObject e = new JsonObject();
            e.addProperty("id", id.toString());
            String n = Names.of(reg.key(), id, reg.get(id));
            if (n != null) e.addProperty("name", n);
            return e;
        }, 200);
        o.addProperty("registry", reg.key().location().toString());
        o.addProperty("tag", "#" + tagId);
        o.addProperty("source", res.source());
        return o;
    }
}
