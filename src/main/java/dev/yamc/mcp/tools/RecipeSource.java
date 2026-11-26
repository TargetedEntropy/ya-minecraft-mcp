package dev.yamc.mcp.tools;

import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.util.IntegratedServer;
import dev.yamc.mcp.util.Source;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeManager;

/** A recipe set plus the registries to encode it with. */
record RecipeSource(RecipeManager manager, HolderLookup.Provider regs, String source) {
    static RecipeSource from(Args a) {
        return of(Source.from(a));
    }

    /** AUTO: the integrated server when there is one (it keeps ingredient tags), else the recipes synced to this client. */
    static RecipeSource of(Source pref) {
        if (pref == Source.SERVER || (pref == Source.AUTO && IntegratedServer.get() != null)) {
            MinecraftServer s = IntegratedServer.require();
            return new RecipeSource(s.getRecipeManager(), s.reloadableRegistries().get(), "integrated_server");
        }
        var c = Minecraft.getInstance().getConnection();
        if (c == null) throw new ToolException("Not in a world: recipes are only available after joining a world/server.");
        return new RecipeSource(c.getRecipeManager(), c.registryAccess(), "client");
    }
}
