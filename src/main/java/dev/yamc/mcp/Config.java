package dev.yamc.mcp;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue PORT;
    public static final ModConfigSpec.ConfigValue<String> BIND_ADDRESS;
    public static final ModConfigSpec.BooleanValue REQUIRE_TOKEN;
    public static final ModConfigSpec.BooleanValue ENABLE_ACTIONS;
    public static final ModConfigSpec.BooleanValue ENABLE_INTEGRATED_SERVER_TOOLS;
    public static final ModConfigSpec.BooleanValue ENABLE_LOCATE;
    public static final ModConfigSpec.IntValue CLIENT_THREAD_TIMEOUT_MS;
    public static final ModConfigSpec.IntValue EVENT_BUFFER_SIZE;

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
        SPEC = b.build();
    }

    private Config() {}
}
