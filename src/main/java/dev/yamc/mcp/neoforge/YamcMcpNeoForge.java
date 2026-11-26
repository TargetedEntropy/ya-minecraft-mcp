package dev.yamc.mcp.neoforge;

//? if neoforge {
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.client.ClientEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** NeoForge entrypoint: registers the config and forwards NeoForge events to {@link ClientEvents}. */
@Mod(value = YamcMcp.MOD_ID, dist = Dist.CLIENT)
public final class YamcMcpNeoForge {
    public YamcMcpNeoForge(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, NeoForgeConfig.SPEC);
        YamcMcp.init(new NeoForgePlatform(), container.getModInfo().getVersion().toString());

        modBus.addListener(FMLClientSetupEvent.class, e -> e.enqueueWork(ClientEvents::setup));
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(GameShuttingDownEvent.class, e -> ClientEvents.shutdown());
        bus.addListener(ClientTickEvent.Post.class, e -> ClientEvents.tick());
        bus.addListener(ClientChatReceivedEvent.class, e -> ClientEvents.chat(e.getMessage(), e.getSender(),
                e instanceof ClientChatReceivedEvent.System, e instanceof ClientChatReceivedEvent.System s && s.isOverlay()));
        bus.addListener(ScreenEvent.Opening.class, e -> ClientEvents.screenOpening(e.getNewScreen()));
        bus.addListener(ScreenEvent.Closing.class, e -> ClientEvents.screenClosing(e.getScreen()));
        bus.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> ClientEvents.loggingIn());
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> ClientEvents.loggingOut());
        //? if >=1.21.9 {
        /*bus.addListener(RenderLevelStageEvent.AfterEntities.class, e ->
                ClientEvents.renderLevel(e.getPoseStack(), net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera()));
        *///?} elif >=1.21.6 {
        /*bus.addListener(RenderLevelStageEvent.AfterBlockEntities.class, e -> ClientEvents.renderLevel(e.getPoseStack(), e.getCamera()));
        *///?} else {
        bus.addListener(RenderLevelStageEvent.class, e -> {
            if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) ClientEvents.renderLevel(e.getPoseStack(), e.getCamera());
        });
        //?}
        bus.addListener(RegisterClientCommandsEvent.class, e -> e.getDispatcher().register(YamcMcp.command()));
    }
}
//?}
