package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.cosmetic.RiverCape;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
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
@Mixin(AvatarRenderer.class)
public abstract class AvatarCapeMixin {
    @Inject(method = "extractCapeState", at = @At("TAIL"))
    private void clientcore$applyRiverCape(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        String style = RiverCape.capeStyleFor(avatar.getUUID());
        if (style != null) {
            state.showCape = true;
            state.skin = RiverCape.applyCape(state.skin, style);
        }
    }
}
