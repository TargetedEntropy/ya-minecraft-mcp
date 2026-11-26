package dev.yamc.mcp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.client.gui.screens.Screen;

/**
 * Development/testing aid only: when the JVM is started with -Dyamcmcp.devAutoWorld=NAME, the first time the
 * title screen opens a singleplayer world NAME is opened (or created with a fixed seed). Inactive otherwise.
 */
final class DevAutoWorld {
    private boolean done;

    static boolean enabled() {
        return System.getProperty("yamcmcp.devAutoWorld") != null;
    }

    void screenOpening(Screen screen) {
        if (done || !(screen instanceof TitleScreen)) return;
        done = true;
        String name = System.getProperty("yamcmcp.devAutoWorld");
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.getLevelSource().levelExists(name)) {
                mc.createWorldOpenFlows().openWorld(name, () -> mc.setScreen(new TitleScreen()));
            } else {
                LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.NORMAL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(Long.getLong("yamcmcp.devSeed", 12345L), true, false),
                        WorldPresets::createNormalWorldDimensions, new TitleScreen());
            }
        });
    }
}
