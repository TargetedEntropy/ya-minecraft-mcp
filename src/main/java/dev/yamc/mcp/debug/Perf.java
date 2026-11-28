package dev.yamc.mcp.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.util.ClientThread;
import dev.yamc.mcp.util.IntegratedServer;
import dev.yamc.mcp.util.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.HashMap;
import java.util.Map;

/** Performance numbers for the perf tool: the client's frame rate and memory, the integrated server's tick cost and load. */
public final class Perf {
    private Perf() {}

    public static JsonObject snapshot(int top) throws Exception {
        JsonObject o = new JsonObject();
        o.add("client", ClientThread.call(() -> client(top)));
        MinecraftServer s = ClientThread.call(IntegratedServer::get);
        if (s == null) o.addProperty("server", "unavailable: only in singleplayer / LAN host");
        else o.add("server", ClientThread.callServer(s, () -> server(s, top), 10_000));
        return o;
    }

    private static JsonObject client(int top) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        o.addProperty("fps", mc.getFps());
        Runtime rt = Runtime.getRuntime();
        JsonObject mem = new JsonObject();
        mem.addProperty("used_mb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        mem.addProperty("allocated_mb", rt.totalMemory() >> 20);
        mem.addProperty("max_mb", rt.maxMemory() >> 20);
        o.add("memory", mem);
        if (mc.level != null) {
            Counter entities = new Counter();
            for (Entity e : mc.level.entitiesForRendering()) entities.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            o.add("entities", entities.json(top));
        }
        return o;
    }

    private static JsonObject server(MinecraftServer s, int top) {
        JsonObject o = new JsonObject();
        long[] times = s.getTickTimesNanos();
        long worst = 0;
        for (long t : times) worst = Math.max(worst, t);
        o.addProperty("mspt_average", Json.round(s.getAverageTickTimeNanos() / 1e6));
        o.addProperty("mspt_worst_recent", Json.round(worst / 1e6));
        o.addProperty("tick_rate", s.tickRateManager().tickrate());
        o.addProperty("frozen", s.tickRateManager().isFrozen());
        o.addProperty("paused", s.isPaused());
        JsonArray levels = new JsonArray();
        for (ServerLevel l : s.getAllLevels()) {
            JsonObject lo = new JsonObject();
            lo.addProperty("dimension", dev.yamc.mcp.util.Mc.id(l.dimension()).toString());
            lo.addProperty("loaded_chunks", l.getChunkSource().getLoadedChunksCount());
            Counter entities = new Counter();
            for (Entity e : l.getAllEntities()) entities.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            lo.add("entities", entities.json(top));
            Counter tickers = new Counter();
            for (TickingBlockEntity t : l.blockEntityTickers) tickers.add(t.getType());
            lo.add("ticking_block_entities", tickers.json(top));
            levels.add(lo);
        }
        o.add("levels", levels);
        return o;
    }

    /** Counts by id, and by namespace (mod). */
    private static final class Counter {
        final Map<String, Integer> byId = new HashMap<>(), byMod = new HashMap<>();
        int total;

        void add(String id) {
            total++;
            byId.merge(id, 1, Integer::sum);
            int colon = id.indexOf(':');
            byMod.merge(colon < 0 ? "?" : id.substring(0, colon), 1, Integer::sum);
        }

        JsonObject json(int top) {
            JsonObject o = new JsonObject();
            o.addProperty("total", total);
            o.add("by_mod", top(byMod, top));
            o.add("by_type", top(byId, top));
            return o;
        }

        private static JsonObject top(Map<String, Integer> m, int n) {
            JsonObject o = new JsonObject();
            m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n).forEach(e -> o.addProperty(e.getKey(), e.getValue()));
            return o;
        }
    }
}
