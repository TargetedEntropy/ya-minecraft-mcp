package dev.yamc.mcp.mcp;

/** Thrown by a tool to report a user-facing error (returned as an MCP tool result with isError=true). */
public class ToolException extends RuntimeException {
    public ToolException(String message) {
        super(message);
    }
}
