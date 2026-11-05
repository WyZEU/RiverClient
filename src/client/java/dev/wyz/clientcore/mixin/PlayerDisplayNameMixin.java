package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.NameTagModule;
import dev.wyz.clientcore.module.impl.TiersModule;
import dev.wyz.clientcore.module.impl.TotemCounterModule;
import dev.wyz.clientcore.nametag.RiverBadgeState;
//? if <1.21.2 {
/*import dev.wyz.clientcore.cosmetic.RiverCape;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
//? if <1.21.2 {
/*import net.minecraft.world.entity.player.PlayerModelPart;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Prefixes River's name decorations - the PvP tier tag and the River badge - onto a
 * player's display name, which is what the nametag above their head renders. Both are
 * read-only labels: nothing here changes what is sent to the server.
 */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void clientcore$applyRiverNameDecorations(CallbackInfoReturnable<Component> cir) {
        Component base = cir.getReturnValue();
        if (base == null) return;
        Player self = (Player) (Object) this;
        UUID uuid = self.getUUID();

        MutableComponent prefix = Component.empty();
        boolean any = false;

        // Tier first: it says something about the player, while the badge belongs right
        // next to the name it decorates.
        TiersModule tiers = ModuleRegistry.INSTANCE.get("tiers");
        if (tiers != null && tiers.showAboveHeads()) {
            Component tag = tiers.tagFor(uuid);
            if (tag != null) {
                prefix.append(tag).append(Component.literal(" "));
                any = true;
            }
        }

        NameTagModule badge = ModuleRegistry.INSTANCE.get("nametag");
        if (badge != null && badge.getActive() && badge.showRiverBadge() && RiverBadgeState.shouldShow(uuid)) {
            prefix.append(RiverBadgeState.badgeComponent()).append(Component.literal(" "));
            any = true;
        }

        // Totem pops go after the name, where TotemCounter puts them: "Name | -3".
        Component suffix = null;
        TotemCounterModule totems = ModuleRegistry.INSTANCE.get("totem_counter");
        if (totems != null && totems.showOnNametags()) suffix = totems.popSuffix(uuid);

        if (!any && suffix == null) return;
        MutableComponent result = any ? prefix.append(base) : base.copy();
        if (suffix != null) result.append(suffix);
        cir.setReturnValue(result);
    }

//? if <1.21.2 {
/*    // The pre-1.21.2 half of AvatarCapeMixin: a River cape shows even if the player has
    // the vanilla cape toggle off, matching what state.showCape = true does from 1.21.2.
    // Only CapeLayer and ElytraLayer read the CAPE part, so nothing else changes.
    @Inject(method = "isModelPartShown", at = @At("RETURN"), cancellable = true)
    private void clientcore$showRiverCape(PlayerModelPart part, CallbackInfoReturnable<Boolean> cir) {
        if (part != PlayerModelPart.CAPE || cir.getReturnValueZ()) return;
        Player self = (Player) (Object) this;
        if (RiverCape.capeStyleFor(self.getUUID()) != null) cir.setReturnValue(true);
    }
*///?}
}
