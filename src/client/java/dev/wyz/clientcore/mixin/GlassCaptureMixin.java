package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.ui.Glass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the finished world image to {@link Glass} once a frame, just before the GUI is
 * drawn over it. The GUI renderer's draw call is that moment in every version that has
 * one, including 26.x, where the GUI is gathered before the world renders and only drawn
 * here. No descriptor, so both of its shapes match (26.2 dropped the parameter). require =
 * 0: before 1.21.6 there is no such call and glass falls back to River's solid surfaces.
 */
@Mixin(GameRenderer.class)
public abstract class GlassCaptureMixin {
    @Inject(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render"),
        require = 0
    )
    private void clientcore$captureForGlass(CallbackInfo ci) {
        Glass.capture();
    }
}
