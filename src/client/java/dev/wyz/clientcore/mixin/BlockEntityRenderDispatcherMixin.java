package dev.wyz.clientcore.mixin;

//? if >=1.21.11 {
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
import dev.wyz.clientcore.perf.BlockEntityCuller;
import net.minecraft.client.Minecraft;
//? if >=1.21.11 {
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
//? if >=1.21.11 {
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
//?} else {
/**///?}
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21.11 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Block-entity occlusion culling. The render-state extraction returns null for block
 * entities River found fully hidden behind blocks, so they're never submitted for
 * rendering. Returning null is the same signal vanilla uses to decline a block entity,
 * so the render loop already handles it; this @Inject never conflicts with other mods.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
//? if >=26.2 {
/*    // 26.2 added isGloballyRendered to the end of the parameter list. An @Inject
    // handler has to mirror the target's descriptor exactly, so the extra argument is
    // carried here even though the culling decision does not read it.
    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void clientcore$cullHiddenBlockEntities(E blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay overlay, boolean isGloballyRendered, CallbackInfoReturnable<S> cir) {
*///?} elif >=1.21.11 {
    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void clientcore$cullHiddenBlockEntities(E blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay overlay, CallbackInfoReturnable<S> cir) {
//?} else {
/*    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity> void clientcore$cullHiddenBlockEntities(E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
*///?}
        if (BlockEntityCuller.INSTANCE.shouldCull(Minecraft.getInstance(), blockEntity.getBlockPos())) {
//? if >=1.21.11 {
            cir.setReturnValue(null);
//?} else {
/*            ci.cancel();
*///?}
        }
    }
}
