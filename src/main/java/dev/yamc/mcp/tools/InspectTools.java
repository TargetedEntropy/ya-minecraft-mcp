package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.debug.EntityAi;
import dev.yamc.mcp.json.Stacks;
import dev.yamc.mcp.platform.Platform;
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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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

        r.add(Tool.builder("entity_inspect").title("Inspect entity (client vs server)")
                .desc("One entity as the client and (in singleplayer) the integrated server see it: class, position, saved NBT and "
                        + "the NBT keys that differ between the sides. The server side adds AI: goals and target goals (running "
                        + "or not, priority), current target, navigation path, brain activities, running behaviors and memories, "
                        + "and every attribute with its base value and modifiers.")
                .schema(Schema.of().reqInt("entity_id", "Numeric entity id (from nearby_entities / look_target).")
                        .bool("include_nbt", "Include the full NBT of both sides (default true)."))
                .anyThread()
                .handler(InspectTools::entityInspect));

        r.add(Tool.builder("capabilities_at").title("Block storage (capabilities)")
                .desc("Singleplayer only. The item, fluid and energy storage a block exposes to other mods (NeoForge capabilities; "
                        + "Fabric Transfer API, plus Team Reborn Energy when installed), read on the server: every slot and tank "
                        + "with amounts and capacities. Also lists which of item/fluid/energy each side exposes, to debug "
                        + "sided access (pipes and hoppers on one face).")
                .schema(Schema.of().reqInt("x", "Block X.").reqInt("y", "Block Y.").reqInt("z", "Block Z.")
                        .enumStr("side", "Side to query from (default: none, the unsided view).", false, "down", "up", "north", "south", "west", "east")
                        .bool("include_empty", "Include empty slots (default false)."))
                .gate(Gates.INTEGRATED_SERVER)
                .anyThread()
                .handler(InspectTools::capabilitiesAt));
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

    private static ToolResult entityInspect(Args a) throws Exception {
        int id = a.reqInt("entity_id");
        boolean nbt = a.bool("include_nbt", true);
        Where<Integer> w = ClientThread.call(() -> new Where<>(Ctx.level().dimension(), Ctx.entity(id).getId()));
        return ToolResult.json(bothSides(w, l -> entity(l, id, nbt), "entity"));
    }

    private static View entity(Level l, int id, boolean includeNbt) {
        Entity e = l.getEntity(id);
        JsonObject o = new JsonObject();
        if (e == null) {
            o.addProperty("exists", false);
            return new View(o, null);
        }
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("class", e.getClass().getName());
        o.addProperty("uuid", e.getUUID().toString());
        o.add("pos", Json.vec(e.position()));
        o.add("velocity", Json.vec(e.getDeltaMovement()));
        o.addProperty("removed", e.isRemoved());
        o.addProperty("tick_count", e.tickCount);
        CompoundTag tag = null;
        try {
            tag = Mc.entityTag(e);
            if (includeNbt) o.addProperty("nbt", tag.toString());
        } catch (Exception ex) {
            o.addProperty("nbt_error", ex.toString());
        }
        if (!l.isClientSide() && e instanceof LivingEntity le) o.add("ai", EntityAi.describe(le));
        return new View(o, tag);
    }

    private static ToolResult capabilitiesAt(Args a) throws Exception {
        BlockPos pos = Ctx.reqPos(a);
        String sideArg = a.str("side", null);
        Direction side = sideArg == null ? null : Direction.byName(sideArg.toLowerCase(java.util.Locale.ROOT));
        if (sideArg != null && side == null) throw new ToolException("Unknown side '" + sideArg + "'.");
        boolean empty = a.bool("include_empty", false);
        MinecraftServer s = ClientThread.call(IntegratedServer::require);
        ResourceKey<Level> dim = ClientThread.call(() -> Ctx.level().dimension());
        return ToolResult.json(ClientThread.callServer(s, () -> {
            ServerLevel l = s.getLevel(dim);
            if (l == null) throw new ToolException("The server has no level " + Mc.id(dim));
            if (!l.isLoaded(pos)) throw new ToolException("That block is not loaded.");
            Platform p = YamcMcp.platform();
            JsonObject o = new JsonObject();
            o.add("pos", Json.pos(pos));
            o.add("block", BlockJson.state(l.getBlockState(pos)));
            o.addProperty("side", side == null ? "none" : side.getName());
            Platform.Storage st = p.storage(l, pos, side);
            o.add("storage", storage(st, empty, l.registryAccess()));
            JsonObject sides = new JsonObject();
            for (Direction d : Direction.values()) {
                Platform.Storage ds = p.storage(l, pos, d);
                JsonArray kinds = new JsonArray();
                if (ds.items() != null) kinds.add("item");
                if (ds.fluids() != null) kinds.add("fluid");
                if (ds.energy() != null) kinds.add("energy");
                sides.add(d.getName(), kinds);
            }
            o.add("exposed_by_side", sides);
            if (!st.any()) o.addProperty("note", "Nothing exposed from " + (side == null ? "the unsided view" : "this side") + ".");
            return o;
        }, SERVER_TIMEOUT_MS));
    }

    private static JsonObject storage(Platform.Storage st, boolean empty, net.minecraft.core.HolderLookup.Provider regs) {
        JsonObject o = new JsonObject();
        if (st.items() != null) {
            JsonArray slots = new JsonArray();
            for (int i = 0; i < st.items().size(); i++) {
                Platform.Slot slot = st.items().get(i);
                if (!empty && slot.amount() == 0) continue;
                JsonObject x = new JsonObject();
                x.addProperty("slot", i);
                if (slot.amount() > 0) x.add("item", Stacks.json(slot.stack(), false, regs));
                x.addProperty("amount", slot.amount());
                x.addProperty("capacity", slot.capacity());
                slots.add(x);
            }
            JsonObject items = new JsonObject();
            items.addProperty("slot_count", st.items().size());
            items.add("slots", slots);
            o.add("items", items);
        }
        if (st.fluids() != null) {
            JsonArray tanks = new JsonArray();
            for (int i = 0; i < st.fluids().size(); i++) {
                Platform.Tank t = st.fluids().get(i);
                if (!empty && t.amount() == 0) continue;
                JsonObject x = new JsonObject();
                x.addProperty("tank", i);
                if (t.fluid() != null) x.addProperty("fluid", t.fluid().toString());
                x.addProperty("amount", t.amount());
                x.addProperty("capacity", t.capacity());
                x.addProperty("buckets", Json.round((double) t.amount() / st.fluidPerBucket()));
                tanks.add(x);
            }
            JsonObject fluids = new JsonObject();
            fluids.addProperty("tank_count", st.fluids().size());
            fluids.addProperty("unit_per_bucket", st.fluidPerBucket());
            fluids.add("tanks", tanks);
            o.add("fluids", fluids);
        }
        if (st.energy() != null) {
            JsonObject e = new JsonObject();
            e.addProperty("stored", st.energy().stored());
            e.addProperty("capacity", st.energy().capacity());
            o.add("energy", e);
        }
        return o;
    }

    /** Where the two views differ: the block/entity itself, and the keys of their saved data. */
    private static JsonObject compare(View client, View server, String what) {
        JsonObject o = new JsonObject();
        boolean sameState = true;
        if (client.json().has("id")) { // a block: compare the block states too
            sameState = Objects.equals(client.json().get("id"), server.json().get("id"))
                    && Objects.equals(client.json().get("properties"), server.json().get("properties"));
            o.addProperty("same_state", sameState);
        }
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
