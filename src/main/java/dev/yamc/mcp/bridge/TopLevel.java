package dev.yamc.mcp.bridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads the scalar members of a JSON object's top level (e.g. a JSON-RPC message's "id" and "method")
 * without a JSON library, ignoring anything nested inside params or results. Values are returned as their
 * raw JSON text: strings keep their quotes, so an id can be echoed back verbatim.
 */
final class TopLevel {
    private TopLevel() {}

    static Map<String, String> scalars(String json) {
        Map<String, String> out = new HashMap<>();
        int i = skipWs(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') return out;
        i++;
        while (true) {
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) != '"') return out;
            int keyEnd = stringEnd(json, i);
            String key = unquote(json.substring(i, keyEnd));
            i = skipWs(json, keyEnd);
            if (i >= json.length() || json.charAt(i) != ':') return out;
            i = skipWs(json, i + 1);
            int valueEnd = valueEnd(json, i);
            if (valueEnd < 0) return out;
            char c = json.charAt(i);
            if (c != '{' && c != '[') out.put(key, json.substring(i, valueEnd).trim());
            i = skipWs(json, valueEnd);
            if (i >= json.length() || json.charAt(i) != ',') return out;
            i++;
        }
    }

    /** The content of a JSON string literal (only \" and \\ escapes are decoded; enough for ids and method names). */
    static String unquote(String s) {
        if (s.length() < 2 || s.charAt(0) != '"') return s;
        return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Index just past the string literal starting at i. */
    private static int stringEnd(String s, int i) {
        for (int j = i + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\\') j++;
            else if (c == '"') return j + 1;
        }
        return s.length();
    }

    /** Index just past the value starting at i (nested objects/arrays are skipped), or -1 if malformed. */
    private static int valueEnd(String s, int i) {
        if (i >= s.length()) return -1;
        char c = s.charAt(i);
        if (c == '"') return stringEnd(s, i);
        if (c == '{' || c == '[') {
            int depth = 0;
            for (int j = i; j < s.length(); j++) {
                char d = s.charAt(j);
                if (d == '"') j = stringEnd(s, j) - 1;
                else if (d == '{' || d == '[') depth++;
                else if ((d == '}' || d == ']') && --depth == 0) return j + 1;
            }
            return -1;
        }
        int j = i;
        while (j < s.length() && s.charAt(j) != ',' && s.charAt(j) != '}') j++;
        return j;
    }
}
