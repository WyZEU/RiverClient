package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.input.FreelookController;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
//? if >=26.1 {
/*import net.minecraft.client.DeltaTracker;
*///?}
//? if >=1.21.11 {
import net.minecraft.world.level.Level;
//?} else {
/*import net.minecraft.world.level.BlockGetter;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Freelook is held, re-orients the already-set-up third person camera around the
 * player using the freelook yaw/pitch. Camera only — the player entity is untouched.
 *
 * 26.1 replaced setup(level, entity, detached, reverse, partialTick) with update(deltaTracker)
 * and moved what used to be arguments onto the camera itself, so that generation reads the
 * entity and partial tick back off Camera and hands them to the same body. `require = 0` does
 * not cover this: it decides whether a missing injection point is fatal, while a handler whose
 * descriptor disagrees with a target that does exist is rejected outright.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

//? if >=26.1 {
/*    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private void clientcore$freelookOrbit(DeltaTracker deltaTracker, CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        clientcore$orbit(self.entity(), self.getCameraEntityPartialTicks(deltaTracker));
    }
*///?} elif >=1.21.11 {
    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void clientcore$freelookOrbit(Level level, Entity entity, boolean detached, boolean reverse, float partialTick, CallbackInfo ci) {
        clientcore$orbit(entity, partialTick);
    }
//?} else {
/*    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void clientcore$freelookOrbit(BlockGetter level, Entity entity, boolean detached, boolean reverse, float partialTick, CallbackInfo ci) {
        clientcore$orbit(entity, partialTick);
    }
*///?}

    private void clientcore$orbit(Entity entity, float partialTick) {
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
