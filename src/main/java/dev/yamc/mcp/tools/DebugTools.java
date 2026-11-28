package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.debug.PacketLog;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;

import java.util.Locale;

/** Mod development: network traffic and mod configs. */
final class DebugTools {
    private DebugTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("packet_log").title("Packet log")
                .desc("Records the client's network packets: start, exercise the mod, then status shows how many packets of each "
                        + "type were sent and received and the most recent ones. Mod networking appears as custom_payload with the "
                        + "payload id, so you can see whether a mod's packet is sent, arrives, or floods. Recording continues "
                        + "until stop (and across reconnects).")
                .schema(Schema.of().enumStr("action", "start (clears previous counts), stop, or status (default).", false, "start", "stop", "status")
                        .str("filter", "For start: only record packet types containing this text, e.g. a mod id.")
                        .bool("include_content", "For start: keep each recent packet's text form (default false).")
                        .integer("recent", "For status: how many recent packets to list (default 30, max 500)."))
                .handler(DebugTools::packetLog));

        r.add(Tool.builder("mod_config").title("Mod config")
                .desc("A mod's config. NeoForge: every value of the mod's registered configs (client, common, server; server "
                        + "configs load with a world) with default, range and comment; give path and value to change one (saved "
                        + "to its file; the mod is notified as for an in-game edit). Fabric has no shared config system: shows "
                        + "the mod's files in the config folder instead, read-only.")
                .schema(Schema.of().reqStr("mod_id", "The mod id, e.g. mymod.")
                        .str("path", "To change a value: its dotted path as listed, e.g. general.maxRange.")
                        .str("value", "The new value as JSON: true, 12, 0.5, \"text\", [\"a\",\"b\"]."))
                .handler(DebugTools::modConfig));
    }

    private static ToolResult packetLog(Args a) {
        String action = a.str("action", "status").toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> PacketLog.start(Ctx.connection().getConnection(), a.bool("include_content", false), a.str("filter", null));
            case "stop" -> {
                var c = Ctx.mc().getConnection();
                PacketLog.stop(c == null ? null : c.getConnection());
            }
            case "status" -> {}
            default -> throw new ToolException("Unknown action '" + action + "'.");
        }
        return ToolResult.json(PacketLog.snapshot(a.integer("recent", 30, 0, 500)));
    }

    private static ToolResult modConfig(Args a) {
        String mod = a.reqStr("mod_id").trim();
        if (YamcMcp.platform().mods().stream().noneMatch(m -> m.id().equals(mod)))
            throw new ToolException("No mod '" + mod + "' is loaded. See mods_list.");
        JsonObject o = new JsonObject();
        o.addProperty("mod_id", mod);
        if (a.has("path")) {
            if (!a.has("value")) throw new ToolException("Give value with path.");
            String path = a.reqStr("path").trim();
            com.google.gson.JsonElement value;
            try {
                value = com.google.gson.JsonParser.parseString(a.reqStr("value"));
            } catch (RuntimeException e) {
                value = new com.google.gson.JsonPrimitive(a.reqStr("value")); // a bare word: a string or enum name
            }
            o.addProperty("path", path);
            o.add("value", YamcMcp.platform().setModConfig(mod, path, value));
            return ToolResult.json(o);
        }
        var configs = YamcMcp.platform().modConfigs(mod);
        o.add("configs", configs);
        if (configs.isEmpty()) o.addProperty("note", "This mod has no registered configs (or config files).");
        return ToolResult.json(o);
    }
}
