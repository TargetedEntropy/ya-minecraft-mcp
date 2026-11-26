package dev.yamc.mcp.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.function.Function;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

    private Json() {}

    public static JsonObject pos(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }

    public static JsonObject vec(Vec3 v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", round(v.x));
        o.addProperty("y", round(v.y));
        o.addProperty("z", round(v.z));
        return o;
    }

    public static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    public static JsonArray ids(Collection<ResourceLocation> ids) {
        JsonArray a = new JsonArray();
        ids.stream().sorted().forEach(id -> a.add(id.toString()));
        return a;
    }

    public static <T> JsonArray array(Iterable<T> items, Function<T, JsonElement> f) {
        JsonArray a = new JsonArray();
        for (T t : items) a.add(f.apply(t));
        return a;
    }

    public static JsonArray strings(Iterable<String> items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return a;
    }

    /** Encodes a value with a codec into JSON using registry-aware ops. Returns an error string on failure. */
    public static <T> JsonElement encode(Codec<T> codec, T value, HolderLookup.Provider registries) {
        DynamicOps<JsonElement> ops = registries == null ? JsonOps.INSTANCE : RegistryOps.create(JsonOps.INSTANCE, registries);
        try {
            return codec.encodeStart(ops, value).mapOrElse(Function.identity(),
                    err -> new JsonPrimitive("<encode error: " + err.message() + ">"));
        } catch (Exception e) {
            return new JsonPrimitive("<encode error: " + e + ">");
        }
    }
}
