package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.Module;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
//? if >=26.1 {
/*import net.minecraft.client.renderer.state.level.CameraRenderState;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Hurt Camera / No View Bob: cancel the two camera-shake passes when active. */
@Mixin(GameRenderer.class)
public abstract class GameRendererBobMixin {

//? if >=26.1 {
/*    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void clientcore$noHurtCam(CameraRenderState cameraRenderState, PoseStack poseStack, CallbackInfo ci) {
*///?} else {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void clientcore$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
//?}
        Module module = ModuleRegistry.INSTANCE.get("no_hurt_cam");
        if (module != null && module.getActive()) {
            ci.cancel();
        }
    }

//? if >=26.1 {
/*    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void clientcore$noViewBob(CameraRenderState cameraRenderState, PoseStack poseStack, CallbackInfo ci) {
*///?} else {
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void clientcore$noViewBob(PoseStack poseStack, float partialTick, CallbackInfo ci) {
//?}
        Module module = ModuleRegistry.INSTANCE.get("no_view_bob");
        if (module != null && module.getActive()) {
            ci.cancel();
        }
    }
}
