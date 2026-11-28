package dev.yamc.mcp.neoforge;

//? if neoforge {
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.platform.Platform;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Any mod's NeoForge configs (ModConfigSpec), for mod_config: read every value, set one. */
final class NeoForgeModConfigs {
    private NeoForgeModConfigs() {}

    static List<Platform.ConfigSpec> read(String modId) {
        List<Platform.ConfigSpec> out = new ArrayList<>();
        for (ModConfig c : ModConfigs.getModConfigs(modId)) {
            String type = c.getType().name().toLowerCase(Locale.ROOT);
            if (!(c.getSpec() instanceof ModConfigSpec spec))
                out.add(new Platform.ConfigSpec(c.getFileName(), type, null,
                        "Not a ModConfigSpec (" + c.getSpec().getClass().getName() + "): values not readable."));
            else if (!spec.isLoaded())
                out.add(new Platform.ConfigSpec(c.getFileName(), type, null, "Not loaded yet (per-world configs load when a world is joined)."));
            else
                out.add(new Platform.ConfigSpec(c.getFileName(), type, values(spec.getValues()).stream().map(NeoForgeModConfigs::describe).toList(), null));
        }
        return out;
    }

    static Object set(String modId, String path, Object value) {
        for (ModConfig c : ModConfigs.getModConfigs(modId)) {
            if (!(c.getSpec() instanceof ModConfigSpec spec) || !spec.isLoaded()) continue;
            for (ModConfigSpec.ConfigValue<?> v : values(spec.getValues()))
                if (path(v).equals(path)) return setValue(v, value);
        }
        throw new ToolException("No loaded config value '" + path + "' for mod '" + modId + "'. List them with mod_config without path.");
    }

    @SuppressWarnings("unchecked")
    private static <T> Object setValue(ModConfigSpec.ConfigValue<T> v, Object value) {
        if (!v.getSpec().test(value)) {
            Object r = v.getSpec().getRange();
            throw new ToolException("Value " + value + " is not allowed for " + path(v) + (r == null ? "." : " (allowed: " + r + ")."));
        }
        v.set((T) value);
        v.save();
        return v.get();
    }

    private static List<ModConfigSpec.ConfigValue<?>> values(UnmodifiableConfig config) {
        List<ModConfigSpec.ConfigValue<?>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : config.valueMap().entrySet()) {
            if (e.getValue() instanceof UnmodifiableConfig sub) out.addAll(values(sub));
            else if (e.getValue() instanceof ModConfigSpec.ConfigValue<?> v) out.add(v);
        }
        return out;
    }

    private static Platform.ConfigValue describe(ModConfigSpec.ConfigValue<?> v) {
        var spec = v.getSpec();
        Object range = spec.getRange();
        String comment = spec.getComment();
        var restart = spec.restartType();
        return new Platform.ConfigValue(path(v), v.get(), v.getDefault(), range == null ? null : range.toString(),
                comment == null || comment.isBlank() ? null : comment.trim(),
                restart == ModConfigSpec.RestartType.NONE ? null : restart.name().toLowerCase(Locale.ROOT));
    }

    private static String path(ModConfigSpec.ConfigValue<?> v) {
        return String.join(".", v.getPath());
    }
}
//?}
