package dev.yamc.mcp.mcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-to-client Server-Sent-Events streams (MCP Streamable HTTP "GET /mcp" stream).
 * Every JSON-RPC notification broadcast here is written to all open streams.
 */
public final class SseHub {
    private static final int MAX_STREAMS = 8;
    private static final int MAX_QUEUE = 500;

    private record Stream(BlockingQueue<String> queue, long openedAt, boolean channel) {}

    private static final CopyOnWriteArrayList<Stream> STREAMS = new CopyOnWriteArrayList<>();
    private static final AtomicLong IDS = new AtomicLong();

    private SseHub() {}

    public static int openStreams() {
        return STREAMS.size();
    }

    /** Streams opened by the stdio bridge, i.e. clients that surface pushes as Claude Code channel messages. */
    public static int channelStreams() {
        int n = 0;
        for (Stream s : STREAMS) if (s.channel) n++;
        return n;
    }

    /** Queues a JSON-RPC notification for every connected client. Safe from any thread. */
    public static void broadcast(JsonObject notification) {
        if (STREAMS.isEmpty()) return;
        String frame = "id: " + IDS.incrementAndGet() + "\nevent: message\ndata: " + Json.GSON.toJson(notification) + "\n\n";
        for (Stream s : STREAMS) {
            if (s.queue.size() >= MAX_QUEUE) s.queue.poll();
            s.queue.offer(frame);
        }
    }

    /** Serves one GET stream until the client disconnects. Blocks the calling HTTP worker thread. */
    static void serve(HttpExchange ex) throws IOException {
        if (STREAMS.size() >= MAX_STREAMS) {
            // drop the oldest stream; clients reconnect automatically if still alive
            Stream oldest = STREAMS.get(0);
            STREAMS.remove(oldest);
            oldest.queue.offer("");
        }
        boolean channel = "channel-bridge".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Yamc-Client"));
        Stream s = new Stream(new LinkedBlockingQueue<>(), System.currentTimeMillis(), channel);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (session != null) ex.getResponseHeaders().set("Mcp-Session-Id", session);
        ex.sendResponseHeaders(200, 0);
        STREAMS.add(s);
        YamcMcp.LOGGER.info("MCP notification stream opened ({}; {} open)", channel ? "channel bridge" : "plain HTTP client", STREAMS.size());
        try (OutputStream os = ex.getResponseBody()) {
            os.write(": yamc-mcp notification stream\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            while (true) {
                String frame = s.queue.poll(15, TimeUnit.SECONDS);
                if (frame == null) frame = ": keepalive\n\n";
                else if (frame.isEmpty()) break; // evicted
                os.write(frame.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // client went away
        } finally {
            STREAMS.remove(s);
            YamcMcp.LOGGER.info("MCP notification stream closed ({} open)", STREAMS.size());
        }
    }
}
