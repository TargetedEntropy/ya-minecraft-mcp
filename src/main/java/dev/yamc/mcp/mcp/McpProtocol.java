package dev.yamc.mcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.util.ClientThread;

import java.util.List;

/** JSON-RPC 2.0 / MCP method dispatcher. Transport independent. */
public final class McpProtocol {
    public static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final ToolRegistry tools;
    private final String instructions;

    public McpProtocol(ToolRegistry tools, String instructions) {
        this.tools = tools;
        this.instructions = instructions;
    }

    /** Handles one JSON-RPC message. Returns null for notifications / responses (no reply). */
    public JsonObject handle(JsonObject msg) {
        JsonElement id = msg.get("id");
        boolean isRequest = msg.has("method") && id != null && !id.isJsonNull();
        if (!msg.has("method")) return null; // a response from the client; we never send requests, ignore
        String method = msg.get("method").getAsString();
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
        if (!isRequest) return null; // notifications/initialized, notifications/cancelled, ...
        try {
            JsonElement result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> new JsonObject();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(params);
                case "resources/list" -> emptyList("resources");
                case "resources/templates/list" -> emptyList("resourceTemplates");
                case "prompts/list" -> emptyList("prompts");
                case "logging/setLevel" -> new JsonObject();
                default -> throw new RpcError(-32601, "Method not found: " + method);
            };
            return success(id, result);
        } catch (RpcError e) {
            return error(id, e.code, e.getMessage());
        } catch (Exception e) {
            YamcMcp.LOGGER.error("MCP method {} failed", method, e);
            return error(id, -32603, "Internal error: " + e);
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : SUPPORTED_VERSIONS.get(0);
        String version = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", version);
        JsonObject caps = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        caps.add("tools", toolsCap);
        r.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "yamc-mcp");
        info.addProperty("title", "YAMC MCP (Minecraft client)");
        info.addProperty("version", YamcMcp.VERSION);
        r.add("serverInfo", info);
        r.addProperty("instructions", instructions);
        return r;
    }

    private JsonObject toolsList() {
        JsonArray arr = new JsonArray();
        for (Tool t : tools.visible()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("title", t.title());
            o.addProperty("description", t.description());
            o.add("inputSchema", t.inputSchema());
            JsonObject ann = new JsonObject();
            ann.addProperty("title", t.title());
            ann.addProperty("readOnlyHint", t.kind() != Tool.Kind.ACTION);
            ann.addProperty("destructiveHint", false);
            ann.addProperty("openWorldHint", false);
            o.add("annotations", ann);
            arr.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("tools", arr);
        return r;
    }

    private JsonObject toolsCall(JsonObject params) {
        if (!params.has("name")) throw new RpcError(-32602, "Missing tool name");
        String name = params.get("name").getAsString();
        Tool tool = tools.get(name);
        if (tool == null) throw new RpcError(-32602, "Unknown tool: " + name);
        Args args = new Args(params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : null);
        try {
            String gate = tools.gateReason(tool);
            if (gate != null) return ToolResult.text(gate, true).toJson();
            ToolResult res = tool.thread() == Tool.Thread.CLIENT ? ClientThread.call(() -> tool.handler().call(args)) : tool.handler().call(args);
            return res.toJson();
        } catch (ToolException e) {
            return ToolResult.text(e.getMessage(), true).toJson();
        } catch (Exception e) {
            YamcMcp.LOGGER.warn("Tool {} failed", name, e);
            return ToolResult.text("Tool '" + name + "' failed: " + e, true).toJson();
        }
    }

    private static JsonObject emptyList(String key) {
        JsonObject r = new JsonObject();
        r.add(key, new JsonArray());
        return r;
    }

    private static JsonObject success(JsonElement id, JsonElement result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    public static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        o.add("error", e);
        return o;
    }

    public static final class RpcError extends RuntimeException {
        final int code;

        public RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

}
