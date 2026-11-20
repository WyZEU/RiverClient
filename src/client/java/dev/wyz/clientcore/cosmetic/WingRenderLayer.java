package dev.wyz.clientcore.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
*///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
*///?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
*///?}

//? if >=1.21.10 {
public final class WingRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
//?} elif >=1.21.2 {
/*public final class WingRenderLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
*///?} else {
/*public final class WingRenderLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
*///?}
    @SuppressWarnings({"rawtypes", "unchecked"})
    private WingRenderLayer(RenderLayerParent parent) { super(parent); }

    public static void register() {
//? if >=26.1 {
/*        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
*///?} else {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
//?}
//? if >=1.21.10 {
            if (renderer instanceof AvatarRenderer) helper.register(new WingRenderLayer(renderer));
//?} else {
/*            if (renderer instanceof PlayerRenderer) helper.register(new WingRenderLayer(renderer));
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
//? if >=1.21.2 {
        if (state.isInvisible) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(state.id) instanceof Player player)) return;
//?} else {
/*        if (state.isInvisible()) return;
        Player player = state;
*///?}
        var chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (player.isSpectator() || chest.is(Items.ELYTRA)) return;
        var style = WingCosmetics.forPlayer(player);
        if (style == null) return;
        var mesh = style.getMesh();
        long time = System.currentTimeMillis();

        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        if (!chest.isEmpty()) pose.translate(0, 0, 0.075F);
//? if >=1.21.11 {
        output.submitCustomGeometry(pose, RenderTypes.entityCutout(style.getTexture()),
            (entry, vertices) -> mesh.render(entry, vertices, light, time));
//?} elif >=1.21.10 {
/*        output.submitCustomGeometry(pose, RenderType.entityCutout(style.getTexture()),
            (entry, vertices) -> mesh.render(entry, vertices, light, time));
*///?} else {
/*        mesh.render(pose.last(), output.getBuffer(RenderType.entityCutout(style.getTexture())), light, time);
*///?}
        pose.popPose();
    }
}
