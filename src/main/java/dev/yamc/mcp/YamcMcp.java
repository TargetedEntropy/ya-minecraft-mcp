package dev.yamc.mcp;

import dev.yamc.mcp.util.Mc;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.yamc.mcp.mcp.McpHttpServer;
import dev.yamc.mcp.mcp.McpProtocol;
import dev.yamc.mcp.mcp.SseHub;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.platform.Platform;
import dev.yamc.mcp.platform.Settings;
import dev.yamc.mcp.push.PushNotifier;
import dev.yamc.mcp.tools.Tools;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/** The loader-independent mod: lifecycle, the MCP server and the /yamc client command. */
public final class YamcMcp {
    public static final String MOD_ID = "yamcmcp";
    public static final Logger LOGGER = LoggerFactory.getLogger("yamcmcp");
    public static String VERSION = "dev";

    private static Platform platform;
    private static McpHttpServer server;
    private static String token;
    private static String startError;

    private YamcMcp() {}

    /** Called once by the loader entrypoint, before any other hook. */
    public static void init(Platform p, String version) {
        platform = p;
        VERSION = version;
        dev.yamc.mcp.debug.LogCapture.install();
        PushNotifier.install();
    }

    public static Platform platform() {
        return platform;
    }

    /** Starts the MCP server; called on the client thread once the game is set up. */
    public static void startServer() {
        try {
            token = Settings.REQUIRE_TOKEN.get() ? loadOrCreateToken() : null;
            ToolRegistry registry = Tools.createRegistry();
            McpProtocol protocol = new McpProtocol(registry, Tools.INSTRUCTIONS);
            server = new McpHttpServer(protocol, Settings.BIND_ADDRESS.get(), Settings.PORT.get(), token);
            server.start();
            LOGGER.info("YAMC MCP server listening on {} with {} tools", server.url(), registry.all().size());
            LOGGER.info("Connect Claude Code with: {}", connectCommand());
            Files.writeString(platform.configDir().resolve("yamcmcp-connect.txt"),
                    "# Plain HTTP (tools only, poll events):" + System.lineSeparator() + connectCommand() + System.lineSeparator() + System.lineSeparator()
                            + "# Channel mode (tools + pushed events):" + System.lineSeparator() + channelCommand() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            startError = e.toString();
            LOGGER.error("Failed to start YAMC MCP server", e);
        }
    }

    private static String loadOrCreateToken() throws Exception {
        Path p = platform.configDir().resolve("yamcmcp-token.txt");
        if (Files.exists(p)) {
            String t = Files.readString(p, StandardCharsets.UTF_8).trim();
            if (!t.isEmpty()) return t;
        }
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String t = HexFormat.of().formatHex(b);
        Files.writeString(p, t, StandardCharsets.UTF_8);
        return t;
    }

    public static String connectCommand() {
        String url = server != null ? server.url() : "http://127.0.0.1:" + Settings.PORT.get() + "/mcp";
        String cmd = "claude mcp add --transport http minecraft " + url;
        if (token != null) cmd += " --header \"Authorization: Bearer " + token + "\"";
        return cmd;
    }

    /** Claude Code command using the stdio bridge so the server is loaded as a channel (push notifications). */
    public static String channelCommand() {
        Path modJar = platform.modJar();
        String jar = modJar != null ? modJar.toAbsolutePath().toString() : "<path to yamcmcp.jar>";
        String url = server != null ? server.url() : "http://127.0.0.1:" + Settings.PORT.get() + "/mcp";
        String cmd = "claude mcp add minecraft -- java -cp \"" + jar + "\" dev.yamc.mcp.bridge.StdioBridge --url " + url;
        if (token != null) cmd += " --token-file \"" + platform.configDir().resolve("yamcmcp-token.txt").toAbsolutePath() + "\"";
        return cmd + "\n# then start Claude Code with: claude --dangerously-load-development-channels server:minecraft";
    }

    public static void stopServer() {
        if (server != null) server.stop();
    }

    /** The /yamc client command, for any command source type (each loader has its own client source). */
    public static <S> LiteralArgumentBuilder<S> command() {
        return LiteralArgumentBuilder.<S>literal("yamc")
                .executes(c -> status())
                .then(LiteralArgumentBuilder.<S>literal("status").executes(c -> status()))
                .then(LiteralArgumentBuilder.<S>literal("connect").then(LiteralArgumentBuilder.<S>literal("channel").executes(c -> {
                    String cmd = channelCommand();
                    Minecraft.getInstance().keyboardHandler.setClipboard(cmd);
                    msg(Component.literal("[YAMC MCP] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("Channel (push notifications) setup copied to clipboard. Start Claude Code with --dangerously-load-development-channels server:minecraft").withStyle(ChatFormatting.WHITE)));
                    return Command.SINGLE_SUCCESS;
                })).executes(c -> {
                    String cmd = connectCommand();
                    Minecraft.getInstance().keyboardHandler.setClipboard(cmd);
                    msg(Component.literal("[YAMC MCP] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("Claude Code setup command copied to clipboard. ").withStyle(ChatFormatting.WHITE))
                            .append(Component.literal("(hover)").withStyle(s -> s.withColor(ChatFormatting.GRAY)
                                    .withHoverEvent(Mc.showText(Component.literal(cmd)))
                                    .withClickEvent(Mc.copyToClipboard(cmd)))));
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static int status() {
        if (server == null) {
            msg(Component.literal("[YAMC MCP] not running: " + startError).withStyle(ChatFormatting.RED));
        } else {
            long ago = server.lastRequestMillis == 0 ? -1 : (System.currentTimeMillis() - server.lastRequestMillis) / 1000;
            msg(Component.literal("[YAMC MCP] " + server.url() + " | requests: " + server.requestCount.get()
                    + " | last client: " + server.lastClient + (ago >= 0 ? " (" + ago + "s ago)" : "")
                    + " | actions " + (Settings.ENABLE_ACTIONS.get() ? "on" : "off")
                    + " | push streams: " + SseHub.openStreams()).withStyle(ChatFormatting.AQUA));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void msg(Component c) {
        var p = Minecraft.getInstance().player;
        if (p != null) Mc.message(p, c, false);
    }
}
