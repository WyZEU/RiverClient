package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.BlockOutlineModule;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Recolours (or hides) the block outline.
 *
 * The draw itself is the same across the whole matrix, only how the colour reaches it
 * changed:
 *
 *   26.x            submitHitOutline, colour as an ARGB int argument
 *   1.21.2-1.21.11  renderHitOutline, colour as an ARGB int argument
 *   1.21/1.21.1     renderHitOutline, colour hardcoded at the renderShape call as four
 *                   floats, so the arguments of that call are rewritten instead
 *
 * Every arm is one argument rewrite - no drawing of our own - so a shader pack or Sodium
 * sees exactly the geometry vanilla submitted, in a different colour.
 */
@Mixin(LevelRenderer.class)
public abstract class BlockOutlineMixin {

//? if >=26.1 {
/*    @Inject(method = "submitHitOutline", at = @At("HEAD"), cancellable = true)
*///?} else {
    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
//?}
    private void clientcore$hideBlockOutline(CallbackInfo ci) {
        BlockOutlineModule module = ModuleRegistry.INSTANCE.get("block_outline");
        if (module != null && module.getActive() && module.hidden()) {
            ci.cancel();
        }
    }

//? if >=26.1 {
/*    @ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int clientcore$recolourBlockOutline(int original) {
        BlockOutlineModule module = ModuleRegistry.INSTANCE.get("block_outline");
        return module != null && module.getActive() ? module.renderColor() : original;
    }
*///?} elif >=1.21.2 {
    @ModifyVariable(method = "renderHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int clientcore$recolourBlockOutline(int original) {
        BlockOutlineModule module = ModuleRegistry.INSTANCE.get("block_outline");
        return module != null && module.getActive() ? module.renderColor() : original;
    }
//?} else {
/*    @ModifyArgs(
        method = "renderHitOutline",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderShape(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/phys/shapes/VoxelShape;DDDFFFF)V"
        )
    )
    private void clientcore$recolourBlockOutline(Args args) {
        BlockOutlineModule module = ModuleRegistry.INSTANCE.get("block_outline");
        if (module == null || !module.getActive()) return;
        int color = module.renderColor();
        args.set(6, ((color >> 16) & 0xFF) / 255.0f);
        args.set(7, ((color >> 8) & 0xFF) / 255.0f);
        args.set(8, (color & 0xFF) / 255.0f);
        args.set(9, ((color >>> 24) & 0xFF) / 255.0f);
    }
*///?}
}
