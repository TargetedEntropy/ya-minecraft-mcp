package dev.yamc.mcp.fabric.mixin;

//? if fabric {
/*import dev.yamc.mcp.client.Capture;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Camera angle override for the screenshot freecam (NeoForge has ViewportEvent.ComputeCameraAngles): turns the camera
// after it has been placed at its entity (Camera.setup; alignWithEntity since 26.1).
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    //? if >=26.1 {
    /^@Inject(method = "alignWithEntity", at = @At("TAIL"))
    ^///?} else
    @Inject(method = "setup", at = @At("TAIL"))
    private void yamcmcp$overrideAngles(CallbackInfo ci) {
        Capture.Pose angles = Capture.angleOverride();
        if (angles != null) setRotation(angles.yaw(), angles.pitch());
    }
}
*///?}
