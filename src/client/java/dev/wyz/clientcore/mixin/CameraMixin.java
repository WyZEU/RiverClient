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
 * Since 26.1 the camera update also builds the culling frustum. Apply the orbit
 * after vanilla positions the camera so the frustum uses the view that gets drawn.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

//? if >=26.1 {
/*    @Inject(method = "update", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
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
        // River's pause menu has its own view of you, and it wins over Freelook.
        if (dev.wyz.clientcore.ui.pause.PauseCamera.place((CameraInvoker) this, entity, partialTick)) {
            return;
        }
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
