package dev.wyz.clientcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wyz.clientcore.dev.DevShot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * From 26.2 the game shows its window itself near the end of start-up, so creating it
 * hidden (DevShotWindowMixin) is no longer enough to keep a dev screenshot run off the
 * screen. This skips that one call while a run is active. require = 0: earlier versions
 * never make it.
 */
@Mixin(Minecraft.class)
public abstract class DevShotShowWindowMixin {
    @WrapOperation(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V", remap = false),
        require = 0
    )
    private void clientcore$keepDevShotHidden(long window, Operation<Void> original) {
        if (DevShot.INSTANCE.getActive()) return;
        original.call(window);
    }
}
