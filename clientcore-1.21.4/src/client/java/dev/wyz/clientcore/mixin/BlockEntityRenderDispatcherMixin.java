package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.wyz.clientcore.perf.BlockEntityCuller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block-entity occlusion culling. 1.21.4 has no render-state extraction step for block
 * entities yet (that came later) - it renders directly, so this cancels the render call
 * itself for block entities River found fully hidden behind blocks.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity> void clientcore$cullHiddenBlockEntities(E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        if (BlockEntityCuller.INSTANCE.shouldCull(Minecraft.getInstance(), blockEntity.getBlockPos())) {
            ci.cancel();
        }
    }
}
