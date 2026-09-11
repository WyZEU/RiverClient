package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.Module;
import dev.wyz.clientcore.module.ModuleRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Static FOV: flatten the speed/sprint FOV multiplier so the view never stretches. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerFovMixin {

    @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
    private void clientcore$staticFov(boolean fovEffectsEnabled, float scale, CallbackInfoReturnable<Float> cir) {
        Module module = ModuleRegistry.INSTANCE.get("static_fov");
        // Vanilla implements the spyglass zoom through this same modifier, so leave it
        // alone while scoping - otherwise Static FOV would flatten the zoom away.
        if (module != null && module.getActive() && !((AbstractClientPlayer) (Object) this).isScoping()) {
            cir.setReturnValue(1.0f);
        }
    }
}
