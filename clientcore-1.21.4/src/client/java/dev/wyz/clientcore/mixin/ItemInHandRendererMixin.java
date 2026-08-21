package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.CombatVisualsModule;
import dev.wyz.clientcore.module.impl.ViewModelModule;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void clientcore$lowerShieldWhileBlocking(
        AbstractClientPlayer player,
        float partialTick,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equipProgress,
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        int packedLight,
        CallbackInfo ci
    ) {
        CombatVisualsModule module = ModuleRegistry.INSTANCE.get("combat_visuals");
        if (module == null || !module.lowerShield()) return;
        if (!stack.is(Items.SHIELD)) return;
        if (!player.isUsingItem() || player.getUsedItemHand() != hand) return;
        poseStack.translate(0.0F, 0.18F, 0.0F);
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void clientcore$applyViewModel(
        AbstractClientPlayer player,
        float partialTick,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equipProgress,
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        int packedLight,
        CallbackInfo ci
    ) {
        ViewModelModule vm = ModuleRegistry.INSTANCE.get("view_model");
        if (vm == null || !vm.getActive()) return;
        poseStack.translate(vm.offsetX(), vm.offsetY(), vm.offsetZ());
        float scale = vm.scale();
        if (scale != 1.0F) {
            poseStack.scale(scale, scale, scale);
        }
    }
}
