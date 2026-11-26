package dev.yamc.mcp.mcp;

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

    /** Tools advertised in tools/list (tools disabled by config are hidden). */
    public List<Tool> visible() {
        return tools.values().stream().filter(t -> t.gateReason() == null).toList();
    }
}
