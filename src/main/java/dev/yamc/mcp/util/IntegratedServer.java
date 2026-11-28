package dev.yamc.mcp.util;

import dev.yamc.mcp.platform.Settings;
import dev.yamc.mcp.mcp.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The singleplayer / LAN-host server, when there is one and config allows reading from it, and running work on its
 * thread from a tool. {@link #call} / {@link #callIfPresent} / {@link #inLevel} may be used from any thread.
 */
public final class IntegratedServer {
    private IntegratedServer() {}

    /** Longest a tool waits for one task on the server thread. */
    public static final long TIMEOUT_MS = 10_000;

    /** Work for the server thread. */
    @FunctionalInterface
    public interface Task<T> {
        T run(MinecraftServer server) throws Exception;
    }

    @Nullable
    public static MinecraftServer get() {
        if (!Settings.ENABLE_INTEGRATED_SERVER_TOOLS.get()) return null;
        return Minecraft.getInstance().getSingleplayerServer();
    }

    /** The server, or a ToolException explaining why server-only data is unavailable. */
    public static MinecraftServer require() {
        MinecraftServer s = get();
        if (s != null) return s;
        if (!Settings.ENABLE_INTEGRATED_SERVER_TOOLS.get())
            throw new ToolException("Integrated-server data is disabled (config: tools.enableIntegratedServerTools=false).");
        throw new ToolException("This needs the integrated server: it only works in singleplayer or when hosting a LAN world"
                + " (server-only data like structures and loot tables is never sent to clients).");
    }

    /** Runs the task on the server thread and returns its result; a ToolException if there is no integrated server. */
    public static <T> T call(Task<T> task) throws Exception {
        return call(TIMEOUT_MS, task);
    }

    /** {@link #call} for work that may take longer than {@link #TIMEOUT_MS}. */
    public static <T> T call(long timeoutMs, Task<T> task) throws Exception {
        MinecraftServer s = ClientThread.call(IntegratedServer::require);
        return ClientThread.callServer(s, () -> task.run(s), timeoutMs);
    }

    /** Like {@link #call}, but empty without an integrated server. The task must not return null. */
    public static <T> Optional<T> callIfPresent(Task<T> task) throws Exception {
        MinecraftServer s = ClientThread.call(IntegratedServer::get);
        return s == null ? Optional.empty() : Optional.of(ClientThread.callServer(s, () -> task.run(s), TIMEOUT_MS));
    }

    /** Runs the task on the server thread with the server's copy of a dimension. */
    public static <T> T inLevel(ResourceKey<Level> dimension, LevelTask<T> task) throws Exception {
        return inLevel(dimension, TIMEOUT_MS, task);
    }

    public static <T> T inLevel(ResourceKey<Level> dimension, long timeoutMs, LevelTask<T> task) throws Exception {
        return call(timeoutMs, s -> task.run(level(s, dimension)));
    }

    /** The server's level for a dimension, or a ToolException. Server thread. */
    public static ServerLevel level(MinecraftServer s, ResourceKey<Level> dimension) {
        ServerLevel l = s.getLevel(dimension);
        if (l == null) throw new ToolException("The server has no level " + Mc.id(dimension) + ".");
        return l;
    }

    @FunctionalInterface
    public interface LevelTask<T> {
        T run(ServerLevel level) throws Exception;
    }
}
