package dev.yamc.mcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Tiny fluent builder for JSON Schema tool input descriptions. */
public final class Schema {
    private final JsonObject props = new JsonObject();
    private final JsonArray required = new JsonArray();

    public static Schema of() {
        return new Schema();
    }

    private Schema prop(String name, String type, String desc, boolean req) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", desc);
        props.add(name, p);
        if (req) required.add(name);
        return this;
    }

    public Schema str(String name, String desc) {
        return prop(name, "string", desc, false);
    }

    public Schema reqStr(String name, String desc) {
        return prop(name, "string", desc, true);
    }

    public Schema integer(String name, String desc) {
        return prop(name, "integer", desc, false);
    }

    public Schema reqInt(String name, String desc) {
        return prop(name, "integer", desc, true);
    }

    public Schema number(String name, String desc) {
        return prop(name, "number", desc, false);
    }

    public Schema bool(String name, String desc) {
        return prop(name, "boolean", desc, false);
    }

    public Schema enumStr(String name, String desc, boolean req, String... values) {
        prop(name, "string", desc, req);
        JsonArray arr = new JsonArray();
        for (String v : values) arr.add(v);
        props.getAsJsonObject(name).add("enum", arr);
        return this;
    }

    public Schema strArray(String name, String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "array");
        p.addProperty("description", desc);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        p.add("items", items);
        props.add(name, p);
        return this;
    }

    /** Adds the standard filter + paging parameters used by every list tool. */
    public Schema paging(int defaultSize) {
        integer("page_size", "Results per page (default " + defaultSize + ", max 500).");
        str("cursor", "Opaque cursor from a previous response's next_cursor.");
        return this;
    }

    public Schema idFilters() {
        str("namespace", "Only ids in this namespace (mod id), e.g. 'minecraft' or 'create'.");
        str("query", "Case-insensitive substring matched against the id and the localized display name.");
        str("regex", "Java regex matched (find) against the full id string.");
        return this;
    }

    public JsonObject build() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", props);
        if (!required.isEmpty()) o.add("required", required);
        return o;
    }
}
