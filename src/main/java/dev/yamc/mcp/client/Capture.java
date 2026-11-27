package dev.yamc.mcp.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.util.Mc;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Captures a frame rendered with temporary view settings: a camera somewhere else or facing another way ("freecam"),
 * a different FOV, or the HUD hidden. Nothing about the player changes and nothing is sent to the server. A moved camera
 * is an invisible entity that is never added to the world; a camera that stays at the player's eyes keeps the player as
 * the camera entity (so their own model is not drawn around it) and only its angles are overridden, through the loader
 * (NeoForge's ComputeCameraAngles event, a Camera mixin on Fabric). Every setting is restored when the job ends.
 */
public final class Capture {
    /**
     * @param camera where the camera is and where it looks, or null to keep the player's view
     * @param fov field of view in degrees, or null to keep the player's
     */
    public record View(@Nullable Pose camera, @Nullable Integer fov, boolean hideHud) {}

    /** @param atEyes the camera stays at the player's eyes and only turns */
    public record Pose(Vec3 pos, float yaw, float pitch, boolean atEyes) {}

    /** @param complete false if the world was still being built (chunk sections compiling) when the wait ran out */
    public record Shot(NativeImage image, boolean complete) {}

    /**
     * Ticks before capturing after a camera move: the camera eases its eye height toward the new entity's (it halves
     * the gap each tick), so 8 ticks leaves under 0.01 blocks of the player's eye height.
     */
    private static final int MOVED_TICKS = 8;
    /** Ticks before capturing after only turning the camera: a frame or two to queue the newly visible chunk sections. */
    private static final int TURNED_TICKS = 3;
    /** Ticks before capturing after only a HUD/FOV change: enough for a frame drawn with it. */
    private static final int SETTINGS_TICKS = 2;

    /** Only touched on the client thread. */
    private static boolean busy;
    private static @Nullable Pose angles;

    /** Camera angles the loader should apply this frame instead of the player's, or null. Client thread only. */
    public static @Nullable Pose angleOverride() {
        return angles;
    }

    private Capture() {}

    /** Call on the client thread (as a {@link Ticker} setup). Applies the view now; the job captures once it has settled. */
    public static Ticker.Job<Shot> start(Minecraft mc, View view, long maxWaitMs) {
        if (busy) throw new ToolException("Another screenshot is being taken; try again in a moment.");
        Saved saved = new Saved(mc.getCameraEntity(), mc.options.getCameraType(), Mc.hudHidden(mc), mc.options.fov().get());
        busy = true;
        Pose c = view.camera();
        if (c != null && c.atEyes()) {
            angles = c;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        } else if (c != null) {
            //? if >=26.2 {
            /*Marker eye = new Marker(net.minecraft.world.entity.EntityTypes.MARKER, mc.level);
            *///?} else
            Marker eye = new Marker(EntityType.MARKER, mc.level);
            eye.setPos(c.pos().x, c.pos().y, c.pos().z);
            eye.setYRot(c.yaw());
            eye.setXRot(c.pitch());
            eye.setOldPosAndRot();
            mc.setCameraEntity(eye);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        if (view.hideHud()) Mc.setHudHidden(mc, true);
        if (view.fov() != null) mc.options.fov().set(view.fov());
        long deadline = System.currentTimeMillis() + maxWaitMs;
        int minTicks = c == null ? SETTINGS_TICKS : c.atEyes() ? TURNED_TICKS : MOVED_TICKS;

        return new Ticker.Job<>() {
            final CompletableFuture<NativeImage> image = new CompletableFuture<>();
            int ticks, builtTicks;
            boolean requested, complete;

            @Override
            public Shot step() {
                if (image.isDone()) return new Shot(image.join(), complete);
                if (requested) return null; // newer versions deliver the image after a GPU readback
                ticks++;
                // Chunk sections the player was not looking at are compiled once the camera sees them; wait until the
                // queue stays empty for two ticks (a fresh view queues its sections over a frame or two).
                builtTicks = view.camera() == null || mc.levelRenderer.hasRenderedAllSections() ? builtTicks + 1 : 0;
                complete = ticks >= minTicks && builtTicks >= 2;
                if (complete || System.currentTimeMillis() > deadline) {
                    requested = true;
                    Mc.screenshot(mc, image::complete);
                }
                return null;
            }

            @Override
            public void end(boolean completed) {
                saved.restore(mc, view);
                busy = false;
                if (!completed && image.isDone()) image.join().close(); // nobody will use it
            }
        };
    }

    private record Saved(@Nullable Entity camera, CameraType cameraType, boolean hudHidden, int fov) {
        void restore(Minecraft mc, View view) {
            if (view.camera() != null) {
                if (!view.camera().atEyes())
                    mc.setCameraEntity(camera == null || camera.isRemoved() || camera.level() != mc.level ? mc.player : camera);
                angles = null;
                mc.options.setCameraType(cameraType);
            }
            if (view.hideHud()) Mc.setHudHidden(mc, hudHidden);
            if (view.fov() != null) mc.options.fov().set(fov);
        }
    }
}
