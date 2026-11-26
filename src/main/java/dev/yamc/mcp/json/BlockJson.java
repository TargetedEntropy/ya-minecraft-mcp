package dev.yamc.mcp.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.util.Json;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;

/** JSON for block states and blocks in the client level. */
public final class BlockJson {
    private BlockJson() {}

    /** {id, properties} of a block state. */
    public static JsonObject state(BlockState s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
        if (!s.getProperties().isEmpty()) {
            JsonObject props = new JsonObject();
            for (Property<?> p : s.getProperties()) props.addProperty(p.getName(), value(s, p));
            o.add("properties", props);
        }
        return o;
    }

    private static <T extends Comparable<T>> String value(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    /** Every value a property can take, as strings. */
    public static <T extends Comparable<T>> JsonArray possibleValues(Property<T> p) {
        JsonArray a = new JsonArray();
        for (T v : p.getPossibleValues()) a.add(p.getName(v));
        return a;
    }

    /** The block at a position: state, fluid, light, hardness, block entity (with NBT if asked). */
    public static JsonObject at(ClientLevel l, BlockPos pos, boolean nbt) {
        JsonObject o = new JsonObject();
        o.add("pos", Json.pos(pos));
        if (!l.isLoaded(pos)) {
            o.addProperty("loaded", false);
            return o;
        }
        BlockState s = l.getBlockState(pos);
        state(s).entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        o.addProperty("name", s.getBlock().getName().getString());
        o.addProperty("air", s.isAir());
        o.addProperty("hardness", s.getDestroySpeed(l, pos));
        o.addProperty("solid", s.isSolid());
        FluidState f = s.getFluidState();
        if (!f.isEmpty()) {
            JsonObject fo = new JsonObject();
            fo.addProperty("id", BuiltInRegistries.FLUID.getKey(f.getType()).toString());
            fo.addProperty("amount", f.getAmount());
            fo.addProperty("source", f.isSource());
            o.add("fluid", fo);
        }
        o.addProperty("block_light", l.getBrightness(LightLayer.BLOCK, pos));
        o.addProperty("sky_light", l.getBrightness(LightLayer.SKY, pos));
        o.addProperty("biome", l.getBiome(pos).getRegisteredName());
        BlockEntity be = l.getBlockEntity(pos);
        if (be != null) {
            JsonObject b = new JsonObject();
            b.addProperty("type", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
            if (nbt) {
                try {
                    b.addProperty("nbt", be.saveWithoutMetadata(l.registryAccess()).toString());
                } catch (Exception e) {
                    b.addProperty("nbt_error", e.toString());
                }
            }
            o.add("block_entity", b);
        }
        return o;
    }
}
