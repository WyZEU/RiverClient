package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.CombatVisualsModule;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void clientcore$lowerFireOverlay(
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        TextureAtlasSprite fireSprite,
        CallbackInfo ci
    ) {
        CombatVisualsModule module = ModuleRegistry.INSTANCE.get("combat_visuals");
        if (module != null && module.lowerFire()) {
            ci.cancel();
        }
    }

    /**
     * Underwater screen tint. Full-screen translucent overdraw, so hiding it is a
     * fill-rate win as well as a clarity one. Vanilla underwater FOG is untouched -
     * that is what actually limits how far you can see, so this stays cosmetic.
     */
    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void clientcore$hideWaterOverlay(
        Minecraft minecraft,
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        CallbackInfo ci
    ) {
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module != null && module.getActive() && !module.waterOverlayEnabled()) {
            ci.cancel();
        }
    }

    /** Suffocation texture drawn when your head is inside a block. */
    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    private static void clientcore$hideWallOverlay(
        TextureAtlasSprite sprite,
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        CallbackInfo ci
    ) {
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module != null && module.getActive() && !module.wallOverlayEnabled()) {
            ci.cancel();
        }
    }
}
