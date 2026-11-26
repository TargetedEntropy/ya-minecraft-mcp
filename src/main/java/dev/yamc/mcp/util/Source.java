package dev.yamc.mcp.util;

import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.ToolException;

/** The "source" tool argument: where to read registries and recipes from. */
public enum Source {
    AUTO, CLIENT, SERVER;

    public static Source from(Args a) {
        String s = a.str("source", "auto");
        return switch (s) {
            case "auto" -> AUTO;
            case "client" -> CLIENT;
            case "server" -> SERVER;
            default -> throw new ToolException("source must be auto, client or server (got '" + s + "').");
        };
    }
}
