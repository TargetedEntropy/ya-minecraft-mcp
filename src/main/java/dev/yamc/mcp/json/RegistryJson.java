package dev.yamc.mcp.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Names;
import dev.yamc.mcp.util.Regs;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
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

import java.util.Locale;

/** JSON for registry entries. */
public final class RegistryJson {
    private RegistryJson() {}

    /** Sorted "#namespace:path" tags of a holder. */
    public static JsonArray tags(Holder.Reference<?> h) {
        return Json.strings(h.tags().map(t -> "#" + t.location()).sorted().toList());
    }

    /** {id, name?, tags?, data?}. data is the codec-encoded value, only for data-driven registries. */
    public static <T> JsonObject entry(Registry<T> reg, Holder.Reference<T> h, HolderLookup.Provider access,
                                       boolean name, boolean tags, boolean data) {
        JsonObject o = new JsonObject();
        o.addProperty("id", h.key().location().toString());
        if (name) {
            String n = Names.of(reg.key(), h.key().location(), h.value());
            if (n != null) o.addProperty("name", n);
        }
        if (tags) o.add("tags", tags(h));
        Codec<T> codec = data ? Regs.elementCodec(reg.key()) : null;
        if (codec != null) o.add("data", Json.encode(codec, h.value(), access));
        return o;
    }

    /** Type-specific summary of a static registry entry (blocks, items, entity types, effects, ...), or null. */
    public static JsonObject details(Object v, HolderLookup.Provider access) {
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
            for (Property<?> p : b.getStateDefinition().getProperties()) props.add(p.getName(), BlockJson.possibleValues(p));
            if (!props.isEmpty()) d.add("properties", props);
            d.add("default_state", BlockJson.state(s));
            d.addProperty("has_block_entity", s.hasBlockEntity());
            return d;
        }
        if (v instanceof Item i) {
            d.addProperty("max_stack", i.getDefaultMaxStackSize());
            if (i instanceof BlockItem bi) d.addProperty("block", BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString());
            Item rem = i.getCraftingRemainingItem();
            if (rem != null) d.addProperty("crafting_remainder", BuiltInRegistries.ITEM.getKey(rem).toString());
            d.add("default_components", Json.encode(DataComponentMap.CODEC, i.components(), access));
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
            d.addProperty("category", m.getCategory().name().toLowerCase(Locale.ROOT));
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

}
