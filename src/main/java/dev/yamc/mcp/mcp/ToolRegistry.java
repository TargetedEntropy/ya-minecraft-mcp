package dev.yamc.mcp.mcp;

import dev.yamc.mcp.Config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry add(Tool t) {
        if (tools.put(t.name(), t) != null) throw new IllegalStateException("Duplicate tool " + t.name());
        return this;
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    /** Tools advertised in tools/list (disabled categories are hidden). */
    public List<Tool> visible() {
        List<Tool> out = new ArrayList<>();
        for (Tool t : tools.values()) if (gateReason(t) == null) out.add(t);
        return out;
    }

    /** Returns a reason the tool is disabled by config, or null if allowed. */
    public String gateReason(Tool t) {
        if (t.kind() == Tool.Kind.ACTION && !Config.ENABLE_ACTIONS.get())
            return "Action tools are disabled (config: tools.enableActions=false).";
        if (t.kind() == Tool.Kind.SERVER && !Config.ENABLE_INTEGRATED_SERVER_TOOLS.get())
            return "Integrated-server tools are disabled (config: tools.enableIntegratedServerTools=false).";
        if (t.name().startsWith("locate_") && !Config.ENABLE_LOCATE.get())
            return "Locate tools are disabled (config: tools.enableLocate=false).";
        return null;
    }
}
