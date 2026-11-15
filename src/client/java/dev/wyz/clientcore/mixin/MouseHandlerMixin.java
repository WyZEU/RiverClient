package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.input.ZoomController;
import dev.wyz.clientcore.input.FreelookController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Freelook is held, mouse movement rotates the freelook camera instead of the
 * player. The player's rotation — and therefore everything the server sees — is frozen.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    /** Every in-game mouse press, for CPS. action 1 is a press; 0 is a release. */
//? if >=1.21.9 {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void clientcore$countClick(long window, net.minecraft.client.input.MouseButtonInfo info, int action, CallbackInfo ci) {
        int button = info.button();
//?} else {
    /*@Inject(method = "onPress", at = @At("HEAD"))
    private void clientcore$countClick(long window, int button, int action, int mods, CallbackInfo ci) {
*///?}
        if (action != 1) return;
//? if >=26.2 {
        /*if (this.minecraft.gui.screen() != null) return;
*///?} else {
        if (this.minecraft.screen != null) return;
//?}
        dev.wyz.clientcore.pvp.CpsTracker.press(button);
    }

    /** Scrolling while zoomed zooms further instead of changing the hotbar slot. */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void clientcore$scrollZoom(long window, double xOffset, double yOffset, CallbackInfo ci) {
//? if >=26.2 {
        /*if (this.minecraft.gui.screen() != null) return;
*///?} else {
        if (this.minecraft.screen != null) return;
//?}
        if (ZoomController.onScroll(yOffset)) ci.cancel();
    }

    @Shadow @Final private Minecraft minecraft;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientcore$freelookTurn(double movementTime, CallbackInfo ci) {
        if (!FreelookController.getActive()) {
            // Zoom: the further in, the slower the mouse, so aim holds steady.
            double scale = ZoomController.sensitivityScale();
            if (scale < 1.0) {
                this.accumulatedDX *= scale;
                this.accumulatedDY *= scale;
            }
            return;
        }
        double sensitivity = this.minecraft.options.sensitivity().get() * 0.6 + 0.2;
        double factor = sensitivity * sensitivity * sensitivity * 8.0;
        FreelookController.turn(
            this.accumulatedDX * factor * 0.15,
            this.accumulatedDY * factor * 0.15
        );
        this.accumulatedDX = 0.0;
        this.accumulatedDY = 0.0;
        ci.cancel();
    }
}
