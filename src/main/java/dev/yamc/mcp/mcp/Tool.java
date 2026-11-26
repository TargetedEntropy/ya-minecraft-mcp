package dev.yamc.mcp.mcp;

import com.google.gson.JsonObject;

/**
 * A single MCP tool.
 *
 * @param thread    where the handler runs. CLIENT = on the Minecraft client thread (safe access to game state),
 *                  ANY = on the HTTP worker thread (handler is responsible for hopping threads itself).
 * @param category  grouping used for config gating ("read", "action", "server").
 */
public record Tool(String name, String title, String description, JsonObject inputSchema, Kind kind, Thread thread,
                   Handler handler) {

    public enum Thread { CLIENT, ANY }

    /** READ: observes state. ACTION: changes game state (gated by enableActions). SERVER: uses the integrated server. */
    public enum Kind { READ, ACTION, SERVER }

    @FunctionalInterface
    public interface Handler {
        ToolResult call(Args args) throws Exception;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String title;
        private String description = "";
        private JsonObject schema = Schema.of().build();
        private Kind kind = Kind.READ;
        private Thread thread = Thread.CLIENT;

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

        public Builder kind(Kind k) {
            this.kind = k;
            return this;
        }

        public Builder anyThread() {
            this.thread = Thread.ANY;
            return this;
        }

        public Tool handler(Handler h) {
            return new Tool(name, title, description, schema, kind, thread, h);
        }
    }
}
