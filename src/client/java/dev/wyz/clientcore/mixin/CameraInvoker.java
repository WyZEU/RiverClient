package dev.wyz.clientcore.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraInvoker {
    @Invoker("setRotation")
    void river$setRotation(float yRot, float xRot);

    @Invoker("setPosition")
    void river$setPosition(double x, double y, double z);

    @Invoker("move")
    void river$move(float zoom, float dy, float dx);

    @Invoker("getMaxZoom")
    float river$getMaxZoom(float zoom);
}
