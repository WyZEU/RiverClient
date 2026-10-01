package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.input.ZoomController;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if >=26.1 {
/*@Mixin(net.minecraft.client.Camera.class)
*///?} else {
@Mixin(GameRenderer.class)
//?}
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
//? if >=26.1 {
/*    private void clientcore$zoomFov(CallbackInfoReturnable<Float> cir) {
*///?} elif >=1.21.2 {
    private void clientcore$zoomFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
//?} else {
/*    // getFov returned a double before 1.21.2.
    private void clientcore$zoomFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
*///?}
        // Runs every frame even when not zooming, so the zoom can ease back out.
//? if >=1.21.2 {
        cir.setReturnValue((float) ZoomController.apply(cir.getReturnValue()));
//?} else {
/*        cir.setReturnValue(ZoomController.apply(cir.getReturnValue()));
*///?}
    }
}
