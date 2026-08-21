package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.NameTagModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows your own nametag in third person while the Name Tag module is on.
 *
 * 1.21.4's PlayerRenderer does not declare shouldShowName at all - it inherits it from
 * LivingEntityRenderer, and Mixin can only inject into a method the target class actually
 * declares, so this targets LivingEntityRenderer instead. The self-check below already
 * limits any effect to the local player, so targeting the broader class does not change
 * behaviour for other entities.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class AvatarRendererMixin {

    // LivingEntityRenderer<T extends LivingEntity, ...>, so T erases to LivingEntity here.
    // The (Entity, double) overload on this class is the synthetic bridge, hence the
    // explicit descriptor.
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
    private void clientcore$showOwnNameTagInThirdPerson(LivingEntity avatar, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer self = client.player;
        if (self == null || avatar != self) return;

        NameTagModule module = ModuleRegistry.INSTANCE.get("nametag");
        if (module == null || !module.getActive()) return;
        if (client.options.getCameraType().isFirstPerson()) return;

        cir.setReturnValue(true);
    }
}
