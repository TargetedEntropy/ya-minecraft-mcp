package dev.yamc.mcp.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.platform.Platform;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * mod_config on any loader: the mod's configs registered with the loader's config system (see {@link Platform#modConfigs}),
 * and its own files in the config folder (mods that keep their config outside that system, and every Fabric mod).
 * Whatever may hold a credential is withheld, here only, for both.
 */
public final class ModConfigs {
    private ModConfigs() {}

    private static final long MAX_FILE_BYTES = 32_768;
    private static final List<String> CREDENTIAL_WORDS = List.of("token", "secret", "password", "credential", "apikey", "api_key");

    /** Whether a config path or file name looks like it holds a token, password or key. */
    static boolean mayHoldCredentials(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".key") || CREDENTIAL_WORDS.stream().anyMatch(n::contains);
    }

    public static JsonObject describe(String modId) {
        JsonObject o = new JsonObject();
        o.addProperty("mod_id", modId);
        o.add("configs", specs(YamcMcp.platform().modConfigs(modId)));
        JsonArray files = files(modId);
        o.add("files", files);
        if (o.getAsJsonArray("configs").isEmpty() && files.isEmpty())
            o.addProperty("note", "This mod has no registered configs and no files in the config folder.");
        return o;
    }

    /** Sets a registered value from its JSON form (a bare word is taken as a string); returns the value now in effect. */
    public static JsonObject set(String modId, String path, String valueText) {
        Platform.ConfigValue current = YamcMcp.platform().modConfigs(modId).stream()
                .filter(c -> c.values() != null).flatMap(c -> c.values().stream()).filter(v -> v.path().equals(path)).findFirst()
                .orElseThrow(() -> new ToolException("No loaded config value '" + path + "' for mod '" + modId + "'. List them with mod_config without path."));
        JsonElement json;
        try {
            json = JsonParser.parseString(valueText);
        } catch (RuntimeException e) {
            json = new JsonPrimitive(valueText); // a bare word: a string or enum name
        }
        Object now = YamcMcp.platform().setModConfig(modId, path, parse(json, current.def()));
        JsonObject o = new JsonObject();
        o.addProperty("mod_id", modId);
        o.addProperty("path", path);
        if (mayHoldCredentials(path)) o.addProperty("value", "<hidden: may be a credential>");
        else o.add("value", toJson(now));
        return o;
    }

    private static JsonArray specs(List<Platform.ConfigSpec> specs) {
        JsonArray out = new JsonArray();
        for (Platform.ConfigSpec c : specs) {
            JsonObject o = new JsonObject();
            o.addProperty("file", c.file());
            o.addProperty("type", c.type());
            if (c.note() != null) o.addProperty("note", c.note());
            if (c.values() != null) {
                JsonArray values = new JsonArray();
                for (Platform.ConfigValue v : c.values()) values.add(value(v));
                o.add("values", values);
            }
            out.add(o);
        }
        return out;
    }

    private static JsonObject value(Platform.ConfigValue v) {
        JsonObject o = new JsonObject();
        o.addProperty("path", v.path());
        if (mayHoldCredentials(v.path())) {
            o.addProperty("value", "<hidden: may be a credential>");
        } else {
            o.add("value", toJson(v.value()));
            o.add("default", toJson(v.def()));
        }
        if (v.range() != null) o.addProperty("range", v.range());
        if (v.comment() != null) o.addProperty("comment", v.comment());
        if (v.restart() != null) o.addProperty("restart", v.restart());
        return o;
    }

    /** The mod's files under config/ (up to one folder deep) whose path starts with the mod id. */
    private static JsonArray files(String modId) {
        Path dir = YamcMcp.platform().configDir();
        String id = modId.toLowerCase(Locale.ROOT);
        JsonArray out = new JsonArray();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                String name = rel.toLowerCase(Locale.ROOT);
                if (!name.startsWith(id)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("file", rel);
                // This mod's own token and connect files hold the MCP token.
                if (name.startsWith("yamcmcp-") || mayHoldCredentials(name)) o.addProperty("note", "Not shown: may hold credentials.");
                else {
                    long size = Files.size(f);
                    o.addProperty("size", size);
                    if (size <= MAX_FILE_BYTES) o.addProperty("text", Files.readString(f));
                    else o.addProperty("note", "Too large to show.");
                }
                out.add(o);
            }
        } catch (IOException e) {
            throw new ToolException("Cannot list the config folder: " + e.getMessage());
        }
        return out;
    }

    /** The JSON value converted to the type of the setting's default. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parse(JsonElement json, @Nullable Object def) {
        try {
            if (def instanceof Boolean) return json.getAsBoolean();
            if (def instanceof Integer) return json.getAsInt();
            if (def instanceof Long) return json.getAsLong();
            if (def instanceof Double) return json.getAsDouble();
            if (def instanceof Float) return json.getAsFloat();
            if (def instanceof Enum<?> e) return Enum.valueOf((Class) e.getDeclaringClass(), json.getAsString().toUpperCase(Locale.ROOT));
            if (def instanceof List<?>) {
                List<Object> out = new ArrayList<>();
                for (JsonElement x : json.getAsJsonArray()) out.add(primitive(x));
                return out;
            }
            return json.getAsString();
        } catch (RuntimeException e) {
            throw new ToolException("Cannot use " + json + " for a setting of type " + (def == null ? "unknown" : def.getClass().getSimpleName()) + ".");
        }
    }

    private static Object primitive(JsonElement x) {
        JsonPrimitive p = x.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsNumber().doubleValue() == p.getAsNumber().longValue() ? (Object) p.getAsNumber().intValue() : p.getAsNumber().doubleValue();
        return p.getAsString();
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
