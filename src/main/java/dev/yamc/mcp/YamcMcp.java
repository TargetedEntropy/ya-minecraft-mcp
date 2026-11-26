package dev.yamc.mcp;

import com.mojang.brigadier.Command;
import dev.yamc.mcp.client.GameWatcher;
import dev.yamc.mcp.client.Markers;
import dev.yamc.mcp.mcp.McpHttpServer;
import dev.yamc.mcp.mcp.McpProtocol;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.push.PushNotifier;
import dev.yamc.mcp.tools.Tools;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

@Mod(value = YamcMcp.MOD_ID, dist = Dist.CLIENT)
public final class YamcMcp {
    public static final String MOD_ID = "yamcmcp";
    public static final Logger LOGGER = LoggerFactory.getLogger("yamcmcp");
    public static String VERSION = "dev";

    private static McpHttpServer server;
    private static String token;
    private static String startError;

    public YamcMcp(IEventBus modBus, ModContainer container) {
        VERSION = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        modBus.addListener(FMLClientSetupEvent.class, e -> e.enqueueWork(YamcMcp::startServer));
        NeoForge.EVENT_BUS.register(new GameWatcher());
        PushNotifier.install();
        NeoForge.EVENT_BUS.register(new Markers());
        if (dev.yamc.mcp.client.DevAutoWorld.enabled()) NeoForge.EVENT_BUS.register(new dev.yamc.mcp.client.DevAutoWorld());
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, YamcMcp::registerCommands);
        NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, e -> {
            if (server != null) server.stop();
        });
    }

    private static void startServer() {
        try {
            token = Config.REQUIRE_TOKEN.get() ? loadOrCreateToken() : null;
            ToolRegistry registry = Tools.createRegistry();
            McpProtocol protocol = new McpProtocol(registry, Tools.INSTRUCTIONS);
            server = new McpHttpServer(protocol, Config.BIND_ADDRESS.get(), Config.PORT.get(), token);
            server.start();
            LOGGER.info("YAMC MCP server listening on {} with {} tools", server.url(), registry.all().size());
            LOGGER.info("Connect Claude Code with: {}", connectCommand());
            Files.writeString(FMLPaths.CONFIGDIR.get().resolve("yamcmcp-connect.txt"),
                    "# Plain HTTP (tools only, poll events):" + System.lineSeparator() + connectCommand() + System.lineSeparator() + System.lineSeparator()
                            + "# Channel mode (tools + pushed events):" + System.lineSeparator() + channelCommand() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            startError = e.toString();
            LOGGER.error("Failed to start YAMC MCP server", e);
        }
    }

    private static String loadOrCreateToken() throws Exception {
        Path p = FMLPaths.CONFIGDIR.get().resolve("yamcmcp-token.txt");
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
        String url = server != null ? server.url() : "http://127.0.0.1:" + Config.PORT.get() + "/mcp";
        String cmd = "claude mcp add --transport http minecraft " + url;
        if (token != null) cmd += " --header \"Authorization: Bearer " + token + "\"";
        return cmd;
    }

    /** Claude Code command using the stdio bridge so the server is loaded as a channel (push notifications). */
    public static String channelCommand() {
        String jar;
        try {
            jar = net.neoforged.fml.ModList.get().getModFileById(MOD_ID).getFile().getFilePath().toAbsolutePath().toString();
        } catch (Exception e) {
            jar = "<path to yamcmcp.jar>";
        }
        String url = server != null ? server.url() : "http://127.0.0.1:" + Config.PORT.get() + "/mcp";
        String cmd = "claude mcp add minecraft -- java -cp \"" + jar + "\" dev.yamc.mcp.bridge.StdioBridge --url " + url;
        if (token != null) cmd += " --token-file \"" + FMLPaths.CONFIGDIR.get().resolve("yamcmcp-token.txt").toAbsolutePath() + "\"";
        return cmd + "\n# then start Claude Code with: claude --dangerously-load-development-channels server:minecraft";
    }

    private static void registerCommands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("yamc")
                .executes(c -> status())
                .then(Commands.literal("status").executes(c -> status()))
                .then(Commands.literal("connect").then(Commands.literal("channel").executes(c -> {
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
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(cmd)))
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, cmd)))));
                    return Command.SINGLE_SUCCESS;
                })));
    }

    private static int status() {
        if (server == null) {
            msg(Component.literal("[YAMC MCP] not running: " + startError).withStyle(ChatFormatting.RED));
        } else {
            long ago = server.lastRequestMillis == 0 ? -1 : (System.currentTimeMillis() - server.lastRequestMillis) / 1000;
            msg(Component.literal("[YAMC MCP] " + server.url() + " | requests: " + server.requestCount.get()
                    + " | last client: " + server.lastClient + (ago >= 0 ? " (" + ago + "s ago)" : "")
                    + " | actions " + (Config.ENABLE_ACTIONS.get() ? "on" : "off")
                    + " | push streams: " + dev.yamc.mcp.mcp.SseHub.openStreams()).withStyle(ChatFormatting.AQUA));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void msg(Component c) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(c, false);
    }
}
