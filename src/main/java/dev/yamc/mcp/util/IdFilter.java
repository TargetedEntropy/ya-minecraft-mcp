package dev.yamc.mcp.util;

import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.ToolException;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The standard namespace / query / regex filter. */
public final class IdFilter {
    private final String namespace;
    private final String query;
    private final Pattern regex;

    private IdFilter(String namespace, String query, Pattern regex) {
        this.namespace = namespace;
        this.query = query;
        this.regex = regex;
    }

    public static IdFilter from(Args args) {
        return from(args, "namespace", "query", "regex");
    }

    public static IdFilter from(Args args, String nsKey, String queryKey, String regexKey) {
        String ns = args.str(nsKey, null);
        String q = args.str(queryKey, null);
        String r = args.str(regexKey, null);
        Pattern p = null;
        if (r != null && !r.isBlank()) {
            try {
                p = Pattern.compile(r, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new ToolException("Invalid regex: " + e.getDescription());
            }
        }
        return new IdFilter(ns == null || ns.isBlank() ? null : ns.trim(),
                q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT), p);
    }

    public boolean isEmpty() {
        return namespace == null && query == null && regex == null;
    }

    public boolean usesName() {
        return query != null;
    }

    public boolean test(ResourceLocation id, Supplier<String> displayName) {
        if (namespace != null && !id.getNamespace().equals(namespace)) return false;
        String s = id.toString();
        if (regex != null && !regex.matcher(s).find()) return false;
        if (query != null) {
            if (s.contains(query)) return true;
            String n = displayName == null ? null : displayName.get();
            return n != null && n.toLowerCase(Locale.ROOT).contains(query);
        }
        return true;
    }
}
