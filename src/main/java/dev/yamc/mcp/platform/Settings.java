package dev.yamc.mcp.platform;

import dev.yamc.mcp.client.GameEvent;

import java.util.List;

/** Every user setting, declared once. Read one with e.g. {@code Settings.PORT.get()}. */
public final class Settings {
    private Settings() {}

    /** Where a loader keeps the values. */
    public interface Source {
        <T> T get(Setting<T> setting);
    }

    public static final Setting.Int PORT = new Setting.Int("server", "port", 25599, 1024, 65535,
            "TCP port of the MCP HTTP endpoint (http://<bindAddress>:<port>/mcp). Requires restart.");
    public static final Setting.Str BIND_ADDRESS = new Setting.Str("server", "bindAddress", "127.0.0.1",
            "Address to bind. Keep 127.0.0.1 unless you know what you are doing. Requires restart.");
    public static final Setting.Bool REQUIRE_TOKEN = new Setting.Bool("server", "requireToken", true,
            "Require 'Authorization: Bearer <token>' (token stored in config/yamcmcp-token.txt).");
    public static final Setting.Int CLIENT_THREAD_TIMEOUT_MS = new Setting.Int("server", "clientThreadTimeoutMs", 15000, 1000, 120000,
            "Max time a tool may wait for the client thread, in milliseconds.");

    public static final Setting.Bool ENABLE_ACTIONS = new Setting.Bool("tools", "enableActions", true,
            "Allow tools that change game state (chat, commands, looking, clicking, moving, markers).");
    public static final Setting.Bool ENABLE_INTEGRATED_SERVER_TOOLS = new Setting.Bool("tools", "enableIntegratedServerTools", true,
            "In singleplayer/LAN-host, read server-only data (structures, loot tables, worldgen, advancements) from the integrated server.");
    public static final Setting.Bool ENABLE_LOCATE = new Setting.Bool("tools", "enableLocate", true,
            "Allow locate_structure / locate_biome (singleplayer only).");
    public static final Setting.Int FREECAM_MAX_DISTANCE = new Setting.Int("tools", "freecamMaxDistance", 32, 0, 256,
            "Farthest the screenshot freecam may be from the player, in blocks; 0 disables freecam.");
    public static final Setting.Bool FREECAM_ON_MULTIPLAYER = new Setting.Bool("tools", "freecamOnMultiplayer", false,
            "Allow the screenshot freecam on multiplayer servers (always allowed in singleplayer/LAN host). Many servers treat freecam as cheating.");
    public static final Setting.Int EVENT_BUFFER_SIZE = new Setting.Int("tools", "eventBufferSize", 1000, 50, 20000,
            "Number of recent game events kept for events_since / wait_for_event.");
    public static final Setting.Int LOG_BUFFER_SIZE = new Setting.Int("tools", "logBufferSize", 5000, 100, 100000,
            "Number of recent log lines (INFO and above) kept for the logs tool.");

    public static final Setting.Bool PUSH_ENABLED = new Setting.Bool("push", "enabled", true,
            "Push game events to connected MCP clients as notifications (no polling needed).");
    public static final Setting.StrList PUSH_EVENT_TYPES = new Setting.StrList("push", "eventTypes",
            "Event types to push. Empty = defaults (" + String.join(", ", GameEvent.defaultPushIds()) + ").\n"
                    + "All types: " + String.join(", ", GameEvent.ids()));

    /** In config-file order, grouped by section. */
    public static final List<Setting<?>> ALL = List.of(PORT, BIND_ADDRESS, REQUIRE_TOKEN, CLIENT_THREAD_TIMEOUT_MS,
            ENABLE_ACTIONS, ENABLE_INTEGRATED_SERVER_TOOLS, ENABLE_LOCATE, FREECAM_MAX_DISTANCE, FREECAM_ON_MULTIPLAYER,
            EVENT_BUFFER_SIZE, LOG_BUFFER_SIZE, PUSH_ENABLED, PUSH_EVENT_TYPES);
}
