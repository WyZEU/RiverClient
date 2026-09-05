package dev.wyz.clientcore.mixin;

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

    @Shadow @Final private Minecraft minecraft;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientcore$freelookTurn(double movementTime, CallbackInfo ci) {
        if (!FreelookController.getActive()) {
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
