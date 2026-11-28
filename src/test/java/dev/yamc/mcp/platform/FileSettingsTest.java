package dev.yamc.mcp.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileSettingsTest {
    @Test
    void newFileHasEveryKeyAndReadsBackAsDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("yamcmcp.properties");
        FileSettings.load(file); // writes the template
        Properties written = new Properties();
        try (var r = Files.newBufferedReader(file)) {
            written.load(r);
        }
        // The keys released so far: existing files must keep working.
        assertEquals(java.util.Set.of("port", "bindAddress", "requireToken", "clientThreadTimeoutMs", "enableActions",
                "enableIntegratedServerTools", "enableLocate", "freecamMaxDistance", "freecamOnMultiplayer", "eventBufferSize", "logBufferSize",
                "push.enabled", "push.eventTypes"), written.stringPropertyNames());
        FileSettings read = FileSettings.load(file);
        for (Setting<?> s : Settings.ALL) assertEquals(s.def(), read.get(s), s.key());
    }

    @Test
    void invalidValuesFallBackToDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("yamcmcp.properties");
        Files.writeString(file, "port=80\nrequireToken=\neventBufferSize=lots\npush.eventTypes= death, ,damage \n");
        FileSettings s = FileSettings.load(file);
        assertEquals(25599, s.get(Settings.PORT));
        assertEquals(true, s.get(Settings.REQUIRE_TOKEN));
        assertEquals(1000, s.get(Settings.EVENT_BUFFER_SIZE));
        assertEquals(List.of("death", "damage"), s.get(Settings.PUSH_EVENT_TYPES));
        assertEquals("127.0.0.1", s.get(Settings.BIND_ADDRESS));
    }
}
