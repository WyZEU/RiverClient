package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.perf.BlockEntityCuller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block-entity occlusion culling. The render-state extraction returns null for block
 * entities River found fully hidden behind blocks, so they're never submitted for
 * rendering. Returning null is the same signal vanilla uses to decline a block entity,
 * so the render loop already handles it; this @Inject never conflicts with other mods.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void clientcore$cullHiddenBlockEntities(E blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay overlay, CallbackInfoReturnable<S> cir) {
        if (BlockEntityCuller.INSTANCE.shouldCull(Minecraft.getInstance(), blockEntity.getBlockPos())) {
            cir.setReturnValue(null);
        }
    }
}
