package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import dev.yamc.mcp.json.BlockJson;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.ClientThread;
import dev.yamc.mcp.util.IntegratedServer;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Mc;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Mod development: blocks and entities as both sides see them. In singleplayer the client and the integrated server
 * each hold their own copy of the world; a difference between the two is a sync bug (data the server never sent).
 */
final class InspectTools {
    private InspectTools() {}

    private static final long SERVER_TIMEOUT_MS = 10_000;

    static void register(ToolRegistry r) {
        r.add(Tool.builder("block_inspect").title("Inspect block (client vs server)")
                .desc("One block as the client and (in singleplayer) the integrated server see it: state, block entity NBT, and "
                        + "a comparison of the two. Keys only on the server are data the block entity does not sync to the "
                        + "client (getUpdateTag / update packet). The server side adds scheduled ticks, whether the block entity "
                        + "ticks, and the redstone power the block receives.")
                .schema(Schema.of().integer("x", "Block X (default: the player's position).").integer("y", "Block Y.").integer("z", "Block Z."))
                .anyThread()
                .handler(InspectTools::blockInspect));
    }

    /** One side's view: the JSON shown, and the saved data compared between the sides. */
    record View(JsonObject json, @Nullable CompoundTag tag) {}

    private record Where<T>(ResourceKey<Level> dimension, T target) {}

    /**
     * Reads {@code read} on the client, then on the integrated server's copy of the same dimension, and puts both in one
     * object with their comparison. Without an integrated server the server side is left out with a note.
     */
    private static <T> JsonObject bothSides(Where<T> where, Function<Level, View> read, String what) throws Exception {
        View client = ClientThread.call(() -> read.apply(Ctx.level()));
        MinecraftServer s = ClientThread.call(IntegratedServer::get);
        JsonObject o = new JsonObject();
        o.add("client", client.json());
        if (s == null) {
            o.addProperty("server", "unavailable: the server side can only be read in singleplayer / LAN host");
            return o;
        }
        View server = ClientThread.callServer(s, () -> {
            ServerLevel l = s.getLevel(where.dimension());
            if (l == null) throw new ToolException("The server has no level " + Mc.id(where.dimension()));
            return read.apply(l);
        }, SERVER_TIMEOUT_MS);
        o.add("server", server.json());
        o.add("sync", compare(client, server, what));
        return o;
    }

    private static ToolResult blockInspect(Args a) throws Exception {
        Where<BlockPos> w = ClientThread.call(() -> new Where<>(Ctx.level().dimension(), Ctx.posArg(a)));
        return ToolResult.json(bothSides(w, l -> block(l, w.target()), "block entity"));
    }

    private static View block(Level l, BlockPos pos) {
        JsonObject o = BlockJson.at(l, pos, false);
        if (!l.isLoaded(pos)) return new View(o, null);
        BlockState state = l.getBlockState(pos);
        BlockEntity be = l.getBlockEntity(pos);
        CompoundTag tag = null;
        if (be != null) {
            JsonObject b = o.getAsJsonObject("block_entity");
            b.addProperty("class", be.getClass().getName());
            try {
                tag = BlockJson.tag(be);
                b.addProperty("nbt", tag.toString());
            } catch (Exception e) {
                b.addProperty("nbt_error", e.toString());
            }
            if (!l.isClientSide()) {
                b.addProperty("ticks", state.getTicker(l, be.getType()) != null);
                b.addProperty("removed", be.isRemoved());
            }
        }
        o.addProperty("block_class", state.getBlock().getClass().getName());
        if (!l.isClientSide()) {
            o.addProperty("scheduled_block_tick", l.getBlockTicks().hasScheduledTick(pos, state.getBlock()));
            if (!state.getFluidState().isEmpty())
                o.addProperty("scheduled_fluid_tick", l.getFluidTicks().hasScheduledTick(pos, state.getFluidState().getType()));
            o.addProperty("redstone_power_received", l.getBestNeighborSignal(pos));
        }
        return new View(o, tag);
    }

    /** Where the two views differ: the block/entity itself, and the keys of their saved data. */
    private static JsonObject compare(View client, View server, String what) {
        JsonObject o = new JsonObject();
        boolean sameState = Objects.equals(client.json().get("id"), server.json().get("id"))
                && Objects.equals(client.json().get("properties"), server.json().get("properties"));
        o.addProperty("same_state", sameState);
        CompoundTag c = client.tag(), s = server.tag();
        if (c == null && s == null) {
            o.addProperty("in_sync", sameState);
            return o;
        }
        if (c == null || s == null) {
            o.addProperty("in_sync", false);
            o.addProperty("note", "The " + what + " exists only on the " + (c == null ? "server" : "client") + ".");
            return o;
        }
        Set<String> onlyServer = new TreeSet<>(), onlyClient = new TreeSet<>(), different = new TreeSet<>();
        for (String k : Mc.keys(s)) {
            if (!c.contains(k)) onlyServer.add(k);
            else if (!Objects.equals(c.get(k), s.get(k))) different.add(k);
        }
        for (String k : Mc.keys(c)) if (!s.contains(k)) onlyClient.add(k);
        o.addProperty("in_sync", sameState && onlyServer.isEmpty() && onlyClient.isEmpty() && different.isEmpty());
        o.add("nbt_only_on_server", Json.strings(onlyServer));
        o.add("nbt_only_on_client", Json.strings(onlyClient));
        o.add("nbt_different", Json.strings(different));
        return o;
    }
}
