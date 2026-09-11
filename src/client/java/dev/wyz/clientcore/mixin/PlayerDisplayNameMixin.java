package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.NameTagModule;
import dev.wyz.clientcore.nametag.RiverBadgeState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Prefixes the River badge onto a player's display name. Purely visual. */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void clientcore$applyRiverBadge(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        NameTagModule module = ModuleRegistry.INSTANCE.get("nametag");
        if (module == null || !module.getActive() || !module.showRiverBadge()) return;

        Component base = cir.getReturnValue();
        if (base == null) return;

        UUID uuid = self.getUUID();
        if (!RiverBadgeState.shouldShow(uuid)) return;

        cir.setReturnValue(Component.empty()
            .append(RiverBadgeState.badgeComponent())
            .append(Component.literal(" "))
            .append(base));
    }
}
