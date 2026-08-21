package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.input.FreelookController;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Freelook is held, re-orients the already-set-up third person camera around the
 * player using the freelook yaw/pitch. Camera only — the player entity is untouched.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    // 1.21.4's Camera.setup takes BlockGetter, not Level (which is what later versions
    // narrowed it to). An @Inject handler's params must match the target's exactly, or
    // Mixin hard-fails at apply time - require=0 does not soften that.
    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void clientcore$freelookOrbit(BlockGetter level, Entity entity, boolean detached, boolean reverse, float partialTick, CallbackInfo ci) {
        if (!FreelookController.getActive() || entity == null) {
            return;
        }
        CameraInvoker self = (CameraInvoker) this;
        self.river$setRotation(FreelookController.getCameraYaw(), FreelookController.getCameraPitch());
        self.river$setPosition(
            Mth.lerp(partialTick, entity.xo, entity.getX()),
            Mth.lerp(partialTick, entity.yo, entity.getY()) + entity.getEyeHeight(),
            Mth.lerp(partialTick, entity.zo, entity.getZ())
        );
        self.river$move(-self.river$getMaxZoom(4.0F), 0.0F, 0.0F);
    }
}
