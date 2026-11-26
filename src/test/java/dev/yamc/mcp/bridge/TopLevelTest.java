package dev.yamc.mcp.bridge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopLevelTest {
    @Test
    void readsTopLevelIdNotNestedOne() {
        Map<String, String> m = TopLevel.scalars(
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"id\":5,\"name\":\"x {\\\"\"},\"id\":\"abc\"}");
        assertEquals("\"abc\"", m.get("id"));
        assertEquals("\"tools/call\"", m.get("method"));
    }

    @Test
    void numericIdWithWhitespaceAndParamsFirst() {
        Map<String, String> m = TopLevel.scalars("{ \"params\" : {\"arguments\":{\"id\":\"zz\"}}, \"id\" : 42 , \"method\":\"initialize\"}");
        assertEquals("42", m.get("id"));
        assertEquals("initialize", TopLevel.unquote(m.get("method")));
        assertNull(m.get("params"), "nested values are skipped");
    }

    @Test
    void notificationHasNoId() {
        assertNull(TopLevel.scalars("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").get("id"));
    }

    @Test
    void nullIdAndMalformedInput() {
        assertEquals("null", TopLevel.scalars("{\"id\":null,\"method\":\"x\"}").get("id"));
        assertTrue(TopLevel.scalars("not json").isEmpty());
        assertTrue(TopLevel.scalars("{\"id\":").isEmpty());
    }

    @Test
    void unquoteDecodesEscapes() {
        assertEquals("a\"b\\c", TopLevel.unquote("\"a\\\"b\\\\c\""));
    }
}
