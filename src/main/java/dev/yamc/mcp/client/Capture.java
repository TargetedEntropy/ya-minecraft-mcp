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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Captures a game frame, optionally rendered with temporary {@link Change}s to the view (camera, FOV, HUD). Each change
 * applies itself and returns its undo; the undos run in reverse when the job ends, however it ends. Nothing about the
 * player changes and nothing is sent to the server.
 */
public final class Capture {
    /** A temporary change to the view. */
    public sealed interface Change permits HideHud, Fov, Turn, Move {
        /** Applies the change on the client thread and returns how to undo it. */
        Runnable apply(Minecraft mc);

        /** Ticks after applying before a frame shows the change. */
        int settleTicks();

        /** Whether the view shows parts of the world the player was not looking at, whose chunk sections must be built. */
        default boolean revealsWorld() {
            return false;
        }
    }

    /** Hide the HUD and hand, as with F1. */
    public record HideHud() implements Change {
        @Override
        public Runnable apply(Minecraft mc) {
            boolean was = Mc.hudHidden(mc);
            Mc.setHudHidden(mc, true);
            return () -> Mc.setHudHidden(mc, was);
        }

        @Override
        public int settleTicks() {
            return 2;
        }
    }

    public record Fov(int degrees) implements Change {
        @Override
        public Runnable apply(Minecraft mc) {
            int was = mc.options.fov().get();
            mc.options.fov().set(degrees);
            return () -> mc.options.fov().set(was);
        }

        @Override
        public int settleTicks() {
            return 2;
        }
    }

    /**
     * Turn the camera at the player's eyes. The player stays the camera entity (so their own model is not drawn around
     * the camera) and only the angles are overridden, through the loader: NeoForge's ComputeCameraAngles event, a
     * Camera mixin on Fabric, both reading {@link #turning()}.
     */
    public record Turn(float yaw, float pitch) implements Change {
        @Override
        public Runnable apply(Minecraft mc) {
            Runnable firstPerson = firstPerson(mc);
            turning = this;
            return () -> {
                turning = null;
                firstPerson.run();
            };
        }

        /** A frame or two for the newly visible chunk sections to be queued. */
        @Override
        public int settleTicks() {
            return 3;
        }

        @Override
        public boolean revealsWorld() {
            return true;
        }
    }

    /** Move the camera: an invisible entity, never added to the world, becomes the camera entity. */
    public record Move(Vec3 pos, float yaw, float pitch) implements Change {
        @Override
        public Runnable apply(Minecraft mc) {
            //? if >=26.2 {
            /*Marker eye = new Marker(net.minecraft.world.entity.EntityTypes.MARKER, mc.level);
            *///?} else
            Marker eye = new Marker(EntityType.MARKER, mc.level);
            eye.setPos(pos.x, pos.y, pos.z);
            eye.setYRot(yaw);
            eye.setXRot(pitch);
            eye.setOldPosAndRot();
            Entity was = mc.getCameraEntity();
            Runnable firstPerson = firstPerson(mc);
            mc.setCameraEntity(eye);
            return () -> {
                mc.setCameraEntity(was == null || was.isRemoved() || was.level() != mc.level ? mc.player : was);
                firstPerson.run();
            };
        }

        /**
         * The camera eases its eye height toward the new entity's, halving the gap each tick: after 8 ticks less than
         * 0.01 blocks of the player's eye height is left.
         */
        @Override
        public int settleTicks() {
            return 8;
        }

        @Override
        public boolean revealsWorld() {
            return true;
        }
    }

    /** @param complete false if the world was still being built (chunk sections compiling) when the wait ran out */
    public record Shot(NativeImage image, boolean complete) {}

    /** Only touched on the client thread. */
    private static boolean busy;
    private static @Nullable Turn turning;
    /** World frames rendered so far (counted from the world render hook). */
    private static long frames;

    private Capture() {}

    /** Called once per rendered world frame. */
    static void frameRendered() {
        frames++;
    }

    /** The camera turn the loader should apply this frame, or null. Client thread only. */
    public static @Nullable Turn turning() {
        return turning;
    }

    /** Third-person camera off for the capture: a moved or turned camera is the view itself. */
    private static Runnable firstPerson(Minecraft mc) {
        CameraType was = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        return () -> mc.options.setCameraType(was);
    }

    /**
     * Call on the client thread (as a {@link Ticker} setup). Applies the changes now (all or none) and returns the job
     * that captures once they have settled, or after {@code maxWaitMs} with {@link Shot#complete()} false.
     */
    public static Ticker.Job<Shot> start(Minecraft mc, List<Change> changes, long maxWaitMs) {
        if (busy) throw new ToolException("Another screenshot is being taken; try again in a moment.");
        List<Runnable> undo = new ArrayList<>();
        try {
            for (Change c : changes) undo.add(c.apply(mc));
        } catch (RuntimeException e) {
            undo.reversed().forEach(Runnable::run);
            throw e;
        }
        busy = true;
        int settleTicks = changes.stream().mapToInt(Change::settleTicks).max().orElse(0);
        boolean revealsWorld = changes.stream().anyMatch(Change::revealsWorld);
        long deadline = System.currentTimeMillis() + maxWaitMs;
        // At a low frame rate several ticks pass per frame: also wait for frames drawn after the changes (in a world,
        // where frames are counted), or the capture could read a frame from before them.
        long frame0 = frames;
        boolean countFrames = !changes.isEmpty() && mc.level != null;

        return new Ticker.Job<>() {
            final CompletableFuture<NativeImage> image = new CompletableFuture<>();
            int ticks, builtTicks;
            boolean requested, complete;

            @Override
            public Shot step() {
                if (image.isDone()) return new Shot(image.join(), complete);
                if (requested) return null; // newer versions deliver the image after a GPU readback
                // Newly visible chunk sections are queued over a frame or two: wait until the queue stays empty for two ticks.
                builtTicks = !revealsWorld || mc.levelRenderer.hasRenderedAllSections() ? builtTicks + 1 : 0;
                complete = ++ticks >= settleTicks && (!revealsWorld || builtTicks >= 2) && (!countFrames || frames - frame0 >= 2);
                if (complete || System.currentTimeMillis() > deadline) {
                    requested = true;
                    Mc.screenshot(mc, image::complete);
                }
                return null;
            }

            @Override
            public void end(boolean completed) {
                undo.reversed().forEach(Runnable::run);
                busy = false;
                if (!completed) image.thenAccept(NativeImage::close); // nobody will use it, even if it arrives later
            }
        };
    }
}
