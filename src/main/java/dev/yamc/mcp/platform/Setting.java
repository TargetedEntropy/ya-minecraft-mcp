package dev.yamc.mcp.platform;

import dev.yamc.mcp.YamcMcp;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * One user setting: its name, default, allowed values and description, declared once in {@link Settings}. Each loader
 * stores the values in its own config system ({@link Settings.Source}); both build their config from these declarations.
 */
public sealed interface Setting<T> permits Setting.Int, Setting.Bool, Setting.Str, Setting.StrList {
    /** Config section: server, tools or push. */
    String section();

    String key();

    T def();

    /** Description shown in the config file; may span lines. */
    String comment();

    /** The current value. */
    default T get() {
        return YamcMcp.platform().settings().get(this);
    }

    /** The value written as text (in Fabric's properties file), or the default if it is missing or invalid. */
    T parse(@Nullable String text);

    /** The default as text, for a new properties file. */
    default String defText() {
        return String.valueOf(def());
    }

    /** Key in Fabric's properties file: push.* keep their section, the rest drop it (the format released in 0.2.0). */
    default String propertiesKey() {
        return section().equals("push") ? section() + "." + key() : key();
    }

    record Int(String section, String key, Integer def, int min, int max, String comment) implements Setting<Integer> {
        @Override
        public Integer parse(@Nullable String text) {
            try {
                int v = Integer.parseInt(text == null ? "" : text.trim());
                return v < min || v > max ? def : v;
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    record Bool(String section, String key, Boolean def, String comment) implements Setting<Boolean> {
        @Override
        public Boolean parse(@Nullable String text) {
            return text == null || text.isBlank() ? def : Boolean.parseBoolean(text.trim());
        }
    }

    record Str(String section, String key, String def, String comment) implements Setting<String> {
        @Override
        public String parse(@Nullable String text) {
            return text == null || text.isBlank() ? def : text.trim();
        }
    }

    /** A list of strings, empty by default; comma-separated in the properties file. */
    record StrList(String section, String key, String comment) implements Setting<List<String>> {
        @Override
        public List<String> def() {
            return List.of();
        }

        @Override
        public List<String> parse(@Nullable String text) {
            return text == null ? def() : Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        @Override
        public String defText() {
            return "";
        }
    }
}
