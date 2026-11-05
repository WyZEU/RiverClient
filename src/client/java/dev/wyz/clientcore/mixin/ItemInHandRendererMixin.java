package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.CombatVisualsModule;
import dev.wyz.clientcore.module.impl.ViewModelModule;
import net.minecraft.client.player.AbstractClientPlayer;
//? if >=26.3 {
/*import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
*///?} else {
import net.minecraft.client.renderer.ItemInHandRenderer;
//?}
//? if >=1.21.10 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=26.3 {
/*@Mixin(FirstPersonHandsAndItemsRenderer.class)
*///?} else {
@Mixin(ItemInHandRenderer.class)
//?}
public abstract class ItemInHandRendererMixin {
//? if >=26.2 {
/*    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
*///?} else {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
//?}
    private void clientcore$lowerShieldWhileBlocking(
//? if >=26.3 {
        /*PlayerRenderState playerState,
        FirstPersonHandsAndItemsRenderState handsState,
*///?} else {
        AbstractClientPlayer player,
//?}
        float partialTick,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equipProgress,
        PoseStack poseStack,
//? if >=1.21.10 {
        SubmitNodeCollector submitNodeCollector,
//?} else {
/*        MultiBufferSource bufferSource,
*///?}
        int packedLight,
        CallbackInfo ci
    ) {
        CombatVisualsModule module = ModuleRegistry.INSTANCE.get("combat_visuals");
        if (module == null || !module.lowerShield()) return;
        if (!stack.is(Items.SHIELD)) return;
//? if >=26.3 {
        /*// The renderer no longer gets the player, only a render state of them.
        AbstractClientPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;
*///?}
        if (!player.isUsingItem() || player.getUsedItemHand() != hand) return;
        poseStack.translate(0.0F, 0.18F, 0.0F);
    }

//? if >=26.2 {
/*    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
*///?} else {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
//?}
    private void clientcore$applyViewModel(
//? if >=26.3 {
        /*PlayerRenderState playerState,
        FirstPersonHandsAndItemsRenderState handsState,
*///?} else {
        AbstractClientPlayer player,
//?}
        float partialTick,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equipProgress,
        PoseStack poseStack,
//? if >=1.21.10 {
        SubmitNodeCollector submitNodeCollector,
//?} else {
/*        MultiBufferSource bufferSource,
*///?}
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
