package dev.yamc.mcp.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.ToolException;

import java.util.List;
import java.util.function.Function;

/** Offset-cursor paging shared by all list tools. */
public final class Paging {
    public static final int DEFAULT = 50;
    public static final int MAX = 500;

    private Paging() {}

    public static <T> JsonObject page(List<T> items, Args args, Function<T, JsonElement> mapper) {
        return page(items, args, mapper, DEFAULT);
    }

    public static <T> JsonObject page(List<T> items, Args args, Function<T, JsonElement> mapper, int defaultSize) {
        int size = args.integer("page_size", defaultSize, 1, MAX);
        int offset = 0;
        String cursor = args.str("cursor", null);
        if (cursor != null && !cursor.isBlank()) {
            try {
                offset = Integer.parseInt(cursor.trim());
            } catch (NumberFormatException e) {
                throw new ToolException("Invalid cursor '" + cursor + "'");
            }
        }
        offset = Math.max(0, Math.min(offset, items.size()));
        int end = Math.min(items.size(), offset + size);
        JsonArray arr = new JsonArray();
        for (int i = offset; i < end; i++) arr.add(mapper.apply(items.get(i)));
        JsonObject o = new JsonObject();
        o.addProperty("total", items.size());
        o.addProperty("offset", offset);
        o.addProperty("count", arr.size());
        if (end < items.size()) o.addProperty("next_cursor", String.valueOf(end));
        o.add("items", arr);
        return o;
    }
}
