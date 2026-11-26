package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import dev.yamc.mcp.json.BlockJson;
import dev.yamc.mcp.json.EntityJson;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Paging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** World and session info, the crosshair target and the player list. */
final class WorldTools {
    private WorldTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("world_info").title("World info")
                .desc("World/session info: dimension, day/time, weather, moon phase, difficulty, world border, build limits, server address/brand, singleplayer flag, FPS, loaded chunks. With x/y/z (defaults: player position) also biome, light levels and surface heights at that position.")
                .schema(Schema.of().integer("x", "X (default player)").integer("y", "Y (default player)").integer("z", "Z (default player)"))
                .handler(a -> ToolResult.json(worldInfo(a))));
        r.add(Tool.builder("look_target").title("Crosshair target")
                .desc("What the player is looking at: the block (with state, face, block entity) and/or entity along the view ray, up to max_distance. Reports whether it is within interaction reach.")
                .schema(Schema.of().number("max_distance", "Ray length in blocks (default 32, max 256).")
                        .bool("include_fluids", "Stop the ray at fluids (default false).")
                        .bool("include_nbt", "Include client-known block entity NBT (default false)."))
                .handler(a -> ToolResult.json(lookTarget(a))));
        r.add(Tool.builder("players_online").title("Online players")
                .desc("Players in the tab list with ping and game mode.")
                .schema(Schema.of().paging(100))
                .handler(a -> {
                    var c = Ctx.mc().getConnection();
                    if (c == null) throw new ToolException("Not connected to a world.");
                    List<PlayerInfo> list = new ArrayList<>(c.getOnlinePlayers());
                    list.sort(Comparator.comparing(i -> i.getProfile().getName(), String.CASE_INSENSITIVE_ORDER));
                    return ToolResult.json(Paging.page(list, a, i -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", i.getProfile().getName());
                        o.addProperty("uuid", i.getProfile().getId().toString());
                        o.addProperty("ping_ms", i.getLatency());
                        if (i.getGameMode() != null) o.addProperty("gamemode", i.getGameMode().getName());
                        if (i.getTabListDisplayName() != null) o.addProperty("display_name", i.getTabListDisplayName().getString());
                        return o;
                    }, 100));
                }));
    }

    static JsonObject worldInfo(Args a) {
        Minecraft mc = Ctx.mc();
        ClientLevel l = Ctx.level();
        LocalPlayer p = Ctx.player();
        JsonObject o = new JsonObject();
        o.addProperty("dimension", l.dimension().location().toString());
        o.addProperty("dimension_type", l.dimensionTypeRegistration().getRegisteredName());
        long dayTime = l.getDayTime();
        o.addProperty("day", dayTime / 24000L);
        o.addProperty("time_of_day", dayTime % 24000L);
        o.addProperty("game_time", l.getGameTime());
        long t = (dayTime + 6000) % 24000;
        o.addProperty("clock", String.format("%02d:%02d", t / 1000, (t % 1000) * 60 / 1000));
        o.addProperty("is_day", l.isDay());
        o.addProperty("raining", l.isRaining());
        o.addProperty("thundering", l.isThundering());
        o.addProperty("moon_phase", l.getMoonPhase());
        o.addProperty("difficulty", l.getDifficulty().getKey());
        o.addProperty("hardcore", l.getLevelData().isHardcore());
        o.addProperty("min_build_height", l.getMinBuildHeight());
        o.addProperty("max_build_height", l.getMaxBuildHeight());
        o.addProperty("sea_level", l.getSeaLevel());
        var wb = l.getWorldBorder();
        JsonObject border = new JsonObject();
        border.addProperty("center_x", wb.getCenterX());
        border.addProperty("center_z", wb.getCenterZ());
        border.addProperty("size", wb.getSize());
        o.add("world_border", border);
        o.addProperty("singleplayer", mc.hasSingleplayerServer());
        ServerData sd = mc.getCurrentServer();
        if (sd != null) {
            o.addProperty("server_name", sd.name);
            o.addProperty("server_address", sd.ip);
        }
        if (mc.getConnection() != null) {
            o.addProperty("server_brand", mc.getConnection().serverBrand());
            o.addProperty("players_online", mc.getConnection().getOnlinePlayers().size());
        }
        o.addProperty("fps", mc.getFps());
        o.addProperty("loaded_chunks", l.getChunkSource().getLoadedChunksCount());
        o.addProperty("entity_count", l.getEntityCount());
        o.addProperty("paused", mc.isPaused());
        BlockPos pos = Ctx.posArg(a);
        JsonObject at = new JsonObject();
        at.add("pos", Json.pos(pos));
        at.addProperty("biome", l.getBiome(pos).getRegisteredName());
        at.addProperty("block_light", l.getBrightness(LightLayer.BLOCK, pos));
        at.addProperty("sky_light", l.getBrightness(LightLayer.SKY, pos));
        at.addProperty("surface_y", l.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()));
        at.addProperty("surface_no_leaves_y", l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()));
        at.addProperty("can_see_sky", l.canSeeSky(pos));
        o.add("at", at);
        o.addProperty("player_distance", Json.round(Math.sqrt(p.blockPosition().distSqr(pos))));
        return o;
    }

    static JsonObject lookTarget(Args a) {
        LocalPlayer p = Ctx.player();
        ClientLevel l = Ctx.level();
        double dist = Math.min(256, Math.max(1, a.dbl("max_distance", 32)));
        boolean fluids = a.bool("include_fluids", false);
        HitResult block = p.pick(dist, 1.0f, fluids);
        Vec3 eye = p.getEyePosition(1.0f);
        Vec3 view = p.getViewVector(1.0f);
        Vec3 end = eye.add(view.scale(dist));
        double blockDist = block.getType() == HitResult.Type.MISS ? dist : block.getLocation().distanceTo(eye);
        EntityHitResult ent = ProjectileUtil.getEntityHitResult(p, eye, eye.add(view.scale(blockDist)),
                p.getBoundingBox().expandTowards(view.scale(blockDist)).inflate(1.0), e -> !e.isSpectator() && e.isPickable(), blockDist * blockDist);
        JsonObject o = new JsonObject();
        o.add("eye", Json.vec(eye));
        o.addProperty("yaw", Json.round(net.minecraft.util.Mth.wrapDegrees(p.getYRot())));
        o.addProperty("pitch", Json.round(p.getXRot()));
        if (ent != null) {
            JsonObject e = EntityJson.summary(ent.getEntity(), p);
            e.addProperty("in_reach", ent.getLocation().distanceTo(eye) <= p.entityInteractionRange());
            o.add("entity", e);
        }
        if (block instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
            JsonObject b = BlockJson.at(l, bh.getBlockPos(), a.bool("include_nbt", false));
            b.addProperty("face", bh.getDirection().getName());
            b.add("hit", Json.vec(bh.getLocation()));
            double d = bh.getLocation().distanceTo(eye);
            b.addProperty("distance", Json.round(d));
            b.addProperty("in_reach", d <= p.blockInteractionRange());
            o.add("block", b);
        }
        if (!o.has("block") && !o.has("entity")) o.addProperty("target", "nothing within " + (int) dist + " blocks");
        return o;
    }
}
