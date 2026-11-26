package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.json.BlockJson;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Paging;
import dev.yamc.mcp.util.Regs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import net.minecraft.core.HolderSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Blocks in the client level: single and batch lookups, area scans and block search. */
final class BlockTools {
    private BlockTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("block_at").title("Block at position")
                .desc("Block state, properties, fluid, light, hardness and block entity at a position (client-loaded chunks only).")
                .schema(Schema.of().reqInt("x", "X").reqInt("y", "Y").reqInt("z", "Z").bool("include_nbt", "Include client-known block entity NBT (default true)."))
                .handler(a -> ToolResult.json(BlockJson.at(Ctx.level(), Ctx.reqPos(a), a.bool("include_nbt", true)))));
        r.add(Tool.builder("blocks_at").title("Blocks at positions")
                .desc("Batch version of block_at: block state, fluid, light and block entity type for up to 256 positions in one call.")
                .schema(Schema.of().reqStr("positions", "Positions as a JSON array of [x,y,z] triples or {x,y,z} objects, e.g. [[1,64,2],[3,65,4]].")
                        .bool("include_nbt", "Include client-known block entity NBT (default false)."))
                .handler(a -> {
                    com.google.gson.JsonElement raw = a.raw().get("positions");
                    if (raw != null && raw.isJsonPrimitive()) raw = com.google.gson.JsonParser.parseString(raw.getAsString());
                    if (raw == null || !raw.isJsonArray()) throw new ToolException("positions must be a JSON array");
                    JsonArray in = raw.getAsJsonArray();
                    if (in.size() > 256) throw new ToolException("At most 256 positions per call.");
                    boolean nbt = a.bool("include_nbt", false);
                    ClientLevel l = Ctx.level();
                    JsonArray out = new JsonArray();
                    for (var e : in) {
                        BlockPos bp;
                        if (e.isJsonArray() && e.getAsJsonArray().size() == 3) {
                            var t = e.getAsJsonArray();
                            bp = new BlockPos(t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt());
                        } else if (e.isJsonObject()) {
                            var t = e.getAsJsonObject();
                            bp = new BlockPos(t.get("x").getAsInt(), t.get("y").getAsInt(), t.get("z").getAsInt());
                        } else throw new ToolException("Bad position: " + e);
                        out.add(BlockJson.at(l, bp, nbt));
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("count", out.size());
                    o.add("blocks", out);
                    return ToolResult.json(o);
                }));
        r.add(Tool.builder("scan_area").title("Scan area")
                .desc("Scan a box of blocks (max 32768 blocks). mode=summary (default) returns counts per block id; mode=list returns every non-air block with its position. Box: x1,y1,z1..x2,y2,z2, or a cube of 'radius' around the player.")
                .schema(Schema.of().integer("x1", "Corner 1 X").integer("y1", "Corner 1 Y").integer("z1", "Corner 1 Z")
                        .integer("x2", "Corner 2 X").integer("y2", "Corner 2 Y").integer("z2", "Corner 2 Z")
                        .integer("radius", "Alternative to corners: cube radius around player (default 4, max 15).")
                        .enumStr("mode", "summary or list", false, "summary", "list")
                        .bool("include_air", "Include air in results (default false).")
                        .paging(200))
                .handler(a -> ToolResult.json(scan(a))));
        r.add(Tool.builder("find_blocks").title("Find blocks")
                .desc("Search client-loaded chunks around the player for a block id or block tag (prefix '#'), nearest first. Note: servers using anti-xray may hide ores.")
                .schema(Schema.of().reqStr("block", "Block id (minecraft:diamond_ore) or tag (#minecraft:logs, #c:ores).")
                        .integer("radius_chunks", "Chunk radius (default 4, max 16).")
                        .integer("min_y", "Minimum Y (optional).").integer("max_y", "Maximum Y (optional).")
                        .integer("limit", "Max results (default 25, max 500)."))
                .handler(a -> ToolResult.json(findBlocks(a))));
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
                JsonObject b = BlockJson.state(s);
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
        HolderSet<Block> set = Regs.holderSet(BuiltInRegistries.BLOCK, spec);
        return s -> s.is(set);
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
            JsonObject b = BlockJson.state(h.state);
            b.addProperty("x", h.pos.getX());
            b.addProperty("y", h.pos.getY());
            b.addProperty("z", h.pos.getZ());
            b.addProperty("distance", Json.round(Math.sqrt(h.d2)));
            arr.add(b);
        }
        o.add("results", arr);
        return o;
    }
}
