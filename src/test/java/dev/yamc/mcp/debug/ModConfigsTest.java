package dev.yamc.mcp.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModConfigsTest {
    @Test
    void credentialLookingNamesAreWithheld() {
        for (String n : new String[]{"general.apiToken", "auth.PASSWORD", "mymod/secrets.json", "mymod-api_key.txt", "server.key", "x.ApiKey"})
            assertTrue(ModConfigs.mayHoldCredentials(n), n);
    }

    @Test
    void ordinaryNamesAreShown() {
        for (String n : new String[]{"general.maxRange", "client.keybindHints", "mymod-client.toml", "mymod/keys.json"})
            assertFalse(ModConfigs.mayHoldCredentials(n), n);
    }
}
