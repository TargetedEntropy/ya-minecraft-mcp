package dev.yamc.mcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Typed accessor over the JSON arguments of a tools/call request. */
public final class Args {
    private final JsonObject obj;

    public Args(JsonObject obj) {
        this.obj = obj == null ? new JsonObject() : obj;
    }

    public JsonObject raw() {
        return obj;
    }

    public boolean has(String key) {
        return obj.has(key) && !obj.get(key).isJsonNull();
    }

    public String str(String key, String def) {
        if (!has(key)) return def;
        JsonElement e = obj.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    /** The enum constant named (case-insensitively) by this argument, or {@code def} when it is absent. */
    public <E extends Enum<E>> E choice(String key, E def) {
        if (!has(key)) return def;
        String v = str(key, "").trim();
        for (E e : def.getDeclaringClass().getEnumConstants()) if (e.name().equalsIgnoreCase(v)) return e;
        throw new ToolException("Unknown " + key + " '" + v + "': use one of " + String.join(", ", choices(def.getDeclaringClass())) + ".");
    }

    /** An enum's constants as argument values: their names in lower case. */
    public static List<String> choices(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(e -> e.name().toLowerCase(Locale.ROOT)).toList();
    }

    public String reqStr(String key) {
        String v = str(key, null);
        if (v == null || v.isBlank()) throw new ToolException("Missing required argument '" + key + "'");
        return v;
    }

    public int integer(String key, int def) {
        if (!has(key)) return def;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            try {
                return (int) Double.parseDouble(obj.get(key).getAsString());
            } catch (Exception e2) {
                throw new ToolException("Argument '" + key + "' must be an integer");
            }
        }
    }

    public int integer(String key, int def, int min, int max) {
        return Math.max(min, Math.min(max, integer(key, def)));
    }

    public int reqInt(String key) {
        if (!has(key)) throw new ToolException("Missing required argument '" + key + "'");
        return integer(key, 0);
    }

    public double dbl(String key, double def) {
        if (!has(key)) return def;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            throw new ToolException("Argument '" + key + "' must be a number");
        }
    }

    public boolean bool(String key, boolean def) {
        if (!has(key)) return def;
        JsonElement e = obj.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return Boolean.parseBoolean(e.getAsString());
        return e.getAsBoolean();
    }

    public List<String> strList(String key) {
        List<String> out = new ArrayList<>();
        if (!has(key)) return out;
        JsonElement e = obj.get(key);
        if (e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) out.add(x.getAsString());
        } else {
            for (String s : e.getAsString().split(",")) if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** Parses a resource location, defaulting the namespace to minecraft. Leading '#' is stripped. */
    public ResourceLocation id(String key) {
        String s = str(key, null);
        return s == null ? null : parseId(s);
    }

    public ResourceLocation reqId(String key) {
        return parseId(reqStr(key));
    }

    public static ResourceLocation parseId(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        ResourceLocation rl = ResourceLocation.tryParse(s);
        if (rl == null) throw new ToolException("Invalid resource location: '" + s + "'");
        return rl;
    }
}
