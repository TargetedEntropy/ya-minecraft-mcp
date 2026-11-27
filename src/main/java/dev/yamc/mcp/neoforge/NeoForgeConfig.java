package dev.yamc.mcp.neoforge;

//? if neoforge {
import dev.yamc.mcp.platform.Setting;
import dev.yamc.mcp.platform.Settings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The settings as a NeoForge client config (config/yamcmcp-client.toml), built from {@link Settings#ALL}. */
final class NeoForgeConfig implements Settings.Source {
    static final NeoForgeConfig INSTANCE = new NeoForgeConfig();

    final ModConfigSpec spec;
    /** Each setting's value; a setting's supplier always yields that setting's type (see {@link #define}). */
    private final Map<Setting<?>, Supplier<?>> values = new HashMap<>();

    private NeoForgeConfig() {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        String section = null;
        for (Setting<?> s : Settings.ALL) {
            if (!s.section().equals(section)) {
                if (section != null) b.pop();
                b.push(section = s.section());
            }
            define(b.comment(s.comment().split("\n")), s);
        }
        b.pop();
        spec = b.build();
    }

    private void define(ModConfigSpec.Builder b, Setting<?> setting) {
        values.put(setting, switch (setting) {
            case Setting.Int s -> b.defineInRange(s.key(), s.def().intValue(), s.min(), s.max());
            case Setting.Bool s -> b.define(s.key(), s.def().booleanValue());
            case Setting.Str s -> b.define(s.key(), s.def());
            case Setting.StrList s -> {
                ModConfigSpec.ConfigValue<List<? extends String>> list =
                        b.defineListAllowEmpty(s.key(), List.of(), () -> "", o -> o instanceof String);
                yield (Supplier<List<String>>) () -> List.copyOf(list.get());
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked") // define() stores a Supplier<T> for each Setting<T>
    public <T> T get(Setting<T> setting) {
        return (T) values.get(setting).get();
    }
}
//?}
