package dev.yamc.mcp.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Records the client's network traffic while active: how many packets of each type went each way, and the most recent
 * ones. A handler in the connection's Netty pipeline sees every packet as an object, the same on every loader and for
 * singleplayer's in-memory connection. Custom payloads (mod networking) are counted by their payload id.
 */
public final class PacketLog {
    private PacketLog() {}

    private static final String HANDLER = "yamcmcp_packet_log";
    private static final int MAX_RECENT = 500;
    private static final int MAX_CONTENT = 300;

    private record Recent(long time, String direction, String type, @Nullable String content) {}

    private static boolean active, withContent;
    private static @Nullable String filter;
    private static long startedAt;
    private static final Map<String, long[]> COUNTS = new HashMap<>();
    private static final ArrayDeque<Recent> RECENT = new ArrayDeque<>();

    public static synchronized boolean active() {
        return active;
    }

    /** Starts (or restarts) recording on this connection; clears what was recorded. */
    public static synchronized void start(Connection c, boolean content, @Nullable String typeFilter) {
        COUNTS.clear();
        RECENT.clear();
        withContent = content;
        filter = typeFilter == null || typeFilter.isBlank() ? null : typeFilter.trim().toLowerCase(Locale.ROOT);
        startedAt = System.currentTimeMillis();
        active = true;
        attach(c);
    }

    public static synchronized void stop(@Nullable Connection c) {
        active = false;
        if (c != null) {
            ChannelPipeline p = pipeline(c);
            if (p.get(HANDLER) != null) p.remove(HANDLER);
        }
    }

    /** Re-attaches to a new connection (after joining another world) when recording. */
    public static synchronized void connected(Connection c) {
        if (active) attach(c);
    }

    private static ChannelPipeline pipeline(Connection c) {
        //? if neoforge {
        return c.channel().pipeline();
        //?} else
        /*return c.channel.pipeline();*/
    }

    private static void attach(Connection c) {
        ChannelPipeline p = pipeline(c);
        if (p.get(HANDLER) == null) p.addBefore("packet_handler", HANDLER, new Handler());
    }

    private static final class Handler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Packet<?> p) record("received", p);
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof Packet<?> p) record("sent", p);
            super.write(ctx, msg, promise);
        }
    }

    private static void record(String direction, Packet<?> p) {
        if (p instanceof BundlePacket<?> b) {
            for (Packet<?> sub : b.subPackets()) record(direction, sub);
            return;
        }
        String type = type(p);
        synchronized (PacketLog.class) {
            if (!active || (filter != null && !type.toLowerCase(Locale.ROOT).contains(filter))) return;
            COUNTS.computeIfAbsent(direction + " " + type, k -> new long[1])[0]++;
            String content = null;
            if (withContent) {
                content = String.valueOf(p);
                if (content.length() > MAX_CONTENT) content = content.substring(0, MAX_CONTENT) + "...";
            }
            RECENT.addLast(new Recent(System.currentTimeMillis(), direction, type, content));
            while (RECENT.size() > MAX_RECENT) RECENT.removeFirst();
        }
    }

    /** The packet type id; for a custom payload, also the payload's id (the mod channel). */
    private static String type(Packet<?> p) {
        if (p instanceof ClientboundCustomPayloadPacket c) return "custom_payload " + c.payload().type().id();
        if (p instanceof ServerboundCustomPayloadPacket c) return "custom_payload " + c.payload().type().id();
        try {
            return p.type().id().toString();
        } catch (RuntimeException e) {
            return p.getClass().getSimpleName();
        }
    }

    public static synchronized JsonObject snapshot(int recentLimit) {
        JsonObject o = new JsonObject();
        o.addProperty("active", active);
        if (startedAt > 0) o.addProperty("recording_ms", System.currentTimeMillis() - startedAt);
        if (filter != null) o.addProperty("filter", filter);
        long total = 0;
        JsonArray counts = new JsonArray();
        for (var e : COUNTS.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(100).toList()) {
            String[] k = e.getKey().split(" ", 2);
            JsonObject x = new JsonObject();
            x.addProperty("direction", k[0]);
            x.addProperty("type", k[1]);
            x.addProperty("count", e.getValue()[0]);
            counts.add(x);
        }
        for (long[] c : COUNTS.values()) total += c[0];
        o.addProperty("total", total);
        o.add("by_type", counts);
        JsonArray recent = new JsonArray();
        RECENT.stream().skip(Math.max(0, RECENT.size() - recentLimit)).forEach(r -> {
            JsonObject x = new JsonObject();
            x.addProperty("time", r.time());
            x.addProperty("direction", r.direction());
            x.addProperty("type", r.type());
            if (r.content() != null) x.addProperty("content", r.content());
            recent.add(x);
        });
        o.add("recent", recent);
        return o;
    }
}
