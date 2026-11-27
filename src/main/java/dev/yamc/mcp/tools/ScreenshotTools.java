package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yamc.mcp.client.Capture;
import dev.yamc.mcp.client.Ticker;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.platform.Settings;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** screenshot: the player's view as an image, optionally from a free camera (freecam). */
final class ScreenshotTools {
    private ScreenshotTools() {}

    /** A camera within this distance of the player's eyes would see the inside of their head: it turns at the eyes instead. */
    private static final double AT_EYES = 0.75;

    static void register(ToolRegistry r) {
        r.add(Tool.builder("screenshot").title("Screenshot")
                .desc("Capture the game as a PNG image so you can see what the player sees (including open GUIs). "
                        + "Freecam: give a camera position (x/y/z, or dx/dy/dz from the player's eyes) and/or a direction (look_at_x/y/z, "
                        + "look_at_entity, or yaw/pitch) to see the world from there without moving the player, e.g. dy=20 pitch=90 for a "
                        + "top-down view, or a spot behind a wall. Without a direction the camera looks the way the player does. "
                        + "Limited to tools.freecamMaxDistance blocks from the player (default 32) and to loaded chunks; off on multiplayer "
                        + "servers unless tools.freecamOnMultiplayer is set. A camera inside solid blocks sees through the world. "
                        + "Freecam always hides the HUD and hand; hide_hud does that for a normal screenshot.")
                .schema(Schema.of().integer("max_width", "Downscale to at most this width (default 1280, max 3840).")
                        .bool("hide_hud", "Hide the HUD and hand, as with F1 (default false).")
                        .integer("fov", "Field of view in degrees, 30-110 (default: the player's setting).")
                        .number("x", "Freecam position X (with y and z).").number("y", "Freecam position Y (with x and z).")
                        .number("z", "Freecam position Z (with x and y).")
                        .number("dx", "Freecam offset X from the player's eyes.").number("dy", "Freecam offset Y from the player's eyes.")
                        .number("dz", "Freecam offset Z from the player's eyes.")
                        .number("look_at_x", "Point the camera at this X (with look_at_y and look_at_z).")
                        .number("look_at_y", "Point the camera at this Y.")
                        .number("look_at_z", "Point the camera at this Z. Whole numbers aim at the block center.")
                        .integer("look_at_entity", "Point the camera at this entity (its center).")
                        .number("yaw", "Camera yaw (0=south, 90=west, 180=north, -90=east).").number("pitch", "Camera pitch (-90=up, 90=down).")
                        .integer("max_wait_ms", "Freecam: longest to wait for the view's chunks to finish building (default 3000, max 10000)."))
                .anyThread()
                .handler(ScreenshotTools::screenshot));
    }

    /** Where the camera goes: an absolute position, an offset from the player's eyes, or (null) the eyes themselves. */
    private sealed interface Place permits Absolute, Offset {}

    private record Absolute(Vec3 pos) implements Place {}

    private record Offset(Vec3 delta) implements Place {}

    /** Where the camera looks: a point, an entity, or angles (a null angle keeps the player's); null aim keeps the player's view. */
    private sealed interface Aim permits Point, AtEntity, Angles {}

    private record Point(Vec3 pos) implements Aim {}

    private record AtEntity(int id) implements Aim {}

    private record Angles(@Nullable Float yaw, @Nullable Float pitch) implements Aim {}

    /** A screenshot call's arguments. A freecam request has a place, an aim, or both. */
    private record Request(int maxWidth, long maxWaitMs, @Nullable Integer fov, boolean hideHud, @Nullable Place place, @Nullable Aim aim) {
        boolean freecam() {
            return place != null || aim != null;
        }

        static Request parse(Args a) {
            Vec3 absolute = vec(a, "x", "y", "z"), point = vec(a, "look_at_x", "look_at_y", "look_at_z");
            boolean offset = Stream.of("dx", "dy", "dz").anyMatch(a::has);
            if (absolute != null && offset) throw new ToolException("Give either x/y/z or dx/dy/dz, not both.");
            Place place = absolute != null ? new Absolute(absolute)
                    : offset ? new Offset(new Vec3(a.dbl("dx", 0), a.dbl("dy", 0), a.dbl("dz", 0))) : null;

            List<Aim> aims = new ArrayList<>();
            if (point != null) aims.add(new Point(point));
            if (a.has("look_at_entity")) aims.add(new AtEntity(a.reqInt("look_at_entity")));
            if (a.has("yaw") || a.has("pitch"))
                aims.add(new Angles(a.has("yaw") ? (float) a.dbl("yaw", 0) : null, a.has("pitch") ? (float) a.dbl("pitch", 0) : null));
            if (aims.size() > 1) throw new ToolException("Give one direction: look_at_x/y/z, look_at_entity, or yaw/pitch.");

            return new Request(a.integer("max_width", 1280, 64, 3840), a.integer("max_wait_ms", 3000, 0, 10000),
                    a.has("fov") ? a.integer("fov", 70, 30, 110) : null, a.bool("hide_hud", false),
                    place, aims.isEmpty() ? null : aims.getFirst());
        }

        /** All three coordinates, none (null), or an error for some. */
        private static @Nullable Vec3 vec(Args a, String x, String y, String z) {
            long given = Stream.of(x, y, z).filter(a::has).count();
            if (given == 0) return null;
            if (given < 3) throw new ToolException("Give all of " + x + ", " + y + " and " + z + ".");
            return new Vec3(a.dbl(x, 0), a.dbl(y, 0), a.dbl(z, 0));
        }
    }

    static ToolResult screenshot(Args a) throws Exception {
        Request req = Request.parse(a);
        Minecraft mc = Minecraft.getInstance();
        JsonObject meta = new JsonObject();
        Capture.Shot shot = Ticker.run(() -> {
            meta.addProperty("screen", Mc.screen(mc) == null ? "in-game" : Mc.screen(mc).getClass().getSimpleName());
            List<Capture.Change> changes = new ArrayList<>();
            if (req.freecam()) changes.add(camera(mc, req, meta));
            if (req.freecam() || req.hideHud()) changes.add(new Capture.HideHud());
            if (req.fov() != null) changes.add(new Capture.Fov(req.fov()));
            return Capture.start(mc, changes, req.maxWaitMs());
        }, req.maxWaitMs() + 15_000);
        if (req.freecam()) meta.addProperty("complete", shot.complete());
        try (NativeImage img = shot.image()) {
            return image(img, req.maxWidth(), meta);
        }
    }

    /** Checks the freecam against the config and the world, works out the camera, and describes it in {@code meta}. */
    private static Capture.Change camera(Minecraft mc, Request req, JsonObject meta) {
        LocalPlayer player = Ctx.player();
        if (Mc.screen(mc) != null) throw new ToolException("Close the open screen first (close_screen): freecam shows the world only.");
        int max = Settings.FREECAM_MAX_DISTANCE.get();
        if (max == 0) throw new ToolException("Freecam is disabled (config: tools.freecamMaxDistance=0).");
        if (!mc.hasSingleplayerServer() && !Settings.FREECAM_ON_MULTIPLAYER.get())
            throw new ToolException("Freecam is off on multiplayer servers (config: tools.freecamOnMultiplayer=false). Plain screenshots still work.");

        Vec3 eye = player.getEyePosition();
        Vec3 pos = switch (req.place()) {
            case Absolute p -> p.pos();
            case Offset o -> eye.add(o.delta());
            case null -> eye;
        };
        double distance = pos.distanceTo(eye);
        if (distance > max) throw new ToolException(String.format(Locale.ROOT,
                "The camera would be %.1f blocks from the player; the limit is %d (config: tools.freecamMaxDistance).", distance, max));
        if (!mc.level.hasChunkAt(BlockPos.containing(pos))) throw new ToolException("That camera position is not in a loaded chunk.");

        float yaw = player.getYRot(), pitch = player.getXRot();
        Vec3 target = switch (req.aim()) {
            case Point p -> blockCenter(p.pos());
            case AtEntity e -> Ctx.entity(e.id()).getBoundingBox().getCenter();
            case Angles an -> {
                if (an.yaw() != null) yaw = an.yaw();
                if (an.pitch() != null) pitch = an.pitch();
                yield null;
            }
            case null -> null;
        };
        if (target != null && target.distanceToSqr(pos) > 1e-6) {
            Vec3 d = target.subtract(pos);
            yaw = (float) Math.toDegrees(Mth.atan2(d.z, d.x)) - 90f;
            pitch = (float) -Math.toDegrees(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        }
        yaw = Mth.wrapDegrees(yaw);
        pitch = Mth.clamp(pitch, -90f, 90f);

        boolean atEyes = distance < AT_EYES;
        if (atEyes) pos = eye;
        JsonObject cam = new JsonObject();
        cam.addProperty("x", Json.round(pos.x));
        cam.addProperty("y", Json.round(pos.y));
        cam.addProperty("z", Json.round(pos.z));
        cam.addProperty("yaw", Json.round(yaw));
        cam.addProperty("pitch", Json.round(pitch));
        cam.addProperty("distance_from_player", Json.round(pos.distanceTo(eye)));
        BlockPos bp = BlockPos.containing(pos);
        cam.addProperty("inside_block", !mc.level.getBlockState(bp).getCollisionShape(mc.level, bp).isEmpty());
        if (atEyes && distance > 0) cam.addProperty("note", "Within " + AT_EYES + " blocks of the eyes: the camera stays at the eyes and only turns.");
        meta.add("camera", cam);
        return atEyes ? new Capture.Turn(yaw, pitch) : new Capture.Move(pos, yaw, pitch);
    }

    /** Whole-number coordinates mean a block: aim at its center. */
    private static Vec3 blockCenter(Vec3 p) {
        boolean whole = p.x == Math.floor(p.x) && p.y == Math.floor(p.y) && p.z == Math.floor(p.z);
        return whole ? p.add(0.5, 0.5, 0.5) : p;
    }

    private static ToolResult image(NativeImage img, int maxW, JsonObject meta) throws Exception {
        NativeImage out = img;
        if (img.getWidth() > maxW) {
            out = new NativeImage(maxW, Math.max(1, img.getHeight() * maxW / img.getWidth()), false);
            img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), out);
        }
        try {
            byte[] png = Mc.png(out);
            meta.addProperty("width", out.getWidth());
            meta.addProperty("height", out.getHeight());
            return new ToolResult().image(Base64.getEncoder().encodeToString(png), "image/png").text(meta.toString());
        } finally {
            if (out != img) out.close();
        }
    }
}
