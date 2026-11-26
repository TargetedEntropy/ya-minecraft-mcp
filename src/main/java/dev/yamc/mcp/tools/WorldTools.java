package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Paging;
import dev.yamc.mcp.util.Stacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

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

        r.add(Tool.builder("block_at").title("Block at position")
                .desc("Block state, properties, fluid, light, hardness and block entity at a position (client-loaded chunks only).")
                .schema(Schema.of().reqInt("x", "X").reqInt("y", "Y").reqInt("z", "Z").bool("include_nbt", "Include client-known block entity NBT (default true)."))
                .handler(a -> ToolResult.json(blockJson(Ctx.level(), Ctx.reqPos(a), a.bool("include_nbt", true)))));

        r.add(Tool.builder("scan_area").title("Scan area")
                .desc("Scan a box of blocks (max 32768 blocks). mode=summary (default) returns counts per block id; mode=list returns every non-air block with its position. Box: x1,y1,z1..x2,y2,z2, or a cube of 'radius' around the player.")
                .schema(Schema.of().integer("x1", "Corner 1 X").integer("y1", "Corner 1 Y").integer("z1", "Corner 1 Z")
                        .integer("x2", "Corner 2 X").integer("y2", "Corner 2 Y").integer("z2", "Corner 2 Z")
                        .integer("radius", "Alternative to corners: cube radius around player (default 4, max 15).")
                        .enumStr("mode", "summary or list", false, "summary", "list")
                        .bool("include_air", "Include air in results (default false).")
                        .paging())
                .handler(a -> ToolResult.json(scan(a))));

        r.add(Tool.builder("find_blocks").title("Find blocks")
                .desc("Search client-loaded chunks around the player for a block id or block tag (prefix '#'), nearest first. Note: servers using anti-xray may hide ores.")
                .schema(Schema.of().reqStr("block", "Block id (minecraft:diamond_ore) or tag (#minecraft:logs, #c:ores).")
                        .integer("radius_chunks", "Chunk radius (default 4, max 16).")
                        .integer("min_y", "Minimum Y (optional).").integer("max_y", "Maximum Y (optional).")
                        .integer("limit", "Max results (default 25, max 500)."))
                .handler(a -> ToolResult.json(findBlocks(a))));

        r.add(Tool.builder("nearby_entities").title("Nearby entities")
                .desc("Entities near the player, nearest first: type, name, entity_id, position, distance, health, hostility. Filter by type id, category (hostile, passive, player, item, other) or name/type substring.")
                .schema(Schema.of().number("radius", "Search radius (default 32, max 256).")
                        .str("type", "Entity type id, e.g. minecraft:zombie.")
                        .enumStr("category", "Entity category filter.", false, "all", "hostile", "passive", "player", "item", "other")
                        .str("query", "Substring of type id or name.")
                        .paging())
                .handler(a -> ToolResult.json(nearby(a))));

        r.add(Tool.builder("entity_info").title("Entity details")
                .desc("Details of a single entity by entity_id (from nearby_entities / look_target): equipment, effects, health, attributes, passengers, vehicle, pose.")
                .schema(Schema.of().reqInt("entity_id", "Numeric entity id."))
                .handler(a -> ToolResult.json(entityInfo(a.reqInt("entity_id")))));

        r.add(Tool.builder("players_online").title("Online players")
                .desc("Players in the tab list with ping and game mode.")
                .schema(Schema.of().paging())
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

        r.add(Tool.builder("open_container").title("Open screen / container")
                .desc("The currently open GUI. For container screens (chests, furnaces, crafting, villager trades, anvils, modded machines) lists every slot with its index (use with container_click), plus furnace progress, brewing, enchanting costs and villager offers.")
                .schema(Schema.of().bool("include_empty", "Include empty slots (default false).")
                        .bool("include_player_inventory", "Include the player's inventory slots of the menu (default false).")
                        .bool("include_components", "Full item components (default false)."))
                .handler(a -> ToolResult.json(openContainer(a))));

        r.add(Tool.builder("hud").title("HUD info")
                .desc("Text shown on the HUD: scoreboard sidebar, boss bars, current title/subtitle and action bar message.")
                .handler(a -> ToolResult.json(hud())));
    }

    // ---------------------------------------------------------------- world_info

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

    // ---------------------------------------------------------------- blocks

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String propValue(Property p, Comparable v) {
        return p.getName(v);
    }

    static JsonObject stateJson(BlockState s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
        if (!s.getValues().isEmpty()) {
            JsonObject props = new JsonObject();
            for (Map.Entry<Property<?>, Comparable<?>> e : s.getValues().entrySet())
                props.addProperty(e.getKey().getName(), propValue(e.getKey(), e.getValue()));
            o.add("properties", props);
        }
        return o;
    }

    static JsonObject blockJson(ClientLevel l, BlockPos pos, boolean nbt) {
        JsonObject o = new JsonObject();
        o.add("pos", Json.pos(pos));
        if (!l.isLoaded(pos)) {
            o.addProperty("loaded", false);
            return o;
        }
        BlockState s = l.getBlockState(pos);
        JsonObject st = stateJson(s);
        for (var e : st.entrySet()) o.add(e.getKey(), e.getValue());
        o.addProperty("name", s.getBlock().getName().getString());
        o.addProperty("air", s.isAir());
        o.addProperty("hardness", s.getDestroySpeed(l, pos));
        o.addProperty("solid", s.isSolid());
        FluidState f = s.getFluidState();
        if (!f.isEmpty()) {
            JsonObject fo = new JsonObject();
            fo.addProperty("id", BuiltInRegistries.FLUID.getKey(f.getType()).toString());
            fo.addProperty("amount", f.getAmount());
            fo.addProperty("source", f.isSource());
            o.add("fluid", fo);
        }
        o.addProperty("block_light", l.getBrightness(LightLayer.BLOCK, pos));
        o.addProperty("sky_light", l.getBrightness(LightLayer.SKY, pos));
        o.addProperty("biome", l.getBiome(pos).getRegisteredName());
        BlockEntity be = l.getBlockEntity(pos);
        if (be != null) {
            JsonObject b = new JsonObject();
            b.addProperty("type", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
            if (nbt) {
                try {
                    b.addProperty("nbt", be.saveWithoutMetadata(l.registryAccess()).toString());
                } catch (Exception e) {
                    b.addProperty("nbt_error", e.toString());
                }
            }
            o.add("block_entity", b);
        }
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
            JsonObject e = entityJson(ent.getEntity(), p);
            e.addProperty("in_reach", ent.getLocation().distanceTo(eye) <= p.entityInteractionRange());
            o.add("entity", e);
        }
        if (block instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
            JsonObject b = blockJson(l, bh.getBlockPos(), a.bool("include_nbt", false));
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

    static JsonObject scan(Args a) {
        ClientLevel l = Ctx.level();
        LocalPlayer p = Ctx.player();
        BlockPos min, max;
        if (a.has("x1")) {
            BlockPos c1 = new BlockPos(a.reqInt("x1"), a.reqInt("y1"), a.reqInt("z1"));
            BlockPos c2 = new BlockPos(a.reqInt("x2"), a.reqInt("y2"), a.reqInt("z2"));
            min = new BlockPos(Math.min(c1.getX(), c2.getX()), Math.min(c1.getY(), c2.getY()), Math.min(c1.getZ(), c2.getZ()));
            max = new BlockPos(Math.max(c1.getX(), c2.getX()), Math.max(c1.getY(), c2.getY()), Math.max(c1.getZ(), c2.getZ()));
        } else {
            int r = a.integer("radius", 4, 0, 15);
            BlockPos c = p.blockPosition();
            min = c.offset(-r, -r, -r);
            max = c.offset(r, r, r);
        }
        long vol = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (vol > 32768) throw new ToolException("Box too large (" + vol + " blocks, max 32768).");
        boolean air = a.bool("include_air", false);
        boolean list = "list".equals(a.str("mode", "summary"));
        Map<String, Integer> counts = new TreeMap<>();
        List<JsonObject> blocks = new ArrayList<>();
        int unloaded = 0;
        for (BlockPos bp : BlockPos.betweenClosed(min, max)) {
            if (!l.isLoaded(bp)) {
                unloaded++;
                continue;
            }
            BlockState s = l.getBlockState(bp);
            if (s.isAir() && !air) continue;
            if (list) {
                JsonObject b = stateJson(s);
                b.addProperty("x", bp.getX());
                b.addProperty("y", bp.getY());
                b.addProperty("z", bp.getZ());
                blocks.add(b);
            } else {
                counts.merge(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(), 1, Integer::sum);
            }
        }
        JsonObject o = new JsonObject();
        o.add("min", Json.pos(min));
        o.add("max", Json.pos(max));
        o.addProperty("volume", vol);
        if (unloaded > 0) o.addProperty("unloaded_blocks", unloaded);
        if (list) {
            JsonObject pg = Paging.page(blocks, a, x -> x, 200);
            for (var e : pg.entrySet()) o.add(e.getKey(), e.getValue());
        } else {
            JsonObject c = new JsonObject();
            counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> c.addProperty(e.getKey(), e.getValue()));
            o.add("counts", c);
        }
        return o;
    }

    static Predicate<BlockState> blockPredicate(String spec) {
        if (spec.startsWith("#")) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, Args.parseId(spec));
            if (BuiltInRegistries.BLOCK.getTag(tag).isEmpty()) throw new ToolException("Unknown block tag " + spec + ". Use tags_list registry=minecraft:block.");
            return s -> s.is(tag);
        }
        ResourceLocation id = Args.parseId(spec);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new ToolException("Unknown block '" + id + "'.");
        Block b = BuiltInRegistries.BLOCK.get(id);
        return s -> s.is(b);
    }

    static JsonObject findBlocks(Args a) {
        ClientLevel l = Ctx.level();
        LocalPlayer p = Ctx.player();
        Predicate<BlockState> pred = blockPredicate(a.reqStr("block"));
        int rc = a.integer("radius_chunks", 4, 0, 16);
        int limit = a.integer("limit", 25, 1, 500);
        int minY = a.integer("min_y", l.getMinBuildHeight());
        int maxY = a.integer("max_y", l.getMaxBuildHeight());
        BlockPos origin = p.blockPosition();
        int pcx = origin.getX() >> 4, pcz = origin.getZ() >> 4;
        record Hit(BlockPos pos, BlockState state, double d2) {}
        List<Hit> hits = new ArrayList<>();
        int chunksScanned = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = pcx - rc; cx <= pcx + rc; cx++) {
            for (int cz = pcz - rc; cz <= pcz + rc; cz++) {
                LevelChunk chunk = l.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                chunksScanned++;
                LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection sec = sections[si];
                    if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(pred)) continue;
                    int baseY = l.getSectionYFromSectionIndex(si) << 4;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    for (int y = 0; y < 16; y++) {
                        int wy = baseY + y;
                        if (wy < minY || wy > maxY) continue;
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BlockState s = sec.getBlockState(x, y, z);
                                if (pred.test(s)) {
                                    m.set((cx << 4) + x, wy, (cz << 4) + z);
                                    hits.add(new Hit(m.immutable(), s, m.distSqr(origin)));
                                    if (hits.size() > 50000) break;
                                }
                            }
                    }
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::d2));
        JsonObject o = new JsonObject();
        o.addProperty("total_found", hits.size());
        o.addProperty("chunks_scanned", chunksScanned);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            Hit h = hits.get(i);
            JsonObject b = stateJson(h.state);
            b.addProperty("x", h.pos.getX());
            b.addProperty("y", h.pos.getY());
            b.addProperty("z", h.pos.getZ());
            b.addProperty("distance", Json.round(Math.sqrt(h.d2)));
            arr.add(b);
        }
        o.add("results", arr);
        return o;
    }

    // ---------------------------------------------------------------- entities

    static String category(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof ItemEntity) return "item";
        if (e instanceof Enemy) return "hostile";
        if (e instanceof Animal || e instanceof net.minecraft.world.entity.animal.WaterAnimal || e instanceof net.minecraft.world.entity.npc.AbstractVillager
                || e instanceof net.minecraft.world.entity.AgeableMob) return "passive";
        return "other";
    }

    static JsonObject entityJson(Entity e, LocalPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("entity_id", e.getId());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("name", e.getName().getString());
        if (e.hasCustomName()) o.addProperty("custom_name", true);
        o.addProperty("category", category(e));
        o.add("pos", Json.vec(e.position()));
        o.addProperty("distance", Json.round(e.distanceTo(p)));
        if (e instanceof LivingEntity le) {
            o.addProperty("health", Json.round(le.getHealth()));
            o.addProperty("max_health", Json.round(le.getMaxHealth()));
            if (le.isBaby()) o.addProperty("baby", true);
        }
        if (e instanceof ItemEntity ie) o.add("item", Stacks.json(ie.getItem(), false, p.registryAccess()));
        if (e instanceof Mob mob && mob.isAggressive()) o.addProperty("aggressive", true);
        return o;
    }

    static JsonObject nearby(Args a) {
        LocalPlayer p = Ctx.player();
        ClientLevel l = Ctx.level();
        double r = Math.min(256, Math.max(1, a.dbl("radius", 32)));
        String type = a.has("type") ? Args.parseId(a.str("type", "")).toString() : null;
        String cat = a.str("category", "all");
        IdFilter f = IdFilter.from(a, "-", "query", "-");
        List<Entity> list = l.getEntities(p, new AABB(p.position(), p.position()).inflate(r), e -> {
            if (e.distanceTo(p) > r) return false;
            String tid = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (type != null && !type.equals(tid)) return false;
            if (!"all".equals(cat) && !cat.equals(category(e))) return false;
            return f.test(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), () -> e.getName().getString());
        });
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return Paging.page(list, a, e -> entityJson(e, p));
    }

    static JsonObject entityInfo(int id) {
        LocalPlayer p = Ctx.player();
        Entity e = Ctx.level().getEntity(id);
        if (e == null) throw new ToolException("No entity with id " + id + " is loaded on the client.");
        JsonObject o = entityJson(e, p);
        o.addProperty("uuid", e.getUUID().toString());
        o.add("velocity", Json.vec(e.getDeltaMovement()));
        o.addProperty("yaw", Json.round(e.getYRot()));
        o.addProperty("pitch", Json.round(e.getXRot()));
        o.addProperty("pose", e.getPose().name().toLowerCase());
        o.addProperty("on_fire", e.isOnFire());
        o.addProperty("glowing", e.isCurrentlyGlowing());
        o.addProperty("invisible", e.isInvisible());
        if (e.getTeam() != null) o.addProperty("team", e.getTeam().getName());
        if (e.getVehicle() != null) o.addProperty("vehicle_id", e.getVehicle().getId());
        if (!e.getPassengers().isEmpty()) o.add("passenger_ids", Json.array(e.getPassengers(), x -> new com.google.gson.JsonPrimitive(x.getId())));
        if (e instanceof LivingEntity le) {
            JsonObject eq = new JsonObject();
            for (EquipmentSlot s : EquipmentSlot.values()) {
                var st = le.getItemBySlot(s);
                if (!st.isEmpty()) eq.add(s.getName(), Stacks.json(st, false, p.registryAccess()));
            }
            o.add("equipment", eq);
            JsonArray fx = new JsonArray();
            for (MobEffectInstance i : le.getActiveEffects()) {
                JsonObject x = new JsonObject();
                x.addProperty("id", i.getEffect().getRegisteredName());
                x.addProperty("amplifier", i.getAmplifier());
                fx.add(x);
            }
            o.add("effects", fx);
            o.addProperty("armor", le.getArmorValue());
        }
        if (e instanceof Player pl) {
            o.addProperty("player_name", pl.getGameProfile().getName());
        }
        return o;
    }

    // ---------------------------------------------------------------- GUI

    static JsonObject openContainer(Args a) {
        Minecraft mc = Ctx.mc();
        LocalPlayer p = Ctx.player();
        JsonObject o = new JsonObject();
        var screen = mc.screen;
        if (screen == null) {
            o.addProperty("open", false);
            o.addProperty("note", "No screen is open. The player inventory is container_id 0 (use open_inventory or player_inventory).");
            return o;
        }
        o.addProperty("open", true);
        o.addProperty("screen", screen.getClass().getName());
        o.addProperty("title", screen.getTitle().getString());
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return o;
        AbstractContainerMenu menu = cs.getMenu();
        o.addProperty("container_id", menu.containerId);
        o.addProperty("menu_class", menu.getClass().getName());
        try {
            o.addProperty("menu_type", String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType())));
        } catch (Exception ignored) {
            o.addProperty("menu_type", "minecraft:player_inventory");
        }
        boolean empty = a.bool("include_empty", false), inv = a.bool("include_player_inventory", false), full = a.bool("include_components", false);
        JsonArray slots = new JsonArray();
        for (Slot s : menu.slots) {
            boolean isInv = s.container instanceof net.minecraft.world.entity.player.Inventory;
            if (isInv && !inv) continue;
            if (!s.hasItem() && !empty) continue;
            JsonObject so = Stacks.json(s.getItem(), full, p.registryAccess());
            so.addProperty("slot", s.index);
            if (isInv) so.addProperty("player_inventory_slot", s.getContainerSlot());
            slots.add(so);
        }
        o.add("slots", slots);
        o.addProperty("total_slots", menu.slots.size());
        if (!menu.getCarried().isEmpty()) o.add("carried", Stacks.json(menu.getCarried(), full, p.registryAccess()));
        if (menu instanceof AbstractFurnaceMenu fm) {
            o.addProperty("cook_progress", Json.round(fm.getBurnProgress()));
            o.addProperty("fuel_remaining", Json.round(fm.getLitProgress()));
            o.addProperty("lit", fm.isLit());
        }
        if (menu instanceof BrewingStandMenu bm) {
            o.addProperty("brew_ticks", bm.getBrewingTicks());
            o.addProperty("fuel", bm.getFuel());
        }
        if (menu instanceof EnchantmentMenu em) {
            JsonArray opts = new JsonArray();
            var reg = p.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            for (int i = 0; i < 3; i++) {
                JsonObject x = new JsonObject();
                x.addProperty("level_cost", em.costs[i]);
                if (em.enchantClue[i] >= 0) {
                    reg.getHolder(em.enchantClue[i]).ifPresent(h -> x.addProperty("clue", h.getRegisteredName()));
                    x.addProperty("clue_level", em.levelClue[i]);
                }
                opts.add(x);
            }
            o.add("enchant_options", opts);
        }
        if (menu instanceof AnvilMenu am) o.addProperty("repair_cost", am.getCost());
        if (menu instanceof MerchantMenu mm) {
            o.addProperty("trader_level", mm.getTraderLevel());
            JsonArray offers = new JsonArray();
            int i = 0;
            for (MerchantOffer of : mm.getOffers()) {
                JsonObject x = new JsonObject();
                x.addProperty("index", i++);
                x.add("cost_a", Stacks.json(of.getCostA(), false, p.registryAccess()));
                if (!of.getCostB().isEmpty()) x.add("cost_b", Stacks.json(of.getCostB(), false, p.registryAccess()));
                x.add("result", Stacks.json(of.getResult(), false, p.registryAccess()));
                x.addProperty("uses", of.getUses());
                x.addProperty("max_uses", of.getMaxUses());
                x.addProperty("out_of_stock", of.isOutOfStock());
                offers.add(x);
            }
            o.add("offers", offers);
        }
        return o;
    }

    static JsonObject hud() {
        Minecraft mc = Ctx.mc();
        ClientLevel l = Ctx.level();
        JsonObject o = new JsonObject();
        var sb = l.getScoreboard();
        var obj = sb.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
        if (obj != null) {
            JsonObject s = new JsonObject();
            s.addProperty("title", obj.getDisplayName().getString());
            JsonArray lines = new JsonArray();
            sb.listPlayerScores(obj).stream().filter(e -> !e.isHidden())
                    .sorted(Comparator.comparingInt(net.minecraft.world.scores.PlayerScoreEntry::value).reversed()).limit(15)
                    .forEach(e -> {
                        JsonObject x = new JsonObject();
                        var team = sb.getPlayersTeam(e.owner());
                        String name = e.display() != null ? e.display().getString()
                                : net.minecraft.world.scores.PlayerTeam.formatNameForTeam(team, net.minecraft.network.chat.Component.literal(e.owner())).getString();
                        x.addProperty("text", name);
                        x.addProperty("score", e.value());
                        lines.add(x);
                    });
            s.add("lines", lines);
            o.add("sidebar", s);
        }
        JsonArray bars = new JsonArray();
        for (var be : mc.gui.getBossOverlay().events.values()) {
            JsonObject b = new JsonObject();
            b.addProperty("name", be.getName().getString());
            b.addProperty("progress", Json.round(be.getProgress()));
            b.addProperty("color", be.getColor().getName());
            bars.add(b);
        }
        o.add("boss_bars", bars);
        if (mc.gui.titleTime > 0) {
            if (mc.gui.title != null) o.addProperty("title", mc.gui.title.getString());
            if (mc.gui.subtitle != null) o.addProperty("subtitle", mc.gui.subtitle.getString());
        }
        if (mc.gui.overlayMessageTime > 0 && mc.gui.overlayMessageString != null)
            o.addProperty("action_bar", mc.gui.overlayMessageString.getString());
        return o;
    }
}
