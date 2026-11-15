package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.cosmetic.RiverCape;
//? if >=1.21.10 {
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
//?} elif >=1.21.2 {
/*import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
*///?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21.2 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/**
 * Wears the River cape by patching the cape texture into the player's render state after
 * vanilla fills in the cape animation. Vanilla's CapeLayer then renders it with full
 * sway. Only applies to players River says have the cape on (self via the module, others
 * via the presence roster), so it never touches unrelated players.
 *
 * Before 1.21.2 there is no render state: CapeLayer reads the skin straight off the
 * player every frame, so the cape is patched into AbstractClientPlayer.getSkin() instead.
 * The showCape half of this lives in PlayerDisplayNameMixin there, because
 * isModelPartShown is declared on Player, not AbstractClientPlayer.
 */
//? if >=1.21.10 {
@Mixin(AvatarRenderer.class)
//?} elif >=1.21.2 {
/*@Mixin(PlayerRenderer.class)
*///?} else {
/*@Mixin(AbstractClientPlayer.class)
*///?}
public abstract class AvatarCapeMixin {
//? if >=1.21.10 {
    @Inject(method = "extractCapeState", at = @At("TAIL"))
    private void clientcore$applyRiverCape(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
//?} elif >=1.21.2 {
/*    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
        at = @At("TAIL")
    )
    private void clientcore$applyRiverCape(AbstractClientPlayer avatar, PlayerRenderState state, float partialTick, CallbackInfo ci) {
*///?}
//? if >=1.21.2 {
        String style = RiverCape.capeStyleFor(avatar.getUUID());
        if (style != null) {
            state.showCape = true;
            state.skin = RiverCape.applyCape(state.skin, style);
        }
    }
//?} else {
/*    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void clientcore$applyRiverCape(CallbackInfoReturnable<PlayerSkin> cir) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        String style = RiverCape.capeStyleFor(self.getUUID());
        if (style != null) {
            cir.setReturnValue(RiverCape.applyCape(cir.getReturnValue(), style));
        }
    }
*///?}
}
