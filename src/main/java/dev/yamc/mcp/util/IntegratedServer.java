package dev.yamc.mcp.util;

import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.mcp.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;

/** The singleplayer / LAN-host server, when there is one and config allows reading from it. */
public final class IntegratedServer {
    private IntegratedServer() {}

    @Nullable
    public static MinecraftServer get() {
        if (!YamcMcp.settings().enableIntegratedServerTools()) return null;
        return Minecraft.getInstance().getSingleplayerServer();
    }

    /** The server, or a ToolException explaining why server-only data is unavailable. */
    public static MinecraftServer require() {
        MinecraftServer s = get();
        if (s != null) return s;
        if (!YamcMcp.settings().enableIntegratedServerTools())
            throw new ToolException("Integrated-server data is disabled (config: tools.enableIntegratedServerTools=false).");
        throw new ToolException("This needs the integrated server: it only works in singleplayer or when hosting a LAN world"
                + " (server-only data like structures and loot tables is never sent to clients).");
    }
}
