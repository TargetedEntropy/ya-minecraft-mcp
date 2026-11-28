package dev.yamc.mcp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.yamc.mcp.YamcMcp;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The client events the mod reacts to, as plain calls. Each loader subscribes to its own event
 * API and forwards here; nothing below this class knows which loader is running.
 */
public final class ClientEvents {
    private ClientEvents() {}

    private static final GameWatcher WATCHER = new GameWatcher();
    private static final DevAutoWorld DEV_AUTO_WORLD = DevAutoWorld.enabled() ? new DevAutoWorld() : null;

    /** The game finished loading (client thread). */
    public static void setup() {
        YamcMcp.startServer();
    }

    public static void shutdown() {
        YamcMcp.stopServer();
    }

    /** End of every client tick. */
    public static void tick() {
        WATCHER.tick();
        Markers.tick();
    }

    /**
     * A chat line arrived. {@code system} is a server/game message (no player sender);
     * {@code overlay} is an action-bar message.
     */
    public static void chat(Component message, @Nullable UUID sender, boolean system, boolean overlay) {
        WATCHER.chat(message, sender, system, overlay);
    }

    public static void screenOpening(Screen screen) {
        WATCHER.screenOpening(screen);
        if (DEV_AUTO_WORLD != null) DEV_AUTO_WORLD.screenOpening(screen);
    }

    public static void screenClosing(Screen screen) {
        WATCHER.screenClosing(screen);
    }

    public static void loggingIn() {
        WATCHER.loggingIn();
        var c = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (c != null) dev.yamc.mcp.debug.PacketLog.connected(c.getConnection());
    }

    public static void loggingOut() {
        WATCHER.loggingOut();
    }

    /** World rendering after block entities, in camera-relative world space. */
    public static void renderLevel(@Nullable PoseStack pose, Camera camera) {
        Capture.frameRendered();
        Markers.render(pose, camera);
    }
}
