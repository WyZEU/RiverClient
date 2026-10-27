package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.dev.DevShot;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dev screenshot run (see {@link DevShot}) leaves the dev client's options.txt as it
 * found it. The run keeps its window out of full screen (DevShotWindowMixin), and the game
 * saves its options once loading finishes, which would write that windowed state over the
 * player's own full screen setting.
 */
@Mixin(Options.class)
public abstract class DevShotOptionsMixin {

    @Inject(method = "save", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientcore$keepOptionsForDevShot(CallbackInfo ci) {
        if (DevShot.INSTANCE.getActive()) ci.cancel();
    }
}
