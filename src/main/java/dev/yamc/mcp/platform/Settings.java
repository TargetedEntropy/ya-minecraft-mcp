package dev.yamc.mcp.platform;

import java.util.List;

/** The mod's user settings, read live (each loader stores them in its own config system). */
public interface Settings {
    /** TCP port of the MCP HTTP endpoint. */
    int port();

    String bindAddress();

    /** Require 'Authorization: Bearer <token>' (token in config/yamcmcp-token.txt). */
    boolean requireToken();

    /** Max time a tool may wait for the client thread. */
    int clientThreadTimeoutMs();

    /** Allow tools that change game state. */
    boolean enableActions();

    /** In singleplayer/LAN-host, read server-only data from the integrated server. */
    boolean enableIntegratedServerTools();

    boolean enableLocate();

    /** Number of recent game events kept. */
    int eventBufferSize();

    /** Push game events to connected MCP clients. */
    boolean pushEnabled();

    /** Event type ids to push; empty means the defaults. */
    List<String> pushEventTypes();
}
