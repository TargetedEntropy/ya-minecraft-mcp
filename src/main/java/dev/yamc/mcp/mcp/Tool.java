package dev.yamc.mcp.mcp;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A single MCP tool.
 *
 * @param readOnly reported to clients as readOnlyHint; false for tools that change game state.
 * @param thread   where the handler runs. CLIENT = on the Minecraft client thread (safe access to game state),
 *                 ANY = on the HTTP worker thread (handler is responsible for hopping threads itself).
 * @param gates    config switches that must all be on for the tool to be listed and callable.
 */
public record Tool(String name, String title, String description, JsonObject inputSchema, boolean readOnly, Thread thread,
                   List<Gate> gates, Handler handler) {

    public enum Thread { CLIENT, ANY }

    /** A config switch that can disable a tool, with the message shown when it is off. */
    public record Gate(BooleanSupplier enabled, String disabledReason) {}

    @FunctionalInterface
    public interface Handler {
        ToolResult call(Args args) throws Exception;
    }

    /** Reason the tool is disabled by config, or null if it is enabled. */
    public String gateReason() {
        for (Gate g : gates) if (!g.enabled().getAsBoolean()) return g.disabledReason();
        return null;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String title;
        private String description = "";
        private JsonObject schema = Schema.of().build();
        private boolean readOnly = true;
        private Thread thread = Thread.CLIENT;
        private final List<Gate> gates = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
            this.title = name;
        }

        public Builder title(String t) {
            this.title = t;
            return this;
        }

        public Builder desc(String d) {
            this.description = d;
            return this;
        }

        public Builder schema(Schema s) {
            this.schema = s.build();
            return this;
        }

        /** Marks the tool as changing game state. */
        public Builder mutating() {
            this.readOnly = false;
            return this;
        }

        public Builder gate(Gate g) {
            gates.add(g);
            return this;
        }

        public Builder anyThread() {
            this.thread = Thread.ANY;
            return this;
        }

        public Tool handler(Handler h) {
            return new Tool(name, title, description, schema, readOnly, thread, List.copyOf(gates), h);
        }
    }
}
