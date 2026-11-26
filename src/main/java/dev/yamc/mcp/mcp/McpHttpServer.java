package dev.yamc.mcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP "Streamable HTTP" transport (spec 2025-06-18), JSON response mode only.
 * POST /mcp carries JSON-RPC requests; GET returns 405 (no server-initiated stream); DELETE ends a session.
 */
public final class McpHttpServer {
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private final McpProtocol protocol;
    private final String bind;
    private final int port;
    private final String token; // null = no auth
    private HttpServer server;
    private ExecutorService pool;
    public final AtomicLong requestCount = new AtomicLong();
    public volatile long lastRequestMillis;
    public volatile String lastClient = "-";

    public McpHttpServer(McpProtocol protocol, String bind, int port, String token) {
        this.protocol = protocol;
        this.bind = bind;
        this.port = port;
        this.token = token;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 32);
        AtomicInteger n = new AtomicInteger();
        // cached pool: long-lived GET notification streams each hold a thread
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "yamcmcp-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/mcp", this::handle);
        server.createContext("/", ex -> {
            byte[] b = "yamc-mcp: MCP endpoint is /mcp\n".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders("/".equals(ex.getRequestURI().getPath()) ? 200 : 404, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    public String url() {
        return "http://" + bind + ":" + port + "/mcp";
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!originAllowed(ex.getRequestHeaders().getFirst("Origin"))) {
                send(ex, 403, "{\"error\":\"Origin not allowed\"}");
                return;
            }
            if (token != null && !authorized(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"yamc-mcp\"");
                send(ex, 401, "{\"error\":\"Missing or invalid bearer token. Run /yamc connect in game to copy the setup command.\"}");
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> post(ex);
                case "DELETE" -> send(ex, 200, "");
                case "GET" -> {
                    String accept = ex.getRequestHeaders().getFirst("Accept");
                    if (accept != null && accept.contains("text/event-stream")) {
                        SseHub.serve(ex);
                    } else {
                        ex.getResponseHeaders().add("Allow", "GET, POST, DELETE");
                        send(ex, 405, "");
                    }
                }
                case "OPTIONS" -> send(ex, 204, "");
                default -> send(ex, 405, "");
            }
        } catch (Throwable t) {
            YamcMcp.LOGGER.error("MCP HTTP handler error", t);
            try {
                send(ex, 500, Json.GSON.toJson(McpProtocol.error(null, -32603, String.valueOf(t))));
            } catch (Throwable ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private void post(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        lastRequestMillis = System.currentTimeMillis();
        String body;
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) {
                send(ex, 413, "");
                return;
            }
            body = new String(b, StandardCharsets.UTF_8);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (Exception e) {
            send(ex, 400, Json.GSON.toJson(McpProtocol.error(null, -32700, "Parse error")));
            return;
        }
        String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (parsed.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : parsed.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    out.add(McpProtocol.error(null, -32600, "Invalid request"));
                    continue;
                }
                JsonObject r = dispatch(e.getAsJsonObject(), ex);
                if (r != null) out.add(r);
            }
            if (out.isEmpty()) send(ex, 202, "");
            else sendJson(ex, out, session);
        } else if (parsed.isJsonObject()) {
            JsonObject r = dispatch(parsed.getAsJsonObject(), ex);
            if (r == null) send(ex, 202, "");
            else sendJson(ex, r, session);
        } else {
            send(ex, 400, Json.GSON.toJson(McpProtocol.error(null, -32600, "Invalid request")));
        }
    }

    private JsonObject dispatch(JsonObject msg, HttpExchange ex) {
        if (msg.has("method") && "initialize".equals(msg.get("method").getAsString())) {
            ex.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
            JsonObject p = msg.getAsJsonObject("params");
            if (p != null && p.has("clientInfo")) {
                JsonObject ci = p.getAsJsonObject("clientInfo");
                lastClient = (ci.has("name") ? ci.get("name").getAsString() : "?") + (ci.has("version") ? " " + ci.get("version").getAsString() : "");
            }
        }
        return protocol.handle(msg);
    }

    private void sendJson(HttpExchange ex, JsonElement el, String session) throws IOException {
        if (session != null && !ex.getResponseHeaders().containsKey("Mcp-Session-Id"))
            ex.getResponseHeaders().set("Mcp-Session-Id", session);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] b = Json.GSON.toJson(el).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (!body.isEmpty()) ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
    }

    /** DNS-rebinding protection: browsers send Origin; only accept localhost origins. Non-browser clients send none. */
    static boolean originAllowed(String origin) {
        if (origin == null || origin.isEmpty() || "null".equals(origin)) return true;
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        } catch (Exception e) {
            return false;
        }
    }
}
