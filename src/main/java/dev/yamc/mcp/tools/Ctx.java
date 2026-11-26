package dev.yamc.mcp.tools;

import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Shared helpers for tool handlers. */
final class Ctx {
    private Ctx() {}

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static LocalPlayer player() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw new ToolException("Not in a world (on the title screen or loading). Join a world first.");
        return p;
    }

    static ClientLevel level() {
        ClientLevel l = Minecraft.getInstance().level;
        if (l == null) throw new ToolException("Not in a world (on the title screen or loading). Join a world first.");
        return l;
    }

    static RegistryAccess regs() {
        return level().registryAccess();
    }

    /** x/y/z args, defaulting to the player's block position for any missing coordinate. */
    static BlockPos posArg(Args a) {
        LocalPlayer p = player();
        BlockPos b = p.blockPosition();
        return new BlockPos(a.integer("x", b.getX()), a.integer("y", b.getY()), a.integer("z", b.getZ()));
    }

    static BlockPos reqPos(Args a) {
        return new BlockPos(a.reqInt("x"), a.reqInt("y"), a.reqInt("z"));
    }

    static Item item(String id) {
        var rl = Args.parseId(id);
        Item it = BuiltInRegistries.ITEM.get(rl);
        if (it == Items.AIR && !rl.getPath().equals("air"))
            throw new ToolException("Unknown item '" + rl + "'. Use registry_entries with registry=minecraft:item to search.");
        return it;
    }

    static ClientPacketListener connection() {
        var c = mc().getConnection();
        if (c == null || mc().player == null) throw new ToolException("Not in a world.");
        return c;
    }

    static Entity entity(int id) {
        Entity e = level().getEntity(id);
        if (e == null) throw new ToolException("No loaded entity with id " + id);
        return e;
    }

    /** Multi-tick actions need ticking: singleplayer pauses on focus loss or with a menu open. */
    static void requireUnpaused() {
        if (mc().isPaused())
            throw new ToolException("The game is paused (singleplayer pauses when the window loses focus or a menu is open). Close the menu, or press F3+P in game to disable pause-on-focus-loss.");
    }
}
