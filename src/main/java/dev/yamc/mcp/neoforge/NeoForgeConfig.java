package dev.yamc.mcp.neoforge;

//? if neoforge {
import dev.yamc.mcp.platform.Settings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** The settings as a NeoForge client config (config/yamcmcp-client.toml). */
final class NeoForgeConfig implements Settings {
    static final ModConfigSpec SPEC;

    static final ModConfigSpec.IntValue PORT;
    static final ModConfigSpec.ConfigValue<String> BIND_ADDRESS;
    static final ModConfigSpec.BooleanValue REQUIRE_TOKEN;
    static final ModConfigSpec.BooleanValue ENABLE_ACTIONS;
    static final ModConfigSpec.BooleanValue ENABLE_INTEGRATED_SERVER_TOOLS;
    static final ModConfigSpec.BooleanValue ENABLE_LOCATE;
    static final ModConfigSpec.IntValue CLIENT_THREAD_TIMEOUT_MS;
    static final ModConfigSpec.IntValue EVENT_BUFFER_SIZE;
    static final ModConfigSpec.BooleanValue PUSH_ENABLED;
    static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PUSH_EVENT_TYPES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("server");
        PORT = b.comment("TCP port of the MCP HTTP endpoint (http://<bind>:<port>/mcp). Requires restart.")
                .defineInRange("port", 25599, 1024, 65535);
        BIND_ADDRESS = b.comment("Address to bind. Keep 127.0.0.1 unless you know what you are doing. Requires restart.")
                .define("bindAddress", "127.0.0.1");
        REQUIRE_TOKEN = b.comment("Require 'Authorization: Bearer <token>' (token stored in config/yamcmcp-token.txt).")
                .define("requireToken", true);
        CLIENT_THREAD_TIMEOUT_MS = b.comment("Max time a tool may wait for the client thread.")
                .defineInRange("clientThreadTimeoutMs", 15000, 1000, 120000);
        b.pop();
        b.push("tools");
        ENABLE_ACTIONS = b.comment("Allow tools that change game state (chat, commands, looking, clicking, moving, markers).")
                .define("enableActions", true);
        ENABLE_INTEGRATED_SERVER_TOOLS = b.comment("In singleplayer/LAN-host, read server-only data (structures, loot tables, worldgen, advancements) from the integrated server.")
                .define("enableIntegratedServerTools", true);
        ENABLE_LOCATE = b.comment("Allow locate_structure / locate_biome (singleplayer only).")
                .define("enableLocate", true);
        EVENT_BUFFER_SIZE = b.comment("Number of recent game events kept for events_since / wait_for_event.")
                .defineInRange("eventBufferSize", 1000, 50, 20000);
        b.pop();
        b.push("push");
        PUSH_ENABLED = b.comment("Push game events to connected MCP clients as notifications (no polling needed).")
                .define("enabled", true);
        PUSH_EVENT_TYPES = b.comment("Event types to push. Empty = defaults (" + String.join(", ", dev.yamc.mcp.client.GameEvent.defaultPushIds()) + ").",
                        "All types: " + String.join(", ", dev.yamc.mcp.client.GameEvent.ids()))
                .defineListAllowEmpty("eventTypes", java.util.List.of(), () -> "", o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }

    static final NeoForgeConfig INSTANCE = new NeoForgeConfig();

    private NeoForgeConfig() {}

    @Override public int port() { return PORT.get(); }
    @Override public String bindAddress() { return BIND_ADDRESS.get(); }
    @Override public boolean requireToken() { return REQUIRE_TOKEN.get(); }
    @Override public int clientThreadTimeoutMs() { return CLIENT_THREAD_TIMEOUT_MS.get(); }
    @Override public boolean enableActions() { return ENABLE_ACTIONS.get(); }
    @Override public boolean enableIntegratedServerTools() { return ENABLE_INTEGRATED_SERVER_TOOLS.get(); }
    @Override public boolean enableLocate() { return ENABLE_LOCATE.get(); }
    @Override public int eventBufferSize() { return EVENT_BUFFER_SIZE.get(); }
    @Override public boolean pushEnabled() { return PUSH_ENABLED.get(); }
    @Override public List<String> pushEventTypes() { return List.copyOf(PUSH_EVENT_TYPES.get()); }
}
//?}
