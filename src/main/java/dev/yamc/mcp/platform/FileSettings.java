package dev.yamc.mcp.platform;

import dev.yamc.mcp.YamcMcp;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings from a plain properties file, for loaders without a config system (Fabric). Missing or invalid values fall
 * back to the defaults; the file is written with every setting and its description on first start. Read once at
 * startup: changes need a game restart.
 */
public final class FileSettings implements Settings.Source {
    private final Properties p = new Properties();

    private FileSettings() {}

    public static FileSettings load(Path file) {
        FileSettings s = new FileSettings();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                s.p.load(r);
            } catch (IOException e) {
                YamcMcp.LOGGER.warn("Could not read {}; using defaults", file, e);
            }
        } else {
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(template());
            } catch (IOException e) {
                YamcMcp.LOGGER.warn("Could not write {}", file, e);
            }
        }
        return s;
    }

    @Override
    public <T> T get(Setting<T> setting) {
        return setting.parse(p.getProperty(setting.propertiesKey()));
    }

    /** Every setting with its description and default, in sections. */
    private static String template() {
        StringBuilder out = new StringBuilder("# YAMC MCP settings. Restart the game after editing.\n");
        String section = null;
        for (Setting<?> s : Settings.ALL) {
            if (!s.section().equals(section)) out.append('\n');
            section = s.section();
            String comment = s.comment();
            if (s instanceof Setting.Int i) comment += " Range " + i.min() + "-" + i.max() + ".";
            if (s instanceof Setting.StrList) comment += "\nComma-separated.";
            comment.lines().forEach(line -> out.append("# ").append(line).append('\n'));
            out.append(s.propertiesKey()).append('=').append(s.defText()).append('\n');
        }
        return out.toString();
    }
}
