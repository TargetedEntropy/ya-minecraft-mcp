package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.client.Capture;
import dev.yamc.mcp.client.Ticker;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.platform.Settings;
import dev.yamc.mcp.util.ClientThread;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** screenshot: the player's view as an image, optionally from a free camera (freecam). */
final class ScreenshotTools {
    private ScreenshotTools() {}

    private static final List<String> POSITION = List.of("x", "y", "z"), OFFSET = List.of("dx", "dy", "dz"),
            LOOK_AT = List.of("look_at_x", "look_at_y", "look_at_z");
    private static final List<String> FREECAM = List.of("x", "y", "z", "dx", "dy", "dz", "look_at_x", "look_at_y", "look_at_z",
            "look_at_entity", "yaw", "pitch");

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
                        .number("x", "Freecam position X (absolute).").number("y", "Freecam position Y (absolute).").number("z", "Freecam position Z (absolute).")
                        .number("dx", "Freecam offset X from the player's eyes.").number("dy", "Freecam offset Y from the player's eyes.")
                        .number("dz", "Freecam offset Z from the player's eyes.")
                        .number("look_at_x", "Point the camera at this X.").number("look_at_y", "Point the camera at this Y.")
                        .number("look_at_z", "Point the camera at this Z. Whole numbers aim at the block center.")
                        .integer("look_at_entity", "Point the camera at this entity (its center).")
                        .number("yaw", "Camera yaw (0=south, 90=west, 180=north, -90=east).").number("pitch", "Camera pitch (-90=up, 90=down).")
                        .integer("max_wait_ms", "Freecam: longest to wait for the view's chunks to finish building (default 3000, max 10000)."))
                .anyThread()
                .handler(ScreenshotTools::screenshot));
    }

    static ToolResult screenshot(Args a) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        int maxW = a.integer("max_width", 1280, 64, 3840);
        long maxWait = a.integer("max_wait_ms", 3000, 0, 10000);
        boolean freecam = FREECAM.stream().anyMatch(a::has);
        Integer fov = a.has("fov") ? a.integer("fov", 70, 30, 110) : null;
        boolean hideHud = freecam || a.bool("hide_hud", false);

        JsonObject meta = new JsonObject();
        NativeImage img;
        if (!freecam && fov == null && !hideHud) {
            CompletableFuture<NativeImage> shot = new CompletableFuture<>();
            ClientThread.call(() -> {
                meta.addProperty("screen", screenName(mc));
                Mc.screenshot(mc, shot::complete); // newer versions deliver the image after a GPU readback
                return null;
            });
            try {
                img = shot.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new ToolException("The game did not deliver the screenshot within 10s (is the window minimized?).");
            }
        } else {
            Capture.Shot shot = Ticker.run(() -> {
                meta.addProperty("screen", screenName(mc));
                Capture.Pose pose = null;
                if (freecam) {
                    pose = freecamPose(mc, a);
                    meta.add("camera", describe(mc, pose));
                }
                return Capture.start(mc, new Capture.View(pose, fov, hideHud), maxWait);
            }, maxWait + 15_000);
            img = shot.image();
            if (freecam) meta.addProperty("complete", shot.complete());
        }
        try (img) {
            return image(img, maxW, meta);
        }
    }

    /** Validates the freecam request against the config and the world, and works out where the camera goes and looks. */
    private static Capture.Pose freecamPose(Minecraft mc, Args a) {
        LocalPlayer player = Ctx.player();
        if (Mc.screen(mc) != null) throw new ToolException("Close the open screen first (close_screen): freecam shows the world only.");
        Settings s = YamcMcp.settings();
        int max = s.freecamMaxDistance();
        if (max == 0) throw new ToolException("Freecam is disabled (config: tools.freecamMaxDistance=0).");
        if (!mc.hasSingleplayerServer() && !s.freecamOnMultiplayer())
            throw new ToolException("Freecam is off on multiplayer servers (config: tools.freecamOnMultiplayer=false). Plain screenshots still work.");

        Vec3 eye = player.getEyePosition();
        boolean absolute = POSITION.stream().anyMatch(a::has), relative = OFFSET.stream().anyMatch(a::has);
        if (absolute && relative) throw new ToolException("Give either x/y/z or dx/dy/dz, not both.");
        Vec3 pos = absolute ? new Vec3(a.dbl("x", eye.x), a.dbl("y", eye.y), a.dbl("z", eye.z))
                : eye.add(a.dbl("dx", 0), a.dbl("dy", 0), a.dbl("dz", 0));
        double distance = pos.distanceTo(eye);
        if (distance > max) throw new ToolException(String.format(java.util.Locale.ROOT,
                "The camera would be %.1f blocks from the player; the limit is %d (config: tools.freecamMaxDistance).", distance, max));
        if (!mc.level.hasChunkAt(BlockPos.containing(pos))) throw new ToolException("That camera position is not in a loaded chunk.");

        Vec3 target = null;
        if (a.has("look_at_entity")) target = Ctx.entity(a.reqInt("look_at_entity")).getBoundingBox().getCenter();
        else if (LOOK_AT.stream().anyMatch(a::has)) {
            double x = a.dbl("look_at_x", pos.x), y = a.dbl("look_at_y", pos.y), z = a.dbl("look_at_z", pos.z);
            boolean whole = x == Math.floor(x) && y == Math.floor(y) && z == Math.floor(z);
            target = whole ? new Vec3(x + 0.5, y + 0.5, z + 0.5) : new Vec3(x, y, z);
        }
        float yaw = player.getYRot(), pitch = player.getXRot();
        if (target != null) {
            Vec3 d = target.subtract(pos);
            if (d.lengthSqr() > 1e-6) {
                yaw = (float) Math.toDegrees(Mth.atan2(d.z, d.x)) - 90f;
                pitch = (float) -Math.toDegrees(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            }
        } else {
            yaw = (float) a.dbl("yaw", yaw);
            pitch = (float) a.dbl("pitch", pitch);
        }
        // Within the player's head the camera would see its inside: stay at the eyes and only turn instead.
        boolean atEyes = distance < 0.75;
        return new Capture.Pose(atEyes ? eye : pos, Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f), atEyes);
    }

    private static JsonObject describe(Minecraft mc, Capture.Pose pose) {
        JsonObject o = new JsonObject();
        o.addProperty("x", Json.round(pose.pos().x));
        o.addProperty("y", Json.round(pose.pos().y));
        o.addProperty("z", Json.round(pose.pos().z));
        o.addProperty("yaw", Json.round(pose.yaw()));
        o.addProperty("pitch", Json.round(pose.pitch()));
        o.addProperty("distance_from_player", Json.round(pose.pos().distanceTo(Ctx.player().getEyePosition())));
        BlockPos bp = BlockPos.containing(pose.pos());
        o.addProperty("inside_block", !mc.level.getBlockState(bp).getCollisionShape(mc.level, bp).isEmpty());
        return o;
    }

    private static String screenName(Minecraft mc) {
        return Mc.screen(mc) == null ? "in-game" : Mc.screen(mc).getClass().getSimpleName();
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
