package dev.yamc.mcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.yamc.mcp.util.Json;

/** Result of a tool call: a list of MCP content blocks. */
public final class ToolResult {
    private final JsonArray content = new JsonArray();
    private boolean error;

    public static ToolResult json(JsonElement el) {
        return new ToolResult().text(Json.GSON.toJson(el));
    }

    public static ToolResult text(String s, boolean isError) {
        ToolResult r = new ToolResult().text(s);
        r.error = isError;
        return r;
    }

    public static ToolResult ok(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", message);
        return json(o);
    }

    public ToolResult text(String s) {
        JsonObject c = new JsonObject();
        c.addProperty("type", "text");
        c.addProperty("text", s);
        content.add(c);
        return this;
    }

    public ToolResult image(String base64, String mime) {
        JsonObject c = new JsonObject();
        c.addProperty("type", "image");
        c.addProperty("data", base64);
        c.addProperty("mimeType", mime);
        content.add(c);
        return this;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("content", content);
        o.addProperty("isError", error);
        return o;
    }
}
