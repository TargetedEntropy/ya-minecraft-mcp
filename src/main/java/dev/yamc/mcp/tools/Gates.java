package dev.yamc.mcp.tools;

import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.mcp.Tool;

/** The config switches tools are gated on. */
final class Gates {
    private Gates() {}

    static final Tool.Gate ACTIONS = new Tool.Gate(() -> YamcMcp.settings().enableActions(),
            "Action tools are disabled (config: tools.enableActions=false).");
    static final Tool.Gate INTEGRATED_SERVER = new Tool.Gate(() -> YamcMcp.settings().enableIntegratedServerTools(),
            "Integrated-server tools are disabled (config: tools.enableIntegratedServerTools=false).");
    static final Tool.Gate LOCATE = new Tool.Gate(() -> YamcMcp.settings().enableLocate(),
            "Locate tools are disabled (config: tools.enableLocate=false).");

    /** Builder for a tool that changes game state: not read-only, and gated on tools.enableActions. */
    static Tool.Builder action(String name) {
        return Tool.builder(name).mutating().gate(ACTIONS);
    }
}
