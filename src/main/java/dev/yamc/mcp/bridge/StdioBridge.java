package dev.yamc.mcp.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stdio <-> HTTP bridge so Claude Code can load YAMC MCP as a <em>channel</em> (channels must be stdio servers).
 * <p>
 * Run by Claude Code, NOT by Minecraft. Uses only the JDK (no Minecraft or Gson classes):
 * <pre>java -cp yamcmcp.jar dev.yamc.mcp.bridge.StdioBridge [--url URL] [--token-file FILE | --token TOKEN]</pre>
 * <ul>
 *   <li>Each JSON-RPC line on stdin is POSTed to the in-game server; the reply is written to stdout.</li>
 *   <li>The initialize reply gets {@code capabilities.experimental["claude/channel"]} added.</li>
 *   <li>After initialize, the bridge holds the server's GET SSE stream open and writes every pushed
 *       notification (notifications/claude/channel) to stdout, reconnecting if the game restarts.</li>
 * </ul>
 * stdout carries only JSON-RPC; diagnostics go to stderr.
 */
public final class StdioBridge {
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(-?\\d+|\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final PrintStream out;
    private final URI url;
    private final String token;
    private volatile String session;
    private volatile boolean streamStarted;

    private StdioBridge(URI url, String token) {
        this.url = url;
        this.token = token;
        this.out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String url = env("YAMCMCP_URL", "http://127.0.0.1:25599/mcp");
        String token = System.getenv("YAMCMCP_TOKEN");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = args[++i];
                case "--token" -> token = args[++i];
                case "--token-file" -> token = Files.readString(Path.of(args[++i]), StandardCharsets.UTF_8).trim();
                default -> {
                    System.err.println("Unknown argument " + args[i]);
                    System.exit(2);
                }
            }
        }
        new StdioBridge(URI.create(url), token).run();
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static void log(String s) {
        System.err.println("[yamcmcp-bridge] " + s);
    }

    private synchronized void emit(String json) {
        out.print(json.replace("\n", "").replace("\r", ""));
        out.print('\n');
        out.flush();
    }

    private void run() throws Exception {
        log("bridging stdio <-> " + url);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            final String msg = line;
            // Handle each message on its own thread so a slow tool call doesn't block pings/other calls.
            Thread.ofVirtual().start(() -> forward(msg));
        }
        log("stdin closed, exiting");
        System.exit(0);
    }

    private HttpRequest.Builder req() {
        HttpRequest.Builder b = HttpRequest.newBuilder(url);
        if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
        String s = session;
        if (s != null) b.header("Mcp-Session-Id", s);
        return b;
    }

    private void forward(String msg) {
        Matcher mm = METHOD.matcher(msg);
        String method = mm.find() ? mm.group(1) : null;
        Matcher im = ID.matcher(msg);
        String id = im.find() ? im.group(1) : null;
        boolean isRequest = method != null && id != null;
        try {
            HttpRequest r = req().timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(msg, StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
            int code = resp.statusCode();
            if (code == 202 || resp.body().isEmpty()) return;
            if (code == 401) {
                if (isRequest) emit(error(id, "Unauthorized: wrong or missing token (see config/yamcmcp-token.txt; run /yamc connect in game)."));
                return;
            }
            String body = resp.body();
            if ("initialize".equals(method) && code == 200) {
                body = addChannelCapability(body);
                startStream();
            }
            emit(body);
        } catch (ConnectException e) {
            if (isRequest) emit(error(id, "Minecraft is not running or the YAMC MCP mod is not listening on " + url + ". Start the game, then reconnect with /mcp."));
        } catch (Exception e) {
            if (isRequest) emit(error(id, "Bridge error: " + e));
        }
    }

    static String addChannelCapability(String body) {
        String exp = "\"experimental\":{\"claude/channel\":{}}";
        int i = body.indexOf("\"capabilities\":{");
        if (i < 0) return body;
        int at = i + "\"capabilities\":{".length();
        boolean empty = body.charAt(at) == '}';
        return body.substring(0, at) + exp + (empty ? "" : ",") + body.substring(at);
    }

    private static String error(String id, String message) {
        String m = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32000,\"message\":\"" + m + "\"}}";
    }

    /** Keeps the server's SSE notification stream open for the life of the bridge. */
    private synchronized void startStream() {
        if (streamStarted) return;
        streamStarted = true;
        Thread.ofPlatform().daemon().name("yamcmcp-sse").start(() -> {
            long backoff = 1000;
            while (true) {
                try {
                    HttpRequest r = req().header("Accept", "text/event-stream").GET().build();
                    HttpResponse<Stream<String>> resp = http.send(r, HttpResponse.BodyHandlers.ofLines());
                    if (resp.statusCode() != 200) {
                        log("notification stream: HTTP " + resp.statusCode());
                    } else {
                        log("notification stream connected");
                        backoff = 1000;
                        StringBuilder data = new StringBuilder();
                        try (Stream<String> lines = resp.body()) {
                            for (var it = lines.iterator(); it.hasNext(); ) {
                                String l = it.next();
                                if (l.startsWith("data:")) {
                                    data.append(l.substring(5).trim());
                                } else if (l.isEmpty() && !data.isEmpty()) {
                                    emit(data.toString());
                                    data.setLength(0);
                                }
                            }
                        }
                        log("notification stream ended");
                    }
                } catch (Exception e) {
                    // game not running / restarting
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    return;
                }
                backoff = Math.min(backoff * 2, 15000);
            }
        });
    }
}
