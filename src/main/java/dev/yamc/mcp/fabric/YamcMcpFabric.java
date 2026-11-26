package dev.yamc.mcp.fabric;

//? if fabric {
/*import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.client.ClientEvents;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

// Fabric entrypoint: forwards Fabric API events to ClientEvents. Screen open/close come from MinecraftMixin.
public final class YamcMcpFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        String version = FabricLoader.getInstance().getModContainer(YamcMcp.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        YamcMcp.init(new FabricPlatform(), version);

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> ClientEvents.setup());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> ClientEvents.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> ClientEvents.tick());
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
                ClientEvents.chat(message, sender == null ? null : dev.yamc.mcp.util.Mc.id(sender), false, false));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> ClientEvents.chat(message, null, true, overlay));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ClientEvents.loggingIn());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> ClientEvents.loggingOut());
        //? if >=26.1 {
        /^net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_SOLID_FEATURES.register(ctx ->
                ClientEvents.renderLevel(ctx.poseStack(), dev.yamc.mcp.util.Mc.camera(net.minecraft.client.Minecraft.getInstance())));
        ^///?} elif >=1.21.9 {
        /^net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(ctx ->
                ClientEvents.renderLevel(ctx.matrices(), dev.yamc.mcp.util.Mc.camera(net.minecraft.client.Minecraft.getInstance())));
        ^///?} else
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(ctx -> ClientEvents.renderLevel(ctx.matrixStack(), ctx.camera()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(YamcMcp.command()));
    }
}
*///?}
