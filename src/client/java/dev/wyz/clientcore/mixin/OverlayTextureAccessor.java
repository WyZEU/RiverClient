package dev.wyz.clientcore.mixin;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The 16x16 texture entity shaders read the hurt flash from. Hit Color repaints its
 * top half (the hurt rows) with the chosen colour and puts vanilla's back when it is off.
 */
@Mixin(OverlayTexture.class)
public interface OverlayTextureAccessor {
    @Accessor("texture")
    DynamicTexture clientcore$texture();
}
