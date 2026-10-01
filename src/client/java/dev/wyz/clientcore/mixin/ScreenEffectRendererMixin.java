package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.CombatVisualsModule;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
*///?} elif >=1.21.4 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 * The buffer source these three take was added in 1.21.4, so 1.21.2 and 1.21.3 pass one
 * argument fewer, and renderFire took the Minecraft instance instead of a buffer there.
 *
 * 26.2 moved these three overlays onto the submit-node model: they no longer draw into
 * a buffer source, they hand a node to a collector, and each was renamed to match
 * (renderFire -> submitFire, renderWater -> submitWater, renderTex -> submitBlockSprite,
 * the last one also taking a light value it did not before). Only the signatures differ
 * - each cancel decision below is the same on every version.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
//? if >=26.2 {
/*    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void clientcore$lowerFireOverlay(
        PoseStack poseStack,
        SubmitNodeCollector collector,
        TextureAtlasSprite fireSprite,
        CallbackInfo ci
    ) {
*///?} elif >=1.21.11 {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void clientcore$lowerFireOverlay(
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        TextureAtlasSprite fireSprite,
        CallbackInfo ci
    ) {
//?} elif >=1.21.4 {
/*    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void clientcore$lowerFireOverlay(
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        CallbackInfo ci
    ) {
*///?} else {
/*    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void clientcore$lowerFireOverlay(
        Minecraft minecraft,
        PoseStack poseStack,
        CallbackInfo ci
    ) {
*///?}
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
//? if >=26.2 {
/*    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void clientcore$hideWaterOverlay(
        Minecraft minecraft,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        CallbackInfo ci
    ) {
*///?} elif >=1.21.4 {
    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void clientcore$hideWaterOverlay(
        Minecraft minecraft,
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        CallbackInfo ci
    ) {
//?} else {
/*    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void clientcore$hideWaterOverlay(
        Minecraft minecraft,
        PoseStack poseStack,
        CallbackInfo ci
    ) {
*///?}
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module != null && module.getActive() && !module.waterOverlayEnabled()) {
            ci.cancel();
        }
    }

    /** Suffocation texture drawn when your head is inside a block. */
//? if >=26.2 {
/*    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    private static void clientcore$hideWallOverlay(
        TextureAtlasSprite sprite,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int lightCoords,
        CallbackInfo ci
    ) {
*///?} elif >=1.21.4 {
    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    private static void clientcore$hideWallOverlay(
        TextureAtlasSprite sprite,
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        CallbackInfo ci
    ) {
//?} else {
/*    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    private static void clientcore$hideWallOverlay(
        TextureAtlasSprite sprite,
        PoseStack poseStack,
        CallbackInfo ci
    ) {
*///?}
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module != null && module.getActive() && !module.wallOverlayEnabled()) {
            ci.cancel();
        }
    }
}
