package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.cosmetic.RiverCape;
//? if >=1.21.11 {
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
//?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wears the River cape by patching the cape texture into the player's render state after
 * vanilla fills in the cape animation. Vanilla's CapeLayer then renders it with full
 * sway. Only applies to players River says have the cape on (self via the module, others
 * via the presence roster), so it never touches unrelated players.
 */
//? if >=1.21.11 {
@Mixin(AvatarRenderer.class)
//?} else {
/*@Mixin(PlayerRenderer.class)
*///?}
public abstract class AvatarCapeMixin {
//? if >=1.21.11 {
    @Inject(method = "extractCapeState", at = @At("TAIL"))
    private void clientcore$applyRiverCape(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
//?} else {
/*    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
        at = @At("TAIL")
    )
    private void clientcore$applyRiverCape(AbstractClientPlayer avatar, PlayerRenderState state, float partialTick, CallbackInfo ci) {
*///?}
        String style = RiverCape.capeStyleFor(avatar.getUUID());
        if (style != null) {
            state.showCape = true;
            state.skin = RiverCape.applyCape(state.skin, style);
        }
    }
}
