package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.NameTagModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"), cancellable = true)
    private void clientcore$showOwnNameTagInThirdPerson(Avatar avatar, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer self = client.player;
        if (self == null || avatar != self) return;

        NameTagModule module = ModuleRegistry.INSTANCE.get("nametag");
        if (module == null || !module.getActive()) return;
        if (client.options.getCameraType().isFirstPerson()) return;

        cir.setReturnValue(true);
    }
}
