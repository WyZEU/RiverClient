package dev.wyz.clientcore.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
*///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
//? if >=1.21.11 {
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.RenderType;
*///?}
//? if >=1.21.10 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
//? if >=1.21.10 {
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
*///?} else {
/*import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
*///?}

/** Attaches prototypes to the local player's animated head and body, including inventory previews. */
//? if >=1.21.10 {
public final class AccessoryLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
//?} elif >=1.21.2 {
/*public final class AccessoryLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
*///?} else {
/*public final class AccessoryLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
*///?}
    @SuppressWarnings({"rawtypes", "unchecked"})
    public AccessoryLayer(RenderLayerParent parent) { super(parent); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void register() {
        if (!AccessoryModels.isEnabled()) return;
//? if >=26.1 {
/*        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
*///?} else {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
//?}
//? if >=1.21.10 {
            if (renderer instanceof AvatarRenderer) helper.register(new AccessoryLayer(renderer));
//?} else {
/*            if (renderer instanceof PlayerRenderer) helper.register(new AccessoryLayer(renderer));
*///?}
        });
    }

    @Override
//? if >=1.21.10 {
    public void submit(PoseStack pose, SubmitNodeCollector output, int light, AvatarRenderState state, float yaw, float pitch) {
//?} elif >=1.21.2 {
/*    public void render(PoseStack pose, MultiBufferSource output, int light, PlayerRenderState state, float yaw, float pitch) {
*///?} else {
/*    public void render(PoseStack pose, MultiBufferSource output, int light, AbstractClientPlayer state,
                       float walk, float amount, float partialTick, float age, float yaw, float pitch) {
*///?}
        var player = Minecraft.getInstance().player;
        if (!AccessoryModels.isEnabled() || player == null) return;
//? if >=1.21.2 {
        if (state.id != player.getId() || state.isInvisible) return;
//?} else {
/*        if (state != player || state.isInvisible()) return;
*///?}
        ModelPart head = player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() ? AccessoryModels.headModel() : null;
        ModelPart wings = player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) || player.isSpectator() ? null : AccessoryModels.wingsModel();
        if (head != null) {
            pose.pushPose();
            getParentModel().head.translateAndRotate(pose);
            draw(head, pose, output, light);
            pose.popPose();
        }
        if (wings != null) {
            pose.pushPose();
            getParentModel().body.translateAndRotate(pose);
            if (!player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) pose.translate(0, 0, 0.075F);
            draw(wings, pose, output, light);
            pose.popPose();
        }
    }

//? if >=26.1 {
/*    private void draw(ModelPart model, PoseStack pose, SubmitNodeCollector output, int light) {
        output.submitModelPart(model, pose, RenderTypes.entityCutout(AccessoryModels.texture()), light, OverlayTexture.NO_OVERLAY, null);
    }
*///?} elif >=1.21.11 {
    private void draw(ModelPart model, PoseStack pose, SubmitNodeCollector output, int light) {
        output.submitModelPart(model, pose, RenderTypes.entityCutoutNoCull(AccessoryModels.texture()), light, OverlayTexture.NO_OVERLAY, null);
    }
//?} elif >=1.21.10 {
/*    private void draw(ModelPart model, PoseStack pose, SubmitNodeCollector output, int light) {
        output.submitModelPart(model, pose, RenderType.entityCutoutNoCull(AccessoryModels.texture()), light, OverlayTexture.NO_OVERLAY, null);
    }
*///?} else {
/*    private void draw(ModelPart model, PoseStack pose, MultiBufferSource output, int light) {
        model.render(pose, output.getBuffer(RenderType.entityCutoutNoCull(AccessoryModels.texture())), light, OverlayTexture.NO_OVERLAY);
    }
*///?}
}
