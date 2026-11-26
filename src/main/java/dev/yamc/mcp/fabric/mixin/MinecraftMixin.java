package dev.yamc.mcp.fabric.mixin;

//? if fabric {
/*import dev.yamc.mcp.client.ClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Screen open/close events for Fabric (NeoForge has ScreenEvent.Opening/Closing): compares the
// current screen before and after Minecraft.setScreen.
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow @Nullable public Screen screen;

    @Unique private Screen yamcmcp$before;

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void yamcmcp$beforeSetScreen(Screen next, CallbackInfo ci) {
        yamcmcp$before = screen;
    }

    @Inject(method = "setScreen", at = @At("TAIL"))
    private void yamcmcp$afterSetScreen(Screen next, CallbackInfo ci) {
        Screen before = yamcmcp$before;
        yamcmcp$before = null;
        if (before == screen) return;
        if (before != null) ClientEvents.screenClosing(before);
        if (screen != null) ClientEvents.screenOpening(screen);
    }
}
*///?}
