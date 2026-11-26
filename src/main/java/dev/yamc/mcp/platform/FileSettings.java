package dev.yamc.mcp.platform;

import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.client.GameEvent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Settings from a plain properties file, for loaders without a config system (Fabric). Missing or
 * invalid values fall back to the defaults; the file is written with every key and its description
 * on first start. Read once at startup: changes need a game restart.
 */
public final class FileSettings implements Settings {
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
                w.write(TEMPLATE);
            } catch (IOException e) {
                YamcMcp.LOGGER.warn("Could not write {}", file, e);
            }
        }
        return s;
    }

    private static final String TEMPLATE = """
            # YAMC MCP settings. Restart the game after editing.

            # TCP port of the MCP HTTP endpoint (http://<bindAddress>:<port>/mcp), 1024-65535.
            port=25599
            # Address to bind. Keep 127.0.0.1 unless you know what you are doing.
            bindAddress=127.0.0.1
            # Require 'Authorization: Bearer <token>' (token stored in config/yamcmcp-token.txt).
            requireToken=true
            # Max time a tool may wait for the client thread, 1000-120000 ms.
            clientThreadTimeoutMs=15000

            # Allow tools that change game state (chat, commands, looking, clicking, moving, markers).
            enableActions=true
            # In singleplayer/LAN-host, read server-only data (structures, loot tables, worldgen, advancements) from the integrated server.
            enableIntegratedServerTools=true
            # Allow locate_structure / locate_biome (singleplayer only).
            enableLocate=true
            # Number of recent game events kept for events_since / wait_for_event, 50-20000.
            eventBufferSize=1000

            # Push game events to connected MCP clients as notifications (no polling needed).
            push.enabled=true
            # Comma-separated event types to push. Empty = defaults (%s).
            # All types: %s
            push.eventTypes=
            """.formatted(String.join(", ", GameEvent.defaultPushIds()), String.join(", ", GameEvent.ids()));

    private int integer(String key, int def, int min, int max) {
        try {
            int v = Integer.parseInt(p.getProperty(key, "").trim());
            return v < min || v > max ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean bool(String key, boolean def) {
        String v = p.getProperty(key, "").trim();
        return v.isEmpty() ? def : Boolean.parseBoolean(v);
    }

    @Override public int port() { return integer("port", 25599, 1024, 65535); }
    @Override public String bindAddress() { return p.getProperty("bindAddress", "127.0.0.1").trim(); }
    @Override public boolean requireToken() { return bool("requireToken", true); }
    @Override public int clientThreadTimeoutMs() { return integer("clientThreadTimeoutMs", 15000, 1000, 120000); }
    @Override public boolean enableActions() { return bool("enableActions", true); }
    @Override public boolean enableIntegratedServerTools() { return bool("enableIntegratedServerTools", true); }
    @Override public boolean enableLocate() { return bool("enableLocate", true); }
    @Override public int eventBufferSize() { return integer("eventBufferSize", 1000, 50, 20000); }
    @Override public boolean pushEnabled() { return bool("push.enabled", true); }

    @Override
    public List<String> pushEventTypes() {
        return Arrays.stream(p.getProperty("push.eventTypes", "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
