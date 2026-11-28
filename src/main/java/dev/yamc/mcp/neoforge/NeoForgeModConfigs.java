package dev.yamc.mcp.neoforge;

//? if neoforge {
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.yamc.mcp.mcp.ToolException;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Any mod's NeoForge configs (ModConfigSpec), for mod_config: read every value, set one. */
final class NeoForgeModConfigs {
    private NeoForgeModConfigs() {}

    static JsonArray read(String modId) {
        JsonArray out = new JsonArray();
        for (ModConfig c : ModConfigs.getModConfigs(modId)) {
            JsonObject o = new JsonObject();
            o.addProperty("file", c.getFileName());
            o.addProperty("type", c.getType().name().toLowerCase(java.util.Locale.ROOT));
            if (!(c.getSpec() instanceof ModConfigSpec spec)) {
                o.addProperty("note", "Not a ModConfigSpec (" + c.getSpec().getClass().getName() + "): values not readable.");
            } else if (!spec.isLoaded()) {
                o.addProperty("loaded", false);
                o.addProperty("note", "Not loaded yet (per-world configs load when a world is joined).");
            } else {
                JsonArray values = new JsonArray();
                for (ModConfigSpec.ConfigValue<?> v : values(spec.getValues())) values.add(describe(v));
                o.add("values", values);
            }
            out.add(o);
        }
        return out;
    }

    static JsonElement set(String modId, String path, JsonElement value) {
        for (ModConfig c : ModConfigs.getModConfigs(modId)) {
            if (!(c.getSpec() instanceof ModConfigSpec spec) || !spec.isLoaded()) continue;
            for (ModConfigSpec.ConfigValue<?> v : values(spec.getValues())) {
                if (String.join(".", v.getPath()).equals(path)) return setValue(v, value);
            }
        }
        throw new ToolException("No loaded config value '" + path + "' for mod '" + modId + "'. List them with mod_config without path.");
    }

    @SuppressWarnings("unchecked")
    private static <T> JsonElement setValue(ModConfigSpec.ConfigValue<T> v, JsonElement json) {
        Object parsed = parse(json, v.getDefault());
        if (!v.getSpec().test(parsed))
            throw new ToolException("Value " + json + " is not allowed for " + String.join(".", v.getPath()) + range(v));
        v.set((T) parsed);
        v.save();
        return toJson(v.get());
    }

    private static String range(ModConfigSpec.ConfigValue<?> v) {
        Object r = v.getSpec().getRange();
        return r == null ? "." : " (allowed: " + r + ").";
    }

    /** The JSON value converted to the type of the setting's default. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parse(JsonElement json, Object def) {
        try {
            if (def instanceof Boolean) return json.getAsBoolean();
            if (def instanceof Integer) return json.getAsInt();
            if (def instanceof Long) return json.getAsLong();
            if (def instanceof Double) return json.getAsDouble();
            if (def instanceof Float) return json.getAsFloat();
            if (def instanceof Enum<?> e) return Enum.valueOf((Class) e.getDeclaringClass(), json.getAsString().toUpperCase(java.util.Locale.ROOT));
            if (def instanceof List<?>) {
                List<Object> out = new ArrayList<>();
                for (JsonElement x : json.getAsJsonArray()) out.add(x.isJsonPrimitive() && x.getAsJsonPrimitive().isString() ? x.getAsString() : primitive(x));
                return out;
            }
            return json.getAsString();
        } catch (RuntimeException e) {
            throw new ToolException("Cannot use " + json + " for a setting of type " + def.getClass().getSimpleName() + ".");
        }
    }

    private static Object primitive(JsonElement x) {
        JsonPrimitive p = x.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsNumber().doubleValue() == p.getAsNumber().longValue() ? (Object) p.getAsNumber().intValue() : p.getAsNumber().doubleValue();
        return p.getAsString();
    }

    private static List<ModConfigSpec.ConfigValue<?>> values(UnmodifiableConfig config) {
        List<ModConfigSpec.ConfigValue<?>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : config.valueMap().entrySet()) {
            if (e.getValue() instanceof UnmodifiableConfig sub) out.addAll(values(sub));
            else if (e.getValue() instanceof ModConfigSpec.ConfigValue<?> v) out.add(v);
        }
        return out;
    }

    private static JsonObject describe(ModConfigSpec.ConfigValue<?> v) {
        JsonObject o = new JsonObject();
        String path = String.join(".", v.getPath());
        o.addProperty("path", path);
        if (secret(path)) {
            o.addProperty("value", "<hidden: may be a credential>");
        } else {
            o.add("value", toJson(v.get()));
            o.add("default", toJson(v.getDefault()));
        }
        Object range = v.getSpec().getRange();
        if (range != null) o.addProperty("range", range.toString());
        String comment = v.getSpec().getComment();
        if (comment != null && !comment.isBlank()) o.addProperty("comment", comment.trim());
        var restart = v.getSpec().restartType();
        if (restart != ModConfigSpec.RestartType.NONE) o.addProperty("restart", restart.name().toLowerCase(java.util.Locale.ROOT));
        return o;
    }

    /** Tokens, passwords and API keys stay out of the AI's context. */
    private static boolean secret(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        return List.of("token", "secret", "password", "credential", "apikey", "api_key").stream().anyMatch(p::contains);
    }

    private static JsonElement toJson(@Nullable Object x) {
        if (x == null) return JsonNull.INSTANCE;
        if (x instanceof Boolean b) return new JsonPrimitive(b);
        if (x instanceof Number n) return new JsonPrimitive(n);
        if (x instanceof Iterable<?> it) {
            JsonArray a = new JsonArray();
            for (Object e : it) a.add(toJson(e));
            return a;
        }
        if (x.getClass().isArray()) return toJson(Arrays.asList((Object[]) x));
        return new JsonPrimitive(String.valueOf(x));
    }
}
//?}
