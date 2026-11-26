package dev.yamc.mcp.tools;

import dev.yamc.mcp.util.Mc;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.recipe.RecipeView;
import dev.yamc.mcp.recipe.Recipes;
import dev.yamc.mcp.util.IntegratedServer;
import dev.yamc.mcp.util.Source;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Optional;

/** A recipe set plus the registries to encode it with. */
record RecipeSource(List<RecipeView> recipes, HolderLookup.Provider regs, String source) {
    static RecipeSource from(Args a) {
        return of(Source.from(a));
    }

    /** AUTO: the integrated server when there is one (it keeps ingredient tags), else the recipes synced to this client. */
    static RecipeSource of(Source pref) {
        boolean server = pref == Source.SERVER || (pref == Source.AUTO && (IntegratedServer.get() != null || !Recipes.CLIENT_HAS_RECIPES));
        if (server) {
            MinecraftServer s = IntegratedServer.get();
            if (s == null && !Recipes.CLIENT_HAS_RECIPES)
                throw new ToolException("Recipes are not sent to clients on this Minecraft version (1.21.2+): recipe tools only work in singleplayer or a LAN world you host.");
            s = IntegratedServer.require();
            HolderLookup.Provider regs = Mc.serverRegistries(s);
            return new RecipeSource(Recipes.all(s.getRecipeManager(), regs, s.overworld()), regs, "integrated_server");
        }
        if (!Recipes.CLIENT_HAS_RECIPES)
            throw new ToolException("Recipes are not sent to clients on this Minecraft version (1.21.2+): use source=server in singleplayer or a LAN world you host.");
        var c = Minecraft.getInstance().getConnection();
        if (c == null) throw new ToolException("Not in a world: recipes are only available after joining a world/server.");
        return new RecipeSource(clientRecipes(c, c.registryAccess()), c.registryAccess(), "client");
    }

    private static List<RecipeView> clientRecipes(net.minecraft.client.multiplayer.ClientPacketListener c, HolderLookup.Provider regs) {
        //? if >=1.21.2 {
        /*return List.of();
        *///?} else
        return Recipes.all(c.getRecipeManager(), regs, c.getLevel());
    }

    Optional<RecipeView> byId(ResourceLocation id) {
        return recipes.stream().filter(r -> r.id().equals(id)).findFirst();
    }
}
